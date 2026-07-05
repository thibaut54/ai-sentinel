"""Build span-level gold files from public PII datasets.

Reads the versioned normalization table (``label_mapping.toml`` via
:mod:`mapping`), downloads each declared Hugging Face dataset at its frozen
revision, normalizes every labelled span to our canonical concept vocabulary,
and writes one JSONL gold file per dataset plus the flattened detector concept
map consumed by the Java scorer.

Pipeline per dataset (see spec étape 2 and the team brief):

1. ``datasets.load_dataset(hf_id, revision=..., split=...)`` then keep only the
   requested ``languages`` (matched against a best-effort language column).
2. Parse spans per ``spans_format``:
   * ``json_string``  -> ``pii_spans`` is a JSON *string* -> ``json.loads``.
   * ``object_list``  -> ``privacy_mask`` is already a list of dicts.
3. Apply the mapping: a span whose label is in ``.map`` becomes a canonical
   gold span ``{start, end, label}``; a span whose label is in ``.exclude`` or
   is simply unknown becomes an ignore span ``{start, end, src_label}`` (never
   counted as FN, and a detector hit landing there is not a FP either).
4. Keep only docs with >= 1 mappable gold span.
5. Drop docs containing any non-BMP code point (> 0xFFFF) and log the exact
   count (no silent cap).
6. Deterministic sub-sampling: ``random.Random(seed).shuffle(indices)`` then
   head(n).
7. Write ``gold/<dataset>.jsonl`` (offsets are code points, consistent with the
   text as stored).

Offsets: Python string indices are code points. Once non-BMP docs are dropped
every code point is a single UTF-16 unit too, so the offsets stay BMP-safe.

HF auth: the token lives in ``HUGGING_FACE_API_KEY`` (not ``HF_TOKEN``); we
mirror it into ``HF_TOKEN`` before importing ``datasets``.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import random
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import mapping

# Mirror the project's HF token env var into the one the HF libraries read.
os.environ.setdefault("HF_TOKEN", os.environ.get("HUGGING_FACE_API_KEY", ""))

LOGGER = logging.getLogger("build_datasets")

BASE_DIR = Path(__file__).resolve().parent
GOLD_DIR = BASE_DIR / "gold"
MAPPINGS_DIR = BASE_DIR / "mappings"

# Candidate columns that may carry a per-row language tag. The two target
# datasets do not share a single convention, so we probe a small ordered set.
LANGUAGE_COLUMNS = ("language", "lang", "locale")


def _has_non_bmp(text: str) -> bool:
    """True if any code point in ``text`` is outside the Basic Multilingual Plane."""
    return any(ord(ch) > 0xFFFF for ch in text)


def _row_language(row: Dict[str, Any]) -> Optional[str]:
    """Best-effort per-row language value, or None if the dataset has no column."""
    for col in LANGUAGE_COLUMNS:
        if col in row and row[col]:
            return str(row[col])
    return None


def _lang_matches(row_lang: Optional[str], languages: set) -> bool:
    """Lenient language match.

    Datasets disagree on the convention: gretelai uses full names ("English"),
    ai4privacy may use codes ("en"). Match case-insensitively and accept a prefix
    in either direction so "en" matches "English" and vice-versa. No language
    filter (empty set) or a row without a language column keeps the row.
    """
    if not languages or row_lang is None:
        return True
    rl = row_lang.strip().lower()
    for lang in languages:
        ll = str(lang).strip().lower()
        if rl == ll or rl.startswith(ll) or ll.startswith(rl):
            return True
    return False


def _parse_spans(raw: Any, spans_format: str) -> List[Dict[str, Any]]:
    """Return a list of ``{start, end, label}`` dicts from the raw span field.

    ``json_string`` decodes a JSON string into a list; ``object_list`` is
    already a list of dicts. Both yield dicts carrying at least start/end/label.
    """
    if spans_format == "json_string":
        if raw is None:
            return []
        decoded = json.loads(raw) if isinstance(raw, str) else raw
        return list(decoded)
    if spans_format == "object_list":
        return list(raw or [])
    raise ValueError(f"unknown spans_format: {spans_format!r}")


def normalize_doc(
    text: str,
    raw_spans: List[Dict[str, Any]],
    label_map: Dict[str, str],
    exclude: set,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], int]:
    """Split raw spans into canonical gold spans and ignore spans.

    Returns ``(gold_spans, ignore_spans, skipped)`` where ``skipped`` counts
    spans dropped because ``text[start:end]`` is empty/out of range (logged by
    the caller). A span whose label is mapped becomes gold; anything else
    (excluded or unknown) becomes an ignore span.
    """
    gold: List[Dict[str, Any]] = []
    ignore: List[Dict[str, Any]] = []
    skipped = 0
    for span in raw_spans:
        start = int(span["start"])
        end = int(span["end"])
        if not (0 <= start < end <= len(text)) or not text[start:end]:
            skipped += 1
            continue
        label = span.get("label")
        canonical = label_map.get(label)
        if canonical is not None:
            gold.append({"start": start, "end": end, "label": canonical})
        else:
            # Excluded OR unknown: both are ignore zones (never FN, never FP).
            ignore.append({"start": start, "end": end, "src_label": label})
    return gold, ignore, skipped


def build_dataset(
    name: str,
    spec: Dict[str, Any],
    label_map: Dict[str, str],
    exclude: set,
    seed: int,
    sample_size: int,
) -> Path:
    """Download, normalize, sub-sample and write ``gold/<name>.jsonl``.

    Returns the path written. Logs the non-BMP drop count and span-skip count.
    """
    from datasets import load_dataset  # local import: keep mapping.py pure

    languages = set(spec["languages"])
    LOGGER.info(
        "loading %s @ %s split=%s (languages=%s)",
        spec["hf_id"], spec["revision"], spec["split"], sorted(languages),
    )
    dataset = load_dataset(spec["hf_id"], revision=spec["revision"], split=spec["split"])

    text_field = spec["text_field"]
    spans_field = spec["spans_field"]
    spans_format = spec["spans_format"]

    docs: List[Dict[str, Any]] = []
    non_bmp_dropped = 0
    span_skipped = 0
    lang_filtered = 0

    for idx, row in enumerate(dataset):
        row_lang = _row_language(row)
        if not _lang_matches(row_lang, languages):
            lang_filtered += 1
            continue

        text = row[text_field]
        if text is None:
            continue
        if _has_non_bmp(text):
            non_bmp_dropped += 1
            continue

        raw_spans = _parse_spans(row.get(spans_field), spans_format)
        gold, ignore, skipped = normalize_doc(text, raw_spans, label_map, exclude)
        span_skipped += skipped
        if not gold:
            continue

        docs.append({
            "id": f"{name}-{idx}",
            "dataset": name,
            "lang": row_lang if row_lang is not None else (sorted(languages)[0] if languages else None),
            "text": text,
            "spans": gold,
            "ignore_spans": ignore,
        })

    LOGGER.info(
        "%s: %d docs with gold (lang_filtered=%d, non_bmp_dropped=%d, span_skipped=%d)",
        name, len(docs), lang_filtered, non_bmp_dropped, span_skipped,
    )

    # Deterministic sub-sampling: shuffle indices then head(n).
    indices = list(range(len(docs)))
    random.Random(seed).shuffle(indices)
    selected = sorted(indices[:sample_size])
    sampled = [docs[i] for i in selected]
    LOGGER.info("%s: sampled %d/%d docs (seed=%d)", name, len(sampled), len(docs), seed)

    GOLD_DIR.mkdir(parents=True, exist_ok=True)
    out_path = GOLD_DIR / f"{name}.jsonl"
    with open(out_path, "w", encoding="utf-8") as fh:
        for doc in sampled:
            fh.write(json.dumps(doc, ensure_ascii=False) + "\n")
    LOGGER.info("%s: wrote %s", name, out_path)
    return out_path


def write_detector_concept_map() -> Path:
    """Flatten ``[detectors.*]`` into ``mappings/detector_concept_map.json``."""
    detector_map = mapping.load_detector_concept_map()
    MAPPINGS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = MAPPINGS_DIR / "detector_concept_map.json"
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(detector_map, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    LOGGER.info("wrote %s", out_path)
    return out_path


def write_extractor_concept_map() -> Path:
    """Flatten ``[extractors.*]`` into ``mappings/extractor_concept_map.json``."""
    extractor_map = mapping.load_extractor_concept_map()
    MAPPINGS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = MAPPINGS_DIR / "extractor_concept_map.json"
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(extractor_map, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    LOGGER.info("wrote %s", out_path)
    return out_path


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build span-level PII gold files from HF datasets.")
    parser.add_argument("--seed", type=int, default=42, help="Deterministic sub-sampling seed (default: 42).")
    parser.add_argument("--n", type=int, default=300, help="Docs kept per dataset after sub-sampling (default: 300).")
    parser.add_argument(
        "--dataset",
        action="append",
        dest="datasets",
        help="Restrict to a dataset name (repeatable). Default: all in the table.",
    )
    parser.add_argument(
        "--mapping",
        type=Path,
        default=mapping.DEFAULT_MAPPING_PATH,
        help="Path to label_mapping.toml.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    args = parse_args(argv)

    specs = mapping.load_dataset_specs(args.mapping)
    maps = mapping.load_dataset_maps(args.mapping)
    excludes = mapping.load_dataset_excludes(args.mapping)

    targets = args.datasets or list(specs.keys())
    for name in targets:
        if name not in specs:
            LOGGER.error("unknown dataset %r (known: %s)", name, ", ".join(specs))
            return 2
        build_dataset(
            name=name,
            spec=specs[name],
            label_map=maps[name],
            exclude=excludes[name],
            seed=args.seed,
            sample_size=args.n,
        )

    write_detector_concept_map()
    write_extractor_concept_map()
    return 0


if __name__ == "__main__":
    sys.exit(main())
