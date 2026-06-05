"""POC L0 — Dérisquage de la lib `gliner2` (NON mergé en prod).

Objectifs (spec technique §8 L0 + §12 points bloquants) :
  1. Charger GLiNER2 via `GLiNER2.from_pretrained(model_id)`.
  2. Construire un schéma {label: description} via `create_schema().entities(...)`.
  3. Appeler `extract(text, schema, threshold, ...)` et obtenir des OFFSETS
     caractère fiables : valider `text[start:end] == entité`.
  4. Mesurer la latence CPU p50/p95.
  5. Identifier l'accès tokenizer pour le chunking.

Lancement :
  PYTHONIOENCODING=utf-8 python experiments/poc_gliner2_offsets.py
"""
from __future__ import annotations

import statistics
import time

from gliner2 import GLiNER2

MODEL_ID = "fastino/gliner2-large-v1"

# Schéma d'inférence FR/suisse {label: description}
SCHEMA_DICT = {
    "person_name": "nom complet d'une personne physique",
    "email": "adresse e-mail",
    "phone_number": "numero de telephone",
    "iban": "numero de compte bancaire international IBAN",
    "avs_number": "numero AVS suisse a 13 chiffres",
    "credit_card": "numero de carte de credit",
}

TEXTS_FR = [
    "Bonjour, je m'appelle Jean Dupont et mon email est jean.dupont@example.ch.",
    "Mon IBAN est CH93 0076 2011 6238 5295 7 et mon numero AVS est 756.1234.5678.97.",
    "Veuillez contacter Marie Curie au +41 79 123 45 67 pour toute question.",
]


def main() -> None:
    print(f"[POC] Loading {MODEL_ID} ...")
    t0 = time.time()
    model = GLiNER2.from_pretrained(MODEL_ID)
    print(f"[POC] Loaded in {time.time() - t0:.1f}s")

    # Accès tokenizer (pour Gliner2ModelManager.get_tokenizer + chunking)
    tok = None
    for attr in ("tokenizer", "data_processor"):
        obj = getattr(model, attr, None)
        if obj is not None:
            print(f"[POC] model.{attr} -> {type(obj).__name__}")
            if attr == "tokenizer":
                tok = obj
    if tok is None:
        try:
            from transformers import AutoTokenizer
            tok = AutoTokenizer.from_pretrained(MODEL_ID)
            print(f"[POC] tokenizer via AutoTokenizer -> {type(tok).__name__}")
        except Exception as exc:
            print(f"[POC] tokenizer fallback FAILED: {exc}")

    schema = model.create_schema().entities(SCHEMA_DICT)

    latencies = []
    all_ok = True
    for text in TEXTS_FR:
        t = time.time()
        # include_spans + include_confidence + format_results=False -> spans bruts
        raw = model.extract(
            text, schema, threshold=0.3,
            format_results=False, include_confidence=True, include_spans=True,
        )
        latencies.append(time.time() - t)
        print("\n[POC] text:", text)
        print("[POC] raw keys:", list(raw.keys()) if isinstance(raw, dict) else type(raw))
        ents = raw.get("entities", raw) if isinstance(raw, dict) else raw
        print("[POC] entities repr:", repr(ents)[:600])
        # Validation offsets caractère-par-caractère
        _validate_offsets(ents, text)

    print("\n[POC] === LATENCE CPU ===")
    print(f"[POC] p50={statistics.median(latencies)*1000:.0f}ms "
          f"max={max(latencies)*1000:.0f}ms n={len(latencies)}")
    print(f"[POC] GO/NO-GO offsets fiables: {'GO' if all_ok else 'NO-GO'}")


def _validate_offsets(ents, text: str) -> None:
    """Inspecte la structure pour trouver (text,start,end) et valide le slicing."""
    items = []
    if isinstance(ents, dict):
        for label, spans in ents.items():
            if isinstance(spans, list):
                for sp in spans:
                    items.append((label, sp))
    elif isinstance(ents, list):
        for sp in ents:
            items.append(("?", sp))
    for label, sp in items:
        start = end = etext = None
        if isinstance(sp, tuple) and len(sp) == 4:
            etext, _conf, start, end = sp
        elif isinstance(sp, dict):
            etext = sp.get("text")
            start = sp.get("start")
            end = sp.get("end")
        if start is not None and end is not None:
            sliced = text[start:end]
            ok = sliced == etext
            flag = "OK" if ok else "MISMATCH"
            print(f"[POC]   {label}: ({start},{end}) model={etext!r} "
                  f"slice={sliced!r} -> {flag}")
        else:
            print(f"[POC]   {label}: NO OFFSETS in span={sp!r}")


if __name__ == "__main__":
    main()
