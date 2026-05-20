#!/usr/bin/env python3
"""
Diagnostic script - GLiNER PII parity investigation.

Compares 5 inference configurations against the NVIDIA hosted baseline to
isolate which post-processing step drops entities in our production pipeline.

Runs:
  A  flat_ner=False | all labels in one call    | no overlap resolution | no per-type filter
  B  flat_ner=True  | all labels in one call    | no overlap resolution | no per-type filter
  C  flat_ner=True  | multi-pass batches of N   | no overlap resolution | no per-type filter
  D  flat_ner=True  | multi-pass batches of N   | overlap resolution    | no per-type filter
  E  flat_ner=True  | multi-pass batches of N   | overlap resolution    | per-type filter (data.sql)

Usage:
    python scripts/diagnose_gliner_parity.py \\
        --text     tests/resources/gliner-parity-baseline.txt \\
        --baseline tests/resources/gliner-parity-baseline.json \\
        --data-sql ../pii-reporting-api/src/main/resources/data.sql \\
        --report   doc/gliner-parity-investigation.md
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Make pii_detector importable when launching from the service root.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pii_detector.infrastructure.text_processing.semantic_chunker import (
    GlinerSubwordChunker,
)


MODEL_ID_DEFAULT = "nvidia/gliner-PII"
CHUNK_SIZE_DEFAULT = 384
OVERLAP_DEFAULT = 128
BATCH_SIZE_DEFAULT = 35
THRESHOLD_DEFAULT = 0.4  # matches the NVIDIA baseline capture


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------
@dataclass
class Entity:
    text: str
    label: str
    start: int
    end: int
    score: float

    def key(self) -> Tuple[int, int, str]:
        return (self.start, self.end, self.label)


@dataclass
class RunResult:
    name: str
    description: str
    entities: List[Entity]
    elapsed_s: float
    matched_baseline: int = 0
    missing_baseline: List[Dict[str, Any]] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Baseline + data.sql parsing
# ---------------------------------------------------------------------------
def load_baseline(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


_DATA_SQL_GLINER_RE = re.compile(
    r"\(\s*'([A-Z_]+)'\s*,\s*'GLINER'\s*,\s*(true|false)\s*,\s*([0-9.]+)\s*,"
    r"\s*'[A-Z_]+'\s*,\s*(NULL|'([a-z0-9_]+)')",
    re.IGNORECASE,
)


def parse_data_sql_thresholds(path: Path) -> Tuple[Dict[str, float], Dict[str, str]]:
    """Return (label_to_threshold, label_to_pii_type) for ENABLED GLINER rows.

    Only labels with a non-NULL detector_label and enabled=true are returned.
    """
    label_to_threshold: Dict[str, float] = {}
    label_to_pii_type: Dict[str, str] = {}

    text = path.read_text(encoding="utf-8")
    for match in _DATA_SQL_GLINER_RE.finditer(text):
        pii_type, enabled, threshold, _null_or_quoted, detector_label = match.groups()
        if enabled.lower() != "true":
            continue
        if not detector_label:
            continue
        label_to_threshold[detector_label] = float(threshold)
        label_to_pii_type[detector_label] = pii_type

    return label_to_threshold, label_to_pii_type


# ---------------------------------------------------------------------------
# Chunked inference
# ---------------------------------------------------------------------------
def _build_chunker(model: Any, chunk_size: int, overlap: int) -> GlinerSubwordChunker:
    """Reuse the production-aligned subword chunker so chunking is identical."""
    data_processor = getattr(model, "data_processor", None)
    tokenizer = None
    if data_processor is not None:
        tokenizer = getattr(data_processor, "transformer_tokenizer", None)
    if tokenizer is None:
        from transformers import AutoTokenizer
        model_name = getattr(model.config, "model_name", "microsoft/deberta-v3-large")
        tokenizer = AutoTokenizer.from_pretrained(model_name)
    return GlinerSubwordChunker(
        tokenizer=tokenizer,
        chunk_size=chunk_size,
        overlap=overlap,
    )


def _predict_chunked(
    model: Any,
    chunker: GlinerSubwordChunker,
    text: str,
    labels: List[str],
    threshold: float,
    flat_ner: bool,
) -> List[Entity]:
    """Run predict_entities chunked over the text, returning entities aligned on the original offsets."""
    chunks = chunker.chunk_text(text)
    entities: List[Entity] = []
    for chunk in chunks:
        raw = model.predict_entities(
            chunk.text,
            labels,
            threshold=threshold,
            flat_ner=flat_ner,
        )
        for r in raw:
            entities.append(Entity(
                text=r.get("text", ""),
                label=r.get("label", ""),
                start=int(r.get("start", 0)) + chunk.start,
                end=int(r.get("end", 0)) + chunk.start,
                score=float(r.get("score", 0.0)),
            ))
    return entities


def infer_all_in_one(
    model: Any,
    chunker: GlinerSubwordChunker,
    text: str,
    labels: List[str],
    threshold: float,
    flat_ner: bool,
) -> List[Entity]:
    return _predict_chunked(model, chunker, text, labels, threshold, flat_ner)


def infer_multipass(
    model: Any,
    chunker: GlinerSubwordChunker,
    text: str,
    labels: List[str],
    batch_size: int,
    threshold: float,
    flat_ner: bool,
) -> List[Entity]:
    """Split labels into batches of ``batch_size`` and run one pass per batch."""
    sorted_labels = sorted(labels)
    aggregated: List[Entity] = []
    for i in range(0, len(sorted_labels), batch_size):
        batch = sorted_labels[i : i + batch_size]
        aggregated.extend(_predict_chunked(model, chunker, text, batch, threshold, flat_ner))
    return aggregated


# ---------------------------------------------------------------------------
# Production-style post-processing
# ---------------------------------------------------------------------------
def resolve_overlaps(entities: List[Entity]) -> List[Entity]:
    """Reproduce ``MultiPassGlinerDetector._resolve_overlapping_spans``.

    Sort by (start, -length), keep the first non-overlapping span.
    """
    sorted_entities = sorted(entities, key=lambda e: (e.start, -(e.end - e.start)))
    kept: List[Entity] = []
    max_end = -1
    for entity in sorted_entities:
        if entity.start >= max_end:
            kept.append(entity)
            if entity.end > max_end:
                max_end = entity.end
    return kept


def filter_by_type(
    entities: List[Entity],
    label_to_threshold: Dict[str, float],
) -> List[Entity]:
    """Reproduce ``GLiNERDetector._apply_entity_scoring_filter`` (per-label)."""
    kept: List[Entity] = []
    for entity in entities:
        threshold = label_to_threshold.get(entity.label)
        if threshold is None:
            kept.append(entity)
            continue
        if entity.score >= threshold:
            kept.append(entity)
    return kept


# ---------------------------------------------------------------------------
# Recall vs baseline
# ---------------------------------------------------------------------------
def _spans_overlap(a_start: int, a_end: int, b_start: int, b_end: int) -> bool:
    return a_start < b_end and b_start < a_end


def compute_recall(
    entities: List[Entity],
    baseline_entities: List[Dict[str, Any]],
) -> Tuple[int, List[Dict[str, Any]]]:
    matched = 0
    missing: List[Dict[str, Any]] = []
    for b in baseline_entities:
        hit = False
        for e in entities:
            if e.label == b["label"] and _spans_overlap(e.start, e.end, b["start"], b["end"]):
                hit = True
                break
        if hit:
            matched += 1
        else:
            missing.append(b)
    return matched, missing


def entity_keys(entities: List[Entity]) -> set[Tuple[int, int, str]]:
    return {e.key() for e in entities}


def delta_keys(prev: List[Entity], curr: List[Entity]) -> Tuple[set, set]:
    prev_keys = entity_keys(prev)
    curr_keys = entity_keys(curr)
    return (prev_keys - curr_keys, curr_keys - prev_keys)


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------
def print_table(runs: List[RunResult], baseline_total: int) -> None:
    print()
    print(f"{'Run':<4} {'Total':>6} {'Matched':>8} {'Recall':>8} {'Missing':>8} {'Time(s)':>8}  Description")
    print("-" * 110)
    for r in runs:
        recall_pct = (100.0 * r.matched_baseline / baseline_total) if baseline_total else 0.0
        print(
            f"{r.name:<4} {len(r.entities):>6} {r.matched_baseline:>8} "
            f"{recall_pct:>7.1f}% {len(r.missing_baseline):>8} {r.elapsed_s:>8.2f}  {r.description}"
        )
    print()


def print_deltas(runs: List[RunResult]) -> None:
    print("Inter-run deltas (entities lost when applying each pipeline step):")
    print("-" * 110)
    for prev, curr in zip(runs, runs[1:]):
        lost, gained = delta_keys(prev.entities, curr.entities)
        print(f"  {prev.name} -> {curr.name}: lost={len(lost)} gained={len(gained)}")
        if lost:
            by_label: Dict[str, List[Tuple[int, int, str]]] = defaultdict(list)
            for s, e, lbl in lost:
                by_label[lbl].append((s, e, lbl))
            for label, items in sorted(by_label.items(), key=lambda x: -len(x[1])):
                preview = ", ".join(f"[{s}:{e}]" for s, e, _ in items[:3])
                more = f" (+{len(items) - 3} more)" if len(items) > 3 else ""
                print(f"      {label}: {len(items)}  {preview}{more}")
    print()


def write_markdown_report(
    runs: List[RunResult],
    baseline_total: int,
    baseline_meta: Dict[str, Any],
    out_path: Path,
) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    lines: List[str] = []
    lines.append("# GLiNER parity investigation\n")
    lines.append("Generated by `scripts/diagnose_gliner_parity.py`.\n")
    lines.append(f"- Baseline source: `{baseline_meta.get('source', 'n/a')}`")
    lines.append(f"- Baseline captured: `{baseline_meta.get('captured_on', 'n/a')}`")
    lines.append(f"- Model: `{baseline_meta.get('model', 'n/a')}`")
    lines.append(f"- Baseline entity count: **{baseline_total}**\n")

    lines.append("## Comparison table\n")
    lines.append("| Run | Description | Total | Matched | Recall | Missing | Time (s) |")
    lines.append("|-----|-------------|------:|--------:|-------:|--------:|---------:|")
    for r in runs:
        recall_pct = (100.0 * r.matched_baseline / baseline_total) if baseline_total else 0.0
        lines.append(
            f"| {r.name} | {r.description} | {len(r.entities)} | {r.matched_baseline} | "
            f"{recall_pct:.1f}% | {len(r.missing_baseline)} | {r.elapsed_s:.2f} |"
        )
    lines.append("")

    lines.append("## Inter-run deltas\n")
    for prev, curr in zip(runs, runs[1:]):
        lost, gained = delta_keys(prev.entities, curr.entities)
        lines.append(f"### {prev.name} -> {curr.name}\n")
        lines.append(f"- Lost: **{len(lost)}** entities")
        lines.append(f"- Gained: **{len(gained)}** entities\n")
        if lost:
            by_label: Dict[str, int] = defaultdict(int)
            for _s, _e, lbl in lost:
                by_label[lbl] += 1
            lines.append("| Label | Lost |")
            lines.append("|-------|-----:|")
            for label, count in sorted(by_label.items(), key=lambda x: -x[1]):
                lines.append(f"| {label} | {count} |")
            lines.append("")

    lines.append("## Missing baseline entities per run\n")
    for r in runs:
        if not r.missing_baseline:
            lines.append(f"- **{r.name}**: 100% recall\n")
            continue
        lines.append(f"### {r.name}\n")
        lines.append("| Label | Text | Span |")
        lines.append("|-------|------|------|")
        for b in r.missing_baseline:
            text = b["text"].replace("|", "\\|")
            lines.append(f"| {b['label']} | `{text}` | [{b['start']}, {b['end']}) |")
        lines.append("")

    out_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Markdown report written to: {out_path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--text", type=Path, required=True, help="Path to fixture text file")
    parser.add_argument("--baseline", type=Path, required=True, help="Path to baseline JSON file")
    parser.add_argument("--data-sql", type=Path, default=None, help="Path to data.sql for per-type thresholds (Run E)")
    parser.add_argument("--model-id", type=str, default=MODEL_ID_DEFAULT)
    parser.add_argument("--threshold", type=float, default=THRESHOLD_DEFAULT)
    parser.add_argument("--chunk-size", type=int, default=CHUNK_SIZE_DEFAULT)
    parser.add_argument("--overlap", type=int, default=OVERLAP_DEFAULT)
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE_DEFAULT)
    parser.add_argument("--report", type=Path, default=Path("doc/gliner-parity-investigation.md"))
    args = parser.parse_args()

    text = args.text.read_text(encoding="utf-8")
    baseline = load_baseline(args.baseline)
    baseline_meta = baseline.get("_meta", {})
    baseline_entities = baseline["entities"]
    labels = list(baseline_meta.get("labels", []))
    baseline_total = len(baseline_entities)

    print(f"Loading model: {args.model_id}")
    from gliner import GLiNER
    model = GLiNER.from_pretrained(args.model_id)
    chunker = _build_chunker(model, args.chunk_size, args.overlap)
    print(f"Loaded. text={len(text)} chars, labels={len(labels)}, baseline_entities={baseline_total}")

    label_to_threshold: Dict[str, float] = {}
    if args.data_sql is not None and args.data_sql.exists():
        label_to_threshold, _ = parse_data_sql_thresholds(args.data_sql)
        print(f"Loaded {len(label_to_threshold)} per-label thresholds from {args.data_sql}")
    else:
        print("WARNING: --data-sql not provided or missing; Run E falls back to threshold=0.5 per label")

    runs: List[RunResult] = []

    # ---- Run A
    t0 = time.time()
    a_entities = infer_all_in_one(model, chunker, text, labels, args.threshold, flat_ner=False)
    runs.append(RunResult(
        name="A",
        description=f"flat_ner=False | all-in-one | threshold={args.threshold}",
        entities=a_entities,
        elapsed_s=time.time() - t0,
    ))

    # ---- Run B
    t0 = time.time()
    b_entities = infer_all_in_one(model, chunker, text, labels, args.threshold, flat_ner=True)
    runs.append(RunResult(
        name="B",
        description=f"flat_ner=True | all-in-one | threshold={args.threshold}",
        entities=b_entities,
        elapsed_s=time.time() - t0,
    ))

    # ---- Run C
    t0 = time.time()
    c_entities = infer_multipass(
        model, chunker, text, labels, args.batch_size, args.threshold, flat_ner=True,
    )
    runs.append(RunResult(
        name="C",
        description=f"flat_ner=True | multi-pass batches of {args.batch_size}",
        entities=c_entities,
        elapsed_s=time.time() - t0,
    ))

    # ---- Run D
    t0 = time.time()
    d_entities = resolve_overlaps(list(c_entities))
    runs.append(RunResult(
        name="D",
        description=f"flat_ner=True | multi-pass | overlap resolution",
        entities=d_entities,
        elapsed_s=time.time() - t0,
    ))

    # ---- Run E
    t0 = time.time()
    if label_to_threshold:
        e_entities = filter_by_type(list(d_entities), label_to_threshold)
        e_desc = "flat_ner=True | multi-pass | overlap resolution | per-type filter (data.sql)"
    else:
        e_entities = filter_by_type(list(d_entities), {l: 0.5 for l in labels})
        e_desc = "flat_ner=True | multi-pass | overlap resolution | per-type filter (default 0.5)"
    runs.append(RunResult(
        name="E",
        description=e_desc,
        entities=e_entities,
        elapsed_s=time.time() - t0,
    ))

    # Compute recall for each run
    for r in runs:
        matched, missing = compute_recall(r.entities, baseline_entities)
        r.matched_baseline = matched
        r.missing_baseline = missing

    print_table(runs, baseline_total)
    print_deltas(runs)
    write_markdown_report(runs, baseline_total, baseline_meta, args.report)

    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
