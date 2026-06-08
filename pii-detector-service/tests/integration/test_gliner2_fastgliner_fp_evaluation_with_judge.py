"""Realistic FP/FN evaluation for **GLiNER2 on the fast_gliner runtime**
(gline-rs / ONNX), with the LLM judge applied as a post-processing step.

Duplicate of
:mod:`tests.integration.test_gliner2_realistic_fp_evaluation_with_judge` — same
corpus (``gliner2-fp-eval/*.json``), same 6 axes, same IoU >= 0.5 matching, same
``DualMetrics`` accounting, same judge wiring — but the detector under test is
``fastino/gliner2-privacy-filter-PII-multi`` loaded through the production
``fast_gliner`` runtime instead of the PyTorch ``gliner2`` pipeline.

Why a separate file
-------------------

The two runtimes differ on exactly the variable we want to price:

* PyTorch (``extract_entities(text, {label: description})``) sends the per-label
  **descriptions** to the model and honours any threshold.
* fast_gliner (``predict_entities(text, [labels])``) is **labels-only** (the
  gline-rs binding rejects a ``{label: description}`` dict) and hard-caps the
  effective threshold at a 0.5 probability floor.

Running the *same* fixtures through both files and diffing the P/R/F1 in the two
``report.md`` quantifies the real cost of the production speed mode (ONNX engine
+ lost descriptions). This file holds the fastgliner half; the sibling holds the
PyTorch half (run it with ``-k privacy-filter``).

Loading reuses the production :class:`Gliner2ModelManager` (ONNX resolution,
local staging, ``FastGLiNER2.from_pretrained``). If no monolithic ONNX export of
privacy-filter is resolvable, the manager falls back to PyTorch — in which case
the whole session is **skipped** (we will not silently measure PyTorch here).

Outputs (written to ``target/gliner2-fastgliner-fp-eval-with-judge/``):

* ``findings.jsonl`` — per-detection record (model, baseline + judged verdict)
* ``metrics.json``   — aggregated metrics per type (P/R/F1, FP rate)
* ``report.md``      — markdown report mirroring the PyTorch sibling's columns
"""

from __future__ import annotations

import json
import logging
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
# verbatim from the OpenMed *with-judge* sibling (LM-Studio probe at import).
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

# The label registry, fixtures, and per-type params are shared verbatim with the
# PyTorch sibling — importing them keeps the two evals byte-identical on the
# corpus side, so any P/R/F1 delta is attributable to the runtime alone.
from tests.integration.test_gliner2_realistic_fp_evaluation_with_judge import (
    GLINER2_LABELS,
    GLINER2_LABEL_SPECS,
    LABEL_TO_PII_TYPE,
    GLOBAL_THRESHOLD,
    _CHUNK_CHARS,
    _CHUNK_OVERLAP_CHARS,
    _CHUNK_TRIGGER_CHARS,
    _TYPE_PARAMS,
    _detected_spans,
    _is_tp,
    _load_gliner2_cases_for_type,
    _log_case_breakdown,
    _write_markdown_report,
    _write_metrics_json,
)

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.model_management.gliner2_model_manager import (
    Gliner2ModelManager,
)

log = logging.getLogger("gliner2_fastgliner_fp_eval_with_judge")


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# fastgliner is the production runtime for the privacy-filter fine-tune; that is
# the only checkpoint with a monolithic ONNX export, so the comparison is fixed
# to it (large-v1 has no export and is out of scope).
FASTGLINER_MODEL_ID = "fastino/gliner2-privacy-filter-PII-multi"

OUTPUT_DIR = (
    Path(__file__).resolve().parent.parent.parent
    / "target"
    / "gliner2-fastgliner-fp-eval-with-judge"
)


# ---------------------------------------------------------------------------
# Skip guards
# ---------------------------------------------------------------------------


def _fast_gliner_available() -> bool:
    try:
        import fast_gliner  # noqa: F401

        return True
    except Exception:
        return False


pytestmark = [pytest.mark.integration, pytest.mark.slow]

