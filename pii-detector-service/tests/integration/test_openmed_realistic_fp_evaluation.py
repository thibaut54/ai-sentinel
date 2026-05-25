"""
Realistic FP/FN evaluation for the 12 PII types OpenMed must support
in production (PASSWORD, CVV, PIN, IMEI, BITCOIN/ETHEREUM/LITECOIN_ADDRESS,
VEHICLE_VIN, VEHICLE_REGISTRATION, ACCOUNT_NAME, BANK_ACCOUNT, CREDIT_CARD).

Why this test exists when ``test_openmed_pipeline_benchmark.py`` already runs
recall/specificity on those labels: the existing benchmark uses **short
one-line fixtures** and could not surface the high FP rate observed by
``CorpusDataSqlComparisonIT#runImprovedV3WithOpenMed`` on the real Confluence
corpus. The real corpus contains long documents with technical noise (SHA
hashes, GUIDs, JWT, project codes, ObjectIds, Docker layer hashes, Tika-
extracted Excel/HTML) that mimic the surface form of crypto / VIN / IMEI /
account numbers and trigger OpenMed.

This test reproduces those conditions in **isolated Python** (no Java backend,
no gRPC, no Testcontainers, no Postgres) so we get a fast (< 30 min) gate on
whether OpenMed is "exploitable" against the 12 types before investing in a
full Java IT.

Methodology (inspired by the OpenAI Privacy Filter Model Card §7):

* 6 evaluation axes per type:
    1. ``canonical_with_clue``   — valid PII with explicit contextual cue
    2. ``canonical_no_clue``     — same value, isolated (no context)
    3. ``look_alikes``           — non-PII strings whose surface form mimics
                                    the type (hashes, GUIDs, codes...).
                                    Must NOT be flagged.
    4. ``explicit_negatives``    — text mentioning the type's keyword without
                                    an actual value (policy, documentation,
                                    CLI argument). Must NOT be flagged.
    5. ``adversarial_formatting``— spacing, line breaks, digit-words, symbol
                                    substitution (cf. Model Card §7.5.4)
    6. ``long_context``          — PII (or no PII) buried inside 2-5 kchar
                                    Confluence-like documents with noise

* 4 languages: FR (primary, État de Vaud Confluence is French), EN, DE, IT.

* Pass criteria per type:
    - ``FP_rate = FP / (FP + TP)`` MUST be <= 0.20 (max 20% false positives
      on detections). Aligns with the user brief.
    - ``recall = TP / (TP + FN)`` MUST be >= 0.50 (otherwise the model is
      not useful regardless of precision).
    - WARN-level log if ``FP_rate`` is in (0.10, 0.20] (ideal is < 10%).

* Matching rule (TP vs FP): IoU >= 0.5 on character offsets, same canonical
  ``pii_type``. Mirrors ``detection_merger.py::_resolve_overlaps_for_type``.

Run invocations::

    # Full evaluation (12 types, ~5-15 min on warm CPU)
    pytest tests/integration/test_openmed_realistic_fp_evaluation.py -v

    # Single type
    pytest tests/integration/test_openmed_realistic_fp_evaluation.py \\
        -v -k PASSWORD

    # Generate the aggregated markdown report only (after a previous run)
    pytest tests/integration/test_openmed_realistic_fp_evaluation.py::\\
        test_Should_ProduceAggregatedReport_When_AllPerTypeTestsCompleted -v

Output artifacts written to ``target/openmed-fp-eval/``:

* ``findings.jsonl`` — one JSON line per finding with full context
* ``metrics.json``   — aggregated metrics per type, axis, language
* ``report.md``      — markdown summary with verdict, top FP examples, and
                       per-axis breakdown
"""

from __future__ import annotations

import json
import logging
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import pytest

log = logging.getLogger("openmed_fp_eval")

