"""Export a GLiNER2 checkpoint to the monolithic ONNX format used by gline-rs.

Why this script exists
----------------------
``scripts/export_gliner_to_onnx.py`` only handles GLiNER **v1** checkpoints
(``gliner.GLiNER`` + built-in ``export_to_onnx``). GLiNER2 checkpoints
(``model_type: "extractor"``, e.g. fastino/gliner2-privacy-filter-PII-multi)
need a different graph. The community runtime consumed by ``fast_gliner``
(gline-rs vendored in talmago/fast_gliner) expects a single ``model.onnx``
with the exact interface of lion-ai/gliner2-multi-v1-onnx:

    INPUTS
      input_ids        int64  (1, seq_len)
      attention_mask   int64  (1, seq_len)
      text_positions   int64  (num_words,)        first sub-token of each text word
      schema_positions int64  (num_schema_tokens,) first sub-token of [P] then each [E]
      span_idx         int64  (1, num_spans, 2)   word-level (start, end-inclusive)
    OUTPUT
      span_scores      float  (1, num_fields, num_words, max_width)  sigmoid probs

The graph reproduces the GLiNER2 "entities" inference path
(gliner2/inference/engine.py::_extract_span_result) with count fixed to 1:

    hidden     = encoder(input_ids, attention_mask)
    text_embs  = hidden[0][text_positions]          # token_pooling == "first"
    e_embs     = hidden[0][schema_positions][1:]    # skip [P]
    span_rep   = SpanRepLayer(text_embs, span_idx)  # (num_words, max_width, D)
    proj       = count_embed(e_embs, 1)             # (1, num_fields, D)
    scores     = sigmoid(einsum("lkd,bpd->bplk", span_rep, proj))

NOTE: the official PyTorch pipeline additionally gates the result on
``count_pred`` (no entities when predicted count == 0). The monolithic graph
intentionally omits that gate (as does the lion-ai export): decoding is
threshold-only, which favours recall.

Usage (inside a container/venv with torch + gliner2 installed):
    python scripts/export_gliner2_to_monolithic_onnx.py \
        --model fastino/gliner2-privacy-filter-PII-multi \
        --output models/gliner2-privacy-filter-onnx
"""

import argparse
import logging
import re
import shutil
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

OPSET = 18
MAX_WIDTH_FALLBACK = 8

# Same word splitter as the lion-ai reference example (gline-rs compatible).
WORD_RE = re.compile(
    r"(?:https?://[^\s]+|www\.[^\s]+)"
    r"|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}"
    r"|@[a-z0-9_]+"
    r"|\w+(?:[-_]\w+)*"
    r"|\S",
    re.IGNORECASE,
)

VALIDATION_TEXT = (
    "Jean Dupont habite a Lausanne. Son email est jean.dupont@example.com "
    "et son IBAN est CH93 0076 2011 6238 5295 7."
)
VALIDATION_LABELS = ["person name", "email address", "iban", "city"]
VALIDATION_THRESHOLD = 0.5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export GLiNER2 to monolithic ONNX")
    parser.add_argument("--model", default="fastino/gliner2-privacy-filter-PII-multi")
    parser.add_argument("--output", default="models/gliner2-privacy-filter-onnx")
    parser.add_argument("--skip-validation", action="store_true")
    return parser.parse_args()


def build_wrapper(model):
    """Wrap the GLiNER2 Extractor into the monolithic NER graph."""
    import torch
    from torch import nn

    class MonolithicGliner2NER(nn.Module):
        def __init__(self, extractor):
            super().__init__()
            self.encoder = extractor.encoder
            self.span_rep = extractor.span_rep
            self.count_embed = extractor.count_embed

        def forward(self, input_ids, attention_mask, text_positions, schema_positions, span_idx):
            hidden = self.encoder(
                input_ids=input_ids, attention_mask=attention_mask
            ).last_hidden_state  # (1, seq, D)
            flat = hidden[0]  # (seq, D)
            text_embs = flat.index_select(0, text_positions)  # (num_words, D)
            schema_embs = flat.index_select(0, schema_positions)  # (1 + num_fields, D)
            e_embs = schema_embs[1:]  # (num_fields, D) — skip [P]
            # SpanRepLayer(markerV0) -> (1, num_words, max_width, D)
            span_rep = self.span_rep(text_embs.unsqueeze(0), span_idx).squeeze(0)
            proj = self.count_embed(e_embs, 1)  # (1, num_fields, D)
            scores = torch.einsum("lkd,bpd->bplk", span_rep, proj)
            return torch.sigmoid(scores)  # (1, num_fields, num_words, max_width)

    wrapper = MonolithicGliner2NER(model)
    wrapper.eval()
    return wrapper


