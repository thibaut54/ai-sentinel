//! End-to-end PII detection pipeline.
//!
//! Takes plain text in, returns the final list of [`Finding`]s out. Owns
//! the three detection layers (regex, NER, classification) and the
//! [`Chunker`]; handles chunk re-offsetting, cross-chunk dedup, and
//! span-overlap resolution.
//!
//! The model [`Runtime`] is passed in at `detect` time so a single model
//! can be shared across multiple pipelines (e.g. one per tenant config).

pub mod chunker;

pub use chunker::{Chunk, Chunker, ChunkerConfig};

use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::classification_layer::ClassificationLayer;
use crate::error::Result;
use crate::findings::Finding;
use crate::gliner2::Runtime;
use crate::ner_layer::NerLayer;
use crate::regex_layer::RegexLayer;
use crate::resolve::resolve_overlaps;
use crate::taxonomy::Taxonomy;

/// Per-stage timings, useful for profiling and the benchmark replay tool.
#[derive(Debug, Clone, Copy, Default)]
pub struct PipelineTiming {
    pub chunk: Duration,
    pub regex: Duration,
    pub ner: Duration,
    pub classification: Duration,
    pub dedup: Duration,
    pub resolve: Duration,
    pub total: Duration,
}

#[derive(Debug, Clone)]
pub struct PipelineStats {
    pub n_chunks: usize,
    pub raw_findings: usize,
    pub after_dedup: usize,
    pub after_resolve: usize,
    pub timing: PipelineTiming,
}

/// Per-IPI context-filter rules extracted from the taxonomy at build time.
#[derive(Debug, Clone)]
struct ContextRule {
    /// Other IPI IDs whose findings satisfy the proximity requirement.
    requires_nearby_ipi: Vec<String>,
    /// Character window (each side of the span) within which a qualifying
    /// finding must appear.
    nearby_window_chars: usize,
}

#[derive(Debug)]
pub struct Pipeline {
    regex: RegexLayer,
    ner: NerLayer,
    classification: ClassificationLayer,
    chunker: Chunker,
    /// IPI ID → context rule. Only present for IPIs that opt in.
    context_rules: HashMap<String, ContextRule>,
}

impl Pipeline {
    /// Build a pipeline from the active IPIs in `tax`. Uses the default
    /// chunker config (1200 char chunks, 150 char overlap).
    pub fn from_taxonomy(tax: &Taxonomy) -> Result<Self> {
        let mut context_rules: HashMap<String, ContextRule> = HashMap::new();
        for ipi in tax.active() {
            if ipi.requires_nearby_ipi.is_empty() {
                continue;
            }
            context_rules.insert(
                ipi.id.clone(),
                ContextRule {
                    requires_nearby_ipi: ipi.requires_nearby_ipi.clone(),
                    nearby_window_chars: ipi.nearby_window_chars,
                },
            );
        }

        Ok(Self {
            regex: RegexLayer::from_taxonomy(tax)?,
            ner: NerLayer::from_taxonomy(tax)?,
            classification: ClassificationLayer::from_taxonomy(tax)?,
            chunker: Chunker::new(ChunkerConfig::default()),
            context_rules,
        })
    }

    /// Override the chunker config (e.g. for different model context windows).
    pub fn with_chunker(mut self, cfg: ChunkerConfig) -> Self {
        self.chunker = Chunker::new(cfg);
        self
    }

    pub fn regex_layer(&self) -> &RegexLayer {
        &self.regex
    }
    pub fn ner_layer(&self) -> &NerLayer {
        &self.ner
    }
    pub fn classification_layer(&self) -> &ClassificationLayer {
        &self.classification
    }

    /// Run the full pipeline on `text`. Discards timings.
    pub fn detect(&self, runtime: &Runtime, text: &str) -> Result<Vec<Finding>> {
        self.detect_with_stats(runtime, text).map(|(f, _)| f)
    }

    /// Run the full pipeline, also returning per-stage timings.
    pub fn detect_with_stats(
        &self,
        runtime: &Runtime,
        text: &str,
    ) -> Result<(Vec<Finding>, PipelineStats)> {
        let t_total = Instant::now();
        let mut timing = PipelineTiming::default();

        let t = Instant::now();
        let chunks = self.chunker.chunks(text);
        timing.chunk = t.elapsed();

        let mut raw: Vec<Finding> = Vec::new();

        // For each chunk: run all three detection layers, re-offset spans to source.
        for chunk in &chunks {
            // Regex
            let t = Instant::now();
            let mut regex_findings = self.regex.detect(chunk.text);
            for f in regex_findings.iter_mut() {
                f.start += chunk.start;
                f.end += chunk.start;
            }
            timing.regex += t.elapsed();
            raw.extend(regex_findings);

            // NER
            if !self.ner.is_empty() {
                let t = Instant::now();
                let mut ner_findings = self.ner.detect(runtime, chunk.text)?;
                for f in ner_findings.iter_mut() {
                    f.start += chunk.start;
                    f.end += chunk.start;
                }
                timing.ner += t.elapsed();
                raw.extend(ner_findings);
            }

            // Classification (whole-chunk findings — spans become chunk source range)
            if !self.classification.is_empty() {
                let t = Instant::now();
                let mut cls_findings = self.classification.detect(runtime, chunk.text)?;
                for f in cls_findings.iter_mut() {
                    f.start += chunk.start;
                    f.end += chunk.start;
                }
                timing.classification += t.elapsed();
                raw.extend(cls_findings);
            }
        }

        let raw_count = raw.len();

        // Cross-chunk dedup: same (ipi_id, start, end) → keep highest score.
        let t = Instant::now();
        let deduped = dedup_exact_spans(raw);
        timing.dedup = t.elapsed();
        let deduped_count = deduped.len();

        // Span-overlap resolution (classification findings pass through).
        let t = Instant::now();
        let resolved = resolve_overlaps(deduped);
        timing.resolve = t.elapsed();

        // Context filter: drop IPIs whose taxonomy demands a nearby co-finding
        // and don't have one. Runs after overlap resolution so we filter on
        // the set actually emitted.
        let resolved = apply_context_filter(resolved, &self.context_rules);
        let resolved_count = resolved.len();

        timing.total = t_total.elapsed();

        Ok((
            resolved,
            PipelineStats {
                n_chunks: chunks.len(),
                raw_findings: raw_count,
                after_dedup: deduped_count,
                after_resolve: resolved_count,
                timing,
            },
        ))
    }
}

