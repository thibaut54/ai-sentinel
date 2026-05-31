# GLiNER2 FP/FN evaluation — fixtures & run guide

Fixtures + run instructions for
`tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py`.

## What's here

- `_fixture_helpers.py` — byte-exact `_span` / `_case` helpers (shared).
- `_fixtures_{government,banking,identity,secrets}.py` — per-family `build_*`
  functions (the source of truth for the cases).
- `_generate_gliner2_fixtures.py` — merges the families, self-checks every
  span byte-exact, writes one `<PII_TYPE>.json` per type.
- `<PII_TYPE>.json` — 24 generated fixtures (6 axes × 4 languages, ~23 cases
  each). **Regenerate after editing any `_fixtures_*.py`:**

  ```
  python tests/resources/gliner2-fp-eval/_generate_gliner2_fixtures.py
  ```

## Why a dedicated venv

`gliner2` depends on `gliner`, which pins `transformers<5.2.0`. The main
project venv runs `transformers 5.9.0` (OpenMed / docling). The two cannot
coexist in one environment, so the GLiNER2 eval runs in an isolated
`.venv-gliner2` (transformers 5.1.x there, 5.9.0 in the main `.venv`).

Create it once (from the repo root `pii-detector-service/`):

```
.venv/Scripts/python.exe -m venv .venv-gliner2
.venv-gliner2/Scripts/python.exe -m pip install -e . gliner2 pytest pytest-cov httpx
```

## Run

LM Studio must serve the Qwen judge (see `[llm_judge].base_url` in
`config/detection-settings.toml`, override with `LLM_JUDGE_BASE_URL`). The
GLiNER2 model is pulled from HuggingFace on first use (cached under
`HF_HOME`, e.g. `D:\huggingface-cache`).

```powershell
# Full eval — 2 models × 24 types × 3 gates + report (~30-50 min, many judge calls)
.venv-gliner2\Scripts\python.exe -m pytest `
    tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py `
    -v -s --log-cli-level=INFO --no-cov

# One model only
$env:LM_GLINER2_MODEL_IDS = "fastino/gliner2-large-v1"
.venv-gliner2\Scripts\python.exe -m pytest `
    tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py `
    -k large-v1 -v -s --log-cli-level=INFO --no-cov

# One type, one model (fast smoke)
.venv-gliner2\Scripts\python.exe -m pytest `
    tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py `
    -k "PASSWORD and large-v1" -s --log-cli-level=INFO --no-cov
```

The fixture meta-test (`...LoadByteExactBalancedFixtures...`) needs neither
GLiNER2 nor LM Studio and runs in any venv.

## Outputs

Written to `target/gliner2-fp-eval-with-judge/`:

- `findings.jsonl` — per-detection record (model, baseline + judged verdict)
- `metrics.json` — aggregated metrics per model per type
- `report.md` — markdown comparison grouped by model then type

## Gates (what a green run means)

Hard gates assert the **judge**'s safety contract, model-independent:

1. **judge must not worsen precision** — `judged_fp_rate <= baseline_fp_rate`.
2. **TP-preservation** — recall degradation `<= 10pp`, absolute TP loss `<= 1`.

The detector's absolute FP rate (`<= 0.10`) and recall (`>= 0.50`) are SOFT
signals (WARN + report), not gates — `gliner2-large-v1` is a generalist and is
expected to miss the absolute bars; that's the comparison the report captures.
