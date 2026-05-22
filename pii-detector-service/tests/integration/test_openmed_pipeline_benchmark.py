"""
Integration benchmark for ``OpenMedDetector`` against the per-label fixtures.

Single-file design: the 2.7 GB privacy-filter model is loaded **once** via the
``session``-scoped ``openmed_detector`` fixture. ``@pytest.mark.parametrize``
expands the 24 enabled labels into 48 individual tests (recall on 10 TPs +
specificity on 10 FPs), so failures stay attributable per label while running
the full benchmark in a single pytest session.

Fixtures come from ``tests/resources/openmed-benchmark-fixtures.md``.

Common invocations::

    # Full benchmark (24 labels x 2 metrics = 48 tests, ~2 min on warm CPU)
    pytest tests/integration/test_openmed_pipeline_benchmark.py -v

    # Single label (uses pytest IDs)
    pytest tests/integration/test_openmed_pipeline_benchmark.py -v -k PASSWORD

    # List all parametrised cases
    pytest tests/integration/test_openmed_pipeline_benchmark.py --collect-only -q

Marked ``slow`` + ``integration``. Skipped when transformers < 5.0 is
installed (the model requires the transformers 5.x
``OpenAIPrivacyFilterForTokenClassification`` architecture).
"""

from __future__ import annotations

import logging
import re
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

import pytest

log = logging.getLogger("openmed_benchmark")

# Fixtures contain Unicode currency symbols (€, ₹, ¥...). The Windows default
# cp1252 stdout chokes on these — force UTF-8 so the detection trace can be
# printed safely.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):  # pragma: no cover - stdout already wrapped
    pass


# ---------------------------------------------------------------------------
# Benchmark configuration
# ---------------------------------------------------------------------------

MIN_RECALL = 0.5  # at least 5/10 true positives must be detected
MIN_SPECIFICITY = 0.5  # at least 5/10 false positives must NOT be detected
DEFAULT_GLOBAL_THRESHOLD = 0.30

# (raw OpenMed label, canonical snake_case pii_type, per-type threshold).
# Source of truth: pii-reporting-api/src/main/resources/data.sql (OPENMED).
ENABLED_OPENMED_CONFIG: List[Tuple[str, str, float]] = [
    ("SSN", "SSN", 0.85),
    ("ACCOUNTNAME", "ACCOUNT_NAME", 0.85),
    ("BANKACCOUNT", "BANK_ACCOUNT", 0.85),
    ("IBAN", "IBAN", 0.85),
    ("BIC", "BIC_SWIFT", 0.85),
    ("CREDITCARD", "CREDIT_CARD", 0.85),
    ("CREDITCARDISSUER", "CREDIT_CARD_ISSUER", 0.85),
    ("CVV", "CVV", 0.85),
    ("PIN", "PIN", 0.85),
    ("AMOUNT", "AMOUNT", 0.85),
    ("CURRENCYCODE", "CURRENCY_CODE", 0.85),
    ("BITCOINADDRESS", "BITCOIN_ADDRESS", 0.85),
    ("ETHEREUMADDRESS", "ETHEREUM_ADDRESS", 0.85),
    ("LITECOINADDRESS", "LITECOIN_ADDRESS", 0.85),
    ("VIN", "VEHICLE_VIN", 0.85),
    ("VRM", "VEHICLE_REGISTRATION", 0.85),
    ("IPADDRESS", "IP_ADDRESS", 0.85),
    ("MACADDRESS", "MAC_ADDRESS", 0.85),
    ("IMEI", "IMEI", 0.85),
    ("PASSWORD", "PASSWORD", 0.85),
]

LABEL_TO_PII_TYPE = {label: pii_type for label, pii_type, _ in ENABLED_OPENMED_CONFIG}

FIXTURES_PATH = (
    Path(__file__).resolve().parent.parent
    / "resources"
    / "openmed-benchmark-fixtures.md"
)


# ---------------------------------------------------------------------------
# Skip guard
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
# Fixture parser
# ---------------------------------------------------------------------------

