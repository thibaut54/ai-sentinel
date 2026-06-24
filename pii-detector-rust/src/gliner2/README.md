# `gliner2/` — GLiNER2 ONNX runtime glue

This module is a Rust port of [`gliner2_onnx`](https://github.com/lmoe/gliner2-onnx) — the Python reference runtime for GLiNER2 ONNX models. It is **not** a thin wrapper around ONNX Runtime; it reproduces the orchestration logic that sits between raw ONNX Runtime calls and a usable inference API.

## Why we can't just "call ONNX Runtime"

ONNX Runtime executes computational graphs. GLiNER2 is **not** a single graph — and the part that turns text into something the model can score lives entirely outside the graphs. If we deleted this module and used `ort` directly, we'd have four ONNX sessions and nothing to do with them.

Concretely, the model ships as **four separate ONNX files** that must be chained:

| Graph | Purpose | Where it's called from |
|---|---|---|
| `encoder.onnx` | DeBERTa-v3-large backbone (~1.65 GB) | every inference |
| `span_rep.onnx` | Span representation head (~112 MB) | NER only |
| `classifier.onnx` | Classification MLP head (~8 MB) | classification only |
| `count_embed.onnx` | Unrolled GRU for label transform (~72 MB) | NER only |

ONNX Runtime can load each of these. It cannot tell you what to do with them.

## What this module actually does

Five jobs that have nothing to do with tensor math and everything to do with knowing the model:

1. **Schema-driven input building.** GLiNER2 doesn't accept text. It accepts a structured prompt:
   ```
   ( [P] entities ( [E] person [E] organization ) ) [SEP_TEXT] john works at google
   ```
   The exact placement of `(`, `)`, `[P]`, `[E]`/`[L]`, and `[SEP_TEXT]` is load-bearing. So is the task name (`entities` for NER, `category` for classification). So is lowercasing the text. Get any of this wrong and the model emits noise.

2. **Word-level tokenization with offset tracking.** The model operates on DeBERTa subwords, but entities must come out at word boundaries with character offsets pointing into the original text. We mirror Python's `WORD_PATTERN.finditer(text.lower())` exactly — URLs, emails, mentions, words-with-hyphens, then any single non-whitespace — and remember `(word_idx → char_start, char_end)` and `(word_idx → first_token_idx)` so the post-processing can map back.

3. **Span enumeration.** NER scores every (start_word, end_word) pair where `end_word - start_word < max_width` (= 8). For a 200-word page that's ~1600 candidate spans, all scored in parallel by `span_rep.onnx`.

4. **Multi-graph orchestration with intermediate tensor work.** NER scoring is *not* one ONNX call. It's:
   ```
   hidden_states          = encoder(input_ids, attention_mask)
   label_embeddings       = hidden_states[0, e_positions, :]        # gather at [E] positions
   text_hidden            = hidden_states[0, text_start_idx:, :]    # slice off the schema
   span_rep               = span_rep_onnx(text_hidden, span_starts, span_ends)
   transformed_labels     = count_embed_onnx(label_embeddings)      # GLiNER2-specific projection
   scores                 = sigmoid(span_rep @ transformed_labels.T)
   ```
   Five steps. Three ONNX calls. Two pure-Rust tensor ops. Plus position bookkeeping.

5. **Per-label span deduplication.** The same span can score above threshold for multiple labels (e.g., "Apple Store" as both `organization` and `location`). We keep the highest-scoring entity per label per overlap. That's Python-level logic; ONNX has no opinion.

## Why not call the Python library from Rust?

That's a defensible path — we considered it and dismissed it. Reasons:

- **Defeats the rewrite's purpose.** The whole point of going Rust is removing Python from the production path (orchestration overhead, deploy complexity, GIL).
- **IPC tax.** Even a stdio sidecar adds round-trip overhead on every inference, scaled by every Confluence page.
- **Distribution.** Shipping a Python interpreter + numpy + onnxruntime-python alongside a Rust binary loses every operational benefit of a single static binary.

## Why not write our own model in Candle / Burn?

Considered, dismissed:

- **The ONNX export is already validated** against the PyTorch original (max difference 1.43e-06, see [upstream `ARCHITECTURE.md`](../../gliner2-onnx/ARCHITECTURE.md)). Re-doing it in Candle means re-running that validation work for no incremental value.
- **The custom heads (SpanMarkerV0, unrolled GRU CountEmbed) are non-trivial.** The upstream took explicit care to unroll the GRU because PyTorch's GRU doesn't export to ONNX cleanly. Reproducing that in Candle means redoing the same engineering.
- **`ort` is mature** and well-supported across CPU / CUDA / DirectML / CoreML execution providers. Candle's coverage is narrower.

## What this means in practice

The mapping between this module and the Python reference is deliberately tight:

| Python (`gliner2_onnx/`)         | Rust (`src/gliner2/`)         |
|----------------------------------|-------------------------------|
| `constants.py`                   | `tokens.rs`                   |
| `exceptions.py`                  | folded into top-level `error.rs` |
| `types.py`                       | folded into `runtime.rs` / `ner.rs` |
| `runtime.py` — config loading    | `config.rs`                   |
| `runtime.py` — `_build_*_input`  | `input.rs`                    |
| `runtime.py` — `WORD_PATTERN` walker | `word_split.rs`           |
| `runtime.py` — ONNX session bundle | `sessions.rs`               |
| `runtime.py` — `classify`        | `classification.rs`           |
| `runtime.py` — `extract_entities`, `_generate_spans`, `_collect_entities`, `_deduplicate_entities` | `ner.rs` |
| `runtime.py` — `GLiNER2ONNXRuntime` class | `runtime.rs`         |

When upstream changes — new special tokens, a different input format, an extra ONNX graph — the change applies here at one or two known locations. That tight mirror is the point of the architecture.

## Parity verification

The upstream ships `tests/gliner2.fixtures.json` with expected outputs for a fixed set of (text, labels) pairs across both classification and NER. Our Rust tests load the same fixture file and assert byte-equivalent token IDs at the encoder boundary and approximately-equal scores at the output boundary. If a port diverges from the reference, that's the canary.
