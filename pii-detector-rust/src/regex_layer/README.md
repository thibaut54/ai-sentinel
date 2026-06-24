# `regex_layer/` — deterministic detection (Tier A)

This is the deterministic-pattern detector. It owns everything in the
taxonomy where `layer = "regex"`:

- compiles the `patterns` into `regex::Regex`
- resolves the named `validators` (`luhn`, `iban_checksum`, `swiss_avs`,
  `entropy:<f>`) to actual functions
- applies all validators to each match — a single failing validator drops the finding
- emits [`Finding`]s with character spans pointing back into the input

It is intentionally narrow: no markdown awareness, no chunking, no
overlap resolution. The pipeline layer feeds it text and merges its
output with NER / classification findings.

## Why a separate layer (vs. letting GLiNER2 do everything)

Tier A items are structured: IBAN, AVS, credit cards, IPs, MACs, emails,
API keys. NER models hallucinate badly on these — see the colleague's
baseline:

| Type            | NER precision | Tier A regex+validator equivalent |
|-----------------|--------------:|----------------------------------:|
| `CREDIT_CARD`   |        16.8 % | ~100 % with `luhn`               |
| `API_KEY`       |        13.6 % | ~95 % with entropy + format     |
| `NATIONAL_ID`   |        19.9 % | replaced by `swiss_avs` (100 %) |

Moving these to deterministic detection is the single biggest precision
win in the rewrite plan. The model handles fuzzy/contextual entities
(names, addresses, topics); regex handles patterns it'd miss.

## Validators in code

Each validator is a variant of [`Validator`] with a `parse(name)` and a
`validate(candidate)` method. Adding a new one is three edits in
`validators.rs`:

```rust
pub enum Validator {
    // ... existing
    EanProductCode,                          // 1. add variant
}

impl Validator {
    pub fn parse(name: &str) -> Result<Self> {
        match name {
            // ... existing
            "ean_product"  => Ok(Self::EanProductCode),   // 2. parse
            // ...
        }
    }
    pub fn validate(&self, candidate: &str) -> bool {
        match self {
            // ... existing
            Self::EanProductCode => ean_valid(candidate),  // 3. dispatch
        }
    }
}
```

Unknown validator names fail at `RegexLayer::from_taxonomy` startup, not
at first match — bad config is loud, not silent.

## Capture-group convention

If a pattern has a numbered capture group `(...)`, the **group 1 span**
becomes the entity's span and text. Otherwise the whole match is used.

This lets patterns include surrounding context for disambiguation without
polluting the finding:

```toml
# Match "api_key = <token>" but emit only <token> as the entity:
patterns = ['(?i)\bapi_key\s*[:=]\s*([A-Za-z0-9_\-]{20,})']
```

The finding's span points at `<token>`, not at the `api_key = ` prefix.

## Score model

A regex match either passes or doesn't — there's no native confidence.
We assign `score = ipi.threshold` to every passing finding. Downstream
overlap resolution can compare against NER/classification scores on the
same scale.

If you want a finer scoring scheme later (e.g. validator-weighted), add
it in [`Finding`] — the type is already public.

## Output

```rust
use ai_sentinel_pii_detector::{taxonomy::Taxonomy, regex_layer::RegexLayer};

let tax = Taxonomy::load("config/nlpd-ipi.toml")?;
let layer = RegexLayer::from_taxonomy(&tax)?;

let findings = layer.detect("contact alice@example.ch, IBAN CH93 0076 2011 6238 5295 7");
// → [
//   Finding { ipi_id: "EMAIL_ADDRESS",     matched_text: "alice@example.ch", ... },
//   Finding { ipi_id: "IBAN_CODE",          matched_text: "CH93 0076 ...",  ... },
// ]
```

Build once, share across threads (`RegexLayer: Send + Sync`), call
`detect` per page or per chunk.
