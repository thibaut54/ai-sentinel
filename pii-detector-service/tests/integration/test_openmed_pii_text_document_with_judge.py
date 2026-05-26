"""End-to-end FP/FN evaluation on a single plain-text document.

Companion of :mod:`tests.integration.test_openmed_realistic_fp_evaluation_with_judge`
that takes the same fixtures and assembles them into ONE long plain-text
document (``tests/resources/openmed-pii-test-document.txt``) instead of
scanning each JSON case in isolation. Goal: validate the OpenMed + LLM
judge pipeline against a realistic Confluence-like document where many
PII candidates coexist with technical noise on the same page — which is
what the production scan actually sees.

Reading the fixture
-------------------

The document is **plain text** — exactly what a Confluence page would
look like. Test annotations live in a sibling file
``openmed-pii-test-document.annotations.json`` keyed by 1-based line
number::

    {
      "line_number": 24,
      "pii_type": "PASSWORD",
      "verdict": "TP",            // or "FP"
      "value": "Tr0ub4dor!2024",  // null when verdict == "FP"
      ...
    }

This split (text vs ground-truth) keeps the scanned document
indistinguishable from production input while preserving the per-line
expected verdict the test needs to score the pipeline.

Assertions (document-wide)
--------------------------

* ``judged_fp_rate <= 0.10`` (hard cap, ``5%`` is the WARN target)
* ``recall_degradation <= 0.10pp`` vs raw OpenMed
* ``judged_recall >= 0.50`` absolute floor

Per-line breakdown is logged at INFO; FP-survivors and TP-loss outcomes
are promoted to WARNING with the line number and the surrounding context
so a failure is actionable from the pytest log alone (no need to crack
open findings.jsonl).

Run
---

::

    rtk proxy pytest \\
        tests/integration/test_openmed_pii_text_document_with_judge.py \\
        -v -s --log-cli-level=INFO --no-cov

The LM Studio endpoint defaults to the value of ``[llm_judge].base_url``
in ``config/detection-settings.toml`` (single source of truth). Override
ad hoc with ``LLM_JUDGE_BASE_URL=http://host:port/v1`` if needed.

Skipped automatically when LM Studio is unreachable or ``transformers <
5.0`` (OpenMed unsupported).
"""

from __future__ import annotations

import json
import logging
import os
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import pytest

# Reuse the live-IT probe pattern from the existing tests.
from tests.integration.test_openmed_realistic_fp_evaluation_with_judge import (
    MAX_FP_RATE_WITH_JUDGE,
    MAX_RECALL_DEGRADATION,
    MIN_RECALL,
    WARN_FP_RATE_WITH_JUDGE,
    _lm_studio_reachable,
    _transformers_supports_openmed,
)

log = logging.getLogger("openmed_text_doc_with_judge")


# ---------------------------------------------------------------------------
# Fixture document
# ---------------------------------------------------------------------------


DOCUMENT_PATH = (
    Path(__file__).resolve().parent.parent
    / "resources"
    / "openmed-pii-test-document.txt"
)
ANNOTATIONS_PATH = (
    Path(__file__).resolve().parent.parent
    / "resources"
    / "openmed-pii-test-document.annotations.json"
)

# Threshold passed to OpenMed (matches what the other tests use).
GLOBAL_THRESHOLD = 0.30

# IoU floor for matching an OpenMed detection to the line's expected value.
# A line is short enough that a single shared character is usually
# accidental; we still require >= 0.5 to stay consistent with the rest of
# the test suite.
IOU_MATCH_THRESHOLD = 0.5


@dataclass(frozen=True)
class AnnotatedLine:
    """One sidecar annotation entry (resolved to its document line)."""

    line_number: int            # 1-based, matches editor line numbers
    pii_type: str               # set by the sidecar JSON
    verdict: str                # "TP" or "FP"
    expected_value: Optional[str]  # populated only for TP
    text: str                   # actual line content in the document


