"""Matrice de scaling COMPLÈTE sur le modèle GLiNER2 privacy-filter.

Miroir de la matrice large-v1 (run_matrix.py) mais avec
``--gliner2-model fastino/gliner2-privacy-filter-PII-multi``, pour trouver le
meilleur point de VÉLOCITÉ sur ce modèle plus léger (1.23 Go vs 1.86) — en
particulier : les copies multiples (proc-n8) tiennent-elles en RAM maintenant ?

Séquentiel + resumable (skip si metrics.json existe) : lancer les configs en
parallèle fausserait les mesures (contention CPU/mémoire). 1er run télécharge le
modèle (HF_HUB_OFFLINE=0) ; les suivants réutilisent le cache.

À la fin :
  - SUMMARY-PF.md (table chars/s + speedup vs l'ACTUEL large-v1 seq-t8=141),
  - invariance des findings (chaque levier pipeline complet == pf-seq-t8 ?),
  - recall par type PII attendu (large-v1 vs privacy-filter).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

BENCH = Path(__file__).parent
MODEL = 'fastino/gliner2-privacy-filter-PII-multi'
SAMPLE = BENCH / '.sample-matrix'
SEED = (BENCH.parent.parent / 'pii-reporting-api' / 'src' / 'test' / 'resources'
        / 'sql' / 'data-improved-gliner2-presidio-regex.sql')
OUT = BENCH / 'out-pf'
LARGE_BASELINE_CPS = 141.0   # base-seq-t8 large-v1 (= état actuel du test)

# (tag, [args bench]) — miroir de la matrice large-v1.
CONFIGS = [
    ('pf-seq-t1',   ['--mode', 'seq', '--torch-threads', '1']),
    ('pf-seq-t8',   ['--mode', 'seq', '--torch-threads', '8']),
    ('pf-seq-t16',  ['--mode', 'seq', '--torch-threads', '16']),
    ('pf-thr-k8',   ['--mode', 'threads', '--workers', '8', '--torch-threads', '1']),
    ('pf-thr-k14',  ['--mode', 'threads', '--workers', '14', '--torch-threads', '1']),
    ('pf-proc-n4-t2',  ['--mode', 'procs', '--workers', '4', '--torch-threads', '2']),
    ('pf-proc-n8-t1',  ['--mode', 'procs', '--workers', '8', '--torch-threads', '1']),
    ('pf-proc-n12-t1', ['--mode', 'procs', '--workers', '12', '--torch-threads', '1']),
]


def write_summary(results: dict) -> None:
    lines = [
        '# Matrice privacy-filter — résultats (sample 101k, host 16 cœurs)',
        '',
        'Speedup référencé sur **l\'état actuel du test** = large-v1 seq torch=8 = 141 c/s.',
        '',
        '| tag | mode | W | torchT | chars/s | ×vs actuel | findings | RSS MB | wall s |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|',
    ]
    for tag, _ in CONFIGS:
        m = results.get(tag)
        if not m:
            lines.append(f'| {tag} | — | | | ÉCHEC | | | | |')
            continue
        sp = m['chars_per_s'] / LARGE_BASELINE_CPS
        lines.append(
            f"| {tag} | {m['mode']} | {m['workers']} | {m['torch_threads']} "
            f"| {m['chars_per_s']:,.0f} | x{sp:.2f} | {m['n_findings']} "
            f"| {m['rss_mb']} | {m['wall_s']} |")
    (OUT / 'SUMMARY-PF.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main() -> None:
    py = sys.executable
    env = dict(os.environ)
    env['HF_HUB_OFFLINE'] = '0'
    env['HF_HOME'] = str(Path.home() / '.ai-sentinel-it-hf-cache')
    env['PYTHONIOENCODING'] = 'utf-8'
    OUT.mkdir(parents=True, exist_ok=True)

    results: dict = {}
    for tag, extra in CONFIGS:
        mpath = OUT / tag / 'metrics.json'
        if mpath.exists():
            print(f'[pf] SKIP {tag}', flush=True)
            results[tag] = json.loads(mpath.read_text(encoding='utf-8'))
            write_summary(results)
            continue
        cmd = [py, str(BENCH / 'bench_scaling.py'), '--tag', tag,
               '--gliner2-model', MODEL, '--sample-dir', str(SAMPLE),
               '--seed-sql', str(SEED), '--out-root', str(OUT)] + extra
        print(f'[pf] RUN {tag}: {" ".join(extra)}', flush=True)
        (OUT / tag).mkdir(parents=True, exist_ok=True)
        with (OUT / tag / 'run.log').open('w', encoding='utf-8') as log:
            rc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT,
                                env=env, timeout=45 * 60, check=False).returncode
        if mpath.exists():
            results[tag] = json.loads(mpath.read_text(encoding='utf-8'))
            m = results[tag]
            print(f'[pf] OK {tag}: {m["chars_per_s"]:.0f} c/s, '
                  f'{m["n_findings"]} findings, rss {m["rss_mb"]}MB', flush=True)
        else:
            print(f'[pf] FAIL {tag} (rc={rc}) -> {OUT / tag / "run.log"}', flush=True)
        write_summary(results)

    # Invariance des findings : chaque levier pipeline complet == pf-seq-t8 ?
    base = OUT / 'pf-seq-t8' / 'findings.jsonl'
    if base.exists():
        print('\n[pf] === INVARIANCE findings (vs pf-seq-t8) ===', flush=True)
        for tag in results:
            cand = OUT / tag / 'findings.jsonl'
            if tag == 'pf-seq-t8' or not cand.exists():
                continue
            r = subprocess.run([py, str(BENCH / 'compare_findings.py'),
                                str(base), str(cand)],
                               capture_output=True, text=True, check=False)
            verdict = 'IDENTIQUE' if r.returncode == 0 else 'DIFFÉRENT'
            third = (r.stdout.splitlines() or ['', '', ''])[2:3]
            print(f'  {tag}: {verdict} {third[0] if third else ""}', flush=True)

    # Recall privacy-filter vs large-v1 (dénominateur = manifest complet).
    large = BENCH / 'out-matrix' / 'base-seq-t8' / 'findings.jsonl'
    targets = [str(p) for p in (large, base) if p.exists()]
    if targets:
        print('\n[pf] === RECALL large-v1 vs privacy-filter ===', flush=True)
        subprocess.run([py, str(BENCH / 'analyze_recall.py'),
                        f'--sample-dir={SAMPLE}', *targets], check=False)
    print('[pf] DONE', flush=True)


if __name__ == '__main__':
    main()