_requires_runtime = (
    pytest.mark.skipif(
        not _fast_gliner_available(),
        reason="fastgliner runtime requires the `fast_gliner` package.",
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
# In-file fast_gliner wrapper (mirrors the PyTorch sibling; inference differs)
# ---------------------------------------------------------------------------


class _FastGliner2Wrapper:
    """``fast_gliner`` adapter producing ``PIIEntity`` lists, labels-only.

    Loaded through the production :class:`Gliner2ModelManager` so the ONNX
    resolution + staging path is exercised exactly as in prod. ``detect``
    mirrors the PyTorch wrapper (same chunking, dedup, span conversion); only
    the raw inference call differs: ``predict_entities(text, [labels])`` returns
    a flat list of ``{text, label, score, start, end}`` dicts (no per-label
    description is sent — the gline-rs binding is labels-only).
    """

    def __init__(self, model_id: str):
        self.model_id = model_id
        self._model = None
        self._schema_warned = False

    def load(self) -> None:
        manager = Gliner2ModelManager(
            DetectionConfig(model_id=self.model_id, gliner2_runtime="fastgliner")
        )
        t0 = time.time()
        model = manager.load_model()
        if manager.runtime != "fastgliner":
            pytest.skip(
                "No resolvable monolithic ONNX export for "
                f"{self.model_id} — fastgliner runtime unavailable (manager fell "
                "back to PyTorch). Produce it with "
                "scripts/export_gliner2_to_monolithic_onnx.py."
            )
        self._model = model
        log.info(
            "[setup] fast_gliner ready (model=%s, load_time=%.2fs)",
            self.model_id,
            time.time() - t0,
        )

    def detect(self, text: str, threshold: float) -> List[PIIEntity]:
        """Detect across the full label set, chunking long inputs (as PyTorch)."""
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
        # fast_gliner: flat list of {text, label, score, start, end}; gline-rs
        # pre-filters at its 0.5 probability floor. The per-type threshold cut
        # happens downstream in _scan_and_judge_case, mirroring the sibling.
        raw = self._model.predict_entities(window, list(GLINER2_LABELS))
        if not isinstance(raw, list):
            self._warn_schema(f"predict_entities returned {type(raw).__name__}: {raw!r}")
            return []
        results: List[PIIEntity] = []
        for span in raw:
            if not isinstance(span, dict):
                self._warn_schema(f"span is {type(span).__name__}: {span!r}")
                continue
            label = span.get("label")
            pii_type = LABEL_TO_PII_TYPE.get(label)
            if pii_type is None:
                continue
            entity = self._span_to_entity(window, base, label, pii_type, span)
            if entity is not None:
                results.append(entity)
        return results

    @staticmethod
    def _char_windows(text: str) -> List[Tuple[int, str]]:
        """Overlapping ``(base_offset, window_text)`` windows, newline-aligned."""
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
        """Collapse identical spans from overlapping windows (highest score)."""
        best: Dict[Tuple[int, int, str], PIIEntity] = {}
        for e in entities:
            key = (e.start, e.end, e.pii_type)
            cur = best.get(key)
            if cur is None or e.score > cur.score:
                best[key] = e
        return list(best.values())

    def _span_to_entity(
        self, window: str, base: int, label: str, pii_type: str, span: dict
    ) -> PIIEntity | None:
        value = span.get("text")
        score = span.get("confidence", span.get("score", 0.0))
        start = span.get("start")
        end = span.get("end")
        if start is None or end is None:
            if not value:
                self._warn_schema(f"span without offsets nor text: {span!r}")
                return None
            idx = window.find(value)
            if idx < 0:
                self._warn_schema(f"value {value!r} not found for offset fallback")
                return None
            start, end = idx, idx + len(value)
            self._warn_schema(
                "predict_entities returned no start/end — used text.find fallback."
            )
        start, end = int(start), int(end)
        entity_text = (
            window[start:end] if 0 <= start < end <= len(window) else (value or "")
        )
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
        if not self._schema_warned:
            log.warning("[fastgliner-schema] %s", message)
            self._schema_warned = True


# ---------------------------------------------------------------------------
# Session fixtures (heavy: fast_gliner model + judge validator)
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session", ids=lambda m: f"{m.split('/')[-1]}-fastgliner")
def gliner2_detector() -> _FastGliner2Wrapper:
    wrapper = _FastGliner2Wrapper(FASTGLINER_MODEL_ID)
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
    """Session-scope collector keyed by ``f"{model_id}::{pii_type}"``."""
    return {}


@pytest.fixture(scope="session", autouse=True)
def _ensure_output_dir() -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    findings = OUTPUT_DIR / "findings.jsonl"
    if findings.exists():
        findings.unlink()
    return OUTPUT_DIR


# ---------------------------------------------------------------------------
# Per-case helper (fast_gliner scan + judge filter) — own OUTPUT_DIR
# ---------------------------------------------------------------------------


def _scan_and_judge_case(
    detector: _FastGliner2Wrapper,
    judge_validator,
    case: EvalCase,
) -> Tuple[
    List[Tuple[int, int, float, str]],
    List[Tuple[int, int, float, str]],
]:
    """Return ``(baseline_detected, judged_detected)`` for one case."""
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
                        "runtime": "fastgliner",
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
# Per-type metric computation (memoized per type)
# ---------------------------------------------------------------------------


def _collector_key(model_id: str, pii_type: str) -> str:
    return f"{model_id}::{pii_type}"


def _compute_dual_metrics(
    pii_type: str,
    detector: _FastGliner2Wrapper,
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
        "[%s|%s] running %d cases through fast_gliner + judge",
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
# Parametrized per-type tests
# ---------------------------------------------------------------------------


@_runtime
@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_NotWorsenFpRate_When_JudgeIsAppliedPostFastgliner(
    gliner2_detector, judge_validator, dual_metrics_collector, pii_type
):
    """Per-type judge-precision contract (HARD): judged_fp_rate <= baseline."""
    metrics = _compute_dual_metrics(
        pii_type, gliner2_detector, judge_validator, dual_metrics_collector
    )
    if metrics.judged_fp_rate > WARN_FP_RATE_WITH_JUDGE:
        log.warning(
            "[%s|%s] judged FP_rate=%.3f above the soft cap %.2f "
            "(baseline=%.3f) — detector precision signal, see report.md",
            gliner2_detector.model_id, pii_type, metrics.judged_fp_rate,
            WARN_FP_RATE_WITH_JUDGE, metrics.baseline_fp_rate,
        )
    assert metrics.judged_fp_rate <= metrics.baseline_fp_rate + 1e-9, (
        f"[{gliner2_detector.model_id}|{pii_type}] judge WORSENED the FP rate "
        f"({metrics.baseline_fp_rate:.3f} -> {metrics.judged_fp_rate:.3f}). "
        f"baseline tp/fp={metrics.baseline_tp}/{metrics.baseline_fp}, judged tp/fp="
        f"{metrics.judged_tp}/{metrics.judged_fp}. Dropped TPs: "
        + "; ".join(
            f"{cid}: {val!r}" for cid, val, _ in metrics.dropped_tp_samples[:3]
        )
    )


@_runtime
@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_PreserveBaselineTp_When_JudgeIsAppliedPostFastgliner(
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
def test_Should_ReportAbsoluteRecall_When_FastglinerEvaluated(
    gliner2_detector, judge_validator, dual_metrics_collector, pii_type
):
    """Per-type absolute-recall report (SOFT): WARN below ``MIN_RECALL``."""
    metrics = _compute_dual_metrics(
        pii_type, gliner2_detector, judge_validator, dual_metrics_collector
    )
    if metrics.judged_recall < MIN_RECALL:
        log.warning(
            "[%s|%s] judged recall=%.3f below soft floor %.2f "
            "(baseline=%.3f, judge degradation=%.3f) — detector-level gap.",
            gliner2_detector.model_id, pii_type, metrics.judged_recall, MIN_RECALL,
            metrics.baseline_recall, metrics.recall_degradation,
        )


# ---------------------------------------------------------------------------
# Aggregate report (reuses the PyTorch sibling's writers, distinct OUTPUT_DIR)
# ---------------------------------------------------------------------------


@_runtime
def test_Should_ProduceFastglinerAggregatedReport_When_AllPerTypeTestsCompleted(
    dual_metrics_collector,
):
    """Consolidate per-type metrics into ``report.md`` and ``metrics.json``
    under ``target/gliner2-fastgliner-fp-eval-with-judge/``."""
    if not dual_metrics_collector:
        pytest.skip("No per-type results recorded; run the full file instead.")
    _write_metrics_json(OUTPUT_DIR / "metrics.json", dual_metrics_collector)
    _write_markdown_report(OUTPUT_DIR / "report.md", dual_metrics_collector)
    log.info("[aggregate] report written to %s", OUTPUT_DIR / "report.md")