# Fixtures contain Unicode (€, ₹, ä, ö, …). Force UTF-8 on Windows so the
# trace stays readable.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):  # pragma: no cover - stdout already wrapped
    pass


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# The 12 types from the user brief, with their OpenMed raw labels and the
# thresholds currently configured in data-openmed-no-gliner.sql.
# Source of truth: ``pii-reporting-api/src/test/resources/sql/data-openmed-no-gliner.sql``
EVALUATED_TYPES: List[Tuple[str, str, float]] = [
    # (canonical_pii_type, openmed_raw_label, threshold)
    ("PASSWORD",              "PASSWORD",          0.85),
    ("CVV",                   "CVV",               0.85),
    ("PIN",                   "PIN",               0.85),
    ("IMEI",                  "IMEI",              0.85),
    ("BITCOIN_ADDRESS",       "BITCOINADDRESS",    0.85),
    ("ETHEREUM_ADDRESS",      "ETHEREUMADDRESS",   0.85),
    ("LITECOIN_ADDRESS",      "LITECOINADDRESS",   0.85),
    ("VEHICLE_VIN",           "VIN",               0.85),
    ("VEHICLE_REGISTRATION",  "VRM",               0.85),
    ("ACCOUNT_NAME",          "ACCOUNTNAME",       0.85),
    ("BANK_ACCOUNT",          "BANKACCOUNT",       0.85),
    ("CREDIT_CARD",           "CREDITCARD",        0.85),
]

PII_TYPE_TO_LABEL: Dict[str, str] = {t[0]: t[1] for t in EVALUATED_TYPES}
PII_TYPE_TO_THRESHOLD: Dict[str, float] = {t[0]: t[2] for t in EVALUATED_TYPES}

# Permissive global threshold; per-type overrides do the final cut.
# Mirrors ``OpenMedDetector.DEFAULT_GLOBAL_THRESHOLD``.
GLOBAL_THRESHOLD = 0.30

# Pass/fail thresholds (cf. user brief: "less than 20% FP, ideally 10%").
MAX_FP_RATE = 0.20
WARN_FP_RATE = 0.10
MIN_RECALL = 0.50

# IoU threshold for matching a finding to an expected span. 0.5 is the value
# used by ``detection_merger.py`` for cross-detector overlap resolution.
IOU_MATCH_THRESHOLD = 0.5

# Valid axis names (any extra value in fixtures will fail the meta-test).
VALID_AXES = frozenset({
    "canonical_with_clue",
    "canonical_no_clue",
    "look_alikes",
    "explicit_negatives",
    "adversarial_formatting",
    "long_context",
})

VALID_LANGUAGES = frozenset({"fr", "en", "de", "it"})

FIXTURES_DIR = (
    Path(__file__).resolve().parent.parent / "resources" / "openmed-fp-eval"
)

OUTPUT_DIR = (
    Path(__file__).resolve().parent.parent.parent / "target" / "openmed-fp-eval"
)


# ---------------------------------------------------------------------------
# Skip guard — OpenMed needs transformers >= 5.0
# ---------------------------------------------------------------------------

def _transformers_supports_openmed() -> bool:
    try:
        import transformers
        major = int(transformers.__version__.split(".", 1)[0])
        return major >= 5
    except Exception:
        return False


pytestmark = [
    pytest.mark.integration,
    pytest.mark.slow,
    pytest.mark.skipif(
        not _transformers_supports_openmed(),
        reason="OpenMed requires transformers >= 5.0.",
    ),
]


# ---------------------------------------------------------------------------
# Domain dataclasses (do not depend on the pii_detector package so this file
# can be collected/inspected even when the detector cannot be loaded).
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ExpectedSpan:
    """An annotated PII span the model is expected to detect at byte-exact
    offsets in the fixture text. ``value`` is informational (sanity check
    against ``text[start:end]``)."""
    start: int
    end: int
    value: str


@dataclass(frozen=True)
class EvalCase:
    """One synthetic case to evaluate. ``expected_spans`` may be empty for
    look-alike / explicit-negative / long-context-negative cases."""
    case_id: str
    pii_type: str
    detector_label: str
    threshold: float
    language: str
    axis: str
    text: str
    expected_spans: Tuple[ExpectedSpan, ...]


@dataclass
class CaseResult:
    """Outcome of running a single case through the detector."""
    case: EvalCase
    detected_spans: List[Tuple[int, int, float, str]]  # (start, end, score, value)
    tp: int
    fp: int
    fn: int
    elapsed_ms: int

    @property
    def has_findings(self) -> bool:
        return bool(self.detected_spans)