def build_inputs(tokenizer, text: str, labels: list, max_width: int):
    """Build the five monolithic input tensors (mirrors the lion-ai example)."""
    import numpy as np

    words = [(m.group(), m.start(), m.end()) for m in WORD_RE.finditer(text.lower())]
    word_strings = [w for w, _, _ in words]

    schema_tokens = ["(", "[P]", "entities", "("]
    for label in labels:
        schema_tokens.append("[E]")
        schema_tokens.extend(label.split())
    schema_tokens.extend([")", ")"])

    full_sequence = schema_tokens + ["[SEP_TEXT]"] + word_strings
    encoding = tokenizer(
        full_sequence,
        is_split_into_words=True,
        add_special_tokens=False,
        return_tensors=None,
    )
    token_ids = encoding["input_ids"]
    word_ids = encoding.word_ids()

    num_schema_words = len(schema_tokens) + 1  # +1 for [SEP_TEXT]

    first_token_of_word = {}
    for tok_pos, wid in enumerate(word_ids):
        if wid is not None and wid not in first_token_of_word:
            first_token_of_word[wid] = tok_pos

    text_positions = [
        first_token_of_word[num_schema_words + w] for w in range(len(word_strings))
    ]
    schema_positions = [
        first_token_of_word[i]
        for i, tok in enumerate(schema_tokens)
        if tok in ("[P]", "[E]")
    ]

    num_words = len(word_strings)
    spans = []
    for start in range(num_words):
        for width in range(1, max_width + 1):
            end = start + width
            spans.append((start, end - 1) if end <= num_words else (0, 0))

    feeds = {
        "input_ids": np.array([token_ids], dtype=np.int64),
        "attention_mask": np.ones((1, len(token_ids)), dtype=np.int64),
        "text_positions": np.array(text_positions, dtype=np.int64),
        "schema_positions": np.array(schema_positions, dtype=np.int64),
        "span_idx": np.array(spans, dtype=np.int64).reshape(1, -1, 2),
    }
    return feeds, words


def decode_entities(span_scores, words, labels, text, threshold):
    """Threshold decoding identical to the lion-ai reference example."""
    scores = span_scores[0]  # (num_fields, num_words, max_width)
    entities = []
    for field_idx, label in enumerate(labels):
        for start in range(scores.shape[1]):
            for width_idx in range(scores.shape[2]):
                score = scores[field_idx, start, width_idx]
                if score >= threshold:
                    end = start + width_idx
                    if end >= len(words):
                        continue
                    char_start, char_end = words[start][1], words[end][2]
                    entities.append(
                        {
                            "label": label,
                            "text": text[char_start:char_end],
                            "start": char_start,
                            "end": char_end,
                            "score": float(score),
                        }
                    )
    entities.sort(key=lambda e: (e["start"], e["end"]))
    return entities


def export(model, tokenizer, output_dir: Path, max_width: int) -> Path:
    import torch

    wrapper = build_wrapper(model)
    feeds, _ = build_inputs(tokenizer, VALIDATION_TEXT, VALIDATION_LABELS, max_width)
    dummy = tuple(
        torch.from_numpy(feeds[k])
        for k in ("input_ids", "attention_mask", "text_positions", "schema_positions", "span_idx")
    )

    onnx_dir = output_dir / "onnx"
    onnx_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = onnx_dir / "model.onnx"

    logger.info("Exporting to %s (opset=%d)...", onnx_path, OPSET)
    start = time.time()
    torch.onnx.export(
        wrapper,
        dummy,
        str(onnx_path),
        input_names=[
            "input_ids",
            "attention_mask",
            "text_positions",
            "schema_positions",
            "span_idx",
        ],
        output_names=["span_scores"],
        dynamic_axes={
            "input_ids": {1: "seq_len"},
            "attention_mask": {1: "seq_len"},
            "text_positions": {0: "num_words"},
            "schema_positions": {0: "num_schema_tokens"},
            "span_idx": {1: "num_spans"},
            "span_scores": {1: "num_fields", 2: "num_words_out"},
        },
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,
    )
    logger.info("Export done in %.1fs (%.1f MB)", time.time() - start,
                onnx_path.stat().st_size / 1e6)
    return onnx_path


