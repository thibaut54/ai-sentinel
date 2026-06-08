"""Realistic FP/FN evaluation for OpenMed **with the LLM judge** applied as a
post-processing step. Companion of
:mod:`tests.integration.test_openmed_realistic_fp_evaluation` — same 12 PII
types, same 6 evaluation axes, same fixtures — but instead of stopping at the
raw OpenMed verdict, every detection is submitted to ``LLMJudgeValidator`` with
``audit_sources={DetectorSource.OPENMED}`` and the outcome is recomputed.

Goal: prove the user's expectation that **the LLM judge brings the OpenMed FP
rate from the 20-80% range down to near-zero** while preserving recall on real
positives.

Methodology
-----------

1. **Reuse the baseline fixtures**. Loaded via
   :func:`test_openmed_realistic_fp_evaluation._load_cases_for_type`, so the
   per-type cases, expected spans, axes and languages are byte-identical to
   the baseline.

2. **Run OpenMed live** on each case (no replay from ``findings.jsonl`` —
   replay would be cheaper but would only re-test the judge against the *last*
   captured run, not the current OpenMed weights).

3. **Submit detections to the judge in batch per case**. For each case the
   detections tagged as ``source=DetectorSource.OPENMED`` are passed to
   :meth:`LLMJudgeValidator.filter`. The returned subset is the post-judge
   verdict; rejected entities are dropped.

4. **Recompute TP/FP/FN with IoU >= 0.5 matching after the judge**. This uses
   the exact same matching logic as the baseline so the two reports can be
   compared apples-to-apples.

5. **Hard assertion: FP_rate_after_judge <= 0.10 per type** (operator-accepted
   budget after empirical calibration) AND **recall_after_judge >=
   recall_baseline - 0.10** (the judge must not erode recall by more than 10
   percentage points). A WARNING is logged when 0.05 < FP_rate <= 0.10 so
   regressions toward the upper bound surface in the CI output.

Skips
-----

* The whole module is skipped when ``transformers < 5.0`` (OpenMed unsupported).
* Each judge-dependent test is skipped when LM Studio is unreachable on
  ``LLM_JUDGE_BASE_URL`` (no hard fail in CI without the model).

Cost note
---------

* 12 types × ~20 cases = ~240 OpenMed scans (warm CPU, ~100 ms each = 30 s).
* Average ~3 OpenMed detections per case = ~720 judge calls.
* Qwen 3.6 35B at ``max_workers=4`` parallelism ≈ 1 verdict/s sustained.
* Total runtime: roughly **10-15 minutes** on a warm LM Studio. Plan
  accordingly — this test is gated behind ``@pytest.mark.slow``.

Outputs (written to ``target/openmed-fp-eval-with-judge/``):

* ``findings.jsonl``  — per-detection record with baseline + judged verdicts
* ``metrics.json``    — aggregated metrics per type, baseline vs judged
* ``report.md``       — markdown comparison: precision / recall / FP rate
                        before and after judge, per type, per axis

Spec references
---------------

* ``_bmad-output/planning-artifacts/llm-judge-qwen-spec.md`` §1.4 (was backlog:
  ``llm_judge_sources``), §2.5 (rule extended via ``audit_sources``), §5.1
  (acceptance: FP rate < 15% globally — this test goes further and targets
  < 5% per type for OpenMed specifically).
"""

from __future__ import annotations

import json
import logging
import os
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

import httpx
import pytest

# Reuse the baseline test's fixture loaders, matching utilities and data
# classes verbatim — we want identical case sets so the comparison is fair.
from tests.integration.test_openmed_realistic_fp_evaluation import (
    EVALUATED_TYPES,
    ExpectedSpan,
    GLOBAL_THRESHOLD,
    IOU_MATCH_THRESHOLD,
    OUTPUT_DIR as BASELINE_OUTPUT_DIR,
    PII_TYPE_TO_LABEL,
    PII_TYPE_TO_THRESHOLD,
    VALID_AXES,
    VALID_LANGUAGES,
    _load_cases_for_type,
    iou,
    match_findings,
)

log = logging.getLogger("openmed_fp_eval_with_judge")


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


# Hard cap on the FP rate after the judge has post-processed OpenMed
# detections. Tighter than the baseline 0.20 because the judge is supposed to
# strip out most false positives (cf. spec §5.1).
#
# Empirical calibration (2026-05-26): on the first live run against Qwen 3.6
# 35B A3B, a 0.05 cap was too strict — types like CVV legitimately keep a
# single ambiguous survivor (e.g. a 3-digit value next to "CVV:") which the
# judge cannot disambiguate from semantics alone. 0.10 is the operator-
# accepted budget: "moins de 5%, voire 10%, c'est acceptable". Below 0.05 is
# the goal, between 0.05 and 0.10 logs a WARNING but still passes.
MAX_FP_RATE_WITH_JUDGE = 0.10
WARN_FP_RATE_WITH_JUDGE = 0.05

# Recall after the judge must stay close to the OpenMed baseline. The judge
# is allowed to drop **at most** this many percentage points (e.g. baseline
# recall 0.80 → judged recall must be >= 0.70).
MAX_RECALL_DEGRADATION = 0.10

