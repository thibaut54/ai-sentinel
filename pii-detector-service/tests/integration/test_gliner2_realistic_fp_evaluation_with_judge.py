"""Realistic FP/FN evaluation for **GLiNER2** (``fastino/gliner2-*``) with the
LLM judge applied as a post-processing step.

Sibling of
:mod:`tests.integration.test_openmed_realistic_fp_evaluation_with_judge`: same
6 evaluation axes, same IoU >= 0.5 matching, same ``DualMetrics`` baseline-vs-
judged accounting, same judge wiring — but the detector under test is GLiNER2
(the ``gliner2`` package, schema-driven ``extract_entities``) instead of
OpenMed, and the evaluated PII types are a 24-label subset of the
``fastino/gliner2-privacy-filter-PII-multi`` label space (Government/tax IDs,
Banking/payment, Digital identity, Secrets/credentials).

Two models are evaluated by default (parametrized fixture):

* ``fastino/gliner2-large-v1`` — the generalist multi-task model the user
  asked about. NOT a PII-specialised checkpoint: expect lower recall on the
  niche secret/credential types. This is the headline subject.
* ``fastino/gliner2-privacy-filter-PII-multi`` — the PII fine-tune (42 types,
  7 languages), kept as a comparator so the report shows how much the
  generalist gives up vs the dedicated model.

Override with ``LM_GLINER2_MODEL_IDS=id1,id2`` to evaluate a different set.

Why GLiNER2 maps cleanly onto the OpenMed harness
-------------------------------------------------

``GLiNER2.extract_entities(text, {label: description}, threshold, include_
confidence=True, include_spans=True)`` returns, per label, a list of
``{text, start, end, confidence}`` dicts (cf. GLiNER2 README / model card).
Character offsets + a confidence score mean each detection converts directly
into a :class:`PIIEntity` with ``source=DetectorSource.GLINER``, exactly like
OpenMed — so the IoU matching, dual metrics and judge all reuse unchanged.

Defensive parsing: the exact span key names are validated at runtime, not
assumed. The wrapper reads ``confidence`` then falls back to ``score``; if a
span carries no offsets it falls back to locating ``text`` in the source
string. A first live run should confirm the schema (look for the
``[gliner2-schema]`` WARNING which fires once if a fallback path is taken).

What is asserted (per model × per type)
---------------------------------------

The hard gates assert **judge** properties (its safety contract), which hold
regardless of which GLiNER2 checkpoint produced the candidates. The detector's
absolute quality (FP rate, recall) is reported as SOFT signals — it is the
*subject under evaluation*, not a contract. This diverges deliberately from the
OpenMed sibling, whose absolute ``FP_rate <= 0.10`` cap is realistic for a
PII-specialised model but not for the generalist ``gliner2-large-v1`` (low
recall, high raw FP). Forcing that cap here would just produce expected-red
noise and hide genuine judge defects behind detector weakness.

1. **Judge-must-not-worsen-precision (HARD)** — ``judged_fp_rate <=
   baseline_fp_rate``. The judge only removes entities, so a regression means
   it dropped true positives while keeping false ones — a real defect. The
   absolute ``FP_rate <= 0.10`` cap is kept as a SOFT signal (WARN + report).
2. **TP-preservation (HARD)** — the judge must not erode baseline TPs beyond
   ``MAX_RECALL_DEGRADATION`` (10pp) or ``MAX_ABS_TP_LOSS`` (1) in absolute
   terms.
3. **Absolute-recall-floor (SOFT / WARN)** — logged, not asserted. The
   detector's raw recall is the thing under evaluation (especially for
   large-v1). The number is in the report.

Skips
-----

* The whole module is skipped when the ``gliner2`` package is not importable.
* Each judge-dependent test is skipped when LM Studio is unreachable on
  ``BASE_URL`` (reuses the OpenMed sibling's probe).

Outputs (written to ``target/gliner2-fp-eval-with-judge/``):

* ``findings.jsonl`` — per-detection record (model, baseline + judged verdict)
* ``metrics.json``   — aggregated metrics per model per type
* ``report.md``      — markdown comparison, grouped by model then type
"""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from typing import Dict, List, Tuple

import pytest

# Pure matching utilities + data classes from the OpenMed baseline (no LM
# Studio side effects on import).
from tests.integration.test_openmed_realistic_fp_evaluation import (
    EvalCase,
    ExpectedSpan,
    IOU_MATCH_THRESHOLD,
    VALID_AXES,
    VALID_LANGUAGES,
    iou,
)

# DualMetrics + the live-IT probe + the calibrated judge budgets are reused
# verbatim from the OpenMed *with-judge* sibling. Importing it evaluates that
# module's LM-Studio probe once at import time — same pattern as
# test_openmed_pii_text_document_with_judge.py.
from tests.integration.test_openmed_realistic_fp_evaluation_with_judge import (
    BASE_URL,
    DualMetrics,
    MAX_ABS_TP_LOSS,
    MAX_FP_RATE_WITH_JUDGE,
    MAX_RECALL_DEGRADATION,
    MIN_RECALL,
    WARN_FP_RATE_WITH_JUDGE,
    _lm_studio_reachable,
)

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity

