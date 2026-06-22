# PII detector benchmark — P/R/F1 per detector, pipeline & LLM-judge impact

Reproducible span-level benchmark (spec: `my-files/integratoin-test-with-dataset/spec.md`).
It measures **precision / recall / F1** by exact char-offset match for:

- each detector **in isolation** — `GLINER2`, `PRESIDIO`, `REGEX`, `OPENMED`;
- the full **pipeline** (union of the four, with the production merge);
- the **LLM-as-judge impact** (ON vs OFF) on each of the above.

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
   the pipeline), scans every gold doc once **with the judge ON**, and derives
   both states from the same response:
   - judge **ON**  = `sensitiveDataFound()` (kept after the judge);
   - judge **OFF** = `sensitiveDataFound() ∪ discardedByJudge()` (pre-judge).
   This is exact because `prefilter_enabled=false` and the judge is drop-only, so
   the only post-detection drops are the judge's. It also halves the runtime
   (5 scans instead of 10).
3. **`SpanScorer`** computes strict (span + canonical label) and type-agnostic
   (span only) P/R/F1, and `BenchmarkReport` writes `target/pii-bench/{report.md,
   metrics.json,metrics.csv}`.

## Datasets (frozen)

| Dataset | Revision | Spans | Status |
|---|---|---|---|
| `gretelai/synthetic_pii_finance_multilingual` | `7b844d1…` | `pii_spans` (JSON string) | ✅ |
| `ai4privacy/pii-masking-300k` | `c8c7789…` | `privacy_mask` (objects) | ✅ |
| `bigcode/bigcode-pii-dataset` | `eb952c9…` | gated | ⛔ access not granted (HTTP 401) — secrets covered by synthetic fixtures instead |

Blind spots with no public-dataset coverage (`ACCESS_TOKEN`, `RECOVERY_CODE`,
`CARD_EXPIRY`, `SECRET`) are injected by `synthetic_fixtures.py`
(`gold/synthetic-blindspots.jsonl`) so they are measured without being unfairly
counted as FN where datasets simply don't label them. The same applies to
`TAX_ID` and `TAX_NUMBER` (predictable per-country / Swiss IDE-VAT formats),
injected by `synthetic_idtax_fixtures.py` (`gold/synthetic-idtax.jsonl`,
fr/de/it/en). `LICENSE_NUMBER` and `SENSITIVE_ACCOUNT_ID` have no predictable
value format nor annotated dataset, so they are intentionally left ungolded.

## Run

### 1. Build the gold (needs network + HF token)

The HF token is read from `HUGGING_FACE_API_KEY` (mirrored into `HF_TOKEN`).

```powershell
cd benchmarks/pii-dataset-eval
pip install -r requirements.txt
python build_datasets.py --seed 42 --n 300
python synthetic_fixtures.py        # writes gold/synthetic-blindspots.jsonl
python synthetic_idtax_fixtures.py  # writes gold/synthetic-idtax.jsonl (TAX_ID, TAX_NUMBER)
```

Offline self-check of the mapping (no network):

```powershell
python self_test.py
```

### 2. Run the benchmark IT (needs Docker + a reachable judge endpoint)

```powershell
$env:RUN_PII_BENCHMARK = "true"
mvn -pl pii-reporting-api -Dtest=PiiDetectorBenchmarkIT `
    "-Dcorpus.bench.hf-cache=C:\hf-cache" `
    "-Dcorpus.bench.llm-judge-url=http://host.docker.internal:1234/v1" `
    "-Dcorpus.bench.llm-judge-model=detect-pii-4b-v2" test
```

Outputs land in `pii-reporting-api/target/pii-bench/`.

### System properties

| Property | Default | Purpose |
|---|---|---|
| `corpus.bench.gold-dir` | `../benchmarks/pii-dataset-eval/gold` | gold JSONL location |
| `corpus.bench.concept-map` | `../benchmarks/pii-dataset-eval/mappings/detector_concept_map.json` | detector→canonical map |
| `corpus.bench.llm-judge-url` | `http://host.docker.internal:1234/v1` | OpenAI-compatible judge endpoint |
| `corpus.bench.llm-judge-model` | `detect-pii-4b-v2` | preferred judge model (mind the quant suffix, e.g. `@q8_0`) |
| `corpus.bench.max-docs` | (all) | cap docs for a smoke run |
| `corpus.bench.threshold` | (seed defaults) | uniform confidence threshold override |
| `corpus.bench.require-judge-effect` | `true` | fail if the judge discarded 0 across all configs (passthrough guard) |

