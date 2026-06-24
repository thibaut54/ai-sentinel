use std::path::Path;

use serde::Deserialize;

use crate::error::{Error, Result};
use crate::taxonomy::types::{Category, Ipi, Meta};

#[derive(Debug, Deserialize)]
pub(crate) struct BaselineDoc {
    pub meta: Meta,
    #[serde(rename = "category")]
    pub categories: Vec<Category>,
    #[serde(rename = "ipi")]
    pub ipis: Vec<Ipi>,
}

pub(crate) fn read_baseline(path: &Path) -> Result<BaselineDoc> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| Error::Taxonomy(format!("read {}: {}", path.display(), e)))?;
    let doc: BaselineDoc = toml::from_str(&text)?;
    Ok(doc)
}
