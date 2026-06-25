"""Map raw chunk detections to canonical predictions and merge across chunks.

Ministral returns PII *values* (not char offsets), so when overlapping chunks
both cover the same occurrence, the same value comes back twice and would inflate
the prediction multiset. We deduplicate without offsets by capping each
``(canonical, value)`` count at the number of times that value actually occurs in
the document:

    detection_count = min(raw_count, max(1, occurrences_in_doc))

* value occurs once in the doc  -> capped to 1 (overlap duplicates collapse);
* value occurs twice            -> capped to 2 (genuine multiplicity preserved);
* value not in the doc (hallucination) -> capped to 1 (one FP, overlap-stable).

This isolates the chunking *quality* effect: overlap never inflates counts, while
its recall benefit (catching a value a non-overlapping cut would have split)
still shows up as a recovered true positive.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

if sys.version_info >= (3, 11):
    import tomllib
else:  # pragma: no cover
    import tomli as tomllib

_WS = re.compile(r"\s+")
IGNORE = "IGNORE"


def normalize_value(value: str) -> str:
    """Value normalisation identical to the Java ValueScorer."""
    return _WS.sub(" ", value.strip().lower())


def _normalize_label(label: str) -> str:
    return label.strip().lower().replace(" ", "_").replace("-", "_")


@dataclass
class Prediction:
    canonical: str
    value: str


class ConceptMap:
    """Extractor label -> canonical concept, from ``[extractors]`` in the TOML."""

    def __init__(self, by_model: Dict[str, Dict[str, str]], model_id: str):
        self._by_model = by_model
        self._model_id = model_id
        self.unknown: Set[str] = set()

    @classmethod
    def load(cls, toml_path: Path, model_id: str) -> "ConceptMap":
        with open(toml_path, "rb") as fh:
            table = tomllib.load(fh)
        extractors = table.get("extractors", {})
        by_model: Dict[str, Dict[str, str]] = {}
        for name, section in extractors.items():
            by_model[name] = {_normalize_label(k): v for k, v in section.items()}
        return cls(by_model, model_id)

    def canonical(self, label: str) -> Optional[str]:
        key = _normalize_label(label)
        mapped = self._lookup(key)
        if mapped is None:
            self.unknown.add(key)
            return None
        return None if mapped == IGNORE else mapped

    def _lookup(self, key: str) -> Optional[str]:
        model_map = self._by_model.get(self._model_id)
        if model_map and key in model_map:
            return model_map[key]
        default = self._by_model.get("_default", {})
        return default.get(key)


@dataclass
class MergeStats:
    raw: int = 0
    kept: int = 0
    dropped_ignore: int = 0
    hallucinated: int = 0
    unknown: Set[str] = field(default_factory=set)


def merge(chunk_entities: List[List[Tuple[str, str]]], doc_text: str,
          cmap: ConceptMap) -> Tuple[List[Prediction], MergeStats]:
    """Collapse per-chunk ``(value,label)`` lists into capped canonical predictions."""
    stats = MergeStats()
    norm_doc = normalize_value(doc_text)
    # raw_count[(canonical, norm_value)] -> count; repr keeps an original-cased value.
    raw_count: Dict[Tuple[str, str], int] = {}
    repr_value: Dict[Tuple[str, str], str] = {}
    for entities in chunk_entities:
        for value, label in entities:
            stats.raw += 1
            canonical = cmap.canonical(label)
            if canonical is None:
                stats.dropped_ignore += 1
                continue
            nv = normalize_value(value)
            if not nv:
                continue
            key = (canonical, nv)
            raw_count[key] = raw_count.get(key, 0) + 1
            repr_value.setdefault(key, value)

    preds: List[Prediction] = []
    occ_cache: Dict[str, int] = {}
    for (canonical, nv), count in raw_count.items():
        n_occ = occ_cache.get(nv)
        if n_occ is None:
            n_occ = norm_doc.count(nv) if nv else 0
            occ_cache[nv] = n_occ
        if n_occ == 0:
            stats.hallucinated += 1
        capped = min(count, max(1, n_occ))
        for _ in range(capped):
            preds.append(Prediction(canonical, repr_value[(canonical, nv)]))
    stats.kept = len(preds)
    stats.unknown = set(cmap.unknown)
    return preds, stats
