use std::collections::HashMap;
use std::path::Path;

use serde::Deserialize;

use crate::error::{Error, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Precision {
    Fp32,
    Fp16,
}

impl Precision {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Fp32 => "fp32",
            Self::Fp16 => "fp16",
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct OnnxModelFiles {
    pub encoder: String,
    pub classifier: String,
    pub span_rep: String,
    pub count_embed: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GLiNER2Config {
    pub max_width: usize,
    pub special_tokens: HashMap<String, u32>,
    pub onnx_files: HashMap<String, OnnxModelFiles>,
}

impl GLiNER2Config {
    pub fn load(model_dir: &Path) -> Result<Self> {
        let path = model_dir.join("gliner2_config.json");
        let bytes = std::fs::read(&path)
            .map_err(|e| Error::ModelNotFound(format!("{}: {}", path.display(), e)))?;
        let config: Self = serde_json::from_slice(&bytes)?;
        config.validate()?;
        Ok(config)
    }

    pub fn files_for(&self, precision: Precision) -> Result<&OnnxModelFiles> {
        self.onnx_files.get(precision.as_str()).ok_or_else(|| {
            let available: Vec<&str> = self.onnx_files.keys().map(String::as_str).collect();
            Error::Config(format!(
                "precision '{}' not available; have: {:?}",
                precision.as_str(),
                available
            ))
        })
    }

    pub fn special_token_id(&self, token: &str) -> Result<u32> {
        self.special_tokens
            .get(token)
            .copied()
            .ok_or_else(|| Error::Config(format!("missing special token: {token}")))
    }

    fn validate(&self) -> Result<()> {
        use super::tokens::{TOKEN_E, TOKEN_L, TOKEN_P, TOKEN_SEP_TEXT};
        for required in [TOKEN_P, TOKEN_L, TOKEN_E, TOKEN_SEP_TEXT] {
            if !self.special_tokens.contains_key(required) {
                return Err(Error::Config(format!(
                    "missing required special token: {required}"
                )));
            }
        }
        if self.onnx_files.is_empty() {
            return Err(Error::Config("onnx_files is empty".into()));
        }
        Ok(())
    }
}
