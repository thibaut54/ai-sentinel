# Chunking strategy benchmark — Ministral-3B-PII-Preview

Finds the **best content-chunking strategy** to send to the
`OpenMed/Ministral-3B-PII-Preview` extractor (served by LM Studio,
OpenAI-compatible): **minimise inference time without degrading PII quality**.

Today the extractor receives the *whole document* in one request. This benchmark
sweeps chunk **size × overlap × boundary policy**, scores each strategy, and
reports the **F1-vs-time Pareto front** with a recommended **knee** (best
trade-off) versus the whole-doc baseline.

## Two tracks

| Track | Documents | Measures | Why |
|---|---|---|---|
| **A — quality** | mega-docs built by concatenating gold docs (offsets recomputed) to target token lengths | value-level **P/R/F1** + time | the corpus has no ground truth; the gold does, so quality is exact |
| **B — latency** | real corpus text files sliced to the same lengths | time / throughput only | realistic large, structured, PII-sparse content; no gold → no quality |

## What it sweeps (default "large sweep")

- **doc lengths**: `16384, 65536, 262144` tokens
- **chunk sizes**: `1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072` tokens
- **overlap**: `0, 10%, 20%`
- **boundary**: `char, token, line, sentence, paragraph` + the `whole`-doc baseline

`char` cuts blindly; `token` never splits a token; `line`/`sentence`/`paragraph`
cut on natural seams — the honest, production-viable approximation of "don't split
a PII value across a boundary" (we can't know value positions on real input).

## Design choices that matter

- **Real Mistral tokenizer** (via `tokenizers`) for exact token budgets and the
  256k target. `usage.prompt_tokens` from the server gives true token counts; the
  run **flags server-side truncation** (sent vs accepted tokens) so a baseline that
  exceeds LM Studio's `n_ctx` is reported as ⚠, not as a silent quality drop.
- **Overlap dedup without offsets**: Ministral returns values, not spans. The same
  occurrence seen by two overlapping chunks is collapsed by capping each
  `(concept, value)` count at its number of occurrences in the document
  (`min(raw, max(1, occurrences))`). Overlap never inflates counts; its recall
  benefit (catching a value a hard cut would have split) still shows as a TP.
- **Value-level scoring** identical to the Java `ValueScorer` (canonical concept +
  normalised value, strict and type-agnostic), reusing the canonical vocabulary
  and `[extractors]` label map from `../pii-dataset-eval/label_mapping.toml`.
- **Replayable**: `temperature=0` + a content-addressed response cache → re-runs
  are instant and deterministic.
- **Resumable**: a per-unit done-set skips completed `(track, doc, strategy)` work;
  the response cache resumes a crashed unit at the chunk level.
- **Failures logged**: failed units go to `failures.jsonl`; re-run only those with
  `--retry-failures`.

## Prerequisites

1. **Gold dataset** (Track A). Build it in the sibling folder:
   ```powershell
   cd ../pii-dataset-eval
   pip install -r requirements.txt
   python build_datasets.py --seed 42 --n 300
   python synthetic_fixtures.py
   python synthetic_idtax_fixtures.py
   ```
   > **For the 256k-token target** the default gold (~600 short docs ≈ 150k tokens
   > total) is too small: mega-docs will **cap below 256k and log a warning**. To
   > reach 256k with *unique* docs, rebuild with a larger sample (and, if needed,
   > switch the splits to `train` in `label_mapping.toml`):
   > ```powershell
   > python build_datasets.py --seed 42 --n 4000
   > ```
   > Docs are never repeated (that would bias value-level scoring).

2. **This benchmark's deps** (real tokenizer):
   ```powershell
   cd ../pii-chunking-eval
   pip install -r requirements.txt
   ```
   The tokenizer downloads from `OpenMed/Ministral-3B-PII-Preview`; set `HF_HOME`
   to your external HF cache. Override the source with `--tokenizer <repo|path>`.

