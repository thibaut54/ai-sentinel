use std::path::{Path, PathBuf};
use std::sync::Mutex;

use ort::session::Session;

use crate::error::{Error, Result};
use crate::gliner2::config::{GLiNER2Config, Precision};

/// The four ONNX sessions that make up a GLiNER2 model.
///
/// Each session is wrapped in a `Mutex` because `ort::session::Session::run`
/// takes `&mut self`. The Mutex lets `Runtime` expose an `&self`-only public
/// API; the underlying ONNX Runtime serialises inference on a session anyway,
/// so the lock adds no real contention beyond what the engine already enforces.
pub struct Sessions {
    pub encoder: Mutex<Session>,
    pub span_rep: Mutex<Session>,
    pub classifier: Mutex<Session>,
    pub count_embed: Mutex<Session>,
}

impl Sessions {
    pub fn load(model_dir: &Path, config: &GLiNER2Config, precision: Precision) -> Result<Self> {
        let files = config.files_for(precision)?;
        Ok(Self {
            encoder: Mutex::new(load_one(model_dir, &files.encoder)?),
            span_rep: Mutex::new(load_one(model_dir, &files.span_rep)?),
            classifier: Mutex::new(load_one(model_dir, &files.classifier)?),
            count_embed: Mutex::new(load_one(model_dir, &files.count_embed)?),
        })
    }
}

fn load_one(model_dir: &Path, rel_path: &str) -> Result<Session> {
    let full: PathBuf = model_dir.join(rel_path);
    if !full.exists() {
        return Err(Error::ModelNotFound(full.display().to_string()));
    }
    Ok(Session::builder()?.commit_from_file(&full)?)
}
