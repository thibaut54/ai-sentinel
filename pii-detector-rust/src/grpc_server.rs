//! gRPC adapter for the PII detection pipeline.
//!
//! Implements the `pii_detection.PIIDetectionService` contract from
//! `ai-sentinel/pii-reporting-api/.../pii_detection.proto`. The Java side's
//! `CorpusDataSqlComparisonIT` consumes this contract; behaviour must match
//! the equivalent Python `pii-detector-service` it replaces.

use std::collections::HashMap;
use std::sync::Arc;

use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};

use crate::findings::Finding;
use crate::gliner2::Runtime;
use crate::pipeline::Pipeline;
use crate::taxonomy::Taxonomy;

pub mod proto {
    tonic::include_proto!("pii_detection");
}

use proto::pii_detection_service_server::{PiiDetectionService, PiiDetectionServiceServer};
use proto::{PiiDetectionRequest, PiiDetectionResponse, PiiDetectionUpdate, PiiEntity};

/// Server state: taxonomy → pipeline + model runtime, all shared & immutable
/// across requests. Runtime is `Sync` because its ONNX sessions are mutex-wrapped.
pub struct PiiServer {
    pipeline: Arc<Pipeline>,
    runtime: Arc<Runtime>,
    taxonomy: Arc<Taxonomy>,
}

impl PiiServer {
    pub fn new(taxonomy: Taxonomy, pipeline: Pipeline, runtime: Runtime) -> Self {
        Self {
            taxonomy: Arc::new(taxonomy),
            pipeline: Arc::new(pipeline),
            runtime: Arc::new(runtime),
        }
    }

    pub fn into_service(self) -> PiiDetectionServiceServer<Self> {
        PiiDetectionServiceServer::new(self)
    }
}

/// Build a sorted table mapping byte offsets in a UTF-8 string to UTF-16 code
/// unit offsets — the indexing unit Java's `String` exposes. Without this
/// mapping, finding spans returned over gRPC point at the wrong characters in
/// any text containing multi-byte UTF-8 (every French accent is 2 bytes / 1
/// UTF-16 unit; emoji is 4 bytes / 2 UTF-16 units).
fn build_byte_to_utf16_table(text: &str) -> Vec<(usize, u32)> {
    let mut table: Vec<(usize, u32)> = Vec::with_capacity(text.len() + 1);
    let mut utf16_cursor: u32 = 0;
    table.push((0, 0));
    for (byte_idx, c) in text.char_indices() {
        utf16_cursor += c.len_utf16() as u32;
        table.push((byte_idx + c.len_utf8(), utf16_cursor));
    }
    table
}

fn byte_to_utf16(table: &[(usize, u32)], byte_off: usize) -> i32 {
    match table.binary_search_by_key(&byte_off, |&(b, _)| b) {
        Ok(i) => table[i].1 as i32,
        Err(i) => table[i.saturating_sub(1)].1 as i32,
    }
}

fn finding_to_entity(
    f: &Finding,
    taxonomy: &Taxonomy,
    byte_to_utf16_table: &[(usize, u32)],
) -> PiiEntity {
    let type_label = taxonomy
        .by_id(&f.ipi_id)
        .map(|i| i.display_fr.clone())
        .unwrap_or_else(|| f.ipi_id.clone());
    PiiEntity {
        text: f.matched_text.clone(),
        r#type: f.ipi_id.clone(),
        type_label,
        // UTF-16 offsets match Java's String.substring(start, end) semantics.
        start: byte_to_utf16(byte_to_utf16_table, f.start),
        end: byte_to_utf16(byte_to_utf16_table, f.end),
        score: f.score,
    }
}

/// Replace every detected span with `[<TYPE>]`. Sorted by start, with
/// later-overlapping spans dropped (overlap resolution should have happened
/// in the pipeline already).
fn build_masked_content(text: &str, findings: &[Finding]) -> String {
    if findings.is_empty() {
        return text.to_string();
    }
    let mut sorted: Vec<&Finding> = findings.iter().collect();
    sorted.sort_by_key(|f| f.start);

    let mut out = String::with_capacity(text.len());
    let mut cursor = 0;
    for f in sorted {
        if f.start < cursor || f.start > text.len() || f.end > text.len() {
            continue;
        }
        out.push_str(&text[cursor..f.start]);
        out.push('[');
        out.push_str(&f.ipi_id);
        out.push(']');
        cursor = f.end;
    }
    if cursor < text.len() {
        out.push_str(&text[cursor..]);
    }
    out
}

