"""Pure loader for ``label_mapping.toml`` (stdlib only).

This module is the single Python entry point for reading the versioned label
normalization table. It deliberately depends on nothing but the standard
library (``tomllib`` on Python >= 3.11, ``tomli`` as a fallback) so it can be
imported from offline self-tests without pulling in ``datasets`` or any heavy
ML dependency.

It exposes three views of the table:

* :func:`load_dataset_maps` -> ``{dataset: {dataset_label: CANONICAL}}``
* :func:`load_dataset_excludes` -> ``{dataset: {dataset_label}}``
* :func:`load_detector_concept_map` -> ``{DETECTOR: {PII_TYPE: CANONICAL}}``

plus :func:`load_dataset_specs` for the per-dataset HF coordinates
(``hf_id`` / ``revision`` / ``split`` / fields / ``spans_format`` / languages).
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Dict, List, Set

if sys.version_info >= (3, 11):
    import tomllib
else:  # pragma: no cover - exercised only on Python < 3.11
    import tomli as tomllib

DEFAULT_MAPPING_PATH = Path(__file__).resolve().parent / "label_mapping.toml"

# Detector sections we flatten into detector_concept_map.json. Order is fixed so
# the emitted JSON is deterministic across runs.
DETECTOR_NAMES = ("GLINER2", "PRESIDIO", "REGEX", "OPENMED")


def load_table(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Any]:
    """Parse the TOML mapping file and return the raw nested dict."""
    with open(path, "rb") as fh:
        return tomllib.load(fh)


def load_dataset_specs(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Dict[str, Any]]:
    """Return per-dataset HF coordinates and parsing hints.

    Each value carries ``hf_id``, ``revision``, ``split``, ``text_field``,
    ``spans_field``, ``spans_format`` and ``languages`` exactly as declared in
    the table (no defaults injected here: the table is the source of truth).
    """
    table = load_table(path)
    datasets = table.get("datasets", {})
    specs: Dict[str, Dict[str, Any]] = {}
    for name, section in datasets.items():
        specs[name] = {
            "hf_id": section["hf_id"],
            "revision": section["revision"],
            "split": section["split"],
            "text_field": section["text_field"],
            "spans_field": section["spans_field"],
            "spans_format": section["spans_format"],
            "languages": list(section.get("languages", [])),
        }
    return specs


def load_dataset_maps(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Dict[str, str]]:
    """Return ``{dataset: {dataset_label: CANONICAL_CONCEPT}}``."""
    table = load_table(path)
    datasets = table.get("datasets", {})
    return {name: dict(section.get("map", {})) for name, section in datasets.items()}


def load_dataset_excludes(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Set[str]]:
    """Return ``{dataset: {dataset_label, ...}}`` for explicitly excluded labels.

    Excluded labels become ignore zones (never FN, never FP); their values in
    the table are human-readable rationales and are intentionally dropped here.
    """
    table = load_table(path)
    datasets = table.get("datasets", {})
    return {name: set(section.get("exclude", {}).keys()) for name, section in datasets.items()}


def load_detector_concept_map(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Dict[str, str]]:
    """Return ``{DETECTOR: {PII_TYPE: CANONICAL_CONCEPT}}`` for the JSON map.

    Only the four in-scope detectors are emitted, in :data:`DETECTOR_NAMES`
    order, so the resulting JSON is stable and Java-friendly.
    """
    table = load_table(path)
    detectors = table.get("detectors", {})
    out: Dict[str, Dict[str, str]] = {}
    for name in DETECTOR_NAMES:
        out[name] = dict(detectors.get(name, {}))
    return out


def load_extractor_concept_map(path: Path = DEFAULT_MAPPING_PATH) -> Dict[str, Dict[str, str]]:
    """Return ``{model_or__default: {model_label: CANONICAL_or_IGNORE}}``.

    Used by the generative LLM extractor comparison harness. ``_default`` applies
    to every model; a ``[extractors.<model>]`` table overrides it for that model.
    """
    table = load_table(path)
    extractors = table.get("extractors", {})
    return {name: dict(section) for name, section in extractors.items()}


def canonical_concepts(path: Path = DEFAULT_MAPPING_PATH) -> List[str]:
    """Return the canonical concept vocabulary keys (documentation section)."""
    table = load_table(path)
    return list(table.get("canonical", {}).keys())


if __name__ == "__main__":
    specs = load_dataset_specs()
    maps = load_dataset_maps()
    excludes = load_dataset_excludes()
    detectors = load_detector_concept_map()
    print("datasets:", ", ".join(sorted(specs)))
    for name in sorted(maps):
        print(f"  {name}: {len(maps[name])} mapped, {len(excludes[name])} excluded")
    print("detectors:", {k: len(v) for k, v in detectors.items()})
