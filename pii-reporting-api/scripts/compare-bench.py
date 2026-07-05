"""Compare 2 bench TSV files produced by FileBenchRecorderAdapter.

Usage:
    python compare-bench.py bench-pytorch.tsv bench-onnx.tsv

Outputs aggregated metrics per label, plus a side-by-side comparison:
- total items, total chars, total findings
- mean / median / p95 duration (ms)
- mean chars/sec (weighted by chars)
- per-space breakdown if both files share spaces

Tab-separated columns expected (header in row 1):
    timestamp label scanId spaceKey pageId itemKind charCount durationMs charsPerSec findings
"""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def load(path: Path):
    rows = []
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for r in reader:
            try:
                rows.append({
                    "label": r["label"],
                    "spaceKey": r["spaceKey"],
                    "itemKind": r["itemKind"],
                    "charCount": int(r["charCount"]),
                    "durationMs": int(r["durationMs"]),
                    "findings": int(r["findings"]),
                })
            except (KeyError, ValueError):
                continue
    return rows


def aggregate(rows):
    if not rows:
        return None
    durations = [r["durationMs"] for r in rows]
    durations_sorted = sorted(durations)
    total_chars = sum(r["charCount"] for r in rows)
    total_duration_s = sum(durations) / 1000.0
    return {
        "items": len(rows),
        "totalChars": total_chars,
        "totalFindings": sum(r["findings"] for r in rows),
        "meanMs": statistics.mean(durations),
        "medianMs": statistics.median(durations),
        "p95Ms": durations_sorted[max(0, int(len(durations) * 0.95) - 1)],
        "weightedCharsPerSec": (total_chars / total_duration_s) if total_duration_s > 0 else 0.0,
        "label": rows[0]["label"],
    }


def per_space(rows):
    by_space = defaultdict(list)
    for r in rows:
        by_space[r["spaceKey"]].append(r)
    return {sk: aggregate(items) for sk, items in by_space.items()}


def fmt(stats):
    return (
        f"  items           {stats['items']}\n"
        f"  totalChars      {stats['totalChars']:,}\n"
        f"  totalFindings   {stats['totalFindings']:,}\n"
        f"  mean ms         {stats['meanMs']:.1f}\n"
        f"  median ms       {stats['medianMs']:.1f}\n"
        f"  p95 ms          {stats['p95Ms']:.1f}\n"
        f"  chars/sec (W)   {stats['weightedCharsPerSec']:,.0f}"
    )


def main():
    if len(sys.argv) != 3:
        print("usage: compare-bench.py <baseline.tsv> <candidate.tsv>", file=sys.stderr)
        sys.exit(2)
    baseline_path, candidate_path = Path(sys.argv[1]), Path(sys.argv[2])
    base = load(baseline_path)
    cand = load(candidate_path)
    bs, cs = aggregate(base), aggregate(cand)
    if bs is None or cs is None:
        print("Empty input file(s).", file=sys.stderr)
        sys.exit(1)

    print(f"\n=== {bs['label']} (baseline)\n{fmt(bs)}")
    print(f"\n=== {cs['label']} (candidate)\n{fmt(cs)}")

    speedup = bs["meanMs"] / cs["meanMs"] if cs["meanMs"] > 0 else float("inf")
    throughput = cs["weightedCharsPerSec"] / bs["weightedCharsPerSec"] if bs["weightedCharsPerSec"] > 0 else float("inf")
    findings_delta = cs["totalFindings"] - bs["totalFindings"]
    findings_pct = (findings_delta / bs["totalFindings"] * 100.0) if bs["totalFindings"] else 0.0

    print(f"\n=== diff candidate vs baseline")
    print(f"  speedup (mean ms)         x{speedup:.2f}")
    print(f"  throughput (chars/sec)    x{throughput:.2f}")
    print(f"  total findings delta      {findings_delta:+d} ({findings_pct:+.1f}%)")

    base_spaces = per_space(base)
    cand_spaces = per_space(cand)
    common = sorted(set(base_spaces) & set(cand_spaces))
    if common:
        print(f"\n=== per-space (common: {len(common)})")
        print(f"  {'spaceKey':<24} base ms  cand ms  speedup  base findings  cand findings")
        for sk in common:
            b, c = base_spaces[sk], cand_spaces[sk]
            sp = b["meanMs"] / c["meanMs"] if c["meanMs"] > 0 else float("inf")
            print(f"  {sk:<24} {b['meanMs']:7.1f}  {c['meanMs']:7.1f}  x{sp:5.2f}   {b['totalFindings']:>13}  {c['totalFindings']:>13}")


if __name__ == "__main__":
    main()
