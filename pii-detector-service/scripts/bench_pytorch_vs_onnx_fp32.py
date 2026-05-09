"""Bench PyTorch FP32 vs ONNX FP32 sur model deja exporte localement.

Reutilise models/gliner-pii-onnx/pytorch/ : aucun telechargement, aucune ecriture.
"""
import logging
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

MODEL_DIR = Path("models/gliner-pii-onnx/pytorch")
ONNX_FILE = "model.onnx"
RUNS = 10
WARMUP = 3

TEXT = (
    "Jean Dupont habite au 15 rue de Lausanne, 1000 Lausanne. "
    "Son email est jean.dupont@example.com et son numero AVS est 756.1234.5678.90. "
    "Il est ne le 15 mars 1985. Son IBAN est CH93 0076 2011 6238 5295 7."
)
LABELS = [
    "person name", "email address", "street address", "postal code",
    "city", "date of birth", "social security number", "iban",
]
THRESHOLD = 0.3


def bench(model, label):
    for _ in range(WARMUP):
        model.predict_entities(TEXT, LABELS, threshold=THRESHOLD)
    durations = []
    last = None
    for _ in range(RUNS):
        t0 = time.perf_counter()
        last = model.predict_entities(TEXT, LABELS, threshold=THRESHOLD)
        durations.append(time.perf_counter() - t0)
    durations.sort()
    mean = sum(durations) / len(durations)
    p50 = durations[len(durations) // 2]
    p95 = durations[int(len(durations) * 0.95)]
    log.info("%s: mean=%.3fs p50=%.3fs p95=%.3fs (n=%d)", label, mean, p50, p95, RUNS)
    return mean, last


def main():
    from gliner import GLiNER

    if not MODEL_DIR.exists():
        raise SystemExit(f"Missing {MODEL_DIR}. Run scripts/export_gliner_to_onnx.py first.")

    log.info("Loading PyTorch from %s...", MODEL_DIR)
    pt_model = GLiNER.from_pretrained(str(MODEL_DIR), load_tokenizer=True)

    log.info("Loading ONNX FP32 (%s)...", ONNX_FILE)
    onnx_model = GLiNER.from_pretrained(
        str(MODEL_DIR),
        load_onnx_model=True,
        load_tokenizer=True,
        onnx_model_file=ONNX_FILE,
    )

    pt_mean, pt_entities = bench(pt_model, "PyTorch FP32")
    ox_mean, ox_entities = bench(onnx_model, "ONNX FP32   ")

    speedup = pt_mean / ox_mean if ox_mean > 0 else float("inf")
    log.info("=" * 60)
    log.info("Speedup ONNX vs PyTorch: %.2fx", speedup)
    log.info("=" * 60)

    pt_set = {(e["text"], e["label"]) for e in pt_entities}
    ox_set = {(e["text"], e["label"]) for e in ox_entities}
    common = pt_set & ox_set
    log.info("PyTorch entities: %d | ONNX entities: %d | common: %d",
             len(pt_set), len(ox_set), len(common))
    if pt_set - ox_set:
        log.warning("PyTorch only: %s", pt_set - ox_set)
    if ox_set - pt_set:
        log.warning("ONNX only:    %s", ox_set - pt_set)


if __name__ == "__main__":
    main()
