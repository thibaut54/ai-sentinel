//! Build script: compile the gRPC proto into Rust types.
//!
//! Proto contract source: `ai-sentinel/pii-reporting-api/target/classes/pii_detection.proto`.
//! Keep in sync if the Java side changes it.

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Vendor a protoc binary so the build doesn't depend on a system install
    // (CI / fresh dev machines don't always have protoc).
    let protoc = protoc_bin_vendored::protoc_bin_path()?;
    // Safe because main() runs in a single thread at build time.
    unsafe { std::env::set_var("PROTOC", protoc); }

    tonic_build::configure()
        .build_server(true)
        .build_client(false)
        .compile_protos(&["proto/pii_detection.proto"], &["proto"])?;
    println!("cargo:rerun-if-changed=proto/pii_detection.proto");
    Ok(())
}
