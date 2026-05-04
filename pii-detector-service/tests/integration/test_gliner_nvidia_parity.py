"""
Integration test: parity between local GLiNER and NVIDIA-hosted gliner-PII.

Verifies that the locally loaded `nvidia/gliner-PII` model returns the same
findings as the NVIDIA demo at https://build.nvidia.com/nvidia/gliner-pii
when run on identical input text with identical labels.

Setup of the baseline:
- Labels: every GLiNER `detector_label` flagged enabled=true in
  `pii-reporting-api/src/main/resources/data-old.sql` (snake_case taxonomy).
- Fixture text: `tests/resources/gliner-parity-baseline.txt`.
- Baseline entities: `tests/resources/gliner-parity-baseline.json` -- captured
  on 2026-04-29 via Chrome DevTools by submitting the fixture to NVIDIA's
  Free Endpoint with the labels above and threshold=0.4.

Local vs NVIDIA may differ slightly because the hosted endpoint uses
chunk_length=384 / overlap=128 and may post-process spans. The test asserts
that the local model still finds every NVIDIA entity by (label + overlapping
span), not strict offset equality, and that label coverage matches.

Usage:
    pytest tests/integration/test_gliner_nvidia_parity.py -s -m integration
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

MODEL_ID = "nvidia/gliner-PII"
THRESHOLD = 0.4

RESOURCES_DIR = Path(__file__).resolve().parent.parent / "resources"
FIXTURE_TEXT_PATH = RESOURCES_DIR / "gliner-parity-baseline.txt"
BASELINE_JSON_PATH = RESOURCES_DIR / "gliner-parity-baseline.json"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _spans_overlap(a_start: int, a_end: int, b_start: int, b_end: int) -> bool:
    """True iff [a_start, a_end) and [b_start, b_end) share at least one char."""
    return a_start < b_end and b_start < a_end


def _find_matching_local(baseline: Dict[str, Any], local: List[Dict[str, Any]]) -> Dict[str, Any] | None:
    """Return a local entity with the same label and an overlapping span, if any."""
    for e in local:
        same_label = e["label"] == baseline["label"]
        if same_label and _spans_overlap(e["start"], e["end"], baseline["start"], baseline["end"]):
            return e
    return None


def _label_coverage(entities: List[Dict[str, Any]]) -> Set[str]:
    return {e["label"] for e in entities}


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def fixture_text() -> str:
    return FIXTURE_TEXT_PATH.read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def baseline() -> Dict[str, Any]:
    return json.loads(BASELINE_JSON_PATH.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def labels(baseline) -> List[str]:
    return list(baseline["_meta"]["labels"])


@pytest.fixture(scope="module")
def local_entities(fixture_text, labels) -> List[Dict[str, Any]]:
    """Run the local nvidia/gliner-PII model with the same labels and threshold."""
    from gliner import GLiNER

    model = GLiNER.from_pretrained(MODEL_ID)
    raw = model.predict_entities(fixture_text, labels, threshold=THRESHOLD)
    # Normalize to baseline shape: {text, label, start, end, score}
    return [
        {
            "text": e["text"],
            "label": e["label"],
            "start": int(e["start"]),
            "end": int(e["end"]),
            "score": float(e.get("score", 0.0)),
        }
        for e in raw
    ]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
class TestGlinerNvidiaParity:
    """Parity checks against the NVIDIA-hosted gliner-PII Free Endpoint baseline."""

    def test_every_nvidia_finding_has_local_match(self, baseline, local_entities, fixture_text):
        """Each entity reported by NVIDIA must be found locally (same label, overlapping span)."""
        misses: List[Tuple[Dict[str, Any], str]] = []
        for nvidia_e in baseline["entities"]:
            match = _find_matching_local(nvidia_e, local_entities)
            if match is None:
                snippet = fixture_text[nvidia_e["start"]:nvidia_e["end"]]
                misses.append((nvidia_e, snippet))

        if misses:
            print("\n[PARITY] Missing local matches for NVIDIA findings:")
            for nvidia_e, snippet in misses:
                print(
                    f"  - label={nvidia_e['label']:<32} text={nvidia_e['text']!r:<35} "
                    f"span=[{nvidia_e['start']},{nvidia_e['end']}] snippet={snippet!r}"
                )

        assert not misses, (
            f"{len(misses)}/{len(baseline['entities'])} NVIDIA entities have no local "
            f"counterpart (same label + overlapping span)."
        )

    def test_label_coverage_matches_baseline(self, baseline, local_entities):
        """Every label that produced >=1 entity at NVIDIA must produce >=1 locally."""
        baseline_labels = _label_coverage(baseline["entities"])
        local_labels = _label_coverage(local_entities)
        missing = sorted(baseline_labels - local_labels)

        print(f"\n[COVERAGE] baseline labels={len(baseline_labels)} local labels={len(local_labels)}")
        if missing:
            print(f"[COVERAGE] missing locally: {missing}")

        assert not missing, (
            f"Local model is missing {len(missing)} label(s) present in NVIDIA baseline: {missing}"
        )

    def test_local_does_not_lose_more_than_one_third_of_entities(self, baseline, local_entities):
        """Sanity: local must reproduce at least 2/3 of NVIDIA total entity count."""
        ratio = len(local_entities) / max(1, len(baseline["entities"]))
        print(
            f"\n[VOLUME] baseline={len(baseline['entities'])} local={len(local_entities)} "
            f"ratio={ratio:.2f}"
        )
        assert ratio >= 0.66, (
            f"Local entity count ({len(local_entities)}) too low vs baseline "
            f"({len(baseline['entities'])}). Possible model drift or label taxonomy mismatch."
        )


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    pytest.main([__file__, "-s", "-v", "-m", "integration"])