# Absolute TP-loss budget: the judge is allowed to drop at most this many
# baseline TPs per type. Catches regressions on types where the baseline TP
# count is small enough that a single TP-loss is < MAX_RECALL_DEGRADATION in
# percentage-point terms but still concerning in absolute terms.
MAX_ABS_TP_LOSS = 1

# Absolute floor (mirrors the baseline) so a type with 0% recall doesn't
# slip through just because the judge didn't erode it. Default applies to
# every type; ``MIN_RECALL_BY_TYPE`` overrides per type when a detector is
# known to under-perform (e.g. OpenMed on IMEI / adversarial formatting).
MIN_RECALL = 0.50
MIN_RECALL_BY_TYPE: Dict[str, float] = {
    pii_type: MIN_RECALL for pii_type in PII_TYPE_TO_LABEL
}

# LM Studio config — same defaults as the rest of the live IT suite.
# Single source of truth: [llm_judge].base_url in
# config/detection-settings.toml. Env var overrides for ad-hoc runs.
from pii_detector.infrastructure.validation.llm_validator import (  # noqa: E402
    _DEFAULT_BASE_URL,
    _DEFAULT_PREFERRED_MODEL,
    _load_llm_judge_toml_defaults,
)

BASE_URL = (
    os.getenv("LLM_JUDGE_BASE_URL")
    or _load_llm_judge_toml_defaults().get("base_url")
    or _DEFAULT_BASE_URL
)

# The model the judge will actually resolve at runtime (env > TOML > default),
# same precedence as LLMJudgeValidator. The reachability probe below warms
# THIS model so the cold-start it absorbs is the one the suite then hits.
PREFERRED_MODEL = (
    os.getenv("LLM_JUDGE_PREFERRED_MODEL")
    or _load_llm_judge_toml_defaults().get("preferred_model")
    or _DEFAULT_PREFERRED_MODEL
)

_FINETUNE_BLACKLIST: Tuple[str, ...] = (
    "uncensored",
    "heretic",
    "distilled",
    "aggressive",
    "finetune",
)

# Output dir is sibling of the baseline's so report.md diffs are trivial.
OUTPUT_DIR = (
    Path(__file__).resolve().parent.parent.parent
    / "target"
    / "openmed-fp-eval-with-judge"
)


# ---------------------------------------------------------------------------
# Transformers / LM Studio guards
# ---------------------------------------------------------------------------


def _transformers_supports_openmed() -> bool:
    try:
        import transformers

        major = int(transformers.__version__.split(".", 1)[0])
        return major >= 5
    except Exception:
        return False


def _find_qwen_base_model(
    ids: Iterable[str], preferred: str = PREFERRED_MODEL
) -> str | None:
    """Pick the model the judge will actually use.

    Mirrors prod resolution (``llm_validator._resolve_model_id``): exact match
    on the configured ``preferred`` first, then a fuzzy ``qwen3.6`` + ``a3b``
    fallback minus fine-tune markers. Honouring ``preferred`` keeps the probe
    warming the SAME checkpoint the ``LLMJudgeValidator`` resolves (e.g.
    ``qwen3.6-35b-a3b-instruct-pure``) instead of whatever lands first in
    ``/v1/models``.
    """
    ids = list(ids)
    if preferred in ids:
        return preferred
    for model_id in ids:
        lower = model_id.lower()
        if "qwen3.6" not in lower or "a3b" not in lower:
            continue
        if any(token in lower for token in _FINETUNE_BLACKLIST):
            continue
        return model_id
    return None


def _lm_studio_reachable() -> bool:
    """Mirror :func:`test_llm_judge_pipeline._lm_studio_reachable`.

    Two-step probe (model listing + cheap inference) with a 45 s budget on
    the inference probe to absorb the 35B Qwen 3.6 cold-start.
    """
    try:
        response = httpx.get(f"{BASE_URL}/models", timeout=15.0)
        response.raise_for_status()
        ids = [m["id"] for m in response.json().get("data", [])]
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        log.warning(
            "[LM-STUDIO-PROBE] /v1/models failed on %s (%s: %s); suite skipped",
            BASE_URL,
            exc.__class__.__name__,
            exc,
        )
        return False

    model_id = _find_qwen_base_model(ids)
    if model_id is None:
        log.warning(
            "[LM-STUDIO-PROBE] no Qwen 3.6 A3B base model on %s (available=%s)",
            BASE_URL,
            ids,
        )
        return False

    try:
        probe = httpx.post(
            f"{BASE_URL}/chat/completions",
            json={
                "model": model_id,
                "messages": [{"role": "user", "content": "ping"}],
                "max_tokens": 1,
                "temperature": 0.0,
                "stream": False,
            },
            timeout=45.0,
        )
    except httpx.HTTPError as exc:
        log.warning(
            "[LM-STUDIO-PROBE] inference probe failed (%s: %s) — model listed "
            "but not warm. Click 'Load' on qwen/qwen3.6-35b-a3b. Skipped.",
            exc.__class__.__name__,
            exc,
        )
        return False
    return probe.status_code == 200


pytestmark = [
    pytest.mark.integration,
    pytest.mark.slow,
    pytest.mark.skipif(
        not _transformers_supports_openmed(),
        reason="OpenMed requires transformers >= 5.0.",
    ),
    pytest.mark.skipif(
        not _lm_studio_reachable(),
        reason=(
            f"LM Studio not reachable on {BASE_URL} — load Qwen 3.6 35B A3B "
            "with Structured Output enabled to run the judge."
        ),
    ),
]


