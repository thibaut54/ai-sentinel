"""Generate the labelled judge-findings golden set (``judge_findings.jsonl``).

This is the *artifact of value* behind
:mod:`tests.integration.test_llm_judge_prompt_comparison`: a deterministic,
versioned dataset of findings fed **directly** to the LLM judge (no OpenMed, no
detection pipeline) so prompt variants can be A/B-compared on a fixed, balanced
ground truth.

Each line is one finding — exactly the shape the judge consumes — plus an
oracle label (``ground_truth``) that is **never** sent to the model:

```json
{"finding_id": "ETHEREUM_ADDRESS__FP__sha256",
 "text": "0x2cf2...9824", "pii_type": "ETHEREUM_ADDRESS",
 "type_label": "adresse Ethereum", "start": 57, "end": 123,
 "score": 0.9, "source": "OPENMED",
 "document_text": "...sha256(rapport.pdf) = 0x2cf2...9824. ...",
 "ground_truth": "FP", "note": "hash SHA256 (64 hex) ..."}
```

Sourcing (hybrid, 50/50 per type — see the plan):

* **TP** — derived from the existing OpenMed FP-eval corpus via
  :func:`tests.integration.test_openmed_realistic_fp_evaluation._load_cases_for_type`.
  Their ``expected_spans`` give byte-exact ``value``/``start``/``end`` against a
  real ``document_text``, so offsets are guaranteed correct. A round-robin over
  axes always includes ``canonical_no_clue`` findings — the recall-critical case
  the context-aware variant must NOT regress.
* **FP** — hand-curated look-alikes the judge must reject: the value carries the
  *format* of the claimed ``pii_type`` but the surrounding ``document_text``
  designates another type (a SHA256 tagged ETHEREUM_ADDRESS, a 15-digit order id
  tagged IMEI, a network port tagged CVV...). Several are *context-only* FPs
  (the format alone is a valid instance of the type) — those are what actually
  separate ``v1_baseline`` from ``v2_context_aware``.

Invariant enforced for **every** record: ``document_text[start:end] == text``.

Regenerate (from the service root) with::

    .venv/Scripts/python.exe -m tests.integration.fixtures.generate_judge_findings

The output is committed; rerun whenever the corpus or the FP curation changes.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Dict, List, NamedTuple, Tuple

# --- make ``tests.integration...`` importable when run as a standalone script
# (fixtures/ -> integration/ -> tests/ -> <service root>). Harmless when the
# module is imported under pytest, where the root is already on sys.path.
_SERVICE_ROOT = Path(__file__).resolve().parents[3]
if str(_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVICE_ROOT))

from tests.integration.test_openmed_realistic_fp_evaluation import (  # noqa: E402
    EvalCase,
    ExpectedSpan,
    _load_cases_for_type,
)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Scope of the first iteration (confirmed with the operator): the SHA256-vs-ETH
# motivating case plus two types where format alone cannot settle the verdict.
SCOPE: Tuple[str, ...] = ("IMEI", "ETHEREUM_ADDRESS", "CVV")

# Strict 50/50 balance per type so precision/recall are not skewed by the mix.
TP_PER_TYPE = 6
FP_PER_TYPE = 6

# French labels shown to the judge as ``type_label`` (the corpus only stores the
# raw OpenMed label, which is not human-facing French). A secondary signal — the
# verdict is driven by ``pii_type`` + value + context.
TYPE_LABELS_FR: Dict[str, str] = {
    "IMEI": "IMEI",
    "ETHEREUM_ADDRESS": "adresse Ethereum",
    "CVV": "code de verification de carte (CVV)",
}

# OpenMed score attached to synthetic findings: above the 0.85 per-type cut so a
# finding looks like a genuine OpenMed detection (``_is_audited`` keys on source,
# not score, but a realistic value keeps the dataset faithful).
SYNTHETIC_SCORE = 0.9

# Axis priority for TP selection. ``canonical_no_clue`` first so the recall-
# critical "valid PII without any contextual cue" case is always represented.
AXIS_PRIORITY: Tuple[str, ...] = (
    "canonical_no_clue",
    "canonical_with_clue",
    "adversarial_formatting",
    "long_context",
)

OUTPUT_PATH = Path(__file__).resolve().parent / "judge_findings.jsonl"


# ---------------------------------------------------------------------------
# Hand-curated false positives
# ---------------------------------------------------------------------------


class _FpSpec(NamedTuple):
    """One curated false positive.

    ``document_text`` is assembled as ``prefix + value + suffix`` so the byte
    offsets are correct by construction and the value sits in a context that
    explicitly names *another* type.
    """

    slug: str  # unique id fragment within the type
    value: str  # the look-alike value (wrongly) tagged as the type
    prefix: str  # document text before the value
    suffix: str  # document text after the value
    note: str  # why it is a FP — kept in the dataset for traceability


# Each list mixes "context-only" FPs (value is a format-valid instance of the
# type, only the context betrays it — these separate v1 from v2) with FPs that
# also fail on format (length / charset), which both variants should catch.
_FP_SPECS: Dict[str, List[_FpSpec]] = {
    "ETHEREUM_ADDRESS": [
        _FpSpec(
            "sha256",
            "0x2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            "Empreinte d'integrite du fichier : sha256(rapport.pdf) = ",
            ". Verifiez-la avant ouverture.",
            "hash SHA256 (64 hex), pas une adresse ETH (40 hex) ; contexte sha256()",
        ),
        _FpSpec(
            "keccak256",
            "0x1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
            "Le hash keccak256 du message signe est ",
            " (utilise pour la verification de signature).",
            "digest keccak256 (64 hex) ; contexte keccak256, pas une adresse",
        ),
        _FpSpec(
            "docker_digest",
            "9b2a4f7c3e1d8a6b5c0f2e9d7a4b1c8e6f3a0d2b9c7e4f1a8d6b3c0e2f9a7d4b",
            "Image de base : registry.example.com/app@sha256:",
            "\nPull effectue le 12.03.2024.",
            "digest de layer Docker (64 hex) ; contexte @sha256:",
        ),
        _FpSpec(
            "bitcoin_addr",
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "Adresse de don Bitcoin (BTC) : ",
            ". Merci de votre soutien.",
            "adresse Bitcoin base58 (mauvais reseau) ; contexte Bitcoin/BTC",
        ),
        _FpSpec(
            "git_commit",
            "e83c5163316f89bfbde7d9ab23ca2e25604af290",
            "Correctif livre dans le commit git ",
            " sur la branche main.",
            "SHA-1 de commit git (40 hex, sans 0x) ; contexte commit git",
        ),
        _FpSpec(
            "private_key",
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318",
            "ATTENTION ne jamais committer la cle privee : PRIVATE_KEY=",
            "\n(rotation mensuelle obligatoire).",
            "cle privee hex (64 hex) ; contexte private key, pas une adresse",
        ),
    ],
    "IMEI": [
        _FpSpec(
            "order_number",
            "300123456789012",
            "Votre commande n° ",
            " a ete expediee ce jour par la poste.",
            "numero de commande a 15 chiffres ; format IMEI mais contexte commande",
        ),
        _FpSpec(
            "transaction_id",
            "987650043210028",
            "Reference de transaction bancaire : TRX-",
            " (debit immediat sur le compte).",
            "ID de transaction a 15 chiffres ; contexte transaction bancaire",
        ),
        _FpSpec(
            "invoice_number",
            "202400000123456",
            "Facture n° ",
            " du 30 avril 2024, montant TTC 1 250.00 CHF.",
            "numero de facture a 15 chiffres ; contexte facture",
        ),
        _FpSpec(
            "ean13_barcode",
            "3760123456789",
            "Code-barres EAN-13 du produit : ",
            ". Scannez-le en caisse.",
            "EAN-13 (13 chiffres) ; trop court pour un IMEI (15 chiffres)",
        ),
        _FpSpec(
            "credit_card_pan",
            "4111111111111111",
            "Carte de paiement enregistree, numero complet ",
            " (a masquer dans les logs).",
            "PAN de carte de credit (16 chiffres) ; mauvais type, format 16!=15",
        ),
        _FpSpec(
            "epoch_ns",
            "1714521600000000000",
            "Horodatage (epoch nanosecondes) de l'evenement : ",
            ". Trace systeme.",
            "timestamp epoch ns (19 chiffres) ; trop long pour un IMEI",
        ),
    ],
    "CVV": [
        _FpSpec(
            "network_port",
            "443",
            "Le service ecoute sur le port HTTPS ",
            " (TLS active).",
            "numero de port reseau ; 3 chiffres (format CVV) mais contexte port",
        ),
        _FpSpec(
            "http_status",
            "404",
            "La requete a renvoye une erreur HTTP ",
            " (ressource introuvable).",
            "code de statut HTTP ; 3 chiffres mais contexte HTTP",
        ),
        _FpSpec(
            "page_number",
            "599",
            "Voir la suite a la page ",
            " du manuel d'utilisation.",
            "numero de page ; 3 chiffres mais contexte pagination",
        ),
        _FpSpec(
            "room_number",
            "300",
            "Reunion en salle ",
            ", batiment B, 3e etage.",
            "numero de salle ; 3 chiffres mais contexte salle/local",
        ),
        _FpSpec(
            "fiscal_year",
            "2024",
            "Exercice comptable de l'annee ",
            " clos au 31 decembre.",
            "annee a 4 chiffres ; format CVV Amex mais contexte annee",
        ),
        _FpSpec(
            "dept_code",
            "12",
            "Code de departement administratif : ",
            " (region concernee).",
            "code a 2 chiffres ; trop court pour un CVV (3-4 chiffres)",
        ),
    ],
}


# ---------------------------------------------------------------------------
# Finding builders
# ---------------------------------------------------------------------------


def _record(
    *,
    finding_id: str,
    pii_type: str,
    text: str,
    start: int,
    end: int,
    document_text: str,
    ground_truth: str,
    note: str,
) -> Dict[str, object]:
    """Assemble one dataset record and assert the byte-offset invariant."""
    if document_text[start:end] != text:
        raise AssertionError(
            f"{finding_id}: document_text[{start}:{end}]="
            f"{document_text[start:end]!r} != text={text!r}"
        )
    return {
        "finding_id": finding_id,
        "text": text,
        "pii_type": pii_type,
        "type_label": TYPE_LABELS_FR[pii_type],
        "start": start,
        "end": end,
        "score": SYNTHETIC_SCORE,
        "source": "OPENMED",
        "document_text": document_text,
        "ground_truth": ground_truth,
        "note": note,
    }


def _select_tp_spans(cases: List[EvalCase]) -> List[Tuple[EvalCase, ExpectedSpan]]:
    """Pick ``TP_PER_TYPE`` (case, span) pairs with axis diversity.

    Round-robin over :data:`AXIS_PRIORITY` so ``canonical_no_clue`` is always
    represented (recall-critical) and the selection is deterministic (spans are
    ordered by case id, then by start offset).
    """
    by_axis: Dict[str, List[Tuple[EvalCase, ExpectedSpan]]] = {}
    for case in cases:
        for span in case.expected_spans:
            by_axis.setdefault(case.axis, []).append((case, span))
    for axis in by_axis:
        by_axis[axis].sort(key=lambda cs: (cs[0].case_id, cs[1].start))

    selected: List[Tuple[EvalCase, ExpectedSpan]] = []
    round_idx = 0
    while len(selected) < TP_PER_TYPE:
        progressed = False
        for axis in AXIS_PRIORITY:
            bucket = by_axis.get(axis, [])
            if round_idx < len(bucket):
                selected.append(bucket[round_idx])
                progressed = True
                if len(selected) >= TP_PER_TYPE:
                    break
        if not progressed:  # corpus ran out of spans for this type
            break
        round_idx += 1
    return selected


def _build_tp_findings(pii_type: str) -> List[Dict[str, object]]:
    cases = _load_cases_for_type(pii_type)
    pairs = _select_tp_spans(cases)
    if len(pairs) < TP_PER_TYPE:
        raise AssertionError(
            f"{pii_type}: only {len(pairs)} TP spans available in the corpus, "
            f"need {TP_PER_TYPE}. Add fixtures or lower TP_PER_TYPE."
        )
    findings: List[Dict[str, object]] = []
    for case, span in pairs:
        findings.append(
            _record(
                finding_id=f"{pii_type}__TP__{case.case_id}__@{span.start}",
                pii_type=pii_type,
                text=span.value,
                start=span.start,
                end=span.end,
                document_text=case.text,
                ground_truth="TP",
                note=f"corpus span | axis={case.axis} | lang={case.language}",
            )
        )
    return findings


def _build_fp_findings(pii_type: str) -> List[Dict[str, object]]:
    specs = _FP_SPECS.get(pii_type, [])
    if len(specs) != FP_PER_TYPE:
        raise AssertionError(
            f"{pii_type}: {len(specs)} curated FPs, need exactly {FP_PER_TYPE}."
        )
    findings: List[Dict[str, object]] = []
    for spec in specs:
        document_text = f"{spec.prefix}{spec.value}{spec.suffix}"
        start = len(spec.prefix)
        end = start + len(spec.value)
        findings.append(
            _record(
                finding_id=f"{pii_type}__FP__{spec.slug}",
                pii_type=pii_type,
                text=spec.value,
                start=start,
                end=end,
                document_text=document_text,
                ground_truth="FP",
                note=spec.note,
            )
        )
    return findings


def build_dataset() -> List[Dict[str, object]]:
    """Return all findings, grouped by type (TP then FP), in a stable order."""
    dataset: List[Dict[str, object]] = []
    for pii_type in SCOPE:
        dataset.extend(_build_tp_findings(pii_type))
        dataset.extend(_build_fp_findings(pii_type))
    return dataset


def _assert_balanced(dataset: List[Dict[str, object]]) -> None:
    """Guard the 50/50 balance globally and per type before writing."""
    per_type: Dict[str, Dict[str, int]] = {}
    for rec in dataset:
        bucket = per_type.setdefault(rec["pii_type"], {"TP": 0, "FP": 0})
        bucket[str(rec["ground_truth"])] += 1
    for pii_type, counts in per_type.items():
        if counts["TP"] != counts["FP"]:
            raise AssertionError(
                f"{pii_type}: unbalanced TP={counts['TP']} FP={counts['FP']}"
            )
    total_tp = sum(c["TP"] for c in per_type.values())
    total_fp = sum(c["FP"] for c in per_type.values())
    if total_tp != total_fp:
        raise AssertionError(f"global imbalance TP={total_tp} FP={total_fp}")


def write_dataset(path: Path = OUTPUT_PATH) -> List[Dict[str, object]]:
    dataset = build_dataset()
    _assert_balanced(dataset)
    # Guard finding_id uniqueness — duplicates would silently shadow records.
    ids = [str(r["finding_id"]) for r in dataset]
    if len(ids) != len(set(ids)):
        raise AssertionError("duplicate finding_id detected in dataset")
    with path.open("w", encoding="utf-8") as fp:
        for rec in dataset:
            fp.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return dataset


def main() -> None:
    dataset = write_dataset()
    per_type: Dict[str, Dict[str, int]] = {}
    for rec in dataset:
        bucket = per_type.setdefault(str(rec["pii_type"]), {"TP": 0, "FP": 0})
        bucket[str(rec["ground_truth"])] += 1
    print(f"Wrote {len(dataset)} findings to {OUTPUT_PATH}")
    for pii_type in SCOPE:
        counts = per_type[pii_type]
        print(f"  {pii_type:<18} TP={counts['TP']} FP={counts['FP']}")


if __name__ == "__main__":
    main()