@dataclass(frozen=True)
class LinePosition:
    """Offset of an annotated line inside the assembled scan document."""

    start: int  # inclusive offset in the scan document
    end: int    # exclusive offset in the scan document
    line_number: int


# ---------------------------------------------------------------------------
# Document loader
# ---------------------------------------------------------------------------


def _parse_document() -> Tuple[str, List[AnnotatedLine], List[LinePosition]]:
    """Load the plain-text document + its sidecar annotations.

    Returns ``(scan_document, annotations, positions)``:

    * ``scan_document`` is the raw plain-text document — what OpenMed
      sees. Loaded verbatim from disk so the bytes the test feeds to
      the pipeline are byte-identical to what a Confluence export would
      look like.
    * ``annotations`` is the list of ``AnnotatedLine`` in document order,
      reconstructed by joining the sidecar JSON entries with the
      corresponding lines from the document.
    * ``positions`` mirrors ``annotations`` and gives the
      ``(document_start_offset, document_end_offset)`` of each annotated
      line so OpenMed detections (which carry global offsets) can be
      mapped back to a line.

    Lines NOT mentioned in the sidecar (section headers, blank lines,
    free-form paragraphs in the header block) are still part of
    ``scan_document`` so OpenMed sees the realistic surrounding context,
    but they are simply not scored.
    """
    if not DOCUMENT_PATH.exists():
        raise AssertionError(
            f"Missing PII test document at {DOCUMENT_PATH}. Regenerate via "
            f"`python tests/resources/openmed-fp-eval/_generate_text_document.py`."
        )
    if not ANNOTATIONS_PATH.exists():
        raise AssertionError(
            f"Missing annotations sidecar at {ANNOTATIONS_PATH}. The "
            f"generator writes both files together — regenerate if you "
            f"only have one."
        )

    scan_document = DOCUMENT_PATH.read_text(encoding="utf-8")
    file_lines = scan_document.split("\n")

    payload = json.loads(ANNOTATIONS_PATH.read_text(encoding="utf-8"))
    raw_entries = payload.get("lines", [])
    if not raw_entries:
        raise AssertionError(
            f"Annotations file {ANNOTATIONS_PATH} contains no entries."
        )

    # Precompute (start, end) of each physical line in the scan_document so
    # the mapper from detection offset -> line is O(log N) via bisect.
    line_offsets: List[Tuple[int, int]] = []
    cursor = 0
    for raw in file_lines:
        line_offsets.append((cursor, cursor + len(raw)))
        cursor += len(raw) + 1  # +1 for the "\n" separator

    annotations: List[AnnotatedLine] = []
    positions: List[LinePosition] = []
    for entry in raw_entries:
        line_number = int(entry["line_number"])
        if not 1 <= line_number <= len(file_lines):
            raise AssertionError(
                f"Annotation line_number={line_number} is out of range "
                f"[1, {len(file_lines)}]. Sidecar / document drift — "
                f"regenerate with _generate_text_document.py."
            )
        verdict = entry["verdict"]
        value = entry.get("value")
        line_text = file_lines[line_number - 1]

        if verdict == "TP":
            if not value:
                raise AssertionError(
                    f"Annotation line {line_number} verdict=TP has no value."
                )
            if value not in line_text:
                raise AssertionError(
                    f"Annotation line {line_number} value={value!r} "
                    f"is not present in the document line "
                    f"{line_text[:80]!r}. Sidecar/document drift."
                )

        annotations.append(
            AnnotatedLine(
                line_number=line_number,
                pii_type=entry["pii_type"],
                verdict=verdict,
                expected_value=value,
                text=line_text,
            )
        )
        start, end = line_offsets[line_number - 1]
        positions.append(LinePosition(start=start, end=end, line_number=line_number))

    return scan_document, annotations, positions


# ---------------------------------------------------------------------------
# LM Studio guards (reuses the sibling test's probe)
# ---------------------------------------------------------------------------


# Single source of truth: [llm_judge].base_url in
# config/detection-settings.toml. Env var overrides for ad-hoc runs.
from pii_detector.infrastructure.validation.llm_validator import (  # noqa: E402
    _DEFAULT_BASE_URL,
    _load_llm_judge_toml_defaults,
)

