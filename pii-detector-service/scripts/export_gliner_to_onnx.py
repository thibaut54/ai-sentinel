"""
Export GLiNER model to ONNX format for faster inference.

This script converts the nvidia/gliner-PII model from PyTorch format
to ONNX format, with optional INT8 quantization.

What this does:
1. Downloads the PyTorch model from HuggingFace (nvidia/gliner-PII)
2. Exports it to ONNX FP32 format (same weights, optimized container)
3. Optionally quantizes to INT8 (smaller + faster, ~same accuracy)
4. Validates the ONNX model produces identical results

Memory requirements:
- FP32 export + INT8 quantization: ~20-25 GB free RAM
- FP32 export only (--no-quantize): ~18-20 GB free RAM
- Close IDE, browser, etc. before running if needed

Usage:
    python scripts/export_gliner_to_onnx.py --validate
    python scripts/export_gliner_to_onnx.py --model nvidia/gliner-PII --output models/gliner-pii-onnx
    python scripts/export_gliner_to_onnx.py --no-quantize
"""

import argparse
import logging
import time
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)

ONNX_FILENAME = "model.onnx"
QUANTIZED_FILENAME = "model_quantized.onnx"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export GLiNER model to ONNX format"
    )
    parser.add_argument(
        "--model",
        default="nvidia/gliner-PII",
        help="HuggingFace model ID (default: nvidia/gliner-PII)",
    )
    parser.add_argument(
        "--output",
        default="models/gliner-pii-onnx",
        help="Output directory for ONNX files (default: models/gliner-pii-onnx)",
    )
    parser.add_argument(
        "--no-quantize",
        action="store_true",
        help="Skip INT8 quantization (only export FP32 ONNX)",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Run validation after export to compare PyTorch vs ONNX results",
    )
    parser.add_argument(
        "--opset",
        type=int,
        default=19,
        help="ONNX opset version (default: 19)",
    )
    return parser.parse_args()


def export_model(model_id: str, output_dir: str, quantize: bool, opset: int) -> Path:
    """Export GLiNER model to ONNX using built-in export_to_onnx().

    IMPORTANT: Export must be done in FP32 (no model.half()). Reasons:
    - INT8 quantization (DynamicQuantizeLinear) requires FP32 inputs
    - FP16 on CPU is slower than FP32 (no native FP16 CPU kernels)
    - The ONNX model.onnx will be FP32, quantized model will be INT8
    """
    import gc

    import torch
    from gliner import GLiNER

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    local_dir = output_path / "pytorch"

    # Step 1: Load PyTorch model
    logger.info("Loading PyTorch model: %s", model_id)
    start = time.time()
    model = GLiNER.from_pretrained(model_id, load_tokenizer=True)
    logger.info("Model loaded in %.1fs", time.time() - start)

    # Step 2: Save locally (required before ONNX export)
    logger.info("Saving PyTorch model to: %s", local_dir)
    model.save_pretrained(str(local_dir))

    # Free memory before reload
    del model
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    # Step 3: Reload from local (ensures clean state for export)
    logger.info("Reloading model from local copy...")
    model = GLiNER.from_pretrained(str(local_dir), load_tokenizer=True)

    # Step 4: Export to ONNX (FP32 — required for INT8 quantization)
    logger.info(
        "Exporting to ONNX FP32 (opset=%d, quantize=%s)...",
        opset, quantize,
    )
    logger.info(
        "This requires ~20-25 GB free RAM. If MemoryError occurs, "
        "close other applications and retry."
    )
    start = time.time()
    model.export_to_onnx(
        save_dir=str(local_dir),
        onnx_filename=ONNX_FILENAME,
        quantized_filename=QUANTIZED_FILENAME,
        quantize=quantize,
        opset=opset,
    )
    logger.info("ONNX export completed in %.1fs", time.time() - start)

    # Report file sizes
    _report_file_size(local_dir / ONNX_FILENAME, "ONNX FP32")
    if quantize:
        _report_file_size(local_dir / QUANTIZED_FILENAME, "ONNX INT8 quantized")

    return local_dir


def _report_file_size(path: Path, label: str) -> None:
    if path.exists():
        size_mb = path.stat().st_size / (1024 * 1024)
        logger.info("%s: %.1f MB", label, size_mb)


