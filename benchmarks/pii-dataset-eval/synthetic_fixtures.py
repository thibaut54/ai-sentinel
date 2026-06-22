"""Generate synthetic gold for the four detector blind spots.

The public datasets (gretelai, ai4privacy) carry little to no gold for
ACCESS_TOKEN, RECOVERY_CODE, CARD_EXPIRY and SECRET (see spec §"Angles morts").
Scoring those concepts only on dataset gold would skew F1. This module emits a
small, byte-exact gold file covering exactly these four canonical concepts so
the benchmark can measure them on purpose-built positives.

The texts are ported from the production blind-spot fixtures under
``pii-detector-service/tests/resources/gliner2-fp-eval/`` (SECRET / ACCESS_TOKEN
/ RECOVERY_CODE builders and CARD_EXPIRY.json). Only the *positive* cases (those
with an expected value) are kept: span-level gold lists where PII is, and the
self-check ``text[start:end] == value`` guarantees offsets are exact and in code
points (every value here is BMP, so code points == UTF-16 units).
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Tuple

BASE_DIR = Path(__file__).resolve().parent
GOLD_DIR = BASE_DIR / "gold"
OUT_PATH = GOLD_DIR / "synthetic-blindspots.jsonl"

DATASET_NAME = "synthetic-blindspots"

# (case_id, language, canonical_label, text, value) — one positive span each.
# Ported from the gliner2-fp-eval blind-spot fixtures; only positives kept.
CASES: List[Tuple[str, str, str, str, str]] = [
    # ACCESS_TOKEN
    ("ACCESS_TOKEN_fr_01", "fr", "ACCESS_TOKEN",
     "Jeton d'acces GitHub : ghp_xxxxxxxxxxxxxxxxxxxx", "ghp_xxxxxxxxxxxxxxxxxxxx"),
    ("ACCESS_TOKEN_en_01", "en", "ACCESS_TOKEN",
     "Authorization header: Bearer ya29.a0AfH-EXAMPLE-token", "ya29.a0AfH-EXAMPLE-token"),
    ("ACCESS_TOKEN_de_01", "de", "ACCESS_TOKEN",
     "Das Access-Token lautet eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
     "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
    ("ACCESS_TOKEN_fmt_01", "en", "ACCESS_TOKEN",
     '{"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"}',
     "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
    ("ACCESS_TOKEN_long_fr_01", "fr", "ACCESS_TOKEN",
     "Apres authentification reussie, le serveur OAuth renvoie une reponse JSON "
     "contenant le jeton d'acces a utiliser pour les appels suivants : "
     "ghp_xxxxxxxxxxxxxxxxxxxx. Ce jeton expire au bout d'une heure et doit etre "
     "rafraichi via le refresh token associe.", "ghp_xxxxxxxxxxxxxxxxxxxx"),

    # RECOVERY_CODE
    ("RECOVERY_CODE_fr_01", "fr", "RECOVERY_CODE",
     "Code de recuperation : ABCD-EFGH-1234", "ABCD-EFGH-1234"),
    ("RECOVERY_CODE_en_01", "en", "RECOVERY_CODE",
     "Backup code: 8f3k-29dz-71qm", "8f3k-29dz-71qm"),
    ("RECOVERY_CODE_de_01", "de", "RECOVERY_CODE",
     "Ihr Wiederherstellungscode lautet RECOV-7782-3341", "RECOV-7782-3341"),
    ("RECOVERY_CODE_fmt_01", "en", "RECOVERY_CODE",
     "code=ABCD-EFGH-1234;used=false", "ABCD-EFGH-1234"),
    ("RECOVERY_CODE_long_fr_01", "fr", "RECOVERY_CODE",
     "Lors de l'activation de la double authentification, le systeme vous a fourni une "
     "liste de codes de secours a usage unique. En cas de perte de votre telephone, "
     "utilisez l'un d'eux pour vous reconnecter, par exemple RECOV-7782-3341. "
     "Chaque code ne fonctionne qu'une seule fois.", "RECOV-7782-3341"),

    # CARD_EXPIRY
    ("CARD_EXPIRY_fr_01", "fr", "CARD_EXPIRY",
     "La date d'expiration de la carte est 04/27 selon le recto.", "04/27"),
    ("CARD_EXPIRY_en_01", "en", "CARD_EXPIRY",
     "The card expiry date is 12/2026, please update it on file.", "12/2026"),
    ("CARD_EXPIRY_de_01", "de", "CARD_EXPIRY",
     "Die Karte laeuft ab, Ablaufdatum expire 03/28 laut Vorderseite.", "03/28"),
    ("CARD_EXPIRY_fmt_01", "en", "CARD_EXPIRY",
     "Expiry 04-27 written with a dash on the receipt.", "04-27"),
    ("CARD_EXPIRY_long_fr_01", "fr", "CARD_EXPIRY",
     "En relisant le formulaire de paiement renvoye par le client, le conseiller a "
     "remarque que le numero de carte etait correct mais que la date d'expiration "
     "04/27 arrivait bientot a terme; il a donc invite le client a preparer une "
     "nouvelle carte avant le prochain prelevement recurrent.", "04/27"),

    # SECRET
    ("SECRET_fr_01", "fr", "SECRET",
     "Le secret du client est : s3cr3t_v4lue_9xQ", "s3cr3t_v4lue_9xQ"),
    ("SECRET_en_01", "en", "SECRET",
     "Use this client secret: 8a9f2c1d4b6e7081", "8a9f2c1d4b6e7081"),
    ("SECRET_de_01", "de", "SECRET",
     "Das Geheimnis im Vault: vault://prod/db#Hk7-Zq91", "vault://prod/db#Hk7-Zq91"),
    ("SECRET_fmt_01", "en", "SECRET",
     "client_secret=8a9f2c1d4b6e7081&grant_type=client_credentials", "8a9f2c1d4b6e7081"),
    ("SECRET_long_fr_01", "fr", "SECRET",
     "Dans le cadre de l'integration OAuth, l'application backend doit echanger un "
     "jeton. Voici les parametres a configurer cote serveur : client_id=app-42 et "
     "client secret : 8a9f2c1d4b6e7081. Ne committez jamais ces valeurs dans Git.",
     "8a9f2c1d4b6e7081"),
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
    """Build the gold docs (one positive span each) for the four blind spots."""
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
    """Write ``gold/synthetic-blindspots.jsonl`` and return its path."""
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