3. **LM Studio** serving the model. The endpoint is reached from the host
   (`localhost` / LAN IP, **not** `host.docker.internal`). For the whole-doc
   baseline at large sizes, load the model with a large `n_ctx` (ideally 256k);
   the chunked strategies use small chunks and don't need it.

## Run

```powershell
# 1) Offline plumbing check — no endpoint, no downloads (~2s). Run this first.
python selftest.py

# 2) Offline end-to-end with the stub detector (exercises the full pipeline + report):
python chunk_bench.py --no-llm --smoke

# 3) Real short smoke against the live model (~1-2 min once warm):
python chunk_bench.py --smoke --base-url http://172.22.22.63:1234/v1

# 4) Medium real signal:
python chunk_bench.py --quick --base-url http://172.22.22.63:1234/v1

# 5) Full large sweep (long; fully resumable — safe to Ctrl-C and relaunch):
python chunk_bench.py --base-url http://172.22.22.63:1234/v1

# Re-run only previously failed units:
python chunk_bench.py --retry-failures --base-url http://172.22.22.63:1234/v1
```

Endpoint and model can also be set via env: `MINISTRAL_BASE_URL`,
`MINISTRAL_MODEL` (mind the `@quant` suffix, e.g. `ministral-3b-pii-preview@q8_0`),
`MINISTRAL_TOKENIZER`.

### Useful flags

`--track A|B|both` · `--doc-tokens 16384,65536,262144` · `--sizes ...` ·
`--overlaps 0,0.1,0.2` · `--boundaries token,line,paragraph` · `--reps N`
(mega-docs per length, for variance) · `--max-corpus-files N` · `--out DIR` ·
`--max-tokens` · `--request-timeout` · `--retries` · `--allow-tokenizer-fallback`.

## Outputs (in `--out`, default `./out`, smoke `./out-smoke`)

| File | Content |
|---|---|
| `report.md` | Pareto front per doc length, recommended knee, baseline Δ, Track B latency, failed units |
| `metrics.json` / `metrics.csv` | full aggregated rows (F1, P, R, time, tokens, throughput, pareto/knee flags) |
| `results.jsonl` | one line per completed unit (the resume done-set) |
| `failures.jsonl` | failed units (re-run with `--retry-failures`) |
| `cache.jsonl` | content-addressed LLM responses (replay + chunk-level resume) |

## Performance & slow (CPU) endpoints

Inference time scales with chunk **token** count. On a CPU-only LM Studio this is
slow (measured ~87 s for a 2048-token chunk), so with the default
`--request-timeout 120` any chunk above ~2800 tokens times out — which means the
**whole-doc baseline** and chunk **sizes ≥ 8192** are infeasible there.

- All chunk sizes are bounded in tokens (a structural seam adds at most +25 %), so
  a `s2048` config never silently sends a 2900-token chunk.
- A read timeout is **not retried** (the request reached the model; it's just slow)
  — the unit is logged to `failures.jsonl` for `--retry-failures`.
- **First lever: enable GPU offload in LM Studio** (10–50× faster) → the full sweep
  becomes feasible. Otherwise keep sizes small and skip the baseline:
  ```powershell
  python chunk_bench.py --track A --doc-tokens 16384 `
      --sizes 512,1024,2048 --overlaps 0,0.2 `
      --base-url http://127.0.0.1:1234/v1
  ```
  or raise the timeout to attempt large chunks/baseline: `--request-timeout 1800`.

## Caveats

- Track B has **no ground truth** (the corpus is unlabelled and its biggest files
  are PII-sparse boilerplate) → latency/throughput only, never P/R/F1.
- Token-boundary offsets are exact on ASCII (the English gold); on non-ASCII
  (French corpus, Track B) ByteLevel offsets can drift a char — irrelevant to
  timing.
- The Pareto **time** is the sum of per-chunk latencies (intrinsic, parallelism-
  independent, cache-stable), not wall-clock.
