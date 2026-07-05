# PII detector benchmark — P/R/F1 per detector & pipeline

Reproducible span-level benchmark (spec: `my-files/integratoin-test-with-dataset/spec.md`).
It measures **precision / recall / F1** by exact char-offset match for:

- each detector **in isolation** — `PRESIDIO`, `REGEX`;
- the full **pipeline** (union of the two, with the production merge).

The detectors run inside the same containerised pipeline as production (Postgres +
the Python `pii-detector` over gRPC), driven by the Java integration test
`PiiDetectorBenchmarkIT` which reuses the Testcontainers infra of
`CorpusDataSqlComparisonIT`.

## How it works

1. **`build_datasets.py`** (this folder) downloads the labelled HF datasets at
   **frozen revisions**, normalises every span to a **canonical concept** via
   [`label_mapping.toml`](label_mapping.toml), drops non-mappable labels into
   *ignore zones* (never counted as FN, and a detector hit there is not a FP),
   sub-samples deterministically, and writes:
   - `gold/<dataset>.jsonl` — `{id,dataset,lang,text,spans:[{start,end,label}],ignore_spans:[{start,end,src_label}]}`
   - `mappings/detector_concept_map.json` — `{DETECTOR:{PII_TYPE:CANONICAL}}` (read by the Java scorer)
2. **`PiiDetectorBenchmarkIT`** seeds the DB per config (one detector enabled, or
   the pipeline) and scans every gold doc once.
3. **`SpanScorer`** computes strict (span + canonical label) and type-agnostic
   (span only) P/R/F1, and `BenchmarkReport` writes `target/pii-bench/{report.md,
   metrics.json,metrics.csv}`.

## Datasets (frozen)

| Dataset | Revision | Spans | Status |
|---|---|---|---|
| `gretelai/synthetic_pii_finance_multilingual` | `7b844d1…` | `pii_spans` (JSON string) | ✅ |
| `ai4privacy/pii-masking-300k` | `c8c7789…` | `privacy_mask` (objects) | ✅ |
| `bigcode/bigcode-pii-dataset` | `eb952c9…` | gated | ⛔ access not granted (HTTP 401) |

## Run

### 1. Build the gold (needs network + HF token)

The HF token is read from `HUGGING_FACE_API_KEY` (mirrored into `HF_TOKEN`).

```powershell
cd benchmarks/pii-dataset-eval
pip install -r requirements.txt
python build_datasets.py --seed 42 --n 300
```

Offline self-check of the mapping (no network):

```powershell
python self_test.py
```

### 2. Run the benchmark IT (needs Docker)

```powershell
$env:RUN_PII_BENCHMARK = "true"
mvn -pl pii-reporting-api -Dtest=PiiDetectorBenchmarkIT `
    "-Dcorpus.bench.hf-cache=C:\hf-cache" test
```

Outputs land in `pii-reporting-api/target/pii-bench/`.

### System properties

| Property | Default | Purpose |
|---|---|---|
| `corpus.bench.gold-dir` | `../benchmarks/pii-dataset-eval/gold` | gold JSONL location |
| `corpus.bench.concept-map` | `../benchmarks/pii-dataset-eval/mappings/detector_concept_map.json` | detector→canonical map |
| `corpus.bench.max-docs` | (all) | cap docs for a smoke run |
| `corpus.bench.threshold` | (seed defaults) | uniform confidence threshold override |

## Reproducibility

- HF dataset revisions are pinned in `label_mapping.toml`.
- Sub-sampling is `random.Random(seed).shuffle` + head (default seed 42, n 300).
- Detector thresholds come from the master seed
  `pii-reporting-api/src/test/resources/sql/data-presidio-regex.sql`
  and are overridable uniformly via `corpus.bench.threshold`.
- Offsets are code points; documents containing non-BMP characters are dropped at
  conversion time (logged, not silently capped) so offsets stay UTF-16-safe and
  match what the Java adapter returns.

## Files

| File | Role |
|---|---|
| `label_mapping.toml` | **source of truth**: canonical vocab, dataset→canonical, detector pii_type→canonical, ambiguous decisions |
| `mapping.py` | pure stdlib loader for the table |
| `build_datasets.py` | HF download → normalise → sub-sample → gold JSONL + concept map |
| `self_test.py` | offline mapping correctness check (no network) |
| `requirements.txt` | `datasets`, `huggingface_hub`, `tomli` (py<3.11) |

The Java side lives in
`pii-reporting-api/src/test/java/pro/softcom/aisentinel/integration/`:
`PiiDetectorBenchmarkIT` + the `bench/` package (`SpanScorer`, `ConceptMap`,
`GoldDataset`, `DetectorConfigSeed`, `BenchmarkReport`, …) with `SpanScorerTest`
unit-testing the scorer without Docker.
