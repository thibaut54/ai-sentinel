# `classification_layer/` — Tier C detection (topic classification via GLiNER2)

Runs zero-shot multi-label classification on the input text against every IPI marked `layer = "classification"`. Each label that scores above its IPI's threshold becomes a [`Finding`].

## Why this exists

A markdown / Confluence section like:

> "Le patient présente des symptômes dépressifs depuis 3 ans. Suivi en consultation psychiatrique mensuelle. Traitement par sertraline 50mg."

has **no clean entity span** to extract — there's no "person name" or "medical record number" you can point at. But the section unambiguously discusses health data (an Art. 5 nLPD sensitive category). Tier A (regex) misses it. Tier B (NER) misses it. **Tier C classification catches it** because the model can score the whole text against the label "health_diagnosis".

The colleague's baseline FP file shows entire pages of medical/legal discussion sitting *next to* a single phone number that got detected — meanwhile, the genuinely sensitive content was invisible to the old pipeline. Tier C closes that gap.

## Span semantics — different from regex / NER

Classification findings describe the **whole input**, not a sub-region. Their `start = 0, end = text.len()`. `matched_text` is left empty — the matching prose is the whole text, and the topic is encoded in `ipi_id`.

The resolver knows this and **passes classification findings through untouched**, regardless of overlapping span findings. A page can legitimately produce:

- `HEALTH_DIAGNOSIS` (classification, whole page)
- `PERSON_NAME` at byte 12 (NER, inside the page)
- `EMAIL_ADDRESS` at byte 47 (regex, inside the page)

All three coexist in the final output — they answer different questions about the same text.

## Multi-label by default

Documents commonly discuss several sensitive topics at once (health + criminal proceedings; politics + ethnic origin). The layer always uses multi-label mode and applies per-IPI thresholds independently to each label's sigmoid score.

If you want single-label "best topic" behavior, call `Runtime::classify` directly with `multi_label: false` — but for PII detection that's the wrong primitive (you'd suppress real sensitive-topic detections).

## Public API

```rust
use ai_sentinel_pii_detector::{
    classification_layer::ClassificationLayer,
    taxonomy::Taxonomy,
    gliner2::Runtime,
};

let tax = Taxonomy::load("config/nlpd-ipi.toml")?;
let runtime = Runtime::load("models/gliner2-large-v1-onnx")?;
let layer = ClassificationLayer::from_taxonomy(&tax)?;

let findings = layer.detect(&runtime, "Le patient présente des symptômes...")?;
// → [
//   Finding { ipi_id: "HEALTH_DIAGNOSIS",  source_layer: Classification, score: 0.94, ... },
//   Finding { ipi_id: "HEALTH_TREATMENT",  source_layer: Classification, score: 0.81, ... },
// ]
```

## Configuration

In the taxonomy, each classification IPI declares:

```toml
[[ipi]]
id = "HEALTH_DIAGNOSIS"
sensitive = true                                    # nLPD Art. 5
severity = "high"
priority = 80
layer = "classification"
threshold = 0.5                                     # sigmoid cutoff
classification_label = "health_diagnosis"           # tokenized into the schema
classification_description = "Sections discussing medical diagnoses, pathologies, illnesses, ICD codes"  # documentation
```

Same convention as the NER layer: `classification_label` is exactly what reaches the model; `classification_description` is informational.

## Performance notes

- One classify call ≈ one encoder pass + one tiny classifier-head pass (~8 MB). Slightly cheaper than NER (which also runs `span_rep` + `count_embed`).
- Adding more classification labels grows the schema linearly. Up to ~30 labels comfortably; beyond that consider grouping or per-page label-set selection.
- The encoder pass dominates — feeding the layer a 4 KB page costs roughly the same as a 1 KB page.