def validate_model(model_dir: Path, quantized: bool) -> None:
    """Compare PyTorch and ONNX model outputs on sample PII text."""
    from gliner import GLiNER

    test_text = (
        "Jean Dupont habite au 15 rue de Lausanne, 1000 Lausanne. "
        "Son email est jean.dupont@example.com et son numero AVS est 756.1234.5678.90. "
        "Il est ne le 15 mars 1985. Son IBAN est CH93 0076 2011 6238 5295 7."
    )
    labels = [
        "person name", "email address", "street address", "postal code",
        "city", "date of birth", "social security number", "iban",
    ]
    threshold = 0.3
    runs = 5

    # PyTorch inference
    logger.info("Running PyTorch inference (%d runs)...", runs)
    pytorch_model = GLiNER.from_pretrained(str(model_dir), load_tokenizer=True)
    pytorch_model.predict_entities(test_text, labels, threshold=threshold)  # warmup
    start = time.time()
    for _ in range(runs):
        pytorch_entities = pytorch_model.predict_entities(test_text, labels, threshold=threshold)
    pytorch_time = (time.time() - start) / runs

    # ONNX inference
    onnx_file = QUANTIZED_FILENAME if quantized else ONNX_FILENAME
    logger.info("Running ONNX inference (%s, %d runs)...", onnx_file, runs)
    onnx_model = GLiNER.from_pretrained(
        str(model_dir),
        load_onnx_model=True,
        load_tokenizer=True,
        onnx_model_file=onnx_file,
    )
    onnx_model.predict_entities(test_text, labels, threshold=threshold)  # warmup
    start = time.time()
    for _ in range(runs):
        onnx_entities = onnx_model.predict_entities(test_text, labels, threshold=threshold)
    onnx_time = (time.time() - start) / runs

    # Compare results
    logger.info("=" * 60)
    logger.info("VALIDATION RESULTS (avg over %d runs)", runs)
    logger.info("=" * 60)
    logger.info("PyTorch: %d entities in %.3fs", len(pytorch_entities), pytorch_time)
    for e in pytorch_entities:
        logger.info("  [PT] %s => %s (score: %.3f)", e["text"], e["label"], e["score"])

    logger.info("")
    logger.info("ONNX (%s): %d entities in %.3fs", onnx_file, len(onnx_entities), onnx_time)
    for e in onnx_entities:
        logger.info("  [OX] %s => %s (score: %.3f)", e["text"], e["label"], e["score"])

    logger.info("")
    speedup = pytorch_time / onnx_time if onnx_time > 0 else float("inf")
    logger.info("Speedup: %.2fx", speedup)

    # Check entity overlap
    pt_set = {(e["text"], e["label"]) for e in pytorch_entities}
    ox_set = {(e["text"], e["label"]) for e in onnx_entities}
    common = pt_set & ox_set
    pt_only = pt_set - ox_set
    ox_only = ox_set - pt_set

    logger.info("Entities in common: %d/%d", len(common), len(pt_set))
    if pt_only:
        logger.warning("PyTorch only: %s", pt_only)
    if ox_only:
        logger.warning("ONNX only: %s", ox_only)

    if len(common) >= len(pt_set) * 0.9:
        logger.info("VALIDATION PASSED")
    else:
        logger.warning("VALIDATION WARNING - Significant differences detected.")


def main() -> None:
    args = parse_args()

    logger.info("=" * 60)
    logger.info("GLiNER to ONNX Export Tool")
    logger.info("=" * 60)
    logger.info("Model: %s", args.model)
    logger.info("Output: %s", args.output)
    logger.info("Quantize: %s", not args.no_quantize)
    logger.info("Opset: %d", args.opset)
    logger.info("")

    model_dir = export_model(
        model_id=args.model,
        output_dir=args.output,
        quantize=not args.no_quantize,
        opset=args.opset,
    )

    if args.validate:
        validate_model(model_dir, quantized=not args.no_quantize)

    logger.info("")
    logger.info("=" * 60)
    logger.info("DONE! ONNX files saved to: %s", model_dir)
    logger.info("")
    logger.info("To use the ONNX model in pii-detector-service:")
    logger.info("  model = GLiNER.from_pretrained('%s',", model_dir)
    logger.info("      load_onnx_model=True,")
    logger.info("      load_tokenizer=True,")
    if not args.no_quantize:
        logger.info("      onnx_model_file='%s')", QUANTIZED_FILENAME)
    else:
        logger.info("      onnx_model_file='%s')", ONNX_FILENAME)
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