@dataclass
class TypeAggregate:
    """Aggregated counters for a single PII type across all its cases."""
    pii_type: str
    tp: int = 0
    fp: int = 0
    fn: int = 0
    cases: int = 0
    per_axis: Dict[str, Dict[str, int]] = field(
        default_factory=lambda: defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
    )
    per_language: Dict[str, Dict[str, int]] = field(
        default_factory=lambda: defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
    )
    top_fp_examples: List[Tuple[str, str, str]] = field(default_factory=list)
    # (case_id, detected_value, surrounding_context)

    def record(self, result: CaseResult) -> None:
        self.cases += 1
        self.tp += result.tp
        self.fp += result.fp
        self.fn += result.fn
        axis_bucket = self.per_axis[result.case.axis]
        axis_bucket["tp"] += result.tp
        axis_bucket["fp"] += result.fp
        axis_bucket["fn"] += result.fn
        lang_bucket = self.per_language[result.case.language]
        lang_bucket["tp"] += result.tp
        lang_bucket["fp"] += result.fp
        lang_bucket["fn"] += result.fn
        # Stash a few raw FP samples for the report.
        if result.fp > 0 and len(self.top_fp_examples) < 5:
            for start, end, _score, value in result.detected_spans:
                if not self._is_tp_span(result, start, end):
                    ctx = self._snippet(result.case.text, start, end)
                    self.top_fp_examples.append((result.case.case_id, value, ctx))
                    if len(self.top_fp_examples) >= 5:
                        break

    @staticmethod
    def _is_tp_span(result: CaseResult, start: int, end: int) -> bool:
        for exp in result.case.expected_spans:
            if iou(start, end, exp.start, exp.end) >= IOU_MATCH_THRESHOLD:
                return True
        return False

    @staticmethod
    def _snippet(text: str, start: int, end: int, radius: int = 60) -> str:
        a = max(0, start - radius)
        b = min(len(text), end + radius)
        s = text[a:b].replace("\n", " ").replace("\r", " ").strip()
        return s[:200]

    @property
    def precision(self) -> float:
        d = self.tp + self.fp
        return self.tp / d if d > 0 else 0.0

    @property
    def recall(self) -> float:
        d = self.tp + self.fn
        return self.tp / d if d > 0 else 0.0

    @property
    def fp_rate(self) -> float:
        d = self.tp + self.fp
        return self.fp / d if d > 0 else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return (2 * p * r) / (p + r) if (p + r) > 0 else 0.0


# ---------------------------------------------------------------------------
# Span matching utilities
# ---------------------------------------------------------------------------

def iou(a_start: int, a_end: int, b_start: int, b_end: int) -> float:
    """Intersection over Union on two character ranges. Returns 0.0 on
    disjoint ranges or empty intervals."""
    if a_end <= a_start or b_end <= b_start:
        return 0.0
    inter = max(0, min(a_end, b_end) - max(a_start, b_start))
    if inter == 0:
        return 0.0
    union = max(a_end, b_end) - min(a_start, b_start)
    return inter / union if union > 0 else 0.0


def match_findings(
    detected: List[Tuple[int, int]],
    expected: List[ExpectedSpan],
) -> Tuple[int, int, int]:
    """Greedy 1-to-1 matching of detected vs expected spans using IoU >= 0.5.

    Returns ``(tp, fp, fn)``. Each detected span can match at most one
    expected span and vice versa — bipartite matching done greedily by
    descending IoU to avoid greedy artifacts on near-duplicate detections.
    """
    if not detected and not expected:
        return 0, 0, 0
    if not detected:
        return 0, 0, len(expected)
    if not expected:
        return 0, len(detected), 0

    pairs: List[Tuple[float, int, int]] = []
    for di, (ds, de) in enumerate(detected):
        for ei, exp in enumerate(expected):
            score = iou(ds, de, exp.start, exp.end)
            if score >= IOU_MATCH_THRESHOLD:
                pairs.append((score, di, ei))
    pairs.sort(reverse=True)  # highest IoU first

    used_d: set = set()
    used_e: set = set()
    tp = 0
    for _score, di, ei in pairs:
        if di in used_d or ei in used_e:
            continue
        used_d.add(di)
        used_e.add(ei)
        tp += 1

    fp = len(detected) - tp
    fn = len(expected) - tp
    return tp, fp, fn


# ---------------------------------------------------------------------------
# Fixture loader
# ---------------------------------------------------------------------------

