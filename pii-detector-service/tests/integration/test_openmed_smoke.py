"""
OpenMed detector smoke test.

Ultra-fast sanity check: builds the detector once, runs a single inference on
a short multi-PII sentence, and asserts that the obvious entities come back.
Designed to fail fast when the detector is broken (model load, pipeline
caching, label mapping) without paying the cost of the full benchmark.

Total runtime target: < 30 s (mostly model load on first run, < 2 s after).
"""

from __future__ import annotations

import sys
import time
from typing import Dict

import pytest

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):  # pragma: no cover - stdout already wrapped
    pass


SMOKE_TEXT = (
    "Hi, my name is John Smith. My SSN is 123-45-6789 and my IBAN is "
    "DE89 3704 0044 0532 0130 00. You can reach me at john.smith@example.com "
    "or with card 4111-1111-1111-1111 (CVV 123)."
)

# (raw OpenMed label, canonical pii_type, threshold) — mirror of data.sql.
# Limited to the PII we actually plant in SMOKE_TEXT so failures point at
# specific labels.
SMOKE_CONFIG = [
    ("SSN", "SSN", 0.85),
    ("IBAN", "IBAN", 0.85),
    ("CREDITCARD", "CREDIT_CARD", 0.85),
    ("CVV", "CVV", 0.85),
]
SMOKE_LABELS = [pii_type for _, pii_type, _ in SMOKE_CONFIG]


def _transformers_supports_openmed() -> bool:
    try:
        import transformers

        major = int(transformers.__version__.split(".", 1)[0])
        return major >= 5
    except Exception:
        return False


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not _transformers_supports_openmed(),
        reason="OpenMed requires transformers >= 5.0.",
    ),
]


def _build_configs() -> Dict[str, Dict]:
    configs: Dict[str, Dict] = {}
    for label, pii_type, threshold in SMOKE_CONFIG:
        configs[f"OPENMED:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "OPENMED",
            "category": "TEST",
            "country_code": None,
            "detector_label": label,
        }
    return configs


@pytest.fixture(scope="module")
def detector():
    from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector

    d = OpenMedDetector()
    t0 = time.time()
    d.load_model()
    print(f"\n[smoke] model load took {time.time() - t0:.2f}s")
    return d


def test_Should_DetectAllPlantedPii_When_RunningSmokeText(detector):
    """Single-shot smoke: every label planted in SMOKE_TEXT must be detected."""
    configs = _build_configs()

    t0 = time.time()
    entities = detector.detect_pii(SMOKE_TEXT, threshold=0.30, pii_type_configs=configs)
    elapsed = time.time() - t0

    detected_types = {e.pii_type for e in entities}
    print(
        f"\n[smoke] inference took {elapsed:.2f}s, "
        f"got {len(entities)} entities: "
        + ", ".join(f"{e.pii_type}({e.score:.2f}:{e.text!r})" for e in entities)
    )

    missing = [t for t in SMOKE_LABELS if t not in detected_types]
    assert not missing, (
        f"Smoke failed: planted PII types not detected: {missing}. "
        f"Got types: {sorted(detected_types)}. "
        f"Raw entities: {[(e.pii_type, e.score, e.text) for e in entities]}"
    )

    # Inference itself must be fast once model is warm. 5 s is a generous
    # CPU bound; if we ever cross it, regressions in caching are likely.
    assert elapsed < 5.0, (
        f"Inference took {elapsed:.2f}s — likely a caching regression "
        f"(expected < 5s once model is warm)."
    )