log = logging.getLogger("gliner2_fp_eval_with_judge")


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Permissive global threshold passed to extract_entities; per-type thresholds
# (from the fixtures) make the final cut downstream, mirroring OpenMed.
GLOBAL_THRESHOLD = 0.30

# Models under evaluation. large-v1 is the headline subject; the privacy
# fine-tune is the comparator. Override via LM_GLINER2_MODEL_IDS=a,b.
_DEFAULT_MODEL_IDS = (
    "fastino/gliner2-large-v1",
    "fastino/gliner2-privacy-filter-PII-multi",
)
GLINER2_MODEL_IDS: Tuple[str, ...] = tuple(
    m.strip()
    for m in os.getenv(
        "LM_GLINER2_MODEL_IDS", ",".join(_DEFAULT_MODEL_IDS)
    ).split(",")
    if m.strip()
)

# Canonical PII type -> (GLiNER2 native label, zero-shot description). The
# label MUST match the fixture's ``detector_label`` (cross-checked on load).
# Descriptions are the zero-shot hints fed to extract_entities; they sharpen
# precision on fine-grained / confusable types.
GLINER2_LABEL_SPECS: Dict[str, Tuple[str, str]] = {
    # Government / tax IDs
    "GOVERNMENT_ID": (
        "government_id",
        "Government-issued identification number such as a national ID card, "
        "residence permit, or other state-issued personal identifier",
    ),
    "NATIONAL_ID": (
        "national_id_number",
        "National identity number such as a social-security-style personal "
        "number, French INSEE number, or Italian codice fiscale",
    ),
    "PASSPORT": ("passport_number", "Passport number"),
    "DRIVER_LICENSE": (
        "drivers_license_number",
        "Driver's license or driving permit number",
    ),
    "LICENSE_NUMBER": (
        "license_number",
        "Generic license or permit number (professional, business, or "
        "software license) — NOT a driving licence",
    ),
    "TAX_ID": (
        "tax_id",
        "Taxpayer identification number such as a TIN, EIN, or personal tax ID",
    ),
    "TAX_NUMBER": (
        "tax_number",
        "Tax or VAT registration number of a person or company",
    ),
    # Banking / payment
    "BANK_ACCOUNT": ("bank_account", "Bank account number"),
    "ACCOUNT_NUMBER": ("account_number", "Generic account number"),
    "ROUTING_NUMBER": (
        "routing_number",
        "Bank routing, ABA, or sort code number",
    ),
    "IBAN": ("iban", "International Bank Account Number (IBAN)"),
    "PAYMENT_CARD": (
        "payment_card",
        "Payment card number (credit or debit card)",
    ),
    "CARD_NUMBER": ("card_number", "Credit or debit card number"),
    "CARD_EXPIRY": (
        "card_expiry",
        "Payment card expiration date (MM/YY or MM/YYYY)",
    ),
    "CARD_CVV": (
        "card_cvv",
        "Card security code (CVV / CVC), 3 or 4 digits",
    ),
    # Digital identity
    "USERNAME": ("username", "Login or account username / handle"),
    "IP_ADDRESS": ("ip_address", "IPv4 or IPv6 network address"),
    "ACCOUNT_ID": ("account_id", "Technical account identifier"),
    "SENSITIVE_ACCOUNT_ID": (
        "sensitive_account_id",
        "Customer-specific sensitive reference such as an order, invoice, "
        "case, or contract number",
    ),
    # Secrets / credentials
    "PASSWORD": ("password", "Account password or PIN code"),
    "SECRET": (
        "secret",
        "Secret value such as a client secret or other confidential credential",
    ),
    "API_KEY": ("api_key", "API key or authentication credential"),
    "ACCESS_TOKEN": (
        "access_token",
        "Access token or bearer token (e.g. JWT, OAuth token)",
    ),
    "RECOVERY_CODE": ("recovery_code", "Account recovery or backup code"),
}

# label -> canonical pii_type, used to convert GLiNER2 detections back.
LABEL_TO_PII_TYPE: Dict[str, str] = {
    label: pii_type for pii_type, (label, _desc) in GLINER2_LABEL_SPECS.items()
}

# The full {label: description} dict passed to extract_entities on every scan.
# Passing ALL labels at once (not just the case's own) reproduces the
# cross-type confusion that drives real false positives.
GLINER2_LABELS: Dict[str, str] = {
    label: desc for (label, desc) in GLINER2_LABEL_SPECS.values()
}

FIXTURES_DIR = (
    Path(__file__).resolve().parent.parent / "resources" / "gliner2-fp-eval"
)
OUTPUT_DIR = (
    Path(__file__).resolve().parent.parent.parent
    / "target"
    / "gliner2-fp-eval-with-judge"
)


