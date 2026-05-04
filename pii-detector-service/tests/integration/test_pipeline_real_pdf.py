"""
Integration test: full PII pipeline end-to-end on a real document.

Exercises the production pipeline (CompositePIIDetector with GLiNER + Regex +
Presidio, then LLMValidator/Gemma 4) against a Markdown document captured
from a real Confluence page. Verifies the pipeline executes without errors,
produces entities, and -- when the LLM is available -- filters at least
some candidates while preserving most.

A JSON audit artefact (entities + LLM verdicts + context snippets) is
written under pytest's tmp_path so a human can review what the pipeline
actually does on real content.

Skips automatically when:
  - The markdown fixture is missing
  - The Postgres fallback TOML is missing (pipeline needs DB or fallback)
  - llama-cpp-python is not installed (LLM-validation tests only)

Usage:
    pytest tests/integration/test_pipeline_real_pdf.py -s -m integration
    PIPELINE_REAL_PDF_MARKDOWN=path/to/doc.md \\
        pytest tests/integration/test_pipeline_real_pdf.py -s -m integration
    PIPELINE_REAL_PDF_MAX_CHARS=20000 \\
        pytest tests/integration/test_pipeline_real_pdf.py -s -m integration
"""
from __future__ import annotations

import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import pytest

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Paths & env wiring (must run BEFORE any pii_detector import)
# ---------------------------------------------------------------------------
SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(SERVICE_ROOT))

DEFAULT_MARKDOWN = (
    SERVICE_ROOT / "scripts" / "confluence-pii-test-document-docanno-light.txt"
)
FALLBACK_TOML = SERVICE_ROOT / "config" / "test-fallback-pii-config.toml"

# The pipeline reads PII type config from Postgres. Without docker, the
# database adapter falls back to a TOML file pointed to by this env var.
# setdefault keeps any value the caller already provided.
if FALLBACK_TOML.is_file():
    os.environ.setdefault("PII_DETECTOR_TEST_FALLBACK_TOML", str(FALLBACK_TOML))


# ---------------------------------------------------------------------------
# Skip conditions
# ---------------------------------------------------------------------------
try:
    import llama_cpp  # noqa: F401

    HAS_LLAMA_CPP = True
except ImportError:
    HAS_LLAMA_CPP = False

MARKDOWN_PATH = Path(
    os.getenv("PIPELINE_REAL_PDF_MARKDOWN", str(DEFAULT_MARKDOWN))
).resolve()
HAS_MARKDOWN = MARKDOWN_PATH.is_file()
HAS_FALLBACK_TOML = FALLBACK_TOML.is_file()

# Cap document size by default to keep CI runtime reasonable. 0 = no truncation.
MAX_CHARS = int(os.getenv("PIPELINE_REAL_PDF_MAX_CHARS", "30000"))
DETECTION_THRESHOLD = float(os.getenv("PIPELINE_REAL_PDF_THRESHOLD", "0.5"))

skip_no_markdown = pytest.mark.skipif(
    not HAS_MARKDOWN, reason=f"Markdown fixture not found: {MARKDOWN_PATH}"
)
skip_no_fallback = pytest.mark.skipif(
    not HAS_FALLBACK_TOML,
    reason=f"Postgres fallback TOML not found: {FALLBACK_TOML}",
)
skip_no_llama = pytest.mark.skipif(
    not HAS_LLAMA_CPP, reason="llama-cpp-python not installed"
)


# ---------------------------------------------------------------------------
# Helpers (kept small, side-effect free)
# ---------------------------------------------------------------------------
def _load_document(path: Path, max_chars: int) -> str:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return text[:max_chars] if max_chars > 0 else text


def _attr(entity: Any, name: str, default: Any = None) -> Any:
    """Read attribute uniformly from a domain object or a dict."""
    if isinstance(entity, dict):
        return entity.get(name, default)
    return getattr(entity, name, default)


def _entity_key(entity: Any) -> Tuple[str, int, int]:
    """Stable identity key for an entity (text + offsets)."""
    return (str(_attr(entity, "text", "")), int(_attr(entity, "start", 0)), int(_attr(entity, "end", 0)))


def _split_kept_rejected(
    raw: List[Any], validated: List[Any]
) -> Tuple[List[Any], List[Any]]:
    """Return (kept, rejected) by comparing identity keys. O(N) via set lookup."""
    kept_keys = {_entity_key(e) for e in validated}
    rejected = [e for e in raw if _entity_key(e) not in kept_keys]
    return list(validated), rejected


def _context_snippet(text: str, start: int, end: int, window: int = 80) -> str:
    """Single-line excerpt centered on the entity, with [[..]] around the match."""
    a = max(0, start - window)
    b = min(len(text), end + window)
    return (
        text[a:start].replace("\n", " ")
        + "[["
        + text[start:end].replace("\n", " ")
        + "]]"
        + text[end:b].replace("\n", " ")
    )


def _pii_type_name(entity: Any) -> str:
    pii_type = _attr(entity, "pii_type") or _attr(entity, "type", "UNKNOWN")
    return pii_type.name if hasattr(pii_type, "name") else str(pii_type)


