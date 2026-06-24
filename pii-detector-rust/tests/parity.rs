//! Parity test: compare this Rust runtime against the Python reference's
//! ground-truth fixtures from `gliner2-onnx/tests/gliner2.fixtures.json`.
//!
//! Marked `#[ignore]` because it requires the model on disk (~1.85 GB) and
//! actual ONNX inference. Run with:
//!
//!     cargo test --test parity -- --ignored --nocapture
//!
//! Knobs (env vars):
//!     PARITY_LIMIT=20      number of fixtures to check per section (default 5)
//!     PARITY_TOL=0.005     absolute score tolerance vs reference (default 5e-3)
//!     PARITY_MODEL_DIR     override model dir (default: models/gliner2-large-v1-onnx)
//!     PARITY_FIXTURES      override fixtures file (default: gliner2-onnx/tests/gliner2.fixtures.json)

use std::collections::HashMap;
use std::path::PathBuf;

use serde::Deserialize;

use ai_sentinel_pii_detector::gliner2::{ClassifyOptions, Runtime};

const MODEL_KEY: &str = "gliner2-large-v1";

#[derive(Deserialize)]
struct Fixtures {
    classification: Vec<ClsFixture>,
    ner: Vec<NerFixture>,
}

#[derive(Deserialize)]
struct ClsFixture {
    text: String,
    labels: Vec<String>,
    expected_label: String,
    expected_score: f32,
}

#[derive(Deserialize)]
struct NerFixture {
    text: String,
    labels: Vec<String>,
    threshold: f32,
    expected: Vec<ExpectedEntity>,
}

#[derive(Deserialize, Debug)]
struct ExpectedEntity {
    label: String,
    score: f32,
    start: usize,
    end: usize,
}

fn env_usize(name: &str, default: usize) -> usize {
    std::env::var(name)
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(default)
}

fn env_f32(name: &str, default: f32) -> f32 {
    std::env::var(name)
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(default)
}

fn model_dir() -> PathBuf {
    std::env::var("PARITY_MODEL_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("models/gliner2-large-v1-onnx"))
}

fn fixtures_path() -> PathBuf {
    std::env::var("PARITY_FIXTURES")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("gliner2-onnx/tests/gliner2.fixtures.json"))
}

fn load_fixtures() -> Option<Fixtures> {
    let path = fixtures_path();
    if !path.exists() {
        eprintln!("SKIP: fixtures not found at {}", path.display());
        return None;
    }
    let bytes = std::fs::read(&path).ok()?;
    let mut all: HashMap<String, Fixtures> = serde_json::from_slice(&bytes)
        .map_err(|e| eprintln!("fixture parse error: {e}"))
        .ok()?;
    all.remove(MODEL_KEY)
}

fn load_runtime() -> Option<Runtime> {
    let dir = model_dir();
    if !dir.exists() {
        eprintln!("SKIP: model dir not found at {}", dir.display());
        return None;
    }
    match Runtime::load(&dir) {
        Ok(r) => Some(r),
        Err(e) => {
            eprintln!("FAIL: Runtime::load: {e}");
            None
        }
    }
}

#[test]
#[ignore]
fn classification_parity() {
    let Some(runtime) = load_runtime() else {
        return;
    };
    let Some(fx) = load_fixtures() else {
        return;
    };

    let limit = env_usize("PARITY_LIMIT", 5).min(fx.classification.len());
    let tol = env_f32("PARITY_TOL", 5e-3);

    let mut failures: Vec<String> = Vec::new();

    for (i, f) in fx.classification.iter().take(limit).enumerate() {
        let labels: Vec<&str> = f.labels.iter().map(String::as_str).collect();
        let result = runtime.classify(&f.text, &labels, ClassifyOptions::default());
        let res = match result {
            Ok(r) => r,
            Err(e) => {
                failures.push(format!("[cls #{i}] runtime error: {e}"));
                continue;
            }
        };
        let Some(top) = res.first() else {
            failures.push(format!("[cls #{i}] empty result"));
            continue;
        };
        let delta = (top.score - f.expected_score).abs();
        let label_match = top.label == f.expected_label;
        let score_match = delta < tol;
        let marker = if label_match && score_match { "ok" } else { "FAIL" };
        println!(
            "[cls #{i}] {marker} | label={:?} (expected {:?}) | score={:.6} (expected {:.6}, delta={:.6})",
            top.label, f.expected_label, top.score, f.expected_score, delta
        );
        if !label_match {
            failures.push(format!(
                "[cls #{i}] label mismatch: got {:?}, expected {:?} | text={:?}",
                top.label, f.expected_label, f.text
            ));
        } else if !score_match {
            failures.push(format!(
                "[cls #{i}] score mismatch: got {:.6}, expected {:.6}, delta={:.6} (tol={tol}) | text={:?}",
                top.score, f.expected_score, delta, f.text
            ));
        }
    }

    if !failures.is_empty() {
        panic!("{} classification parity failure(s):\n{}", failures.len(), failures.join("\n"));
    }
}

#[test]
#[ignore]
fn ner_parity() {
    let Some(runtime) = load_runtime() else {
        return;
    };
    let Some(fx) = load_fixtures() else {
        return;
    };

    let limit = env_usize("PARITY_LIMIT", 5).min(fx.ner.len());
    let tol = env_f32("PARITY_TOL", 5e-3);

    let mut failures: Vec<String> = Vec::new();

    for (i, f) in fx.ner.iter().take(limit).enumerate() {
        let labels: Vec<&str> = f.labels.iter().map(String::as_str).collect();
        let got = match runtime.extract_entities(&f.text, &labels, f.threshold) {
            Ok(v) => v,
            Err(e) => {
                failures.push(format!("[ner #{i}] runtime error: {e}"));
                continue;
            }
        };

        println!(
            "[ner #{i}] text={:?} | expected={} got={}",
            f.text,
            f.expected.len(),
            got.len()
        );

        for exp in &f.expected {
            let m = got.iter().find(|g| {
                g.label == exp.label && g.start == exp.start && g.end == exp.end
            });
            match m {
                Some(g) => {
                    let delta = (g.score - exp.score).abs();
                    let marker = if delta < tol { "ok" } else { "score-off" };
                    println!(
                        "  - {marker} {:?} [{}..{}] {} score={:.6} (expected {:.6}, delta={:.6})",
                        g.text, g.start, g.end, g.label, g.score, exp.score, delta
                    );
                    if delta >= tol {
                        failures.push(format!(
                            "[ner #{i}] score mismatch for {:?}/{} [{}..{}]: got {:.6}, expected {:.6}, delta={:.6}",
                            g.text, g.label, g.start, g.end, g.score, exp.score, delta
                        ));
                    }
                }
                None => {
                    failures.push(format!(
                        "[ner #{i}] missing expected entity: label={:?} [{}..{}] (got {} entities)",
                        exp.label, exp.start, exp.end, got.len()
                    ));
                    println!(
                        "  - MISS {:?} [{}..{}] (expected score {:.6})",
                        exp.label, exp.start, exp.end, exp.score
                    );
                }
            }
        }
    }

    if !failures.is_empty() {
        panic!("{} NER parity failure(s):\n{}", failures.len(), failures.join("\n"));
    }
}
