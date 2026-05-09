"""Quick script: combien de findings sur le doc doccano avec le nouveau chunker ?

Charge GLiNERDetector via la chaine de prod (avec GlinerSubwordChunker),
lance predict_chunked sur le corpus parity, affiche le decompte total +
breakdown par label + comparaison vs baseline NVIDIA.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVICE_ROOT))
REPO_ROOT = SERVICE_ROOT.parent

CORPUS_PATH = (
    REPO_ROOT
    / "pii-reporting-api/src/test/resources/test-corpus/Miscellaneous"
    / "confluence-pii-test-document-docanno.txt"
)
BASELINE_PATH = REPO_ROOT / "my-files" / "confluence-pii-test-document-nvidia-gliner-result.json"
LOCAL_MODEL = SERVICE_ROOT / "models" / "gliner-pii-onnx" / "pytorch"

PARITY_LABELS = [
    "customer_id", "api_key", "account_number", "swift_bic",
    "device_identifier", "certificate_license_number",
    "health_plan_beneficiary_number", "medical_record_number",
    "national_id", "password", "http_cookie", "ssn", "license_plate",
]
THRESHOLD = 0.5

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.detector.gliner_detector import GLiNERDetector

corpus = CORPUS_PATH.read_text(encoding="utf-8")
print(f"[CORPUS] {CORPUS_PATH.name}: {len(corpus)} chars")

baseline_raw = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
baseline_entities = json.loads(baseline_raw["choices"][0]["message"]["content"])["entities"]
baseline_counter = Counter(e["label"] for e in baseline_entities)
print(f"[BASELINE NVIDIA] total={len(baseline_entities)}")
for label, count in sorted(baseline_counter.items()):
    print(f"  {label:<35} = {count}")

cfg = DetectionConfig(model_id=str(LOCAL_MODEL), threshold=THRESHOLD)
det = GLiNERDetector(config=cfg)
det.load_model()
info = det.semantic_chunker.get_chunk_info()
print(f"\n[CHUNKER] {info}")

entities = det.predict_chunked(corpus, PARITY_LABELS, THRESHOLD)
detected_counter = Counter(e["label"] for e in entities)

print(f"\n[CHUNKED DETECTION] total={len(entities)}")
print(f"  baseline_total={len(baseline_entities)}")
print(f"  detected_total={len(entities)}")
print(f"  delta=detected-baseline={len(entities) - len(baseline_entities):+d}")

print("\n[BREAKDOWN by label]")
print(f"  {'label':<35} {'baseline':>9} {'detected':>9} {'delta':>7}")
print(f"  {'-' * 35} {'-' * 9} {'-' * 9} {'-' * 7}")
all_labels = sorted(set(baseline_counter) | set(detected_counter))
for label in all_labels:
    b = baseline_counter.get(label, 0)
    d = detected_counter.get(label, 0)
    print(f"  {label:<35} {b:>9} {d:>9} {d - b:>+7}")

extras = {l: c for l, c in detected_counter.items() if l not in baseline_counter}
if extras:
    print(f"\n[EXTRAS hors baseline] {extras}")
