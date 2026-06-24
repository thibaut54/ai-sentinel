//! IPI taxonomy: the configurable list of personal-information types this
//! detector knows about, the layer that detects each one (regex / NER /
//! classification), and per-tenant overrides.

use std::collections::HashMap;
use std::path::Path;

use crate::error::{Error, Result};

mod loader;
mod overrides;
mod types;
mod validation;

pub use types::{Category, Detection, Ipi, Layer, Meta, Severity};

/// A loaded taxonomy: baseline IPIs plus any applied tenant overrides.
#[derive(Debug, Clone)]
pub struct Taxonomy {
    pub meta: Meta,
    pub categories: HashMap<String, Category>,
    pub ipis: Vec<Ipi>,
}

impl Taxonomy {
    /// Load a baseline taxonomy from a TOML file.
    ///
    /// The file structure:
    /// ```toml
    /// [meta]
    /// name = "..."
    /// version = "1.0.0"
    ///
    /// [[category]]
    /// id = "..."
    /// display_fr = "..."
    /// display_en = "..."
    ///
    /// [[ipi]]
    /// id = "..."
    /// # ... (see Ipi)
    /// ```
    pub fn load(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref();
        let doc = loader::read_baseline(path)?;
        validation::validate(&doc.categories, &doc.ipis)?;

        let categories: HashMap<String, Category> = doc
            .categories
            .into_iter()
            .map(|c| (c.id.clone(), c))
            .collect();

        Ok(Self {
            meta: doc.meta,
            categories,
            ipis: doc.ipis,
        })
    }

    /// Apply an override file to this taxonomy in place.
    ///
    /// Effects, in order:
    /// 1. Any `[[disable]]` block sets `enabled = false` on listed IPIs.
    /// 2. Any `[[override]]` block updates threshold/severity/priority/enabled
    ///    on the named IPI.
    /// 3. Any `[[custom_ipi]]` block is appended to the IPI list and must not
    ///    collide with an existing ID.
    ///
    /// Validation runs again after merging.
    pub fn apply_override(&mut self, path: impl AsRef<Path>) -> Result<()> {
        let path = path.as_ref();
        let doc = overrides::read_override(path)?;

        let known_ids: std::collections::HashSet<String> =
            self.ipis.iter().map(|i| i.id.clone()).collect();

        for block in &doc.disable {
            for id in &block.ids {
                if let Some(ipi) = self.ipis.iter_mut().find(|i| &i.id == id) {
                    ipi.enabled = false;
                } else {
                    return Err(Error::Taxonomy(format!(
                        "override: disable references unknown ipi id {id:?}"
                    )));
                }
            }
        }

        for block in &doc.overrides {
            let ipi = self
                .ipis
                .iter_mut()
                .find(|i| i.id == block.id)
                .ok_or_else(|| {
                    Error::Taxonomy(format!(
                        "override: tries to modify unknown ipi id {:?}",
                        block.id
                    ))
                })?;
            if let Some(t) = block.threshold {
                ipi.detection.set_threshold(t);
            }
            if let Some(s) = block.severity {
                ipi.severity = s;
            }
            if let Some(p) = block.priority {
                ipi.priority = p;
            }
            if let Some(e) = block.enabled {
                ipi.enabled = e;
            }
        }

        for new_ipi in doc.custom_ipis {
            if known_ids.contains(&new_ipi.id) {
                return Err(Error::Taxonomy(format!(
                    "override: custom_ipi {:?} collides with existing ipi",
                    new_ipi.id
                )));
            }
            self.ipis.push(new_ipi);
        }

        validation::validate(
            &self.categories.values().cloned().collect::<Vec<_>>(),
            &self.ipis,
        )?;

        Ok(())
    }

    /// Iterate IPIs whose `enabled = true`.
    pub fn active(&self) -> impl Iterator<Item = &Ipi> {
        self.ipis.iter().filter(|i| i.enabled)
    }

    pub fn by_id(&self, id: &str) -> Option<&Ipi> {
        self.ipis.iter().find(|i| i.id == id)
    }

    /// Iterate active IPIs in a given layer.
    pub fn by_layer(&self, layer: Layer) -> impl Iterator<Item = &Ipi> {
        self.active().filter(move |i| i.detection.layer() == layer)
    }