def _entity_to_dict(entity: Any, source_text: str, verdict: Optional[str]) -> Dict[str, Any]:
    """Serialize an entity (domain object or dict) into a JSON-friendly record."""
    start = int(_attr(entity, "start", 0))
    end = int(_attr(entity, "end", 0))
    return {
        "text": str(_attr(entity, "text", "")),
        "type": _pii_type_name(entity),
        "type_label": str(_attr(entity, "type_label", "") or ""),
        "start": start,
        "end": end,
        "score": float(_attr(entity, "score", 0.0) or 0.0),
        "source": str(_attr(entity, "source", "") or ""),
        "verdict": verdict,
        "context": _context_snippet(source_text, start, end),
    }


def _write_audit_report(report_path: Path, payload: Dict[str, Any]) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )
    logger.info("Audit report written: %s", report_path)


# ---------------------------------------------------------------------------
# Module-scoped fixtures (expensive: GLiNER ~90s load, Gemma ~4s load)
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def document_text() -> str:
    return _load_document(MARKDOWN_PATH, MAX_CHARS)


@pytest.fixture(scope="module")
def composite_detector():
    """Production composite detector: ML + Regex + Presidio."""
    from pii_detector.application.config.detection_policy import DetectionConfig
    from pii_detector.application.orchestration.composite_detector import (
        create_composite_detector,
    )
    from pii_detector.infrastructure.detector.gliner_detector import GLiNERDetector
    from pii_detector.infrastructure.detector.multi_pass_gliner_detector import (
        MultiPassGlinerDetector,
    )

    det_cfg = DetectionConfig()
    ml_detector = (
        MultiPassGlinerDetector(config=det_cfg)
        if "gliner" in (det_cfg.model_id or "").lower()
        else GLiNERDetector(config=det_cfg)
    )

    composite = create_composite_detector(ml_detector=ml_detector)
    started = time.monotonic()
    composite.download_model()
    composite.load_model()
    logger.info("Composite pipeline ready in %.1fs", time.monotonic() - started)
    return composite


@pytest.fixture(scope="module")
def llm_validator():
    """Gemma 4 E4B GGUF validator. Skips if the model cannot be loaded."""
    from pii_detector.infrastructure.validation.llm_validator import LLMValidator

    validator = LLMValidator(
        context_window=200,
        max_batch_size=20,
        max_output_tokens=400,
        n_gpu_layers=-1,
        n_ctx=4096,
    )
    if not validator.load_model():
        pytest.skip("LLMValidator could not load the Gemma 4 GGUF model")
    return validator


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
@skip_no_markdown
@skip_no_fallback
class TestPipelineRealPdf:
    """End-to-end pipeline test on a real Confluence-derived Markdown document."""

    def test_detection_finds_entities_with_valid_bounds(
        self, composite_detector, document_text
    ):
        """Composite detection should return well-formed entities on real content."""
        entities = composite_detector.detect_pii(
            document_text,
            threshold=DETECTION_THRESHOLD,
            enable_ml=True,
            enable_regex=True,
            enable_presidio=True,
        )

        assert entities, "Real document should yield at least one PII candidate"
        assert all(0 <= e.start < e.end <= len(document_text) for e in entities), (
            "All entities must have valid offsets"
        )
        assert all(0.0 <= float(e.score or 0.0) <= 1.0 for e in entities), (
            "All scores must be in [0, 1]"
        )

    @skip_no_llama
    def test_llm_validation_filters_some_but_not_all(
        self,
        composite_detector,
        llm_validator,
        document_text,
        tmp_path,
    ):
        """LLM should reject at least one false-positive without nuking everything."""
        raw = composite_detector.detect_pii(
            document_text,
            threshold=DETECTION_THRESHOLD,
            enable_ml=True,
            enable_regex=True,
            enable_presidio=True,
        )
        if not raw:
            pytest.skip("No entities detected -- nothing to validate")

        started = time.monotonic()
        validated = llm_validator.validate_entities(raw, document_text)
        elapsed = time.monotonic() - started

        kept, rejected = _split_kept_rejected(raw, validated)

        report_path = tmp_path / "pipeline_real_pdf_report.json"
        _write_audit_report(
            report_path,
            {
                "input": {
                    "markdown_path": str(MARKDOWN_PATH),
                    "char_count": len(document_text),
                    "max_chars_truncation": MAX_CHARS or None,
                    "threshold": DETECTION_THRESHOLD,
                },
                "metrics": {
                    "raw_count": len(raw),
                    "kept_count": len(kept),
                    "rejected_count": len(rejected),
                    "llm_inference_seconds": round(elapsed, 3),
                },
                "kept": [_entity_to_dict(e, document_text, "TRUE_POSITIVE") for e in kept],
                "rejected": [_entity_to_dict(e, document_text, "FALSE_POSITIVE") for e in rejected],
            },
        )

        assert validated is not None, "Validator must return a list, never None"
        assert len(validated) <= len(raw), (
            f"Validator cannot invent entities (raw={len(raw)}, validated={len(validated)})"
        )
        assert len(validated) > 0, (
            "Validator rejected EVERY candidate -- prompt or model regression suspected"
        )
        # Sanity threshold: real production docs always contain noise the LLM
        # can prune (acronyms, project codes, ...). If nothing is rejected on
        # tens of entities, something is silently broken upstream.
        if len(raw) >= 10:
            assert len(rejected) >= 1, (
                f"Expected at least one rejection on {len(raw)} entities, got 0"
            )

    @skip_no_llama
    def test_validator_handles_empty_input(self, llm_validator):
        assert llm_validator.validate_entities([], "any text") == []


# ---------------------------------------------------------------------------
# Allow direct execution
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    pytest.main([__file__, "-s", "-v", "-m", "integration"])