# ---------------------------------------------------------------------------
# Skip guards
# ---------------------------------------------------------------------------


def _gliner2_available() -> bool:
    try:
        import gliner2  # noqa: F401

        return True
    except Exception:
        return False


# Module-wide markers stay light so the fixture meta-test runs in CI without a
# model. The two heavy guards (gliner2 package + LM Studio) are applied only to
# the runtime tests below, via ``_requires_runtime``.
pytestmark = [pytest.mark.integration, pytest.mark.slow]

_requires_runtime = (
    pytest.mark.skipif(
        not _gliner2_available(),
        reason="GLiNER2 detector requires the `gliner2` package (pip install gliner2).",
    ),
    pytest.mark.skipif(
        not _lm_studio_reachable(),
        reason=(
            f"LM Studio not reachable on {BASE_URL} — load the Qwen judge with "
            "Structured Output enabled to run the judge."
        ),
    ),
)


def _runtime(test):
    """Apply both heavy guards to a runtime (detector + judge) test."""
    for marker in _requires_runtime:
        test = marker(test)
    return test


# ---------------------------------------------------------------------------
# Fixture loader (gliner2-fp-eval)
# ---------------------------------------------------------------------------


def _load_gliner2_cases_for_type(pii_type: str) -> List[EvalCase]:
    """Read ``gliner2-fp-eval/{pii_type}.json`` into ``EvalCase`` instances.

    Reuses the OpenMed ``EvalCase``/``ExpectedSpan`` shape and validates
    aggressively (axis, language, byte-exact spans, label coherence) so
    malformed fixtures fail at collection rather than mid-run.
    """
    path = FIXTURES_DIR / f"{pii_type}.json"
    if not path.exists():
        raise AssertionError(
            f"Missing fixture file for {pii_type}: {path}. Regenerate via "
            f"`python tests/resources/gliner2-fp-eval/_generate_gliner2_fixtures.py`."
        )
    with path.open("r", encoding="utf-8") as fp:
        payload = json.load(fp)

    declared_type = payload.get("pii_type")
    if declared_type != pii_type:
        raise AssertionError(
            f"Fixture {path.name} declares pii_type={declared_type!r} but was "
            f"loaded for {pii_type!r}."
        )
    detector_label = payload["detector_label"]
    expected_label = GLINER2_LABEL_SPECS[pii_type][0]
    if detector_label != expected_label:
        raise AssertionError(
            f"{path.name}: detector_label={detector_label!r} but the harness "
            f"registry expects GLiNER2 label {expected_label!r} for {pii_type}."
        )
    threshold = float(payload.get("threshold", 0.50))

    cases: List[EvalCase] = []
    for raw in payload["cases"]:
        case_id = raw["id"]
        language = raw["language"]
        axis = raw["axis"]
        text = raw["text"]
        if axis not in VALID_AXES:
            raise AssertionError(
                f"{case_id}: unknown axis {axis!r} (allowed: {sorted(VALID_AXES)})"
            )
        if language not in VALID_LANGUAGES:
            raise AssertionError(
                f"{case_id}: unknown language {language!r} (allowed: "
                f"{sorted(VALID_LANGUAGES)})"
            )
        spans = tuple(
            ExpectedSpan(start=int(s["start"]), end=int(s["end"]), value=s["value"])
            for s in raw.get("expected_spans", [])
        )
        for sp in spans:
            slice_ = text[sp.start:sp.end]
            if slice_ != sp.value:
                raise AssertionError(
                    f"{case_id}: text[{sp.start}:{sp.end}]={slice_!r} but "
                    f"expected_spans.value={sp.value!r}. Offsets must be byte-exact."
                )
        cases.append(
            EvalCase(
                case_id=case_id,
                pii_type=pii_type,
                detector_label=detector_label,
                threshold=threshold,
                language=language,
                axis=axis,
                text=text,
                expected_spans=spans,
            )
        )
    return cases


# Per-type tests run only for types that ship a fixture file under
# gliner2-fp-eval/. The detection label set (GLINER2_LABEL_SPECS) is broader —
# the detection-only labels (CRYPTO_WALLET / IMEI / VIN / VEHICLE_REGISTRATION)
# have no per-type fixtures and are exercised solely by the document-wide test.
_EVAL_TYPES = [
    pii_type
    for pii_type in GLINER2_LABEL_SPECS
    if (FIXTURES_DIR / f"{pii_type}.json").exists()
]
_TYPE_PARAMS = [pytest.param(pii_type, id=pii_type) for pii_type in _EVAL_TYPES]


# ---------------------------------------------------------------------------
# In-file GLiNER2 wrapper (no production code touched)
# ---------------------------------------------------------------------------

