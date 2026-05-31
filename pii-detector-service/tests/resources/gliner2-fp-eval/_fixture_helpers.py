"""Shared helpers for the GLiNER2 FP/FN evaluation fixture builders.

Byte-exact span computation is centralized here so every per-family builder
module (``_fixtures_*.py``) stays consistent with the runner's
``text[start:end] == value`` invariant. Copied verbatim from the OpenMed
generator (``openmed-fp-eval/_generate_fixtures.py``) so the two fixture sets
share identical span semantics.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional


def _span(text: str, value: str, occurrence: int = 1) -> Dict[str, Any]:
    """Return a span dict for the *occurrence*-th instance of ``value`` in ``text``.

    Uses ``text.find`` (and successive ``find`` with an offset) so the runner's
    ``text[start:end] == value`` assertion always passes regardless of later
    edits to the prompt body.
    """
    idx = -1
    pos = 0
    for _ in range(occurrence):
        idx = text.find(value, pos)
        if idx == -1:
            raise AssertionError(
                f"value {value!r} not found (occurrence {occurrence}) in text"
            )
        pos = idx + 1
    end = idx + len(value)
    assert text[idx:end] == value, "self-check failed"
    return {"start": idx, "end": end, "value": value}


def _case(
    case_id: str,
    language: str,
    axis: str,
    text: str,
    expected: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    return {
        "id": case_id,
        "language": language,
        "axis": axis,
        "text": text,
        "expected_spans": expected or [],
    }
