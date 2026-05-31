"""GLiNER2 FP/FN evaluation fixtures — Digital identity family.

Covers four canonical PII types and their GLiNER2 detector labels:
USERNAME, IP_ADDRESS, ACCOUNT_ID, SENSITIVE_ACCOUNT_ID.

Each ``build_<type>()`` returns a dict consumed by the eval runner. Spans are
computed byte-exact via ``_span`` so ``text[start:end] == value`` always holds.
"""
from __future__ import annotations

from typing import Any, Dict, List

from _fixture_helpers import _span, _case


def build_username() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue (TP keyword + value)
    t = "Identifiant de connexion : jdupont"
    cases.append(_case("USERNAME_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "jdupont")]))
    t = "Login username for the admin console: admin_svc"
    cases.append(_case("USERNAME_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "admin_svc")]))
    t = "Anmeldename des Technikers: m.mueller"
    cases.append(_case("USERNAME_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "m.mueller")]))
    t = "Nome utente per l'accesso al portale: athena_svc"
    cases.append(_case("USERNAME_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "athena_svc")]))

    # canonical_no_clue (TP isolated)
    t = "jdupont"
    cases.append(_case("USERNAME_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "jdupont")]))
    t = "@alice_m"
    cases.append(_case("USERNAME_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "@alice_m")]))
    t = "m.mueller"
    cases.append(_case("USERNAME_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "m.mueller")]))
    t = "athena_svc"
    cases.append(_case("USERNAME_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "athena_svc")]))

    # look_alikes (negatives, similar shape, no span)
    cases.append(_case("USERNAME_look_alikes_fr_01", "fr", "look_alikes",
        "La variable db_user contient la chaine de connexion a la base."))
    cases.append(_case("USERNAME_look_alikes_en_01", "en", "look_alikes",
        "The function signature is def authenticate(username: str) -> bool."))
    cases.append(_case("USERNAME_look_alikes_de_01", "de", "look_alikes",
        "Die Klasse UserSessionManager verwaltet alle offenen Sitzungen."))
    cases.append(_case("USERNAME_look_alikes_it_01", "it", "look_alikes",
        "Segui la campagna con l'hashtag #DigitalFirst su tutti i canali."))
    cases.append(_case("USERNAME_look_alikes_en_02", "en", "look_alikes",
        "Rename the local variable currentUser to activeUser before merging."))
    cases.append(_case("USERNAME_look_alikes_fr_02", "fr", "look_alikes",
        "Le parametre nomUtilisateur est passe par reference dans cette methode."))

    # explicit_negatives (keyword, no value, no span)
    cases.append(_case("USERNAME_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Veuillez saisir votre identifiant de connexion dans le champ prevu."))
    cases.append(_case("USERNAME_explicit_negatives_en_01", "en", "explicit_negatives",
        "Your username could not be verified, please try again later."))
    cases.append(_case("USERNAME_explicit_negatives_de_01", "de", "explicit_negatives",
        "Der Anmeldename wird beim ersten Start automatisch generiert."))
    cases.append(_case("USERNAME_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il nome utente e obbligatorio per completare la registrazione."))

    # adversarial_formatting (span if value contiguous, else none)
    t = "user:jdupont;role:admin;active:true"
    cases.append(_case("USERNAME_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t, [_span(t, "jdupont")]))
    t = "<login>admin_svc</login>"
    cases.append(_case("USERNAME_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "admin_svc")]))
    cases.append(_case("USERNAME_adversarial_formatting_de_02", "de", "adversarial_formatting",
        "u s e r n a m e : m . m u e l l e r"))  # spaced out, not contiguous -> no span

    # long_context (1 positive + 1 negative)
    t = ("Lors de la migration du cluster de production, l'equipe a constate que le "
         "compte de service utilise par le pipeline CI/CD etait toujours actif. Apres "
         "verification dans l'annuaire LDAP, l'identifiant de connexion concerne etait "
         "athena_svc, et il disposait encore de droits eleves sur les noeuds.")
    cases.append(_case("USERNAME_long_context_fr_01", "fr", "long_context", t, [_span(t, "athena_svc")]))
    cases.append(_case("USERNAME_long_context_en_01", "en", "long_context",
        "During the post-incident review the on-call engineer explained that the "
        "deployment script reads the connection string from an environment variable "
        "called DB_USER, which is injected by the orchestrator at boot time, so no "
        "human account credentials are ever stored in the repository itself."))

    return {"pii_type": "USERNAME", "detector_label": "username", "threshold": 0.50, "cases": cases}


def build_ip_address() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Adresse IP du serveur applicatif : 10.0.4.27"
    cases.append(_case("IP_ADDRESS_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "10.0.4.27")]))
    t = "The gateway IP address is 192.168.1.1 on the internal segment."
    cases.append(_case("IP_ADDRESS_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "192.168.1.1")]))
    t = "Die IP-Adresse des Containers lautet 172.22.0.5 im Docker-Netz."
    cases.append(_case("IP_ADDRESS_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "172.22.0.5")]))
    t = "Indirizzo IP del nodo IPv6: 2001:db8::1 nella rete di test."
    cases.append(_case("IP_ADDRESS_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "2001:db8::1")]))

    # canonical_no_clue
    t = "10.0.4.27"
    cases.append(_case("IP_ADDRESS_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "10.0.4.27")]))
    t = "192.168.1.1"
    cases.append(_case("IP_ADDRESS_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "192.168.1.1")]))
    t = "fe80::1ff:fe23:4567:890a"
    cases.append(_case("IP_ADDRESS_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "fe80::1ff:fe23:4567:890a")]))
    t = "2001:db8::1"
    cases.append(_case("IP_ADDRESS_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "2001:db8::1")]))

    # look_alikes
    cases.append(_case("IP_ADDRESS_look_alikes_en_01", "en", "look_alikes",
        "We deployed athena-core version 4.12.0 to staging this morning."))
    cases.append(_case("IP_ADDRESS_look_alikes_fr_01", "fr", "look_alikes",
        "La mise en production est planifiee pour le 10.04.2024 en soiree."))
    cases.append(_case("IP_ADDRESS_look_alikes_de_01", "de", "look_alikes",
        "Das Modul wurde auf Version 1.20.3 aktualisiert, Build 8841."))
    cases.append(_case("IP_ADDRESS_look_alikes_it_01", "it", "look_alikes",
        "Le coordinate del punto di rilievo sono 46.5197, 6.6323 in citta."))
    cases.append(_case("IP_ADDRESS_look_alikes_en_02", "en", "look_alikes",
        "The firmware revision 2.0.0.1 ships with the next hardware batch."))
    cases.append(_case("IP_ADDRESS_look_alikes_fr_02", "fr", "look_alikes",
        "Le numero de build 20240412.3 figure dans les notes de version."))

    # explicit_negatives
    cases.append(_case("IP_ADDRESS_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "L'adresse IP du poste sera attribuee automatiquement par le serveur DHCP."))
    cases.append(_case("IP_ADDRESS_explicit_negatives_en_01", "en", "explicit_negatives",
        "The IP address field is left blank when the host uses DHCP leasing."))
    cases.append(_case("IP_ADDRESS_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die IP-Adresse konnte nicht ermittelt werden, bitte spaeter erneut versuchen."))
    cases.append(_case("IP_ADDRESS_explicit_negatives_it_01", "it", "explicit_negatives",
        "L'indirizzo IP verra assegnato dinamicamente al primo avvio del dispositivo."))

    # adversarial_formatting
    t = "ip=10.0.4.27,port=8443,proto=tcp"
    cases.append(_case("IP_ADDRESS_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "10.0.4.27")]))
    t = "[host:192.168.1.1]"
    cases.append(_case("IP_ADDRESS_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t, [_span(t, "192.168.1.1")]))
    cases.append(_case("IP_ADDRESS_adversarial_formatting_de_02", "de", "adversarial_formatting",
        "10 . 0 . 4 . 27"))  # spaced out, not contiguous -> no span

    # long_context
    t = ("Pendant l'analyse de l'incident reseau, l'equipe securite a remarque un pic "
         "de trafic sortant inhabituel vers l'exterieur. En croisant les journaux du "
         "pare-feu et ceux du proxy, il est apparu que la source etait le serveur "
         "applicatif dont l'adresse interne 10.0.4.27 generait des milliers de "
         "requetes par minute vers un domaine inconnu.")
    cases.append(_case("IP_ADDRESS_long_context_fr_01", "fr", "long_context", t, [_span(t, "10.0.4.27")]))
    cases.append(_case("IP_ADDRESS_long_context_en_01", "en", "long_context",
        "The release notes for the latest sprint mention that the analytics module was "
        "bumped from version 4.11.2 to 4.12.0, the documentation generator now targets "
        "build 20240501.7, and the changelog references ticket numbers rather than any "
        "network endpoints, so reviewers should not expect host details in this file."))

    return {"pii_type": "IP_ADDRESS", "detector_label": "ip_address", "threshold": 0.50, "cases": cases}


def build_account_id() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Identifiant de compte technique : acct_1J5KQ2EnExampleId"
    cases.append(_case("ACCOUNT_ID_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "acct_1J5KQ2EnExampleId")]))
    t = "The customer account id on file is CUST-00231 for this tenant."
    cases.append(_case("ACCOUNT_ID_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "CUST-00231")]))
    t = "Die Konto-ID des Benutzers lautet uid-7782341 im System."
    cases.append(_case("ACCOUNT_ID_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "uid-7782341")]))
    t = "Identificativo account del cliente: CUST-00231 nel registro."
    cases.append(_case("ACCOUNT_ID_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "CUST-00231")]))

    # canonical_no_clue
    t = "acct_1J5KQ2EnExampleId"
    cases.append(_case("ACCOUNT_ID_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "acct_1J5KQ2EnExampleId")]))
    t = "CUST-00231"
    cases.append(_case("ACCOUNT_ID_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "CUST-00231")]))
    t = "uid-7782341"
    cases.append(_case("ACCOUNT_ID_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "uid-7782341")]))
    t = "acct_1J5KQ2EnExampleId"
    cases.append(_case("ACCOUNT_ID_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "acct_1J5KQ2EnExampleId")]))

    # look_alikes (technical non-account identifiers)
    cases.append(_case("ACCOUNT_ID_look_alikes_en_01", "en", "look_alikes",
        "The Mongo document _id is 507f1f77bcf86cd799439011 in the orders collection."))
    cases.append(_case("ACCOUNT_ID_look_alikes_fr_01", "fr", "look_alikes",
        "Le correlationId de la requete est 9f1c2e7a-4b6d-4e2a-9c1f-3d8a7b6e5f40."))
    cases.append(_case("ACCOUNT_ID_look_alikes_de_01", "de", "look_alikes",
        "Das Support-Ticket INC-447 wurde gestern Abend automatisch geschlossen."))
    cases.append(_case("ACCOUNT_ID_look_alikes_it_01", "it", "look_alikes",
        "L'identificativo della sessione e a3f9-bb12-77ce generato dal gateway."))
    cases.append(_case("ACCOUNT_ID_look_alikes_en_02", "en", "look_alikes",
        "The trace id 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 was logged."))
    cases.append(_case("ACCOUNT_ID_look_alikes_fr_02", "fr", "look_alikes",
        "Le hash du commit deploye est e28e530 sur la branche principale."))

    # explicit_negatives
    cases.append(_case("ACCOUNT_ID_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "L'identifiant de compte sera communique par courrier separe au titulaire."))
    cases.append(_case("ACCOUNT_ID_explicit_negatives_en_01", "en", "explicit_negatives",
        "The account id is not available until the onboarding step is completed."))
    cases.append(_case("ACCOUNT_ID_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Konto-ID wird erst nach der Freischaltung des Kontos vergeben."))
    cases.append(_case("ACCOUNT_ID_explicit_negatives_it_01", "it", "explicit_negatives",
        "L'identificativo account verra mostrato dopo la verifica dell'indirizzo email."))

    # adversarial_formatting
    t = "account_id=acct_1J5KQ2EnExampleId&status=active"
    cases.append(_case("ACCOUNT_ID_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "acct_1J5KQ2EnExampleId")]))
    t = "[compte:CUST-00231]"
    cases.append(_case("ACCOUNT_ID_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t, [_span(t, "CUST-00231")]))
    cases.append(_case("ACCOUNT_ID_adversarial_formatting_de_02", "de", "adversarial_formatting",
        "C U S T - 0 0 2 3 1"))  # spaced out, not contiguous -> no span

    # long_context
    t = ("Au cours de l'audit trimestriel des acces, l'equipe conformite a recoupe les "
         "exports de facturation avec l'annuaire interne. Plusieurs anomalies ont ete "
         "relevees, mais la plus critique concernait un compte technique orphelin dont "
         "l'identifiant acct_1J5KQ2EnExampleId restait rattache a un prestataire ayant "
         "quitte l'organisation depuis plusieurs mois.")
    cases.append(_case("ACCOUNT_ID_long_context_fr_01", "fr", "long_context", t, [_span(t, "acct_1J5KQ2EnExampleId")]))
    cases.append(_case("ACCOUNT_ID_long_context_en_01", "en", "long_context",
        "While triaging the backlog the team noticed that several support tickets such "
        "as INC-447 and the related Mongo document with _id 507f1f77bcf86cd799439011 "
        "had been duplicated by a faulty integration, so the cleanup script targets "
        "those technical references only and never touches any customer account fields."))

    return {"pii_type": "ACCOUNT_ID", "detector_label": "account_id", "threshold": 0.50, "cases": cases}


def build_sensitive_account_id() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numero de commande client : ORD-2024-78421"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t, [_span(t, "ORD-2024-78421")]))
    t = "Please quote invoice number INV-99231 in all correspondence."
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_with_clue_en_01", "en", "canonical_with_clue", t, [_span(t, "INV-99231")]))
    t = "Die Vorgangsnummer des Kundendossiers lautet DOSSIER-CH-44812."
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_with_clue_de_01", "de", "canonical_with_clue", t, [_span(t, "DOSSIER-CH-44812")]))
    t = "Numero di contratto del cliente: CONTRAT-7789 in archivio."
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_with_clue_it_01", "it", "canonical_with_clue", t, [_span(t, "CONTRAT-7789")]))

    # canonical_no_clue
    t = "ORD-2024-78421"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t, [_span(t, "ORD-2024-78421")]))
    t = "INV-99231"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_no_clue_en_01", "en", "canonical_no_clue", t, [_span(t, "INV-99231")]))
    t = "DOSSIER-CH-44812"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_no_clue_de_01", "de", "canonical_no_clue", t, [_span(t, "DOSSIER-CH-44812")]))
    t = "CONTRAT-7789"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_canonical_no_clue_it_01", "it", "canonical_no_clue", t, [_span(t, "CONTRAT-7789")]))

    # look_alikes (technical NON-customer references)
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_en_01", "en", "look_alikes",
        "The artifact was tagged with BUILD-SHA-9f1c2e7 in the CI pipeline."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_fr_01", "fr", "look_alikes",
        "L'inventaire mentionne l'etiquette materielle ASSET-TAG-00917 sur le serveur."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_de_01", "de", "look_alikes",
        "Das Release wurde unter der Versionsnummer RELEASE-18.2.0 veroeffentlicht."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_it_01", "it", "look_alikes",
        "Il numero di build BUILD-20240412 e riportato nelle note tecniche."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_en_02", "en", "look_alikes",
        "The Kubernetes deployment is labelled APP-VER-3.4.1 across all clusters."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_look_alikes_fr_02", "fr", "look_alikes",
        "Le numero de serie du composant est SN-77H2-AABB cote fabricant."))

    # explicit_negatives
    cases.append(_case("SENSITIVE_ACCOUNT_ID_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numero de commande figurera sur la facture envoyee apres expedition."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_explicit_negatives_en_01", "en", "explicit_negatives",
        "The invoice number will be generated once the payment has been confirmed."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Vorgangsnummer des Dossiers wird nach Pruefung der Unterlagen vergeben."))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il numero di contratto sara comunicato al cliente alla firma del documento."))

    # adversarial_formatting
    t = "order=ORD-2024-78421;ship=express"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t, [_span(t, "ORD-2024-78421")]))
    t = "<invoice>INV-99231</invoice>"
    cases.append(_case("SENSITIVE_ACCOUNT_ID_adversarial_formatting_en_01", "en", "adversarial_formatting", t, [_span(t, "INV-99231")]))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_adversarial_formatting_de_02", "de", "adversarial_formatting",
        "O R D - 2 0 2 4 - 7 8 4 2 1"))  # spaced out, not contiguous -> no span

    # long_context
    t = ("Suite a la reclamation transmise par le service apres-vente, la gestionnaire "
         "du dossier a repris l'historique complet de la transaction. Le client "
         "contestait un debit, et apres recherche dans le systeme de facturation, la "
         "commande litigieuse portant la reference ORD-2024-78421 a bien ete retrouvee, "
         "associee a un paiement valide mais a une livraison jamais receptionnee.")
    cases.append(_case("SENSITIVE_ACCOUNT_ID_long_context_fr_01", "fr", "long_context", t, [_span(t, "ORD-2024-78421")]))
    cases.append(_case("SENSITIVE_ACCOUNT_ID_long_context_en_01", "en", "long_context",
        "The infrastructure team documented that the failing rollout was tied to the "
        "artifact tagged BUILD-SHA-9f1c2e7 and the asset inventory entry ASSET-TAG-00917, "
        "both purely internal technical references, and confirmed that no customer order "
        "or invoice records were involved in the incident at any point."))

    return {"pii_type": "SENSITIVE_ACCOUNT_ID", "detector_label": "sensitive_account_id", "threshold": 0.50, "cases": cases}


BUILDERS: Dict[str, Any] = {
    "USERNAME": build_username,
    "IP_ADDRESS": build_ip_address,
    "ACCOUNT_ID": build_account_id,
    "SENSITIVE_ACCOUNT_ID": build_sensitive_account_id,
}
