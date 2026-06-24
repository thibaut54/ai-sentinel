use serde::{Deserialize, Serialize};

/// Metadata about a taxonomy or override file.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct Meta {
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub description: String,
}

/// A category groups related IPIs for reporting and bulk enable/disable.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct Category {
    pub id: String,
    pub display_fr: String,
    pub display_en: String,
}

/// Risk tier for a finding. Drives downstream triage / redaction priority.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Low,
    Medium,
    High,
}

/// Which detection mechanism handles an IPI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Layer {
    Regex,
    Ner,
    Classification,
}

/// Per-layer configuration. Discriminated by the `layer` field in TOML.
///
/// Example TOML for each variant:
/// ```toml
/// layer = "regex"
/// threshold = 0.95
/// patterns = ['\b756[.\s]?\d{4}[.\s]?\d{4}[.\s]?\d{2}\b']
/// validators = ["avs_mod11"]
/// ```
/// ```toml
/// layer = "ner"
/// threshold = 0.5
/// ner_label = "person"
/// ner_description = "Full name of an individual"
/// ```
/// ```toml
/// layer = "classification"
/// threshold = 0.5
/// classification_label = "health_diagnosis"
/// classification_description = "Sections discussing medical diagnoses, pathologies, illnesses"
/// ```
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(tag = "layer", rename_all = "snake_case")]
pub enum Detection {
    Regex {
        threshold: f32,
        patterns: Vec<String>,
        #[serde(default)]
        validators: Vec<String>,
    },
    Ner {
        threshold: f32,
        ner_label: String,
        ner_description: String,
        #[serde(default)]
        validators: Vec<String>,
    },
    Classification {
        threshold: f32,
        classification_label: String,
        classification_description: String,
    },
}

impl Detection {
    pub fn layer(&self) -> Layer {
        match self {
            Self::Regex { .. } => Layer::Regex,
            Self::Ner { .. } => Layer::Ner,
            Self::Classification { .. } => Layer::Classification,
        }
    }

    pub fn threshold(&self) -> f32 {
        match self {
            Self::Regex { threshold, .. }
            | Self::Ner { threshold, .. }
            | Self::Classification { threshold, .. } => *threshold,
        }
    }

    pub(crate) fn set_threshold(&mut self, value: f32) {
        match self {
            Self::Regex { threshold, .. }
            | Self::Ner { threshold, .. }
            | Self::Classification { threshold, .. } => *threshold = value,
        }
    }
}

/// One identifiable personal information type.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Ipi {
    /// Stable canonical identifier (e.g. `AVS_NUMBER`, `PERSON_NAME`).
    pub id: String,

    /// Human-readable French label.
    pub display_fr: String,

    /// Human-readable English label.
    pub display_en: String,

    /// ID of a [`Category`] declared elsewhere in the file.
    pub category: String,

    /// nLPD "sensitive" flag (Art. 5 special categories — health, religion, …).
    #[serde(default)]
    pub sensitive: bool,

    pub severity: Severity,

    /// ISO 3166-1 alpha-2 country code if the IPI is region-specific (e.g. `"CH"` for AVS).
    #[serde(default)]
    pub country: Option<String>,

    /// Higher wins when two IPIs match the same span. Default 0.
    #[serde(default)]
    pub priority: i32,

    #[serde(default = "default_enabled")]
    pub enabled: bool,

    /// Optional context filter: drop this IPI's findings unless at least one
    /// finding from any of these IPIs sits within `nearby_window_chars`
    /// of the span. Encodes "this IPI is only PII when near a person, an
    /// email, etc." — semantic constraints a NER label can't express.
    #[serde(default)]
    pub requires_nearby_ipi: Vec<String>,

    /// Character window for [`Self::requires_nearby_ipi`]. Ignored if that
    /// list is empty. Defaults to 0 (which would drop everything when the
    /// filter is active — set explicitly in TOML when using the filter).
    #[serde(default)]
    pub nearby_window_chars: usize,

    /// Optional canonical IPI ID to emit findings under. Lets two IPIs in
    /// different layers (e.g. `PASSWORD` regex + `PASSWORD_NER` NER) share a
    /// single canonical name for downstream consumers and eval scoring.
    /// Defaults to `id` when None.
    #[serde(default)]
    pub emit_as: Option<String>,

    #[serde(flatten)]
    pub detection: Detection,
}

fn default_enabled() -> bool {
    true
}
