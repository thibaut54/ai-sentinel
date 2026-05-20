"""
Integration benchmark: pipeline throughput vs CPU threading.

NO MOCKS. Loads a real MultiPassGlinerDetector, runs detect_pii on an
actual corpus several times for each torch.set_num_threads(K) value and
reports chars/sec, latency stats, and speedup vs single-thread baseline.

Why this matters
----------------
The grpc adapter forces torch.set_num_threads() at import time from the
TORCH_NUM_THREADS env var (default: 4). Picking the wrong value either
under-utilizes the CPU (K too low) or causes BLAS oversubscription
(N_python_threads x K > N_physical_cores). This bench measures the
real impact on a representative workload so you can pick K empirically.

Run
---
    # Fast default: 3 thread counts x 2 runs on a 5KB corpus (~1-3 min total)
    pytest tests/integration/test_threading_throughput_benchmark.py -s

    # Full bench: all thread counts, more runs, larger corpus
    $env:THREAD_COUNTS="1,2,4,8,16"
    $env:RUNS_PER_CONFIG="3"
    $env:CORPUS_FILE="long-text-for-gliner.txt"
    pytest tests/integration/test_threading_throughput_benchmark.py -s

    # Pin a specific corpus
    $env:CORPUS_FILE="text-for-gliner.txt"
    pytest tests/integration/test_threading_throughput_benchmark.py -s
"""

from __future__ import annotations

import os
import statistics
import sys
import time
from pathlib import Path
from typing import Dict, List

import pytest
import torch

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.detector.multi_pass_gliner_detector import (
    MultiPassGlinerDetector,
)

RESOURCES_DIR = Path(__file__).parent.parent / "resources"
DEFAULT_CORPUS = "text-for-gliner.txt"  # ~5KB, fast for iteration
DEFAULT_THREAD_COUNTS: List[int] = [1, 4, 16]  # baseline / sweet spot guess / all cores
DEFAULT_RUNS_PER_CONFIG: int = 2
DEFAULT_WARMUP_RUNS: int = 1
DETECTION_THRESHOLD: float = 0.5


def _say(msg: str) -> None:
    """Print with timestamp and flush so pytest -s shows it live."""
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def _parse_thread_counts() -> List[int]:
    raw = os.getenv("THREAD_COUNTS")
    if not raw:
        return DEFAULT_THREAD_COUNTS
    return [int(x) for x in raw.split(",") if x.strip()]


def _parse_runs_per_config() -> int:
    return int(os.getenv("RUNS_PER_CONFIG", str(DEFAULT_RUNS_PER_CONFIG)))


def _parse_warmup_runs() -> int:
    return int(os.getenv("WARMUP_RUNS", str(DEFAULT_WARMUP_RUNS)))


def _resolve_corpus_path() -> Path:
    name = os.getenv("CORPUS_FILE", DEFAULT_CORPUS)
    candidate = RESOURCES_DIR / name
    return candidate


