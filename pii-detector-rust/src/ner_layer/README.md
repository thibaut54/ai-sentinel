# `ner_layer/` — Tier B detection (zero-shot NER via GLiNER2)

Wraps the GLiNER2 NER head with the taxonomy. For each active IPI where `layer = "ner"`, the IPI's `ner_label` becomes a GLiNER2 zero-shot label. One `detect()` call = one model invocation.

## Pipeline shape

```
Taxonomy (Tier B IPIs)        Plain text
        │                          │
        ▼                          ▼
   ┌──────────────────────────────────────┐
   │  NerLayer::detect(&runtime, &text)   │
   │     ↓                                │
   │  Runtime::extract_entities(...)      │
   │     ↓                                │
   │  for each model output:              │
   │    map gliner2 label → IPI(s)        │
   │    apply per-IPI threshold           │
   │    emit Finding{source_layer: Ner}   │
   └──────────────────────────────────────┘
        │
        ▼
   Vec<Finding>  (consumed by resolve + pipeline)
```

## What the taxonomy gets to control

```toml
[[ipi]]
id = "PERSON_NAME"
layer = "ner"
threshold = 0.5
ner_label = "person"
ner_description = "Full name of an individual"  # for humans; not sent to the model
```

- `ner_label` is **exactly** what gets tokenized into the GLiNER2 schema. Short labels (`"person"`, `"organization"`) match what the model was fine-tuned on and produce the most reliable scores.
- `ner_description` is informational — it documents the intent of the label for taxonomy maintainers. It is **not** currently passed to the model.
- `threshold` is per-IPI. The layer calls the model with the **minimum** threshold across all NER IPIs (so no IPI is starved of candidates), then filters each returned entity against the IPI it maps to.

## Multi-IPI per label

Two IPIs can share the same `ner_label`. Both emit findings on every model hit:

```toml
[[ipi]] id = "PERSON_NAME"    threshold = 0.5 ner_label = "person" ...
[[ipi]] id = "EMPLOYEE_NAME"  threshold = 0.8 ner_label = "person" ...
```

A model output with `label="person"`, `score=0.6` emits:
- `PERSON_NAME` (score 0.6 ≥ 0.5 ✓)
- not `EMPLOYEE_NAME` (score 0.6 < 0.8 ✗)

A model output with `label="person"`, `score=0.9` emits both.

Use this when different IPIs need the same model label but different priorities, severities, or thresholds.

## What this layer doesn't do

- **Chunking** — the model has a context window (~131k tokens for GLiNER2-large-v1, but realistic per-page latency suggests keeping under a few thousand tokens). Long inputs must be split by the pipeline before calling `detect`.
- **Span resolution** — overlap between NER findings and regex / classification findings is the resolver's job; this layer just emits raw `Finding`s.
- **Confidence calibration** — model scores are reported as-is. The `Finding.score` matches the model's sigmoid output.

## Public API

```rust
use ai_sentinel_pii_detector::{
    ner_layer::NerLayer, taxonomy::Taxonomy, gliner2::Runtime,
};

let tax = Taxonomy::load("config/nlpd-ipi.toml")?;
let runtime = Runtime::load("models/gliner2-large-v1-onnx")?;
let layer = NerLayer::from_taxonomy(&tax)?;

let findings = layer.detect(&runtime, "Alice Dupont works at Google in Lausanne.")?;
// → [
//   Finding { ipi_id: "PERSON_NAME",     start: 0,  end: 12, score: 0.99, source_layer: Ner, ... },
//   Finding { ipi_id: "ORGANIZATION_NAME", start: 22, end: 28, score: 0.99, source_layer: Ner, ... },
//   Finding { ipi_id: "CITY",            start: 32, end: 40, score: 0.99, source_layer: Ner, ... },
// ]
```

## Performance notes

- One NER call ≈ one encoder pass + one span_rep pass + one count_embed call. On CPU with the FP32 large model, ~50–150 ms per inference depending on input length and label count.
- Adding more labels grows the schema prefix linearly (each `[E] <label>` is a handful of tokens). 10 labels add < 1ms of overhead; 100 labels would be noticeable.
- `min_threshold` across IPIs means a single model call covers everyone. We pay for what we use, not per-IPI.
