//! Tier B detection: NER via GLiNER2 zero-shot.
//!
//! Compiles every taxonomy IPI with `layer = "ner"` into a single
//! GLiNER2 NER schema, runs one model call per `detect()`, and maps the
//! model's per-label outputs back to canonical IPI ids — including
//! handling the multi-IPI-per-label case (two IPIs sharing one model
//! label both emit findings).
//!
//! Designed to mirror [`crate::regex_layer::RegexLayer`]'s ergonomics:
//! built once from a [`Taxonomy`], stateless after construction, safe
//! to share across threads. The [`Runtime`] is passed in at detection
//! time so a single model can serve many layers.

use std::collections::HashMap;

use crate::error::{Error, Result};
use crate::findings::{Finding, SourceLayer};
use crate::gliner2::Runtime;
use crate::regex_layer::Validator;
use crate::taxonomy::{Detection, Layer, Severity, Taxonomy};

#[derive(Debug, Clone)]
struct IpiInfo {
    ipi_id: String,
    threshold: f32,
    priority: i32,
    severity: Severity,
    validators: Vec<Validator>,
}

/// NER detector built from the taxonomy's Tier B IPIs.
#[derive(Debug)]
pub struct NerLayer {
    /// `gliner2 label → IPIs that consume it`. Multi-IPI per label is supported.
    label_map: HashMap<String, Vec<IpiInfo>>,
    /// `gliner2 label → ner_description` from the taxonomy. Sent alongside the
    /// label to GLiNER2 via the `[DESCRIPTION]` special token. If multiple IPIs
    /// share a label with different descriptions, the first one wins.
    descriptions: HashMap<String, String>,
}

impl NerLayer {
    /// Build the layer from every active `layer = "ner"` IPI in `tax`.
    pub fn from_taxonomy(tax: &Taxonomy) -> Result<Self> {
        let mut label_map: HashMap<String, Vec<IpiInfo>> = HashMap::new();
        let mut descriptions: HashMap<String, String> = HashMap::new();
        for ipi in tax.by_layer(Layer::Ner) {
            if let Detection::Ner {
                threshold,
                ner_label,
                ner_description,
                validators: validator_names,
                ..
            } = &ipi.detection
            {
                let mut validators: Vec<Validator> = Vec::with_capacity(validator_names.len());
                for name in validator_names {
                    validators.push(Validator::parse(name).map_err(|e| {
                        Error::Taxonomy(format!("ipi {:?}: {e}", ipi.id))
                    })?);
                }
                descriptions
                    .entry(ner_label.clone())
                    .or_insert_with(|| ner_description.clone());
                // emit_as lets an internal IPI (e.g. PASSWORD_NER) surface
                // findings under a canonical ID (PASSWORD) so downstream
                // and eval treat regex+NER detectors as one logical IPI.
                let emitted_id = ipi.emit_as.clone().unwrap_or_else(|| ipi.id.clone());
                label_map
                    .entry(ner_label.clone())
                    .or_default()
                    .push(IpiInfo {
                        ipi_id: emitted_id,
                        threshold: *threshold,
                        priority: ipi.priority,
                        severity: ipi.severity,
                        validators,
                    });
            }
        }
        Ok(Self {
            label_map,
            descriptions,
        })
    }

    /// Distinct gliner2 labels this layer asks the model to extract.
    pub fn labels(&self) -> Vec<&str> {
        let mut v: Vec<&str> = self.label_map.keys().map(String::as_str).collect();
        v.sort();
        v
    }

    /// Number of IPIs covered (multi-IPI labels count separately).
    pub fn ipi_count(&self) -> usize {
        self.label_map.values().map(Vec::len).sum()
    }

    /// True iff there are no NER IPIs to detect.
    pub fn is_empty(&self) -> bool {
        self.label_map.is_empty()
    }

    /// Minimum threshold across all NER IPIs. Used as the call-site
    /// threshold to the model so no IPI is starved of candidates — per-IPI
    /// thresholds are then applied to the returned entities.
    fn min_threshold(&self) -> f32 {
        self.label_map
            .values()
            .flat_map(|v| v.iter().map(|info| info.threshold))
            .fold(1.0_f32, f32::min)
    }

