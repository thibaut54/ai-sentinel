use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("model file not found: {0}")]
    ModelNotFound(String),

    #[error("invalid configuration: {0}")]
    Config(String),

    #[error("tokenizer error: {0}")]
    Tokenizer(String),

    #[error("inference error: {0}")]
    Inference(String),

    #[error("empty input: {0}")]
    EmptyInput(&'static str),

    #[error(transparent)]
    Ort(#[from] ort::Error),

    #[error(transparent)]
    Io(#[from] std::io::Error),

    #[error(transparent)]
    Json(#[from] serde_json::Error),

    #[error("TOML parse error: {0}")]
    Toml(#[from] toml::de::Error),

    #[error("taxonomy error: {0}")]
    Taxonomy(String),
}

pub type Result<T> = std::result::Result<T, Error>;
