"""TEMPORARY debug: simulate full app pipeline on corpus and pinpoint where recall drops.

Pipeline reproduit:
  1) GlinerSubwordChunker(384, 128) (= test SEMCHUNK passing)
  2) GLiNER predict_entities per chunk + offset re-alignment (= predict_chunked)
  3) MultiPassGlinerDetector._aggregate_by_span (group by exact (start,end))
  4) MultiPassGlinerDetector._resolve_conflicts (1 batch -> identity)
  5) MultiPassGlinerDetector._resolve_overlapping_spans (LABEL-AGNOSTIC, longest wins)

Usage:
    .venv/Scripts/python.exe tests/integration/_debug_pipeline_simulation.py
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPO_ROOT / "pii-reporting-api/src/test/resources/test-corpus/Miscellaneous/confluence-pii-test-document-docanno.txt"
BASELINE = REPO_ROOT / "my-files/confluence-pii-test-document-nvidia-gliner-result.json"

PARITY_LABELS = [
    "customer_id", "api_key", "account_number", "swift_bic", "device_identifier",
    "certificate_license_number", "health_plan_beneficiary_number",
    "medical_record_number", "national_id", "password", "http_cookie", "ssn",
    "license_plate",
]
THRESHOLD = 0.8  # match prod default_threshold from pii_detection_config


def main():
    text = CORPUS.read_text(encoding="utf-8")
    nvidia = json.loads(BASELINE.read_text(encoding="utf-8"))
    baseline_ents = json.loads(nvidia["choices"][0]["message"]["content"])["entities"]
    baseline_total = len(baseline_ents)
    print(f"[BASELINE] NVIDIA: {baseline_total} entities (range {min(e['start'] for e in baseline_ents)}-{max(e['end'] for e in baseline_ents)})")

    # ---- Stage 1+2 : run our chunker + GLiNER ----
    from gliner import GLiNER
    from pii_detector.infrastructure.text_processing.semantic_chunker import GlinerSubwordChunker

    print("[SETUP] Loading nvidia/gliner-PII...")
    model = GLiNER.from_pretrained("nvidia/gliner-PII")
    tokenizer = model.data_processor.transformer_tokenizer

    chunker = GlinerSubwordChunker(tokenizer=tokenizer, chunk_size=384, overlap=128)
    chunks = chunker.chunk_text(text)
    print(f"[CHUNKER] {len(chunks)} chunks")

    raw = []
    for ch in chunks:
        for e in model.predict_entities(ch.text, PARITY_LABELS, threshold=THRESHOLD):
            raw.append({
                "label": e["label"],
                "start": int(e["start"]) + ch.start,
                "end": int(e["end"]) + ch.start,
                "score": float(e.get("score", 0.0)),
                "text": e["text"],
            })
    print(f"[STAGE 1+2] predict_chunked total raw: {len(raw)}")

    # ---- Stage 3 : _aggregate_by_span (key = (start, end), label-aware via merge of label list) ----
    # In prod : entity.start/end exact -> span. Multiple entities at same span are KEPT in span.labels.
    # The span itself is unique per (start, end).
    span_map: dict[tuple[int, int], dict] = {}
    for e in raw:
        k = (e["start"], e["end"])
        if k not in span_map:
            span_map[k] = {"start": e["start"], "end": e["end"], "text": e["text"], "labels": []}
        span_map[k]["labels"].append((e["label"], e["score"]))
    aggregated = list(span_map.values())
    print(f"[STAGE 3] _aggregate_by_span: {len(aggregated)} spans (collapsed {len(raw)-len(aggregated)} dups by exact (start,end))")

    # ---- Stage 4 : _resolve_conflicts (one label per span, max score) ----
    # 1 batch only -> rarely two labels, but we mirror prod logic: pick highest scoring label.
    resolved = []
    conflicts = 0
    for sp in aggregated:
        if len({lbl for lbl, _ in sp["labels"]}) > 1:
            conflicts += 1
        best_lbl, best_sc = max(sp["labels"], key=lambda x: x[1])
        resolved.append({**sp, "label": best_lbl, "score": best_sc})
    print(f"[STAGE 4] _resolve_conflicts: {len(resolved)} entities ({conflicts} multi-label conflicts collapsed)")

    # ---- Stage 5 : _resolve_overlapping_spans (label-agnostic, longest-first) ----
    sorted_resolved = sorted(resolved, key=lambda e: (e["start"], -(e["end"] - e["start"])))
    kept = []
    discarded = []
    max_end = -1
    for e in sorted_resolved:
        if e["start"] >= max_end:
            kept.append(e)
            max_end = max(max_end, e["end"])
        else:
            discarded.append(e)
    print(f"[STAGE 5] _resolve_overlapping_spans (LABEL-AGNOSTIC): {len(kept)} kept, {len(discarded)} discarded")

    # ---- Diagnosis : how many of the 'discarded' entities are actually distinct labels (not duplicates)? ----
    print("\n[DIAGNOSIS] Discarded entities by label:")
    for lbl, c in Counter(e["label"] for e in discarded).most_common():
        print(f"  {lbl:<32} {c}")

    # Critical check: would per-label dedup keep them?
    by_label_overlaps = defaultdict(int)
    by_label_kept = defaultdict(int)
    for d in discarded:
        # Find what kept entity it overlaps with, and which label that one has
        for k in kept:
            if d["start"] < k["end"] and k["start"] < d["end"]:
                if d["label"] != k["label"]:
                    by_label_overlaps[d["label"]] += 1  # discarded across-label
                else:
                    by_label_kept[d["label"]] += 1     # discarded within-label (legit dup)
                break
    print(f"\n[DIAGNOSIS] Discarded across-label  (would survive per-label dedup): {sum(by_label_overlaps.values())}")
    print(f"           Discarded within-label   (legit duplicates):                {sum(by_label_kept.values())}")

    # ---- Compare app pipeline output vs NVIDIA recall ----
    def overlaps(a, b):
        return a[0] < b[1] and b[0] < a[1]

    matched = 0
    for n in baseline_ents:
        if any(overlaps((n["start"], n["end"]), (k["start"], k["end"])) and n["label"] == k["label"]
               for k in kept):
            matched += 1
    print(f"\n[FINAL] App pipeline output: {len(kept)} entities, matched against NVIDIA: {matched}/{baseline_total} ({matched*100/baseline_total:.1f}%)")

    # Sample discarded across-label
    print("\n[SAMPLE] Discarded across-label (potential recall loss):")
    n_shown = 0
    for d in discarded:
        for k in kept:
            if d["start"] < k["end"] and k["start"] < d["end"] and d["label"] != k["label"]:
                print(f"  [{d['start']:5d}-{d['end']:5d}] DISCARDED {d['label']:<32} score={d['score']:.2f} text={d['text']!r}")
                print(f"  [{k['start']:5d}-{k['end']:5d}] KEPT      {k['label']:<32} score={k['score']:.2f} text={k['text']!r}")
                print()
                n_shown += 1
                break
        if n_shown >= 12:
            break


if __name__ == "__main__":
    main()
