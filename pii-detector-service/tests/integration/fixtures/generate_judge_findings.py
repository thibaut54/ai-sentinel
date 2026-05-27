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

# Scope: the 12 OpenMed types kept active in production. Broad coverage so the
# variant comparison is statistically meaningful (144 findings) rather than
# driven by a couple of findings on a single type.
SCOPE: Tuple[str, ...] = (
    "PASSWORD",
    "CVV",
    "PIN",
    "IMEI",
    "BITCOIN_ADDRESS",
    "ETHEREUM_ADDRESS",
    "LITECOIN_ADDRESS",
    "VEHICLE_VIN",
    "VEHICLE_REGISTRATION",
    "ACCOUNT_NAME",
    "BANK_ACCOUNT",
    "CREDIT_CARD",
)

# Strict 50/50 balance per type so precision/recall are not skewed by the mix.
TP_PER_TYPE = 6
FP_PER_TYPE = 6

# French labels shown to the judge as ``type_label`` (the corpus only stores the
# raw OpenMed label, which is not human-facing French). A secondary signal — the
# verdict is driven by ``pii_type`` + value + context.
TYPE_LABELS_FR: Dict[str, str] = {
    "PASSWORD": "mot de passe",
    "CVV": "code de verification de carte (CVV)",
    "PIN": "code PIN",
    "IMEI": "IMEI",
    "BITCOIN_ADDRESS": "adresse Bitcoin",
    "ETHEREUM_ADDRESS": "adresse Ethereum",
    "LITECOIN_ADDRESS": "adresse Litecoin",
    "VEHICLE_VIN": "numero de chassis (VIN)",
    "VEHICLE_REGISTRATION": "plaque d'immatriculation",
    "ACCOUNT_NAME": "titulaire du compte",
    "BANK_ACCOUNT": "compte bancaire (IBAN)",
    "CREDIT_CARD": "numero de carte de credit",
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
    "PASSWORD": [
        _FpSpec(
            "config_var_name",
            "DB_PASSWORD_KEY",
            "Variable de configuration a definir dans le vault : ",
            " (ne jamais coder en dur).",
            "nom de variable de config ; contexte cle de config, pas une valeur de mot de passe",
        ),
        _FpSpec(
            "project_codename",
            "ZEPHYR-X42",
            "Le nom de code du projet retenu lors de l'offsite est ",
            ", lancement prevu au T3.",
            "nom de code projet ; contexte codename, pas un mot de passe",
        ),
        _FpSpec(
            "product_serial",
            "QX12-PLM9-7741-AABF",
            "Le numero de serie imprime sur la boite est ",
            ", a conserver pour la garantie.",
            "numero de serie produit ; contexte serial, pas un mot de passe",
        ),
        _FpSpec(
            "internal_codename",
            "PROJET-PHENIX-7",
            "Notre nom de code interne pour la migration est ",
            ".",
            "nom de code interne ; contexte projet, pas un mot de passe",
        ),
        _FpSpec(
            "brand_name",
            "Sturmwacht2024",
            "Der Markenname ",
            " wurde im Marketing-Plan eingefroren.",
            "nom de marque marketing ; contexte brand name, pas un mot de passe",
        ),
        _FpSpec(
            "service_account",
            "appsvc",
            "Compte de service Linux : utilisateur ",
            ", groupe athena-developers, shell /bin/bash.",
            "nom d'utilisateur de service ; contexte compte technique, pas un mot de passe",
        ),
    ],
    "PIN": [
        _FpSpec(
            "postal_code",
            "4053",
            "Postleitzahl ",
            " Basel, Hausnummer 12.",
            "code postal a 4 chiffres ; contexte adresse, pas un PIN",
        ),
        _FpSpec(
            "employee_number",
            "638294",
            "Numero d'employe ",
            ", badge d'acces au batiment Phoenix.",
            "numero d'employe a 6 chiffres ; contexte RH, pas un PIN",
        ),
        _FpSpec(
            "tracking_number",
            "729361",
            "Order tracking number ",
            " was shipped via DHL on Monday.",
            "numero de suivi a 6 chiffres ; contexte logistique, pas un PIN",
        ),
        _FpSpec(
            "page_number",
            "2839",
            "Voir la page ",
            " du rapport annuel, chapitre 4.",
            "numero de page a 4 chiffres ; contexte pagination, pas un PIN",
        ),
        _FpSpec(
            "room_number",
            "5184",
            "Reunion en salle ",
            ", batiment Phoenix, a 9h30.",
            "numero de salle a 4 chiffres ; contexte salle, pas un PIN",
        ),
        _FpSpec(
            "fiscal_year",
            "2024",
            "L'exercice comptable ",
            " a ete clos au 31 decembre.",
            "annee a 4 chiffres ; contexte temporel, pas un PIN",
        ),
    ],
    "BITCOIN_ADDRESS": [
        _FpSpec(
            "eth_address",
            "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
            "Adresse d'envoi Ethereum (ETH) : ",
            ". Reseau ERC-20 uniquement.",
            "adresse Ethereum (0x + 40 hex) ; mauvais reseau, contexte ETH",
        ),
        _FpSpec(
            "ltc_address",
            "LcHKHkLPzWFqe1uK4UvAd3z3vN8pBVNDct",
            "Adresse de retrait Litecoin (LTC) : ",
            ".",
            "adresse Litecoin (prefixe L) ; mauvais reseau, contexte LTC",
        ),
        _FpSpec(
            "txid",
            "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
            "Identifiant de transaction Bitcoin (txid) : ",
            " (a ne pas confondre avec une adresse).",
            "txid de transaction (64 hex) ; contexte txid, pas une adresse",
        ),
        _FpSpec(
            "mongo_objectid",
            "507f1f77bcf86cd799439011",
            "Mongo ObjectId de la commande : ",
            " (24 caracteres hexadecimaux).",
            "ObjectId MongoDB (24 hex) ; contexte base de donnees, pas une adresse",
        ),
        _FpSpec(
            "license_key",
            "XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V",
            "Cle de licence logicielle : ",
            " (format 16 blocs).",
            "cle de licence (tirets) ; contexte license key, pas une adresse Bitcoin",
        ),
        _FpSpec(
            "git_commit",
            "a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9",
            "Git commit SHA-1 : ",
            " (branche master).",
            "SHA-1 de commit git (40 hex) ; contexte git, pas une adresse",
        ),
    ],
    "LITECOIN_ADDRESS": [
        _FpSpec(
            "btc_address",
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "Adresse de don Bitcoin (BTC) : ",
            ".",
            "adresse Bitcoin (prefixe 1) ; mauvais reseau, contexte BTC",
        ),
        _FpSpec(
            "eth_address",
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "Adresse Ethereum (ETH) : ",
            ".",
            "adresse Ethereum (0x + 40 hex) ; mauvais reseau, contexte ETH",
        ),
        _FpSpec(
            "license_key",
            "LcHKQR-5T8AZ9P-MQRNF1B-2D4G7H9",
            "Cle de licence des applications bureautiques : ",
            ".",
            "cle de licence (prefixe Lc, tirets) ; contexte license, pas une adresse",
        ),
        _FpSpec(
            "product_batch",
            "LcZX9VPLM77AABFQRNF1B2D4G7H9J6L5W3VTQ",
            "Charge de production (lot interne) : ",
            ", aucune valeur crypto.",
            "code de lot produit (prefixe Lc, 37 car.) ; contexte batch, pas une adresse",
        ),
        _FpSpec(
            "hostname",
            "ltc1-prod-fr-zh1",
            "Cluster de test de charge, hostname : ",
            " (zone FR, host 1).",
            "hostname (prefixe ltc1, tirets) ; contexte infra, pas une adresse bech32",
        ),
        _FpSpec(
            "article_code",
            "MQR5T8AZ9PMQRNF1B2D4G7H9J6L5W3V",
            "Codice articolo che identifica il lotto Q1 : ",
            ".",
            "code article (prefixe M, 31 car.) ; contexte catalogue, pas une adresse",
        ),
    ],
    "VEHICLE_VIN": [
        _FpSpec(
            "product_sku",
            "QXPLM77QRNF1B2DG4",
            "Product SKU du catalogue : ",
            " (17 caracteres).",
            "SKU produit contenant Q (interdit en VIN) ; contexte catalogue",
        ),
        _FpSpec(
            "batch_number",
            "BN18A3F9C1D2E4B5A",
            "Numero de lot (lot tracking) : ",
            ", 17 caracteres.",
            "numero de lot a 17 car. ; contexte tracking, pas un VIN",
        ),
        _FpSpec(
            "internal_product_code",
            "PROD2024A3F9C1D2E",
            "Code produit interne utilise par la chaine SCM : ",
            ".",
            "code produit a 17 car. ; contexte SCM, pas un VIN",
        ),
        _FpSpec(
            "asset_tag",
            "ASSET77QRNF1B2D4G",
            "Asset tag de l'ordinateur portable : ",
            " (inventaire labo).",
            "asset tag a 17 car. ; contexte inventaire, pas un VIN",
        ),
        _FpSpec(
            "license_key",
            "LIC2024QRNF1B2D4G",
            "Cle de licence Desktop : ",
            " (pas un vehicule).",
            "cle de licence a 17 car. ; contexte licence, pas un VIN",
        ),
        _FpSpec(
            "container_id",
            "MSCU58392740162",
            "Identifiant de conteneur maritime (ISO 6346) : ",
            ".",
            "ID de conteneur (15 car.) ; longueur != 17, contexte logistique, pas un VIN",
        ),
    ],
    "VEHICLE_REGISTRATION": [
        _FpSpec(
            "conference_badge",
            "XY-456-ZW",
            "Le code du badge de conference ",
            " a ete remis au participant 4827.",
            "code de badge (format plaque) ; contexte conference, pas une immatriculation",
        ),
        _FpSpec(
            "lab_sample",
            "CD12-ABX",
            "Identifiant d'echantillon de laboratoire ",
            " enregistre dans le lot de sequencage.",
            "ID d'echantillon (format plaque) ; contexte labo, pas une immatriculation",
        ),
        _FpSpec(
            "warehouse_shelf",
            "K-CD 5678",
            "Kennung des Lagerregals ",
            " im Lagerhaus B.",
            "identifiant d'etagere (format plaque) ; contexte entrepot, pas une immatriculation",
        ),
        _FpSpec(
            "postal_route",
            "RP-275",
            "La route postale ",
            " dessert les communes du secteur Nord.",
            "code de route postale ; contexte adressage, pas une immatriculation",
        ),
        _FpSpec(
            "room_code",
            "EF345GH",
            "Codice aula ",
            " del padiglione est, capienza 45 persone.",
            "code de salle (format plaque) ; contexte salle, pas une immatriculation",
        ),
        _FpSpec(
            "build_agent",
            "GH-789-IJ",
            "Le build agent ",
            " est hors ligne depuis 14:32 UTC.",
            "identifiant de build agent (format plaque) ; contexte CI, pas une immatriculation",
        ),
    ],
    "ACCOUNT_NAME": [
        _FpSpec(
            "hostname",
            "athena-api-prod-zh1",
            "Modele de hostname des serveurs : ",
            ", athena-batch-prod-zh2.",
            "nom d'hote serveur ; contexte infra, pas un titulaire de compte",
        ),
        _FpSpec(
            "project_codename",
            "PHOENIX 2024",
            "Nom de code du projet : ",
            " (suivi interne uniquement).",
            "nom de code projet ; contexte projet, pas un titulaire de compte",
        ),
        _FpSpec(
            "folder_name",
            "rapports_annuels_2023",
            "Nom du dossier sur le partage reseau : ",
            ".",
            "nom de dossier ; contexte fichiers, pas un titulaire de compte",
        ),
        _FpSpec(
            "file_name",
            "jahresbericht_2024_final_v3.pdf",
            "Dateiname im Ordner Berichte : ",
            ".",
            "nom de fichier ; contexte document, pas un titulaire de compte",
        ),
        _FpSpec(
            "linux_user",
            "appsvc",
            "Utilisateur Linux applicatif : ",
            ", groupe athena-developers.",
            "compte de service technique ; contexte systeme, pas un titulaire nominatif",
        ),
        _FpSpec(
            "repo_name",
            "piattaforma-athena",
            "Nom du repository sur GitHub Enterprise : ",
            ".",
            "nom de repository ; contexte code, pas un titulaire de compte",
        ),
    ],
    "BANK_ACCOUNT": [
        _FpSpec(
            "stripe_acct_id",
            "acct_1J5KQ2EnAB12CDExampleAccountId",
            "Identifiant de compte Stripe (facturation) : ",
            ".",
            "id de compte Stripe ; contexte facturation, pas un IBAN",
        ),
        _FpSpec(
            "crm_client_code",
            "IT60-CLT-0542811101-000000-123456",
            "Code client CRM : ",
            " (ce n'est PAS un IBAN).",
            "code client CRM (tirets) ; format IBAN invalide, contexte CRM",
        ),
        _FpSpec(
            "order_reference",
            "DE89-PROD-37040044-0532013000",
            "Reference de commande achat : ",
            " (coincide par hasard avec un format IBAN).",
            "reference de commande (tirets) ; format IBAN invalide, contexte achat",
        ),
        _FpSpec(
            "tracking_number",
            "749382017456290374821059463720",
            "Numero de suivi DHL groupe : ",
            ".",
            "numero de suivi a 30 chiffres ; contexte logistique, pas un IBAN",
        ),
        _FpSpec(
            "amount",
            "92366499.59",
            "Montant total TTC de la facture : ",
            " CHF.",
            "montant decimal ; contexte comptable, pas un numero de compte",
        ),
        _FpSpec(
            "aws_arn",
            "arn:aws:s3:::corp-data-lake-prod-eu-west-1",
            "ARN du bucket S3 : ",
            ".",
            "ARN AWS ; contexte cloud, pas un IBAN",
        ),
    ],
    "CREDIT_CARD": [
        _FpSpec(
            "dhl_tracking",
            "7493 8201 7456 2904",
            "Numero de suivi DHL pour l'expedition Q1 : ",
            ".",
            "numero de suivi (16 chiffres groupes 4x4) ; contexte logistique, pas une carte",
        ),
        _FpSpec(
            "order_reference",
            "9283 4017 2650 1830",
            "Reference de commande passee via le portail : ",
            ".",
            "reference de commande (16 chiffres groupes) ; contexte commande, pas une carte",
        ),
        _FpSpec(
            "container_id",
            "5839 2740 1628 3950",
            "Identifiant de conteneur combine : ",
            " (prefixe MSCU retire).",
            "ID conteneur (16 chiffres groupes) ; contexte logistique, pas une carte",
        ),
        _FpSpec(
            "build_number",
            "4829 1037 4625 1830",
            "Sequence de numero de build (artefact CI) : ",
            ".",
            "numero de build (16 chiffres groupes) ; contexte CI, pas une carte",
        ),
        _FpSpec(
            "delivery_note",
            "6150 2847 1293 6500",
            "Numero de bon de livraison (logistique interne) : ",
            ".",
            "numero de bon de livraison (16 chiffres groupes) ; contexte logistique, pas une carte",
        ),
        _FpSpec(
            "patent_number",
            "EP 1234 5678 9012 3456",
            "Numero de brevet europeen ",
            " depose en 2018.",
            "numero de brevet (prefixe EP) ; contexte propriete intellectuelle, pas une carte",
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