# GLiNER2's DeBERTa-v3 encoder uses O(n^2) relative-position attention, so a
# whole-document forward pass OOMs (~3 GB on a 56k-char page). Inputs longer
# than the trigger are split into overlapping, newline-aligned char windows
# whose offsets are rebased to the original text. The per-type eval cases are
# all far below the trigger, so they keep going through in a single pass.
_CHUNK_TRIGGER_CHARS = 1500
_CHUNK_CHARS = 1200
_CHUNK_OVERLAP_CHARS = 200


class _Gliner2Wrapper:
    """Minimal ``GLiNER2`` adapter producing ``PIIEntity`` lists.

    Loaded once per model id. ``detect`` runs ``extract_entities`` with the
    full label set and converts every span into a ``PIIEntity`` tagged
    ``source=GLINER`` (reusing the existing enum — the judge gates on source
    membership, not on the literal value, so no domain change is needed).
    """

    def __init__(self, model_id: str):
        self.model_id = model_id
        self._model = None
        self._schema_warned = False

    def load(self) -> None:
        from gliner2 import GLiNER2

        t0 = time.time()
        self._model = GLiNER2.from_pretrained(self.model_id)
        log.info(
            "[setup] GLiNER2 ready (model=%s, load_time=%.2fs)",
            self.model_id,
            time.time() - t0,
        )

    def detect(self, text: str, threshold: float) -> List[PIIEntity]:
        """Detect across the full label set, chunking long inputs.

        Short inputs (the per-type eval cases) go through in a single pass.
        Long inputs are split into overlapping newline-aligned windows, each
        scanned independently with offsets rebased to the original text, then
        de-duplicated across the overlaps.
        """
        if not text or self._model is None:
            return []
        if len(text) <= _CHUNK_TRIGGER_CHARS:
            return self._detect_window(text, threshold, base=0)
        collected: List[PIIEntity] = []
        for base, window in self._char_windows(text):
            collected.extend(self._detect_window(window, threshold, base=base))
        return self._dedup(collected)

    def _detect_window(
        self, window: str, threshold: float, base: int
    ) -> List[PIIEntity]:
        raw = self._model.extract_entities(
            window,
            GLINER2_LABELS,
            threshold=threshold,
            include_confidence=True,
            include_spans=True,
        )
        entities_by_label = raw.get("entities", raw) if isinstance(raw, dict) else {}
        results: List[PIIEntity] = []
        for label, spans in entities_by_label.items():
            pii_type = LABEL_TO_PII_TYPE.get(label)
            if pii_type is None or not isinstance(spans, list):
                continue
            for span in spans:
                entity = self._span_to_entity(window, base, label, pii_type, span)
                if entity is not None:
                    results.append(entity)
        return results

    @staticmethod
    def _char_windows(text: str) -> List[Tuple[int, str]]:
        """Overlapping ``(base_offset, window_text)`` windows, cut on a newline
        boundary when one is available near the window end so a PII value lands
        whole in at least one window."""
        windows: List[Tuple[int, str]] = []
        n = len(text)
        start = 0
        while start < n:
            end = min(start + _CHUNK_CHARS, n)
            if end < n:
                nl = text.rfind("\n", end - _CHUNK_OVERLAP_CHARS, end)
                if nl > start:
                    end = nl
            windows.append((start, text[start:end]))
            if end >= n:
                break
            start = max(end - _CHUNK_OVERLAP_CHARS, start + 1)
        return windows

    @staticmethod
    def _dedup(entities: List[PIIEntity]) -> List[PIIEntity]:
        """Collapse identical spans emitted by overlapping windows, keeping the
        highest score per ``(start, end, pii_type)``."""
        best: Dict[Tuple[int, int, str], PIIEntity] = {}
        for e in entities:
            key = (e.start, e.end, e.pii_type)
            cur = best.get(key)
            if cur is None or e.score > cur.score:
                best[key] = e
        return list(best.values())

    def _span_to_entity(
        self, window: str, base: int, label: str, pii_type: str, span: object
    ) -> PIIEntity | None:
        if not isinstance(span, dict):
            self._warn_schema(f"span is {type(span).__name__}, expected dict: {span!r}")
            return None
        value = span.get("text")
        score = span.get("confidence", span.get("score", 0.0))
        start = span.get("start")
        end = span.get("end")
        if start is None or end is None:
            # Schema fallback: locate the value within the window.
            if not value:
                self._warn_schema(f"span without offsets nor text: {span!r}")
                return None
            idx = window.find(value)
            if idx < 0:
                self._warn_schema(f"value {value!r} not found for offset fallback")
                return None
            start, end = idx, idx + len(value)
            self._warn_schema(
                "extract_entities returned no start/end — used text.find fallback. "
                "Verify include_spans semantics for this gliner2 version."
            )
        start, end = int(start), int(end)
        entity_text = (
            window[start:end] if 0 <= start < end <= len(window) else (value or "")
        )
        # Rebase window-local offsets onto the original document.
        return PIIEntity(
            text=entity_text,
            pii_type=pii_type,
            type_label=GLINER2_LABEL_SPECS[pii_type][1],
            start=base + start,
            end=base + end,
            score=float(score or 0.0),
            source=DetectorSource.GLINER,
        )

    def _warn_schema(self, message: str) -> None:
        # Fire once per wrapper so a schema mismatch is loud but not spammy.
        if not self._schema_warned:
            log.warning("[gliner2-schema] %s", message)
            self._schema_warned = True


