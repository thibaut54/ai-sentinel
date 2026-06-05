"""Comparatif recall large-v1 (run IT réel) vs privacy-filter (run host) — périmètre constant.

Périmètre = pages HTML présentes dans processed.txt du backup IT (réellement
scannées par le run réel). Pour chaque page, on regarde si chaque modèle a émis
au moins un finding d'un type accepté du dossier.

Usage:
  python compare_models_recall.py <backup_dir> <pf_findings.jsonl>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from analyze_recall import EXPECTED


def page_of(rel: str) -> str:
    return '/'.join(rel.split('/')[:2])


def types_from_it(findings_path: Path) -> dict:
    out: dict = {}
    with findings_path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            e = json.loads(line)
            rel = e.get('relativePath') or ''
            if rel.endswith('page.html'):
                out.setdefault(page_of(rel), set()).add(e.get('piiTypeDetected'))
    return out


def types_from_bench(findings_path: Path) -> dict:
    out: dict = {}
    with findings_path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            e = json.loads(line)
            out.setdefault(page_of(e['file']), set()).add(e['pii_type'])
    return out


def main() -> None:
    backup = Path(sys.argv[1])
    pf_path = Path(sys.argv[2])

    pages_by_folder: dict = {}
    for line in (backup / 'processed.txt').read_text(encoding='utf-8').splitlines():
        rel = line.strip()
        if rel.endswith('page.html'):
            pages_by_folder.setdefault(rel.split('/', 1)[0], set()).add(page_of(rel))

    it_types = types_from_it(backup / 'findings.jsonl')
    pf_types = types_from_bench(pf_path)

    print(f"{'dossier attendu':45} {'pages':>5} {'large-v1':>9} {'privacy-f':>9}  delta")
    tot = {'pages': 0, 'it': 0, 'pf': 0}
    for folder, accepted in EXPECTED.items():
        pages = sorted(pages_by_folder.get(folder, set()))
        it_hit = sum(1 for p in pages if it_types.get(p, set()) & accepted)
        pf_hit = sum(1 for p in pages if pf_types.get(p, set()) & accepted)
        tot['pages'] += len(pages)
        tot['it'] += it_hit
        tot['pf'] += pf_hit
        delta = pf_hit - it_hit
        mark = '=' if delta == 0 else (f'+{delta} ✅' if delta > 0 else f'{delta} ❌')
        print(f"{folder:45} {len(pages):>5} {it_hit:>9} {pf_hit:>9}  {mark}")
        # Pages divergentes : utile pour l'analyse qualitative.
        for p in pages:
            a = bool(it_types.get(p, set()) & accepted)
            b = bool(pf_types.get(p, set()) & accepted)
            if a != b:
                who = 'pf SEUL' if b else 'large SEUL'
                print(f"    -> {who}: {p}")
    print(f"{'TOTAL':45} {tot['pages']:>5} {tot['it']:>9} {tot['pf']:>9}")
    it_pct = 100.0 * tot['it'] / tot['pages'] if tot['pages'] else 0
    pf_pct = 100.0 * tot['pf'] / tot['pages'] if tot['pages'] else 0
    print(f"{'recall global':45} {'':>5} {it_pct:>8.0f}% {pf_pct:>8.0f}%")

    # Indicateur FP : volumes par type (toutes pages du périmètre confondues).
    def counts(types_by_page):
        c: dict = {}
        for p, ts in types_by_page.items():
            for t in ts:
                c[t] = c.get(t, 0) + 1
        return c

    print('\nTop types (nb de pages où le type apparaît) :')
    it_c, pf_c = counts(it_types), counts(pf_types)
    keys = sorted(set(it_c) | set(pf_c), key=lambda k: -(pf_c.get(k, 0) + it_c.get(k, 0)))
    print(f"{'type':28} {'large-v1':>9} {'privacy-f':>9}")
    for k in keys[:18]:
        print(f"{k:28} {it_c.get(k, 0):>9} {pf_c.get(k, 0):>9}")


if __name__ == '__main__':
    main()