_ITEM = re.compile(r"^\d+\.\s+`(?P<phrase>.+?)`", re.MULTILINE)
_TP_BLOCK = re.compile(r"#### True positives[\s\S]+?(?=\n#### |\n---|\n## |\Z)")
_FP_BLOCK = re.compile(r"#### False positives[\s\S]+?(?=\n#### |\n---|\n## |\Z)")


def _parse_fixtures(path: Path) -> Dict[str, Dict[str, List[str]]]:
    text = path.read_text(encoding="utf-8")
    by_label: Dict[str, Dict[str, List[str]]] = {}

    sections = re.split(r"^### ", text, flags=re.MULTILINE)[1:]
    for section in sections:
        header_line, _, body = section.partition("\n")
        label = header_line.strip().split()[0]
        if label not in LABEL_TO_PII_TYPE:
            continue
        tp_match = _TP_BLOCK.search(body)
        fp_match = _FP_BLOCK.search(body)
        tps = _ITEM.findall(tp_match.group(0)) if tp_match else []
        fps = _ITEM.findall(fp_match.group(0)) if fp_match else []
        by_label[label] = {"tp": tps, "fp": fps}
    return by_label


# ---------------------------------------------------------------------------
# Session-scoped fixtures (model loaded once for the entire run)
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def openmed_detector():
    """The 2.7 GB privacy-filter model is loaded **once** per pytest session.

    See ``OpenMedDetector._ensure_loaded``: a single ``PrivacyFilterTorchPipeline``
    is instantiated and reused across all subsequent ``detect_pii`` calls,
    keeping per-inference latency under ~1 s on CPU.
    """
    from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector

    log.info("Building OpenMedDetector — about to load the 2.7 GB model...")
    detector = OpenMedDetector()
    t0 = time.time()
    detector.load_model()
    log.info("Model loaded in %.2fs — running 49 parametrised tests next", time.time() - t0)
    return detector


@pytest.fixture(scope="session")
def pii_type_configs() -> Dict[str, Dict]:
    """The ``pii_type_config`` dict consumed by the detector. Mirrors data.sql."""
    configs: Dict[str, Dict] = {}
    for label, pii_type, threshold in ENABLED_OPENMED_CONFIG:
        configs[f"OPENMED:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "OPENMED",
            "category": "TEST",
            "country_code": None,
            "detector_label": label,
        }
    return configs


@pytest.fixture(scope="session")
def benchmark_fixtures() -> Dict[str, Dict[str, List[str]]]:
    """Return whatever labels the fixture file contains. Missing labels surface
    as clear ``KeyError``-style failures at the test level (not at setup), so
    swapping ``FIXTURES_PATH`` to a partial file (e.g. password-only) still
    lets the matching tests pass while only unrelated tests fail."""
    assert FIXTURES_PATH.exists(), f"Fixture file missing: {FIXTURES_PATH}"
    return _parse_fixtures(FIXTURES_PATH)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _scan_types(detector, phrase: str, configs: Dict) -> List[str]:
    t0 = time.time()
    entities = detector.detect_pii(
        phrase, threshold=DEFAULT_GLOBAL_THRESHOLD, pii_type_configs=configs,
    )
    elapsed = time.time() - t0
    types = [e.pii_type for e in entities]
    log.debug("scan %.2fs -> %s | %s", elapsed, types, phrase[:70])
    return types


def _require_label(
    benchmark_fixtures: Dict[str, Dict[str, List[str]]], label: str
) -> Dict[str, List[str]]:
    if label not in benchmark_fixtures:
        raise AssertionError(
            f"Label {label!r} not present in fixture file {FIXTURES_PATH.name}. "
            f"Available labels: {sorted(benchmark_fixtures.keys())}"
        )
    bucket = benchmark_fixtures[label]
    if not bucket.get("tp") or not bucket.get("fp"):
        raise AssertionError(
            f"Label {label!r} is missing TP or FP fixtures in {FIXTURES_PATH.name} "
            f"(tp={len(bucket.get('tp') or [])}, fp={len(bucket.get('fp') or [])})."
        )
    return bucket


