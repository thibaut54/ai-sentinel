//! Shared `Finding` type produced by all detection layers (regex, NER,
//! classification). Carries everything downstream needs to resolve
//! conflicts, triage, and report.

use crate::taxonomy::Severity;

/// Which detection layer produced a finding.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SourceLayer {
    Regex,
    Ner,
    Classification,
}

/// One detected PII span.
#[derive(Debug, Clone, PartialEq)]
pub struct Finding {
    /// Canonical IPI id (e.g. `"AVS_NUMBER"`).
    pub ipi_id: String,

    /// The matched substring (entity span content).
    pub matched_text: String,

    /// Byte offsets into the input that was scanned.
    pub start: usize,
    pub end: usize,

    /// Confidence in `[0, 1]`. For regex layer = the IPI's declared threshold;
    /// for NER / classification = the model's sigmoid score.
    pub score: f32,

    /// Span-conflict priority — higher wins when spans overlap.
    /// Copied from the IPI at detection time so the resolver doesn't
    /// need the taxonomy on hand.
    pub priority: i32,

    /// Risk tier copied from the IPI for downstream triage / reporting.
    pub severity: Severity,

    /// Which layer produced this — useful for debug / provenance / metrics.
    pub source_layer: SourceLayer,
}