## Judge endpoint & validity guard

The judge needs a reachable OpenAI-compatible endpoint. From the container the
dev host's LM Studio is reachable via `host.docker.internal`; a LAN endpoint may
not be (Docker subnet collisions) — override `corpus.bench.llm-judge-url`
accordingly. If the endpoint is unreachable the judge runs **fail-open** and
discards nothing, which would silently make the judge-impact half meaningless.
The IT therefore **fails** when the judge discarded 0 findings across all configs
(disable with `-Dcorpus.bench.require-judge-effect=false` to acknowledge).

## Comparing generative LLM extractors (e.g. Ministral-3B-PII vs detect-pii-4b-v2)

A second, independent harness compares **generative LLM PII extractors**
head-to-head — models that take text and return a JSON array of `{text,label}`
entities (e.g. `OpenMed/Ministral-3B-PII-Preview` vs `detect-pii-4b-v2` run in
extraction mode). It reuses the same gold + canonical vocabulary but scores
**value-level** (canonical concept + normalised value per doc), since these
models return values, not char offsets. Out-of-scope predictions (email, names,
dates…) are dropped, not penalised; unknown labels are logged so the map can be
extended.

1. Configure the models (copy the sample, edit endpoints/ids):
   ```powershell
   cp extractors.sample.json extractors.json   # extractors.json is gitignored
   ```
2. Build the gold (same as above) — `build_datasets.py` also emits
   `mappings/extractor_concept_map.json` (from `[extractors]` in the mapping).
3. Run:
   ```powershell
   $env:RUN_LLM_EXTRACTOR_BENCHMARK = "true"
   mvn -pl pii-reporting-api -Dtest=LlmExtractorComparisonIT test
   ```
   Output: `pii-reporting-api/target/pii-bench/extractor-comparison.{md,json,csv}`
   with a side-by-side P/R/F1 table and a head-to-head Δ when exactly two models
   are configured.

Properties: `bench.extractors-config`, `bench.extractor-concept-map`,
`corpus.bench.gold-dir`, `corpus.bench.max-docs`,
`bench.extractor.request-timeout-s` (120), `bench.extractor.max-tokens` (2048).

> Endpoint note: unlike the detector benchmark (where the judge runs *inside* the
> container and uses `host.docker.internal`), this harness runs on the **host
> JVM** and calls the endpoint directly — use `http://localhost:1234/v1` (LM
> Studio on this machine) or the LAN IP (e.g. `http://172.22.22.63:1234/v1`),
> **not** `host.docker.internal`.

> Note: this measures the models as **extractors** (recall/precision of what they
> find). It is distinct from the detector benchmark above, where
> `detect-pii-4b-v2` is the LLM-as-**judge** (FP pruning). Mapping for the
> extractors' labels lives in `[extractors._default]` of `label_mapping.toml`
> (add `[extractors.<name>]` to override per model).

## Reproducibility

- HF dataset revisions are pinned in `label_mapping.toml`.
- Sub-sampling is `random.Random(seed).shuffle` + head (default seed 42, n 300).
- Detector thresholds come from the master seed
  `pii-reporting-api/src/test/resources/sql/data-improved-gliner2-presidio-regex.sql`
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
| `synthetic_fixtures.py` | blind-spot fixtures (`gold/synthetic-blindspots.jsonl`) |
| `self_test.py` | offline mapping correctness check (no network) |
| `extractors.sample.json` | template for the LLM extractor comparison (copy to `extractors.json`) |
| `requirements.txt` | `datasets`, `huggingface_hub`, `tomli` (py<3.11) |

The Java side lives in
`pii-reporting-api/src/test/java/pro/softcom/aisentinel/integration/`:
`PiiDetectorBenchmarkIT` + the `bench/` package (`SpanScorer`, `ConceptMap`,
`GoldDataset`, `DetectorConfigSeed`, `BenchmarkReport`, …) with `SpanScorerTest`
unit-testing the scorer without Docker.
