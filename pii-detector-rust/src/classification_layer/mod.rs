//! Tier C detection: zero-shot topic classification via GLiNER2.
//!
//! Where regex and NER produce per-span findings (this exact byte range
//! is an entity), classification produces per-document findings
//! (this whole input discusses health / religion / criminal record / …).
//! The two scopes don't compete with each other and the resolver treats
//! them independently.
//!
//! Build the layer once from `[layer = "classification"]` IPIs in the
//! taxonomy; call `detect(&runtime, text)` as needed.

use std::collections::HashMap;

use crate::error::Result;
use crate::findings::{Finding, SourceLayer};
use crate::gliner2::{ClassifyOptions, Runtime};
use crate::taxonomy::{Detection, Layer, Severity, Taxonomy};

#[derive(Debug, Clone)]
struct IpiInfo {
    ipi_id: String,
    threshold: f32,
    priority: i32,
    severity: Severity,
}

/// Topic classification detector built from the taxonomy's Tier C IPIs.
#[derive(Debug)]
pub struct ClassificationLayer {
    /// `classification label → IPIs that consume it` (multi-IPI supported).
    label_map: HashMap<String, Vec<IpiInfo>>,
}

impl ClassificationLayer {
    /// Build the layer from every active `layer = "classification"` IPI in `tax`.
    pub fn from_taxonomy(tax: &Taxonomy) -> Result<Self> {
        let mut label_map: HashMap<String, Vec<IpiInfo>> = HashMap::new();
        for ipi in tax.by_layer(Layer::Classification) {
            if let Detection::Classification {
                threshold,
                classification_label,
                ..
            } = &ipi.detection
            {
                label_map
                    .entry(classification_label.clone())
                    .or_default()
                    .push(IpiInfo {
                        ipi_id: ipi.id.clone(),
                        threshold: *threshold,
                        priority: ipi.priority,
                        severity: ipi.severity,
                    });
            }
        }
        Ok(Self { label_map })
    }

    pub fn labels(&self) -> Vec<&str> {
        let mut v: Vec<&str> = self.label_map.keys().map(String::as_str).collect();
        v.sort();
        v
    }

    pub fn ipi_count(&self) -> usize {
        self.label_map.values().map(Vec::len).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.label_map.is_empty()
    }

    fn min_threshold(&self) -> f32 {
        self.label_map
            .values()
            .flat_map(|v| v.iter().map(|i| i.threshold))
            .fold(1.0_f32, f32::min)
    }

    /// Score `text` against every taxonomy classification label.
    ///
    /// Always runs in multi-label mode (a document can discuss several
    /// sensitive topics simultaneously). Per-IPI thresholds filter the
    /// model's sigmoid scores.
    ///
    /// Each emitted [`Finding`] spans the entire input (`start=0,
    /// end=text.len()`) and carries `source_layer = Classification`.
    /// `matched_text` is intentionally empty — the topic is encoded in
    /// `ipi_id`, and the matching prose is the whole input.
    /// Minimum number of alphabetic characters a chunk must contain before
    /// classification is even attempted. Below this, the chunk is mostly
    /// whitespace / numbers / table separators and any positive classification
    /// is almost certainly spurious (the model still confidently labels noise).
    const MIN_ALPHA_CHARS: usize = 80;

    /// Maximum length of `matched_text` written into the Finding — a snippet
    /// for downstream visibility, not the whole chunk.
    const MATCHED_TEXT_SNIPPET: usize = 200;