BASE_URL = (
    os.getenv("LLM_JUDGE_BASE_URL")
    or _load_llm_judge_toml_defaults().get("base_url")
    or _DEFAULT_BASE_URL
)


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
# Detection helpers
# ---------------------------------------------------------------------------


@dataclass
class DocumentMetrics:
    """Document-wide TP/FP/FN counters, baseline and judged side-by-side."""

    baseline_tp: int = 0
    baseline_fp: int = 0
    baseline_fn: int = 0
    judged_tp: int = 0
    judged_fp: int = 0
    judged_fn: int = 0

    per_type: Dict[str, Dict[str, int]] = field(
        default_factory=lambda: defaultdict(
            lambda: {
                "b_tp": 0, "b_fp": 0, "b_fn": 0,
                "j_tp": 0, "j_fp": 0, "j_fn": 0,
            }
        )
    )
    fp_survivors: List[Tuple[int, str, str, str]] = field(default_factory=list)
    tp_loss: List[Tuple[int, str, str, str]] = field(default_factory=list)
    # (line_number, pii_type, value, context)

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
        return max(0.0, self.baseline_recall - self.judged_recall)


def _resolve_line_for_offset(
    positions: List[LinePosition], start: int, end: int
) -> Optional[LinePosition]:
    """Return the line whose ``[start, end)`` slot contains ``[start, end)``.

    Linear scan — the document has ~290 lines so this stays comfortably
    sub-millisecond.
    """
    for pos in positions:
        if pos.start <= start and end <= pos.end + 1:
            return pos
    return None


def _value_offsets_on_line(
    line_text: str, line_pos: LinePosition, value: str
) -> List[Tuple[int, int]]:
    """All start/end offsets where ``value`` occurs inside the line, expressed
    in scan_document coordinates."""
    occurrences: List[Tuple[int, int]] = []
    idx = 0
    while True:
        found = line_text.find(value, idx)
        if found < 0:
            break
        occurrences.append(
            (line_pos.start + found, line_pos.start + found + len(value))
        )
        idx = found + 1
    return occurrences


def _iou(a_start: int, a_end: int, b_start: int, b_end: int) -> float:
    if a_end <= a_start or b_end <= b_start:
        return 0.0
    inter = max(0, min(a_end, b_end) - max(a_start, b_start))
    if inter == 0:
        return 0.0
    union = max(a_end, b_end) - min(a_start, b_start)
    return inter / union if union > 0 else 0.0


# ---------------------------------------------------------------------------
# Pytest fixtures (heavy: OpenMed + judge)
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def parsed_document():
    """Parse the .txt file once for the whole module."""
    scan_document, annotations, positions = _parse_document()
    log.info(
        "[setup] parsed %d annotated lines (%d TP / %d FP) — scan_document=%d chars",
        len(annotations),
        sum(1 for a in annotations if a.verdict == "TP"),
        sum(1 for a in annotations if a.verdict == "FP"),
        len(scan_document),
    )
    return scan_document, annotations, positions


@pytest.fixture(scope="module")
def openmed_detector():
    from pii_detector.infrastructure.detector.openmed_detector import (
        OpenMedDetector,
    )

    log.info("[setup] loading OpenMedDetector (2.7 GB model)")
    detector = OpenMedDetector()
    t0 = time.time()
    detector.load_model()
    log.info("[setup] OpenMed ready in %.2fs", time.time() - t0)
    return detector


@pytest.fixture(scope="module")
def pii_type_configs() -> Dict[str, Dict[str, object]]:
    # Mirror the per-type runtime config used by the sibling tests so OpenMed
    # behaves identically across the suite.
    from tests.integration.test_openmed_realistic_fp_evaluation import (
        EVALUATED_TYPES,
    )

    configs: Dict[str, Dict[str, object]] = {}
    for pii_type, label, threshold in EVALUATED_TYPES:
        configs[f"OPENMED:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "OPENMED",
            "category": "EVAL",
            "country_code": None,
            "detector_label": label,
        }
    return configs