    /// Run the model on `text`. Returns [`Finding`]s tagged with canonical
    /// IPI ids. Same-label multi-IPI emits one finding per IPI.
    ///
    /// `text` is passed to the model as-is; this layer does **not** chunk.
    /// For very long inputs (more than the encoder's context window) the
    /// pipeline is responsible for splitting before calling.
    pub fn detect(&self, runtime: &Runtime, text: &str) -> Result<Vec<Finding>> {
        if self.label_map.is_empty() {
            return Ok(Vec::new());
        }
        let labels = self.labels();
        let descriptions: Vec<&str> = labels
            .iter()
            .map(|l| self.descriptions.get(*l).map(String::as_str).unwrap_or(""))
            .collect();
        let threshold = self.min_threshold();
        let entities =
            runtime.extract_entities(text, &labels, Some(&descriptions), threshold)?;

        let mut findings: Vec<Finding> = Vec::new();
        for ent in entities {
            let Some(infos) = self.label_map.get(&ent.label) else {
                continue;
            };
            for info in infos {
                if ent.score < info.threshold {
                    continue;
                }
                if !info.validators.iter().all(|v| v.validate(&ent.text)) {
                    continue;
                }
                findings.push(Finding {
                    ipi_id: info.ipi_id.clone(),
                    matched_text: ent.text.clone(),
                    start: ent.start,
                    end: ent.end,
                    score: ent.score,
                    priority: info.priority,
                    severity: info.severity,
                    source_layer: SourceLayer::Ner,
                });
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
        let dir = std::env::temp_dir().join("ai-sentinel-pii-ner-layer-tests");
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
name = "ner-test"
version = "1.0.0"

[[category]]
id = "id"
display_fr = "x"
display_en = "x"

[[ipi]]
id = "PERSON_NAME"
display_fr = "Nom"
display_en = "Name"
category = "id"
severity = "low"
priority = 50
layer = "ner"
threshold = 0.5
ner_label = "person"
ner_description = "Full name of an individual"

[[ipi]]
id = "ORG_NAME"
display_fr = "Org"
display_en = "Org"
category = "id"
severity = "low"
priority = 40
layer = "ner"
threshold = 0.6
ner_label = "organization"
ner_description = "Name of a company or organization"

[[ipi]]
id = "EMPLOYEE_NAME"
display_fr = "Employé"
display_en = "Employee"
category = "id"
severity = "medium"
priority = 55
layer = "ner"
threshold = 0.8
ner_label = "person"
ner_description = "Name of a company employee"
"#;

    #[test]
    fn from_taxonomy_collects_ner_ipis() {
        let path = write_tmp("ner-tax.toml", TAX);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = NerLayer::from_taxonomy(&tax).unwrap();
        assert_eq!(layer.ipi_count(), 3);
        assert_eq!(layer.labels(), vec!["organization", "person"]);
        assert!(!layer.is_empty());
    }

    #[test]
    fn shared_label_groups_multiple_ipis() {
        let path = write_tmp("ner-tax2.toml", TAX);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = NerLayer::from_taxonomy(&tax).unwrap();
        let person_ipis = layer.label_map.get("person").unwrap();
        assert_eq!(person_ipis.len(), 2);
        let ids: Vec<&str> = person_ipis.iter().map(|i| i.ipi_id.as_str()).collect();
        assert!(ids.contains(&"PERSON_NAME"));
        assert!(ids.contains(&"EMPLOYEE_NAME"));
    }

    #[test]
    fn min_threshold_is_lowest_per_ipi_threshold() {
        let path = write_tmp("ner-tax3.toml", TAX);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = NerLayer::from_taxonomy(&tax).unwrap();
        // 0.5 (PERSON_NAME) is the floor across {0.5, 0.6, 0.8}.
        assert!((layer.min_threshold() - 0.5).abs() < 1e-6);
    }

    #[test]
    fn empty_taxonomy_yields_empty_layer() {
        let empty_tax = r#"
[meta]
name = "empty"
version = "1.0.0"

[[category]]
id = "x"
display_fr = "x"
display_en = "x"

[[ipi]]
id = "ONLY_REGEX"
display_fr = "r"
display_en = "r"
category = "x"
severity = "low"
layer = "regex"
threshold = 0.9
patterns = ['\d+']
"#;
        let path = write_tmp("empty.toml", empty_tax);
        let tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let layer = NerLayer::from_taxonomy(&tax).unwrap();
        assert!(layer.is_empty());
        assert_eq!(layer.ipi_count(), 0);
        assert_eq!(layer.labels(), Vec::<&str>::new());
    }

    #[test]
    fn disabled_ipis_are_excluded() {
        let path = write_tmp("ner-tax4.toml", TAX);
        let mut tax = crate::taxonomy::Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "t"

[[disable]]
ids = ["ORG_NAME"]
"#;
        let ov_path = write_tmp("ner-ov.toml", ov);
        tax.apply_override(&ov_path).unwrap();
        let layer = NerLayer::from_taxonomy(&tax).unwrap();
        assert_eq!(layer.ipi_count(), 2);
        assert_eq!(layer.labels(), vec!["person"]);
    }
}