_LABEL_PII_PARAMS = [
    pytest.param(label, pii_type, id=label)
    for label, pii_type in LABEL_TO_PII_TYPE.items()
]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("label,pii_type", _LABEL_PII_PARAMS)
def test_Should_DetectMajorityOfTruePositives_When_PiiTypeIsEnabled(
    openmed_detector, pii_type_configs, benchmark_fixtures, label, pii_type
):
    """Per-label recall: at least MIN_RECALL of the 10 true positives must
    have ``pii_type`` reported by the detector."""
    tps = _require_label(benchmark_fixtures, label)["tp"]
    log.info("[%s/%s] scanning %d TPs...", label, pii_type, len(tps))
    t0 = time.time()
    hits = sum(
        1 for phrase in tps
        if pii_type in _scan_types(openmed_detector, phrase, pii_type_configs)
    )
    recall = hits / max(len(tps), 1)
    log.info(
        "[%s/%s] recall=%.2f (%d/%d) — %.2fs",
        label, pii_type, recall, hits, len(tps), time.time() - t0,
    )
    assert recall >= MIN_RECALL, (
        f"OpenMed missed too many true positives for {label} ({pii_type}): "
        f"recall={recall:.2f} ({hits}/{len(tps)}). Sample missed: "
        + str([
            p for p in tps
            if pii_type not in _scan_types(openmed_detector, p, pii_type_configs)
        ][:3])
    )


@pytest.mark.parametrize("label,pii_type", _LABEL_PII_PARAMS)
def test_Should_RejectMajorityOfFalsePositives_When_PhraseIsAmbiguous(
    openmed_detector, pii_type_configs, benchmark_fixtures, label, pii_type
):
    """Per-label specificity: at least MIN_SPECIFICITY of the 10 false
    positives must NOT be flagged as ``pii_type``."""
    fps = _require_label(benchmark_fixtures, label)["fp"]
    log.info("[%s/%s] scanning %d FPs...", label, pii_type, len(fps))
    t0 = time.time()
    hits = sum(
        1 for phrase in fps
        if pii_type in _scan_types(openmed_detector, phrase, pii_type_configs)
    )
    specificity = (len(fps) - hits) / max(len(fps), 1)
    log.info(
        "[%s/%s] specificity=%.2f (%d/%d not flagged) — %.2fs",
        label, pii_type, specificity, len(fps) - hits, len(fps), time.time() - t0,
    )
    assert specificity >= MIN_SPECIFICITY, (
        f"OpenMed over-flagged false positives for {label} ({pii_type}): "
        f"specificity={specificity:.2f} ({len(fps) - hits}/{len(fps)}). "
        f"Sample wrongly flagged: "
        + str([
            p for p in fps
            if pii_type in _scan_types(openmed_detector, p, pii_type_configs)
        ][:3])
    )


def test_Should_HaveFixturesForEveryEnabledLabel_When_RunningFullBenchmark(
    benchmark_fixtures,
):
    """Meta-test: when the full fixture file is in use, every enabled label
    needs 10 TPs and 10 FPs. Auto-skipped if the file is a partial one
    (e.g. ``openmed-benchmark-fixtures-password.md``) to keep per-label
    debugging frictionless."""
    if len(benchmark_fixtures) < len(LABEL_TO_PII_TYPE):
        pytest.skip(
            f"Partial fixture file in use ({FIXTURES_PATH.name}): "
            f"{len(benchmark_fixtures)}/{len(LABEL_TO_PII_TYPE)} labels present."
        )
    missing = [
        label for label in LABEL_TO_PII_TYPE
        if label not in benchmark_fixtures
        or len(benchmark_fixtures[label]["tp"]) < 10
        or len(benchmark_fixtures[label]["fp"]) < 10
    ]
    assert not missing, (
        f"Benchmark fixture file is incomplete for: {missing}. "
        f"Each enabled label needs 10 TPs and 10 FPs."
    )