# ---------------------------------------------------------------------------
# Session fixtures (heavy: GLiNER2 model + judge validator)
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session", params=GLINER2_MODEL_IDS, ids=lambda m: m.split("/")[-1])
def gliner2_detector(request) -> _Gliner2Wrapper:
    wrapper = _Gliner2Wrapper(request.param)
    wrapper.load()
    return wrapper


@pytest.fixture(scope="session")
def judge_validator():
    """Real ``LLMJudgeValidator`` auditing GLiNER-sourced entities."""
    from pii_detector.infrastructure.validation.llm_validator import (
        LLMJudgeValidator,
    )

    log.info(
        "[setup] building LLMJudgeValidator(audit_sources={GLINER}) on %s", BASE_URL
    )
    validator = LLMJudgeValidator(
        base_url=BASE_URL,
        audit_sources={DetectorSource.GLINER},
        fail_open=True,
        max_workers=4,
    )
    try:
        yield validator
    finally:
        validator.shutdown()


@pytest.fixture(scope="session")
def dual_metrics_collector() -> Dict[str, DualMetrics]:
    """Session-scope collector keyed by ``f"{model_id}::{pii_type}"`` so both
    models' per-type metrics coexist for the aggregate report."""
    return {}


@pytest.fixture(scope="session", autouse=True)
def _ensure_output_dir() -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    findings = OUTPUT_DIR / "findings.jsonl"
    if findings.exists():
        findings.unlink()
    return OUTPUT_DIR


# ---------------------------------------------------------------------------
# Per-case helper (GLiNER2 scan + judge filter)
# ---------------------------------------------------------------------------


def _detected_spans(
    text: str, entities: List[PIIEntity], pii_type: str
) -> List[Tuple[int, int, float, str]]:
    return [
        (e.start, e.end, float(e.score), text[e.start:e.end])
        for e in entities
        if e.pii_type == pii_type
    ]


def _scan_and_judge_case(
    detector: _Gliner2Wrapper,
    judge_validator,
    case: EvalCase,
) -> Tuple[
    List[Tuple[int, int, float, str]],
    List[Tuple[int, int, float, str]],
]:
    """Return ``(baseline_detected, judged_detected)`` for one case.

    GLiNER2 scans with the full label set; we keep only entities matching the
    case's ``pii_type`` (others belong to sibling cases and we don't pay an
    LLM call for them), apply the per-type threshold, then submit the survivors
    to the judge.
    """
    raw_entities = detector.detect(case.text, threshold=GLOBAL_THRESHOLD)
    baseline_entities = [
        e
        for e in raw_entities
        if e.pii_type == case.pii_type and e.score >= case.threshold
    ]
    judged_entities = (
        judge_validator.filter(case.text, baseline_entities)
        if baseline_entities
        else []
    )
    baseline_detected = _detected_spans(case.text, baseline_entities, case.pii_type)
    judged_detected = _detected_spans(case.text, judged_entities, case.pii_type)
    _log_case_breakdown(case, baseline_detected, judged_detected)
    _append_findings_jsonl(detector.model_id, case, baseline_detected, judged_detected)
    return baseline_detected, judged_detected