@pytest.fixture(scope="module")
def judge_validator():
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


# ---------------------------------------------------------------------------
# Main document-wide test
# ---------------------------------------------------------------------------


def test_Should_DropFpRateNearZero_When_JudgeIsAppliedOnPlainTextDocument(
    parsed_document,
    openmed_detector,
    judge_validator,
    pii_type_configs,
):
    """Document-wide assertion: scan one big concatenated text, run the judge,
    compare baseline vs judged metrics. See module docstring for thresholds.

    Logs every detection with the line it came from + verdict (KEPT/DROP)
    and ground truth (TP/FP) at INFO level. FP-survivors and TP-loss
    outcomes are promoted to WARNING with the offending line number.
    """
    scan_document, annotations, positions = parsed_document
    annotation_by_line = {a.line_number: a for a in annotations}
    line_text_by_number = {a.line_number: a.text for a in annotations}
    line_pos_by_number = {p.line_number: p for p in positions}

    # --- 1. OpenMed scan over the full document ------------------------
    log.info(
        "[scan] running OpenMed on %d-char document (%d annotated lines)",
        len(scan_document),
        len(annotations),
    )
    t0 = time.time()
    raw_entities = openmed_detector.detect_pii(
        scan_document,
        threshold=GLOBAL_THRESHOLD,
        pii_type_configs=pii_type_configs,
    )
    log.info(
        "[scan] OpenMed produced %d entities in %.1fs", len(raw_entities), time.time() - t0
    )

    # --- 2. Apply the LLM judge ----------------------------------------
    t0 = time.time()
    judged_entities = (
        judge_validator.filter(scan_document, raw_entities) if raw_entities else []
    )
    log.info(
        "[judge] judged set: %d entities (out of %d baseline) in %.1fs",
        len(judged_entities),
        len(raw_entities),
        time.time() - t0,
    )

    judged_spans = {(e.start, e.end) for e in judged_entities}

    # --- 3. Per-entity verdict + structured logging --------------------
    metrics = DocumentMetrics()
    matched_tp_lines_baseline: set = set()
    matched_tp_lines_judged: set = set()

    for ent in raw_entities:
        line_pos = _resolve_line_for_offset(positions, ent.start, ent.end)
        if line_pos is None:
            log.warning(
                "[orphan] entity %r @%d:%d (type=%s) did not map to any "
                "annotated line — skipping (likely an offset edge case)",
                scan_document[ent.start : ent.end],
                ent.start,
                ent.end,
                ent.pii_type,
            )
            continue
        annotation = annotation_by_line[line_pos.line_number]
        line_text = line_text_by_number[line_pos.line_number]

        # Decide ground-truth for THIS entity (not the line as a whole): a
        # TP line that contains the expected value still produces FPs if
        # OpenMed picks an unrelated substring on the same line.
        ground_truth = "FP"
        if annotation.verdict == "TP" and annotation.expected_value:
            for ex_start, ex_end in _value_offsets_on_line(
                line_text, line_pos, annotation.expected_value
            ):
                if _iou(ent.start, ent.end, ex_start, ex_end) >= IOU_MATCH_THRESHOLD:
                    ground_truth = "TP"
                    break

        action = "KEPT" if (ent.start, ent.end) in judged_spans else "DROP"
        pii_type = annotation.pii_type
        type_bucket = metrics.per_type[pii_type]

        if ground_truth == "TP":
            metrics.baseline_tp += 1
            type_bucket["b_tp"] += 1
            matched_tp_lines_baseline.add(line_pos.line_number)
            if action == "KEPT":
                metrics.judged_tp += 1
                type_bucket["j_tp"] += 1
                matched_tp_lines_judged.add(line_pos.line_number)
            else:
                metrics.judged_fn += 1
                type_bucket["j_fn"] += 1
                metrics.tp_loss.append(
                    (
                        line_pos.line_number,
                        pii_type,
                        scan_document[ent.start : ent.end],
                        line_text[:200],
                    )
                )
        else:
            metrics.baseline_fp += 1
            type_bucket["b_fp"] += 1
            if action == "KEPT":
                metrics.judged_fp += 1
                type_bucket["j_fp"] += 1
                metrics.fp_survivors.append(
                    (
                        line_pos.line_number,
                        pii_type,
                        scan_document[ent.start : ent.end],
                        line_text[:200],
                    )
                )

        is_bad = (action == "KEPT" and ground_truth == "FP") or (
            action == "DROP" and ground_truth == "TP"
        )
        emit = log.warning if is_bad else log.info
        flag = (
            "  <-- FP-survivor"
            if (action == "KEPT" and ground_truth == "FP")
            else ("  <-- TP-loss" if (action == "DROP" and ground_truth == "TP") else "")
        )
        emit(
            "[line=%d %s/%s] %s | %s | %r @%d:%d score=%.2f%s",
            line_pos.line_number,
            pii_type,
            annotation.verdict,
            action,
            ground_truth,
            scan_document[ent.start : ent.end],
            ent.start,
            ent.end,
            float(ent.score),
            flag,
        )

    # --- 4. Compute FN (TP lines OpenMed missed entirely) --------------
    for ann in annotations:
        if ann.verdict != "TP":
            continue
        type_bucket = metrics.per_type[ann.pii_type]
        if ann.line_number not in matched_tp_lines_baseline:
            metrics.baseline_fn += 1
            type_bucket["b_fn"] += 1
            log.info(
                "[line=%d %s/TP] MISS | FN | %r (OpenMed did not detect the expected value)",
                ann.line_number,
                ann.pii_type,
                ann.expected_value,
            )
        if ann.line_number not in matched_tp_lines_judged:
            # Already counted in tp_loss above if the entity was dropped;
            # but ALSO count a baseline miss as a judged miss to keep the
            # recall metric coherent.
            if ann.line_number not in matched_tp_lines_baseline:
                metrics.judged_fn += 1
                type_bucket["j_fn"] += 1

    # --- 5. Type-level and document-wide structured summary -------------
    log.info(
        "[document] cases=%d (TP=%d FP=%d) | baseline tp=%d fp=%d fn=%d "
        "FP_rate=%.3f recall=%.3f | judged tp=%d fp=%d fn=%d "
        "FP_rate=%.3f recall=%.3f",
        len(annotations),
        sum(1 for a in annotations if a.verdict == "TP"),
        sum(1 for a in annotations if a.verdict == "FP"),
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
    )

    for pii_type in sorted(metrics.per_type):
        b = metrics.per_type[pii_type]
        b_fp_rate = b["b_fp"] / (b["b_fp"] + b["b_tp"]) if (b["b_fp"] + b["b_tp"]) > 0 else 0.0
        j_fp_rate = b["j_fp"] / (b["j_fp"] + b["j_tp"]) if (b["j_fp"] + b["j_tp"]) > 0 else 0.0
        log.info(
            "[type=%s] baseline tp=%d fp=%d fn=%d FP_rate=%.3f | judged tp=%d fp=%d fn=%d FP_rate=%.3f",
            pii_type,
            b["b_tp"], b["b_fp"], b["b_fn"], b_fp_rate,
            b["j_tp"], b["j_fp"], b["j_fn"], j_fp_rate,
        )

    if metrics.fp_survivors:
        log.warning(
            "[document] FP-survivors (%d): %s",
            len(metrics.fp_survivors),
            "; ".join(
                f"line={ln} {tp}: {val!r} in {ctx[:80]!r}..."
                for ln, tp, val, ctx in metrics.fp_survivors[:10]
            ),
        )
    if metrics.tp_loss:
        log.warning(
            "[document] TP-loss (%d): %s",
            len(metrics.tp_loss),
            "; ".join(
                f"line={ln} {tp}: {val!r} in {ctx[:80]!r}..."
                for ln, tp, val, ctx in metrics.tp_loss[:10]
            ),
        )

    # --- 6. Soft warning + hard assertions --------------------------------
    if WARN_FP_RATE_WITH_JUDGE < metrics.judged_fp_rate <= MAX_FP_RATE_WITH_JUDGE:
        log.warning(
            "[document] judged FP_rate=%.3f exceeds ideal target %.2f "
            "(still under the hard cap %.2f).",
            metrics.judged_fp_rate,
            WARN_FP_RATE_WITH_JUDGE,
            MAX_FP_RATE_WITH_JUDGE,
        )

    assert metrics.judged_fp_rate <= MAX_FP_RATE_WITH_JUDGE, (
        f"[document] judged FP_rate={metrics.judged_fp_rate:.3f} exceeds cap "
        f"{MAX_FP_RATE_WITH_JUDGE:.2f} on the plain-text document. "
        f"Baseline was {metrics.baseline_fp_rate:.3f}; the judge cleared "
        f"{metrics.baseline_fp - metrics.judged_fp}/{metrics.baseline_fp} "
        f"FPs but {metrics.judged_fp} survived. First few survivors: "
        + "; ".join(
            f"line={ln} {tp}: {val!r}"
            for ln, tp, val, _ in metrics.fp_survivors[:3]
        )
    )

    assert metrics.recall_degradation <= MAX_RECALL_DEGRADATION, (
        f"[document] judge eroded recall by {metrics.recall_degradation:.3f}pp "
        f"({metrics.baseline_recall:.3f} -> {metrics.judged_recall:.3f}), "
        f"exceeding the {MAX_RECALL_DEGRADATION:.2f}pp budget. First few "
        f"TP-loss lines: "
        + "; ".join(
            f"line={ln} {tp}: {val!r}"
            for ln, tp, val, _ in metrics.tp_loss[:3]
        )
    )

    assert metrics.judged_recall >= MIN_RECALL, (
        f"[document] judged recall={metrics.judged_recall:.3f} below floor "
        f"{MIN_RECALL:.2f}. baseline={metrics.baseline_recall:.3f}, "
        f"degradation={metrics.recall_degradation:.3f}."
    )


