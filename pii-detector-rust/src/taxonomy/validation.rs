use std::collections::HashSet;

use crate::error::{Error, Result};
use crate::taxonomy::types::{Category, Ipi};

/// Checks the invariants we want to hold across a loaded baseline:
///
/// - IPI IDs unique
/// - Category IDs unique
/// - Every IPI references a declared Category
/// - Thresholds in [0.0, 1.0]
/// - Country codes are 2 uppercase letters when present
/// - Regex IPIs have at least one pattern; NER/classification have non-empty label
pub(crate) fn validate(categories: &[Category], ipis: &[Ipi]) -> Result<()> {
    let mut seen_cat: HashSet<&str> = HashSet::with_capacity(categories.len());
    for cat in categories {
        if !seen_cat.insert(cat.id.as_str()) {
            return Err(Error::Taxonomy(format!(
                "duplicate category id: {:?}",
                cat.id
            )));
        }
    }

    let mut seen_ipi: HashSet<&str> = HashSet::with_capacity(ipis.len());
    for ipi in ipis {
        if !seen_ipi.insert(ipi.id.as_str()) {
            return Err(Error::Taxonomy(format!("duplicate ipi id: {:?}", ipi.id)));
        }

        if !seen_cat.contains(ipi.category.as_str()) {
            return Err(Error::Taxonomy(format!(
                "ipi {:?} references unknown category {:?}",
                ipi.id, ipi.category
            )));
        }

        let t = ipi.detection.threshold();
        if !(0.0..=1.0).contains(&t) {
            return Err(Error::Taxonomy(format!(
                "ipi {:?}: threshold {} out of [0.0, 1.0]",
                ipi.id, t
            )));
        }

        if let Some(cc) = &ipi.country {
            let ok = cc.len() == 2 && cc.chars().all(|c| c.is_ascii_uppercase());
            if !ok {
                return Err(Error::Taxonomy(format!(
                    "ipi {:?}: country must be 2 uppercase ASCII letters, got {:?}",
                    ipi.id, cc
                )));
            }
        }

        match &ipi.detection {
            crate::taxonomy::types::Detection::Regex { patterns, .. } => {
                if patterns.is_empty() {
                    return Err(Error::Taxonomy(format!(
                        "ipi {:?}: regex layer requires at least one pattern",
                        ipi.id
                    )));
                }
            }
            crate::taxonomy::types::Detection::Ner {
                ner_label,
                ner_description,
                ..
            } => {
                if ner_label.is_empty() || ner_description.is_empty() {
                    return Err(Error::Taxonomy(format!(
                        "ipi {:?}: ner_label and ner_description must be non-empty",
                        ipi.id
                    )));
                }
            }
            crate::taxonomy::types::Detection::Classification {
                classification_label,
                classification_description,
                ..
            } => {
                if classification_label.is_empty() || classification_description.is_empty() {
                    return Err(Error::Taxonomy(format!(
                        "ipi {:?}: classification_label and classification_description must be non-empty",
                        ipi.id
                    )));
                }
            }
        }
    }

    Ok(())
}
