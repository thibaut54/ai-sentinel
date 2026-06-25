"""Chunking strategy benchmark for the Ministral-3B-PII-Preview extractor.

Goal: find the chunking strategy that minimises inference time without degrading
PII extraction quality (P/R/F1). Two tracks:

* Track A (quality): mega-documents built from the gold set, scored value-level.
* Track B (latency): real corpus files sliced to the same lengths, timed only.

The run is replayable (temperature=0 + content-addressed response cache),
resumable (per-unit done-set + chunk-level cache), and logs failed units for
re-analysis (``--retry-failures``).

Examples
--------
    # Offline plumbing check (no endpoint, stub detector) — fastest sanity test:
    python chunk_bench.py --no-llm --smoke

    # Real short smoke against the live model (~1-2 min once warm):
    python chunk_bench.py --smoke --base-url http://172.22.22.63:1234/v1

    # Full large sweep (long, fully resumable):
    python chunk_bench.py --base-url http://172.22.22.63:1234/v1

    # Re-run only the units that failed previously:
    python chunk_bench.py --retry-failures --base-url http://172.22.22.63:1234/v1
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

import chunkers
import corpora
import report as report_mod
import scoring
from corpora import Doc
from llmclient import LlmClient, StubClient
from merging import ConceptMap, merge
from mistral_tokenizer import Tokenizer
from store import Store, call_key, unit_key

HERE = Path(__file__).resolve().parent
DATASET_DIR = HERE.parent / "pii-dataset-eval"
DEFAULT_CORPUS = HERE.parent.parent / "pii-reporting-api" / "src" / "test" / "resources" / "corpus"

DEFAULT_BASE_URL = os.environ.get("MINISTRAL_BASE_URL", "http://172.22.22.63:1234/v1")
DEFAULT_MODEL = os.environ.get("MINISTRAL_MODEL", "ministral-3b-pii-preview@q8_0")
DEFAULT_TOKENIZER = os.environ.get("MINISTRAL_TOKENIZER", "OpenMed/Ministral-3B-PII-Preview")


@dataclass(frozen=True)
class Config:
    id: str
    boundary: str
    size: Optional[int]
    overlap: float


# --------------------------------------------------------------------------
# CLI / presets
# --------------------------------------------------------------------------
def parse_args(argv: List[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Chunking strategy benchmark for Ministral PII.")
    p.add_argument("--smoke", action="store_true", help="Tiny preset to validate the harness.")
    p.add_argument("--quick", action="store_true", help="Medium preset for a fast real signal.")
    p.add_argument("--no-llm", action="store_true", help="Use the offline stub detector (no endpoint).")
    p.add_argument("--retry-failures", action="store_true", help="Re-run only previously failed units.")
    p.add_argument("--track", choices=["A", "B", "both"], default="both")
    p.add_argument("--doc-tokens", type=_int_list, help="Target document lengths (tokens).")
    p.add_argument("--sizes", type=_int_list, help="Chunk sizes (tokens).")
    p.add_argument("--overlaps", type=_float_list, help="Overlap fractions, e.g. 0,0.1,0.2.")
    p.add_argument("--boundaries", type=_str_list, help="char,token,line,sentence,paragraph.")
    p.add_argument("--reps", type=int, help="Mega-docs per length (variance).")
    p.add_argument("--max-corpus-files", type=int, help="Track B: number of corpus files.")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--gold-dir", type=Path, default=DATASET_DIR / "gold")
    p.add_argument("--mapping", type=Path, default=DATASET_DIR / "label_mapping.toml")
    p.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS)
    p.add_argument("--base-url", default=DEFAULT_BASE_URL)
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    p.add_argument("--max-tokens", type=int, default=2048)
    p.add_argument("--request-timeout", type=float, default=120.0)
    p.add_argument("--retries", type=int, default=3)
    p.add_argument("--no-json-schema", action="store_true", help="Disable response_format json_schema.")
    p.add_argument("--allow-tokenizer-fallback", action="store_true",
                   help="Fall back to a char-ratio token estimate if the real tokenizer is unavailable.")
    p.add_argument("--out", type=Path, default=HERE / "out")
    return p.parse_args(argv)


def apply_preset(a: argparse.Namespace) -> None:
    if a.smoke:
        preset = dict(doc_tokens=[2048, 8192], sizes=[1024, 4096], overlaps=[0.0, 0.2],
                      boundaries=["token", "line"], reps=1, max_corpus_files=1)
    elif a.quick:
        preset = dict(doc_tokens=[16384, 65536], sizes=[2048, 8192, 32768], overlaps=[0.0, 0.2],
                      boundaries=["token", "line", "paragraph"], reps=1, max_corpus_files=2)
    else:  # full "large sweep"
        preset = dict(doc_tokens=[16384, 65536, 262144],
                      sizes=[1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072],
                      overlaps=[0.0, 0.1, 0.2],
                      boundaries=list(chunkers.BOUNDARIES), reps=1, max_corpus_files=3)
    for key, value in preset.items():
        if getattr(a, key) is None:
            setattr(a, key, value)
    if a.smoke and a.out == HERE / "out":
        a.out = HERE / "out-smoke"


def _int_list(s: str) -> List[int]:
    return [int(x) for x in s.split(",") if x.strip()]


def _float_list(s: str) -> List[float]:
    return [float(x) for x in s.split(",") if x.strip()]


def _str_list(s: str) -> List[str]:
    return [x.strip() for x in s.split(",") if x.strip()]


# --------------------------------------------------------------------------
# Matrix
# --------------------------------------------------------------------------
def build_configs(sizes: List[int], overlaps: List[float], boundaries: List[str],
                  doc_len_tokens: int) -> List[Config]:
    configs = [Config("whole", "whole", None, 0.0)]
    for size in sizes:
        if size >= doc_len_tokens:
            continue  # collapses to the whole-doc baseline
        for ov in overlaps:
            for b in boundaries:
                configs.append(Config(f"{b}-s{size}-o{int(round(ov * 100))}", b, size, ov))
    return configs


# --------------------------------------------------------------------------
# Run one (doc, config) unit
# --------------------------------------------------------------------------
def run_unit(track: str, doc: Doc, cfg: Config, client, tok: Tokenizer, cmap: ConceptMap,
             store: Store, model: str, max_tokens: int, json_schema: bool):
    key = unit_key(track, doc.id, cfg.id)
    size = cfg.size if cfg.size is not None else 10 ** 9
    chunk_list = chunkers.chunk(doc.text, tok, cfg.boundary, size, cfg.overlap)

    wall0 = time.perf_counter()
    entities_per_chunk = []
    sum_latency = 0.0
    prompt_tokens = 0
    completion_tokens = 0
    truncated = False
    for ch in chunk_list:
        local_tokens = tok.count(ch.text)
        ckey = call_key(model, max_tokens, json_schema, ch.text)
        res = store.cache_get(ckey)
        if res is None or not res.ok:
            res = client.extract(ch.text, local_tokens)
            if res.ok:
                store.cache_put(ckey, res)
        if not res.ok:
            failure = {"unit_key": key, "track": track, "doc_id": doc.id,
                       "config_id": cfg.id, "chunk_index": ch.index, "reason": res.error}
            return None, failure
        entities_per_chunk.append(res.entities)
        sum_latency += res.latency_ms
        prompt_tokens += res.prompt_tokens
        completion_tokens += res.completion_tokens
        if local_tokens > 0 and res.prompt_tokens > 0 and res.prompt_tokens < 0.9 * local_tokens:
            truncated = True
    wall_ms = (time.perf_counter() - wall0) * 1000.0

    metrics = None
    merge_stats = None
    if doc.gold is not None:
        preds, stats = merge(entities_per_chunk, doc.text, cmap)
        metrics = scoring.score_doc(doc.gold, preds).to_dict()
        merge_stats = {"raw": stats.raw, "kept": stats.kept,
                       "dropped_ignore": stats.dropped_ignore,
                       "hallucinated": stats.hallucinated, "unknown": sorted(stats.unknown)}

    row = {
        "unit_key": key, "track": track, "doc_id": doc.id,
        "doc_tokens": doc.target_tokens, "actual_tokens": doc.actual_tokens,
        "capped": doc.capped, "config_id": cfg.id, "boundary": cfg.boundary,
        "size": cfg.size, "overlap": cfg.overlap,
        "n_chunks": len(chunk_list), "n_requests": len(chunk_list),
        "sum_latency_ms": round(sum_latency, 1), "wall_ms": round(wall_ms, 1),
        "prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens,
        "truncated": truncated, "metrics": metrics, "merge": merge_stats,
    }
    return row, None


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
def main(argv: List[str]) -> int:
    args = parse_args(argv)
    apply_preset(args)
    json_schema = not args.no_json_schema

    tok = Tokenizer.load(args.tokenizer, allow_fallback=args.allow_tokenizer_fallback or args.no_llm)
    cmap = ConceptMap.load(args.mapping, args.model)
    client = StubClient() if args.no_llm else LlmClient(
        args.base_url, args.model, args.max_tokens, args.request_timeout, args.retries, json_schema)
    # Namespace the response cache so stub (--no-llm) responses can never be
    # reused by a real run sharing the same --out directory.
    cache_model = ("STUB:" if args.no_llm else "") + args.model

    _banner(args, tok)
    if not args.no_llm:
        _preflight(client, args.model)

    # Build documents.
    docs: List[tuple] = []  # (track, Doc)
    if args.track in ("A", "both"):
        gold = corpora.load_gold(args.gold_dir)
        if not gold:
            print(f"[error] no gold found in {args.gold_dir} — run build_datasets.py first.")
            return 2
        for d in corpora.build_megadocs(gold, tok, args.doc_tokens, args.reps, args.seed):
            docs.append(("A", d))
            if d.capped:
                print(f"[warn] mega-doc {d.id} capped at {d.actual_tokens} tokens "
                      f"(target {d.target_tokens}) — gold exhausted; rebuild with larger --n.")
    if args.track in ("B", "both"):
        if args.corpus_dir.exists():
            for d in corpora.build_corpus_docs(args.corpus_dir, tok, args.doc_tokens, args.max_corpus_files):
                docs.append(("B", d))
        else:
            print(f"[warn] corpus dir {args.corpus_dir} not found — skipping Track B.")

    store = Store(args.out)
    retry_set = store.retry_units() if args.retry_failures else None

    # Plan units.
    planned = []
    for track, d in docs:
        for cfg in build_configs(args.sizes, args.overlaps, args.boundaries, max(1, d.actual_tokens)):
            key = unit_key(track, d.id, cfg.id)
            if store.is_done(key):
                continue
            if retry_set is not None and key not in retry_set:
                continue
            planned.append((track, d, cfg))

    total = len(planned)
    print(f"[plan] {total} units to run "
          f"({len(docs)} docs × strategies; resume skips completed). out={args.out}\n")

    done_ok = 0
    failed = 0
    t_start = time.perf_counter()
    for i, (track, d, cfg) in enumerate(planned, 1):
        row, failure = run_unit(track, d, cfg, client, tok, cmap, store, cache_model,
                                args.max_tokens, json_schema)
        if failure is not None:
            store.add_failure(failure)
            failed += 1
            print(f"[{i}/{total}] FAIL {track} {d.id} {cfg.id}: {failure['reason']}")
            continue
        store.add_result(row)
        done_ok += 1
        _log_unit(i, total, track, row, t_start)

    failures = store.load_failures()
    meta = {"model": args.model, "tokenizer": tok.name, "base_url": args.base_url,
            "tokenizer_real": tok.is_real, "n_units": done_ok,
            "doc_tokens": args.doc_tokens, "sizes": args.sizes,
            "overlaps": args.overlaps, "boundaries": args.boundaries,
            "preset": "smoke" if args.smoke else ("quick" if args.quick else "full"),
            "stub": args.no_llm}
    report_mod.write_reports(args.out, store.load_results(), failures, meta)

    print(f"\n[done] ok={done_ok} failed={failed} "
          f"(failed units in {args.out / 'failures.jsonl'}; retry with --retry-failures)")
    print(f"[report] {args.out / 'report.md'}")
    if args.smoke:
        return _smoke_verdict(store.load_results())
    return 0


def _banner(args: argparse.Namespace, tok: Tokenizer) -> None:
    print("=" * 72)
    print("Chunking benchmark — Ministral-3B-PII-Preview")
    print(f"  preset      : {'smoke' if args.smoke else ('quick' if args.quick else 'full')}"
          f"{'  [NO-LLM stub]' if args.no_llm else ''}")
    print(f"  tokenizer   : {tok.name} (real={tok.is_real})")
    print(f"  doc lengths : {args.doc_tokens} tokens")
    print(f"  chunk sizes : {args.sizes}")
    print(f"  overlaps    : {args.overlaps}")
    print(f"  boundaries  : {args.boundaries}")
    print(f"  endpoint    : {args.base_url}  model={args.model}")
    print("=" * 72)
    if not tok.is_real:
        print("[warn] using a CHAR-APPROX tokenizer — token budgets are approximate.")


def _preflight(client: LlmClient, model: str) -> None:
    try:
        ids = client.list_models()
    except Exception as exc:  # noqa: BLE001
        print(f"[warn] /models preflight failed ({exc}); the endpoint may be cold or down.")
        return
    if model not in ids:
        print(f"[warn] model '{model}' not in served models {sorted(ids)} — "
              f"check the @quant suffix; calls will likely fail.")
    else:
        print(f"[ok] endpoint reachable, model '{model}' served.")


def _log_unit(i: int, total: int, track: str, row: dict, t_start: float) -> None:
    elapsed = time.perf_counter() - t_start
    eta = (elapsed / i) * (total - i)
    extra = ""
    if row["metrics"] is not None:
        extra = f" F1={row['metrics']['strict']['f1']:.3f}"
    trunc = " ⚠TRUNC" if row["truncated"] else ""
    print(f"[{i}/{total}] {track} {row['doc_id']} {row['config_id']} "
          f"chunks={row['n_chunks']} {row['sum_latency_ms']:.0f}ms{extra}{trunc} "
          f"(ETA {eta / 60:.1f}m)")
    sys.stdout.flush()


def _smoke_verdict(results: List[dict]) -> int:
    track_a = [r for r in results if r["track"] == "A" and r["metrics"]]
    if not results:
        print("[smoke] FAIL — no units completed.")
        return 1
    scored = sum(r["metrics"]["strict"]["tp"] + r["metrics"]["strict"]["fp"]
                 + r["metrics"]["strict"]["fn"] for r in track_a)
    if track_a and scored == 0:
        print("[smoke] FAIL — Track A produced no predictions and no gold matched.")
        return 1
    print(f"[smoke] PASS — {len(results)} units, Track A scored {scored} (tp+fp+fn).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