def _load_cases_for_type(pii_type: str) -> List[EvalCase]:
    """Read ``tests/resources/openmed-fp-eval/{pii_type}.json`` and yield
    ``EvalCase`` instances. Validates structure aggressively — malformed
    fixtures should fail at collection rather than midway through a run."""
    path = FIXTURES_DIR / f"{pii_type}.json"
    if not path.exists():
        raise AssertionError(
            f"Missing fixture file for {pii_type}: {path}. "
            f"Each type in EVALUATED_TYPES needs a JSON fixture."
        )
    with path.open("r", encoding="utf-8") as fp:
        payload = json.load(fp)

    declared_type = payload.get("pii_type")
    if declared_type != pii_type:
        raise AssertionError(
            f"Fixture file {path.name} declares pii_type={declared_type!r}"
            f" but was loaded for {pii_type!r}."
        )
    detector_label = payload["detector_label"]
    threshold = float(payload.get("threshold", PII_TYPE_TO_THRESHOLD[pii_type]))

    cases: List[EvalCase] = []
    for raw in payload["cases"]:
        case_id = raw["id"]
        language = raw["language"]
        axis = raw["axis"]
        text = raw["text"]
        spans_raw = raw.get("expected_spans", [])
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
            for s in spans_raw
        )
        # Sanity check: text[start:end] must equal value (catch off-by-one
        # in hand-written fixtures before they pollute the metrics).
        for sp in spans:
            slice_ = text[sp.start:sp.end]
            if slice_ != sp.value:
                raise AssertionError(
                    f"{case_id}: text[{sp.start}:{sp.end}]={slice_!r} "
                    f"but expected_spans.value={sp.value!r}. "
                    f"Re-check offsets — they must be byte-exact."
                )
        cases.append(EvalCase(
            case_id=case_id,
            pii_type=pii_type,
            detector_label=detector_label,
            threshold=threshold,
            language=language,
            axis=axis,
            text=text,
            expected_spans=spans,
        ))
    return cases


# ---------------------------------------------------------------------------
# Pytest fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def openmed_detector():
    """Loads the 2.7 GB OpenMed model **once** for the whole session.

    Reuses ``OpenMedDetector`` from the production codebase to ensure we're
    testing the exact same chunking / threshold / mapping logic that runs in
    prod via gRPC."""
    from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector

    log.info("[setup] loading OpenMedDetector — 2.7 GB model download/cache hit")
    detector = OpenMedDetector()
    t0 = time.time()
    detector.load_model()
    log.info("[setup] detector ready in %.2fs", time.time() - t0)
    return detector


@pytest.fixture(scope="session")
def pii_type_configs() -> Dict[str, Dict[str, Any]]:
    """Mirrors ``data-openmed-no-gliner.sql`` for the 12 evaluated types.
    Format matches ``OpenMedDetector._resolve_runtime_config`` expectations."""
    configs: Dict[str, Dict[str, Any]] = {}
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


@pytest.fixture(scope="session")
def results_collector() -> Dict[str, TypeAggregate]:
    """Shared collector populated by per-type tests, then read by the final
    aggregate report test. The session scope guarantees we get one
    ``TypeAggregate`` per ``pii_type`` regardless of test order."""
    return {}


@pytest.fixture(scope="session", autouse=True)
def _ensure_output_dir() -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    return OUTPUT_DIR


# ---------------------------------------------------------------------------
# Per-case scan helper
# ---------------------------------------------------------------------------

def _scan_case(detector, configs: Dict, case: EvalCase) -> CaseResult:
    t0 = time.time()
    entities = detector.detect_pii(
        case.text,
        threshold=GLOBAL_THRESHOLD,
        pii_type_configs=configs,
    )
    elapsed_ms = int((time.time() - t0) * 1000)

    # Restrict to entities of the type under test — OpenMed may emit other
    # types if their labels are enabled, but they don't count for this case.
    relevant = [e for e in entities if e.pii_type == case.pii_type]
    detected_spans: List[Tuple[int, int, float, str]] = [
        (e.start, e.end, float(e.score), case.text[e.start:e.end])
        for e in relevant
    ]
    tp, fp, fn = match_findings(
        [(s, ed) for s, ed, _sc, _v in detected_spans],
        list(case.expected_spans),
    )
    return CaseResult(
        case=case,
        detected_spans=detected_spans,
        tp=tp,
        fp=fp,
        fn=fn,
        elapsed_ms=elapsed_ms,
    )


