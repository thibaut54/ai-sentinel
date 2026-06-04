"""Shared helpers for GLiNER-family detectors (GLiNER, GLiNER2).

Factored out of :mod:`gliner_detector` so :class:`Gliner2Detector` can reuse the
exact same config-resolution, per-type threshold filtering and masking logic
without duplication (spec technique §4.1 "extraction recommandée").

The functions here are pure (no detector state) and keyed by the *detector
namespace* string (``"GLINER"`` / ``"GLINER2"``) so the same routines serve
both detectors while keeping their DB rows strictly isolated.
"""
from __future__ import annotations

import logging
from typing import Dict, Iterator, List, Tuple

from pii_detector.domain.entity.pii_entity import PIIEntity

logger = logging.getLogger(__name__)


def iterate_detector_configs(
    pii_type_configs: dict, detector: str
) -> Iterator[Tuple[str, dict]]:
    """Yield ``(pii_type, config)`` pairs for the given detector namespace only.

    Handles both layouts of ``pii_type_configs``:
      - the multi-detector dict with composite keys (``"GLINER2:EMAIL"``),
      - a pre-filtered detector-only dict (plain ``pii_type`` keys).

    Filtering by namespace prevents a REGEX/PRESIDIO/GLINER row that shares the
    same ``pii_type`` (e.g. ``API_KEY``) from leaking into the GLiNER2 mapping.
    """
    prefix = f"{detector}:"
    has_composite = any(
        isinstance(k, str) and k.startswith(prefix) for k in pii_type_configs
    )
    if has_composite:
        for key, config in pii_type_configs.items():
            if isinstance(key, str) and key.startswith(prefix):
                yield key.split(":", 1)[1], config
        return

    for key, config in pii_type_configs.items():
        if not isinstance(key, str) or ":" in key:
            continue
        cfg_detector = config.get("detector")
        if cfg_detector and cfg_detector != detector:
            continue
        yield key, config


def build_pii_type_mapping_from_configs(
    pii_type_configs: dict, detector: str
) -> Dict[str, str]:
    """Build ``{detector_label: pii_type}`` for enabled rows of ``detector``."""
    mapping: Dict[str, str] = {}
    for pii_type, config in iterate_detector_configs(pii_type_configs, detector):
        detector_label = config.get("detector_label")
        if detector_label and config.get("enabled", False):
            mapping[detector_label] = pii_type
    return mapping


def build_scoring_overrides_from_configs(
    pii_type_configs: dict, detector: str
) -> Dict[str, float]:
    """Build ``{pii_type: threshold}`` for enabled rows of ``detector``."""
    scoring: Dict[str, float] = {}
    for pii_type, config in iterate_detector_configs(pii_type_configs, detector):
        if config.get("enabled", False) and config.get("threshold") is not None:
            scoring[pii_type] = float(config["threshold"])
    return scoring


def apply_entity_scoring_filter(
    entities: List[PIIEntity], scoring_overrides: Dict[str, float]
) -> List[PIIEntity]:
    """Discard entities scoring below their per-type threshold (post-filter)."""
    if not scoring_overrides:
        return entities
    kept: List[PIIEntity] = []
    for entity in entities:
        per_type = scoring_overrides.get(entity.pii_type)
        if per_type is not None and entity.score < per_type:
            continue
        kept.append(entity)
    return kept


def apply_masks(text: str, entities: List[PIIEntity]) -> str:
    """Return ``text`` with each entity replaced by ``[PII_TYPE]`` (pure)."""
    if not entities:
        return text
    sorted_entities = sorted(entities, key=lambda entity: entity.start)
    parts: List[str] = []
    last_pos = 0
    for entity in sorted_entities:
        if entity.start < last_pos:
            continue
        parts.append(text[last_pos:entity.start])
        parts.append(f"[{entity.pii_type}]")
        last_pos = entity.end
    parts.append(text[last_pos:])
    return "".join(parts)
