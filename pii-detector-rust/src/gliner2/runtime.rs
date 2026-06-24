use std::path::Path;

use tokenizers::Tokenizer;

use crate::error::{Error, Result};
use crate::gliner2::config::{GLiNER2Config, Precision};
use crate::gliner2::sessions::Sessions;

/// Loaded GLiNER2 model. Holds the four ONNX sessions, the tokenizer, and the
/// model-specific config. Cheap to share via `&` across threads; the heavy
/// resources are inside the sessions, which `ort` makes thread-safe.
pub struct Runtime {
    pub(crate) config: GLiNER2Config,
    pub(crate) tokenizer: Tokenizer,
    pub(crate) sessions: Sessions,
}

impl Runtime {
    /// Load a model from a directory (e.g. `models/gliner2-large-v1-onnx`).
    /// Defaults to fp32 on the CPU execution provider.
    pub fn load(model_dir: impl AsRef<Path>) -> Result<Self> {
        Self::load_with(model_dir.as_ref(), Precision::Fp32)
    }

    /// Load with explicit precision selection.
    pub fn load_with(model_dir: &Path, precision: Precision) -> Result<Self> {
        let config = GLiNER2Config::load(model_dir)?;
        let tokenizer_path = model_dir.join("tokenizer.json");
        let tokenizer = Tokenizer::from_file(&tokenizer_path)
            .map_err(|e| Error::Tokenizer(format!("{}: {}", tokenizer_path.display(), e)))?;
        let sessions = Sessions::load(model_dir, &config, precision)?;
        Ok(Self {
            config,
            tokenizer,
            sessions,
        })
    }

    pub fn config(&self) -> &GLiNER2Config {
        &self.config
    }
}
