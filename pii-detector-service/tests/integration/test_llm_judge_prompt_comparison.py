"""Offline A/B comparison of LLM-judge system-prompt variants (judge isolated).

This is a **decision eval**, not a CI gate (no hard thresholds, gated behind
``@pytest.mark.slow``). It answers one empirical question: does adding an
*asymmetric context rule* to the judge prompt
(:data:`prompt_templates.SYSTEM_PROMPT_CONTEXT_AWARE`) reject more format
look-alikes (a SHA256 tagged ``ETHEREUM_ADDRESS``, a 15-digit order id tagged
``IMEI``) **without** eroding recall on ``canonical_no_clue`` true positives?

Design (deliberately does NOT run the detection pipeline):

* No OpenMed, no scanning. Each finding is fed **directly** to
  :meth:`LLMJudgeValidator.filter` with ``audit_sources={OPENMED}``. The input
  is the committed golden set ``fixtures/judge_findings.jsonl`` (see
  ``fixtures/generate_judge_findings.py``), whose ground truth is known by
  construction. This isolates prompt quality, makes the input deterministic,
  and drops OpenMed's cost/noise.
* One :class:`LLMJudgeValidator` per variant (same config, only ``system_prompt``
  differs), built from :data:`prompt_templates.PROMPT_VARIANTS`.
* Every ``(variant, finding)`` pair is judged; the binary KEEP/DROP outcome is
  scored against the finding's oracle label into :class:`JudgeDecisionMetrics`.

KEEP/DROP semantics mirror production (``LLMJudgeValidator._should_keep``): only
``FALSE_POSITIVE`` drops; ``TRUE_POSITIVE``, ``UNSURE`` and ``None`` (fail-open)
keep. So a FP the judge marks ``UNSURE`` survives and counts as ``fp_kept`` — the
metrics follow the pipeline's effective verdict, not the raw LLM label.

Run (from the service root, LM Studio + Qwen 3.6 Instruct-pure loaded)::

    .venv/Scripts/python.exe -m pytest \
        tests/integration/test_llm_judge_prompt_comparison.py -v -s

Then read ``target/llm-judge-prompt-comparison/report.md``. Decision criterion:
``v2_context_aware`` should raise ``fp_rejection_rate`` on ``ETHEREUM_ADDRESS``
(rejects SHA256) WITHOUT lowering ``judge_recall`` on ``IMEI``
(``canonical_no_clue`` TPs especially). The winning variant can then be promoted
to ``SYSTEM_PROMPT`` in prod.

Caveats:

* The per-``(variant, finding)`` tests accumulate into session-scoped collectors
  drained by the final report test, so do **not** run this file under
  ``pytest-xdist`` (``-n``) — the workers would each see a partial collector.
* Skipped cleanly when LM Studio is unreachable (no hard fail in CI). No
  transformers/OpenMed guard is needed: the pipeline is never instantiated.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.validation.llm_validator import (
    _DEFAULT_BASE_URL,
    _DEFAULT_PREFERRED_MODEL,
    _load_llm_judge_toml_defaults,
    LLMJudgeValidator,
)
from pii_detector.infrastructure.validation.prompt_templates import PROMPT_VARIANTS

log = logging.getLogger("llm_judge_prompt_comparison")


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


# Single source of truth: [llm_judge] in config/detection-settings.toml, env
# overrides for ad-hoc runs. Same precedence as LLMJudgeValidator itself.
BASE_URL = (
    os.getenv("LLM_JUDGE_BASE_URL")
    or _load_llm_judge_toml_defaults().get("base_url")
    or _DEFAULT_BASE_URL
)
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

# Baseline variant the deltas in the report are measured against.
BASELINE_VARIANT = "v1_baseline"

DATASET_PATH = Path(__file__).resolve().parent / "fixtures" / "judge_findings.jsonl"

OUTPUT_DIR = (
    Path(__file__).resolve().parent.parent.parent
    / "target"
    / "llm-judge-prompt-comparison"
)


# ---------------------------------------------------------------------------
# LM Studio guards (mirror test_openmed_realistic_fp_evaluation_with_judge)
# ---------------------------------------------------------------------------


def _find_qwen_base_model(
    ids: Iterable[str], preferred: str = PREFERRED_MODEL
) -> Optional[str]:
    """Pick the model the judge will actually resolve (mirrors prod logic)."""
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
    """Two-step probe (model listing + cheap inference) with a 45 s budget on
    the inference probe to absorb the 35B Qwen 3.6 cold-start."""
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


# No transformers/OpenMed guard: this eval never instantiates the pipeline.
pytestmark = [
    pytest.mark.integration,
    pytest.mark.slow,
    pytest.mark.skipif(
        not _lm_studio_reachable(),
        reason=(
            f"LM Studio not reachable on {BASE_URL} — load Qwen 3.6 35B A3B "
            "with Structured Output enabled to run the prompt comparison."
        ),
    ),
]


# ---------------------------------------------------------------------------
# Dataset record + loader
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class JudgeFinding:
    """One labelled finding from ``judge_findings.jsonl`` — the exact shape the
    judge consumes plus the oracle ``ground_truth`` (never sent to the model)."""

    finding_id: str
    text: str
    pii_type: str
    type_label: str
    start: int
    end: int
    score: float
    document_text: str
    ground_truth: str  # "TP" | "FP"
    note: str

    def to_entity(self) -> PIIEntity:
        """Rebuild the PIIEntity exactly as the OpenMed pipeline would emit it
        (``source=OPENMED`` so the judge audits it)."""
        return PIIEntity(
            text=self.text,
            pii_type=self.pii_type,
            type_label=self.type_label,
            start=self.start,
            end=self.end,
            score=self.score,
            source=DetectorSource.OPENMED,
        )


def _load_findings(path: Path = DATASET_PATH) -> List[JudgeFinding]:
    """Read the JSONL golden set, asserting the byte-offset invariant per line.

    ``document_text[start:end] == text`` must hold for the judge's context
    window to line up with the value — a mismatch would silently feed the model
    the wrong span, so we fail loudly at load time.
    """
    if not path.exists():
        return []
    findings: List[JudgeFinding] = []
    with path.open("r", encoding="utf-8") as fp:
        for line_no, line in enumerate(fp, start=1):
            line = line.strip()
            if not line:
                continue
            raw = json.loads(line)
            finding = JudgeFinding(
                finding_id=raw["finding_id"],
                text=raw["text"],
                pii_type=raw["pii_type"],
                type_label=raw["type_label"],
                start=int(raw["start"]),
                end=int(raw["end"]),
                score=float(raw["score"]),
                document_text=raw["document_text"],
                ground_truth=raw["ground_truth"],
                note=raw.get("note", ""),
            )
            slice_ = finding.document_text[finding.start : finding.end]
            if slice_ != finding.text:
                raise AssertionError(
                    f"line {line_no} ({finding.finding_id}): "
                    f"document_text[{finding.start}:{finding.end}]={slice_!r} "
                    f"!= text={finding.text!r}"
                )
            if finding.ground_truth not in ("TP", "FP"):
                raise AssertionError(
                    f"line {line_no} ({finding.finding_id}): bad ground_truth "
                    f"{finding.ground_truth!r}"
                )
            findings.append(finding)
    return findings


# Loaded at module scope so the per-pair test can be parametrised. The dataset
# is committed, so this normally yields 36 findings; an empty list (file absent)
# degrades to a skipped suite rather than a collection error.
_FINDINGS: List[JudgeFinding] = _load_findings()
_VARIANTS: List[str] = list(PROMPT_VARIANTS.keys())


# ---------------------------------------------------------------------------
# Judge decision metrics (confusion matrix on KEEP/DROP — NOT DualMetrics)
# ---------------------------------------------------------------------------


@dataclass
class JudgeDecisionMetrics:
    """Confusion matrix of the judge's binary verdict for one variant (or one
    ``(variant, type)`` cell). Ground truth is carried by the finding; there is
    no IoU matching and no OpenMed baseline here."""

    variant: str
    tp_kept: int = 0  # TP the judge keeps (good)
    tp_dropped: int = 0  # TP the judge rejects (TP-loss — kills recall)
    fp_kept: int = 0  # FP the judge keeps (FP-survivor — kills precision)
    fp_dropped: int = 0  # FP the judge rejects (good)

    # (finding_id, value, snippet) debug samples.
    dropped_tp_samples: List[Tuple[str, str, str]] = field(default_factory=list)
    surviving_fp_samples: List[Tuple[str, str, str]] = field(default_factory=list)

    def record(self, finding: JudgeFinding, decision: str) -> None:
        snippet = _snippet(finding)
        if finding.ground_truth == "TP":
            if decision == "KEEP":
                self.tp_kept += 1
            else:
                self.tp_dropped += 1
                if len(self.dropped_tp_samples) < 20:
                    self.dropped_tp_samples.append(
                        (finding.finding_id, finding.text, snippet)
                    )
        else:  # FP
            if decision == "DROP":
                self.fp_dropped += 1
            else:
                self.fp_kept += 1
                if len(self.surviving_fp_samples) < 20:
                    self.surviving_fp_samples.append(
                        (finding.finding_id, finding.text, snippet)
                    )

    @property
    def total(self) -> int:
        return self.tp_kept + self.tp_dropped + self.fp_kept + self.fp_dropped

    @property
    def judge_precision(self) -> float:
        """Of the findings kept, the share that are genuine TP."""
        d = self.tp_kept + self.fp_kept
        return self.tp_kept / d if d > 0 else 0.0

    @property
    def judge_recall(self) -> float:
        """Of the genuine TP, the share the judge kept."""
        d = self.tp_kept + self.tp_dropped
        return self.tp_kept / d if d > 0 else 0.0

    @property
    def fp_rejection_rate(self) -> float:
        """Of the genuine FP, the share the judge correctly dropped."""
        d = self.fp_dropped + self.fp_kept
        return self.fp_dropped / d if d > 0 else 0.0


def _snippet(finding: JudgeFinding, radius: int = 60) -> str:
    a = max(0, finding.start - radius)
    b = min(len(finding.document_text), finding.end + radius)
    s = finding.document_text[a:b].replace("\n", " ").replace("\r", " ").strip()
    return s[:200]


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def judge_validators():
    """One real ``LLMJudgeValidator`` per prompt variant, OpenMed-audited.

    Same config across variants — only ``system_prompt`` differs — so the only
    variable under test is the prompt. All validators are shut down in teardown.
    """
    validators: Dict[str, LLMJudgeValidator] = {}
    for name, prompt in PROMPT_VARIANTS.items():
        log.info("[setup] building validator for variant=%s on %s", name, BASE_URL)
        validators[name] = LLMJudgeValidator(
            base_url=BASE_URL,
            audit_sources={DetectorSource.OPENMED},
            fail_open=True,
            max_workers=4,
            system_prompt=prompt,
        )
    try:
        yield validators
    finally:
        for validator in validators.values():
            validator.shutdown()


@pytest.fixture(scope="session")
def metrics_by_variant() -> Dict[str, JudgeDecisionMetrics]:
    """Aggregated (all types) confusion matrix per variant."""
    return {}


@pytest.fixture(scope="session")
def metrics_by_variant_type() -> Dict[Tuple[str, str], JudgeDecisionMetrics]:
    """Per ``(variant, pii_type)`` breakdown."""
    return {}


@pytest.fixture(scope="session", autouse=True)
def _ensure_output_dir() -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    return OUTPUT_DIR


# ---------------------------------------------------------------------------
# Per-(variant, finding) judging
# ---------------------------------------------------------------------------


def _judge_finding(validator: LLMJudgeValidator, finding: JudgeFinding) -> str:
    """Return ``"KEEP"`` or ``"DROP"`` for one finding under one validator.

    Mirrors prod exactly: a non-empty ``filter`` result means the entity
    survived (TRUE_POSITIVE / UNSURE / fail-open) → KEEP; an empty result means
    the judge returned FALSE_POSITIVE → DROP.
    """
    kept = validator.filter(finding.document_text, [finding.to_entity()])
    return "KEEP" if kept else "DROP"


@pytest.mark.parametrize("variant", _VARIANTS)
@pytest.mark.parametrize(
    "finding", _FINDINGS, ids=[f.finding_id for f in _FINDINGS]
)
def test_Should_RecordJudgeDecision_When_VariantJudgesFinding(
    variant: str,
    finding: JudgeFinding,
    judge_validators: Dict[str, LLMJudgeValidator],
    metrics_by_variant: Dict[str, JudgeDecisionMetrics],
    metrics_by_variant_type: Dict[Tuple[str, str], JudgeDecisionMetrics],
) -> None:
    """Judge one finding under one variant and record the KEEP/DROP outcome.

    No threshold assertion — this is a decision eval, the report carries the
    verdict. Bad outcomes (a TP dropped, a FP kept) are logged at WARNING so
    they surface in the ``-s`` stream without cracking open the report.
    """
    decision = _judge_finding(judge_validators[variant], finding)

    metrics_by_variant.setdefault(
        variant, JudgeDecisionMetrics(variant=variant)
    ).record(finding, decision)
    metrics_by_variant_type.setdefault(
        (variant, finding.pii_type), JudgeDecisionMetrics(variant=variant)
    ).record(finding, decision)

    bad = (finding.ground_truth == "TP" and decision == "DROP") or (
        finding.ground_truth == "FP" and decision == "KEEP"
    )
    emit = log.warning if bad else log.info
    flag = ""
    if finding.ground_truth == "TP" and decision == "DROP":
        flag = "  <-- TP-loss"
    elif finding.ground_truth == "FP" and decision == "KEEP":
        flag = "  <-- FP-survivor"
    emit(
        "[%s] %s | gt=%s -> %s | %r%s",
        variant,
        finding.finding_id,
        finding.ground_truth,
        decision,
        finding.text,
        flag,
    )


def test_Should_HaveBalancedDataset_When_GoldenSetLoaded() -> None:
    """Sanity guard on the golden set itself (cheap, no LM Studio needed beyond
    the module-level skip): findings exist and TP/FP are balanced per type, so
    precision/recall are not skewed by the mix."""
    assert _FINDINGS, f"No findings loaded from {DATASET_PATH}"
    per_type: Dict[str, Dict[str, int]] = {}
    for finding in _FINDINGS:
        bucket = per_type.setdefault(finding.pii_type, {"TP": 0, "FP": 0})
        bucket[finding.ground_truth] += 1
    for pii_type, counts in per_type.items():
        assert counts["TP"] == counts["FP"], (
            f"{pii_type}: unbalanced TP={counts['TP']} FP={counts['FP']} — the "
            f"comparison would be biased. Regenerate the dataset."
        )


# ---------------------------------------------------------------------------
# Aggregate report (variant comparison, deltas vs baseline)
# ---------------------------------------------------------------------------


def test_Should_ProduceComparisonReport_When_AllVariantFindingPairsJudged(
    metrics_by_variant: Dict[str, JudgeDecisionMetrics],
    metrics_by_variant_type: Dict[Tuple[str, str], JudgeDecisionMetrics],
) -> None:
    """Consolidate the per-pair decisions into ``report.md`` + ``metrics.json``
    under ``target/llm-judge-prompt-comparison/``."""
    if not metrics_by_variant:
        pytest.skip("No decisions recorded; run the full file instead.")

    metrics_path = OUTPUT_DIR / "metrics.json"
    report_path = OUTPUT_DIR / "report.md"
    _write_metrics_json(metrics_path, metrics_by_variant, metrics_by_variant_type)
    _write_markdown_report(
        report_path, metrics_by_variant, metrics_by_variant_type
    )

    for variant in _VARIANTS:
        m = metrics_by_variant.get(variant)
        if m is None:
            continue
        log.info(
            "[%s] precision=%.3f recall=%.3f fp_rejection=%.3f "
            "(tp_kept=%d tp_dropped=%d fp_kept=%d fp_dropped=%d)",
            variant,
            m.judge_precision,
            m.judge_recall,
            m.fp_rejection_rate,
            m.tp_kept,
            m.tp_dropped,
            m.fp_kept,
            m.fp_dropped,
        )
    log.info("[aggregate] report written to %s", report_path)
    log.info("[aggregate] metrics written to %s", metrics_path)


def _metrics_payload(m: JudgeDecisionMetrics) -> Dict[str, object]:
    return {
        "tp_kept": m.tp_kept,
        "tp_dropped": m.tp_dropped,
        "fp_kept": m.fp_kept,
        "fp_dropped": m.fp_dropped,
        "judge_precision": round(m.judge_precision, 4),
        "judge_recall": round(m.judge_recall, 4),
        "fp_rejection_rate": round(m.fp_rejection_rate, 4),
        "dropped_tp_samples": [
            {"finding_id": fid, "value": val, "snippet": ctx}
            for fid, val, ctx in m.dropped_tp_samples
        ],
        "surviving_fp_samples": [
            {"finding_id": fid, "value": val, "snippet": ctx}
            for fid, val, ctx in m.surviving_fp_samples
        ],
    }


def _write_metrics_json(
    path: Path,
    by_variant: Dict[str, JudgeDecisionMetrics],
    by_variant_type: Dict[Tuple[str, str], JudgeDecisionMetrics],
) -> None:
    payload: Dict[str, object] = {}
    for variant in _VARIANTS:
        m = by_variant.get(variant)
        if m is None:
            continue
        per_type = {
            pii_type: _metrics_payload(mt)
            for (var, pii_type), mt in sorted(by_variant_type.items())
            if var == variant
        }
        payload[variant] = {"overall": _metrics_payload(m), "per_type": per_type}
    with path.open("w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, ensure_ascii=False)


def _delta(value: float, baseline: Optional[float]) -> str:
    if baseline is None:
        return "—"
    return f"{value - baseline:+.3f}"


def _write_markdown_report(
    path: Path,
    by_variant: Dict[str, JudgeDecisionMetrics],
    by_variant_type: Dict[Tuple[str, str], JudgeDecisionMetrics],
) -> None:
    baseline = by_variant.get(BASELINE_VARIANT)
    base_recall = baseline.judge_recall if baseline else None
    base_fp_rej = baseline.fp_rejection_rate if baseline else None

    lines: List[str] = []
    lines.append("# LLM judge — system-prompt comparison\n")
    lines.append(
        "- Judge isolated: findings fed directly to "
        "`LLMJudgeValidator.filter(audit_sources={OPENMED}, fail_open=True)`, "
        "no OpenMed scan.\n"
        f"- Golden set: `{DATASET_PATH.name}` "
        f"({len(_FINDINGS)} findings, balanced TP/FP per type).\n"
        "- KEEP/DROP follow prod: only `FALSE_POSITIVE` drops; `UNSURE` / "
        "fail-open keep (a kept FP is an `fp_kept` survivor).\n"
        f"- Deltas are vs `{BASELINE_VARIANT}`."
    )
    lines.append("")

    lines.append("## Overall per variant (all types)")
    lines.append("")
    lines.append(
        "| Variant | N | TP kept | TP dropped | FP kept | FP dropped "
        "| Precision | Recall | FP-reject | Δ Recall | Δ FP-reject |"
    )
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for variant in _VARIANTS:
        m = by_variant.get(variant)
        if m is None:
            lines.append(f"| {variant} | — | — | — | — | — | — | — | — | — | — |")
            continue
        lines.append(
            f"| {variant} | {m.total} | {m.tp_kept} | {m.tp_dropped} "
            f"| {m.fp_kept} | {m.fp_dropped} | {m.judge_precision:.3f} "
            f"| {m.judge_recall:.3f} | {m.fp_rejection_rate:.3f} "
            f"| {_delta(m.judge_recall, base_recall)} "
            f"| {_delta(m.fp_rejection_rate, base_fp_rej)} |"
        )
    lines.append("")

    lines.append("## Per type, per variant")
    lines.append("")
    pii_types = sorted({pt for (_v, pt) in by_variant_type})
    for pii_type in pii_types:
        lines.append(f"### {pii_type}")
        lines.append("")
        lines.append(
            "| Variant | TP kept | TP dropped | FP kept | FP dropped "
            "| Recall | FP-reject |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---:|")
        type_base = by_variant_type.get((BASELINE_VARIANT, pii_type))
        for variant in _VARIANTS:
            mt = by_variant_type.get((variant, pii_type))
            if mt is None:
                continue
            recall_cell = f"{mt.judge_recall:.3f}"
            fprej_cell = f"{mt.fp_rejection_rate:.3f}"
            if type_base is not None and variant != BASELINE_VARIANT:
                recall_cell += f" ({_delta(mt.judge_recall, type_base.judge_recall)})"
                fprej_cell += (
                    f" ({_delta(mt.fp_rejection_rate, type_base.fp_rejection_rate)})"
                )
            lines.append(
                f"| {variant} | {mt.tp_kept} | {mt.tp_dropped} | {mt.fp_kept} "
                f"| {mt.fp_dropped} | {recall_cell} | {fprej_cell} |"
            )
        lines.append("")

    lines.append("## TP dropped (recall killers) per variant")
    lines.append("")
    for variant in _VARIANTS:
        m = by_variant.get(variant)
        if m is None or not m.dropped_tp_samples:
            continue
        lines.append(f"### {variant}")
        lines.append("")
        for fid, val, ctx in m.dropped_tp_samples:
            lines.append(f"- `{fid}` → dropped TP `{val}` in: `{ctx}`")
        lines.append("")

    lines.append("## FP survivors (precision killers) per variant")
    lines.append("")
    for variant in _VARIANTS:
        m = by_variant.get(variant)
        if m is None or not m.surviving_fp_samples:
            continue
        lines.append(f"### {variant}")
        lines.append("")
        for fid, val, ctx in m.surviving_fp_samples:
            lines.append(f"- `{fid}` → kept FP `{val}` in: `{ctx}`")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
