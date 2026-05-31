"""Banking / payment PII fixtures for the GLiNER2 FP/FN evaluation harness.

One ``build_<type>()`` per canonical PII type, each returning the detector
label, a shared 0.50 threshold and a list of axis-tagged cases. Spans are always
computed via :func:`_span` so the runner's ``text[start:end] == value``
invariant holds byte-exact.
"""
from __future__ import annotations

from typing import Any, Dict, List

from _fixture_helpers import _span, _case


def build_iban() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Veuillez virer le montant sur l'IBAN CH9300762011623852957 svp."
    cases.append(_case("IBAN_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "CH9300762011623852957")]))
    t = "Please transfer to IBAN FR1420041010050500013M02606 by Friday."
    cases.append(_case("IBAN_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "FR1420041010050500013M02606")]))
    t = "Bitte ueberweisen Sie auf das IBAN DE89370400440532013000 heute."
    cases.append(_case("IBAN_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "DE89370400440532013000")]))
    t = "Effettuare il bonifico sull'IBAN IT60X0542811101000000123456 grazie."
    cases.append(_case("IBAN_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "IT60X0542811101000000123456")]))

    # canonical_no_clue
    t = "CH9300762011623852957"
    cases.append(_case("IBAN_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "CH9300762011623852957")]))
    t = "FR1420041010050500013M02606"
    cases.append(_case("IBAN_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "FR1420041010050500013M02606")]))
    t = "DE89370400440532013000"
    cases.append(_case("IBAN_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "DE89370400440532013000")]))
    t = "IT60X0542811101000000123456"
    cases.append(_case("IBAN_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "IT60X0542811101000000123456")]))

    # look_alikes (git SHA, n de serie, code build) -> aucun span
    cases.append(_case("IBAN_look_alikes_en_01", "en", "look_alikes",
                       "The fix landed in commit 9fceb02d8e74f3e0aab1c2d3b4567890abcdef12."))
    cases.append(_case("IBAN_look_alikes_fr_01", "fr", "look_alikes",
                       "Numero de serie de l'appareil: SN-AB1234567890CDEF345."))
    cases.append(_case("IBAN_look_alikes_de_01", "de", "look_alikes",
                       "Build-Kennung des Releases: BUILD20260311443A0019X22."))
    cases.append(_case("IBAN_look_alikes_it_01", "it", "look_alikes",
                       "Codice articolo a catalogo: ART9300762011623852957QX."))
    cases.append(_case("IBAN_look_alikes_en_02", "en", "look_alikes",
                       "Docker image digest sha256 0762011623852957013000aa is cached."))
    cases.append(_case("IBAN_look_alikes_fr_02", "fr", "look_alikes",
                       "Reference interne dossier DE37040044053201300012 (non bancaire)."))

    # explicit_negatives (mot-cle sans valeur)
    cases.append(_case("IBAN_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Merci de me communiquer votre IBAN par retour de courrier."))
    cases.append(_case("IBAN_explicit_negatives_en_01", "en", "explicit_negatives",
                       "We could not locate an IBAN for this account, please confirm."))
    cases.append(_case("IBAN_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Das IBAN-Feld im Formular ist leider noch nicht ausgefuellt."))
    cases.append(_case("IBAN_explicit_negatives_it_01", "it", "explicit_negatives",
                       "L'IBAN richiesto non e ancora stato fornito dal cliente."))

    # adversarial_formatting
    t = "IBAN: CH93 0076 2011 6238 5295 7 (format groupe)."
    cases.append(_case("IBAN_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "CH93 0076 2011 6238 5295 7")]))
    t = "Compte: DE89 3704 0044 0532 0130 00 a utiliser."
    cases.append(_case("IBAN_adversarial_formatting_de_01", "de", "adversarial_formatting", t,
                       [_span(t, "DE89 3704 0044 0532 0130 00")]))
    # trop fragmente -> aucun span
    cases.append(_case("IBAN_adversarial_formatting_en_01", "en", "adversarial_formatting",
                       "C-H-9-3-0-0-7-6-2-0-1-1 is split across columns in the export."))

    # long_context
    t = ("Compte rendu de la reunion trimestrielle: apres une longue discussion sur "
         "le budget marketing et la roadmap produit, le tresorier a rappele que le "
         "remboursement devra etre vire sur l'IBAN CH9300762011623852957 avant la "
         "cloture comptable, puis la seance a aborde les recrutements a venir.")
    cases.append(_case("IBAN_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "CH9300762011623852957")]))
    cases.append(_case("IBAN_long_context_en_01", "en", "long_context",
                       "The onboarding guide explains how the finance team validates bank "
                       "transfers, reconciles the monthly ledger and archives invoices, but "
                       "it never lists any concrete account identifier in this paragraph."))

    return {"pii_type": "IBAN", "detector_label": "iban", "threshold": 0.50, "cases": cases}


def build_bank_account() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le numero de compte bancaire est 0023 6589 12 chez notre banque."
    cases.append(_case("BANK_ACCOUNT_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "0023 6589 12")]))
    t = "Your bank account number is 12-34567-8, please keep it confidential."
    cases.append(_case("BANK_ACCOUNT_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "12-34567-8")]))
    t = "Die Kontonummer lautet 532013000 bei der Hausbank."
    cases.append(_case("BANK_ACCOUNT_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "532013000")]))
    t = "Il numero di conto corrente e 0023 6589 12 presso la filiale."
    cases.append(_case("BANK_ACCOUNT_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "0023 6589 12")]))

    # canonical_no_clue
    t = "0023 6589 12"
    cases.append(_case("BANK_ACCOUNT_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "0023 6589 12")]))
    t = "12-34567-8"
    cases.append(_case("BANK_ACCOUNT_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "12-34567-8")]))
    t = "532013000"
    cases.append(_case("BANK_ACCOUNT_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "532013000")]))
    t = "0023 6589 12"
    cases.append(_case("BANK_ACCOUNT_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "0023 6589 12")]))

    # look_alikes (n client, n commande) -> aucun span
    cases.append(_case("BANK_ACCOUNT_look_alikes_fr_01", "fr", "look_alikes",
                       "Votre numero de client fidelite est CL-0023-6589 dans l'espace perso."))
    cases.append(_case("BANK_ACCOUNT_look_alikes_en_01", "en", "look_alikes",
                       "Your order number ORD-12-34567 has shipped this morning."))
    cases.append(_case("BANK_ACCOUNT_look_alikes_de_01", "de", "look_alikes",
                       "Ihre Kundennummer KD-532-013 finden Sie oben rechts im Portal."))
    cases.append(_case("BANK_ACCOUNT_look_alikes_it_01", "it", "look_alikes",
                       "Il numero d'ordine 0023-6589 risulta gia evaso dal magazzino."))
    cases.append(_case("BANK_ACCOUNT_look_alikes_en_02", "en", "look_alikes",
                       "Ticket reference TCK-12-34567-8 was closed by support yesterday."))
    cases.append(_case("BANK_ACCOUNT_look_alikes_fr_02", "fr", "look_alikes",
                       "Le numero de facture FA-2026-006589 est joint en piece jointe."))

    # explicit_negatives
    cases.append(_case("BANK_ACCOUNT_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Merci de nous transmettre votre numero de compte bancaire."))
    cases.append(_case("BANK_ACCOUNT_explicit_negatives_en_01", "en", "explicit_negatives",
                       "We have no bank account number on file for this customer yet."))
    cases.append(_case("BANK_ACCOUNT_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Die Kontonummer wurde im Antrag nicht angegeben."))
    cases.append(_case("BANK_ACCOUNT_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Il numero di conto corrente non risulta ancora registrato."))

    # adversarial_formatting
    t = "Compte n. 0 0 2 3 6 5 8 9 1 2 epele a l'oral lors de l'appel."
    cases.append(_case("BANK_ACCOUNT_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
                       t))  # trop fragmente -> aucun span
    t = "Account: 0023-6589-12 (tirets a la place des espaces)."
    cases.append(_case("BANK_ACCOUNT_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "0023-6589-12")]))
    t = "Konto Nr 532 013 000 in der Bestaetigung."
    cases.append(_case("BANK_ACCOUNT_adversarial_formatting_de_01", "de", "adversarial_formatting", t,
                       [_span(t, "532 013 000")]))

    # long_context
    t = ("Suite a notre echange telephonique de ce matin concernant le litige sur la "
         "derniere facture et les penalites de retard, je vous confirme que le "
         "remboursement sera credite sur le compte bancaire 0023 6589 12 des reception "
         "de votre accord ecrit, comme convenu avec le service comptabilite.")
    cases.append(_case("BANK_ACCOUNT_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "0023 6589 12")]))
    cases.append(_case("BANK_ACCOUNT_long_context_en_01", "en", "long_context",
                       "This section of the welcome email describes how to update your "
                       "mailing address, change your communication preferences and download "
                       "past statements, without disclosing any banking identifier."))

    return {"pii_type": "BANK_ACCOUNT", "detector_label": "bank_account", "threshold": 0.50, "cases": cases}


def build_account_number() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le numero de compte est ACCT-00231-7789 dans notre systeme."
    cases.append(_case("ACCOUNT_NUMBER_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "ACCT-00231-7789")]))
    t = "The account number on the invoice is 0001234567 for reference."
    cases.append(_case("ACCOUNT_NUMBER_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "0001234567")]))
    t = "Die Kontonummer im Vertrag lautet ACCT-00231-7789 fuer den Kunden."
    cases.append(_case("ACCOUNT_NUMBER_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "ACCT-00231-7789")]))
    t = "Il numero di conto indicato e 0001234567 sul documento."
    cases.append(_case("ACCOUNT_NUMBER_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "0001234567")]))

    # canonical_no_clue
    t = "ACCT-00231-7789"
    cases.append(_case("ACCOUNT_NUMBER_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "ACCT-00231-7789")]))
    t = "0001234567"
    cases.append(_case("ACCOUNT_NUMBER_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "0001234567")]))
    t = "ACCT-00231-7789"
    cases.append(_case("ACCOUNT_NUMBER_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "ACCT-00231-7789")]))
    t = "0001234567"
    cases.append(_case("ACCOUNT_NUMBER_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "0001234567")]))

    # look_alikes (ticket id, ObjectId) -> aucun span
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_en_01", "en", "look_alikes",
                       "The Mongo ObjectId 507f1f77bcf86cd799439011 points to the doc."))
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_fr_01", "fr", "look_alikes",
                       "Le ticket JIRA SENT-00231 a ete assigne a l'equipe support."))
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_de_01", "de", "look_alikes",
                       "Die Vorgangsnummer TCK-0001234567 wurde gestern geschlossen."))
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_it_01", "it", "look_alikes",
                       "L'identificativo build 00231-7789-rc1 e stato pubblicato."))
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_en_02", "en", "look_alikes",
                       "Issue tracker id BUG-1234567 is now marked as resolved."))
    cases.append(_case("ACCOUNT_NUMBER_look_alikes_fr_02", "fr", "look_alikes",
                       "Le code produit ACCT-CABLE-USB est en rupture de stock."))

    # explicit_negatives
    cases.append(_case("ACCOUNT_NUMBER_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Veuillez indiquer votre numero de compte dans le formulaire."))
    cases.append(_case("ACCOUNT_NUMBER_explicit_negatives_en_01", "en", "explicit_negatives",
                       "The account number field was left blank by the applicant."))
    cases.append(_case("ACCOUNT_NUMBER_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Eine gueltige Kontonummer liegt uns derzeit nicht vor."))
    cases.append(_case("ACCOUNT_NUMBER_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Il numero di conto deve ancora essere comunicato dal cliente."))

    # adversarial_formatting
    t = "Compte: ACCT - 00231 - 7789 (espaces autour des tirets)."
    cases.append(_case("ACCOUNT_NUMBER_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "ACCT - 00231 - 7789")]))
    t = "Account no. 000 123 4567 grouped in threes."
    cases.append(_case("ACCOUNT_NUMBER_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "000 123 4567")]))
    # trop fragmente -> aucun span
    cases.append(_case("ACCOUNT_NUMBER_adversarial_formatting_de_01", "de", "adversarial_formatting",
                       "A=0 C=0 C=0 T=1 verteilt ueber mehrere Tabellenzellen."))

    # long_context
    t = ("Dans le cadre de la migration de notre ERP vers la nouvelle plateforme cloud, "
         "chaque dossier client devra etre rapproche manuellement; pour le dossier "
         "concerne, le numero de compte ACCT-00231-7789 doit etre reporte tel quel dans "
         "le champ dedie avant la bascule definitive prevue le mois prochain.")
    cases.append(_case("ACCOUNT_NUMBER_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "ACCT-00231-7789")]))
    cases.append(_case("ACCOUNT_NUMBER_long_context_en_01", "en", "long_context",
                       "The release notes summarize the new dashboard widgets, the revamped "
                       "search experience and several bug fixes, but contain no account "
                       "identifier anywhere in this changelog entry."))

    return {"pii_type": "ACCOUNT_NUMBER", "detector_label": "account_number", "threshold": 0.50, "cases": cases}


def build_routing_number() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le routing number de la banque est 021000021 pour les virements ACH."
    cases.append(_case("ROUTING_NUMBER_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "021000021")]))
    t = "The ABA routing number is 011401533, use it for the wire."
    cases.append(_case("ROUTING_NUMBER_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "011401533")]))
    t = "Die Routing-Number der US-Bank lautet 021000021 fuer Ueberweisungen."
    cases.append(_case("ROUTING_NUMBER_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "021000021")]))
    t = "Il routing number da utilizzare e 011401533 per il bonifico USA."
    cases.append(_case("ROUTING_NUMBER_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "011401533")]))

    # canonical_no_clue
    t = "021000021"
    cases.append(_case("ROUTING_NUMBER_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "021000021")]))
    t = "011401533"
    cases.append(_case("ROUTING_NUMBER_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "011401533")]))
    t = "021000021"
    cases.append(_case("ROUTING_NUMBER_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "021000021")]))
    t = "011401533"
    cases.append(_case("ROUTING_NUMBER_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "011401533")]))

    # look_alikes (autres nombres a 9 chiffres: telephone, code postal+suffixe, build) -> aucun span
    cases.append(_case("ROUTING_NUMBER_look_alikes_fr_01", "fr", "look_alikes",
                       "Vous pouvez nous joindre au 0219110021 pendant les heures de bureau."))
    cases.append(_case("ROUTING_NUMBER_look_alikes_en_01", "en", "look_alikes",
                       "The ZIP+4 code for the warehouse is 940015033 on the label."))
    cases.append(_case("ROUTING_NUMBER_look_alikes_de_01", "de", "look_alikes",
                       "Die interne Build-Nummer der Pipeline ist 202603115 heute."))
    cases.append(_case("ROUTING_NUMBER_look_alikes_it_01", "it", "look_alikes",
                       "Il numero di telefono dell'ufficio e 011 401 533 in citta."))
    cases.append(_case("ROUTING_NUMBER_look_alikes_en_02", "en", "look_alikes",
                       "Employee badge number 021000021 was deactivated on exit."))
    cases.append(_case("ROUTING_NUMBER_look_alikes_fr_02", "fr", "look_alikes",
                       "Le numero d'inventaire de l'imprimante est 110015339 au sous-sol."))

    # explicit_negatives
    cases.append(_case("ROUTING_NUMBER_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Merci d'indiquer le routing number de votre banque americaine."))
    cases.append(_case("ROUTING_NUMBER_explicit_negatives_en_01", "en", "explicit_negatives",
                       "We still need the ABA routing number before we can wire funds."))
    cases.append(_case("ROUTING_NUMBER_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Die Routing-Number fehlt noch in den Bankdaten des Kunden."))
    cases.append(_case("ROUTING_NUMBER_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Il routing number non e stato fornito per questo conto estero."))

    # adversarial_formatting
    t = "Routing: 021-000-021 (groupe par tirets sur le releve)."
    cases.append(_case("ROUTING_NUMBER_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "021-000-021")]))
    t = "ABA 0214 0153 3 grouped oddly in the PDF export."
    cases.append(_case("ROUTING_NUMBER_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "0214 0153 3")]))
    # trop fragmente -> aucun span
    cases.append(_case("ROUTING_NUMBER_adversarial_formatting_de_01", "de", "adversarial_formatting",
                       "0\t2\t1\t0\t0\t0\t0\t2\t1 stand verstreut in der Tabelle."))

    # long_context
    t = ("Pour finaliser le paiement international vers le fournisseur americain, le "
         "service tresorerie aura besoin a la fois du SWIFT et du routing number "
         "021000021, faute de quoi la banque rejettera l'instruction; merci de verifier "
         "ces informations avant la prochaine echeance de paiement.")
    cases.append(_case("ROUTING_NUMBER_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "021000021")]))
    cases.append(_case("ROUTING_NUMBER_long_context_en_01", "en", "long_context",
                       "This FAQ entry explains the difference between domestic and "
                       "international transfers, the typical processing delays and the "
                       "cut-off times, but it does not quote any specific routing code."))

    return {"pii_type": "ROUTING_NUMBER", "detector_label": "routing_number", "threshold": 0.50, "cases": cases}


def build_payment_card() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le numero de carte de paiement est 4111 1111 1111 1111 pour la commande."
    cases.append(_case("PAYMENT_CARD_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "4111 1111 1111 1111")]))
    t = "The payment card on file is 5555 5555 5555 4444, please verify."
    cases.append(_case("PAYMENT_CARD_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "5555 5555 5555 4444")]))
    t = "Die Kreditkarte fuer die Zahlung lautet 4111 1111 1111 1111 hier."
    cases.append(_case("PAYMENT_CARD_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "4111 1111 1111 1111")]))
    t = "La carta di pagamento Amex e 3782 822463 10005 sul profilo."
    cases.append(_case("PAYMENT_CARD_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "3782 822463 10005")]))

    # canonical_no_clue
    t = "4111 1111 1111 1111"
    cases.append(_case("PAYMENT_CARD_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "4111 1111 1111 1111")]))
    t = "5555 5555 5555 4444"
    cases.append(_case("PAYMENT_CARD_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "5555 5555 5555 4444")]))
    t = "3782 822463 10005"
    cases.append(_case("PAYMENT_CARD_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "3782 822463 10005")]))
    t = "4111 1111 1111 1111"
    cases.append(_case("PAYMENT_CARD_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "4111 1111 1111 1111")]))

    # look_alikes (tracking colis 16 chiffres, n de serie groupe) -> aucun span
    cases.append(_case("PAYMENT_CARD_look_alikes_en_01", "en", "look_alikes",
                       "Your parcel tracking number 7421 9983 0145 6677 is out for delivery."))
    cases.append(_case("PAYMENT_CARD_look_alikes_fr_01", "fr", "look_alikes",
                       "Le numero de suivi du colis 3300 1234 5678 9012 est en transit."))
    cases.append(_case("PAYMENT_CARD_look_alikes_de_01", "de", "look_alikes",
                       "Die Seriennummer des Geraets 8801 2233 4455 6677 steht hinten."))
    cases.append(_case("PAYMENT_CARD_look_alikes_it_01", "it", "look_alikes",
                       "Il codice di spedizione 1020 3040 5060 7080 risulta consegnato."))
    cases.append(_case("PAYMENT_CARD_look_alikes_en_02", "en", "look_alikes",
                       "Asset tag 1234 5678 9012 3456 is assigned to the meeting room TV."))
    cases.append(_case("PAYMENT_CARD_look_alikes_fr_02", "fr", "look_alikes",
                       "La reference logistique 9000 1111 2222 3333 figure sur le bon."))

    # explicit_negatives
    cases.append(_case("PAYMENT_CARD_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Veuillez saisir votre numero de carte de paiement ci-dessous."))
    cases.append(_case("PAYMENT_CARD_explicit_negatives_en_01", "en", "explicit_negatives",
                       "No payment card is currently stored for this account."))
    cases.append(_case("PAYMENT_CARD_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Es ist keine Kreditkarte fuer dieses Konto hinterlegt."))
    cases.append(_case("PAYMENT_CARD_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Nessuna carta di pagamento risulta salvata per questo cliente."))

    # adversarial_formatting
    t = "Carte: 4111-1111-1111-1111 (tirets a la place des espaces)."
    cases.append(_case("PAYMENT_CARD_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "4111-1111-1111-1111")]))
    t = "Card: 4111111111111111 written without any separator."
    cases.append(_case("PAYMENT_CARD_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "4111111111111111")]))
    # trop fragmente -> aucun span
    cases.append(_case("PAYMENT_CARD_adversarial_formatting_de_01", "de", "adversarial_formatting",
                       "4 1 1 1 / 1 1 1 1 / 1 1 1 1 / 1 1 1 1 quer ueber die Zeile verteilt."))

    # long_context
    t = ("Lors du paiement en ligne d'hier soir, le systeme antifraude a temporairement "
         "bloque la transaction; pour la debloquer, l'operateur a du confirmer que la "
         "carte de paiement 4111 1111 1111 1111 appartenait bien au titulaire, avant de "
         "relancer la commande sans nouvelle erreur de validation.")
    cases.append(_case("PAYMENT_CARD_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "4111 1111 1111 1111")]))
    cases.append(_case("PAYMENT_CARD_long_context_en_01", "en", "long_context",
                       "The checkout tutorial walks new users through adding items to the "
                       "cart, applying a discount code and choosing a delivery slot, without "
                       "ever showing a real card number on screen."))

    return {"pii_type": "PAYMENT_CARD", "detector_label": "payment_card", "threshold": 0.50, "cases": cases}


def build_card_number() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Le numero de carte est 4242 4242 4242 4242 pour le test de paiement."
    cases.append(_case("CARD_NUMBER_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "The card number used was 4242 4242 4242 4242 in the sandbox."
    cases.append(_case("CARD_NUMBER_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "Die Kartennummer im Test ist 4242 4242 4242 4242 hinterlegt."
    cases.append(_case("CARD_NUMBER_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "Il numero della carta usato e 4242 4242 4242 4242 in collaudo."
    cases.append(_case("CARD_NUMBER_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))

    # canonical_no_clue
    t = "4242 4242 4242 4242"
    cases.append(_case("CARD_NUMBER_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "4242 4242 4242 4242"
    cases.append(_case("CARD_NUMBER_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "4242 4242 4242 4242"
    cases.append(_case("CARD_NUMBER_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))
    t = "4242 4242 4242 4242"
    cases.append(_case("CARD_NUMBER_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "4242 4242 4242 4242")]))

    # look_alikes (IMEI, n conteneur) -> aucun span
    cases.append(_case("CARD_NUMBER_look_alikes_en_01", "en", "look_alikes",
                       "The phone IMEI is 35 680605 521234 7 on the back label."))
    cases.append(_case("CARD_NUMBER_look_alikes_fr_01", "fr", "look_alikes",
                       "Le numero de conteneur MSKU 424242 4 est arrive au port."))
    cases.append(_case("CARD_NUMBER_look_alikes_de_01", "de", "look_alikes",
                       "Die IMEI des Testgeraets lautet 49 015420 323751 8 hier."))
    cases.append(_case("CARD_NUMBER_look_alikes_it_01", "it", "look_alikes",
                       "Il container TGHU 424242 7 e in attesa di sdoganamento."))
    cases.append(_case("CARD_NUMBER_look_alikes_en_02", "en", "look_alikes",
                       "Router serial SN 4242 4242 GH is mounted in rack B."))
    cases.append(_case("CARD_NUMBER_look_alikes_fr_02", "fr", "look_alikes",
                       "La reference du chassis CH-4242-4242 est gravee sous le capot."))

    # explicit_negatives
    cases.append(_case("CARD_NUMBER_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Merci de renseigner le numero de carte dans le champ prevu."))
    cases.append(_case("CARD_NUMBER_explicit_negatives_en_01", "en", "explicit_negatives",
                       "The card number was not captured during this test run."))
    cases.append(_case("CARD_NUMBER_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Die Kartennummer wurde im Formular nicht eingegeben."))
    cases.append(_case("CARD_NUMBER_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Il numero della carta non e stato inserito in fase di test."))

    # adversarial_formatting
    t = "Carte: 4242-4242-4242-4242 (separateurs tirets)."
    cases.append(_case("CARD_NUMBER_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "4242-4242-4242-4242")]))
    t = "Card: 4242424242424242 with no spaces at all."
    cases.append(_case("CARD_NUMBER_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "4242424242424242")]))
    # trop fragmente -> aucun span
    cases.append(_case("CARD_NUMBER_adversarial_formatting_de_01", "de", "adversarial_formatting",
                       "4-2-4-2 4-2-4-2 4-2-4-2 4-2-4-2 ueber Spalten verteilt."))

    # long_context
    t = ("Pendant la recette de notre nouvelle passerelle de paiement, l'equipe QA a "
         "rejoue une douzaine de scenarios de bout en bout; pour le scenario nominal, "
         "la carte de test portant le numero de carte 4242 4242 4242 4242 a ete utilisee "
         "afin de valider l'autorisation puis la capture du montant.")
    cases.append(_case("CARD_NUMBER_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "4242 4242 4242 4242")]))
    cases.append(_case("CARD_NUMBER_long_context_en_01", "en", "long_context",
                       "The QA report lists the environments under test, the browser matrix "
                       "and the known flaky scenarios, but no actual card number is included "
                       "in this summary paragraph."))

    return {"pii_type": "CARD_NUMBER", "detector_label": "card_number", "threshold": 0.50, "cases": cases}


def build_card_expiry() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "La date d'expiration de la carte est 04/27 selon le recto."
    cases.append(_case("CARD_EXPIRY_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "04/27")]))
    t = "The card expiry date is 12/2026, please update it on file."
    cases.append(_case("CARD_EXPIRY_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "12/2026")]))
    t = "Die Karte laeuft ab, Ablaufdatum expire 03/28 laut Vorderseite."
    cases.append(_case("CARD_EXPIRY_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "03/28")]))
    t = "La data di scadenza della carta e 04/27 sul fronte."
    cases.append(_case("CARD_EXPIRY_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "04/27")]))

    # canonical_no_clue
    t = "04/27"
    cases.append(_case("CARD_EXPIRY_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "04/27")]))
    t = "12/2026"
    cases.append(_case("CARD_EXPIRY_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "12/2026")]))
    t = "03/28"
    cases.append(_case("CARD_EXPIRY_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "03/28")]))
    t = "12/2026"
    cases.append(_case("CARD_EXPIRY_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "12/2026")]))

    # look_alikes (dates ordinaires, versions, fractions) -> aucun span
    cases.append(_case("CARD_EXPIRY_look_alikes_fr_01", "fr", "look_alikes",
                       "La reunion est reportee au 14/03 en debut d'apres-midi."))
    cases.append(_case("CARD_EXPIRY_look_alikes_en_01", "en", "look_alikes",
                       "We upgraded the runtime to version 4.12 last sprint."))
    cases.append(_case("CARD_EXPIRY_look_alikes_de_01", "de", "look_alikes",
                       "Das Rezept verlangt 3/4 Liter Milch und etwas Mehl."))
    cases.append(_case("CARD_EXPIRY_look_alikes_it_01", "it", "look_alikes",
                       "L'evento si terra il 14/03 presso la sede centrale."))
    cases.append(_case("CARD_EXPIRY_look_alikes_en_02", "en", "look_alikes",
                       "The score ended 04/27 in favor of the visiting team... just kidding, it was 4-2."))
    cases.append(_case("CARD_EXPIRY_look_alikes_fr_02", "fr", "look_alikes",
                       "Le ratio de conversion observe est de 12/26 sur l'echantillon."))

    # explicit_negatives
    cases.append(_case("CARD_EXPIRY_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Merci d'indiquer la date d'expiration de votre carte."))
    cases.append(_case("CARD_EXPIRY_explicit_negatives_en_01", "en", "explicit_negatives",
                       "The card expiry date is missing from the saved payment method."))
    cases.append(_case("CARD_EXPIRY_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Das Ablaufdatum der Karte wurde noch nicht erfasst."))
    cases.append(_case("CARD_EXPIRY_explicit_negatives_it_01", "it", "explicit_negatives",
                       "La data di scadenza della carta non risulta ancora inserita."))

    # adversarial_formatting
    t = "Exp: 04 / 27 avec des espaces autour du slash."
    cases.append(_case("CARD_EXPIRY_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
                       [_span(t, "04 / 27")]))
    t = "Expiry 04-27 written with a dash on the receipt."
    cases.append(_case("CARD_EXPIRY_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "04-27")]))
    # trop fragmente -> aucun span
    cases.append(_case("CARD_EXPIRY_adversarial_formatting_de_01", "de", "adversarial_formatting",
                       "Monat 0 4 Jahr 2 7 ueber zwei Zellen verteilt."))

    # long_context
    t = ("En relisant le formulaire de paiement renvoye par le client, le conseiller a "
         "remarque que le numero de carte etait correct mais que la date d'expiration "
         "04/27 arrivait bientot a terme; il a donc invite le client a preparer une "
         "nouvelle carte avant le prochain prelevement recurrent.")
    cases.append(_case("CARD_EXPIRY_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "04/27")]))
    cases.append(_case("CARD_EXPIRY_long_context_en_01", "en", "long_context",
                       "The billing FAQ describes how recurring charges work, when invoices "
                       "are issued and how to request a refund, but does not mention any "
                       "specific expiry date in this section."))

    return {"pii_type": "CARD_EXPIRY", "detector_label": "card_expiry", "threshold": 0.50, "cases": cases}


def build_card_cvv() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numero de carte: 4111 1111 1111 1111, CVV: 737"
    cases.append(_case("CARD_CVV_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
                       [_span(t, "737")]))
    t = "The CVV on the back of the card is 519, do not share it."
    cases.append(_case("CARD_CVV_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
                       [_span(t, "519")]))
    t = "Der Sicherheitscode CVV lautet 248 auf der Rueckseite."
    cases.append(_case("CARD_CVV_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
                       [_span(t, "248")]))
    t = "Il codice di sicurezza CVV della Amex e 1234 sul fronte."
    cases.append(_case("CARD_CVV_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
                       [_span(t, "1234")]))

    # canonical_no_clue
    t = "737"
    cases.append(_case("CARD_CVV_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
                       [_span(t, "737")]))
    t = "519"
    cases.append(_case("CARD_CVV_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
                       [_span(t, "519")]))
    t = "248"
    cases.append(_case("CARD_CVV_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
                       [_span(t, "248")]))
    t = "1234"
    cases.append(_case("CARD_CVV_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
                       [_span(t, "1234")]))

    # look_alikes (n de salle, codes HTTP, n de page) -> aucun span
    cases.append(_case("CARD_CVV_look_alikes_en_01", "en", "look_alikes",
                       "The conference room 312 is booked from 9am to noon."))
    cases.append(_case("CARD_CVV_look_alikes_fr_01", "fr", "look_alikes",
                       "Le serveur a renvoye une erreur 503 pendant la maintenance."))
    cases.append(_case("CARD_CVV_look_alikes_de_01", "de", "look_alikes",
                       "Die Anfrage scheiterte mit dem HTTP-Status 401 ohne Token."))
    cases.append(_case("CARD_CVV_look_alikes_it_01", "it", "look_alikes",
                       "La citazione si trova a pagina 248 del manuale tecnico."))
    cases.append(_case("CARD_CVV_look_alikes_en_02", "en", "look_alikes",
                       "The API returned 200 OK for every request in the smoke test."))
    cases.append(_case("CARD_CVV_look_alikes_fr_02", "fr", "look_alikes",
                       "Rendez-vous en salle 737 au septieme etage du batiment."))

    # explicit_negatives
    cases.append(_case("CARD_CVV_explicit_negatives_fr_01", "fr", "explicit_negatives",
                       "Ne communiquez jamais le code CVV de votre carte par email."))
    cases.append(_case("CARD_CVV_explicit_negatives_en_01", "en", "explicit_negatives",
                       "The CVV is never stored on our servers for security reasons."))
    cases.append(_case("CARD_CVV_explicit_negatives_de_01", "de", "explicit_negatives",
                       "Der CVV-Code wird aus Sicherheitsgruenden nicht gespeichert."))
    cases.append(_case("CARD_CVV_explicit_negatives_it_01", "it", "explicit_negatives",
                       "Il codice CVV non viene mai memorizzato dai nostri sistemi."))

    # adversarial_formatting
    t = "CVV : 7 3 7 dicte lettre par lettre au telephone."
    cases.append(_case("CARD_CVV_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
                       t))  # trop fragmente -> aucun span
    t = "Security code (CVV)519 stuck to the label with no space."
    cases.append(_case("CARD_CVV_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
                       [_span(t, "519")]))
    t = "CVV=248 inline mit Gleichheitszeichen geschrieben."
    cases.append(_case("CARD_CVV_adversarial_formatting_de_01", "de", "adversarial_formatting", t,
                       [_span(t, "248")]))

    # long_context
    t = ("Au telephone, pour confirmer son identite avant le remboursement, le client a "
         "lu lentement les informations de sa carte; apres le numero et la date, il a "
         "fini par communiquer le code CVV 737 que le conseiller a saisi dans l'outil "
         "securise sans jamais le noter sur papier.")
    cases.append(_case("CARD_CVV_long_context_fr_01", "fr", "long_context", t,
                       [_span(t, "737")]))
    cases.append(_case("CARD_CVV_long_context_en_01", "en", "long_context",
                       "The security awareness module reminds employees never to read out "
                       "card details over chat, to lock their screens and to report phishing, "
                       "without quoting any actual security code here."))

    return {"pii_type": "CARD_CVV", "detector_label": "card_cvv", "threshold": 0.50, "cases": cases}


BUILDERS: Dict[str, Any] = {
    "BANK_ACCOUNT": build_bank_account,
    "ACCOUNT_NUMBER": build_account_number,
    "ROUTING_NUMBER": build_routing_number,
    "IBAN": build_iban,
    "PAYMENT_CARD": build_payment_card,
    "CARD_NUMBER": build_card_number,
    "CARD_EXPIRY": build_card_expiry,
    "CARD_CVV": build_card_cvv,
}