# ---------------------------------------------------------------------------
# Meta-test: fixture document sanity
# ---------------------------------------------------------------------------


def test_Should_ParseEveryAnnotatedLine_When_DocumentIsLoaded():
    """Sanity check independent of LM Studio: the document parser sees a
    healthy mix of TP / FP lines for every evaluated PII type."""
    scan_document, annotations, positions = _parse_document()
    assert scan_document, "scan_document must not be empty"
    assert len(annotations) == len(positions), (
        "annotations and positions must be aligned"
    )

    per_type_counts: Dict[str, Dict[str, int]] = defaultdict(
        lambda: {"TP": 0, "FP": 0}
    )
    for ann in annotations:
        per_type_counts[ann.pii_type][ann.verdict] += 1

    # Cross-check: every annotated line's text appears at the recorded
    # position in scan_document.
    for ann, pos in zip(annotations, positions):
        slice_ = scan_document[pos.start : pos.end]
        assert slice_ == ann.text, (
            f"line {ann.line_number}: scan_document[{pos.start}:{pos.end}]"
            f"={slice_!r} but annotated text was {ann.text!r}."
        )

    # Each evaluated type must contribute at least one TP and one FP so the
    # judge can be exercised meaningfully.
    missing: List[str] = []
    for pii_type, counts in per_type_counts.items():
        if counts["TP"] == 0 or counts["FP"] == 0:
            missing.append(
                f"{pii_type}: TP={counts['TP']} FP={counts['FP']} "
                "(both must be > 0)"
            )
    assert not missing, (
        "fixture document has unbalanced type coverage:\n"
        + "\n".join(f"  - {m}" for m in missing)
    )

    log.info(
        "[meta] %d types annotated, %d total lines (%s)",
        len(per_type_counts),
        len(annotations),
        ", ".join(
            f"{t}={c['TP']}TP/{c['FP']}FP" for t, c in sorted(per_type_counts.items())
        ),
    )
