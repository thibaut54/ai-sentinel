"""Run the scaling-lever matrix sequentially, one subprocess per lever.

Each lever runs in a FRESH process (clean torch threading state, fair RAM).
Resumable: levers whose ``metrics.json`` already exists are skipped.
After each run, findings are diffed against the baseline tag and a summary
markdown table is (re)written.

Usage (from pii-detector-service/):
    .venv\\Scripts\\python.exe benchmarks/run_matrix.py \
        --sample-dir benchmarks/.sample-matrix --seed-sql <path> \
        --out-root benchmarks/out-matrix
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

BENCH_DIR = Path(__file__).parent

def lever(tag, mode, workers=1, torch_threads=1, chunk_workers=1, only='all',
          batch_size=0, quantize='none'):
    return {'tag': tag, 'mode': mode, 'workers': workers,
            'torch_threads': torch_threads, 'chunk_workers': chunk_workers,
            'only': only, 'batch_size': batch_size, 'quantize': quantize}


MATRIX = [
    # --- profils par détecteur (où passe le temps ?) ---
    lever('prof-regex-t8',    'seq', torch_threads=8, only='regex'),
    lever('prof-presidio-t8', 'seq', torch_threads=8, only='presidio'),
    lever('prof-gliner2-t8',  'seq', torch_threads=8, only='gliner2'),
    # --- baselines intra-op (état actuel = t8) ---
    lever('base-seq-t8',      'seq', torch_threads=8),
    lever('seq-t16',          'seq', torch_threads=16),
    # seq-t1 retiré : mesure partielle suffisante (20/24 fichiers, ~50 c/s médian,
    # min 30 / max 61) et 35 min de run — le dénominateur mono-thread est connu.
    # --- L1: threads + modèle partagé (plafond GIL démontré) ---
    lever('thr-k8-t1',        'threads', workers=8),
    lever('thr-k14-t1',       'threads', workers=14),
    # --- L6: parallélisme intra-requête (chunks d'un même doc) ---
    lever('chunk-c8-t1',      'seq', chunk_workers=8),
    # --- L2: N processus / N copies du modèle ---
    lever('proc-n4-t2',       'procs', workers=4, torch_threads=2),
    lever('proc-n8-t1',       'procs', workers=8),
    lever('proc-n12-t1',      'procs', workers=12),
    # --- L5: quantization int8 dynamique (bande passante /4) ---
    lever('quant-i8-seq-t8',  'seq', torch_threads=8, quantize='int8'),
    # quant-i8-proc-n8 retiré : int8 dynamique REJETÉ — perd 46 % des findings
    # (cf. SCALING-CONCLUSIONS.md §5.3) + crash NoneType.eval en mode pool.
    # --- L4: batch_extract sur GROS fichiers — à lancer avec
    #     --sample-dir benchmarks/.sample-80k --tags big-base-t8,big-batch-b8-t8,big-proc-n8-b8
    #     (les fichiers 4-37k chars ont assez de chunks pour remplir les batchs).
    lever('big-base-t8',     'seq', torch_threads=8),
    lever('big-batch-b8-t8', 'seq', torch_threads=8, batch_size=8),
    lever('big-proc-n8-b8',  'procs', workers=8, batch_size=8),
]

BASELINE_TAG = 'base-seq-t8'
RUN_TIMEOUT_S = 45 * 60


def run_one(py: str, spec: dict, sample_dir, seed_sql, out_root) -> dict | None:
    tag = spec['tag']
    out_dir = Path(out_root) / tag
    metrics_path = out_dir / 'metrics.json'
    if metrics_path.exists():
        print(f"[matrix] SKIP {tag} (metrics.json present)", flush=True)
        return json.loads(metrics_path.read_text(encoding='utf-8'))

    cmd = [py, str(BENCH_DIR / 'bench_scaling.py'),
           '--tag', tag, '--mode', spec['mode'],
           '--workers', str(spec['workers']),
           '--torch-threads', str(spec['torch_threads']),
           '--gliner2-chunk-workers', str(spec['chunk_workers']),
           '--gliner2-batch-size', str(spec['batch_size']),
           '--gliner2-quantize', spec['quantize'],
           '--only', spec['only'],
           '--sample-dir', str(sample_dir), '--seed-sql', str(seed_sql),
           '--out-root', str(out_root)]
    print(f"[matrix] RUN {tag}: {' '.join(cmd[2:])}", flush=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    log_path = out_dir / 'run.log'
    t0 = time.time()
    with log_path.open('w', encoding='utf-8') as log:
        proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT,
                              timeout=RUN_TIMEOUT_S, check=False)
    dur = time.time() - t0
    if proc.returncode != 0 or not metrics_path.exists():
        print(f"[matrix] FAIL {tag} (rc={proc.returncode}, {dur:.0f}s) "
              f"-> see {log_path}", flush=True)
        return None
    m = json.loads(metrics_path.read_text(encoding='utf-8'))
    print(f"[matrix] OK   {tag}: {m['chars_per_s']:,.0f} chars/s "
          f"(wall {m['wall_s']}s, {m['n_findings']} findings, "
          f"rss {m['rss_mb']}MB)", flush=True)
    return m


def write_summary(results: dict, out_root: Path, baseline_tag: str) -> None:
    base = results.get(baseline_tag)
    lines = [
        '# Matrice de scaling — résultats',
        '',
        '| tag | mode | W | torchT | chunkW | batch | quant | only | chars/s | speedup vs base | findings | RSS MB | wall s |',
        '|---|---|---:|---:|---:|---:|---|---|---:|---:|---:|---:|---:|',
    ]
    for spec in MATRIX:
        tag = spec['tag']
        m = results.get(tag)
        if not m:
            lines.append(f"| {tag} | — | | | | | | | ÉCHEC | | | | |")
            continue
        speedup = (m['chars_per_s'] / base['chars_per_s']) if base else 0.0
        lines.append(
            f"| {tag} | {m['mode']} | {m['workers']} | {m['torch_threads']} "
            f"| {m['gliner2_chunk_workers']} | {m.get('gliner2_batch_size', 0)} "
            f"| {m.get('gliner2_quantize', 'none')} | {m['only']} "
            f"| {m['chars_per_s']:,.0f} | x{speedup:.2f} | {m['n_findings']} "
            f"| {m['rss_mb']} | {m['wall_s']} |")
    (out_root / 'SUMMARY.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--sample-dir', required=True)
    ap.add_argument('--seed-sql', required=True)
    ap.add_argument('--out-root', required=True)
    ap.add_argument('--tags', default='',
                    help='comma list to restrict which tags run')
    args = ap.parse_args()

    py = sys.executable
    out_root = Path(args.out_root)
    out_root.mkdir(parents=True, exist_ok=True)
    restrict = {t for t in args.tags.split(',') if t}

    results: dict = {}
    for spec in MATRIX:
        if restrict and spec['tag'] not in restrict:
            continue
        m = run_one(py, spec, args.sample_dir, args.seed_sql, args.out_root)
        if m:
            results[spec['tag']] = m
        write_summary(results, out_root, BASELINE_TAG)

    # Findings diff vs baseline (precision guard) for full-pipeline levers.
    base_findings = out_root / BASELINE_TAG / 'findings.jsonl'
    if base_findings.exists():
        print('\n[matrix] === diff findings vs baseline ===', flush=True)
        for tag in results:
            if tag == BASELINE_TAG or results[tag]['only'] != 'all':
                continue
            cand = out_root / tag / 'findings.jsonl'
            if not cand.exists():
                continue
            r = subprocess.run(
                [py, str(BENCH_DIR / 'compare_findings.py'),
                 str(base_findings), str(cand)],
                capture_output=True, text=True, check=False)
            verdict = 'IDENTIQUE' if r.returncode == 0 else 'DIFFÉRENT'
            first = r.stdout.splitlines()[2] if len(r.stdout.splitlines()) > 2 else ''
            print(f"  {tag}: {verdict} — {first}", flush=True)
            (out_root / tag / 'diff-vs-baseline.txt').write_text(
                r.stdout, encoding='utf-8')

    print('[matrix] DONE', flush=True)


if __name__ == '__main__':
    main()
