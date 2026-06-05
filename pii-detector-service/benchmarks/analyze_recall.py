"""Recall par type PII attendu sur un findings.jsonl du bench.

Réplique la logique de recall du test IT : le dossier de tête du chemin
(`AVS_NUMBER/...`) est le type PII attendu ; une page est "hit" si elle produit
au moins un finding dont le type ∈ codes acceptés. On agrège par dossier attendu.

Usage : python analyze_recall.py <findings.jsonl> [<findings.jsonl> ...]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# Mapping repris de CorpusGliner2PresidioRegexScanIT.EXPECTED_PII_TYPES.
EXPECTED = {
    'AVS_NUMBER': {'AVS_NUMBER'},
    'Adresse_MAC': {'MAC_ADDRESS'},
    'Carte_de_credit': {'CREDIT_CARD_NUMBER', 'CREDIT_CARD', 'PAYMENT_CARD', 'CARD_NUMBER'},
    'Identifiant_bancaire_international_IBAN': {'IBAN', 'IBAN_CODE'},
    'Identifiant_systeme_ou_compte_de_connexion': {'USERNAME', 'PASSWORD', 'API_KEY', 'ACCESS_TOKEN', 'SECRET'},
    'MEDICAL_LICENSE': {'MEDICAL_LICENSE'},
    'Plaque_d_immatriculation': {'LICENSE_PLATE', 'VEHICLE_REGISTRATION'},
    'SESSION_ID': {'SESSION_ID'},
    'SOCIALNUM': {'SOCIALNUM'},
    'TAX_ID': {'TAX_ID', 'TAX_NUMBER'},
}


def load(path: Path):
    by_file = {}
    by_type = {}
    n = 0
    with path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            e = json.loads(line)
            n += 1
            by_file.setdefault(e['file'], []).append(e)
            by_type[e['pii_type']] = by_type.get(e['pii_type'], 0) + 1
    return by_file, by_type, n


def page_of(rel: str) -> str:
    # "AVS_NUMBER/01_xxx/page.html" -> "AVS_NUMBER/01_xxx"
    parts = rel.split('/')
    return '/'.join(parts[:2]) if len(parts) >= 2 else rel


def load_manifest_pages(sample_dir: Path):
    """folder -> set(pages) sur TOUTES les pages du sample (dénominateur correct).

    Sans ça, le dénominateur = pages ayant ≥1 finding, qui diffère d'un modèle à
    l'autre et fausse la comparaison croisée.
    """
    man = json.loads((sample_dir / 'manifest.json').read_text(encoding='utf-8'))
    folders = {}
    for entry in man['files']:
        rel = entry['rel']
        folders.setdefault(rel.split('/', 1)[0], set()).add(page_of(rel))
    return folders


def analyze(path: Path, all_pages: dict | None = None):
    by_file, by_type, n = load(path)
    # folder -> {page -> set(types détectés)}
    folders = {}
    for rel, ents in by_file.items():
        folder = rel.split('/', 1)[0]
        page = page_of(rel)
        folders.setdefault(folder, {}).setdefault(page, set())
        for e in ents:
            folders[folder][page].add(e['pii_type'])

    print(f"\n=== {path.parent.name} : {n} findings, "
          f"{len(by_file)} fichiers avec findings ===")
    print(f"{'dossier attendu':45} {'pages_hit/pages':>14} {'recall':>8}  codes acceptés")
    tot_hit = tot_pages = 0
    for folder, accepted in EXPECTED.items():
        pages = folders.get(folder, {})
        # Dénominateur = toutes les pages du folder (manifest) si dispo, sinon
        # pages ayant produit un finding (fallback, comparaison non fiable).
        n_pages = len(all_pages[folder]) if all_pages and folder in all_pages else len(pages)
        hit = sum(1 for types in pages.values() if types & accepted)
        tot_hit += hit
        tot_pages += n_pages
        rec = f"{100.0 * hit / n_pages:.0f}%" if n_pages else "n/a"
        print(f"{folder:45} {f'{hit}/{n_pages}':>14} {rec:>8}  {', '.join(sorted(accepted))}")
    glob = f"{100.0 * tot_hit / tot_pages:.0f}%" if tot_pages else "n/a"
    print(f"{'TOTAL':45} {f'{tot_hit}/{tot_pages}':>14} {glob:>8}")
    # Top types détectés (vue d'ensemble bruit/signal).
    top = sorted(by_type.items(), key=lambda kv: -kv[1])[:12]
    print("  top types détectés :", ', '.join(f"{t}={c}" for t, c in top))


if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--sample-dir=')]
    sample_args = [a for a in sys.argv[1:] if a.startswith('--sample-dir=')]
    all_pages = None
    if sample_args:
        all_pages = load_manifest_pages(Path(sample_args[0].split('=', 1)[1]))
    for p in args:
        analyze(Path(p), all_pages)