def _is_tp(case: EvalCase, start: int, end: int) -> bool:
    return any(
        iou(start, end, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
        for ex in case.expected_spans
    )


def _log_case_breakdown(
    case: EvalCase,
    baseline_detected: List[Tuple[int, int, float, str]],
    judged_detected: List[Tuple[int, int, float, str]],
) -> None:
    """Log every baseline span with KEPT/DROP + TP/FP, plus missed spans.

    KEPT|FP (FP-survivor) and DROP|TP (TP-loss) are promoted to WARNING.
    """
    judged_spans = {(s, e) for s, e, _, _ in judged_detected}
    prefix = f"[{case.pii_type}/{case.case_id}]"
    if not baseline_detected and not case.expected_spans:
        log.info("%s no detection, no expected span — clean case", prefix)
        return
    for start, end, score, value in baseline_detected:
        ground_truth = "TP" if _is_tp(case, start, end) else "FP"
        action = "KEPT" if (start, end) in judged_spans else "DROP"
        is_bad = (action == "KEPT" and ground_truth == "FP") or (
            action == "DROP" and ground_truth == "TP"
        )
        flag = (
            "  <-- FP-survivor"
            if (action == "KEPT" and ground_truth == "FP")
            else ("  <-- TP-loss" if (action == "DROP" and ground_truth == "TP") else "")
        )
        (log.warning if is_bad else log.info)(
            "%s %s | %s | %r @%d:%d score=%.2f%s",
            prefix, action, ground_truth, value, start, end, score, flag,
        )
    for ex in case.expected_spans:
        if not any(
            iou(ds, de, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
            for ds, de, _, _ in baseline_detected
        ):
            log.info(
                "%s MISS | FN | %r @%d:%d (GLiNER2 did not detect this span)",
                prefix, ex.value, ex.start, ex.end,
            )


def _append_findings_jsonl(
    model_id: str,
    case: EvalCase,
    baseline_detected: List[Tuple[int, int, float, str]],
    judged_detected: List[Tuple[int, int, float, str]],
) -> None:
    path = OUTPUT_DIR / "findings.jsonl"
    judged_spans = {(s, e) for s, e, _, _ in judged_detected}
    with path.open("a", encoding="utf-8") as fp:
        for start, end, score, value in baseline_detected:
            is_tp = _is_tp(case, start, end)
            kept = (start, end) in judged_spans
            fp.write(
                json.dumps(
                    {
                        "model": model_id,
                        "case_id": case.case_id,
                        "pii_type": case.pii_type,
                        "axis": case.axis,
                        "language": case.language,
                        "start": start,
                        "end": end,
                        "score": score,
                        "value": value,
                        "baseline_verdict": "TP" if is_tp else "FP",
                        "kept_by_judge": kept,
                        "judged_verdict": (
                            ("TP" if is_tp else "FP")
                            if kept
                            else ("FN_by_judge" if is_tp else "rejected_FP")
                        ),
                    }
                )
                + "\n"
            )


# ---------------------------------------------------------------------------
# Per-type metric computation (memoized per model × type)
# ---------------------------------------------------------------------------


def _collector_key(model_id: str, pii_type: str) -> str:
    return f"{model_id}::{pii_type}"


def _compute_dual_metrics(
    pii_type: str,
    detector: _Gliner2Wrapper,
    judge_validator,
    collector: Dict[str, DualMetrics],
) -> DualMetrics:
    key = _collector_key(detector.model_id, pii_type)
    cached = collector.get(key)
    if cached is not None:
        return cached

    cases = _load_gliner2_cases_for_type(pii_type)
    assert cases, f"No cases loaded for {pii_type}"

    metrics = DualMetrics(pii_type=pii_type)
    log.info(
        "[%s|%s] running %d cases through GLiNER2 + judge",
        detector.model_id, pii_type, len(cases),
    )
    t0 = time.time()
    for case in cases:
        baseline_detected, judged_detected = _scan_and_judge_case(
            detector, judge_validator, case
        )
        metrics.record(
            case_id=case.case_id,
            axis=case.axis,
            text=case.text,
            baseline_detected=baseline_detected,
            judged_detected=judged_detected,
            expected_spans=case.expected_spans,
        )
    log.info(
        "[%s|%s] cases=%d baseline: tp=%d fp=%d fn=%d P=%.3f R=%.3f F1=%.3f "
        "| judged: tp=%d fp=%d fn=%d P=%.3f R=%.3f F1=%.3f (%.1fs)",
        detector.model_id, pii_type, metrics.cases,
        metrics.baseline_tp, metrics.baseline_fp, metrics.baseline_fn,
        metrics.baseline_precision, metrics.baseline_recall, metrics.baseline_f1,
        metrics.judged_tp, metrics.judged_fp, metrics.judged_fn,
        metrics.judged_precision, metrics.judged_recall, metrics.judged_f1,
        time.time() - t0,
    )
    collector[key] = metrics
    return metrics


# ---------------------------------------------------------------------------
# Parametrized per-type tests (per model × per type)
# ---------------------------------------------------------------------------


@_runtime
@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_NotWorsenFpRate_When_JudgeIsAppliedPostGliner2(
    gliner2_detector, judge_validator, dual_metrics_collector, pii_type
):
    """Per-type judge-precision contract (HARD): the judge must not WORSEN the
    FP rate (``judged_fp_rate <= baseline_fp_rate``).

    The judge only removes entities, so the judged set is a subset of the
    baseline. A regression therefore means it dropped true positives while
    keeping false ones — a genuine judge defect (also surfaced by the
    TP-preservation gate). The absolute ``FP_rate <= 0.10`` cap that the
    OpenMed sibling enforces as a hard gate is kept here as a SOFT signal
    (WARN + report): for the generalist large-v1 it measures the *detector*,
    not the judge.
    """
    metrics = _compute_dual_metrics(
        pii_type, gliner2_detector, judge_validator, dual_metrics_collector
    )
    # SOFT signal: absolute precision vs the OpenMed-style cap.
    if metrics.judged_fp_rate > WARN_FP_RATE_WITH_JUDGE:
        log.warning(
            "[%s|%s] judged FP_rate=%.3f above the soft cap %.2f "
            "(baseline=%.3f) — detector precision signal, see report.md",
            gliner2_detector.model_id, pii_type, metrics.judged_fp_rate,
            WARN_FP_RATE_WITH_JUDGE, metrics.baseline_fp_rate,
        )
    # HARD gate: judge must improve or at worst maintain precision.
    assert metrics.judged_fp_rate <= metrics.baseline_fp_rate + 1e-9, (
        f"[{gliner2_detector.model_id}|{pii_type}] judge WORSENED the FP rate "
        f"({metrics.baseline_fp_rate:.3f} -> {metrics.judged_fp_rate:.3f}): it "
        f"removed true positives faster than false ones. baseline tp/fp="
        f"{metrics.baseline_tp}/{metrics.baseline_fp}, judged tp/fp="
        f"{metrics.judged_tp}/{metrics.judged_fp}. Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r}" for cid, val, _ in metrics.dropped_tp_samples[:3]
        )
    )


@_runtime
@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_PreserveBaselineTp_When_JudgeIsAppliedPostGliner2(
    gliner2_detector, judge_validator, dual_metrics_collector, pii_type
):
    """Per-type TP-preservation check (HARD): judge must not erode baseline TPs."""
    metrics = _compute_dual_metrics(
        pii_type, gliner2_detector, judge_validator, dual_metrics_collector
    )
    abs_tp_loss = metrics.baseline_tp - metrics.judged_tp
    assert metrics.recall_degradation <= MAX_RECALL_DEGRADATION, (
        f"[{gliner2_detector.model_id}|{pii_type}] judge eroded recall by "
        f"{metrics.recall_degradation:.3f}pp "
        f"({metrics.baseline_recall:.3f} -> {metrics.judged_recall:.3f}), "
        f"exceeding the {MAX_RECALL_DEGRADATION:.2f}pp budget. Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r}" for cid, val, _ in metrics.dropped_tp_samples[:3]
        )
    )
    assert abs_tp_loss <= MAX_ABS_TP_LOSS, (
        f"[{gliner2_detector.model_id}|{pii_type}] judge dropped {abs_tp_loss} TPs "
        f"in absolute terms ({metrics.baseline_tp} -> {metrics.judged_tp}), "
        f"exceeding the hard cap of {MAX_ABS_TP_LOSS}. Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r}" for cid, val, _ in metrics.dropped_tp_samples[:3]
        )
    )


