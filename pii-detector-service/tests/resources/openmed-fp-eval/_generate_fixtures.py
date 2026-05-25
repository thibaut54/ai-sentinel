"""Helper script that generates the 12 OpenMed FP/FN evaluation fixtures.

Run with::

    python _generate_fixtures.py

Each ``build_*`` function returns the payload as a dict; offsets are computed
via ``text.find(value)`` so they stay byte-exact even when the prompt body is
edited. The script writes one JSON file per PII type next to itself.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional


def _span(text: str, value: str, occurrence: int = 1) -> Dict[str, Any]:
    """Return a span dict for the *occurrence*-th instance of ``value`` in ``text``.

    Uses ``text.find`` (and successive ``find`` with an offset) so the runner's
    ``text[start:end] == value`` assertion always passes.
    """
    idx = -1
    pos = 0
    for _ in range(occurrence):
        idx = text.find(value, pos)
        if idx == -1:
            raise AssertionError(
                f"value {value!r} not found (occurrence {occurrence}) in text"
            )
        pos = idx + 1
    end = idx + len(value)
    assert text[idx:end] == value, "self-check failed"
    return {"start": idx, "end": end, "value": value}


def _case(
    case_id: str,
    language: str,
    axis: str,
    text: str,
    expected: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    return {
        "id": case_id,
        "language": language,
        "axis": axis,
        "text": text,
        "expected_spans": expected or [],
    }


# ---------------------------------------------------------------------------
# 1. PASSWORD
# ---------------------------------------------------------------------------

def build_password() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue (1 per language)
    t1 = "Mot de passe utilisateur : Tr0ub4dor!2024"
    cases.append(_case(
        "PASSWORD_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, "Tr0ub4dor!2024")],
    ))
    t2 = "Password for the staging admin account: P@ssw0rd-Spring#2024"
    cases.append(_case(
        "PASSWORD_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, "P@ssw0rd-Spring#2024")],
    ))
    t3 = "Passwort fuer das Backup-Konto: Sommer$Regen-19!"
    cases.append(_case(
        "PASSWORD_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, "Sommer$Regen-19!")],
    ))
    t4 = "Password dell'account di servizio: Estate&Sole#2024"
    cases.append(_case(
        "PASSWORD_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, "Estate&Sole#2024")],
    ))

    # canonical_no_clue
    t5 = "QzX!9aRq2Lp$M"
    cases.append(_case(
        "PASSWORD_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, "QzX!9aRq2Lp$M")],
    ))
    t6 = "M00n!Light#Falcon7"
    cases.append(_case(
        "PASSWORD_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, "M00n!Light#Falcon7")],
    ))
    t7 = "Wolkenkratzer#2024!Z"
    cases.append(_case(
        "PASSWORD_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, "Wolkenkratzer#2024!Z")],
    ))
    t8 = "Tramonto-Verde$77"
    cases.append(_case(
        "PASSWORD_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, "Tramonto-Verde$77")],
    ))

    # look_alikes (variable names, codenames, docs)
    cases.append(_case(
        "PASSWORD_look_alikes_en_01", "en", "look_alikes",
        "Function signature: def authenticate(username: str, password: str) -> bool:",
    ))
    cases.append(_case(
        "PASSWORD_look_alikes_en_02", "en", "look_alikes",
        "The codename for project ZEPHYR-X42 was chosen during the offsite.",
    ))
    cases.append(_case(
        "PASSWORD_look_alikes_fr_01", "fr", "look_alikes",
        "Notre nom de code interne est PROJET-PHENIX-7 pour la migration.",
    ))
    cases.append(_case(
        "PASSWORD_look_alikes_fr_02", "fr", "look_alikes",
        "Variable de configuration : DB_PASSWORD_KEY (a definir dans le vault).",
    ))
    cases.append(_case(
        "PASSWORD_look_alikes_en_03", "en", "look_alikes",
        "The serial number printed on the box reads QX12-PLM9-7741-AABF.",
    ))
    cases.append(_case(
        "PASSWORD_look_alikes_de_01", "de", "look_alikes",
        "Der Markenname Sturmwacht2024 wurde im Marketing-Plan eingefroren.",
    ))

    # explicit_negatives
    cases.append(_case(
        "PASSWORD_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "La politique de securite exige un mot de passe d'au moins 12 caracteres. "
        "Aucune valeur reelle n'est consignee dans cette page.",
    ))
    cases.append(_case(
        "PASSWORD_explicit_negatives_en_01", "en", "explicit_negatives",
        "CLI usage: --password <STRING>  (omitted here, see vault for the value).",
    ))
    cases.append(_case(
        "PASSWORD_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bitte das Passwort niemals in Confluence eintragen — Verweis auf Vault.",
    ))
    cases.append(_case(
        "PASSWORD_explicit_negatives_it_01", "it", "explicit_negatives",
        "Politica password: minimo 14 caratteri, rotazione ogni 90 giorni. "
        "Nessun valore di esempio in questa pagina.",
    ))

    # adversarial_formatting
    t_adv1 = "Mot   de   passe   :   T r 0 u b 4 d o r ! 2 0 2 4"
    val_adv1 = "T r 0 u b 4 d o r ! 2 0 2 4"
    cases.append(_case(
        "PASSWORD_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1, [_span(t_adv1, val_adv1)],
    ))
    t_adv2 = "Password (line-wrapped):\nSpring\n2024\n!Falcon"
    cases.append(_case(
        "PASSWORD_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2,  # adversarial enough — no expected span
    ))
    t_adv3 = "Passwort: Zürich-2024!#Falcon"  # contains zürich umlaut
    cases.append(_case(
        "PASSWORD_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, "Zürich-2024!#Falcon")],
    ))

    # long_context (positive)
    intro = (
        "Procedure de bascule en production pour le module Athena.\n\n"
        "Cette page decrit les etapes operationnelles applicables a "
        "l'environnement de pre-production. Toute manipulation doit faire "
        "l'objet d'un ticket dans le portail interne et d'une validation "
        "par l'astreinte. L'equipe est joignable via le canal #ops-prod "
        "sur Teams entre 8h et 18h les jours ouvres.\n\n"
        "Liste des artefacts deployes lors du Sprint 47:\n\n"
    )
    table = (
        "| Composant     | Version | Build hash                                | Status |\n"
        "|---------------|---------|-------------------------------------------|--------|\n"
        "| athena-core   | 4.12.0  | a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9  | OK     |\n"
        "| athena-api    | 4.12.0  | b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0  | OK     |\n"
        "| athena-batch  | 4.12.0  | c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1  | OK     |\n\n"
    )
    middle = (
        "Configuration JDBC: l'URL de connexion est fournie via le secret "
        "ATHENA_DB_URL stocke dans HashiCorp Vault sous le chemin "
        "secret/data/athena/prod/db. Aucun identifiant ne doit jamais etre "
        "ecrit en clair dans Confluence ou dans un repository Git.\n\n"
        "Pour les tests en avant-prod uniquement (compte de service "
        "temporaire), le mot de passe Vault de l'utilisateur athena_svc est "
        "ZéphyrCoupe$2024!. Il sera revoque le 30/06.\n\n"
    )
    outro = (
        "Apres la bascule, executer la verification de sante via curl: "
        "curl -fsSL https://athena.prod.internal/health | jq '.status'. "
        "En cas d'echec, consulter le runbook RB-ATH-014 et ouvrir un "
        "incident severite 2 dans ServiceNow.\n\n"
        "Annexes:\n"
        "- Tableau RACI (lien Confluence)\n"
        "- Mode operatoire backup (PDF)\n"
        "- Plan de rollback (Markdown)\n\n"
        "Historique des modifications:\n"
        "- 2024-03-14 J. Dupont — creation\n"
        "- 2024-03-22 M. Martin — ajout RACI\n"
        "- 2024-04-02 L. Bernard — validation operationnelle\n"
    )
    t_long_pos = intro + table + middle + outro
    cases.append(_case(
        "PASSWORD_long_context_fr_01", "fr", "long_context", t_long_pos,
        [_span(t_long_pos, "ZéphyrCoupe$2024!")],
    ))

    # long_context negative
    t_long_neg = (
        "Security policy for the Athena platform.\n\n"
        "This document specifies the rules around credential handling in the "
        "Athena ecosystem. The general principle is that no password value "
        "should ever be checked into git, written in a Confluence page, "
        "pasted in a Jira comment, or sent over email or chat. Passwords are "
        "managed exclusively through the corporate Vault.\n\n"
        "Approved patterns:\n"
        "- Service accounts use a long random secret rotated every 90 days.\n"
        "- Human users authenticate via SSO with MFA enforced.\n"
        "- CI jobs read credentials via short-lived tokens issued by Vault.\n\n"
        "Forbidden patterns:\n"
        "- Hardcoding a password in application.yml or .env files.\n"
        "- Sharing a password by Teams DM or Outlook mail.\n"
        "- Writing a password in a Markdown table or screenshot.\n\n"
        "Reference identifiers used by the team (NOT passwords): "
        "PROJECT-PHOENIX-2024, ASSET-TAG-QX19PLM77, BUILD-SHA-"
        "a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9. The ticket reference "
        "POL-SEC-0142 tracks ongoing rotation. The standard string used in "
        "code samples is the literal SOME_PLACEHOLDER and not a real value.\n\n"
        "Variables defined in the helm chart values.yaml include "
        "DB_PASSWORD_KEY, REDIS_PASSWORD_KEY, KAFKA_SASL_PASSWORD_KEY but "
        "their values come from Vault at runtime and are never persisted.\n\n"
        "Audit log lines look like this: 2024-04-02T08:11:47Z user=athena_svc "
        "action=login outcome=success source_ip=10.0.4.27 trace_id="
        "9f1c0a2b3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a. No password material "
        "appears in any audit row, period.\n\n"
        "Q&A section: how do I rotate a password? Open a ticket in the "
        "self-service portal under category Credentials > Rotate. The flow "
        "is fully automated, the new password value is injected directly "
        "into Vault and surfaced to the calling pod via the agent injector. "
        "You will never see the cleartext value on screen.\n"
    )
    cases.append(_case(
        "PASSWORD_long_context_en_neg_01", "en", "long_context", t_long_neg,
    ))

    return {
        "pii_type": "PASSWORD",
        "detector_label": "PASSWORD",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 2. CVV
# ---------------------------------------------------------------------------

def build_cvv() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t1 = "Numero de carte: 4111 1111 1111 1111, CVV: 737"
    cases.append(_case(
        "CVV_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, "737")],
    ))
    t2 = "Card 4111111111111111, expiry 04/27, CVV: 519"
    cases.append(_case(
        "CVV_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, "519")],
    ))
    t3 = "Kreditkarte 4111-1111-1111-1111, Pruefziffer CVV: 248"
    cases.append(_case(
        "CVV_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, "248")],
    ))
    t4 = "Carta di credito 4111 1111 1111 1111, codice CVV: 902"
    cases.append(_case(
        "CVV_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, "902")],
    ))

    # canonical_no_clue (3 digits isolated)
    t5 = "Communication bancaire confidentielle 4111 1111 1111 1111 — 384"
    cases.append(_case(
        "CVV_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, "384")],
    ))
    t6 = "Order ref: 4242 4242 4242 4242 — 615"
    cases.append(_case(
        "CVV_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, "615")],
    ))
    t7 = "Zahlung 5555 5555 5555 4444 — 471"
    cases.append(_case(
        "CVV_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, "471")],
    ))
    t8 = "Pagamento 5105 1051 0510 5100 — 138"
    cases.append(_case(
        "CVV_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, "138")],
    ))

    # look_alikes (3-digit numbers)
    cases.append(_case(
        "CVV_look_alikes_en_01", "en", "look_alikes",
        "The conference room 312 is booked from 9am to noon on floor 4.",
    ))
    cases.append(_case(
        "CVV_look_alikes_fr_01", "fr", "look_alikes",
        "Page 218 du manuel d'utilisation, paragraphe 7.4, ligne 12.",
    ))
    cases.append(_case(
        "CVV_look_alikes_en_02", "en", "look_alikes",
        "HTTP status codes observed during the load test: 200, 302, 401, 503.",
    ))
    cases.append(_case(
        "CVV_look_alikes_en_03", "en", "look_alikes",
        "Ticket ID INC-447 was escalated; see also INC-589 and INC-126.",
    ))
    cases.append(_case(
        "CVV_look_alikes_de_01", "de", "look_alikes",
        "Zimmer 214 im zweiten Stock, Treffen am Montag um 14:30 Uhr.",
    ))
    cases.append(_case(
        "CVV_look_alikes_it_01", "it", "look_alikes",
        "Appartamento numero 305, terzo piano, citofono 12.",
    ))
    cases.append(_case(
        "CVV_look_alikes_fr_02", "fr", "look_alikes",
        "Code postal 1003 Lausanne — Avenue de la Gare 17, batiment B.",
    ))

    # explicit_negatives
    cases.append(_case(
        "CVV_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "La verification CVV est obligatoire pour les transactions e-commerce. "
        "Le code n'est jamais stocke en base.",
    ))
    cases.append(_case(
        "CVV_explicit_negatives_en_01", "en", "explicit_negatives",
        "PCI-DSS forbids storing the CVV/CVC/CID after authorization completes.",
    ))
    cases.append(_case(
        "CVV_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Pruefziffer (CVV) wird nach der Autorisierung niemals gespeichert.",
    ))
    cases.append(_case(
        "CVV_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il codice CVV non viene mai conservato dopo l'autorizzazione del pagamento.",
    ))

    # adversarial_formatting
    t_adv1 = "CVV : 7  3  7"
    cases.append(_case(
        "CVV_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1, [_span(t_adv1, "7  3  7")],
    ))
    t_adv2 = "CVV:\n5\n1\n9"
    cases.append(_case(
        "CVV_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2,
    ))
    t_adv3 = "CVV: deux-quatre-huit (248)"
    cases.append(_case(
        "CVV_adversarial_formatting_fr_02", "fr", "adversarial_formatting",
        t_adv3, [_span(t_adv3, "248")],
    ))

    # long_context
    long_pos = (
        "Procedure de saisie d'une commande exceptionnelle dans le portail "
        "client.\n\n"
        "Ce document decrit la marche a suivre pour un encaissement manuel "
        "lorsque le terminal de paiement principal est indisponible. La "
        "procedure est strictement reservee aux superviseurs et doit faire "
        "l'objet d'une trace ecrite dans le registre des incidents.\n\n"
        "Etapes:\n"
        "1. Ouvrir le portail client et localiser la commande "
        "(reference CMD-2024-04-78421).\n"
        "2. Verifier l'identite du porteur via piece d'identite officielle.\n"
        "3. Saisir manuellement les informations carte fournies par le "
        "client. Pour ce test, utiliser les valeurs suivantes (carte de "
        "test Stripe): 4242 4242 4242 4242, expiration 04/27, code de "
        "verification CVV: 421. Aucune donnee client reelle ne doit etre "
        "saisie dans cet ecran de demo.\n"
        "4. Valider la transaction et imprimer le recu.\n\n"
        "Codes d'erreur frequents: 200 OK, 302 Found, 401 Unauthorized, "
        "402 Payment Required, 500 Internal Server Error. Ces codes ne "
        "sont PAS des CVV — ils correspondent a la specification HTTP.\n\n"
        "Identifiants techniques mentionnes dans cette procedure: "
        "salle de reunion 312, batiment B, etage 4. Numero d'inventaire "
        "du terminal de secours: ASSET-748. Reference du formulaire papier: "
        "FORM-256.\n\n"
        "Pour toute question, contacter le support niveau 2 au poste 410 "
        "ou via le canal Teams #ops-paiements.\n"
    )
    cases.append(_case(
        "CVV_long_context_fr_01", "fr", "long_context", long_pos,
        [_span(long_pos, "421")],
    ))

    long_neg = (
        "Quarterly capacity report for the Athena platform, Q1 2024.\n\n"
        "Throughput observed during business hours: average 312 requests "
        "per second, peak at 487 rps on March 14 between 10:42 and 10:58. "
        "The 99th percentile latency stayed under 240 ms on the API tier "
        "and 615 ms on the batch tier.\n\n"
        "Error budget consumption: 0.12% out of the 0.50% monthly budget, "
        "comfortably within target. The most frequent error codes were "
        "200 OK (98.7%), 302 Found (0.9%), 401 Unauthorized (0.2%), "
        "503 Service Unavailable (0.1%) and a small 0.1% tail of 504 "
        "Gateway Timeout linked to a downstream provider outage.\n\n"
        "Infrastructure breakdown: 24 application pods, 8 batch workers, "
        "3 caching nodes, 2 database primaries with 4 replicas. Total cost "
        "for Q1: 187k CHF, down 4% from Q4 2023 thanks to right-sizing "
        "on the staging cluster.\n\n"
        "Action items for Q2:\n"
        "- Right-size the staging cluster (target 30% cost reduction).\n"
        "- Investigate the 504 tail on provider X.\n"
        "- Roll out the new caching tier (ticket INFRA-572).\n"
        "- Update the runbook RB-PERF-018.\n\n"
        "Contact: capacity-planning@example.test — meeting room 410 at "
        "the HQ, every second Tuesday at 14:00.\n"
    )
    cases.append(_case(
        "CVV_long_context_en_neg_01", "en", "long_context", long_neg,
    ))

    return {
        "pii_type": "CVV",
        "detector_label": "CVV",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 3. PIN
# ---------------------------------------------------------------------------

def build_pin() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue (typically 4-6 digits)
    t1 = "Code PIN de la carte bancaire : 4827"
    cases.append(_case(
        "PIN_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, "4827")],
    ))
    t2 = "Your one-time PIN code is: 638294 (valid for 5 minutes)"
    cases.append(_case(
        "PIN_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, "638294")],
    ))
    t3 = "Geheimzahl (PIN) der EC-Karte: 7142"
    cases.append(_case(
        "PIN_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, "7142")],
    ))
    t4 = "Codice PIN della SIM: 2839"
    cases.append(_case(
        "PIN_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, "2839")],
    ))

    # canonical_no_clue
    t5 = "Carte n. 4111 1111 1111 1111 — 5184"
    cases.append(_case(
        "PIN_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, "5184")],
    ))
    t6 = "SMS confidential — 729361"
    cases.append(_case(
        "PIN_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, "729361")],
    ))
    t7 = "Vertraulich: 8261"
    cases.append(_case(
        "PIN_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, "8261")],
    ))
    t8 = "Riservato: 4053"
    cases.append(_case(
        "PIN_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, "4053")],
    ))

    # look_alikes (4-6 digit numbers in non-PIN contexts)
    cases.append(_case(
        "PIN_look_alikes_en_01", "en", "look_alikes",
        "The build pipeline produced artifact 4827 yesterday at 15:42 UTC.",
    ))
    cases.append(_case(
        "PIN_look_alikes_fr_01", "fr", "look_alikes",
        "Numero d'employe 638294, badge 7142, parking emplacement 39.",
    ))
    cases.append(_case(
        "PIN_look_alikes_en_02", "en", "look_alikes",
        "Year 2024 saw 4827 deployments across all environments combined.",
    ))
    cases.append(_case(
        "PIN_look_alikes_de_01", "de", "look_alikes",
        "Postleitzahl 4053 Basel, Hausnummer 12, Tuer 4.",
    ))
    cases.append(_case(
        "PIN_look_alikes_it_01", "it", "look_alikes",
        "Pagina 2839 del rapporto annuale, capitolo 4.",
    ))
    cases.append(_case(
        "PIN_look_alikes_en_03", "en", "look_alikes",
        "Order tracking number 729361 was shipped via DHL on Monday.",
    ))
    cases.append(_case(
        "PIN_look_alikes_fr_02", "fr", "look_alikes",
        "Salle 5184, batiment Phoenix, RDV a 9h30 pour le comite de pilotage.",
    ))

    # explicit_negatives
    cases.append(_case(
        "PIN_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Ne jamais communiquer son code PIN par telephone ou par mail.",
    ))
    cases.append(_case(
        "PIN_explicit_negatives_en_01", "en", "explicit_negatives",
        "Your bank will never ask for your PIN. Hang up if anyone does.",
    ))
    cases.append(_case(
        "PIN_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die PIN niemals per E-Mail oder Telefon weitergeben.",
    ))
    cases.append(_case(
        "PIN_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il PIN non viene mai richiesto via email o telefono dalla banca.",
    ))

    # adversarial_formatting
    t_adv1 = "PIN : 4 8 2 7"
    cases.append(_case(
        "PIN_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1, [_span(t_adv1, "4 8 2 7")],
    ))
    t_adv2 = "PIN:\n6-3-8-2-9-4"
    cases.append(_case(
        "PIN_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, "6-3-8-2-9-4")],
    ))
    t_adv3 = "PIN: sieben-eins-vier-zwei (7142)"
    cases.append(_case(
        "PIN_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, "7142")],
    ))

    # long_context positive
    long_pos = (
        "Activation procedure for the new corporate purchasing card.\n\n"
        "This page describes the steps to follow when a finance team member "
        "receives a new physical card from the bank partner. The process "
        "must be completed in person within five business days of card "
        "delivery, otherwise the card is automatically deactivated.\n\n"
        "Materials needed:\n"
        "- The card itself (printed with the cardholder name).\n"
        "- The activation letter (separate envelope, do NOT discard).\n"
        "- A photo ID.\n"
        "- Access to the secure terminal in meeting room 1184 on the second "
        "floor of building C.\n\n"
        "Steps:\n"
        "1. Sign the back of the card with the cardholder signature.\n"
        "2. On the secure terminal, enter the customer reference 638294 "
        "and the temporary PIN provided in the activation letter, which is "
        "8246 for this card. The terminal will prompt for a new PIN of the "
        "cardholder's choice — pick 4 digits not derived from a date of "
        "birth or phone number.\n"
        "3. Validate and wait for the printed confirmation receipt.\n\n"
        "Related identifiers used in this procedure but which are NOT a PIN:\n"
        "- Customer reference 638294.\n"
        "- Terminal asset tag ASSET-7142.\n"
        "- Meeting room number 1184.\n"
        "- Ticket reference FIN-2024-039.\n"
        "- Building access badge slot 25.\n\n"
        "If the activation fails after three attempts, contact the bank "
        "support line at extension 4053 and reference incident number "
        "INC-FIN-572.\n"
    )
    cases.append(_case(
        "PIN_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, "8246")],
    ))

    long_neg = (
        "Inventaire trimestriel du parc materiel — site de Lausanne.\n\n"
        "Cette page recense l'ensemble des actifs physiques affectes au "
        "departement informatique pour le premier trimestre 2024. Les "
        "numeros d'inventaire suivent le format ASSET-NNNN et sont "
        "exclusivement utilises pour le suivi comptable. Aucun de ces "
        "numeros n'est un code PIN, un mot de passe ou une donnee "
        "personnelle.\n\n"
        "Liste partielle:\n"
        "- ASSET-4827: poste de travail Dell, salle 312, etage 2.\n"
        "- ASSET-5184: ecran 27 pouces LG, salle 410, etage 4.\n"
        "- ASSET-7142: imprimante laser HP, hall central.\n"
        "- ASSET-2839: scanner Canon, salle d'archives.\n"
        "- ASSET-4053: visioconference Logitech, salle de reunion grand "
        "comite.\n"
        "- ASSET-638294: serveur de stockage NAS, salle technique TR-7.\n\n"
        "Procedure de remplacement: ouvrir un ticket dans Jira sous le "
        "projet INFRA en mentionnant l'ASSET concerne. Le delai de "
        "traitement est de 5 jours ouvres maximum. Pour toute question "
        "concernant la gestion du parc, contacter le service "
        "asset.management@example.test au poste 4053.\n\n"
        "Rappel important: ne jamais saisir un code PIN sur un equipement "
        "non agree par la DSI, et signaler immediatement toute tentative "
        "de phishing demandant un code PIN par mail ou telephone. La "
        "politique de securite SEC-POL-014 detaille ces regles.\n"
    )
    cases.append(_case(
        "PIN_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "PIN",
        "detector_label": "PIN",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 4. IMEI
# ---------------------------------------------------------------------------

def build_imei() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue (15 digits)
    t1 = "IMEI du telephone professionnel : 490154203237518"
    cases.append(_case(
        "IMEI_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, "490154203237518")],
    ))
    t2 = "Device IMEI: 356938035643809 (verified at activation)"
    cases.append(_case(
        "IMEI_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, "356938035643809")],
    ))
    t3 = "Geraete-IMEI: 869462050274319"
    cases.append(_case(
        "IMEI_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, "869462050274319")],
    ))
    t4 = "IMEI dello smartphone aziendale: 358240051111110"
    cases.append(_case(
        "IMEI_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, "358240051111110")],
    ))

    # canonical_no_clue
    t5 = "Fiche materiel: 490154203237518"
    cases.append(_case(
        "IMEI_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, "490154203237518")],
    ))
    t6 = "Device record: 356938035643809"
    cases.append(_case(
        "IMEI_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, "356938035643809")],
    ))
    t7 = "Geraet: 869462050274319"
    cases.append(_case(
        "IMEI_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, "869462050274319")],
    ))
    t8 = "Dispositivo: 358240051111110"
    cases.append(_case(
        "IMEI_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, "358240051111110")],
    ))

    # look_alikes (long digit sequences that aren't IMEIs)
    cases.append(_case(
        "IMEI_look_alikes_en_01", "en", "look_alikes",
        "Tracking number for the shipment: 749382017456290 via DHL Express.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_en_02", "en", "look_alikes",
        "Patent reference WO/2019/183421058 was cited in the legal review.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_fr_01", "fr", "look_alikes",
        "Numero de commande client 928340172650183 a livrer avant le 15.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_en_03", "en", "look_alikes",
        "Container ID MSCU 583927401628395 cleared customs on March 14.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_de_01", "de", "look_alikes",
        "Sendungsnummer 374821059463720 wurde am Mittwoch zugestellt.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_it_01", "it", "look_alikes",
        "Bolla di consegna 615028471293650 ricevuta in magazzino il martedi.",
    ))
    cases.append(_case(
        "IMEI_look_alikes_en_04", "en", "look_alikes",
        "Build number 482910374625183 was promoted to staging by the bot.",
    ))

    # explicit_negatives
    cases.append(_case(
        "IMEI_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "L'IMEI doit etre fourni au service IT en cas de declaration de "
        "perte ou de vol. Aucun IMEI n'est consigne dans cette page.",
    ))
    cases.append(_case(
        "IMEI_explicit_negatives_en_01", "en", "explicit_negatives",
        "To check your device IMEI, dial *#06# on the keypad of your phone.",
    ))
    cases.append(_case(
        "IMEI_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bei Diebstahl die IMEI-Nummer dem IT-Support melden. Wert hier nicht "
        "hinterlegt.",
    ))
    cases.append(_case(
        "IMEI_explicit_negatives_it_01", "it", "explicit_negatives",
        "Per ottenere l'IMEI digitare *#06# sul tastierino del telefono.",
    ))

    # adversarial_formatting
    t_adv1 = "IMEI : 49 01 54 20 32 37 51 8"
    cases.append(_case(
        "IMEI_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1, [_span(t_adv1, "49 01 54 20 32 37 51 8")],
    ))
    t_adv2 = "IMEI:\n356-938-035-643-809"
    cases.append(_case(
        "IMEI_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, "356-938-035-643-809")],
    ))
    t_adv3 = "IMEI: 8 6 9 4 6 2 0 5 0 2 7 4 3 1 9"
    cases.append(_case(
        "IMEI_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, "8 6 9 4 6 2 0 5 0 2 7 4 3 1 9")],
    ))

    # long_context positive
    long_pos = (
        "Mobile device asset management — Q2 2024 inventory snapshot.\n\n"
        "This page tracks the corporate fleet of mobile devices issued to "
        "employees. Each device is assigned a unique inventory number, "
        "the IMEI is recorded for theft recovery and the cellular plan is "
        "managed centrally via the corporate mobile portal.\n\n"
        "Inventory excerpt (sample of 6 devices out of 248 total):\n\n"
        "| Inventory | Model            | Assignee   | Activation | Notes        |\n"
        "|-----------|------------------|------------|------------|--------------|\n"
        "| ASSET-001 | iPhone 14 Pro    | M. Smith   | 2024-01-15 | active       |\n"
        "| ASSET-002 | Samsung S23      | J. Doe     | 2024-01-22 | active       |\n"
        "| ASSET-003 | iPhone 13        | L. Martin  | 2023-11-08 | active       |\n"
        "| ASSET-004 | iPhone 14        | A. Bernard | 2024-02-19 | active       |\n"
        "| ASSET-005 | Samsung A54      | T. Garcia  | 2024-03-04 | active       |\n"
        "| ASSET-006 | iPhone 14 Pro    | P. Dupont  | 2024-03-18 | active       |\n\n"
        "Device IMEI for ASSET-006 (iPhone 14 Pro assigned to P. Dupont): "
        "353294105672834. This value is required by the carrier when "
        "reporting a stolen device and is also used to lock the device "
        "remotely if needed.\n\n"
        "Tracking numbers for recent shipments (these are NOT IMEIs, they "
        "are courier references): 749382017456290, 928340172650183, "
        "374821059463720, 615028471293650.\n\n"
        "Procedure for a stolen device:\n"
        "1. Lock the device via MDM within 15 minutes of report.\n"
        "2. File an incident in ServiceNow (severity 3).\n"
        "3. Submit a police report with the IMEI.\n"
        "4. Order a replacement via the mobile portal.\n"
    )
    cases.append(_case(
        "IMEI_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, "353294105672834")],
    ))

    long_neg = (
        "Statistiques d'expedition du centre logistique de Renens — Q1 2024.\n\n"
        "Cette page consolide les chiffres de production et de livraison du "
        "centre logistique principal pour le premier trimestre. Toutes les "
        "expeditions sont tracees via des numeros de suivi a 15 chiffres "
        "fournis par les transporteurs partenaires (DHL, La Poste, "
        "Chronopost). Ces numeros NE SONT PAS des IMEI de telephones — ils "
        "n'identifient pas un appareil mobile et ne permettent pas de "
        "geolocaliser une personne.\n\n"
        "Top 10 des expeditions du trimestre par tonnage:\n"
        "- 749382017456290 — 18.4 tonnes — Genève\n"
        "- 928340172650183 — 16.2 tonnes — Berne\n"
        "- 374821059463720 — 15.7 tonnes — Zurich\n"
        "- 615028471293650 — 14.9 tonnes — Lugano\n"
        "- 482910374625183 — 14.1 tonnes — Bale\n"
        "- 583927401628395 — 13.8 tonnes — Lausanne\n"
        "- 729384105672834 — 13.5 tonnes — Sion\n"
        "- 836204195283746 — 13.2 tonnes — Fribourg\n"
        "- 947561038264915 — 12.9 tonnes — Neuchatel\n"
        "- 158293704162735 — 12.6 tonnes — Saint-Gall\n\n"
        "Volumes mensuels: janvier 4820 tonnes, fevrier 5174 tonnes, mars "
        "5638 tonnes. Le pic du trimestre a ete observe le 14 mars avec "
        "847 expeditions traitees en 24 heures.\n\n"
        "Pour rappel, les numeros de tracking ne sont pas couverts par la "
        "politique de protection des donnees personnelles (PDP-018). Ils "
        "peuvent etre conserves pendant 5 ans pour des raisons douanieres "
        "et fiscales. Les eventuelles donnees personnelles associees a une "
        "expedition sont traitees separement et anonymisees apres 90 jours.\n"
    )
    cases.append(_case(
        "IMEI_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "IMEI",
        "detector_label": "IMEI",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 5. BITCOIN_ADDRESS
# ---------------------------------------------------------------------------

def build_bitcoin() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # Use the historical genesis address + a few known test/example addresses
    btc_a = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"  # Satoshi genesis (well-known)
    btc_b = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"  # P2SH example
    btc_c = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"  # Bech32 example
    btc_d = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"  # Famous BTC vanity address

    # canonical_with_clue
    t1 = f"Adresse Bitcoin du wallet de don : {btc_a}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, btc_a)],
    ))
    t2 = f"Bitcoin address (BTC) for testing: {btc_b}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, btc_b)],
    ))
    t3 = f"Bitcoin-Adresse fuer Spenden: {btc_c}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, btc_c)],
    ))
    t4 = f"Indirizzo Bitcoin di donazione: {btc_d}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, btc_d)],
    ))

    # canonical_no_clue
    t5 = btc_a
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, btc_a)],
    ))
    t6 = f"Recipient: {btc_b}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, btc_b)],
    ))
    t7 = f"Empfaenger: {btc_c}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, btc_c)],
    ))
    t8 = f"Destinatario: {btc_d}"
    cases.append(_case(
        "BITCOIN_ADDRESS_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, btc_d)],
    ))

    # look_alikes (SHA hashes, git SHAs, JWT, base64, ObjectIds, S3 keys)
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_01", "en", "look_alikes",
        "Git commit SHA-1: a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9 (master).",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_02", "en", "look_alikes",
        "Docker layer hash: sha256:b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0e1f2",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_03", "en", "look_alikes",
        "Mongo ObjectId for the order: 507f1f77bcf86cd799439011 (24 hex chars).",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_fr_01", "fr", "look_alikes",
        "JWT token (header tronque): eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_04", "en", "look_alikes",
        "S3 object key: s3://bucket-prod/exports/2024/03/14/a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5/data.parquet",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_05", "en", "look_alikes",
        "License key: XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V (16-block format).",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_de_01", "de", "look_alikes",
        "Build-Artefakt-Pfad: artifacts/builds/c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1.tar.gz",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_look_alikes_en_06", "en", "look_alikes",
        "Base64 payload: U29tZUJhc2U2NEVuY29kZWRTdHJpbmdGb3JUZXN0aW5nT25seQ==",
    ))

    # explicit_negatives
    cases.append(_case(
        "BITCOIN_ADDRESS_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Notre societe n'accepte pas les paiements en Bitcoin. Toute demande "
        "d'adresse Bitcoin est consideree comme une tentative d'arnaque.",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_explicit_negatives_en_01", "en", "explicit_negatives",
        "Bitcoin payments are not supported. Do not paste any BTC address here.",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bitcoin-Zahlungen sind nicht zulaessig. Keine Adresse hinterlegt.",
    ))
    cases.append(_case(
        "BITCOIN_ADDRESS_explicit_negatives_it_01", "it", "explicit_negatives",
        "I pagamenti in Bitcoin non sono accettati. Nessun indirizzo registrato.",
    ))

    # adversarial_formatting
    t_adv1 = f"Adresse:\n{btc_a[:10]}\n{btc_a[10:20]}\n{btc_a[20:]}"
    cases.append(_case(
        "BITCOIN_ADDRESS_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2_value = " ".join(btc_b)
    t_adv2 = f"BTC: {t_adv2_value}"
    cases.append(_case(
        "BITCOIN_ADDRESS_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, t_adv2_value)],
    ))
    t_adv3 = f"Wallet:  {btc_c}  "
    cases.append(_case(
        "BITCOIN_ADDRESS_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, btc_c)],
    ))

    # long_context positive
    long_pos = (
        "Charity drive year-end report 2024 — community division.\n\n"
        "This page consolidates the donation tracking for the year-end "
        "campaign. The campaign accepts traditional bank transfers as well "
        "as a few cryptocurrency channels. All transactions are reconciled "
        "monthly with the finance team and reported in the annual statement.\n\n"
        "Donation channels and their reference identifiers:\n\n"
        "| Channel        | Reference                                    | YTD amount (CHF) |\n"
        "|----------------|----------------------------------------------|------------------|\n"
        "| Bank transfer  | IBAN CH9300762011623852957                   | 184'250          |\n"
        "| PayPal         | donations@example.test                       | 47'320           |\n"
        "| Stripe         | acct_1J5KQ2EnExampleAccountIdHere            | 28'940           |\n"
        f"| Bitcoin        | {btc_a} | 12'480           |\n"
        "| Ethereum       | 0x742d35Cc6634C0532925a3b844Bc454e4438f44e   | 8'310            |\n\n"
        "Reconciliation: the Bitcoin address is monitored daily by the "
        "treasury team and any incoming transaction triggers an alert via "
        "the blockchain explorer webhook. The current month-to-date balance "
        "is automatically synced to the finance dashboard.\n\n"
        "Operational identifiers used in this page that are NOT crypto "
        "addresses (do not flag them): git commit SHA "
        "a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9, Docker layer hash "
        "b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0e1f2, "
        "Mongo ObjectId 507f1f77bcf86cd799439011, license token "
        "XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V.\n\n"
        "Audit trail: every quarter the external auditor reviews the chain "
        "of custody for crypto donations. The last review (Q4 2024) raised "
        "no findings. Reference document: AUD-2024-CRYP-018.\n"
    )
    cases.append(_case(
        "BITCOIN_ADDRESS_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, btc_a)],
    ))

    long_neg = (
        "Documentation interne — gestion des artefacts de build dans la "
        "plateforme Athena.\n\n"
        "Cette page decrit la convention de nommage et la procedure de "
        "stockage des artefacts produits par la chaine CI/CD. Aucune "
        "donnee personnelle, identifiant bancaire ou adresse de "
        "cryptomonnaie n'est referencee ici — uniquement des hashes "
        "techniques (SHA-1, SHA-256), des identifiants de container "
        "(Docker layer hashes) et des references S3.\n\n"
        "Conventions de nommage:\n"
        "- Commit Git: SHA-1 sur 40 caracteres hexadecimaux.\n"
        "- Image Docker: digest SHA-256 sur 64 caracteres hexadecimaux.\n"
        "- Mongo ObjectId: 12 octets, 24 caracteres hexadecimaux.\n"
        "- UUID v4: 32 caracteres hexadecimaux groupes 8-4-4-4-12.\n\n"
        "Exemples (techniques, non sensibles):\n"
        "- Commit SHA: a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9\n"
        "- Commit SHA: b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0\n"
        "- Commit SHA: c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1\n"
        "- Docker digest: sha256:d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4\n"
        "- Docker digest: sha256:e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5\n"
        "- ObjectId: 507f1f77bcf86cd799439011\n"
        "- ObjectId: 5f8d0d55b54764421b7156c0\n"
        "- UUID: 550e8400-e29b-41d4-a716-446655440000\n\n"
        "Chemin S3 type pour les artefacts du Sprint 47:\n"
        "s3://athena-artifacts-prod/builds/2024/04/02/a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5/athena-core-4.12.0.tar.gz\n"
        "s3://athena-artifacts-prod/builds/2024/04/02/b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6/athena-api-4.12.0.tar.gz\n\n"
        "Procedure de purge: les artefacts de plus de 90 jours sont "
        "automatiquement deplaces vers le stockage froid (S3 Glacier). La "
        "purge totale intervient apres 18 mois sauf marquage explicite "
        "via le tag retention=permanent. Reference de la politique: "
        "ART-RET-2024-007.\n"
    )
    cases.append(_case(
        "BITCOIN_ADDRESS_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "BITCOIN_ADDRESS",
        "detector_label": "BITCOINADDRESS",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 6. ETHEREUM_ADDRESS
# ---------------------------------------------------------------------------

def build_ethereum() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    eth_a = "0x742d35Cc6634C0532925a3b844Bc454e4438f44e"  # Famous test address
    eth_b = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    eth_c = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    eth_d = "0xdc17f958d2ee523a2206206994597C13D831ec7"

    # canonical_with_clue
    t1 = f"Adresse Ethereum du fonds communautaire : {eth_a}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, eth_a)],
    ))
    t2 = f"Ethereum (ETH) wallet for testing: {eth_b}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, eth_b)],
    ))
    t3 = f"Ethereum-Adresse fuer Spenden: {eth_c}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, eth_c)],
    ))
    t4 = f"Indirizzo Ethereum della tesoreria: {eth_d}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, eth_d)],
    ))

    # canonical_no_clue
    t5 = eth_a
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, eth_a)],
    ))
    t6 = f"Recipient: {eth_b}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, eth_b)],
    ))
    t7 = f"Empfaenger: {eth_c}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, eth_c)],
    ))
    t8 = f"Destinatario: {eth_d}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, eth_d)],
    ))

    # look_alikes (0x prefixed values that are NOT ETH addresses)
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_01", "en", "look_alikes",
        "Memory address in the core dump: 0x00007ffee4b03f80 (stack pointer).",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_02", "en", "look_alikes",
        "Solidity contract opcode prefix 0x6080604052348015 (constructor begins).",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_03", "en", "look_alikes",
        "CRC-32 hex: 0xDEADBEEF and 0xCAFEBABE — used as test sentinels.",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_fr_01", "fr", "look_alikes",
        "Hash SHA-256 (32 octets) sans prefixe: e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_04", "en", "look_alikes",
        "Build hash truncated to 40 chars: a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9 (no 0x prefix, this is git).",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_de_01", "de", "look_alikes",
        "Pointer-Adresse im Dump: 0xffff8000123456ab (Kernel-Bereich).",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_05", "en", "look_alikes",
        "Color palette hex codes: 0xFF6347 (tomato), 0x4682B4 (steel blue).",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_look_alikes_en_06", "en", "look_alikes",
        "Transaction hash on ETH chain (NOT address): 0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2b9b9c5e07d2a4a82b8c0f6e1",
    ))

    # explicit_negatives
    cases.append(_case(
        "ETHEREUM_ADDRESS_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Aucun paiement en ETH n'est autorise via la plateforme interne. "
        "Toute communication d'une adresse Ethereum est consideree suspecte.",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_explicit_negatives_en_01", "en", "explicit_negatives",
        "We do not accept ETH or any cryptocurrency. No Ethereum address listed.",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_explicit_negatives_de_01", "de", "explicit_negatives",
        "Ethereum-Zahlungen sind nicht moeglich. Keine Adresse hinterlegt.",
    ))
    cases.append(_case(
        "ETHEREUM_ADDRESS_explicit_negatives_it_01", "it", "explicit_negatives",
        "I pagamenti in Ethereum non sono accettati. Nessun indirizzo presente.",
    ))

    # adversarial_formatting
    t_adv1 = f"ETH:\n{eth_a[:10]}\n{eth_a[10:30]}\n{eth_a[30:]}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2 = f"Ethereum:  {eth_b}  "
    cases.append(_case(
        "ETHEREUM_ADDRESS_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, eth_b)],
    ))
    t_adv3_value = "0x " + eth_c[2:]
    t_adv3 = f"ETH: {t_adv3_value}"
    cases.append(_case(
        "ETHEREUM_ADDRESS_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3,  # adversarial — no expected span (broken format)
    ))

    # long_context positive
    long_pos = (
        "Treasury report — Q1 2024 — cryptocurrency holdings update.\n\n"
        "This page summarizes the corporate treasury position in digital "
        "assets at the end of the first quarter. The portfolio is reviewed "
        "monthly by the CFO and the risk committee, and the underlying "
        "smart contracts are audited annually by an external firm.\n\n"
        "Active holdings (custody by Fireblocks):\n\n"
        "| Asset    | Wallet address                                        | Balance     |\n"
        "|----------|-------------------------------------------------------|-------------|\n"
        f"| ETH      | {eth_a} | 412.834     |\n"
        "| USDC     | 0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48             | 1'847'200   |\n"
        "| DAI      | 0x6B175474E89094C44Da98b954EedeAC495271d0F             | 250'000     |\n\n"
        "Operational identifiers in this report (NOT crypto addresses): "
        "git commit a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9, Docker layer "
        "hash sha256:b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0e1f2, "
        "memory pointer 0x00007ffee4b03f80, contract opcode prefix "
        "0x6080604052348015, color hex 0xFF6347.\n\n"
        "Risk metrics observed during Q1:\n"
        "- Slippage on USDC swaps: 0.04% average, 0.18% worst-case.\n"
        "- Gas costs paid: 18.2 ETH over 412 transactions.\n"
        "- Custody failover drills: 2 (both passed in under 10 minutes).\n\n"
        "Reference policy document: TREAS-CRYPTO-2024-003.\n"
    )
    cases.append(_case(
        "ETHEREUM_ADDRESS_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, eth_a)],
    ))

    long_neg = (
        "Manuel d'integration de la librairie de cryptographie interne "
        "ETHCRYPT-LIB version 4.2.\n\n"
        "Cette librairie expose plusieurs fonctions de hashing et de "
        "signature numerique utilisees par les services backend. Elle ne "
        "manipule PAS d'adresses de cryptomonnaies — toutes les chaines "
        "hexadecimales mentionnees ici sont des hashes (SHA-256, SHA-3), "
        "des pointeurs memoire ou des constantes magiques.\n\n"
        "API publique:\n"
        "- sha256(bytes) -> bytes32: produit un digest de 32 octets, "
        "represente en hex sur 64 caracteres sans prefixe 0x.\n"
        "- keccak256(bytes) -> bytes32: digest Keccak-256 utilise pour "
        "la verification d'integrite des messages.\n"
        "- ecdsaSign(msg, privKey) -> Signature: signature ECDSA sur "
        "la courbe secp256k1.\n\n"
        "Exemples de hashes produits par la librairie:\n"
        "- sha256(\"hello\")  = "
        "0x2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\n"
        "- sha256(\"world\")  = "
        "0x486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7\n"
        "- keccak256(\"hi\")  = "
        "0xfd0d0c9eafb8c0b94aaab1e3c8d6a2bce8a5f0fc14e6c61e0f76b32f5a52bb5d\n"
        "- keccak256(\"yo\")  = "
        "0xe5b08e3a9c2a8c4cce6d5f4a2b3c7d8e1f9a0b2c3d4e5f6a7b8c9d0e1f2a3b4c\n\n"
        "Adresses memoire observees dans le core dump du dernier crash:\n"
        "- 0x00007ffee4b03f80 — pointeur de pile principal\n"
        "- 0x00007ffee4b03f88 — pointeur de pile auxiliaire\n"
        "- 0x000055a3d4f12000 — adresse de chargement du heap\n"
        "- 0xffff8000123456ab — espace noyau (Linux x86_64)\n\n"
        "Constantes magiques utilisees dans les tests:\n"
        "- 0xDEADBEEF (sentinel d'erreur generique)\n"
        "- 0xCAFEBABE (signature de fichier Java class)\n"
        "- 0xFEEDFACE (marqueur de buffer non initialise)\n\n"
        "Reference du document: CRYPTO-LIB-DOC-2024-007.\n"
    )
    cases.append(_case(
        "ETHEREUM_ADDRESS_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "ETHEREUM_ADDRESS",
        "detector_label": "ETHEREUMADDRESS",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 7. LITECOIN_ADDRESS
# ---------------------------------------------------------------------------

def build_litecoin() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    ltc_a = "LcHKHkLPzWFqe1uK4UvAd3z3vN8pBVNDct"
    ltc_b = "MHpMM8gpBd83NPbgU2vRk2sJ8N1pToabBs"
    ltc_c = "ltc1qg9stkxrszkdqsuj92lm4c7akvk36zvhqw7p6tk"
    ltc_d = "LdP8Qox1VAhCzLJNqrr74YovaWYyNBUWvL"

    # canonical_with_clue
    t1 = f"Adresse Litecoin pour reception : {ltc_a}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, ltc_a)],
    ))
    t2 = f"Litecoin (LTC) deposit address: {ltc_b}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, ltc_b)],
    ))
    t3 = f"Litecoin-Adresse fuer Einzahlung: {ltc_c}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, ltc_c)],
    ))
    t4 = f"Indirizzo Litecoin di prova: {ltc_d}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, ltc_d)],
    ))

    # canonical_no_clue
    t5 = ltc_a
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, ltc_a)],
    ))
    t6 = f"Wallet: {ltc_b}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, ltc_b)],
    ))
    t7 = f"Empfaenger: {ltc_c}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, ltc_c)],
    ))
    t8 = f"Destinatario: {ltc_d}"
    cases.append(_case(
        "LITECOIN_ADDRESS_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, ltc_d)],
    ))

    # look_alikes
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_en_01", "en", "look_alikes",
        "License key: LcHKQR-5T8AZ9P-MQRNF1B-2D4G7H9 (corporate desktop apps).",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_en_02", "en", "look_alikes",
        "Order code MHpMM8 dispatched on 2024-04-02 with batch number BN-7841.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_fr_01", "fr", "look_alikes",
        "Code de validation a 34 caracteres: LdP8Qox1VAhCzLJNqrr74YovaWYyNBUWvL "
        "n'est qu'un identifiant interne, pas une adresse crypto.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_en_03", "en", "look_alikes",
        "S3 bucket name: ltc-archive-prod-2024 (LTC stands for legacy transaction cache).",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_de_01", "de", "look_alikes",
        "Produkt-Charge LcZX9VPLM77AABFQRNF1B2D4G7H9J6L5W3VTQ (intern, kein Crypto).",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_it_01", "it", "look_alikes",
        "Codice articolo MQR5T8AZ9PMQRNF1B2D4G7H9J6L5W3V identifica il lotto Q1.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_look_alikes_en_04", "en", "look_alikes",
        "Hostname pattern: ltc1-prod-fr-zh1 (load test cluster, FR zone, host 1).",
    ))

    # explicit_negatives
    cases.append(_case(
        "LITECOIN_ADDRESS_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Les transactions en Litecoin ne sont pas supportees. Aucune adresse "
        "n'est conservee en base de donnees.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_explicit_negatives_en_01", "en", "explicit_negatives",
        "Litecoin (LTC) payments are out of scope for this platform.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_explicit_negatives_de_01", "de", "explicit_negatives",
        "Litecoin wird derzeit nicht unterstuetzt. Keine LTC-Adresse erfasst.",
    ))
    cases.append(_case(
        "LITECOIN_ADDRESS_explicit_negatives_it_01", "it", "explicit_negatives",
        "Litecoin non e supportato. Nessun indirizzo LTC conservato in archivio.",
    ))

    # adversarial_formatting
    t_adv1 = f"LTC:\n{ltc_a[:10]}\n{ltc_a[10:20]}\n{ltc_a[20:]}"
    cases.append(_case(
        "LITECOIN_ADDRESS_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2 = f"LTC: {ltc_b.upper()}"
    cases.append(_case(
        "LITECOIN_ADDRESS_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2,
    ))
    t_adv3 = f"Litecoin:  {ltc_c}  "
    cases.append(_case(
        "LITECOIN_ADDRESS_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, ltc_c)],
    ))

    # long_context positive
    long_pos = (
        "Rapport trimestriel du programme de tresorerie crypto — T1 2024.\n\n"
        "Ce document presente les positions de l'entite legale en actifs "
        "numeriques pour le premier trimestre de l'annee 2024. Les "
        "operations sont realisees via un prestataire de garde reglemente "
        "(FINMA categorie B) et reconciliees mensuellement avec la "
        "comptabilite generale par l'equipe finance.\n\n"
        "Positions au 31 mars 2024:\n\n"
        "| Actif    | Wallet de garde                                        | Solde         |\n"
        "|----------|--------------------------------------------------------|---------------|\n"
        "| BTC      | 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa                     | 0.0421        |\n"
        "| ETH      | 0x742d35Cc6634C0532925a3b844Bc454e4438f44e             | 12.83         |\n"
        f"| LTC      | {ltc_a}                     | 184.20        |\n\n"
        "Mouvements du trimestre: trois entrees LTC pour un total de "
        "210 LTC, deux sorties pour 25.80 LTC vers le compte technique de "
        "stress test. Aucun mouvement frauduleux detecte par le moteur de "
        "surveillance Chainalysis.\n\n"
        "Identifiants techniques cites dans ce rapport et qui ne sont "
        "PAS des adresses crypto (ne pas les flagger):\n"
        "- Reference du contrat de garde: CONTRAT-CUSTO-2024-018\n"
        "- Numero d'agrement FINMA: FINMA-LIC-77412\n"
        "- Reference Chainalysis du tableau de bord: CHN-DASH-9241\n"
        "- Hostname du service interne: ltc1-prod-fr-zh1\n"
        "- Code projet interne: LcHKQR-5T8AZ9P-MQRNF1B-2D4G7H9 (suivi RH)\n\n"
        "Procedure d'audit: revue trimestrielle par le CFO, audit externe "
        "annuel par PricewaterhouseCoopers. Le dernier audit (annee 2023) "
        "n'a souleve aucun ecart materiel. Reference du rapport d'audit: "
        "PWC-CRYPTO-2024-007.\n"
    )
    cases.append(_case(
        "LITECOIN_ADDRESS_long_context_fr_01", "fr", "long_context", long_pos,
        [_span(long_pos, ltc_a)],
    ))

    long_neg = (
        "Internal load testing campaign — March 2024 — Athena Litecoin-Compatible "
        "Transaction Cache (LTC) service.\n\n"
        "Note: in this codebase, the acronym LTC stands for 'Legacy "
        "Transaction Cache', NOT for Litecoin. The two are completely "
        "unrelated and the load test report below does not contain any "
        "cryptocurrency address.\n\n"
        "Test scope: validate that the LTC service can handle a sustained "
        "load of 8000 requests per second with a P99 latency under 50 ms "
        "and zero data loss over a 4-hour window.\n\n"
        "Test environment:\n"
        "- Cluster: ltc1-prod-eu-zh1 (Zurich), ltc1-prod-eu-zh2 (Zurich), "
        "ltc1-prod-eu-zh3 (Zurich).\n"
        "- Instance type: c5.4xlarge with 32 GB RAM and 1 Gbps network.\n"
        "- Storage: NVMe SSD with 8000 IOPS sustained.\n"
        "- Cache topology: Redis Cluster with 6 primaries and 6 replicas.\n\n"
        "Test results:\n"
        "- Sustained throughput: 8420 req/s (target 8000, exceeded by 5%).\n"
        "- P50 latency: 12 ms.\n"
        "- P95 latency: 28 ms.\n"
        "- P99 latency: 41 ms (target under 50, passed).\n"
        "- Error rate: 0.003%.\n"
        "- Data loss: 0 records.\n\n"
        "Build identifiers tested (commit SHAs and Docker hashes — NOT "
        "Litecoin addresses):\n"
        "- Commit SHA: a3f9c1d2e4b5a6f7c8d9e0a1b2c3d4e5f6a7b8c9\n"
        "- Commit SHA: b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0\n"
        "- Docker digest: sha256:c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3\n"
        "- License token: LcHKQR-5T8AZ9P-MQRNF1B-2D4G7H9-J6L5W3V\n\n"
        "Reference of the test plan: LTC-LOADTEST-2024-Q1.\n"
    )
    cases.append(_case(
        "LITECOIN_ADDRESS_long_context_en_neg_01", "en", "long_context", long_neg,
    ))

    return {
        "pii_type": "LITECOIN_ADDRESS",
        "detector_label": "LITECOINADDRESS",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 8. VEHICLE_VIN
# ---------------------------------------------------------------------------

def build_vin() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # VIN: 17 alphanumeric, excluding I, O, Q.
    vin_a = "1HGCM82633A123456"   # 17 chars, no I/O/Q
    vin_b = "WBA3B5C50DF592846"
    vin_c = "JHMFA16526S012345"   # uses no I/O/Q
    vin_d = "5YJSA1E26HF184729"

    # canonical_with_clue
    t1 = f"Numero de chassis (VIN) du vehicule de fonction : {vin_a}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, vin_a)],
    ))
    t2 = f"Vehicle VIN: {vin_b} (registered 2023-09-12)"
    cases.append(_case(
        "VEHICLE_VIN_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, vin_b)],
    ))
    t3 = f"Fahrzeug-Identifikationsnummer (FIN/VIN): {vin_c}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, vin_c)],
    ))
    t4 = f"Numero di telaio (VIN) del veicolo aziendale: {vin_d}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, vin_d)],
    ))

    # canonical_no_clue
    t5 = f"Carte grise: {vin_a}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, vin_a)],
    ))
    t6 = f"Registration document: {vin_b}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, vin_b)],
    ))
    t7 = f"Fahrzeugbrief: {vin_c}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, vin_c)],
    ))
    t8 = f"Libretto di circolazione: {vin_d}"
    cases.append(_case(
        "VEHICLE_VIN_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, vin_d)],
    ))

    # look_alikes — 17-char codes that aren't VINs (they may even contain I/O/Q)
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_en_01", "en", "look_alikes",
        "Product SKU: QXPLM77QRNF1B2DG4 (note: contains Q which a real VIN cannot).",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_en_02", "en", "look_alikes",
        "Batch number BN18A3F9C1D2E4B5A (lot tracking, 17 chars, not a VIN).",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_fr_01", "fr", "look_alikes",
        "Code produit interne: PROD2024A3F9C1D2E (utilise par la chaine SCM).",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_en_03", "en", "look_alikes",
        "Asset tag for the laptop: ASSET77QRNF1B2D4G (lab inventory, 17 chars).",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_de_01", "de", "look_alikes",
        "Lizenzschluessel: LIC2024QRNF1B2D4G (Desktop-Lizenz, kein Fahrzeug).",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_it_01", "it", "look_alikes",
        "Codice articolo ART2024QRNF1B2D4G del catalogo Q1 2024.",
    ))
    cases.append(_case(
        "VEHICLE_VIN_look_alikes_en_04", "en", "look_alikes",
        "Cargo container ID MSCU58392740162 (11 chars, ISO 6346 standard).",
    ))

    # explicit_negatives
    cases.append(_case(
        "VEHICLE_VIN_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numero VIN du vehicule doit etre transmis a l'assurance dans le "
        "formulaire de declaration. Aucun VIN n'est consigne dans cette page.",
    ))
    cases.append(_case(
        "VEHICLE_VIN_explicit_negatives_en_01", "en", "explicit_negatives",
        "VIN format is 17 alphanumeric characters excluding I, O and Q.",
    ))
    cases.append(_case(
        "VEHICLE_VIN_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Fahrzeug-Identifikationsnummer (FIN) ist 17-stellig.",
    ))
    cases.append(_case(
        "VEHICLE_VIN_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il numero di telaio (VIN) e composto da 17 caratteri alfanumerici.",
    ))

    # adversarial_formatting
    t_adv1 = f"VIN:\n{vin_a[:6]}\n{vin_a[6:12]}\n{vin_a[12:]}"
    cases.append(_case(
        "VEHICLE_VIN_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2_value = " ".join(vin_b)
    t_adv2 = f"VIN: {t_adv2_value}"
    cases.append(_case(
        "VEHICLE_VIN_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, t_adv2_value)],
    ))
    t_adv3 = f"FIN:  {vin_c}  "
    cases.append(_case(
        "VEHICLE_VIN_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, vin_c)],
    ))

    # long_context positive
    long_pos = (
        "Fleet management — Q1 2024 — corporate vehicle assignments.\n\n"
        "This page tracks the company-owned vehicle fleet across the "
        "Lausanne and Zurich offices. Each car is uniquely identified by "
        "its VIN (Vehicle Identification Number), which is recorded in the "
        "insurance file, the maintenance log and the leasing contract. "
        "Personal data of the driver is stored separately in the HR "
        "system, never on this page.\n\n"
        "Fleet excerpt (current assignments):\n\n"
        "| Internal ID | Make / Model    | Driver category | VIN              | License plate |\n"
        "|-------------|------------------|------------------|------------------|---------------|\n"
        "| VEH-001     | Skoda Octavia    | Pool car         | WAUDH48H88K012345| VD 184 290    |\n"
        "| VEH-002     | Tesla Model 3    | Management       | 5YJSA1E26HF184729| GE 471 928    |\n"
        f"| VEH-003     | Honda Civic      | Pool car         | {vin_a}| VD 384 712    |\n"
        "| VEH-004     | BMW 3 Series     | Sales            | WBA3B5C50DF592846| ZH 928 401    |\n\n"
        "VIN format reminder: 17 alphanumeric characters, excluding I, O "
        "and Q to avoid confusion with 1, 0 and a typewriter Q. Maintenance "
        "history is keyed by VIN in the Carlog system. The reference "
        "manual is MAINT-VIN-018.\n\n"
        "Codes used in this page that are NOT VINs (do not flag): asset "
        "tag ASSET77QRNF1B2D4G (laptop inventory, contains Q), batch "
        "number BN18A3F9C1D2E4B5A (parts ordering), product SKU "
        "QXPLM77QRNF1B2DG4 (catalog 2024).\n\n"
        "Insurance contact: fleet@example.test, claims line 0848-FLEET-01.\n"
    )
    cases.append(_case(
        "VEHICLE_VIN_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, vin_a)],
    ))

    long_neg = (
        "Catalogue produits — Q1 2024 — division Industries Manufacturieres.\n\n"
        "Ce catalogue interne liste l'ensemble des references produits "
        "actives pour la division Industries Manufacturieres au premier "
        "trimestre 2024. Les codes SKU font 17 caracteres alphanumeriques "
        "et incluent volontairement les lettres I, O et Q (interdites dans "
        "un VIN automobile) pour eviter toute confusion avec des numeros "
        "de chassis de vehicules. Aucun produit liste ici n'est un "
        "vehicule motorise.\n\n"
        "Top 15 des SKU par chiffre d'affaires Q1:\n"
        "- SKU QXPLM77QRNF1B2DG4 — pieces detachees industrielles\n"
        "- SKU IIO2024QRNF1B2D4G — composants electroniques\n"
        "- SKU ART2024QRNF1B2D4G — articles de bureau\n"
        "- SKU LIC2024QRNF1B2D4G — licences logicielles\n"
        "- SKU BN18A3F9C1D2E4B5A — lots de production batch 18\n"
        "- SKU PROD2024A3F9C1D2E — gamme generique 2024\n"
        "- SKU ASSET77QRNF1B2D4G — actifs informatiques\n"
        "- SKU CATA2024QRNF1B2D4 — produits du catalogue principal\n"
        "- SKU IIIQ2024A3F9C1D2E — composants I/O/Q-rich (test interne)\n"
        "- SKU OOO77QRNF1B2D4GH — produits de la gamme O77\n"
        "- SKU QQQ77QRNF1B2D4GH — produits de la gamme Q77\n"
        "- SKU PARK2024QRNF1B2D — accessoires de parking\n"
        "- SKU CARG58392740162B — references container cargo\n"
        "- SKU FLOT2024QRNF1B2D — composants flotte legere\n"
        "- SKU AUTO2024QRNF1B2D — composants automotive (PIECES SEULES)\n\n"
        "Aucune ligne de ce catalogue ne correspond a un VIN de vehicule "
        "complet — il s'agit uniquement de pieces detachees, composants, "
        "accessoires et licences. La traceabilite est assuree par le code "
        "SKU et le numero de lot batch. La politique de gestion des "
        "references est decrite dans le document REF-MGMT-2024-018.\n\n"
        "Pour toute question, contacter le service catalogue au poste 4053 "
        "ou via Teams sur le canal #ops-catalogue.\n"
    )
    cases.append(_case(
        "VEHICLE_VIN_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "VEHICLE_VIN",
        "detector_label": "VIN",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 9. VEHICLE_REGISTRATION (license plate / VRM)
# ---------------------------------------------------------------------------

def build_vrm() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    plate_fr = "AB-123-CD"  # French style
    plate_ch = "VD 184290"  # Swiss style
    plate_en = "AB12 XYZ"   # UK style
    plate_de = "M-AB 1234"  # German style
    plate_it = "AB123CD"    # Italian style

    # canonical_with_clue
    t1 = f"Plaque d'immatriculation du vehicule : {plate_fr}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, plate_fr)],
    ))
    t2 = f"License plate of the test car: {plate_en}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, plate_en)],
    ))
    t3 = f"Kennzeichen des Firmenwagens: {plate_de}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, plate_de)],
    ))
    t4 = f"Targa di immatricolazione: {plate_it}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, plate_it)],
    ))

    # canonical_no_clue (plate in isolation, no context)
    t5 = f"Parking: {plate_ch}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, plate_ch)],
    ))
    t6 = f"Lot 2 — {plate_en}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, plate_en)],
    ))
    t7 = f"Hof B: {plate_de}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, plate_de)],
    ))
    t8 = f"Garage 4: {plate_it}"
    cases.append(_case(
        "VEHICLE_REGISTRATION_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, plate_it)],
    ))

    # look_alikes
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_en_01", "en", "look_alikes",
        "Conference badge code AB-123-CD was issued to attendee #4827.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_en_02", "en", "look_alikes",
        "Lab sample ID AB12-XYZ logged in the sequencing batch 2024-04-02.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_fr_01", "fr", "look_alikes",
        "Code batiment B-12 secteur Nord, salle 184 du campus.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_fr_02", "fr", "look_alikes",
        "Route postale RP-184 dessert les communes de la region Nord.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_de_01", "de", "look_alikes",
        "Lagerregal-Kennung M-AB 1234 im Lagerhaus B des Logistikzentrums.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_en_03", "en", "look_alikes",
        "Build agent AB-123-CD is offline since 14:32 UTC, see runbook RB-021.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_look_alikes_it_01", "it", "look_alikes",
        "Codice aula AB123CD del padiglione est, capienza 45 persone.",
    ))

    # explicit_negatives
    cases.append(_case(
        "VEHICLE_REGISTRATION_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "La plaque d'immatriculation doit etre transmise a la conciergerie "
        "lors de la reservation d'une place de parking visiteur.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_explicit_negatives_en_01", "en", "explicit_negatives",
        "Please provide your license plate when booking visitor parking on level -2.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_explicit_negatives_de_01", "de", "explicit_negatives",
        "Kennzeichen muss bei der Rezeption fuer Besucherparkplaetze gemeldet werden.",
    ))
    cases.append(_case(
        "VEHICLE_REGISTRATION_explicit_negatives_it_01", "it", "explicit_negatives",
        "La targa va comunicata alla reception per il parcheggio visitatori.",
    ))

    # adversarial_formatting
    t_adv1 = "Plaque : A B - 1 2 3 - C D"
    cases.append(_case(
        "VEHICLE_REGISTRATION_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1, [_span(t_adv1, "A B - 1 2 3 - C D")],
    ))
    t_adv2 = f"Plate:\nAB12\nXYZ"
    cases.append(_case(
        "VEHICLE_REGISTRATION_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2,
    ))
    t_adv3 = f"Kennzeichen:  {plate_de}  "
    cases.append(_case(
        "VEHICLE_REGISTRATION_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, plate_de)],
    ))

    # long_context positive
    long_pos = (
        "Procedure d'acces visiteurs au site de Lausanne — version 4.2.\n\n"
        "Cette procedure decrit les modalites d'accueil et de circulation "
        "des visiteurs externes sur le site principal de Lausanne. Elle "
        "couvre les pre-requis, l'enregistrement, l'attribution du badge, "
        "le parking et les regles de circulation interne.\n\n"
        "Pre-requis avant la visite:\n"
        "- L'invitation doit etre saisie au moins 24 heures a l'avance "
        "dans le portail Visiteurs (lien sur l'intranet).\n"
        "- Le visiteur doit fournir une piece d'identite officielle a la "
        "reception le jour de la visite.\n"
        "- Si le visiteur vient en voiture, la plaque d'immatriculation "
        "doit etre communiquee en avance via le formulaire en ligne. Pour "
        f"l'exemple de cette procedure, on utilise la plaque {plate_fr} "
        "(plaque de demonstration uniquement, pas un vehicule reel).\n\n"
        "Identifiants techniques cites dans cette procedure et qui NE "
        "SONT PAS des plaques d'immatriculation:\n"
        "- Code badge visiteur: VIS-AB-123-CD (format different du systeme "
        "national de plaques)\n"
        "- Reference du formulaire d'invitation: FORM-VIS-2024-Q1\n"
        "- Identifiant de la place de parking visiteur: P-V-12\n"
        "- Code postal du site: 1003 Lausanne\n"
        "- Numero de telephone de la reception: 021 555 18 42\n\n"
        "Apres l'arrivee, le badge visiteur est remis a la reception, "
        "associe au profil dans le systeme de controle d'acces et "
        "desactive automatiquement a la fin de la plage de validite "
        "(generalement la journee meme). Le visiteur est tenu de "
        "respecter les regles de circulation interne et de ne pas se "
        "rendre dans les zones non autorisees signalees par les "
        "panneaux orange.\n"
    )
    cases.append(_case(
        "VEHICLE_REGISTRATION_long_context_fr_01", "fr", "long_context", long_pos,
        [_span(long_pos, plate_fr)],
    ))

    long_neg = (
        "Building access codes — Lausanne campus — internal directory.\n\n"
        "This page lists the building codes, room references and postal "
        "routes used internally to designate the various locations on the "
        "Lausanne campus. None of these codes are vehicle license plates — "
        "they are administrative identifiers managed by the facilities "
        "team.\n\n"
        "Buildings:\n"
        "- B-A — administrative HQ, 4 floors, 184 rooms.\n"
        "- B-B — research, 6 floors, 312 rooms, includes labs L1 to L24.\n"
        "- B-C — operations, 3 floors, 91 rooms, includes the security "
        "operations centre.\n"
        "- B-D — logistics, 2 floors, 47 rooms, includes the print shop.\n"
        "- B-E — events and conference centre, 2 floors, 12 meeting rooms.\n\n"
        "Conference room codes (look like plates but are NOT):\n"
        "- AB-001-CD — main auditorium, 320 seats.\n"
        "- AB-002-CD — secondary auditorium, 184 seats.\n"
        "- AB-003-CD — board room, 24 seats.\n"
        "- AB12-XYZ — restricted committee room, 12 seats.\n"
        "- M-AB 1234 — training room, 30 seats.\n"
        "- AB123CD — small meeting room, 6 seats.\n\n"
        "Postal routes for internal mail delivery:\n"
        "- RP-184 (north sector), RP-312 (south sector), RP-091 (east "
        "sector), RP-047 (west sector), RP-012 (central sector).\n\n"
        "Floor plans and access maps are available on the intranet under "
        "Facilities > Maps. The facilities team can be reached at "
        "facilities@example.test or extension 4053 between 7am and 7pm "
        "on business days. Reference document: FAC-MAP-2024-018.\n"
    )
    cases.append(_case(
        "VEHICLE_REGISTRATION_long_context_en_neg_01", "en", "long_context", long_neg,
    ))

    return {
        "pii_type": "VEHICLE_REGISTRATION",
        "detector_label": "VRM",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 10. ACCOUNT_NAME
# ---------------------------------------------------------------------------

def build_account_name() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t1 = "Titulaire du compte bancaire : Jean Dupont"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, "Jean Dupont")],
    ))
    t2 = "Account holder name: Alice Anderson"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, "Alice Anderson")],
    ))
    t3 = "Kontoinhaber: Hans Mueller"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, "Hans Mueller")],
    ))
    t4 = "Intestatario del conto: Marco Rossi"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, "Marco Rossi")],
    ))

    # canonical_no_clue
    t5 = "IBAN CH9300762011623852957 — Marie Martin"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, "Marie Martin")],
    ))
    t6 = "Beneficiary: Bob Brown"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, "Bob Brown")],
    ))
    t7 = "Empfaenger: Greta Schmidt"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, "Greta Schmidt")],
    ))
    t8 = "Destinatario: Giulia Bianchi"
    cases.append(_case(
        "ACCOUNT_NAME_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, "Giulia Bianchi")],
    ))

    # look_alikes (usernames, project names, hostnames, file names)
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_en_01", "en", "look_alikes",
        "Hostname pattern: athena-api-prod-zh1, athena-batch-prod-zh2.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_en_02", "en", "look_alikes",
        "Project codename: PHOENIX 2024 — internal tracking only.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_fr_01", "fr", "look_alikes",
        "Nom du dossier sur le partage reseau: rapports_annuels_2023.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_en_03", "en", "look_alikes",
        "Branch office: Lausanne Centre. Department: Operations North.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_en_04", "en", "look_alikes",
        "Linux user: appsvc. Group: athena-developers. Shell: /bin/bash.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_de_01", "de", "look_alikes",
        "Dateiname: jahresbericht_2024_final_v3.pdf im Ordner Berichte.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_look_alikes_it_01", "it", "look_alikes",
        "Nome del repository: piattaforma-athena su GitHub Enterprise.",
    ))

    # explicit_negatives
    cases.append(_case(
        "ACCOUNT_NAME_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le titulaire du compte doit etre indique dans le formulaire de "
        "virement. Aucun nom n'est rempli sur ce gabarit modele.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_explicit_negatives_en_01", "en", "explicit_negatives",
        "Account holder name (FREE TEXT FIELD, max 70 chars per SEPA spec).",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_explicit_negatives_de_01", "de", "explicit_negatives",
        "Feld 'Kontoinhaber' im SEPA-Formular: max. 70 Zeichen. Bitte ausfuellen.",
    ))
    cases.append(_case(
        "ACCOUNT_NAME_explicit_negatives_it_01", "it", "explicit_negatives",
        "Compilare il campo 'intestatario del conto' nel modulo SEPA prima dell'invio.",
    ))

    # adversarial_formatting
    t_adv1 = "Titulaire :\nJean\nDupont"
    cases.append(_case(
        "ACCOUNT_NAME_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2 = "Account holder: A L I C E   A N D E R S O N"
    cases.append(_case(
        "ACCOUNT_NAME_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, "A L I C E   A N D E R S O N")],
    ))
    t_adv3 = "Kontoinhaber:  Hans  Mueller  "
    cases.append(_case(
        "ACCOUNT_NAME_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, "Hans  Mueller")],
    ))

    # long_context positive
    long_pos = (
        "Procedure de remboursement de frais professionnels — version 3.1.\n\n"
        "Ce document decrit la procedure standard pour la soumission et le "
        "traitement des notes de frais des collaborateurs. Elle s'applique "
        "uniformement a tous les sites (Lausanne, Geneve, Zurich, Bale, "
        "Lugano) et a tous les niveaux hierarchiques.\n\n"
        "Etape 1 — soumission de la note de frais:\n"
        "Le collaborateur soumet sa note via le portail Expense Manager "
        "accessible depuis l'intranet. Les justificatifs doivent etre "
        "scannes en PDF ou photographies en haute resolution. Le delai de "
        "soumission est de 30 jours apres l'engagement de la depense.\n\n"
        "Etape 2 — validation:\n"
        "La note est routee automatiquement vers le manager direct du "
        "collaborateur pour validation. En cas d'absence, la delegation "
        "remonte automatiquement au N+2. Le delai de validation cible "
        "est de 5 jours ouvres.\n\n"
        "Etape 3 — remboursement:\n"
        "Une fois validee, la note est integree au cycle de paiement "
        "hebdomadaire (chaque vendredi). Le virement est emis sur le "
        "compte bancaire personnel du collaborateur enregistre dans le "
        "SIRH. Pour cet exemple, le compte beneficiaire est l'IBAN "
        "CH9300762011623852957, le titulaire du compte etant Marie Martin "
        "(collaboratrice de demonstration uniquement, pas une employee "
        "reelle).\n\n"
        "Identifiants cites dans cette procedure qui ne sont PAS des noms "
        "de titulaires de compte: nom du portail Expense Manager, nom du "
        "SIRH Workday, nom du repository code expense-mgr, nom du projet "
        "PHOENIX 2024, nom du dossier reseau rapports_finance_2024, nom "
        "du hostname athena-api-prod-zh1.\n\n"
        "Reference du processus: PROC-FIN-2024-018. Contact: "
        "expenses@example.test.\n"
    )
    cases.append(_case(
        "ACCOUNT_NAME_long_context_fr_01", "fr", "long_context", long_pos,
        [_span(long_pos, "Marie Martin")],
    ))

    long_neg = (
        "Internal naming conventions — engineering organization handbook.\n\n"
        "This page captures the naming conventions used across the "
        "engineering organization for codebases, services, hosts, "
        "projects, branches and shared resources. None of the names "
        "below are person names or bank account holders — they are "
        "technical identifiers managed by the platform team.\n\n"
        "Project codenames (alphabetical):\n"
        "- ATHENA — main customer-facing platform.\n"
        "- BOREAS — internal cost management tool.\n"
        "- CYCLOPS — anti-fraud screening engine.\n"
        "- DELPHI — predictive analytics for capacity planning.\n"
        "- EREBUS — log aggregation and observability platform.\n"
        "- FENRIR — incident management workflow.\n"
        "- GALATEA — data quality monitoring system.\n"
        "- HELIOS — single sign-on and identity broker.\n"
        "- IKARUS — sandboxing service for external code execution.\n"
        "- JANUS — API gateway and traffic shaping.\n\n"
        "Hostname conventions:\n"
        "- {service}-{role}-{env}-{zone}{index}\n"
        "- athena-api-prod-zh1, athena-api-prod-zh2, athena-batch-prod-zh1.\n"
        "- helios-auth-stag-ge1, fenrir-worker-dev-bsl1.\n\n"
        "Branch naming:\n"
        "- main, develop, feature/<ticket>-<slug>, hotfix/<ticket>-<slug>.\n"
        "- release/<version> for tagged release candidates.\n\n"
        "Repository names:\n"
        "- piattaforma-athena (formerly athena-monorepo).\n"
        "- piattaforma-helios.\n"
        "- piattaforma-erebus.\n"
        "- piattaforma-janus.\n\n"
        "Shared drive folders:\n"
        "- /shared/finance/rapports_annuels_2024.\n"
        "- /shared/finance/jahresbericht_2024_final_v3.pdf.\n"
        "- /shared/legal/contrats_2024_q1.\n"
        "- /shared/marketing/campagne_phoenix_2024.\n\n"
        "Linux service accounts (NOT person names): appsvc, dbsvc, "
        "monitorsvc, backupsvc. These are unprivileged accounts used by "
        "the platform services to access shared resources.\n\n"
        "Reference: NAMING-CONV-2024-007 in the engineering wiki.\n"
    )
    cases.append(_case(
        "ACCOUNT_NAME_long_context_en_neg_01", "en", "long_context", long_neg,
    ))

    return {
        "pii_type": "ACCOUNT_NAME",
        "detector_label": "ACCOUNTNAME",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 11. BANK_ACCOUNT
# ---------------------------------------------------------------------------

def build_bank_account() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    iban_ch = "CH9300762011623852957"   # Swiss test IBAN
    iban_fr = "FR1420041010050500013M02606"
    iban_de = "DE89370400440532013000"
    iban_it = "IT60X0542811101000000123456"

    # canonical_with_clue
    t1 = f"IBAN du compte de remboursement : {iban_ch}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, iban_ch)],
    ))
    t2 = f"Bank account (IBAN) for the wire: {iban_fr}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, iban_fr)],
    ))
    t3 = f"Kontonummer (IBAN) fuer Ueberweisung: {iban_de}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, iban_de)],
    ))
    t4 = f"Coordinate bancarie (IBAN) per il bonifico: {iban_it}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, iban_it)],
    ))

    # canonical_no_clue
    t5 = iban_ch
    cases.append(_case(
        "BANK_ACCOUNT_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, iban_ch)],
    ))
    t6 = f"Wire to: {iban_fr}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, iban_fr)],
    ))
    t7 = f"Ziel: {iban_de}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, iban_de)],
    ))
    t8 = f"Destinazione: {iban_it}"
    cases.append(_case(
        "BANK_ACCOUNT_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, iban_it)],
    ))

    # look_alikes (long alphanumeric strings that aren't IBANs)
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_en_01", "en", "look_alikes",
        "Stripe acct id: acct_1J5KQ2EnAB12CDExampleAccountId — billing only.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_en_02", "en", "look_alikes",
        "S3 bucket ARN: arn:aws:s3:::corp-data-lake-prod-eu-west-1-2024-q1.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_fr_01", "fr", "look_alikes",
        "Reference de la commande achat: PO-2024-Q1-FR1420041010050500013M02606 "
        "(reutilise par erreur d'un format IBAN dans le SCM).",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_en_03", "en", "look_alikes",
        "License key: LIC-CH-2024-Q1-XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_de_01", "de", "look_alikes",
        "Tracking-Nummer DHL: 749382017456290374821059463720 (zwei Sendungen).",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_it_01", "it", "look_alikes",
        "Codice cliente CRM: IT60-CLT-0542811101-000000-123456 (NON e un IBAN).",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_look_alikes_en_04", "en", "look_alikes",
        "Order reference: DE89-PROD-37040044-0532013000 (matches IBAN format coincidentally).",
    ))

    # explicit_negatives
    cases.append(_case(
        "BANK_ACCOUNT_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le compte bancaire de l'entreprise est confidentiel. Aucun IBAN "
        "n'est mentionne dans cette page publique.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_explicit_negatives_en_01", "en", "explicit_negatives",
        "Bank account information is provided on request only via secure channel.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_explicit_negatives_de_01", "de", "explicit_negatives",
        "Bankverbindung wird auf Anfrage ueber den sicheren Kanal mitgeteilt.",
    ))
    cases.append(_case(
        "BANK_ACCOUNT_explicit_negatives_it_01", "it", "explicit_negatives",
        "Le coordinate bancarie verranno fornite su richiesta tramite canale sicuro.",
    ))

    # adversarial_formatting
    t_adv1 = f"IBAN:\n{iban_ch[:4]}\n{iban_ch[4:12]}\n{iban_ch[12:]}"
    cases.append(_case(
        "BANK_ACCOUNT_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2_value = " ".join([iban_fr[i:i+4] for i in range(0, len(iban_fr), 4)])
    t_adv2 = f"IBAN: {t_adv2_value}"
    cases.append(_case(
        "BANK_ACCOUNT_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, t_adv2_value)],
    ))
    t_adv3 = f"IBAN:  {iban_de}  "
    cases.append(_case(
        "BANK_ACCOUNT_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, iban_de)],
    ))

    # long_context positive
    long_pos = (
        "Procedure de paiement fournisseur — version 5.0 — mars 2024.\n\n"
        "Ce document decrit le processus de paiement des factures "
        "fournisseurs en mode automatise via la plateforme financiere "
        "Athena-Finance. Il couvre la reception de la facture, sa "
        "validation, l'attribution du compte bancaire beneficiaire et "
        "l'execution du virement SEPA ou international.\n\n"
        "Reception de la facture:\n"
        "Les factures arrivent par EDI, par mail (boite scan@example.test) "
        "ou via le portail fournisseur. L'OCR extrait automatiquement les "
        "champs cles (numero de facture, montant, IBAN, BIC, devise) et "
        "soumet la facture au workflow de validation.\n\n"
        "Validation:\n"
        "Chaque facture est validee par le manager du centre de cout "
        "concerne. Pour les montants superieurs a 50 000 CHF, une double "
        "validation est requise (manager + directeur financier). Les "
        "ecarts entre le bon de commande et la facture sont signales et "
        "doivent etre justifies.\n\n"
        "Compte beneficiaire:\n"
        "L'IBAN du fournisseur est verifie automatiquement contre le "
        "master data SAP. Pour cet exemple, le fournisseur de "
        "demonstration est domicilie en Suisse, son IBAN etant "
        f"{iban_ch}. Toute discordance entre l'IBAN extrait par OCR et "
        "celui enregistre dans SAP genere une alerte de niveau 2 et bloque "
        "le paiement jusqu'a verification manuelle.\n\n"
        "Identifiants techniques cites dans cette procedure et qui NE "
        "SONT PAS des IBAN: reference Stripe acct_1J5KQ2EnAB12CDExample, "
        "ARN S3 arn:aws:s3:::corp-data-lake-prod-eu-west-1-2024-q1, code "
        "produit LIC-CH-2024-Q1-XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V, "
        "tracking DHL 749382017456290.\n\n"
        "Reference du processus: PROC-AP-2024-018.\n"
    )
    cases.append(_case(
        "BANK_ACCOUNT_long_context_fr_01", "fr", "long_context", long_pos,
        [_span(long_pos, iban_ch)],
    ))

    long_neg = (
        "Procurement reference catalogue — vendor master data conventions.\n\n"
        "This page documents the format of various reference codes used "
        "across the procurement function. None of the codes listed below "
        "are IBAN bank account numbers — they are vendor identifiers, "
        "contract references, S3 ARNs, license keys and tracking numbers "
        "managed by the vendor data team.\n\n"
        "Vendor reference codes (format: 2 letters + 12 digits + 10 alphanum):\n"
        "- IT60X0542811101000000123450 — vendor IT-VENDOR-001 (NOT IBAN).\n"
        "- FR1420041010050500013M02600 — vendor FR-VENDOR-007 (NOT IBAN).\n"
        "- DE89370400440532013005 — vendor DE-VENDOR-013 (NOT IBAN).\n"
        "- CH9300762011623852950 — vendor CH-VENDOR-018 (NOT IBAN).\n\n"
        "Stripe account identifiers (these are platform-side identifiers, "
        "not bank accounts):\n"
        "- acct_1J5KQ2EnAB12CDExampleAccountId1\n"
        "- acct_1J5KQ2EnEF34GHExampleAccountId2\n"
        "- acct_1J5KQ2EnIJ56KLExampleAccountId3\n\n"
        "S3 bucket ARNs containing vendor exports:\n"
        "- arn:aws:s3:::corp-data-lake-prod-eu-west-1-2024-q1\n"
        "- arn:aws:s3:::corp-data-lake-prod-eu-west-1-2024-q2\n"
        "- arn:aws:s3:::corp-data-lake-stag-eu-west-1\n\n"
        "License keys delivered to internal teams (16 blocks of 4):\n"
        "- LIC-CH-2024-Q1-XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V\n"
        "- LIC-FR-2024-Q1-XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V\n"
        "- LIC-DE-2024-Q1-XKQR-5T8A-Z9PM-3RNF-1B2D-4G7H-9J6L-5W3V\n\n"
        "Tracking numbers from logistics providers (15-30 digits):\n"
        "- DHL: 749382017456290, 928340172650183, 374821059463720.\n"
        "- UPS: 615028471293650, 482910374625183, 583927401628395.\n"
        "- FedEx: 729384105672834, 836204195283746, 947561038264915.\n\n"
        "Note: real vendor IBAN values are stored in SAP and never "
        "reproduced in Confluence pages — they are accessible only via "
        "the secure vendor master data screen with audit logging.\n\n"
        "Reference document: VENDOR-MD-CONV-2024-007.\n"
    )
    cases.append(_case(
        "BANK_ACCOUNT_long_context_en_neg_01", "en", "long_context", long_neg,
    ))

    return {
        "pii_type": "BANK_ACCOUNT",
        "detector_label": "BANKACCOUNT",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# 12. CREDIT_CARD
# ---------------------------------------------------------------------------

def build_credit_card() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # Standard public Stripe test card numbers
    cc_a = "4111 1111 1111 1111"   # Visa test
    cc_b = "4242 4242 4242 4242"   # Stripe Visa test
    cc_c = "5555 5555 5555 4444"   # MasterCard test
    cc_d = "3782 822463 10005"     # Amex test (4-6-5)
    cc_e = "5105 1051 0510 5100"   # MasterCard test alt

    # canonical_with_clue
    t1 = f"Numero de carte de credit pour le test E2E : {cc_a}"
    cases.append(_case(
        "CREDIT_CARD_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t1,
        [_span(t1, cc_a)],
    ))
    t2 = f"Credit card number (Stripe test): {cc_b}"
    cases.append(_case(
        "CREDIT_CARD_canonical_with_clue_en_01", "en", "canonical_with_clue", t2,
        [_span(t2, cc_b)],
    ))
    t3 = f"Kreditkartennummer (Testkonto): {cc_c}"
    cases.append(_case(
        "CREDIT_CARD_canonical_with_clue_de_01", "de", "canonical_with_clue", t3,
        [_span(t3, cc_c)],
    ))
    t4 = f"Numero della carta di credito (Amex test): {cc_d}"
    cases.append(_case(
        "CREDIT_CARD_canonical_with_clue_it_01", "it", "canonical_with_clue", t4,
        [_span(t4, cc_d)],
    ))

    # canonical_no_clue
    t5 = cc_a
    cases.append(_case(
        "CREDIT_CARD_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t5,
        [_span(t5, cc_a)],
    ))
    t6 = f"Charge: {cc_b}"
    cases.append(_case(
        "CREDIT_CARD_canonical_no_clue_en_01", "en", "canonical_no_clue", t6,
        [_span(t6, cc_b)],
    ))
    t7 = f"Bezahlung: {cc_e}"
    cases.append(_case(
        "CREDIT_CARD_canonical_no_clue_de_01", "de", "canonical_no_clue", t7,
        [_span(t7, cc_e)],
    ))
    t8 = f"Pagamento: {cc_c}"
    cases.append(_case(
        "CREDIT_CARD_canonical_no_clue_it_01", "it", "canonical_no_clue", t8,
        [_span(t8, cc_c)],
    ))

    # look_alikes (16-digit numbers that aren't cards)
    cases.append(_case(
        "CREDIT_CARD_look_alikes_en_01", "en", "look_alikes",
        "DHL tracking number for shipment Q1: 7493 8201 7456 2904.",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_en_02", "en", "look_alikes",
        "Order reference: 9283 4017 2650 1830 placed via the legacy portal.",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_fr_01", "fr", "look_alikes",
        "Numero de patent europeen: EP 1234 5678 9012 3456 — depose en 2018.",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_en_03", "en", "look_alikes",
        "Container ID combined: 5839 2740 1628 3950 (MSCU prefix removed).",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_en_04", "en", "look_alikes",
        "Build number sequence: 4829 1037 4625 1830 (CI artifact ID).",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_de_01", "de", "look_alikes",
        "Versandbeleg-Nummer: 6150 2847 1293 6500 (interne Logistik).",
    ))
    cases.append(_case(
        "CREDIT_CARD_look_alikes_it_01", "it", "look_alikes",
        "Numero bolla di consegna: 8362 0419 5283 7460 del fornitore X.",
    ))

    # explicit_negatives
    cases.append(_case(
        "CREDIT_CARD_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Aucun numero de carte de credit reel ne doit etre saisi dans "
        "Confluence. Utiliser uniquement les cartes de test Stripe.",
    ))
    cases.append(_case(
        "CREDIT_CARD_explicit_negatives_en_01", "en", "explicit_negatives",
        "PCI-DSS forbids storing the full PAN (credit card number) outside "
        "the secure payment vault.",
    ))
    cases.append(_case(
        "CREDIT_CARD_explicit_negatives_de_01", "de", "explicit_negatives",
        "Echte Kreditkartennummern duerfen niemals in Confluence eingetragen werden.",
    ))
    cases.append(_case(
        "CREDIT_CARD_explicit_negatives_it_01", "it", "explicit_negatives",
        "I numeri reali di carta di credito non devono essere salvati in Confluence.",
    ))

    # adversarial_formatting
    t_adv1 = f"Carte:\n{cc_a[:4]}\n{cc_a[5:9]}\n{cc_a[10:14]}\n{cc_a[15:]}"
    cases.append(_case(
        "CREDIT_CARD_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        t_adv1,
    ))
    t_adv2 = "Card: 4 2 4 2  4 2 4 2  4 2 4 2  4 2 4 2"
    cases.append(_case(
        "CREDIT_CARD_adversarial_formatting_en_01", "en", "adversarial_formatting",
        t_adv2, [_span(t_adv2, "4 2 4 2  4 2 4 2  4 2 4 2  4 2 4 2")],
    ))
    t_adv3 = f"Kreditkarte:  {cc_c}  "
    cases.append(_case(
        "CREDIT_CARD_adversarial_formatting_de_01", "de", "adversarial_formatting",
        t_adv3, [_span(t_adv3, cc_c)],
    ))

    # long_context positive
    long_pos = (
        "Test plan — end-to-end payment flow — Athena checkout — Q2 2024.\n\n"
        "This document captures the full set of scenarios executed during "
        "the quarterly E2E regression of the Athena checkout. The plan "
        "exercises the happy path, the most common error cases and a "
        "handful of edge conditions identified during last quarter's "
        "incident review. The test suite runs nightly against the "
        "staging environment with the standard Stripe test cards.\n\n"
        "Test scenarios:\n\n"
        "1. Happy path — single item purchase with a successful Visa "
        "card. Card number used: 4242 4242 4242 4242, expiry 04/27, "
        "CVV 421. Expected outcome: order confirmed, receipt emailed "
        "within 30 seconds, status updated to PAID in the order ledger.\n\n"
        "2. Insufficient funds — same card with the test-mode trigger "
        "to simulate a 'card declined' response. Expected outcome: order "
        "stays in PENDING, retry banner shown to the customer.\n\n"
        "3. CVV check failure — same card, wrong CVV. Expected outcome: "
        "order rejected, no charge attempted.\n\n"
        f"4. International card — Amex test {cc_d}, expiry 12/26, CVV "
        "1234. Expected outcome: order confirmed, currency conversion "
        "applied, receipt shows both the original and converted amount.\n\n"
        "5. 3DS challenge — card with the 3DS test trigger. Expected "
        "outcome: customer redirected to the issuer's authentication "
        "page, returns to the checkout with the order completed.\n\n"
        "Identifiers cited in this test plan that are NOT credit card "
        "numbers: DHL tracking 7493 8201 7456 2904, order reference "
        "9283 4017 2650 1830, container ID 5839 2740 1628 3950, build "
        "number 4829 1037 4625 1830.\n\n"
        "Reference: QA-E2E-PAY-2024-Q2.\n"
    )
    cases.append(_case(
        "CREDIT_CARD_long_context_en_01", "en", "long_context", long_pos,
        [_span(long_pos, cc_d)],
    ))

    long_neg = (
        "Rapport annuel des incidents de production — division Athena — annee 2023.\n\n"
        "Ce rapport consolide l'ensemble des incidents de severite 1 et 2 "
        "survenus sur la plateforme Athena durant l'annee 2023. Il presente "
        "le nombre d'incidents par mois, la duree moyenne de resolution, "
        "les causes racines identifiees et les actions correctives mises en "
        "place. Aucun numero de carte de credit, IBAN ou autre donnee "
        "personnelle n'est mentionne dans ce rapport — uniquement des "
        "references techniques.\n\n"
        "Volumetrie 2023:\n"
        "- Incidents severite 1: 4 (avril x1, juillet x2, novembre x1).\n"
        "- Incidents severite 2: 18.\n"
        "- Incidents severite 3: 147.\n"
        "- Duree moyenne de resolution sev-1: 2h47.\n"
        "- Duree moyenne de resolution sev-2: 8h12.\n\n"
        "Tableau des incidents sev-1:\n\n"
        "| Reference incident | Date         | Duree | Cause racine                |\n"
        "|--------------------|--------------|-------|----------------------------|\n"
        "| INC-2023-0418      | 2023-04-18   | 3h11  | Mise a jour DB sans test    |\n"
        "| INC-2023-0712      | 2023-07-12   | 2h44  | Saturation cache redis      |\n"
        "| INC-2023-0729      | 2023-07-29   | 2h18  | Panne DNS upstream          |\n"
        "| INC-2023-1108      | 2023-11-08   | 3h02  | Pic de trafic non absorbe   |\n\n"
        "References techniques citees dans le rapport (NE SONT PAS des "
        "numeros de carte):\n"
        "- Tracking colis (incident livraison): 7493 8201 7456 2904\n"
        "- Reference commande (rapport client): 9283 4017 2650 1830\n"
        "- Conteneur logistique cite: 5839 2740 1628 3950\n"
        "- Numero de build de la regression: 4829 1037 4625 1830\n"
        "- Bon de transport: 6150 2847 1293 6500\n"
        "- Bolla logistica: 8362 0419 5283 7460\n\n"
        "Actions correctives prioritaires pour 2024:\n"
        "1. Mise en place d'un test de charge automatise sur le cache "
        "redis (suite a INC-2023-0712).\n"
        "2. Migration vers un DNS resilient multi-fournisseurs (suite a "
        "INC-2023-0729).\n"
        "3. Doublement de la capacite de l'API gateway (suite a "
        "INC-2023-1108).\n"
        "4. Formation des operations sur les procedures de mise a jour "
        "DB (suite a INC-2023-0418).\n\n"
        "Reference du rapport: RAP-INC-2023-ANNUAL. Auteurs: equipe SRE Athena.\n"
    )
    cases.append(_case(
        "CREDIT_CARD_long_context_fr_neg_01", "fr", "long_context", long_neg,
    ))

    return {
        "pii_type": "CREDIT_CARD",
        "detector_label": "CREDITCARD",
        "threshold": 0.85,
        "cases": cases,
    }


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def main() -> None:
    here = Path(__file__).resolve().parent
    builders = {
        "PASSWORD": build_password,
        "CVV": build_cvv,
        "PIN": build_pin,
        "IMEI": build_imei,
        "BITCOIN_ADDRESS": build_bitcoin,
        "ETHEREUM_ADDRESS": build_ethereum,
        "LITECOIN_ADDRESS": build_litecoin,
        "VEHICLE_VIN": build_vin,
        "VEHICLE_REGISTRATION": build_vrm,
        "ACCOUNT_NAME": build_account_name,
        "BANK_ACCOUNT": build_bank_account,
        "CREDIT_CARD": build_credit_card,
    }
    for pii_type, builder in builders.items():
        payload = builder()
        # Self-check every expected span before writing.
        for case in payload["cases"]:
            for span in case["expected_spans"]:
                slice_ = case["text"][span["start"]:span["end"]]
                assert slice_ == span["value"], (
                    f"Self-check failed for {case['id']}: "
                    f"text[{span['start']}:{span['end']}]={slice_!r} "
                    f"but span.value={span['value']!r}"
                )
        out_path = here / f"{pii_type}.json"
        with out_path.open("w", encoding="utf-8") as fp:
            json.dump(payload, fp, indent=2, ensure_ascii=False)
        print(f"wrote {out_path.name} — {len(payload['cases'])} cases")


if __name__ == "__main__":
    main()