# ---------------------------------------------------------------------------
# Dual aggregate (baseline vs judged)
# ---------------------------------------------------------------------------


@dataclass
class DualMetrics:
    """Counts both the raw OpenMed verdict and the judged verdict for a type.

    Recall is computed only against ``expected_spans`` (FN cannot be invented
    by the judge — it can only drop detections). The judge's job is to lower
    FP without raising FN; in practice it may erode recall by a small margin
    when it (rightly or wrongly) marks a true positive as ``FALSE_POSITIVE``.
    """

    pii_type: str
    cases: int = 0

    # Baseline = raw OpenMed before judge
    baseline_tp: int = 0
    baseline_fp: int = 0
    baseline_fn: int = 0

    # Judged = OpenMed → judge.filter(...)
    judged_tp: int = 0
    judged_fp: int = 0
    judged_fn: int = 0

    # Per-axis (judged only, baseline already covered by sibling test)
    per_axis: Dict[str, Dict[str, int]] = field(
        default_factory=lambda: defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
    )

    # Sample of FP that survived the judge — most useful debug aid.
    surviving_fp_samples: List[Tuple[str, str, str]] = field(default_factory=list)

    # Sample of TP that the judge wrongly rejected — surfaces recall regressions.
    dropped_tp_samples: List[Tuple[str, str, str]] = field(default_factory=list)

    def record(
        self,
        case_id: str,
        axis: str,
        text: str,
        baseline_detected: List[Tuple[int, int, float, str]],
        judged_detected: List[Tuple[int, int, float, str]],
        expected_spans: Tuple[ExpectedSpan, ...],
    ) -> None:
        self.cases += 1

        b_tp, b_fp, b_fn = match_findings(
            [(s, e) for s, e, _, _ in baseline_detected], list(expected_spans)
        )
        j_tp, j_fp, j_fn = match_findings(
            [(s, e) for s, e, _, _ in judged_detected], list(expected_spans)
        )
        self.baseline_tp += b_tp
        self.baseline_fp += b_fp
        self.baseline_fn += b_fn
        self.judged_tp += j_tp
        self.judged_fp += j_fp
        self.judged_fn += j_fn

        bucket = self.per_axis[axis]
        bucket["tp"] += j_tp
        bucket["fp"] += j_fp
        bucket["fn"] += j_fn

        if j_fp > 0 and len(self.surviving_fp_samples) < 5:
            for start, end, _score, value in judged_detected:
                if not self._is_tp_span(expected_spans, start, end):
                    snippet = self._snippet(text, start, end)
                    self.surviving_fp_samples.append((case_id, value, snippet))
                    if len(self.surviving_fp_samples) >= 5:
                        break

        # Surface every TP dropped by the judge (recall-cap killer).
        if b_tp > j_tp and len(self.dropped_tp_samples) < 5:
            judged_set = {(s, e) for s, e, _, _ in judged_detected}
            for start, end, _score, value in baseline_detected:
                if (start, end) in judged_set:
                    continue
                if self._is_tp_span(expected_spans, start, end):
                    snippet = self._snippet(text, start, end)
                    self.dropped_tp_samples.append((case_id, value, snippet))
                    if len(self.dropped_tp_samples) >= 5:
                        break

    @staticmethod
    def _is_tp_span(
        expected: Tuple[ExpectedSpan, ...], start: int, end: int
    ) -> bool:
        return any(
            iou(start, end, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
            for ex in expected
        )

    @staticmethod
    def _snippet(text: str, start: int, end: int, radius: int = 60) -> str:
        a = max(0, start - radius)
        b = min(len(text), end + radius)
        s = text[a:b].replace("\n", " ").replace("\r", " ").strip()
        return s[:200]

    @property
    def baseline_fp_rate(self) -> float:
        d = self.baseline_tp + self.baseline_fp
        return self.baseline_fp / d if d > 0 else 0.0

    @property
    def judged_fp_rate(self) -> float:
        d = self.judged_tp + self.judged_fp
        return self.judged_fp / d if d > 0 else 0.0

    @property
    def baseline_recall(self) -> float:
        d = self.baseline_tp + self.baseline_fn
        return self.baseline_tp / d if d > 0 else 0.0

    @property
    def judged_recall(self) -> float:
        d = self.judged_tp + self.judged_fn
        return self.judged_tp / d if d > 0 else 0.0

    @property
    def recall_degradation(self) -> float:
        """Percentage points lost between baseline and judged recall."""
        return max(0.0, self.baseline_recall - self.judged_recall)

    @property
    def baseline_precision(self) -> float:
        d = self.baseline_tp + self.baseline_fp
        return self.baseline_tp / d if d > 0 else 0.0

    @property
    def judged_precision(self) -> float:
        d = self.judged_tp + self.judged_fp
        return self.judged_tp / d if d > 0 else 0.0

    @property
    def baseline_f1(self) -> float:
        return self._f1(self.baseline_precision, self.baseline_recall)

    @property
    def judged_f1(self) -> float:
        return self._f1(self.judged_precision, self.judged_recall)

    @staticmethod
    def _f1(precision: float, recall: float) -> float:
        d = precision + recall
        return 2 * precision * recall / d if d > 0 else 0.0


# ---------------------------------------------------------------------------
# Session fixtures (heavy: OpenMed model + judge validator)
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def openmed_detector():
    """Same fixture as the baseline test — load the 2.7 GB model once."""
    from pii_detector.infrastructure.detector.openmed_detector import (
        OpenMedDetector,
    )

    log.info("[setup] loading OpenMedDetector (2.7 GB model)")
    detector = OpenMedDetector()
    t0 = time.time()
    detector.load_model()
    log.info("[setup] OpenMed ready in %.2fs", time.time() - t0)
    return detector


# Descriptive judge-facing labels (PIIEntity.type_label) per type. The LLM
# judge reads `type_label` alongside `pii_type` to assess semantics; when the
# raw enum name is misleading we override it here. ACCOUNT_NAME is what OpenMed
# tags ACCOUNTNAME on: customer-related references (order/invoice/case ids),
# which are secrets we WANT to keep — so we spell that out for the judge.
#
# EMPIRICAL CEILING (documented, intentionally NOT worked around — 2026-05-27):
#   OpenMed only emits ACCOUNTNAME on alphanumeric customer refs that carry a
#   contextual clue (ORD-/INV-/CLI- with a keyword nearby). Bare REF-/CUST-
#   forms and clue-less references are tagged USERNAME/ZIPCODE/etc. or missed
#   entirely, capping baseline recall at ~0.36. That is a DETECTOR limitation:
#   the judge cannot recover a span OpenMed never surfaced. The descriptive
#   type_label below fixes the JUDGE side (FP_rate -> 0, recall preserved within
#   budget); MaintainAbsoluteRecallFloor[ACCOUNT_NAME] stays red on purpose to
#   flag that a complementary detector (regex/GLiNER) is needed upstream.
JUDGE_TYPE_LABELS: Dict[str, str] = {
    "ACCOUNT_NAME": (
        "Identifiant ou reference alphanumerique propre a un client : numero "
        "de commande, de facture, de dossier, de contrat ou de compte client. "
        "Toute reference de cette nature est une donnee client sensible, quel "
        "que soit son prefixe (ORD-, INV-, REF-, CUST-, ...)."
    ),
}


@pytest.fixture(scope="session")
def pii_type_configs() -> Dict[str, Dict[str, object]]:
    """Same OpenMed runtime configs as the baseline test, plus the descriptive
    judge-facing ``type_label`` overrides from :data:`JUDGE_TYPE_LABELS`."""
    configs: Dict[str, Dict[str, object]] = {}
    for pii_type, label, threshold in EVALUATED_TYPES:
        configs[f"OPENMED:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "OPENMED",
            "category": "EVAL",
            "country_code": None,
            "detector_label": label,
            "type_label": JUDGE_TYPE_LABELS.get(pii_type),
        }
    return configs


@pytest.fixture(scope="session")
def judge_validator():
    """Real ``LLMJudgeValidator`` configured to audit OpenMed entities."""
    from pii_detector.domain.entity.detector_source import DetectorSource
    from pii_detector.infrastructure.validation.llm_validator import (
        LLMJudgeValidator,
    )

    log.info(
        "[setup] building LLMJudgeValidator(audit_sources={OPENMED}) on %s",
        BASE_URL,
    )
    validator = LLMJudgeValidator(
        base_url=BASE_URL,
        audit_sources={DetectorSource.OPENMED},
        fail_open=True,
        max_workers=4,
    )
    try:
        yield validator
    finally:
        validator.shutdown()


@pytest.fixture(scope="session")
def dual_metrics_collector() -> Dict[str, DualMetrics]:
    """Shared session-scope collector populated by per-type tests, drained by
    the final aggregate report."""
    return {}


@pytest.fixture(scope="session", autouse=True)
def _ensure_output_dir() -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    # Truncate per-run; the baseline test does the same, this keeps the two
    # files lifecycle-coupled.
    findings = OUTPUT_DIR / "findings.jsonl"
    if findings.exists():
        findings.unlink()
    return OUTPUT_DIR


# ---------------------------------------------------------------------------
# Per-case helper (OpenMed scan + judge filter, both timed)
# ---------------------------------------------------------------------------


def _detected_spans_from_entities(
    text: str, entities, pii_type: str
) -> List[Tuple[int, int, float, str]]:
    """Convert detector output to the ``(start, end, score, value)`` tuples
    used everywhere else in this file."""
    return [
        (e.start, e.end, float(e.score), text[e.start : e.end])
        for e in entities
        if e.pii_type == pii_type
    ]


def _scan_and_judge_case(
    openmed_detector,
    judge_validator,
    configs: Dict,
    case,
) -> Tuple[
    List[Tuple[int, int, float, str]],
    List[Tuple[int, int, float, str]],
]:
    """Return ``(baseline_detected, judged_detected)`` for one case.

    The judge is invoked only on entities whose ``pii_type`` matches the case
    under test (OpenMed may surface other types if their labels are enabled,
    but those don't count for this case and we don't pay an LLM call for
    them).

    Emits a per-case INFO log line for every span listing: action taken
    (KEPT/DROP), ground-truth verdict (TP/FP), value, span, score. Makes the
    diff between OpenMed output and the post-judge result inspectable from
    the pytest log alone — no need to crack open ``findings.jsonl`` to
    understand why a case failed.
    """
    raw_entities = openmed_detector.detect_pii(
        case.text,
        threshold=GLOBAL_THRESHOLD,
        pii_type_configs=configs,
    )
    baseline_entities = [e for e in raw_entities if e.pii_type == case.pii_type]

    judged_entities = (
        judge_validator.filter(case.text, baseline_entities)
        if baseline_entities
        else []
    )

    baseline_detected = _detected_spans_from_entities(
        case.text, baseline_entities, case.pii_type
    )
    judged_detected = _detected_spans_from_entities(
        case.text, judged_entities, case.pii_type
    )

    _log_case_breakdown(case, baseline_detected, judged_detected)

    return baseline_detected, judged_detected


def _log_case_breakdown(
    case,
    baseline_detected: List[Tuple[int, int, float, str]],
    judged_detected: List[Tuple[int, int, float, str]],
) -> None:
    """Log every baseline span with KEPT/DROP + TP/FP + missed expected spans.

    Format (one log line per span, prefixed with ``[<pii_type>/<case_id>]``)::

        [CVV/CVV_long_context_fr_01] KEPT | FP | '421'      @1234:1237 score=0.91
        [CVV/CVV_long_context_fr_01] DROP | FP | '4242 4242 4242' @100:115 score=0.95
        [CVV/CVV_long_context_fr_01] KEPT | TP | '599'      @2000:2003 score=0.88
        [CVV/CVV_long_context_fr_01] MISS | FN | '<expected>' @500:503

    Reading rules:
      - ``KEPT | TP`` -> good: real PII still flagged after the judge
      - ``KEPT | FP`` -> bad: false positive that survived the judge (a
        ``FP-survivor`` — the FP_rate-cap killer)
      - ``DROP | FP`` -> good: false positive correctly removed by the judge
      - ``DROP | TP`` -> bad: real PII the judge wrongly rejected (a
        ``TP-loss`` — the recall-cap killer)
      - ``MISS | FN`` -> OpenMed missed this expected span (judge has no
        bearing on this — the model itself can't detect it)
    """
    judged_spans = {(s, e) for s, e, _, _ in judged_detected}
    prefix = f"[{case.pii_type}/{case.case_id}]"

    if not baseline_detected and not case.expected_spans:
        log.info("%s no detection, no expected span — clean case", prefix)
        return

    for start, end, score, value in baseline_detected:
        is_tp = any(
            iou(start, end, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
            for ex in case.expected_spans
        )
        ground_truth = "TP" if is_tp else "FP"
        action = "KEPT" if (start, end) in judged_spans else "DROP"

        # Surface bad outcomes (FP-survivor, TP-loss) at WARNING so they
        # jump out of a "everything green" log scroll.
        is_bad = (action == "KEPT" and ground_truth == "FP") or (
            action == "DROP" and ground_truth == "TP"
        )
        emit = log.warning if is_bad else log.info
        flag = "  <-- FP-survivor" if (action == "KEPT" and ground_truth == "FP") else (
            "  <-- TP-loss" if (action == "DROP" and ground_truth == "TP") else ""
        )
        emit(
            "%s %s | %s | %r @%d:%d score=%.2f%s",
            prefix,
            action,
            ground_truth,
            value,
            start,
            end,
            score,
            flag,
        )

    for ex in case.expected_spans:
        matched = any(
            iou(ds, de, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
            for ds, de, _, _ in baseline_detected
        )
        if not matched:
            log.info(
                "%s MISS | FN | %r @%d:%d (OpenMed did not detect this expected span)",
                prefix,
                ex.value,
                ex.start,
                ex.end,
            )


def _append_findings_jsonl(
    case,
    baseline_detected: List[Tuple[int, int, float, str]],
    judged_detected: List[Tuple[int, int, float, str]],
) -> None:
    """Append every (baseline-detected, judged-detected) pair to
    ``findings.jsonl`` so a downstream script can diff the two."""
    path = OUTPUT_DIR / "findings.jsonl"
    judged_spans = {(s, e) for s, e, _, _ in judged_detected}
    with path.open("a", encoding="utf-8") as fp:
        for start, end, score, value in baseline_detected:
            is_tp = any(
                iou(start, end, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
                for ex in case.expected_spans
            )
            kept_by_judge = (start, end) in judged_spans
            fp.write(
                json.dumps(
                    {
                        "case_id": case.case_id,
                        "pii_type": case.pii_type,
                        "axis": case.axis,
                        "language": case.language,
                        "start": start,
                        "end": end,
                        "score": score,
                        "value": value,
                        "baseline_verdict": "TP" if is_tp else "FP",
                        "kept_by_judge": kept_by_judge,
                        # Effective judged verdict: a kept FP stays an FP, a
                        # rejected TP becomes an FN (judge dropped a real
                        # positive — penalises recall).
                        "judged_verdict": (
                            ("TP" if is_tp else "FP")
                            if kept_by_judge
                            else ("FN_by_judge" if is_tp else "rejected_FP")
                        ),
                    }
                )
                + "\n"
            )
        for ex in case.expected_spans:
            matched_in_baseline = any(
                iou(ds, de, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
                for ds, de, _, _ in baseline_detected
            )
            if not matched_in_baseline:
                fp.write(
                    json.dumps(
                        {
                            "case_id": case.case_id,
                            "pii_type": case.pii_type,
                            "axis": case.axis,
                            "language": case.language,
                            "start": ex.start,
                            "end": ex.end,
                            "value": ex.value,
                            "baseline_verdict": "FN",
                            "kept_by_judge": False,
                            "judged_verdict": "FN",
                        }
                    )
                    + "\n"
                )


# ---------------------------------------------------------------------------
# Parametrized per-type tests (judge enabled)
# ---------------------------------------------------------------------------


_TYPE_PARAMS = [
    pytest.param(pii_type, id=pii_type) for pii_type in PII_TYPE_TO_LABEL.keys()
]


def _compute_dual_metrics(
    pii_type: str,
    openmed_detector,
    judge_validator,
    pii_type_configs: Dict,
    dual_metrics_collector: Dict[str, DualMetrics],
) -> DualMetrics:
    """Run OpenMed + judge over the type's cases and memoize the result.

    The OpenMed scan and judge calls are expensive (~tens of seconds and
    dozens of LLM round-trips per type), so we cache the computed
    :class:`DualMetrics` on the session-scoped ``dual_metrics_collector``.
    All three per-type tests below share the same metrics for a given type,
    which keeps the runtime constant whether one or three assertions run.
    """
    cached = dual_metrics_collector.get(pii_type)
    if cached is not None:
        return cached

    cases = _load_cases_for_type(pii_type)
    assert cases, f"No cases loaded for {pii_type}"

    metrics = DualMetrics(pii_type=pii_type)
    log.info("[%s] running %d cases through OpenMed + judge", pii_type, len(cases))
    t0 = time.time()
    for case in cases:
        baseline_detected, judged_detected = _scan_and_judge_case(
            openmed_detector, judge_validator, pii_type_configs, case
        )
        metrics.record(
            case_id=case.case_id,
            axis=case.axis,
            text=case.text,
            baseline_detected=baseline_detected,
            judged_detected=judged_detected,
            expected_spans=case.expected_spans,
        )
        _append_findings_jsonl(case, baseline_detected, judged_detected)
    elapsed_s = time.time() - t0

    log.info(
        "[%s] cases=%d baseline: tp=%d fp=%d fn=%d FP_rate=%.3f recall=%.3f "
        "| judged: tp=%d fp=%d fn=%d FP_rate=%.3f recall=%.3f (%.1fs)",
        pii_type,
        metrics.cases,
        metrics.baseline_tp,
        metrics.baseline_fp,
        metrics.baseline_fn,
        metrics.baseline_fp_rate,
        metrics.baseline_recall,
        metrics.judged_tp,
        metrics.judged_fp,
        metrics.judged_fn,
        metrics.judged_fp_rate,
        metrics.judged_recall,
        elapsed_s,
    )

    if metrics.surviving_fp_samples:
        log.warning(
            "[%s] FP-survivors (judge kept these false positives): %s",
            pii_type,
            "; ".join(
                f"{cid}: {val!r} in '{ctx[:80]}...'"
                for cid, val, ctx in metrics.surviving_fp_samples
            ),
        )
    if metrics.dropped_tp_samples:
        log.warning(
            "[%s] TP-loss (judge rejected these true positives): %s",
            pii_type,
            "; ".join(
                f"{cid}: {val!r} in '{ctx[:80]}...'"
                for cid, val, ctx in metrics.dropped_tp_samples
            ),
        )

    dual_metrics_collector[pii_type] = metrics
    return metrics


@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_KeepFpRateUnderCap_When_JudgeIsAppliedPostOpenMed(
    openmed_detector,
    judge_validator,
    pii_type_configs,
    dual_metrics_collector,
    pii_type,
):
    """Per-type precision check: post-judge FP rate must stay <= 10%.

    Failure here means the judge is too lenient — it kept false positives
    that OpenMed surfaced. Typical root causes:
      * System prompt accepts ``UNSURE`` verdicts (e.g. SHA hashes classed
        as look-alikes the judge can't confidently reject).
      * Structured Output schema lets the judge skip a verdict on edge
        cases → fail_open keeps the entity.

    Inspect the ``surviving_fp_samples`` listed in the assertion message and
    tighten the system prompt for the offending axis.
    """
    metrics = _compute_dual_metrics(
        pii_type,
        openmed_detector,
        judge_validator,
        pii_type_configs,
        dual_metrics_collector,
    )

    # Soft warning: judged FP rate above the ideal 5% target but still within
    # the hard 10% cap. Surfaces drift in CI before it crosses the cap.
    if WARN_FP_RATE_WITH_JUDGE < metrics.judged_fp_rate <= MAX_FP_RATE_WITH_JUDGE:
        log.warning(
            "[%s] judged FP_rate=%.3f exceeds ideal target %.2f (still under "
            "the hard cap %.2f). Survivors: %s",
            pii_type,
            metrics.judged_fp_rate,
            WARN_FP_RATE_WITH_JUDGE,
            MAX_FP_RATE_WITH_JUDGE,
            "; ".join(
                f"{cid}: {val!r}"
                for cid, val, _ in metrics.surviving_fp_samples[:3]
            ),
        )

    assert metrics.judged_fp_rate <= MAX_FP_RATE_WITH_JUDGE, (
        f"[{pii_type}] judged FP_rate={metrics.judged_fp_rate:.3f} exceeds cap "
        f"{MAX_FP_RATE_WITH_JUDGE:.2f}. Baseline was {metrics.baseline_fp_rate:.3f}; "
        f"the judge cleared "
        f"{metrics.baseline_fp - metrics.judged_fp}/{metrics.baseline_fp} FPs "
        f"but {metrics.judged_fp} survived. Survivors: "
        + "; ".join(
            f"{cid}: {val!r} in '{ctx[:80]}...'"
            for cid, val, ctx in metrics.surviving_fp_samples[:3]
        )
    )


@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_PreserveBaselineTp_When_JudgeIsAppliedPostOpenMed(
    openmed_detector,
    judge_validator,
    pii_type_configs,
    dual_metrics_collector,
    pii_type,
):
    """Per-type TP-preservation check: judge must not erode baseline TPs.

    Two complementary guards:
      * Percentage-point degradation: ``recall_degradation <= 10pp``.
        Standard non-regression budget.
      * Absolute TP-loss: ``baseline_tp - judged_tp <= MAX_ABS_TP_LOSS``.
        Catches the edge case where baseline_tp is small (e.g. 3) — a
        single TP-loss is only 33pp and would pass the relative check, but
        is still a real precision-killer worth flagging.

    Failure means the judge is too aggressive — it rejected a real PII
    that OpenMed correctly found. Inspect ``dropped_tp_samples`` and revisit
    the discrimination hints for this type.
    """
    metrics = _compute_dual_metrics(
        pii_type,
        openmed_detector,
        judge_validator,
        pii_type_configs,
        dual_metrics_collector,
    )

    abs_tp_loss = metrics.baseline_tp - metrics.judged_tp

    assert metrics.recall_degradation <= MAX_RECALL_DEGRADATION, (
        f"[{pii_type}] judge eroded recall by "
        f"{metrics.recall_degradation:.3f}pp "
        f"({metrics.baseline_recall:.3f} -> {metrics.judged_recall:.3f}), "
        f"exceeding the {MAX_RECALL_DEGRADATION:.2f}pp budget. "
        f"Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r} in '{ctx[:80]}...'"
            for cid, val, ctx in metrics.dropped_tp_samples[:3]
        )
    )

    assert abs_tp_loss <= MAX_ABS_TP_LOSS, (
        f"[{pii_type}] judge dropped {abs_tp_loss} true positives in absolute "
        f"terms ({metrics.baseline_tp} -> {metrics.judged_tp}), exceeding the "
        f"hard cap of {MAX_ABS_TP_LOSS}. Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r} in '{ctx[:80]}...'"
            for cid, val, ctx in metrics.dropped_tp_samples[:3]
        )
    )


@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_MaintainAbsoluteRecallFloor_When_JudgeIsAppliedPostOpenMed(
    openmed_detector,
    judge_validator,
    pii_type_configs,
    dual_metrics_collector,
    pii_type,
):
    """Per-type absolute-recall floor: judged recall must clear
    ``MIN_RECALL_BY_TYPE[pii_type]`` (default :data:`MIN_RECALL`).

    Distinct from the TP-preservation test: that one measures the **delta**
    between OpenMed alone and OpenMed+judge. This one measures the
    **absolute** recall of the full pipeline. A type can degrade by 0pp
    (judge drops nothing) but still flunk this check if OpenMed itself
    couldn't reach the floor — surfacing detector-level gaps rather than
    judge-level ones.
    """
    metrics = _compute_dual_metrics(
        pii_type,
        openmed_detector,
        judge_validator,
        pii_type_configs,
        dual_metrics_collector,
    )

    floor = MIN_RECALL_BY_TYPE.get(pii_type, MIN_RECALL)
    assert metrics.judged_recall >= floor, (
        f"[{pii_type}] judged recall={metrics.judged_recall:.3f} below floor "
        f"{floor:.2f}. baseline_recall={metrics.baseline_recall:.3f}, "
        f"degradation={metrics.recall_degradation:.3f}. "
        f"If OpenMed itself can't clear the floor (baseline already low), "
        f"lower MIN_RECALL_BY_TYPE[{pii_type!r}] or add a complementary "
        f"detector upstream of the judge."
    )


# ---------------------------------------------------------------------------
# Aggregate report (baseline vs judged side-by-side)
# ---------------------------------------------------------------------------


def test_Should_ProduceJudgedAggregatedReport_When_AllPerTypeTestsCompleted(
    dual_metrics_collector,
):
    """Consolidate per-type dual metrics into ``report.md`` and
    ``metrics.json`` under ``target/openmed-fp-eval-with-judge/``."""
    if not dual_metrics_collector:
        pytest.skip("No per-type results recorded; run the full file instead.")

    metrics_path = OUTPUT_DIR / "metrics.json"
    report_path = OUTPUT_DIR / "report.md"

    _write_metrics_json(metrics_path, dual_metrics_collector)
    _write_markdown_report(report_path, dual_metrics_collector)

    log.info("[aggregate] report written to %s", report_path)
    log.info("[aggregate] metrics written to %s", metrics_path)


def _write_metrics_json(
    path: Path, collector: Dict[str, DualMetrics]
) -> None:
    payload: Dict[str, dict] = {}
    for pii_type, m in collector.items():
        payload[pii_type] = {
            "cases": m.cases,
            "baseline": {
                "tp": m.baseline_tp,
                "fp": m.baseline_fp,
                "fn": m.baseline_fn,
                "fp_rate": round(m.baseline_fp_rate, 4),
                "recall": round(m.baseline_recall, 4),
            },
            "judged": {
                "tp": m.judged_tp,
                "fp": m.judged_fp,
                "fn": m.judged_fn,
                "fp_rate": round(m.judged_fp_rate, 4),
                "recall": round(m.judged_recall, 4),
            },
            "recall_degradation": round(m.recall_degradation, 4),
            "per_axis_judged": {ax: dict(d) for ax, d in m.per_axis.items()},
        }
    with path.open("w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, ensure_ascii=False)


def _write_markdown_report(
    path: Path, collector: Dict[str, DualMetrics]
) -> None:
    lines: List[str] = []
    lines.append("# OpenMed + LLM judge — FP/FN evaluation\n")
    lines.append(
        f"- Pass criteria: judged `FP_rate <= {MAX_FP_RATE_WITH_JUDGE:.2f}` "
        f"(ideal `<= {WARN_FP_RATE_WITH_JUDGE:.2f}` — over that range emits a "
        f"WARN but still passes), recall degradation "
        f"`<= {MAX_RECALL_DEGRADATION:.2f}pp` vs baseline, "
        f"absolute recall `>= {MIN_RECALL:.2f}` per type.\n"
        f"- Baseline numbers come from raw OpenMed (same fixtures as "
        f"`{BASELINE_OUTPUT_DIR.name}/report.md`).\n"
        f"- Judged numbers come from running every OpenMed detection through "
        f"`LLMJudgeValidator(audit_sources={{OPENMED}}, fail_open=True)`."
    )
    lines.append("")

    lines.append("## Side-by-side per type")
    lines.append("")
    lines.append(
        "| PII type | Cases | Baseline FP rate | Judged FP rate | Δ FP rate "
        "| Baseline recall | Judged recall | Δ recall | Verdict |"
    )
    lines.append(
        "|---|---:|---:|---:|---:|---:|---:|---:|---|"
    )
    for pii_type in PII_TYPE_TO_LABEL.keys():
        m = collector.get(pii_type)
        if m is None:
            lines.append(
                f"| {pii_type} | — | — | — | — | — | — | — | NOT RUN |"
            )
            continue
        delta_fp = m.judged_fp_rate - m.baseline_fp_rate
        delta_recall = m.judged_recall - m.baseline_recall
        verdict_parts: List[str] = []
        if m.judged_fp_rate > MAX_FP_RATE_WITH_JUDGE:
            verdict_parts.append(f"FP_rate>{MAX_FP_RATE_WITH_JUDGE:.2f}")
        if m.recall_degradation > MAX_RECALL_DEGRADATION:
            verdict_parts.append(
                f"recall_loss>{MAX_RECALL_DEGRADATION:.2f}pp"
            )
        if m.judged_recall < MIN_RECALL:
            verdict_parts.append(f"recall<{MIN_RECALL:.2f}")
        if verdict_parts:
            verdict = "FAIL (" + ", ".join(verdict_parts) + ")"
        elif m.judged_fp_rate > WARN_FP_RATE_WITH_JUDGE:
            verdict = "PASS (WARN)"
        else:
            verdict = "PASS"
        lines.append(
            f"| {pii_type} | {m.cases} "
            f"| {m.baseline_fp_rate:.3f} | {m.judged_fp_rate:.3f} | {delta_fp:+.3f} "
            f"| {m.baseline_recall:.3f} | {m.judged_recall:.3f} | {delta_recall:+.3f} "
            f"| {verdict} |"
        )
    lines.append("")

    lines.append("## Per-axis judged FP rate")
    lines.append("")
    lines.append(
        "Helps tell whether residual FPs come from look_alikes (judge can't "
        "tell hashes from crypto addresses) vs explicit_negatives (judge "
        "still flags type keyword without a value)."
    )
    lines.append("")
    for pii_type, m in collector.items():
        lines.append(f"### {pii_type}")
        lines.append("")
        lines.append("| Axis | TP | FP | FN | FP rate |")
        lines.append("|---|---:|---:|---:|---:|")
        for axis in sorted(VALID_AXES):
            d = m.per_axis.get(axis, {"tp": 0, "fp": 0, "fn": 0})
            tp, fp_, fn = d["tp"], d["fp"], d["fn"]
            fp_rate = fp_ / (fp_ + tp) if (fp_ + tp) > 0 else 0.0
            lines.append(
                f"| {axis} | {tp} | {fp_} | {fn} | {fp_rate:.3f} |"
            )
        lines.append("")

    lines.append("## Surviving false positives (judge didn't catch)")
    lines.append("")
    lines.append(
        "Samples of FPs that OpenMed produced AND the judge wrongly kept. "
        "These are the patterns to surface in the next prompt iteration."
    )
    lines.append("")
    for pii_type, m in collector.items():
        if not m.surviving_fp_samples:
            continue
        lines.append(f"### {pii_type}")
        lines.append("")
        for case_id, value, ctx in m.surviving_fp_samples:
            lines.append(
                f"- `{case_id}` → kept FP `{value}` in: `{ctx}`"
            )
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
