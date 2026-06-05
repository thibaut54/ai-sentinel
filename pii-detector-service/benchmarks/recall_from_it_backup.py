"""Recall par type PII attendu depuis le findings.jsonl du VRAI run IT (large-v1).

Le backup du run réel (`corpus-scan-backup-*/findings.jsonl`) contient les findings
du pipeline complet (Java + gRPC + container) sur les fichiers déjà traités
(processed.txt). Champs : relativePath, piiTypeDetected, piiTypeFolder, pageFolder.

On calcule le recall PAR PAGE HTML (relativePath se terminant par page.html) avec
comme dénominateur les pages HTML listées dans processed.txt — c'est-à-dire les
pages réellement scannées par le run IT — pour une comparaison à périmètre constant
avec le run host privacy-filter (qui ne scanne que les pages HTML).

Usage: python recall_from_it_backup.py <backup_dir>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from analyze_recall import EXPECTED  # même mapping que le test IT


def main() -> None:
    backup = Path(sys.argv[1])
    findings_path = backup / 'findings.jsonl'
    processed_path = backup / 'processed.txt'

    # Dénominateur : pages HTML réellement traitées par le run IT.
    pages_by_folder: dict = {}
    for line in processed_path.read_text(encoding='utf-8').splitlines():
        rel = line.strip()
        if not rel.endswith('page.html'):
            continue
        folder = rel.split('/', 1)[0]
        page = '/'.join(rel.split('/')[:2])
        pages_by_folder.setdefault(folder, set()).add(page)

    # Types détectés par page HTML.
    types_by_page: dict = {}
    n = 0
    with findings_path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            e = json.loads(line)
            rel = e.get('relativePath') or ''
            if not rel.endswith('page.html'):
                continue
            n += 1
            page = '/'.join(rel.split('/')[:2])
            types_by_page.setdefault(page, set()).add(e.get('piiTypeDetected'))

    print(f"=== run IT réel (large-v1, pipeline complet) : {n} findings sur pages HTML ===")
    print(f"{'dossier attendu':45} {'pages_hit/pages':>14} {'recall':>8}")
    tot_hit = tot_pages = 0
    for folder, accepted in EXPECTED.items():
        pages = pages_by_folder.get(folder, set())
        hit = sum(1 for p in pages if types_by_page.get(p, set()) & accepted)
        tot_hit += hit
        tot_pages += len(pages)
        rec = f"{100.0 * hit / len(pages):.0f}%" if pages else 'n/a'
        print(f"{folder:45} {f'{hit}/{len(pages)}':>14} {rec:>8}")
    glob = f"{100.0 * tot_hit / tot_pages:.0f}%" if tot_pages else 'n/a'
    print(f"{'TOTAL (pages HTML traitées par le run IT)':45} {f'{tot_hit}/{tot_pages}':>14} {glob:>8}")


if __name__ == '__main__':
    main()
