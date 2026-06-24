//! Tenant overrides: disable IPIs, tweak fields, add custom IPIs.
//!
//! Override files are layered on top of a baseline. The merge is *additive*
//! for `custom_ipi` and *destructive* (in a contained way) for `disable` +
//! `override` blocks. The baseline itself is never mutated on disk — only
//! the in-memory [`Taxonomy`] copy after [`Taxonomy::apply_override`].

use std::path::Path;

use serde::Deserialize;

use crate::error::{Error, Result};
use crate::taxonomy::types::{Ipi, Severity};

#[derive(Debug, Deserialize)]
pub(crate) struct OverrideDoc {
    #[allow(dead_code)]
    pub meta: OverrideMeta,
    #[serde(default)]
    pub disable: Vec<DisableBlock>,
    #[serde(default, rename = "override")]
    pub overrides: Vec<OverrideBlock>,
    #[serde(default, rename = "custom_ipi")]
    pub custom_ipis: Vec<Ipi>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct OverrideMeta {
    #[allow(dead_code)]
    pub name: String,
    /// Optional reference back to the baseline name (informational only —
    /// we don't enforce it matches at load time).
    #[serde(default)]
    #[allow(dead_code)]
    pub base: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct DisableBlock {
    pub ids: Vec<String>,
    #[serde(default)]
    #[allow(dead_code)]
    pub reason: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct OverrideBlock {
    pub id: String,
    #[serde(default)]
    pub threshold: Option<f32>,
    #[serde(default)]
    pub severity: Option<Severity>,
    #[serde(default)]
    pub priority: Option<i32>,
    #[serde(default)]
    pub enabled: Option<bool>,
}

pub(crate) fn read_override(path: &Path) -> Result<OverrideDoc> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| Error::Taxonomy(format!("read {}: {}", path.display(), e)))?;
    let doc: OverrideDoc = toml::from_str(&text)?;
    Ok(doc)
}