    pub fn detect(&self, runtime: &Runtime, text: &str) -> Result<Vec<Finding>> {
        if self.label_map.is_empty() {
            return Ok(Vec::new());
        }

        // Skip near-empty / non-textual chunks. Counts only alphabetic chars
        // so dense tables of digits and IDs don't trigger classification.
        let alpha_count = text.chars().filter(|c| c.is_alphabetic()).count();
        if alpha_count < Self::MIN_ALPHA_CHARS {
            return Ok(Vec::new());
        }

        let labels = self.labels();
        let threshold = self.min_threshold();

        let classifications = runtime.classify(
            text,
            &labels,
            None,
            ClassifyOptions {
                threshold,
                multi_label: true,
            },
        )?;

        // Build a snippet of the chunk for downstream display.
        let snippet: String = text
            .chars()
            .take(Self::MATCHED_TEXT_SNIPPET)
            .collect::<String>()
            .trim()
            .to_string();

        let mut findings: Vec<Finding> = Vec::new();
        for cls in classifications {
            let Some(infos) = self.label_map.get(&cls.label) else {
                continue;
            };
            for info in infos {
                if cls.score >= info.threshold {
                    findings.push(Finding {
                        ipi_id: info.ipi_id.clone(),
                        matched_text: snippet.clone(),
                        start: 0,
                        end: text.len(),
                        score: cls.score,
                        priority: info.priority,
                        severity: info.severity,
                        source_layer: SourceLayer::Classification,
                    });
                }
            }
        }
        Ok(findings)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_tmp(name: &str, content: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join("ai-sentinel-pii-cls-tests");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        std::fs::File::create(&path)
            .unwrap()
            .write_all(content.as_bytes())
            .unwrap();
        path
    }

    const TAX: &str = r#"
[meta]
name = "cls-test"
version = "1.0.0"

[[category]]
id = "h"
display_fr = "Santé"
display_en = "Health"

[[category]]
id = "p"
display_fr = "Opinions"
display_en = "Opinions"

[[ipi]]
id = "HEALTH_DIAGNOSIS"
display_fr = "Diagnostic"
display_en = "Diagnosis"
category = "h"
sensitive = true
severity = "high"
priority = 80
layer = "classification"
threshold = 0.5
classification_label = "health_diagnosis"
classification_description = "Sections discussing medical diagnoses"

[[ipi]]
id = "HEALTH_TREATMENT"
display_fr = "Traitement"
display_en = "Treatment"
category = "h"
sensitive = true
severity = "high"
priority = 80
layer = "classification"
threshold = 0.5
classification_label = "health_treatment"
classification_description = "Sections discussing treatments"

[[ipi]]
id = "POLITICAL_OPINION"
display_fr = "Politique"
display_en = "Political"
category = "p"
sensitive = true
severity = "high"
priority = 80
layer = "classification"
threshold = 0.5
classification_label = "political_opinion"
classification_description = "Political affiliations"
"#;

    #[test]
    fn from_taxonomy_collects_classification_ipis() {
        let path = write_tmp("cls-tax.toml", TAX);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = ClassificationLayer::from_taxonomy(&tax).unwrap();
        assert_eq!(layer.ipi_count(), 3);
        assert_eq!(
            layer.labels(),
            vec!["health_diagnosis", "health_treatment", "political_opinion"]
        );
    }

    #[test]
    fn empty_taxonomy_yields_empty_layer() {
        let empty = r#"
[meta]
name = "empty"
version = "1.0.0"

[[category]]
id = "x"
display_fr = "x"
display_en = "x"

[[ipi]]
id = "ONLY_NER"
display_fr = "x"
display_en = "x"
category = "x"
severity = "low"
layer = "ner"
threshold = 0.5
ner_label = "person"
ner_description = "x"
"#;
        let path = write_tmp("empty-cls.toml", empty);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = ClassificationLayer::from_taxonomy(&tax).unwrap();
        assert!(layer.is_empty());
        assert_eq!(layer.ipi_count(), 0);
    }

    #[test]
    fn min_threshold_floor() {
        let custom = TAX.replace(
            r#"classification_label = "political_opinion"
classification_description = "Political affiliations""#,
            r#"classification_label = "political_opinion"
classification_description = "Political affiliations"
[[ipi]]
id = "EXTRA"
display_fr = "x"
display_en = "x"
category = "p"
severity = "low"
layer = "classification"
threshold = 0.3
classification_label = "extra_topic"
classification_description = "extra""#,
        );
        let path = write_tmp("cls-min.toml", &custom);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = ClassificationLayer::from_taxonomy(&tax).unwrap();
        assert!((layer.min_threshold() - 0.3).abs() < 1e-6);
    }

    #[test]
    fn disabled_ipi_is_excluded() {
        let path = write_tmp("cls-dis.toml", TAX);
        let mut tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "t"

[[disable]]
ids = ["POLITICAL_OPINION"]
"#;
        let ov_path = write_tmp("cls-ov.toml", ov);
        tax.apply_override(&ov_path).unwrap();
        let layer = ClassificationLayer::from_taxonomy(&tax).unwrap();
        assert_eq!(layer.ipi_count(), 2);
        assert!(!layer.labels().contains(&"political_opinion"));
    }
}