/// Drop findings whose IPI demands a nearby co-finding from another IPI but
/// has none. The "nearby" check is: any qualifying finding's span must lie
/// within `nearby_window_chars` of the current finding's span (measured as the
/// distance between span edges).
///
/// Findings whose IPI has no context rule pass through unchanged. Used to
/// encode nLPD-style semantics where standalone entities aren't PII unless
/// they identify a specific person — e.g. ORG only counts when adjacent to a
/// PERSON_NAME or EMAIL_ADDRESS.
fn apply_context_filter(
    findings: Vec<Finding>,
    rules: &HashMap<String, ContextRule>,
) -> Vec<Finding> {
    if rules.is_empty() {
        return findings;
    }

    // Group span positions by IPI for fast lookup. Owned keys so we can
    // consume `findings` afterwards.
    let mut by_ipi: HashMap<String, Vec<(usize, usize)>> = HashMap::new();
    for f in &findings {
        by_ipi
            .entry(f.ipi_id.clone())
            .or_default()
            .push((f.start, f.end));
    }

    findings
        .into_iter()
        .filter(|f| match rules.get(&f.ipi_id) {
            None => true,
            Some(rule) => rule.requires_nearby_ipi.iter().any(|target| {
                by_ipi
                    .get(target)
                    .map(|spans| {
                        spans.iter().any(|&(s, e)| {
                            span_distance(f.start, f.end, s, e) <= rule.nearby_window_chars
                        })
                    })
                    .unwrap_or(false)
            }),
        })
        .collect()
}

/// Edge-to-edge distance between two spans. 0 if they overlap or touch.
fn span_distance(a_s: usize, a_e: usize, b_s: usize, b_e: usize) -> usize {
    if a_e <= b_s {
        b_s - a_e
    } else if b_e <= a_s {
        a_s - b_e
    } else {
        0
    }
}

/// Drop exact-span duplicates of the same IPI (caused by chunk overlap),
/// keeping the highest-scored copy.
fn dedup_exact_spans(findings: Vec<Finding>) -> Vec<Finding> {
    let mut map: HashMap<(String, usize, usize), Finding> = HashMap::new();
    for f in findings {
        let key = (f.ipi_id.clone(), f.start, f.end);
        map.entry(key)
            .and_modify(|existing| {
                if f.score > existing.score {
                    *existing = f.clone();
                }
            })
            .or_insert(f);
    }
    let mut v: Vec<Finding> = map.into_values().collect();
    v.sort_by_key(|f| f.start);
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::findings::SourceLayer;
    use crate::taxonomy::Severity;

    fn f(id: &str, start: usize, end: usize, score: f32) -> Finding {
        Finding {
            ipi_id: id.to_string(),
            matched_text: String::new(),
            start,
            end,
            score,
            priority: 50,
            severity: Severity::Low,
            source_layer: SourceLayer::Regex,
        }
    }

    #[test]
    fn dedup_exact_spans_keeps_highest_score() {
        let findings = vec![
            f("AVS", 100, 116, 0.95),
            f("AVS", 100, 116, 0.99),
            f("AVS", 100, 116, 0.91),
            f("AVS", 200, 216, 0.95),
        ];
        let deduped = dedup_exact_spans(findings);
        assert_eq!(deduped.len(), 2);
        let avs_first = deduped.iter().find(|f| f.start == 100).unwrap();
        assert!((avs_first.score - 0.99).abs() < 1e-6);
    }

    #[test]
    fn pipeline_builds_from_baseline() {
        let tax = crate::taxonomy::Taxonomy::load("config/nlpd-ipi.toml").unwrap();
        let pipe = Pipeline::from_taxonomy(&tax).unwrap();
        assert!(pipe.regex_layer().ipi_count() >= 7);
        assert!(pipe.ner_layer().ipi_count() >= 3);
        // Classification layer is currently empty (NRP removed). Add the
        // `>= 1` assertion back if/when classification IPIs return.
    }
}