fn build_summary(findings: &[Finding]) -> HashMap<String, i32> {
    let mut m: HashMap<String, i32> = HashMap::new();
    for f in findings {
        *m.entry(f.ipi_id.clone()).or_insert(0) += 1;
    }
    m
}

#[tonic::async_trait]
impl PiiDetectionService for PiiServer {
    async fn detect_pii(
        &self,
        request: Request<PiiDetectionRequest>,
    ) -> Result<Response<PiiDetectionResponse>, Status> {
        let req = request.into_inner();
        let content = req.content;
        let content_len = content.len();
        let content_for_mask = content.clone();

        let pipeline = self.pipeline.clone();
        let runtime = self.runtime.clone();
        let taxonomy = self.taxonomy.clone();

        let started = std::time::Instant::now();
        let findings = match tokio::task::spawn_blocking(move || pipeline.detect(&runtime, &content))
            .await
        {
            Ok(Ok(f)) => f,
            Ok(Err(e)) => {
                eprintln!(
                    "DetectPII FAILED: {content_len} chars, pipeline error: {e}"
                );
                return Err(Status::internal(format!("pipeline error: {e}")));
            }
            Err(e) => {
                eprintln!("DetectPII FAILED: {content_len} chars, join error: {e}");
                return Err(Status::internal(format!("join error: {e}")));
            }
        };
        let elapsed = started.elapsed();

        let byte_to_utf16_table = build_byte_to_utf16_table(&content_for_mask);
        let entities: Vec<PiiEntity> = findings
            .iter()
            .map(|f| finding_to_entity(f, &taxonomy, &byte_to_utf16_table))
            .collect();
        let summary = build_summary(&findings);
        let masked_content = build_masked_content(&content_for_mask, &findings);

        eprintln!(
            "DetectPII: {content_len} bytes, {n} findings, {ms:.1}ms",
            n = entities.len(),
            ms = elapsed.as_secs_f64() * 1000.0,
        );

        Ok(Response::new(PiiDetectionResponse {
            entities,
            summary,
            masked_content,
        }))
    }

    type StreamDetectPIIStream = ReceiverStream<Result<PiiDetectionUpdate, Status>>;

    /// Minimal compliant streaming impl: emit one progress update with all
    /// entities, then a `final = true` message with summary + masked content.
    /// The pipeline doesn't expose per-chunk findings publicly (chunking is
    /// internal), so true per-chunk streaming would require an API change.
    async fn stream_detect_pii(
        &self,
        request: Request<PiiDetectionRequest>,
    ) -> Result<Response<Self::StreamDetectPIIStream>, Status> {
        let req = request.into_inner();
        let content = req.content;
        let content_for_mask = content.clone();

        let pipeline = self.pipeline.clone();
        let runtime = self.runtime.clone();
        let taxonomy = self.taxonomy.clone();

        let (tx, rx) = tokio::sync::mpsc::channel(4);

        tokio::task::spawn_blocking(move || {
            let content_len = content.len();
            let started = std::time::Instant::now();
            let findings = match pipeline.detect(&runtime, &content) {
                Ok(f) => f,
                Err(e) => {
                    eprintln!("StreamDetectPII FAILED: {content_len} chars, error: {e}");
                    let _ = tx.blocking_send(Err(Status::internal(format!("pipeline: {e}"))));
                    return;
                }
            };
            eprintln!(
                "StreamDetectPII: {content_len} bytes, {n} findings, {ms:.1}ms",
                n = findings.len(),
                ms = started.elapsed().as_secs_f64() * 1000.0,
            );

            let byte_to_utf16_table = build_byte_to_utf16_table(&content_for_mask);
            let entities: Vec<PiiEntity> = findings
                .iter()
                .map(|f| finding_to_entity(f, &taxonomy, &byte_to_utf16_table))
                .collect();

            let progress = PiiDetectionUpdate {
                chunk_index: 0,
                total_chunks: 1,
                progress_percent: 50,
                entities,
                masked_content: String::new(),
                summary: HashMap::new(),
                r#final: false,
            };
            if tx.blocking_send(Ok(progress)).is_err() {
                return;
            }

            let masked = build_masked_content(&content_for_mask, &findings);
            let summary = build_summary(&findings);
            let final_msg = PiiDetectionUpdate {
                chunk_index: 0,
                total_chunks: 1,
                progress_percent: 100,
                entities: Vec::new(),
                masked_content: masked,
                summary,
                r#final: true,
            };
            let _ = tx.blocking_send(Ok(final_msg));
        });

        Ok(Response::new(ReceiverStream::new(rx)))
    }
}
