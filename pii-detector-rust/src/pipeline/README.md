# `pipeline/` — End-to-end PII detection

The pipeline is the only thing application code should need to touch. It owns the three detection layers (regex + NER + classification), the chunker, the cross-chunk deduper, and the span-overlap resolver. Builds once from a `Taxonomy`, calls `detect(&runtime, text)` per page, returns the final `Vec<Finding>`.

## Flow

```
plain text (one page, post-HTML-strip)
    │
    ▼
chunker (max 1200 chars, 150 overlap, sentence-aware breaks)
    │
    ▼
for each chunk:
    ├─ RegexLayer::detect(chunk.text)         → findings (chunk-local offsets)
    ├─ NerLayer::detect(runtime, chunk.text)  → findings (chunk-local offsets)
    └─ ClassificationLayer::detect(runtime, chunk.text)  → findings (chunk-local offsets)
    │
    ▼
re-offset spans: f.start += chunk.start, f.end += chunk.start
    │
    ▼
dedup_exact_spans (same ipi_id + same span → keep highest score)
   removes duplicates produced by chunk-overlap zones
    │
    ▼
resolve_overlaps (priority-based span resolution;
                  classification findings exempt)
    │
    ▼
Vec<Finding>  (source-relative offsets, sorted by .start)
```

## API surface

```rust
use ai_sentinel_pii_detector::{
    pipeline::{Pipeline, PipelineStats},
    taxonomy::Taxonomy,
    gliner2::Runtime,
};

let tax = Taxonomy::load("config/nlpd-ipi.toml")?;
let runtime = Runtime::load("models/gliner2-large-v1-onnx")?;
let pipeline = Pipeline::from_taxonomy(&tax)?;

// Plain detection
let findings = pipeline.detect(&runtime, page_text)?;

// With per-stage timings (for profiling / benchmark runs)
let (findings, stats) = pipeline.detect_with_stats(&runtime, page_text)?;
println!("chunks={} raw={} dedup={} resolved={} total={:?}",
    stats.n_chunks, stats.raw_findings, stats.after_dedup, stats.after_resolve,
    stats.timing.total);
```

## Chunker configuration

Default: 1200 chars / 150 overlap. Override per use case:

```rust
use ai_sentinel_pii_detector::pipeline::{Pipeline, ChunkerConfig};

let pipeline = Pipeline::from_taxonomy(&tax)?
    .with_chunker(ChunkerConfig { max_chars: 2000, overlap_chars: 200 });
```

Boundary preference order: paragraph (`\n\n`) > sentence (`. `, `! `, `? `) > line (`\n`) > whitespace > hard char boundary.

## What this layer doesn't do

- **HTML stripping** — the input is plain text. In production, the Java side runs BeautifulSoup. The benchmark replay tool will do equivalent stripping in Rust before calling the pipeline. Either way, `Pipeline::detect` doesn't know about HTML.
- **gRPC** — the pipeline is a library API. The eventual `tonic` server will wrap it.
- **Persistence / caching** — every call is stateless. Re-feeding the same text yields the same findings.

## What about parallelism?

The pipeline currently runs chunks sequentially. The two reasons not to rayon-parallelize:

1. **`Mutex<Session>` in `gliner2`** serialises model calls anyway — parallel chunk dispatch wouldn't speed up NER/classification.
2. **Batching is strictly better** when we eventually optimise — packing N chunks into one encoder pass gives 3–5× speedup at no extra memory cost. The current per-chunk-loop is the right shape for that change later (collect chunk texts, one batched encoder call, scatter results).

For now: sequential is honest about the underlying cost.

## Cross-chunk dedup behavior

Two chunks overlap by 150 chars. An entity that falls in the overlap zone is detected by *both* chunks — with byte-identical spans after re-offsetting (because the source offsets agree).

`dedup_exact_spans` keys on `(ipi_id, start, end)` and keeps the highest score. The semantic outcome: one finding per entity per source location, even when chunks duplicate-detect.

Classification findings span whole chunks → their `(start, end)` differs per chunk → they don't dedup. That's correct: chunk 1 classifying as `HEALTH_DIAGNOSIS` is a different observation from chunk 2 classifying as `HEALTH_DIAGNOSIS`, even though they share the topic label.
