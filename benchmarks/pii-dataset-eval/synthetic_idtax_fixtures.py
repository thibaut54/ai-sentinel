"""Generate synthetic gold for the ID/tax blind spots TAX_ID and TAX_NUMBER.

The public datasets (gretelai, ai4privacy) carry no gold for the canonical
concepts TAX_ID and TAX_NUMBER (verified against the built gold). Both have a
predictable, well-documented value format across our four target languages, so
we emit a small byte-exact gold file covering them on purpose-built positives,
mirroring ``synthetic_fixtures.py`` (the four-blind-spot module).

Concept semantics (master seed data-improved-gliner2-presidio-regex.sql):
  * TAX_ID     — "identifiant fiscal d'une personne ou entreprise". Per-country
                 personal/company tax identifier: FR SPI (13 digits), DE IdNr
                 (11 digits), IT Codice Fiscale (16 alphanumeric), UK UTR
                 (10 digits).
  * TAX_NUMBER — "numero fiscal (ex. numero IDE/TVA suisse)". Swiss UID/VAT
                 CHE-NNN.NNN.NNN with a language-region suffix (MWST/TVA/IVA),
                 plus EU VAT numbers (FR/DE/IT prefixes).

Two concepts WITHOUT a predictable format are deliberately NOT synthesised:
  * LICENSE_NUMBER       — "autorisation professionnelle"; no standardised value
                           format (context-driven) and no annotated dataset.
  * SENSITIVE_ACCOUNT_ID — app-specific concept; no public format or dataset.
Add them later only as context-cued fixtures if recall on cues must be measured.

All values are BMP (code points == UTF-16 units) and the self-check
``text[start:end] == value`` guarantees offsets are exact.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Tuple

BASE_DIR = Path(__file__).resolve().parent
GOLD_DIR = BASE_DIR / "gold"
OUT_PATH = GOLD_DIR / "synthetic-idtax.jsonl"

DATASET_NAME = "synthetic-idtax"

# (case_id, language, canonical_label, text, value) — one positive span each.
CASES: List[Tuple[str, str, str, str, str]] = [
    # TAX_NUMBER — Swiss UID/VAT (CHE-NNN.NNN.NNN + MWST/TVA/IVA) and EU VAT.
    ("TAX_NUMBER_fr_01", "fr", "TAX_NUMBER",
     "Le numero IDE de l'entreprise est CHE-116.281.710 TVA.", "CHE-116.281.710"),
    ("TAX_NUMBER_de_01", "de", "TAX_NUMBER",
     "Die UID der Firma lautet CHE-116.281.710 MWST.", "CHE-116.281.710"),
    ("TAX_NUMBER_it_01", "it", "TAX_NUMBER",
     "Il numero IDE dell'azienda e CHE-116.281.710 IVA.", "CHE-116.281.710"),
    ("TAX_NUMBER_en_01", "en", "TAX_NUMBER",
     "Please invoice us using our EU VAT number DE123456789.", "DE123456789"),
    ("TAX_NUMBER_fmt_01", "en", "TAX_NUMBER",
     "vat_number=CHE-116.281.710;country=CH", "CHE-116.281.710"),

    # TAX_ID — per-country personal/company fiscal identifier.
    ("TAX_ID_fr_01", "fr", "TAX_ID",
     "Mon numero fiscal de reference (SPI) est le 2511175246818.", "2511175246818"),
    ("TAX_ID_de_01", "de", "TAX_ID",
     "Die steuerliche Identifikationsnummer lautet 86095742719.", "86095742719"),
    ("TAX_ID_it_01", "it", "TAX_ID",
     "Il codice fiscale del titolare e RSSMRA85T10A562S.", "RSSMRA85T10A562S"),
    ("TAX_ID_en_01", "en", "TAX_ID",
     "My Unique Taxpayer Reference (UTR) is 1234567890.", "1234567890"),
    ("TAX_ID_long_fr_01", "fr", "TAX_ID",
     "Dans le cadre de votre declaration, veuillez reporter votre identifiant "
     "fiscal personnel, qui figure en haut a droite de votre dernier avis "
     "d'imposition : 2511175246818. Ce numero ne change pas d'une annee a l'autre.",
     "2511175246818"),
]


def _locate(text: str, value: str) -> Dict[str, int]:
    """Return ``{start, end}`` for the first occurrence of ``value`` in ``text``.

    Self-checks ``text[start:end] == value`` so offsets are guaranteed exact.
    """
    start = text.find(value)
    if start < 0:
        raise AssertionError(f"value {value!r} not found in text")
    end = start + len(value)
    assert text[start:end] == value, "self-check failed"
    return {"start": start, "end": end}


def build_docs() -> List[Dict[str, object]]:
    """Build the gold docs (one positive span each) for TAX_ID / TAX_NUMBER."""
    docs: List[Dict[str, object]] = []
    for case_id, lang, label, text, value in CASES:
        loc = _locate(text, value)
        docs.append({
            "id": f"{DATASET_NAME}-{case_id}",
            "dataset": DATASET_NAME,
            "lang": lang,
            "text": text,
            "spans": [{"start": loc["start"], "end": loc["end"], "label": label}],
            "ignore_spans": [],
        })
    return docs


def write_gold() -> Path:
    """Write ``gold/synthetic-idtax.jsonl`` and return its path."""
    docs = build_docs()
    GOLD_DIR.mkdir(parents=True, exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as fh:
        for doc in docs:
            fh.write(json.dumps(doc, ensure_ascii=False) + "\n")
    return OUT_PATH


if __name__ == "__main__":
    path = write_gold()
    docs = build_docs()
    labels = sorted({doc["spans"][0]["label"] for doc in docs})
    print(f"wrote {len(docs)} docs to {path}")
    print("labels:", ", ".join(labels))
