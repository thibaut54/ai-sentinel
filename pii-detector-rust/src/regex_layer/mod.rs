//! Regex-driven detector: compiles each `layer = "regex"` IPI from the
//! taxonomy into a runnable matcher, applies named validators
//! (`luhn`, `iban_checksum`, `swiss_avs`, `entropy:N`), and returns
//! [`Finding`]s carrying spans into the original text.
//!
//! Designed to be cheap to share — build once at startup, call `detect`
//! many times. No interior mutability, `Send + Sync` friendly.

mod validators;

pub use validators::Validator;

use regex::Regex;

use crate::error::{Error, Result};
use crate::findings::{Finding, SourceLayer};
use crate::taxonomy::{Detection, Layer, Severity, Taxonomy};

#[derive(Debug)]
struct CompiledIpi {
    ipi_id: String,
    threshold: f32,
    priority: i32,
    severity: Severity,
    patterns: Vec<Regex>,
    validators: Vec<Validator>,
}

/// Compiled detector for every active regex IPI in a taxonomy.
#[derive(Debug)]
pub struct RegexLayer {
    compiled: Vec<CompiledIpi>,
}

impl RegexLayer {
    /// Compile every active IPI with `layer = "regex"` from `taxonomy`.
    /// Fails fast on bad regex syntax or unknown validator names.
    pub fn from_taxonomy(taxonomy: &Taxonomy) -> Result<Self> {
        let mut compiled = Vec::new();
        for ipi in taxonomy.by_layer(Layer::Regex) {
            let (patterns_src, validators_src, threshold) = match &ipi.detection {
                Detection::Regex {
                    patterns,
                    validators,
                    threshold,
                } => (patterns, validators, *threshold),
                _ => unreachable!("by_layer(Regex) only yields Detection::Regex"),
            };

            let mut patterns: Vec<Regex> = Vec::with_capacity(patterns_src.len());
            for pat in patterns_src {
                let re = Regex::new(pat).map_err(|e| {
                    Error::Taxonomy(format!(
                        "ipi {:?}: invalid regex {pat:?}: {e}",
                        ipi.id
                    ))
                })?;
                patterns.push(re);
            }

            let mut validators: Vec<Validator> = Vec::with_capacity(validators_src.len());
            for name in validators_src {
                validators.push(Validator::parse(name).map_err(|e| {
                    Error::Taxonomy(format!("ipi {:?}: {e}", ipi.id))
                })?);
            }

            compiled.push(CompiledIpi {
                ipi_id: ipi.id.clone(),
                threshold,
                priority: ipi.priority,
                severity: ipi.severity,
                patterns,
                validators,
            });
        }
        Ok(Self { compiled })
    }

    /// Number of IPIs this layer detects.
    pub fn ipi_count(&self) -> usize {
        self.compiled.len()
    }

