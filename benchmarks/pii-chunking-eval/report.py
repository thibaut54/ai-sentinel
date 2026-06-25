"""Aggregate results into metrics.{json,csv} and a Pareto report.md.

Track A (quality): for each target document length, configs are aggregated across
reps by summing tp/fp/fn (micro-average) and averaging inference time. We then
compute the F1-vs-time Pareto front, flag the whole-doc baseline, and recommend
the *knee* — the Pareto point closest to the ideal corner (max F1, min time) in
normalised space. Track B (latency only): a time/throughput table per length.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def write_reports(out_dir: Path, results: List[dict], failures: List[dict], meta: dict) -> None:
    track_a = [r for r in results if r["track"] == "A"]
    track_b = [r for r in results if r["track"] == "B"]

    a_groups = _aggregate_track_a(track_a)
    b_groups = _aggregate_track_b(track_b)

    metrics = {
        "meta": meta,
        "track_a": a_groups,
        "track_b": b_groups,
        "failures": len(failures),
        "failed_units": sorted({f.get("unit_key", "") for f in failures}),
    }
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2, ensure_ascii=False),
                                          encoding="utf-8")
    _write_csv(out_dir / "metrics.csv", a_groups, b_groups)
    (out_dir / "report.md").write_text(_render_md(metrics, a_groups, b_groups), encoding="utf-8")


# --------------------------------------------------------------------------
# Aggregation
# --------------------------------------------------------------------------
def _aggregate_track_a(rows: List[dict]) -> Dict[str, List[dict]]:
    by_len: Dict[int, Dict[str, List[dict]]] = {}
    for r in rows:
        by_len.setdefault(r["doc_tokens"], {}).setdefault(r["config_id"], []).append(r)

    out: Dict[str, List[dict]] = {}
    for doc_tokens in sorted(by_len):
        configs = []
        for config_id, reps in by_len[doc_tokens].items():
            tp = sum(x["metrics"]["strict"]["tp"] for x in reps)
            fp = sum(x["metrics"]["strict"]["fp"] for x in reps)
            fn = sum(x["metrics"]["strict"]["fn"] for x in reps)
            ta_tp = sum(x["metrics"]["type_agnostic"]["tp"] for x in reps)
            ta_fn = sum(x["metrics"]["type_agnostic"]["fn"] for x in reps)
            time_ms = _mean(x["sum_latency_ms"] for x in reps)
            tokens = _mean(x["prompt_tokens"] + x["completion_tokens"] for x in reps)
            configs.append({
                "config_id": config_id,
                "boundary": reps[0]["boundary"], "size": reps[0]["size"],
                "overlap": reps[0]["overlap"],
                "f1": _f1(tp, fp, fn), "precision": _p(tp, fp), "recall": _r(tp, fn),
                "tp": tp, "fp": fp, "fn": fn,
                "type_agnostic_recall": _r(ta_tp, ta_fn),
                "time_ms": round(time_ms, 1),
                "tokens": round(tokens, 1),
                "throughput": round(tokens / (time_ms / 1000.0), 1) if time_ms > 0 else 0.0,
                "n_chunks": _mean(x["n_chunks"] for x in reps),
                "n_requests": _mean(x["n_requests"] for x in reps),
                "truncated": any(x["truncated"] for x in reps),
                "reps": len(reps),
            })
        _mark_pareto_and_knee(configs)
        out[str(doc_tokens)] = configs
    return out


def _aggregate_track_b(rows: List[dict]) -> Dict[str, List[dict]]:
    by_len: Dict[int, Dict[str, List[dict]]] = {}
    for r in rows:
        by_len.setdefault(r["doc_tokens"], {}).setdefault(r["config_id"], []).append(r)
    out: Dict[str, List[dict]] = {}
    for doc_tokens in sorted(by_len):
        configs = []
        for config_id, reps in by_len[doc_tokens].items():
            time_ms = _mean(x["sum_latency_ms"] for x in reps)
            tokens = _mean(x["prompt_tokens"] + x["completion_tokens"] for x in reps)
            configs.append({
                "config_id": config_id,
                "boundary": reps[0]["boundary"], "size": reps[0]["size"],
                "overlap": reps[0]["overlap"],
                "time_ms": round(time_ms, 1),
                "tokens": round(tokens, 1),
                "throughput": round(tokens / (time_ms / 1000.0), 1) if time_ms > 0 else 0.0,
                "n_chunks": _mean(x["n_chunks"] for x in reps),
                "truncated": any(x["truncated"] for x in reps),
            })
        configs.sort(key=lambda c: c["time_ms"])
        out[str(doc_tokens)] = configs
    return out


def _mark_pareto_and_knee(configs: List[dict]) -> None:
    """Flag Pareto-optimal points (max F1, min time) and the knee among them."""
    for c in configs:
        dominated = any(
            o is not c and o["f1"] >= c["f1"] and o["time_ms"] <= c["time_ms"]
            and (o["f1"] > c["f1"] or o["time_ms"] < c["time_ms"])
            for o in configs)
        c["pareto"] = not dominated
        c["knee"] = False
    pareto = [c for c in configs if c["pareto"]]
    if not pareto:
        return
    f1s = [c["f1"] for c in pareto]
    times = [c["time_ms"] for c in pareto]
    f1_min, f1_max = min(f1s), max(f1s)
    t_min, t_max = min(times), max(times)

    def dist(c: dict) -> float:
        # Distance to the ideal corner (max F1, min time) in normalised space.
        fn = (c["f1"] - f1_min) / (f1_max - f1_min) if f1_max > f1_min else 1.0
        tn = (c["time_ms"] - t_min) / (t_max - t_min) if t_max > t_min else 0.0
        return ((1.0 - fn) ** 2 + tn ** 2) ** 0.5

    min(pareto, key=dist)["knee"] = True


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------
def _write_csv(path: Path, a_groups: Dict[str, List[dict]], b_groups: Dict[str, List[dict]]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["track", "doc_tokens", "config_id", "boundary", "size", "overlap",
                    "f1", "precision", "recall", "tp", "fp", "fn",
                    "time_ms", "tokens", "throughput", "n_chunks", "n_requests",
                    "truncated", "pareto", "knee"])
        for doc_tokens, configs in a_groups.items():
            for c in configs:
                w.writerow(["A", doc_tokens, c["config_id"], c["boundary"], c["size"],
                            c["overlap"], c["f1"], c["precision"], c["recall"],
                            c["tp"], c["fp"], c["fn"], c["time_ms"], c["tokens"],
                            c["throughput"], c["n_chunks"], c["n_requests"],
                            c["truncated"], c["pareto"], c["knee"]])
        for doc_tokens, configs in b_groups.items():
            for c in configs:
                w.writerow(["B", doc_tokens, c["config_id"], c["boundary"], c["size"],
                            c["overlap"], "", "", "", "", "", "", c["time_ms"],
                            c["tokens"], c["throughput"], c["n_chunks"], "",
                            c["truncated"], "", ""])


def _render_md(metrics: dict, a_groups: Dict[str, List[dict]], b_groups: Dict[str, List[dict]]) -> str:
    m = metrics["meta"]
    lines: List[str] = []
    lines.append("# Chunking strategy benchmark — Ministral-3B-PII-Preview\n")
    lines.append(f"- model: `{m.get('model')}`  ·  tokenizer: `{m.get('tokenizer')}`")
    lines.append(f"- endpoint: `{m.get('base_url')}`")
    lines.append(f"- generated units: {m.get('n_units')}  ·  failures: {metrics['failures']}\n")

    lines.append("## Track A — quality vs inference time (Pareto)\n")
    if not a_groups:
        lines.append("_No Track A results._\n")
    for doc_tokens, configs in a_groups.items():
        knee = next((c for c in configs if c["knee"]), None)
        base = next((c for c in configs if c["config_id"] == "whole"), None)
        lines.append(f"### Document length: {doc_tokens} tokens\n")
        if base:
            lines.append(f"Baseline (whole doc): F1 **{base['f1']:.3f}**, "
                         f"{base['time_ms']:.0f} ms"
                         + ("  ⚠️ truncated by server n_ctx" if base["truncated"] else "") + "\n")
        if knee:
            lines.append(f"**Recommended (knee): `{knee['config_id']}`** — "
                         f"F1 {knee['f1']:.3f}, {knee['time_ms']:.0f} ms, "
                         f"{knee['throughput']:.0f} tok/s"
                         + (f", ΔF1 vs baseline {knee['f1'] - base['f1']:+.3f}" if base else "")
                         + (f", {base['time_ms'] / knee['time_ms']:.1f}× faster" if base and knee['time_ms'] > 0 else "")
                         + "\n")
        lines.append("| config | F1 | P | R | time(ms) | tok/s | chunks | pareto | knee |")
        lines.append("|---|---|---|---|---|---|---|---|---|")
        for c in sorted(configs, key=lambda x: (-x["f1"], x["time_ms"])):
            lines.append(
                f"| `{c['config_id']}` | {c['f1']:.3f} | {c['precision']:.3f} | "
                f"{c['recall']:.3f} | {c['time_ms']:.0f} | {c['throughput']:.0f} | "
                f"{c['n_chunks']:.0f} | {'✓' if c['pareto'] else ''} | "
                f"{'★' if c['knee'] else ''} |")
        lines.append("")

    lines.append("## Track B — latency / throughput on real corpus (no quality)\n")
    if not b_groups:
        lines.append("_No Track B results._\n")
    for doc_tokens, configs in b_groups.items():
        lines.append(f"### Document length: {doc_tokens} tokens\n")
        lines.append("| config | time(ms) | tok/s | chunks | truncated |")
        lines.append("|---|---|---|---|---|")
        for c in configs:
            lines.append(f"| `{c['config_id']}` | {c['time_ms']:.0f} | "
                         f"{c['throughput']:.0f} | {c['n_chunks']:.0f} | "
                         f"{'⚠️' if c['truncated'] else ''} |")
        lines.append("")

    if metrics["failed_units"]:
        lines.append("## Failed units (re-run with `--retry-failures`)\n")
        for u in metrics["failed_units"]:
            if u:
                lines.append(f"- `{u}`")
        lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Small numeric helpers
# --------------------------------------------------------------------------
def _mean(values) -> float:
    vals = list(values)
    return sum(vals) / len(vals) if vals else 0.0


def _p(tp: int, fp: int) -> float:
    return 0.0 if (tp + fp) == 0 else round(tp / (tp + fp), 4)


def _r(tp: int, fn: int) -> float:
    return 0.0 if (tp + fn) == 0 else round(tp / (tp + fn), 4)


def _f1(tp: int, fp: int, fn: int) -> float:
    p, r = _p(tp, fp), _r(tp, fn)
    return 0.0 if (p + r) == 0 else round(2 * p * r / (p + r), 4)