def _append_findings_jsonl(results: Iterable[CaseResult]) -> None:
    """Append every detected span + every missed expected span to
    ``findings.jsonl`` for downstream analysis."""
    path = OUTPUT_DIR / "findings.jsonl"
    with path.open("a", encoding="utf-8") as fp:
        for r in results:
            # Emit detections (TP or FP)
            for start, end, score, value in r.detected_spans:
                is_tp = any(
                    iou(start, end, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
                    for ex in r.case.expected_spans
                )
                fp.write(json.dumps({
                    "case_id": r.case.case_id,
                    "pii_type": r.case.pii_type,
                    "axis": r.case.axis,
                    "language": r.case.language,
                    "kind": "detection",
                    "verdict": "TP" if is_tp else "FP",
                    "start": start,
                    "end": end,
                    "score": score,
                    "value": value,
                }) + "\n")
            # Emit missed expected spans
            for ex in r.case.expected_spans:
                matched = any(
                    iou(ds, de, ex.start, ex.end) >= IOU_MATCH_THRESHOLD
                    for ds, de, _s, _v in r.detected_spans
                )
                if not matched:
                    fp.write(json.dumps({
                        "case_id": r.case.case_id,
                        "pii_type": r.case.pii_type,
                        "axis": r.case.axis,
                        "language": r.case.language,
                        "kind": "miss",
                        "verdict": "FN",
                        "start": ex.start,
                        "end": ex.end,
                        "value": ex.value,
                    }) + "\n")


# ---------------------------------------------------------------------------
# Parametrized per-type tests
# ---------------------------------------------------------------------------

_TYPE_PARAMS = [
    pytest.param(pii_type, id=pii_type)
    for pii_type in PII_TYPE_TO_LABEL.keys()
]


@pytest.mark.parametrize("pii_type", _TYPE_PARAMS)
def test_Should_HaveFpRateUnder20PctAndRecallOver50Pct_When_RunningRealisticCases(
    openmed_detector,
    pii_type_configs,
    results_collector,
    pii_type,
):
    """Per-type assertion: with ~20-30 realistic synthetic cases covering
    6 axes and 4 languages, OpenMed must keep ``FP_rate <= 0.20`` AND
    ``recall >= 0.50`` for the type to be declared "exploitable" in the
    Confluence corpus context.

    Failure points to one of three things, in order of likelihood:
      1. The fixture is mis-annotated (offsets off-by-one) — fix the JSON.
      2. The DB-level threshold (0.85) for this type is too lax/strict
         given the fixture distribution — tune ``data.sql``.
      3. OpenMed genuinely cannot meet 80% precision on this type and we
         must either disable it or pair it with a Regex/Presidio fallback.

    The aggregate test downstream produces a report that helps tell those
    apart by breaking down per axis and language."""
    cases = _load_cases_for_type(pii_type)
    assert cases, f"No cases loaded for {pii_type}"

    log.info("[%s] running %d cases", pii_type, len(cases))
    aggregate = TypeAggregate(pii_type=pii_type)
    case_results: List[CaseResult] = []
    t0 = time.time()
    for case in cases:
        result = _scan_case(openmed_detector, pii_type_configs, case)
        aggregate.record(result)
        case_results.append(result)
    elapsed_s = time.time() - t0

    _append_findings_jsonl(case_results)
    results_collector[pii_type] = aggregate

    log.info(
        "[%s] cases=%d tp=%d fp=%d fn=%d "
        "precision=%.3f recall=%.3f FP_rate=%.3f F1=%.3f (%.1fs)",
        pii_type, aggregate.cases, aggregate.tp, aggregate.fp, aggregate.fn,
        aggregate.precision, aggregate.recall, aggregate.fp_rate, aggregate.f1,
        elapsed_s,
    )

    if WARN_FP_RATE < aggregate.fp_rate <= MAX_FP_RATE:
        log.warning(
            "[%s] FP_rate=%.3f exceeds ideal target %.2f (still under the "
            "hard cap of %.2f). Consider tightening the threshold or "
            "improving look-alike coverage.",
            pii_type, aggregate.fp_rate, WARN_FP_RATE, MAX_FP_RATE,
        )

    # Hard assertion #1: FP rate must be at most MAX_FP_RATE.
    assert aggregate.fp_rate <= MAX_FP_RATE, (
        f"[{pii_type}] FP_rate={aggregate.fp_rate:.3f} exceeds cap "
        f"{MAX_FP_RATE:.2f}. tp={aggregate.tp} fp={aggregate.fp} "
        f"fn={aggregate.fn}. Top FP examples: "
        + "; ".join(
            f"{cid}: {val!r} in '{ctx[:80]}...'"
            for cid, val, ctx in aggregate.top_fp_examples[:3]
        )
    )

    # Hard assertion #2: recall must be at least MIN_RECALL.
    assert aggregate.recall >= MIN_RECALL, (
        f"[{pii_type}] recall={aggregate.recall:.3f} below floor "
        f"{MIN_RECALL:.2f}. The model misses too many positives to be "
        f"useful, regardless of precision. tp={aggregate.tp} "
        f"fn={aggregate.fn} cases={aggregate.cases}."
    )


# ---------------------------------------------------------------------------
# Aggregate report
# ---------------------------------------------------------------------------

def test_Should_ProduceAggregatedReport_When_AllPerTypeTestsCompleted(
    results_collector,
):
    """Final test: consolidates per-type results into ``report.md`` and
    ``metrics.json``. Skips cleanly when run in isolation (e.g. via
    ``-k aggregated`` without the per-type tests).

    This test never fails on metric thresholds — its job is reporting. The
    per-type tests carry the verdict. We do assert that at least one type
    was recorded, otherwise the report is misleading."""
    if not results_collector:
        pytest.skip("No per-type results recorded; run the full file instead.")

    metrics_path = OUTPUT_DIR / "metrics.json"
    report_path = OUTPUT_DIR / "report.md"

    _write_metrics_json(metrics_path, results_collector)
    _write_markdown_report(report_path, results_collector)

    log.info("[aggregate] report written to %s", report_path)
    log.info("[aggregate] metrics written to %s", metrics_path)


def _write_metrics_json(path: Path, collector: Dict[str, TypeAggregate]) -> None:
    payload = {}
    for pii_type, agg in collector.items():
        payload[pii_type] = {
            "cases": agg.cases,
            "tp": agg.tp,
            "fp": agg.fp,
            "fn": agg.fn,
            "precision": round(agg.precision, 4),
            "recall": round(agg.recall, 4),
            "fp_rate": round(agg.fp_rate, 4),
            "f1": round(agg.f1, 4),
            "per_axis": {ax: dict(d) for ax, d in agg.per_axis.items()},
            "per_language": {lg: dict(d) for lg, d in agg.per_language.items()},
        }
    with path.open("w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, ensure_ascii=False)


def _write_markdown_report(path: Path, collector: Dict[str, TypeAggregate]) -> None:
    lines: List[str] = []
    lines.append("# OpenMed realistic FP/FN evaluation — report\n")
    lines.append(
        f"- Pass criteria: `FP_rate <= {MAX_FP_RATE:.2f}` AND "
        f"`recall >= {MIN_RECALL:.2f}` per type.\n"
        f"- WARN if FP_rate > {WARN_FP_RATE:.2f} (ideal is below)."
    )
    lines.append("")

    # Verdict table
    lines.append("## Verdict per type")
    lines.append("")
    lines.append("| PII type | Cases | TP | FP | FN | Precision | Recall | FP rate | F1 | Verdict |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for pii_type in PII_TYPE_TO_LABEL.keys():
        agg = collector.get(pii_type)
        if agg is None:
            lines.append(
                f"| {pii_type} | — | — | — | — | — | — | — | — | NOT RUN |"
            )
            continue
        pass_fp = agg.fp_rate <= MAX_FP_RATE
        pass_recall = agg.recall >= MIN_RECALL
        if pass_fp and pass_recall:
            verdict = "PASS" if agg.fp_rate <= WARN_FP_RATE else "PASS (WARN)"
        else:
            failures = []
            if not pass_fp:
                failures.append(f"FP_rate>{MAX_FP_RATE:.2f}")
            if not pass_recall:
                failures.append(f"recall<{MIN_RECALL:.2f}")
            verdict = "FAIL (" + ", ".join(failures) + ")"
        lines.append(
            f"| {pii_type} | {agg.cases} | {agg.tp} | {agg.fp} | {agg.fn} "
            f"| {agg.precision:.3f} | {agg.recall:.3f} "
            f"| {agg.fp_rate:.3f} | {agg.f1:.3f} | {verdict} |"
        )
    lines.append("")

    # Per-axis breakdown
    lines.append("## Per-axis breakdown")
    lines.append("")
    lines.append("Helps tell apart whether a high FP rate comes from look-alikes (model"
                 " can't tell hashes from crypto addresses) vs adversarial formatting"
                 " vs long_context (chunking artifacts).")
    lines.append("")
    for pii_type, agg in collector.items():
        lines.append(f"### {pii_type}")
        lines.append("")
        lines.append("| Axis | TP | FP | FN | FP rate |")
        lines.append("|---|---:|---:|---:|---:|")
        for axis in sorted(VALID_AXES):
            d = agg.per_axis.get(axis, {"tp": 0, "fp": 0, "fn": 0})
            tp, fp, fn = d["tp"], d["fp"], d["fn"]
            fp_rate = fp / (fp + tp) if (fp + tp) > 0 else 0.0
            lines.append(
                f"| {axis} | {tp} | {fp} | {fn} | {fp_rate:.3f} |"
            )
        lines.append("")

    # Per-language breakdown
    lines.append("## Per-language breakdown")
    lines.append("")
    for pii_type, agg in collector.items():
        lines.append(f"### {pii_type}")
        lines.append("")
        lines.append("| Lang | TP | FP | FN | FP rate |")
        lines.append("|---|---:|---:|---:|---:|")
        for lang in sorted(VALID_LANGUAGES):
            d = agg.per_language.get(lang, {"tp": 0, "fp": 0, "fn": 0})
            tp, fp, fn = d["tp"], d["fp"], d["fn"]
            fp_rate = fp / (fp + tp) if (fp + tp) > 0 else 0.0
            lines.append(
                f"| {lang} | {tp} | {fp} | {fn} | {fp_rate:.3f} |"
            )
        lines.append("")

    # Top FP examples (debugging aid)
    lines.append("## Top FP examples")
    lines.append("")
    lines.append("First few false positives per type with their surrounding context."
                 " Used to diagnose whether OpenMed gets confused by a specific noise"
                 " pattern (hash, GUID, code) or by ambiguous wording.")
    lines.append("")
    for pii_type, agg in collector.items():
        if not agg.top_fp_examples:
            continue
        lines.append(f"### {pii_type}")
        lines.append("")
        for case_id, value, ctx in agg.top_fp_examples:
            lines.append(f"- `{case_id}` → wrongly flagged `{value}` in: "
                         f"`{ctx}`")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# Meta-test: fixture sanity
# ---------------------------------------------------------------------------

def test_Should_HaveValidFixtureForEveryEvaluatedType_When_TestCollectionStarts():
    """Fail fast at collection time if a fixture file is missing or malformed.
    Catches off-by-one offsets and typos in axis/language names before they
    silently pollute the metrics."""
    missing: List[str] = []
    total_cases = 0
    for pii_type in PII_TYPE_TO_LABEL.keys():
        try:
            cases = _load_cases_for_type(pii_type)
        except AssertionError as exc:
            missing.append(f"{pii_type}: {exc}")
            continue
        if not cases:
            missing.append(f"{pii_type}: fixture loaded but contains 0 cases.")
            continue
        total_cases += len(cases)
        # Coverage check: each type should cover ALL 6 axes (otherwise the
        # per-axis report will have gaping holes that mislead the diagnosis).
        axes_covered = {c.axis for c in cases}
        missing_axes = VALID_AXES - axes_covered
        if missing_axes:
            missing.append(
                f"{pii_type}: missing axes {sorted(missing_axes)} "
                f"(only covers {sorted(axes_covered)})."
            )
    assert not missing, (
        "Fixture coverage gaps detected (run will be misleading until fixed):\n"
        + "\n".join(f"  - {m}" for m in missing)
    )
    log.info(
        "[meta] %d types validated, total %d cases ready to evaluate.",
        len(PII_TYPE_TO_LABEL), total_cases,
    )