    /// Iterate active IPIs in a given category.
    pub fn by_category<'a>(&'a self, cat: &'a str) -> impl Iterator<Item = &'a Ipi> + 'a {
        self.active().filter(move |i| i.category == cat)
    }

    /// Iterate active IPIs marked as nLPD-sensitive (Art. 5 special categories).
    pub fn sensitive(&self) -> impl Iterator<Item = &Ipi> {
        self.active().filter(|i| i.sensitive)
    }

    /// Counts per layer, for sanity reporting on startup.
    pub fn layer_counts(&self) -> HashMap<Layer, usize> {
        let mut map: HashMap<Layer, usize> = HashMap::new();
        for ipi in self.active() {
            *map.entry(ipi.detection.layer()).or_insert(0) += 1;
        }
        map
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_tmp(name: &str, content: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join("ai-sentinel-pii-taxonomy-tests");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        let mut f = std::fs::File::create(&path).unwrap();
        f.write_all(content.as_bytes()).unwrap();
        path
    }

    fn baseline_toml() -> String {
        r#"
[meta]
name = "test"
version = "1.0.0"
description = "Test fixture"

[[category]]
id = "official"
display_fr = "Officiel"
display_en = "Official"

[[category]]
id = "contact"
display_fr = "Contact"
display_en = "Contact"

[[category]]
id = "health"
display_fr = "Santé"
display_en = "Health"

[[ipi]]
id = "AVS_NUMBER"
display_fr = "Numéro AVS"
display_en = "Swiss AVS number"
category = "official"
sensitive = false
severity = "high"
country = "CH"
priority = 100
layer = "regex"
threshold = 0.95
patterns = ['\b756\.\d{4}\.\d{4}\.\d{2}\b']
validators = ["avs_mod11"]

[[ipi]]
id = "EMAIL"
display_fr = "Adresse e-mail"
display_en = "Email address"
category = "contact"
severity = "low"
layer = "regex"
threshold = 0.9
patterns = ['[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}']

[[ipi]]
id = "PERSON_NAME"
display_fr = "Nom"
display_en = "Full name"
category = "official"
severity = "low"
layer = "ner"
threshold = 0.5
ner_label = "person"
ner_description = "Full name of an individual"

[[ipi]]
id = "HEALTH_DIAGNOSIS"
display_fr = "Diagnostic"
display_en = "Health diagnosis"
category = "health"
sensitive = true
severity = "high"
layer = "classification"
threshold = 0.6
classification_label = "health_diagnosis"
classification_description = "Sections discussing medical diagnoses"
"#
        .to_string()
    }

    #[test]
    fn loads_baseline() {
        let path = write_tmp("baseline.toml", &baseline_toml());
        let tax = Taxonomy::load(&path).unwrap();
        assert_eq!(tax.meta.name, "test");
        assert_eq!(tax.ipis.len(), 4);
        assert_eq!(tax.categories.len(), 3);
        assert_eq!(tax.active().count(), 4);

        let avs = tax.by_id("AVS_NUMBER").unwrap();
        assert_eq!(avs.detection.layer(), Layer::Regex);
        assert_eq!(avs.detection.threshold(), 0.95);
        assert_eq!(avs.priority, 100);
        assert_eq!(avs.country.as_deref(), Some("CH"));

        let counts = tax.layer_counts();
        assert_eq!(counts[&Layer::Regex], 2);
        assert_eq!(counts[&Layer::Ner], 1);
        assert_eq!(counts[&Layer::Classification], 1);

        let sensitive: Vec<&Ipi> = tax.sensitive().collect();
        assert_eq!(sensitive.len(), 1);
        assert_eq!(sensitive[0].id, "HEALTH_DIAGNOSIS");
    }

    #[test]
    fn rejects_duplicate_ipi_id() {
        let bad = baseline_toml().replace(r#"id = "EMAIL""#, r#"id = "AVS_NUMBER""#);
        let path = write_tmp("dup.toml", &bad);
        let err = Taxonomy::load(&path).unwrap_err();
        assert!(format!("{err}").contains("duplicate ipi id"));
    }

    #[test]
    fn rejects_unknown_category() {
        let bad = baseline_toml().replace(r#"category = "contact""#, r#"category = "ghost""#);
        let path = write_tmp("ghost-cat.toml", &bad);
        let err = Taxonomy::load(&path).unwrap_err();
        assert!(format!("{err}").contains("unknown category"));
    }

    #[test]
    fn rejects_out_of_range_threshold() {
        let bad = baseline_toml().replace("threshold = 0.95", "threshold = 1.5");
        let path = write_tmp("bad-tol.toml", &bad);
        let err = Taxonomy::load(&path).unwrap_err();
        assert!(format!("{err}").contains("threshold"));
    }

    #[test]
    fn override_disables_ipi() {
        let path = write_tmp("baseline-ov.toml", &baseline_toml());
        let mut tax = Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "tenant-x"
base = "test"

[[disable]]
ids = ["EMAIL"]
reason = "tenant does not store email"
"#;
        let ov_path = write_tmp("disable.toml", ov);
        tax.apply_override(&ov_path).unwrap();
        assert_eq!(tax.active().count(), 3);
        assert!(!tax.by_id("EMAIL").unwrap().enabled);
    }

    #[test]
    fn override_tweaks_threshold_and_severity() {
        let path = write_tmp("baseline-ov2.toml", &baseline_toml());
        let mut tax = Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "tenant-y"

[[override]]
id = "EMAIL"
threshold = 0.7
severity = "medium"
priority = 50
"#;
        let ov_path = write_tmp("tweak.toml", ov);
        tax.apply_override(&ov_path).unwrap();
        let email = tax.by_id("EMAIL").unwrap();
        assert_eq!(email.detection.threshold(), 0.7);
        assert_eq!(email.severity, Severity::Medium);
        assert_eq!(email.priority, 50);
    }

    #[test]
    fn override_adds_custom_ipi() {
        let path = write_tmp("baseline-ov3.toml", &baseline_toml());
        let mut tax = Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "tenant-z"

[[custom_ipi]]
id = "PROJECT_AURORA"
display_fr = "Codename Aurora"
display_en = "Codename Aurora"
category = "official"
severity = "medium"
layer = "regex"
threshold = 1.0
patterns = ['(?i)\bproject\s+aurora\b']
"#;
        let ov_path = write_tmp("custom.toml", ov);
        tax.apply_override(&ov_path).unwrap();
        assert!(tax.by_id("PROJECT_AURORA").is_some());
        assert_eq!(tax.ipis.len(), 5);
    }

    #[test]
    fn override_collision_is_rejected() {
        let path = write_tmp("baseline-ov4.toml", &baseline_toml());
        let mut tax = Taxonomy::load(&path).unwrap();
        let ov = r#"
[meta]
name = "tenant-bad"

[[custom_ipi]]
id = "EMAIL"
display_fr = "x"
display_en = "x"
category = "contact"
severity = "low"
layer = "regex"
threshold = 0.5
patterns = ['x']
"#;
        let ov_path = write_tmp("collide.toml", ov);
        let err = tax.apply_override(&ov_path).unwrap_err();
        assert!(format!("{err}").contains("collides"));
    }
}