    /// Run all patterns over `text` and return raw findings.
    ///
    /// Findings are not deduplicated across IPIs; the pipeline layer
    /// resolves span conflicts using each IPI's `priority`.
    pub fn detect(&self, text: &str) -> Vec<Finding> {
        let mut out: Vec<Finding> = Vec::new();
        for ci in &self.compiled {
            for re in &ci.patterns {
                for caps in re.captures_iter(text) {
                    // If pattern has a capture group, use group 1 as the
                    // actual entity span (the rest is context). Otherwise
                    // the whole match is the entity.
                    let m = caps.get(1).unwrap_or_else(|| caps.get(0).unwrap());
                    let candidate = m.as_str();
                    if ci.validators.iter().all(|v| v.validate(candidate)) {
                        out.push(Finding {
                            ipi_id: ci.ipi_id.clone(),
                            matched_text: candidate.to_string(),
                            start: m.start(),
                            end: m.end(),
                            score: ci.threshold,
                            priority: ci.priority,
                            severity: ci.severity,
                            source_layer: SourceLayer::Regex,
                        });
                    }
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::taxonomy::Taxonomy;
    use std::io::Write;

    fn write_tmp(name: &str, content: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join("ai-sentinel-pii-regex-layer-tests");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        std::fs::File::create(&path)
            .unwrap()
            .write_all(content.as_bytes())
            .unwrap();
        path
    }

    fn minimal_tax_toml() -> &'static str {
        r#"
[meta]
name = "minimal"
version = "1.0.0"

[[category]]
id = "x"
display_fr = "x"
display_en = "x"

[[ipi]]
id = "EMAIL"
display_fr = "email"
display_en = "email"
category = "x"
severity = "low"
layer = "regex"
threshold = 0.9
patterns = ['[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}']

[[ipi]]
id = "TEST_AVS"
display_fr = "avs"
display_en = "avs"
category = "x"
severity = "high"
layer = "regex"
threshold = 0.95
patterns = ['\b756[.\s]?\d{4}[.\s]?\d{4}[.\s]?\d{2}\b']
validators = ["swiss_avs"]

[[ipi]]
id = "TEST_TOKEN"
display_fr = "tok"
display_en = "tok"
category = "x"
severity = "high"
layer = "regex"
threshold = 0.9
patterns = ['(?i)\btoken\s*[:=]\s*([A-Za-z0-9_\-]{20,})']
validators = ["entropy:3.5"]
"#
    }

    #[test]
    fn compiles_active_regex_ipis() {
        let path = write_tmp("min.toml", minimal_tax_toml());
        let tax = Taxonomy::load(&path).unwrap();
        let layer = RegexLayer::from_taxonomy(&tax).unwrap();
        assert_eq!(layer.ipi_count(), 3);
    }

    #[test]
    fn email_match_no_validator() {
        let path = write_tmp("min2.toml", minimal_tax_toml());
        let tax = Taxonomy::load(&path).unwrap();
        let layer = RegexLayer::from_taxonomy(&tax).unwrap();
        let findings = layer.detect("contact alice.dupont@example.ch today");
        let f: Vec<&Finding> = findings.iter().filter(|f| f.ipi_id == "EMAIL").collect();
        assert_eq!(f.len(), 1);
        assert_eq!(f[0].matched_text, "alice.dupont@example.ch");
    }

    #[test]
    fn avs_validator_accepts_valid_only() {
        let path = write_tmp("min3.toml", minimal_tax_toml());
        let tax = Taxonomy::load(&path).unwrap();
        let layer = RegexLayer::from_taxonomy(&tax).unwrap();
        // 756.1234.5678.97 is a valid NAVS13.
        // 756.1234.5678.98 has a wrong check digit — same shape, must NOT pass.
        let findings = layer.detect(
            "good 756.1234.5678.97 and bad 756.1234.5678.98 here",
        );
        let avs: Vec<&Finding> = findings.iter().filter(|f| f.ipi_id == "TEST_AVS").collect();
        assert_eq!(avs.len(), 1, "expected one AVS pass, got {avs:?}");
        assert_eq!(avs[0].matched_text, "756.1234.5678.97");
    }

    #[test]
    fn token_entropy_rejects_repetitive_string() {
        let path = write_tmp("min4.toml", minimal_tax_toml());
        let tax = Taxonomy::load(&path).unwrap();
        let layer = RegexLayer::from_taxonomy(&tax).unwrap();
        // High entropy → keep. Low entropy → drop.
        let findings = layer.detect(
            "token = aZ7xQ9pL3mK2nB8vF4cR6tY1wE5sD0 and token = aaaaaaaaaaaaaaaaaaaa",
        );
        let toks: Vec<&Finding> = findings.iter().filter(|f| f.ipi_id == "TEST_TOKEN").collect();
        assert_eq!(toks.len(), 1, "entropy filter should drop the second match");
        assert!(toks[0].matched_text.starts_with("aZ7"));
    }

    #[test]
    fn unknown_validator_fails_at_build_time() {
        let bad = minimal_tax_toml().replace(r#"["swiss_avs"]"#, r#"["mystery"]"#);
        let path = write_tmp("bad-val.toml", &bad);
        let tax = Taxonomy::load(&path).unwrap();
        let err = RegexLayer::from_taxonomy(&tax).unwrap_err();
        assert!(format!("{err}").contains("unknown validator"));
    }

    #[test]
    fn bad_regex_fails_at_build_time() {
        let bad = minimal_tax_toml().replace(
            r#"patterns = ['\b756[.\s]?\d{4}[.\s]?\d{4}[.\s]?\d{2}\b']"#,
            r#"patterns = ['(unclosed group']"#,
        );
        let path = write_tmp("bad-re.toml", &bad);
        let tax = Taxonomy::load(&path).unwrap();
        let err = RegexLayer::from_taxonomy(&tax).unwrap_err();
        assert!(format!("{err}").contains("invalid regex"));
    }

    #[test]
    fn disabled_ipis_are_skipped() {
        let path = write_tmp("min5.toml", minimal_tax_toml());
        let mut tax = Taxonomy::load(&path).unwrap();
        tax.by_id("EMAIL"); // sanity
        // Apply an inline-built override: disable EMAIL.
        let ov = r#"
[meta]
name = "tenant"

[[disable]]
ids = ["EMAIL"]
"#;
        let ov_path = write_tmp("disable.toml", ov);
        tax.apply_override(&ov_path).unwrap();

        let layer = RegexLayer::from_taxonomy(&tax).unwrap();
        let findings = layer.detect("contact alice@example.ch today");
        assert!(findings.iter().all(|f| f.ipi_id != "EMAIL"));
    }
}