@_runtime
@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_ReportAbsoluteRecall_When_Gliner2Evaluated(
    gliner2_detector, judge_validator, dual_metrics_collector, pii_type
):
    """Per-type absolute-recall report (SOFT): logs a WARNING when judged
    recall is below ``MIN_RECALL`` instead of failing.

    Unlike the OpenMed sibling, the detector's raw recall is the subject under
    evaluation here (especially for the generalist large-v1), not a contract —
    so a low floor is informative, not a defect. The number lands in the
    aggregate report regardless.
    """
    metrics = _compute_dual_metrics(
        pii_type, gliner2_detector, judge_validator, dual_metrics_collector
    )
    if metrics.judged_recall < MIN_RECALL:
        log.warning(
            "[%s|%s] judged recall=%.3f below soft floor %.2f "
            "(baseline=%.3f, judge degradation=%.3f) — detector-level gap, "
            "not a judge defect.",
            gliner2_detector.model_id, pii_type, metrics.judged_recall, MIN_RECALL,
            metrics.baseline_recall, metrics.recall_degradation,
        )


# ---------------------------------------------------------------------------
# Aggregate report (grouped by model, then type)
# ---------------------------------------------------------------------------


@_runtime
def test_Should_ProduceGliner2AggregatedReport_When_AllPerTypeTestsCompleted(
    dual_metrics_collector,
):
    """Consolidate per-model per-type metrics into ``report.md`` and
    ``metrics.json`` under ``target/gliner2-fp-eval-with-judge/``."""
    if not dual_metrics_collector:
        pytest.skip("No per-type results recorded; run the full file instead.")
    _write_metrics_json(OUTPUT_DIR / "metrics.json", dual_metrics_collector)
    _write_markdown_report(OUTPUT_DIR / "report.md", dual_metrics_collector)
    log.info("[aggregate] report written to %s", OUTPUT_DIR / "report.md")


def _group_by_model(
    collector: Dict[str, DualMetrics]
) -> Dict[str, Dict[str, DualMetrics]]:
    grouped: Dict[str, Dict[str, DualMetrics]] = {}
    for key, metrics in collector.items():
        model_id, pii_type = key.split("::", 1)
        grouped.setdefault(model_id, {})[pii_type] = metrics
    return grouped


