# `taxonomy/` — IPI catalog and tenant overrides

Two-layer config:

1. **Baseline TOML** (`config/nlpd-ipi.toml`) — the canonical IPI catalog. Source of truth: Swiss nLPD Art. 5 IPI list, supplemented with regex/NER/classification dispatch info per item.
2. **Override TOML** (`config/overrides/<tenant>.toml`) — per-tenant deltas: disable IPIs, tweak thresholds/severity/priority/enabled, or add custom IPIs (e.g. internal project codenames).

The baseline is never mutated on disk. `Taxonomy::apply_override` produces an in-memory merged view.

## Schema (baseline)

```toml
[meta]
name = "nlpd-baseline"
version = "1.0.0"
description = "Swiss nLPD Art. 5 IPI"

[[category]]
id = "identification_directe"
display_fr = "Identification directe"
display_en = "Direct identification"

[[ipi]]
id = "AVS_NUMBER"                 # canonical, stable ID
display_fr = "Numéro AVS"
display_en = "Swiss AVS number"
category = "identification_directe"
sensitive = false                  # nLPD Art. 5 sensitive flag
severity = "high"                  # low | medium | high
country = "CH"                     # optional ISO 3166-1 alpha-2
priority = 100                     # higher wins on span conflict
enabled = true                     # default true
layer = "regex"                    # regex | ner | classification
threshold = 0.95
# regex-specific:
patterns = ['\b756\.\d{4}\.\d{4}\.\d{2}\b']
validators = ["avs_mod11"]         # names resolved by regex_layer
```

The `layer` field discriminates between three shapes:

| `layer = "regex"`           | `layer = "ner"`               | `layer = "classification"`            |
|-----------------------------|-------------------------------|---------------------------------------|
| `threshold`                 | `threshold`                   | `threshold`                           |
| `patterns: [...]`           | `ner_label`                   | `classification_label`                |
| `validators: [...]` (opt)   | `ner_description`             | `classification_description`          |

`validators` are named hooks (e.g. `luhn`, `iban_checksum`, `avs_mod11`, `entropy:4.0`). The names live here; the implementations live in `regex_layer/` once that module exists. Unknown names should fail at regex-layer build time, not at taxonomy load time, so adding new validators stays a one-place change.

## Schema (override)

```toml
[meta]
name = "tenant-x"
base = "nlpd-baseline"             # informational

[[disable]]
ids = ["DRIVER_LICENSE_NUMBER", "MEDICAL_RECORD_NUMBER"]
reason = "tenant has no driving or medical records"

[[override]]
id = "PERSON_NAME"
threshold = 0.8                    # any subset of threshold/severity/priority/enabled

[[custom_ipi]]                     # same shape as [[ipi]] in baseline
id = "PROJECT_AURORA"
display_fr = "Codename Aurora"
display_en = "Codename Aurora"
category = "identification_directe"
severity = "medium"
layer = "regex"
threshold = 1.0
patterns = ['(?i)\bproject\s+aurora\b']
```

Override order is fixed: **disable → override → custom_ipi**. The merged taxonomy is re-validated after every override is applied — unknown IDs, threshold-out-of-range, or custom-IPI collisions all fail loudly at load time.

## Public API

```rust
use ai_sentinel_pii_detector::taxonomy::{Taxonomy, Layer};

let mut tax = Taxonomy::load("config/nlpd-ipi.toml")?;
tax.apply_override("config/overrides/tenant-x.toml")?;

// Lookup
let avs = tax.by_id("AVS_NUMBER").unwrap();

// Filter
let ner_schema: Vec<&Ipi> = tax.by_layer(Layer::Ner).collect();
let sensitive_only: Vec<&Ipi> = tax.sensitive().collect();
let by_cat: Vec<&Ipi> = tax.by_category("sensible_sante").collect();

// Stats
println!("{:?}", tax.layer_counts());
// { Regex: 12, Ner: 8, Classification: 6 }
```

## How the downstream layers consume this

| Module          | Input from taxonomy                                          | Output                                  |
|-----------------|--------------------------------------------------------------|-----------------------------------------|
| `regex_layer`   | `by_layer(Layer::Regex)` → compile patterns + validators     | regex match findings                    |
| `gliner2` (NER) | `by_layer(Layer::Ner)` → `(ner_label, ner_description)` schema | entity findings                       |
| `gliner2` (cls) | `by_layer(Layer::Classification)` → `(cls_label, cls_description)` schema | classification findings    |
| `pipeline`      | `priority` field for span-conflict resolution                | merged findings tagged with canonical ID |
| Reporting       | `display_fr`/`display_en`, `category`, `sensitive`, `severity` | human-readable output                |

The taxonomy is the *single source of truth* for what gets detected. Adding a new IPI is one diff in one TOML file plus optionally one new validator implementation. No code changes for taxonomy-only updates.

## What's not configurable here (by design)

- **Model choice.** GLiNER2 is baked into `gliner2/`. Adding a second model would be a separate decision.
- **Engine-level params** (chunk size, batch size, ONNX EP). Those live in a future `config/pipeline.toml`.
- **Output schema.** The gRPC `PIIEntity` shape comes from the existing `.proto`. Taxonomy fills the values; it doesn't change the contract.
