# Multi-stage build for the PII detection gRPC server.
#
# Stage 1: build the Rust binary in a rust image with build deps (gcc, protoc).
# Stage 2: minimal Debian runtime with the binary + ONNX model + TOML config.
#
# Drop-in for ai-sentinel-forked/.../CorpusDataSqlComparisonIT:
#   - Listens on :50051
#   - Implements pii_detection.PIIDetectionService (DetectPII + StreamDetectPII)
#   - Replaces the Python pii-detector-service container.

# ============================================================================
# Build stage
# ============================================================================
# Ubuntu 24.04 ships glibc 2.39, required by the prebuilt onnxruntime that
# the `ort` crate links against (uses `__isoc23_strtoull` from glibc 2.38+).
# Debian bookworm ships glibc 2.36 and fails to link.
FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        pkg-config \
        libssl-dev \
        ca-certificates \
        build-essential \
        cmake \
        clang \
    && rm -rf /var/lib/apt/lists/*

# Install Rust toolchain (edition 2024 needs >= 1.85).
ENV RUSTUP_HOME=/usr/local/rustup CARGO_HOME=/usr/local/cargo PATH=/usr/local/cargo/bin:$PATH
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y \
        --default-toolchain stable --profile minimal && \
    rustc --version

WORKDIR /build

# Cache dependencies first
COPY Cargo.toml Cargo.lock build.rs ./
COPY proto ./proto
# Empty src to let cargo resolve deps without compiling our code yet
RUN mkdir -p src/bin && \
    echo "fn main() {}" > src/bin/grpc_server.rs && \
    echo "pub fn placeholder() {}" > src/lib.rs && \
    cargo build --release --bin grpc_server || true && \
    rm -rf src

# Now copy the real source and build
COPY src ./src
RUN touch build.rs && cargo build --release --bin grpc_server

# ============================================================================
# Runtime stage
# ============================================================================
# Match builder glibc — Ubuntu 24.04 = glibc 2.39 (the onnxruntime symbols
# the rust binary references won't resolve on older glibc).
FROM ubuntu:24.04 AS runtime

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Binary
COPY --from=builder /build/target/release/grpc_server /app/grpc_server

# Taxonomy is small (~30 IPIs, a few KB) — bundle it.
COPY config /app/config

# Model is ~5.5 GB across encoder/span_rep/classifier/count_embed (+ external
# .data files). DO NOT bundle into the image. Mount at runtime, e.g.:
#   docker run -v /host/path/to/gliner2-large-v1-onnx:/app/models/gliner2-large-v1-onnx ...
# Testcontainers equivalent:
#   .withFileSystemBind(hostModelDir, "/app/models/gliner2-large-v1-onnx", READ_ONLY)

EXPOSE 50051

ENTRYPOINT ["/app/grpc_server"]
CMD ["--taxonomy", "/app/config/nlpd-ipi.toml", \
     "--model",    "/app/models/gliner2-large-v1-onnx", \
     "--addr",     "0.0.0.0:50051"]