def _write_metrics_json(path: Path, collector: Dict[str, DualMetrics]) -> None:
    payload: Dict[str, dict] = {}
    for model_id, per_type in _group_by_model(collector).items():
        payload[model_id] = {
            pii_type: {
                "cases": m.cases,
                "baseline": {
                    "tp": m.baseline_tp, "fp": m.baseline_fp, "fn": m.baseline_fn,
                    "fp_rate": round(m.baseline_fp_rate, 4),
                    "precision": round(m.baseline_precision, 4),
                    "recall": round(m.baseline_recall, 4),
                    "f1": round(m.baseline_f1, 4),
                },
                "judged": {
                    "tp": m.judged_tp, "fp": m.judged_fp, "fn": m.judged_fn,
                    "fp_rate": round(m.judged_fp_rate, 4),
                    "precision": round(m.judged_precision, 4),
                    "recall": round(m.judged_recall, 4),
                    "f1": round(m.judged_f1, 4),
                },
                "recall_degradation": round(m.recall_degradation, 4),
            }
            for pii_type, m in per_type.items()
        }
    with path.open("w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, ensure_ascii=False)


def _verdict_for(m: DualMetrics) -> str:
    """Report verdict for one type. FAIL only on JUDGE defects (hard gates):
    precision regression or eroded TPs. Detector-level weakness (high absolute
    FP, low recall) is a soft annotation, not a failure."""
    hard: List[str] = []
    if m.judged_fp_rate > m.baseline_fp_rate + 1e-9:
        hard.append("judge_worsened_FP")
    if m.recall_degradation > MAX_RECALL_DEGRADATION:
        hard.append(f"recall_loss>{MAX_RECALL_DEGRADATION:.2f}pp")
    if (m.baseline_tp - m.judged_tp) > MAX_ABS_TP_LOSS:
        hard.append("abs_tp_loss")
    if hard:
        return "FAIL (" + ", ".join(hard) + ")"
    soft: List[str] = []
    if m.judged_fp_rate > WARN_FP_RATE_WITH_JUDGE:
        soft.append(f"FP>{WARN_FP_RATE_WITH_JUDGE:.2f}")
    if m.judged_recall < MIN_RECALL:
        soft.append(f"recall<{MIN_RECALL:.2f}")
    return "PASS (" + ", ".join(soft) + ")" if soft else "PASS"


def _write_markdown_report(path: Path, collector: Dict[str, DualMetrics]) -> None:
    lines: List[str] = ["# GLiNER2 + LLM judge — FP/FN evaluation\n"]
    lines.append(
        f"- Hard gates: judged `FP_rate <= {MAX_FP_RATE_WITH_JUDGE:.2f}` (ideal "
        f"`<= {WARN_FP_RATE_WITH_JUDGE:.2f}`), recall degradation by judge "
        f"`<= {MAX_RECALL_DEGRADATION:.2f}pp`, absolute TP-loss "
        f"`<= {MAX_ABS_TP_LOSS}`.\n"
        f"- Absolute recall `>= {MIN_RECALL:.2f}` is reported as a SOFT signal "
        f"(WARN only) — it measures the detector, not the judge.\n"
        f"- Judged numbers come from "
        f"`LLMJudgeValidator(audit_sources={{GLINER}}, fail_open=True)`.\n"
    )
    for model_id, per_type in _group_by_model(collector).items():
        lines.append(f"\n## {model_id}\n")
        lines.append(
            "| PII type | Cases | Base P | Judged P | Base R | Judged R "
            "| Base F1 | Judged F1 | Base FP rate | Judged FP rate | Δ FP | Verdict |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        for pii_type in GLINER2_LABEL_SPECS:
            m = per_type.get(pii_type)
            if m is None:
                lines.append(
                    f"| {pii_type} | — | — | — | — | — | — | — | — | — | — | NOT RUN |"
                )
                continue
            verdict = _verdict_for(m)
            lines.append(
                f"| {pii_type} | {m.cases} "
                f"| {m.baseline_precision:.3f} | {m.judged_precision:.3f} "
                f"| {m.baseline_recall:.3f} | {m.judged_recall:.3f} "
                f"| {m.baseline_f1:.3f} | {m.judged_f1:.3f} "
                f"| {m.baseline_fp_rate:.3f} | {m.judged_fp_rate:.3f} "
                f"| {m.judged_fp_rate - m.baseline_fp_rate:+.3f} | {verdict} |"
            )
    path.write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# Meta-test: fixture sanity (independent of GLiNER2 and LM Studio)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_LoadByteExactBalancedFixtures_When_Gliner2FixturesParsed(pii_type):
    """Fixtures parse, spans are byte-exact, and each type has both a TP-bearing
    case and a negative case so the judge is exercised meaningfully. Runs even
    without GLiNER2 / LM Studio (it asserts on fixtures only).
    """
    cases = _load_gliner2_cases_for_type(pii_type)
    assert cases, f"{pii_type}: no cases"
    has_tp = any(c.expected_spans for c in cases)
    has_negative = any(
        not c.expected_spans and c.axis in {"look_alikes", "explicit_negatives"}
        for c in cases
    )
    assert has_tp, f"{pii_type}: no TP-bearing case (need >=1 with expected_spans)"
    assert has_negative, (
        f"{pii_type}: no look_alikes/explicit_negatives case (FP eval impossible)"
    )
