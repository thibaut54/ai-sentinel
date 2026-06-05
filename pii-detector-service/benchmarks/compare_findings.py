"""Diff two findings.jsonl produced by bench_scaling.py (precision guard).

Key = (file, start, end, pii_type, source). Reports entities missing from /
added by the candidate vs the baseline, plus score drifts above --score-eps.
Exit code 0 when the entity SETS are identical (scores may drift within eps).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load(path: Path) -> dict:
    out = {}
    with path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            e = json.loads(line)
            out[(e['file'], e['start'], e['end'], e['pii_type'], e['source'])] = e
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('baseline')
    ap.add_argument('candidate')
    ap.add_argument('--score-eps', type=float, default=0.02)
    ap.add_argument('--max-print', type=int, default=15)
    args = ap.parse_args()

    base = load(Path(args.baseline))
    cand = load(Path(args.candidate))

    missing = sorted(set(base) - set(cand))
    added = sorted(set(cand) - set(base))
    common = set(base) & set(cand)
    drifts = sorted(
        (k for k in common
         if abs(base[k]['score'] - cand[k]['score']) > args.score_eps),
        key=lambda k: -abs(base[k]['score'] - cand[k]['score']))

    print(f"baseline : {len(base)} findings ({args.baseline})")
    print(f"candidate: {len(cand)} findings ({args.candidate})")
    print(f"identical keys: {len(common)} | missing: {len(missing)} | "
          f"added: {len(added)} | score drifts >{args.score_eps}: {len(drifts)}")

    def show(title, keys, source):
        if not keys:
            return
        print(f"\n--- {title} (top {min(len(keys), args.max_print)}) ---")
        for k in keys[:args.max_print]:
            e = source[k]
            print(f"  {e['file']} [{e['start']}:{e['end']}] {e['pii_type']} "
                  f"({e['source']}, score={e['score']}) '{e['text'][:40]}'")

    show('MISSING in candidate', missing, base)
    show('ADDED by candidate', added, cand)
    if drifts:
        print(f"\n--- score drifts ---")
        for k in drifts[:args.max_print]:
            print(f"  {k}: {base[k]['score']} -> {cand[k]['score']}")

    sys.exit(0 if not missing and not added else 1)


if __name__ == '__main__':
    main()
