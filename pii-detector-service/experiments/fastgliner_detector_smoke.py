"""End-to-end smoke test: Gliner2Detector on the fast_gliner runtime.

Validates the full integration path used by the gRPC service:
ModelManager runtime resolution ($HF_HOME auto-discovery) -> FastGLiNER2 load
-> chunker init (tokenizer from the ONNX export) -> detect_pii with DB-style
pii_type_configs -> PIIEntity offsets.

Run inside the IT image with fast_gliner installed and HF_HOME pointing at a
cache containing gliner2-privacy-filter-onnx/.
"""
import logging
import sys

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector

TEXT = (
    "Bonjour, je m'appelle Jean Dupont et mon email est jean.dupont@example.ch. "
    "Mon IBAN est CH93 0076 2011 6238 5295 7 et j'habite a Lausanne."
)
CONFIGS = {
    "GLINER2:PERSON_NAME": {"enabled": True, "detector": "GLINER2",
                            "detector_label": "person name", "threshold": 0.5},
    "GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                      "detector_label": "email address", "threshold": 0.5},
    "GLINER2:IBAN": {"enabled": True, "detector": "GLINER2",
                     "detector_label": "iban", "threshold": 0.5},
    "GLINER2:CITY": {"enabled": True, "detector": "GLINER2",
                     "detector_label": "city", "threshold": 0.5},
}
EXPECTED_TYPES = {"PERSON_NAME", "EMAIL", "IBAN", "CITY"}

detector = Gliner2Detector(DetectionConfig())
detector.load_model()
print(f"RUNTIME: {detector.runtime}", flush=True)
if detector.runtime != "fastgliner":
    print("FAIL: expected fastgliner runtime")
    sys.exit(1)

entities = detector.detect_pii(TEXT, threshold=0.5, pii_type_configs=CONFIGS)
print(f"ENTITIES: {len(entities)}")
ok = True
for e in entities:
    sliced = TEXT[e.start:e.end]
    match = "OK " if sliced == e.text else "BAD"
    if sliced != e.text:
        ok = False
    print(f"  [{match}] {e.pii_type:<12} {e.score:.3f} '{e.text}' [{e.start}:{e.end}] src={e.source}")

found_types = {e.pii_type for e in entities}
missing = EXPECTED_TYPES - found_types
if missing:
    print(f"FAIL: missing types {missing}")
    ok = False

masked, _ = detector.mask_pii(TEXT, threshold=0.5)
print(f"MASKED: {masked}")

print("SMOKE " + ("PASSED" if ok else "FAILED"))
sys.exit(0 if ok else 1)
