"""
Probe live PII detection service via gRPC on the docanno corpus.

Why this script:
- We observe 78 findings in production for confluence-pii-test-document-docanno.txt
- NVIDIA hosted gliner-PII (same labels, same threshold 0.8) returns ~128 entities
- We need to inspect WHERE the 50 entities are dropped in our pipeline:
  1. Inside predict_chunked (chunker output, per-chunk entities)
  2. After multi-pass aggregation
  3. After conflict resolution
  4. After overlap resolution
  5. After post-filter (pii_service._filter_entities_by_type_config)

Strategy:
- Call the running gRPC server at localhost:50051 with fetch_config_from_db=true
  so we hit the EXACT same pipeline as the live UI scan.
- Breakpoints already set in IntelliJ will fire and capture intermediate states.
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = REPO_ROOT / "pii-detector-service"
sys.path.insert(0, str(SERVICE_ROOT))

import grpc

from pii_detector.proto.generated import (  # noqa: E402
    pii_detection_pb2,
    pii_detection_pb2_grpc,
)

CORPUS_PATH = (
    REPO_ROOT
    / "pii-reporting-api"
    / "src"
    / "test"
    / "resources"
    / "test-corpus"
    / "Miscellaneous"
    / "confluence-pii-test-document-docanno.txt"
)


def main() -> int:
    if not CORPUS_PATH.exists():
        print(f"Corpus not found: {CORPUS_PATH}")
        return 2

    content = CORPUS_PATH.read_text(encoding="utf-8")
    print(f"Corpus loaded: {len(content)} chars from {CORPUS_PATH.name}")

    channel = grpc.insecure_channel(
        "localhost:50051",
        options=[
            ("grpc.max_receive_message_length", 10 * 1024 * 1024),
            ("grpc.max_send_message_length", 10 * 1024 * 1024),
        ],
    )
    stub = pii_detection_pb2_grpc.PIIDetectionServiceStub(channel)

    request = pii_detection_pb2.PIIDetectionRequest(
        content=content,
        threshold=0.0,  # ignored when fetch_config_from_db=true
        fetch_config_from_db=True,
    )

    print("Calling DetectPII (fetch_config_from_db=true)...")
    response = stub.DetectPII(request, timeout=300)

    print(f"\nResponse: {len(response.entities)} entities")
    by_type: dict[str, int] = {}
    for ent in response.entities:
        by_type[ent.type] = by_type.get(ent.type, 0) + 1
    for t, n in sorted(by_type.items(), key=lambda kv: -kv[1]):
        print(f"  {t}: {n}")

    if response.summary:
        print(f"\nServer summary: {dict(response.summary)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