def _build_default_gliner_configs() -> Dict[str, dict]:
    """Build GLiNER pii_type_configs bypassing the database."""
    label_to_type = {
        "email address": "EMAIL",
        "phone number": "PHONE",
        "person name": "PERSON_NAME",
        "system account name": "USERNAME",
        "date of birth": "DATE_OF_BIRTH",
        "age": "AGE",
        "gender": "GENDER",
        "credit card number": "CREDIT_CARD",
        "financial institution account number": "BANK_ACCOUNT",
        "international banking identifier": "IBAN",
        "routing number": "ROUTING_NUMBER",
        "tax identifier": "TAX_ID",
        "cryptocurrency wallet address": "CRYPTO_WALLET",
        "social insurance number": "SSN",
        "passport number": "PASSPORT",
        "driver license identification": "DRIVER_LICENSE",
        "national id number": "NATIONAL_ID",
        "street address": "ADDRESS",
        "city": "CITY",
        "state": "STATE",
        "country": "COUNTRY",
        "postal code": "ZIP_CODE",
        "account password or PIN code": "PASSWORD",
        "API authentication credential": "API_KEY",
        "access token": "ACCESS_TOKEN",
        "secret key": "SECRET_KEY",
        "database connection string": "CONNECTION_STRING",
        "IPv4 or IPv6 network address": "IP_ADDRESS",
        "Swiss AVS 13-digit personal number": "AVS_NUMBER",
        "mac address": "MAC_ADDRESS",
        "url": "URL",
        "medical file number": "MEDICAL_RECORD",
        "health insurance number": "HEALTH_INSURANCE",
        "medical condition": "MEDICAL_CONDITION",
        "medication": "MEDICATION",
    }
    configs: Dict[str, dict] = {}
    for label, pii_type in label_to_type.items():
        configs[f"GLINER:{pii_type}"] = {
            "detector": "GLINER",
            "detector_label": label,
            "enabled": True,
            "threshold": DETECTION_THRESHOLD,
            "category": "GENERIC",
        }
    return configs


@pytest.fixture(scope="module")
def corpus_text() -> str:
    path = _resolve_corpus_path()
    if not path.exists():
        pytest.skip(f"Corpus file not found: {path}")
    text = path.read_text(encoding="utf-8")
    if len(text) < 500:
        pytest.skip(f"Corpus too small: {len(text)} chars at {path}")
    _say(f"Corpus: {path.name} ({len(text):,} chars)")
    return text


@pytest.fixture(scope="module")
def pii_configs() -> Dict[str, dict]:
    cfgs = _build_default_gliner_configs()
    _say(f"GLiNER configs built: {len(cfgs)} labels")
    return cfgs


@pytest.fixture(scope="module")
def detector() -> MultiPassGlinerDetector:
    _say("Loading MultiPassGlinerDetector (this may take 10-30s on first run)...")
    t0 = time.perf_counter()
    config = DetectionConfig()
    det = MultiPassGlinerDetector(config=config)
    det.download_model()
    det.load_model()
    _say(f"Detector loaded in {time.perf_counter() - t0:.1f}s")
    return det


def _run_single(
    detector: MultiPassGlinerDetector,
    text: str,
    pii_configs: Dict[str, dict],
) -> tuple[float, int]:
    start = time.perf_counter()
    entities = detector.detect_pii(
        text,
        threshold=DETECTION_THRESHOLD,
        pii_type_configs=pii_configs,
    )
    elapsed = time.perf_counter() - start
    return elapsed, len(entities)


def _benchmark_at_threads(
    detector: MultiPassGlinerDetector,
    text: str,
    pii_configs: Dict[str, dict],
    num_threads: int,
    runs: int,
) -> dict:
    torch.set_num_threads(num_threads)
    effective = torch.get_num_threads()
    _say(f"  -> set_num_threads({num_threads}) -> effective={effective}")

    _say(f"  -> warmup at K={num_threads}...")
    w_start = time.perf_counter()
    _, n_warmup = _run_single(detector, text, pii_configs)
    _say(f"     warmup done in {time.perf_counter() - w_start:.2f}s "
         f"({n_warmup} entities)")

    latencies: List[float] = []
    entity_counts: List[int] = []
    for i in range(runs):
        elapsed, n_entities = _run_single(detector, text, pii_configs)
        latencies.append(elapsed)
        entity_counts.append(n_entities)
        chars_per_sec = len(text) / elapsed if elapsed > 0 else 0.0
        _say(f"  -> run {i + 1}/{runs}: {elapsed:.2f}s, "
             f"{n_entities} entities, {chars_per_sec:,.0f} chars/sec")

    avg = statistics.fmean(latencies)
    chars_per_sec = len(text) / avg if avg > 0 else 0.0
    return {
        "requested_threads": num_threads,
        "effective_threads": effective,
        "runs": runs,
        "avg_s": avg,
        "median_s": statistics.median(latencies),
        "min_s": min(latencies),
        "max_s": max(latencies),
        "chars_per_sec": chars_per_sec,
        "entities_avg": statistics.fmean(entity_counts),
        "entities_min": min(entity_counts),
        "entities_max": max(entity_counts),
    }