def validate(model, tokenizer, onnx_path: Path, max_width: int) -> None:
    """Numerical parity (wrapper vs ONNX) + entity-level sanity vs extract()."""
    import numpy as np
    import onnxruntime as ort
    import torch

    feeds, words = build_inputs(tokenizer, VALIDATION_TEXT, VALIDATION_LABELS, max_width)

    # 1. Numerical parity: PyTorch wrapper vs ONNX on identical inputs.
    wrapper = build_wrapper(model)
    with torch.no_grad():
        torch_scores = wrapper(
            *(torch.from_numpy(feeds[k]) for k in (
                "input_ids", "attention_mask", "text_positions",
                "schema_positions", "span_idx"))
        ).numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_scores = session.run(None, feeds)[0]

    max_diff = float(np.abs(torch_scores - onnx_scores).max())
    logger.info("Max |torch - onnx| on sigmoid scores: %.2e", max_diff)
    if max_diff > 1e-4:
        raise RuntimeError(f"Numerical parity FAILED (max diff {max_diff:.2e} > 1e-4)")
    logger.info("Numerical parity OK")

    # 2. Entity-level: ONNX threshold decoding vs official extract().
    onnx_entities = decode_entities(
        onnx_scores, words, VALIDATION_LABELS, VALIDATION_TEXT, VALIDATION_THRESHOLD
    )
    logger.info("ONNX entities (threshold=%.2f):", VALIDATION_THRESHOLD)
    for e in onnx_entities:
        logger.info("  [OX] %-14s %.3f  '%s'", e["label"], e["score"], e["text"])

    schema = model.create_schema().entities({lbl: lbl for lbl in VALIDATION_LABELS})
    raw = model.extract(
        VALIDATION_TEXT,
        schema,
        threshold=VALIDATION_THRESHOLD,
        format_results=False,
        include_confidence=True,
        include_spans=True,
    )
    logger.info("PyTorch extract() raw: %s", raw)

    pt_texts = set()
    payload = raw.get("entities", raw) if isinstance(raw, dict) else raw
    for label_map in payload if isinstance(payload, list) else [payload]:
        if not isinstance(label_map, dict):
            continue
        for items in label_map.values():
            if isinstance(items, list):
                for item in items:
                    if isinstance(item, dict):
                        pt_texts.add(item.get("text", "").lower())

    ox_texts = {e["text"].lower() for e in onnx_entities}
    common = pt_texts & ox_texts
    logger.info(
        "Entity overlap PyTorch vs ONNX: %d common / %d pytorch / %d onnx",
        len(common), len(pt_texts), len(ox_texts),
    )
    if pt_texts and not common:
        raise RuntimeError("Entity-level validation FAILED: no overlap with extract()")
    logger.info("Entity-level validation OK")


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output)

    logger.info("Loading GLiNER2 checkpoint: %s", args.model)
    from gliner2 import GLiNER2

    model = GLiNER2.from_pretrained(args.model)
    model.eval()

    tokenizer = model.processor.tokenizer
    max_width = getattr(model, "max_width", None) or getattr(
        getattr(model, "config", None), "max_width", MAX_WIDTH_FALLBACK
    )
    logger.info("max_width=%d, hidden_size=%s", max_width, getattr(model, "hidden_size", "?"))

    onnx_path = export(model, tokenizer, output_dir, max_width)

    # tokenizer.json next to onnx/ — the layout fast_gliner/gline-rs expects.
    tokenizer.save_pretrained(str(output_dir))
    for stray in ("special_tokens_map.json", "added_tokens.json"):
        # keep them: harmless, HF convention
        pass
    logger.info("Tokenizer saved to %s", output_dir)

    if not args.skip_validation:
        validate(model, tokenizer, onnx_path, max_width)

    logger.info("DONE. Export layout:")
    for f in sorted(output_dir.rglob("*")):
        if f.is_file():
            logger.info("  %s (%.1f MB)", f.relative_to(output_dir), f.stat().st_size / 1e6)


if __name__ == "__main__":
    main()
