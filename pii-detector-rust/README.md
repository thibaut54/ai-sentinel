# ai-sentinel-pii-detector

Rust PII detection pipeline. Three-tier cascade:

- **Tier A — Regex** with checksum validators (AVS, IBAN, BIC, credit card, IMEI, VIN, MAC, IP, phone, password, etc.)
- **Tier B — NER** via GLiNER2-large-v1 (DeBERTa-v3 backbone) for semantic IPIs (username, account_id, national_id, medical record, etc.)
- **Tier C — Classification** for whole-section sensitive-topic tagging (currently disabled in the baseline)

Exposes a gRPC service (`pii_detection.PIIDetectionService`) on port 50051 — drop-in for the Java/Kotlin `pii-reporting-api` `CorpusDataSqlComparisonIT` integration test.

## Prerequisites

- **Rust ≥ 1.85** (edition 2024). `rustup toolchain install stable` if missing.
- A C++ toolchain — needed by `tokenizers`' `esaxx-rs` and ONNX Runtime's prebuilt link.
  - Windows: Visual Studio Build Tools 2022 (C++ workload).
  - Linux: `build-essential`, `pkg-config`, `libssl-dev`, `cmake`, `clang`.
  - macOS: Xcode Command Line Tools (`xcode-select --install`).
- **glibc ≥ 2.38** on Linux for the prebuilt ONNX Runtime that ships with the `ort` crate (Ubuntu 24.04 +, Debian 13 +). Bookworm / older Ubuntus will fail to link.
- **GLiNER2-large-v1 ONNX model** (see [Downloading the model](#downloading-the-model) below) — the model files are not vendored in the git repo and must be downloaded before first run.

No network or HuggingFace cache is needed at runtime — the model is read from disk only.

## Downloading the model

The ONNX export lives on HuggingFace:
**[https://huggingface.co/lmo3/gliner2-large-v1-onnx](https://huggingface.co/lmo3/gliner2-large-v1-onnx)**
(unofficial ONNX export of `fastino/gliner2-large-v1`, MIT licensed).

Required directory layout under the project root:

```
models/gliner2-large-v1-onnx/
├── README.md                  # model card
├── config.json                # hidden_size, vocab_size
├── gliner2_config.json        # special tokens, onnx file map
├── tokenizer.json             # tokenizer (HF format)
├── tokenizer_config.json
└── onnx/
    ├── encoder.onnx           # 1.7 GB — main DeBERTa-v3 encoder
    ├── encoder.onnx.data      # 1.7 GB — external tensor data
    ├── span_rep.onnx          # 113 MB — span representation head
    ├── span_rep.onnx.data     # 113 MB
    ├── classifier.onnx        #   8 MB — classification head
    ├── classifier.onnx.data   #   8 MB
    ├── count_embed.onnx       #  73 MB — count embedding head
    └── count_embed.onnx.data  #  73 MB
```

Total on disk: **~3.7 GB** (fp32 only; we don't ship the fp16 variants).

### Quick download (CLI, recommended)

```bash
pip install huggingface_hub
huggingface-cli download lmo3/gliner2-large-v1-onnx \
    --local-dir models/gliner2-large-v1-onnx \
    --include "README.md" "config.json" "gliner2_config.json" \
              "tokenizer.json" "tokenizer_config.json" \
              "onnx/encoder.onnx" "onnx/encoder.onnx.data" \
              "onnx/span_rep.onnx" "onnx/span_rep.onnx.data" \
              "onnx/classifier.onnx" "onnx/classifier.onnx.data" \
              "onnx/count_embed.onnx" "onnx/count_embed.onnx.data"
```

The `--include` filter skips the unused `*_fp16.*` variants. If you ever need fp16 (for ~2× faster inference at slight accuracy cost), drop the `--include` flags entirely.

### Manual download

Browse the files at the link above (`tree/main/onnx`) and download the fp32 set into `models/gliner2-large-v1-onnx/onnx/`, plus the metadata files into `models/gliner2-large-v1-onnx/`.

## Build

```bash
# Library + all binaries
cargo build --release

# Just the gRPC server
cargo build --release --bin grpc_server
```

`protoc` is bundled via `protoc-bin-vendored` — no system install required.

The release binary lands at `target/release/grpc_server` (`.exe` on Windows).

## Run the gRPC server

From the **project root**:

```bash
./target/release/grpc_server \
    --taxonomy config/nlpd-ipi.toml \
    --model    models/gliner2-large-v1-onnx \
    --addr     0.0.0.0:50051
```

From inside `target/release/` (Windows / PowerShell example):

```powershell
./grpc_server.exe `
    --taxonomy ..\..\config\nlpd-ipi.toml `
    --model    ..\..\models\gliner2-large-v1-onnx `
    --addr     0.0.0.0:50051
```

Expected startup logs:

```
Loading taxonomy: config/nlpd-ipi.toml
Pipeline scope: 22 regex / 7 NER / 0 classification IPIs
Loading model: models/gliner2-large-v1-onnx
Model loaded in 23s
Listening on 0.0.0.0:50051
Server started on port 50051
```

Per-request logs are emitted to stderr:

```
DetectPII: 7367 bytes, 22 findings, 13854ms
```

### CLI flags

| Flag | Default | Purpose |
|---|---|---|
| `--taxonomy <path>` | `config/nlpd-ipi.toml` | Baseline IPI taxonomy TOML |
| `--override-path <path>` | none | Optional tenant override TOML applied on top of the baseline |
| `--model <dir>` | `models/gliner2-large-v1-onnx` | Directory containing the ONNX model assets |
| `--addr <host:port>` | `0.0.0.0:50051` | Listen address |

## Docker

```bash
docker build -t ai-sentinel/pii-detector-rust:latest .
docker run --rm -p 50051:50051 \
    -v "$PWD/models/gliner2-large-v1-onnx:/app/models/gliner2-large-v1-onnx:ro" \
    ai-sentinel/pii-detector-rust:latest
```

The image bundles the binary and the taxonomy (~170 MB). The model is mounted at runtime — bundling it inflates the image to ~5.7 GB and isn't necessary for most deployments.

For the Testcontainers-managed integration test, see `ai-sentinel-forked/pii-reporting-api`'s `CorpusDataSqlComparisonIT` — it pulls `ai-sentinel/pii-detector-rust:latest` and mounts the model dir at startup.

## gRPC contract

Proto source: `proto/pii_detection.proto` (vendored from `ai-sentinel-forked/pii-reporting-api/.../pii_detection.proto`).

Two RPCs:

- `DetectPII(PIIDetectionRequest) → PIIDetectionResponse` — synchronous
- `StreamDetectPII(PIIDetectionRequest) → stream PIIDetectionUpdate` — chunk-progress + final summary

Offsets in the response are **UTF-16 code unit positions** so they line up with Java's `String.substring(start, end)` directly.

## Configuration

- `config/nlpd-ipi.toml` — baseline IPI taxonomy. Defines each IPI's layer (regex / NER / classification), threshold, patterns, validators, and proximity-filter rules.
- `config/overrides/` — tenant override TOMLs. Use `--override-path` to apply on top of the baseline; supports `[[disable]]` blocks, per-IPI threshold/severity/priority/enabled tweaks, and `[[custom_ipi]]` additions.

## Tests

```bash
# Lib + integration tests
cargo test

# Labeled eval (per-IPI TP/FP/FN against the hand-labeled corpus)
cargo run --release --example labeled_eval -- \
    --labels labeled_corpus/labels_detected_updated.jsonl
```

The labeled eval writes `false_positives_review.md` / `false_negatives_review.md` next to the labels file for verdict-driven label cleanup.

## Repository layout

```
src/
├── bin/grpc_server.rs        # gRPC entrypoint
├── grpc_server.rs            # tonic service impl, finding → wire mapping
├── pipeline/                 # chunker + 3-tier orchestration + context filter
├── regex_layer/              # tier A — regex + validators (luhn, swiss_avs, …)
├── ner_layer/                # tier B — GLiNER2 NER wrapper
├── classification_layer/     # tier C — whole-section classification
├── gliner2/                  # ONNX runtime, tokenization, schema input building
├── taxonomy/                 # TOML loader, validation, override merging
├── findings.rs               # Finding struct (shared output type)
├── html_strip.rs             # HTML → plain text preprocessor
└── resolve.rs                # cross-IPI span overlap resolution
```