def _print_report(
    results: List[dict],
    corpus_chars: int,
    corpus_name: str,
    runs: int,
    warmups: int,
) -> None:
    print("", flush=True)
    print("=" * 100, flush=True)
    print("THROUGHPUT BENCHMARK: chars/sec vs torch.set_num_threads(K)", flush=True)
    print("=" * 100, flush=True)
    print(f"Corpus           : {corpus_name} ({corpus_chars:,} chars)", flush=True)
    print(f"Runs per K       : {runs} (+ 1 per-K warmup, "
          f"{warmups} initial warmups)", flush=True)
    print(f"CPU cores (logic): {os.cpu_count()}", flush=True)
    print("", flush=True)

    header = (
        f"{'K req':>6} {'K eff':>6} {'avg(s)':>9} {'med(s)':>9} {'min(s)':>9} "
        f"{'max(s)':>9} {'chars/sec':>12} {'entities':>10}"
    )
    print(header, flush=True)
    print("-" * len(header), flush=True)
    for r in results:
        print(
            f"{r['requested_threads']:>6} {r['effective_threads']:>6} "
            f"{r['avg_s']:>9.3f} {r['median_s']:>9.3f} {r['min_s']:>9.3f} "
            f"{r['max_s']:>9.3f} {r['chars_per_sec']:>12,.0f} "
            f"{r['entities_avg']:>10.1f}",
            flush=True,
        )
    print("-" * len(header), flush=True)

    baseline = next((r for r in results if r["requested_threads"] == 1), results[0])
    best = max(results, key=lambda r: r["chars_per_sec"])
    speedup = (
        best["chars_per_sec"] / baseline["chars_per_sec"]
        if baseline["chars_per_sec"] > 0
        else float("inf")
    )
    print(
        f"Best K={best['requested_threads']} -> "
        f"{best['chars_per_sec']:,.0f} chars/sec "
        f"(speedup x{speedup:.2f} vs K={baseline['requested_threads']})",
        flush=True,
    )
    print("=" * 100, flush=True)


@pytest.mark.benchmark
@pytest.mark.slow
def test_throughput_vs_torch_threads(detector, corpus_text, pii_configs, capsys):
    """Measure chars/sec across several torch.set_num_threads() values.

    Live-prints progress so it doesn't look hung. Defaults to a small
    corpus + 3 K values + 2 runs for fast iteration. Override via env
    vars for a heavier bench.
    """
    thread_counts = _parse_thread_counts()
    runs = _parse_runs_per_config()
    warmups = _parse_warmup_runs()
    corpus_name = _resolve_corpus_path().name

    with capsys.disabled():
        _say(f"Bench plan: K={thread_counts}, runs/K={runs}, "
             f"initial warmups={warmups}, threshold={DETECTION_THRESHOLD}")
        _say("Starting initial warmup(s) — first run is always slow "
             "(BLAS init + kernel JIT)...")
        for i in range(warmups):
            t0 = time.perf_counter()
            _, n = _run_single(detector, corpus_text, pii_configs)
            _say(f"  Initial warmup {i + 1}/{warmups} done in "
                 f"{time.perf_counter() - t0:.2f}s ({n} entities)")

        results: List[dict] = []
        for k in thread_counts:
            _say(f"--- K={k} ---")
            result = _benchmark_at_threads(detector, corpus_text, pii_configs,
                                           k, runs)
            results.append(result)

        _print_report(results, len(corpus_text), corpus_name, runs, warmups)

    for r in results:
        assert r["entities_avg"] >= 0, (
            f"K={r['requested_threads']}: invalid entity count"
        )
        assert r["avg_s"] > 0, f"K={r['requested_threads']}: zero elapsed time"
