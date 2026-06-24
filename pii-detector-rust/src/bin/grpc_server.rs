//! Boot the PII detection gRPC server on the contract from
//! `ai-sentinel/pii-reporting-api/.../pii_detection.proto`.
//!
//! Usage:
//!   grpc_server [--taxonomy config/nlpd-ipi.toml] [--model models/gliner2-large-v1-onnx]
//!               [--addr 0.0.0.0:50051]
//!
//! Loads taxonomy + model once at startup. The IT test
//! `ai-sentinel/.../CorpusDataSqlComparisonIT` connects to this on port 50051.

use std::net::SocketAddr;
use std::path::PathBuf;

use clap::Parser;
use tonic::transport::Server;

use ai_sentinel_pii_detector::gliner2::Runtime;
use ai_sentinel_pii_detector::grpc_server::PiiServer;
use ai_sentinel_pii_detector::pipeline::Pipeline;
use ai_sentinel_pii_detector::taxonomy::Taxonomy;

#[derive(Parser, Debug)]
#[command(name = "grpc_server")]
struct Args {
    /// Path to the baseline IPI taxonomy TOML.
    #[arg(long, default_value = "config/nlpd-ipi.toml")]
    taxonomy: PathBuf,

    /// Optional tenant override TOML applied on top of the baseline.
    #[arg(long)]
    override_path: Option<PathBuf>,

    /// Directory containing the GLiNER2 ONNX model assets.
    #[arg(long, default_value = "models/gliner2-large-v1-onnx")]
    model: PathBuf,

    /// Listen address.
    #[arg(long, default_value = "0.0.0.0:50051")]
    addr: SocketAddr,
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    eprintln!("Loading taxonomy: {}", args.taxonomy.display());
    let mut tax = Taxonomy::load(&args.taxonomy)?;
    if let Some(ov) = &args.override_path {
        eprintln!("Applying override: {}", ov.display());
        tax.apply_override(ov)?;
    }
    eprintln!(
        "Pipeline scope: {} regex / {} NER / {} classification IPIs",
        tax.by_layer(ai_sentinel_pii_detector::taxonomy::Layer::Regex).count(),
        tax.by_layer(ai_sentinel_pii_detector::taxonomy::Layer::Ner).count(),
        tax.by_layer(ai_sentinel_pii_detector::taxonomy::Layer::Classification).count(),
    );

    let pipeline = Pipeline::from_taxonomy(&tax)?;

    eprintln!("Loading model: {}", args.model.display());
    let t = std::time::Instant::now();
    let runtime = Runtime::load(&args.model)?;
    eprintln!("Model loaded in {:?}", t.elapsed());

    let server = PiiServer::new(tax, pipeline, runtime);

    // Log line matches the IT's wait probe (Wait.forLogMessage(".*Server started on port.*"))
    // — keep the wording stable so Testcontainers detects readiness reliably.
    println!("Server started on port {}", args.addr.port());
    eprintln!("Listening on {}", args.addr);
    Server::builder()
        .add_service(server.into_service())
        .serve_with_shutdown(args.addr, async {
            let _ = tokio::signal::ctrl_c().await;
            eprintln!("\nShutdown signal received");
        })
        .await?;

    Ok(())
}
