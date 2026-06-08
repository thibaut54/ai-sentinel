"""Probe fast_gliner API against the reference lion-ai/gliner2-multi-v1-onnx model.

Validates, before any integration work:
1. from_pretrained on a HF repo id (and local dir resolution).
2. predict_entities output contract: {text, label, score, start, end} with
   char offsets such that text[start:end] == entity text.
3. Whether create_schema().entities(...) accepts a {label: description} dict
   (the contract our Gliner2Detector relies on) or only a flat label list.
4. The raw shape returned by extract(text, schema).

Run inside python:3.11 with fast_gliner installed; HF_HOME should point to a
persistent cache to avoid re-downloading the 1.2 GB model.
"""
import json
import sys

from fast_gliner import FastGLiNER2

TEXT = (
    "Bonjour je suis Jean Dupont. Mon email est jean.dupont@example.com "
    "et mon IBAN est CH93 0076 2011 6238 5295 7. J'habite a Lausanne."
)
LABELS = ["person name", "email address", "iban", "city"]
SCHEMA_WITH_DESCRIPTIONS = {
    "person name": "full name of a person",
    "email address": "an email address",
    "iban": "international bank account number",
    "city": "name of a city",
}

MODEL_ID = sys.argv[1] if len(sys.argv) > 1 else "lion-ai/gliner2-multi-v1-onnx"

# fast_gliner's from_pretrained uses allow_patterns=["*.json", "*.model",
# "onnx/*.onnx"], which misses repos with model.onnx at the ROOT (lion-ai
# layout). Pre-download the full snapshot ourselves, then load by local dir
# (the rglob fallback finds any single *.onnx).
if "/" in MODEL_ID and not MODEL_ID.startswith((".", "/")):
    from huggingface_hub import snapshot_download

    MODEL_ID = snapshot_download(repo_id=MODEL_ID)

print(f"=== Loading {MODEL_ID} ===", flush=True)
model = FastGLiNER2.from_pretrained(MODEL_ID)
print("LOAD OK", flush=True)

print("\n=== 1. predict_entities(text, labels) ===", flush=True)
entities = model.predict_entities(TEXT, LABELS)
print(json.dumps(entities, indent=2, ensure_ascii=False, default=str))
for e in entities:
    if isinstance(e, dict) and "start" in e and "end" in e:
        sliced = TEXT[e["start"]:e["end"]]
        marker = "OK " if sliced == e.get("text") else "MISMATCH"
        print(f"  offset-check [{marker}] '{sliced}' vs '{e.get('text')}'")

print("\n=== 2. create_schema().entities(dict with descriptions) ===", flush=True)
try:
    schema = model.create_schema().entities(SCHEMA_WITH_DESCRIPTIONS)
    print("DICT SCHEMA ACCEPTED")
    raw = model.extract(TEXT, schema)
    print("RAW extract output:")
    print(json.dumps(raw, indent=2, ensure_ascii=False, default=str))
except Exception as exc:  # noqa: BLE001 - probe script, report everything
    print(f"DICT SCHEMA REJECTED: {type(exc).__name__}: {exc}")

print("\n=== 3. create_schema().entities(list of labels) ===", flush=True)
try:
    schema = model.create_schema().entities(LABELS)
    print("LIST SCHEMA ACCEPTED")
    raw = model.extract(TEXT, schema)
    print("RAW extract output:")
    print(json.dumps(raw, indent=2, ensure_ascii=False, default=str))
except Exception as exc:  # noqa: BLE001
    print(f"LIST SCHEMA REJECTED: {type(exc).__name__}: {exc}")

print("\nPROBE DONE")
