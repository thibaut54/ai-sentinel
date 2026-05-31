"""GLiNER2 FP/FN evaluation fixtures for the Government / tax-ID family.

Seven canonical PII types, each exercised across 6 evaluation axes in four
languages (fr/en/de/it). Spans are always computed byte-exact via ``_span`` so
the runner's ``text[start:end] == value`` invariant holds.
"""
from __future__ import annotations

from typing import Any, Dict, List

from _fixture_helpers import _span, _case


def build_government_id() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Pièce d'identité étatique : CHE-ID-884512 délivrée au guichet."
    cases.append(_case("GOVERNMENT_ID_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "CHE-ID-884512")]))
    t = "Government ID on file: AB123456C for the applicant."
    cases.append(_case("GOVERNMENT_ID_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "AB123456C")]))
    t = "Staatlicher Ausweis (Government ID): CHE-ID-771209 hinterlegt."
    cases.append(_case("GOVERNMENT_ID_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "CHE-ID-771209")]))
    t = "Documento d'identità statale: CHE-ID-552108 registrato a sistema."
    cases.append(_case("GOVERNMENT_ID_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "CHE-ID-552108")]))

    # canonical_no_clue
    t = "CHE-ID-884512"
    cases.append(_case("GOVERNMENT_ID_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "CHE-ID-884512")]))
    t = "AB123456C"
    cases.append(_case("GOVERNMENT_ID_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "AB123456C")]))
    t = "CHE-ID-771209"
    cases.append(_case("GOVERNMENT_ID_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "CHE-ID-771209")]))
    t = "CHE-ID-552108"
    cases.append(_case("GOVERNMENT_ID_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "CHE-ID-552108")]))

    # look_alikes (badges, customer numbers — not state IDs)
    cases.append(_case("GOVERNMENT_ID_look_alikes_fr_01", "fr", "look_alikes",
        "Badge employé : EMP-884512 à présenter à l'accueil."))
    cases.append(_case("GOVERNMENT_ID_look_alikes_en_01", "en", "look_alikes",
        "Customer number CUST-AB123456 was migrated to the new CRM."))
    cases.append(_case("GOVERNMENT_ID_look_alikes_de_01", "de", "look_alikes",
        "Mitarbeiterausweis-Nr. BADGE-771209 für den Zutritt."))
    cases.append(_case("GOVERNMENT_ID_look_alikes_it_01", "it", "look_alikes",
        "Codice cliente CLI-552108 nel gestionale interno."))
    cases.append(_case("GOVERNMENT_ID_look_alikes_en_02", "en", "look_alikes",
        "Asset tag SRV-AB123456C mounted in rack B12."))
    cases.append(_case("GOVERNMENT_ID_look_alikes_fr_02", "fr", "look_alikes",
        "Référence dossier RH-CHE-884512 archivée."))

    # explicit_negatives (keyword, no value)
    cases.append(_case("GOVERNMENT_ID_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "La pièce d'identité étatique doit être présentée lors du dépôt du dossier."))
    cases.append(_case("GOVERNMENT_ID_explicit_negatives_en_01", "en", "explicit_negatives",
        "A valid government ID is required to open an account; no number is stored here."))
    cases.append(_case("GOVERNMENT_ID_explicit_negatives_de_01", "de", "explicit_negatives",
        "Ein gültiger staatlicher Ausweis ist erforderlich, jedoch keine Nummer hinterlegt."))
    cases.append(_case("GOVERNMENT_ID_explicit_negatives_it_01", "it", "explicit_negatives",
        "È richiesto un documento d'identità statale valido, ma nessun numero è memorizzato."))

    # adversarial_formatting
    t = "ID étatique :\nCHE-ID-884512"
    cases.append(_case("GOVERNMENT_ID_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "CHE-ID-884512")]))
    t = "Government ID > AB123456C <"
    cases.append(_case("GOVERNMENT_ID_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "AB123456C")]))
    # too fragmented -> no span
    cases.append(_case("GOVERNMENT_ID_adversarial_formatting_de_01", "de", "adversarial_formatting",
        "Ausweis: C H E - I D - 7 7 1 2 0 9"))

    # long_context
    t = (
        "Page Confluence — Procédure d'onboarding RH (révision 12)\n\n"
        "Cette page décrit les étapes d'intégration d'un nouveau collaborateur. "
        "Le pipeline CI/CD (Jenkins job onboarding-prod) provisionne les comptes "
        "LDAP via le rôle ansible 'hr-bootstrap'. Les secrets sont stockés dans "
        "Vault sous le mount kv/hr. Étape 4 — vérification documentaire : scanner "
        "la pièce d'identité étatique du collaborateur. Exemple de dossier validé : "
        "pièce d'identité étatique CHE-ID-884512 enregistrée dans GED Alfresco. "
        "Étape 5 — activation du badge physique (système Kaba), provisioning du "
        "poste (image Windows 11 standard), et inscription à la mailing-list "
        "interne. Les logs d'audit sont expédiés vers Splunk index=hr-audit. "
        "Pour toute anomalie, ouvrir un ticket Jira projet HR avec le label "
        "'onboarding-blocker' et mentionner le responsable de service."
    )
    cases.append(_case("GOVERNMENT_ID_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "CHE-ID-884512")]))
    t = (
        "Confluence page — Infrastructure runbook (Government services platform)\n\n"
        "This runbook documents the deployment topology of the citizen portal. "
        "The Kubernetes namespace 'gov-portal' hosts 14 microservices behind an "
        "NGINX ingress. Database is PostgreSQL 15 (RDS instance gov-portal-db-1), "
        "connection pooling via PgBouncer. The identity verification service calls "
        "an external KYC provider over mTLS; no government ID number is persisted "
        "in our datastore, only a hashed reference token like REF-AB123456 used for "
        "idempotency. Asset tags such as SRV-77120900 track rack hardware. "
        "Monitoring uses Prometheus + Grafana (dashboard 'gov-portal-overview'), "
        "alerts route to PagerDuty schedule 'gov-oncall'. Backups run nightly to S3 "
        "bucket gov-portal-backups with 30-day retention."
    )
    cases.append(_case("GOVERNMENT_ID_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "GOVERNMENT_ID",
        "detector_label": "government_id",
        "threshold": 0.50,
        "cases": cases,
    }


def build_national_id() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numéro de sécurité sociale (INSEE) : 1 84 12 75 116 001 42."
    cases.append(_case("NATIONAL_ID_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "1 84 12 75 116 001 42")]))
    t = "National ID number: ID-CH-7782341 recorded for the resident."
    cases.append(_case("NATIONAL_ID_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "ID-CH-7782341")]))
    t = "Nationale Ausweisnummer: ID-CH-4410982 im Register erfasst."
    cases.append(_case("NATIONAL_ID_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "ID-CH-4410982")]))
    t = "Codice fiscale del residente: RSSMRA85T10A562S."
    cases.append(_case("NATIONAL_ID_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "RSSMRA85T10A562S")]))

    # canonical_no_clue
    t = "1 84 12 75 116 001 42"
    cases.append(_case("NATIONAL_ID_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "1 84 12 75 116 001 42")]))
    t = "ID-CH-7782341"
    cases.append(_case("NATIONAL_ID_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "ID-CH-7782341")]))
    t = "ID-CH-4410982"
    cases.append(_case("NATIONAL_ID_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "ID-CH-4410982")]))
    t = "RSSMRA85T10A562S"
    cases.append(_case("NATIONAL_ID_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "RSSMRA85T10A562S")]))

    # look_alikes (invoice no, ObjectId, GUID)
    cases.append(_case("NATIONAL_ID_look_alikes_en_01", "en", "look_alikes",
        "MongoDB ObjectId 507f1f77bcf86cd799439011 returned by the query."))
    cases.append(_case("NATIONAL_ID_look_alikes_fr_01", "fr", "look_alikes",
        "Numéro de facture FAC-2024-7782341 émis le 12 mars."))
    cases.append(_case("NATIONAL_ID_look_alikes_de_01", "de", "look_alikes",
        "Korrelations-GUID 3f2504e0-4f89-41d3-9a0c-0305e82c3301 im Log."))
    cases.append(_case("NATIONAL_ID_look_alikes_it_01", "it", "look_alikes",
        "Numero ordine ORD-RSSMRA85 evaso dal magazzino."))
    cases.append(_case("NATIONAL_ID_look_alikes_en_02", "en", "look_alikes",
        "Transaction id TX-4410982-CH posted to the ledger."))
    cases.append(_case("NATIONAL_ID_look_alikes_fr_02", "fr", "look_alikes",
        "Référence colis 1 84 12 99 000 555 11 suivie par le transporteur."))

    # explicit_negatives
    cases.append(_case("NATIONAL_ID_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numéro de sécurité sociale ne doit jamais être transmis par e-mail non chiffré."))
    cases.append(_case("NATIONAL_ID_explicit_negatives_en_01", "en", "explicit_negatives",
        "Please do not paste your national ID number into the public chat channel."))
    cases.append(_case("NATIONAL_ID_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die nationale Ausweisnummer wird gemäß DSGVO nur verschlüsselt gespeichert."))
    cases.append(_case("NATIONAL_ID_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il codice fiscale non deve essere inviato tramite canali non sicuri."))

    # adversarial_formatting
    t = "Codice fiscale:\tRSSMRA85T10A562S"
    cases.append(_case("NATIONAL_ID_adversarial_formatting_it_01", "it", "adversarial_formatting", t,
        [_span(t, "RSSMRA85T10A562S")]))
    t = "ID national => ID-CH-7782341"
    cases.append(_case("NATIONAL_ID_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "ID-CH-7782341")]))
    # too fragmented
    cases.append(_case("NATIONAL_ID_adversarial_formatting_en_01", "en", "adversarial_formatting",
        "National ID: R S S M R A 8 5 T 1 0 A 5 6 2 S"))

    # long_context
    t = (
        "Confluence — Spécification du connecteur registre national (v3.2)\n\n"
        "Le connecteur synchronise les données d'état civil entre le SI métier et "
        "le registre cantonal. Architecture : un consumer Kafka (topic "
        "'civil-events', 6 partitions) écrit dans une base Oracle 19c. Le mapping "
        "des champs est décrit dans le schéma Avro 'CivilRecord'. Cas de test "
        "validé en recette : un résident dont le codice fiscale RSSMRA85T10A562S a "
        "été correctement rapproché de son dossier existant. Le batch nocturne "
        "(Spring Batch job 'civil-sync') traite environ 12 000 enregistrements. "
        "Les rejets sont écrits dans la table CIVIL_REJECT et notifiés sur le "
        "canal Teams 'civil-ops'. Sécurité : appels mTLS, audit dans Elastic "
        "index 'civil-audit', rotation des clés tous les 90 jours via HashiCorp "
        "Vault. Les performances cibles sont de 500 req/s en pointe."
    )
    cases.append(_case("NATIONAL_ID_long_context_it_01", "it", "long_context", t,
        [_span(t, "RSSMRA85T10A562S")]))
    t = (
        "Confluence — National registry batch design notes\n\n"
        "The reconciliation batch matches incoming records against the master "
        "registry. It runs as an Airflow DAG ('registry_recon') scheduled at 02:00 "
        "UTC. Each task shards by region and writes parquet files to the data lake "
        "(s3://gov-lake/registry/). No national ID number appears in these design "
        "notes — only synthetic keys like REC-4410982 and correlation GUIDs such "
        "as 3f2504e0-4f89-41d3-9a0c-0305e82c3301 used for idempotency. Failures are "
        "retried up to three times with exponential backoff, then dead-lettered to "
        "the 'registry_dlq' SQS queue. Observability is provided by OpenTelemetry "
        "traces exported to Jaeger, and SLOs are tracked in the 'registry-slo' "
        "Grafana dashboard."
    )
    cases.append(_case("NATIONAL_ID_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "NATIONAL_ID",
        "detector_label": "national_id_number",
        "threshold": 0.50,
        "cases": cases,
    }


def build_passport() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numéro de passeport : X1234567 valable jusqu'en 2030."
    cases.append(_case("PASSPORT_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "X1234567")]))
    t = "Passport number: 490123456 issued by the US Department of State."
    cases.append(_case("PASSPORT_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "490123456")]))
    t = "Reisepassnummer: C01X00T47 am Schalter erfasst."
    cases.append(_case("PASSPORT_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "C01X00T47")]))
    t = "Numero di passaporto: C03005988 registrato in anagrafe."
    cases.append(_case("PASSPORT_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "C03005988")]))

    # canonical_no_clue
    t = "X1234567"
    cases.append(_case("PASSPORT_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "X1234567")]))
    t = "490123456"
    cases.append(_case("PASSPORT_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "490123456")]))
    t = "C01X00T47"
    cases.append(_case("PASSPORT_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "C01X00T47")]))
    t = "C03005988"
    cases.append(_case("PASSPORT_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "C03005988")]))

    # look_alikes (order ref, product code, serial)
    cases.append(_case("PASSPORT_look_alikes_en_01", "en", "look_alikes",
        "Order reference ORD-X1234567 shipped via DHL."))
    cases.append(_case("PASSPORT_look_alikes_fr_01", "fr", "look_alikes",
        "Code produit C03005988 référencé au catalogue 2024."))
    cases.append(_case("PASSPORT_look_alikes_de_01", "de", "look_alikes",
        "Seriennummer C01X00T47 auf dem Typenschild des Geräts."))
    cases.append(_case("PASSPORT_look_alikes_it_01", "it", "look_alikes",
        "Numero di serie SN-490123456 sull'etichetta del prodotto."))
    cases.append(_case("PASSPORT_look_alikes_en_02", "en", "look_alikes",
        "SKU X1234567 is out of stock in the EU warehouse."))
    cases.append(_case("PASSPORT_look_alikes_fr_02", "fr", "look_alikes",
        "Bon de livraison BL-C03005988 signé à réception."))

    # explicit_negatives
    cases.append(_case("PASSPORT_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numéro de passeport est requis pour la demande de visa, à fournir au consulat."))
    cases.append(_case("PASSPORT_explicit_negatives_en_01", "en", "explicit_negatives",
        "Upload a scan of your passport number page; we do not store the number itself."))
    cases.append(_case("PASSPORT_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Reisepassnummer ist bei der Einreise vorzulegen, wird hier aber nicht gespeichert."))
    cases.append(_case("PASSPORT_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il numero di passaporto è necessario per il visto, ma non viene conservato."))

    # adversarial_formatting
    t = "Passeport :\nX1234567"
    cases.append(_case("PASSPORT_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "X1234567")]))
    t = "Passport no. -> C03005988"
    cases.append(_case("PASSPORT_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "C03005988")]))
    # too fragmented
    cases.append(_case("PASSPORT_adversarial_formatting_de_01", "de", "adversarial_formatting",
        "Reisepass: C 0 1 X 0 0 T 4 7"))

    # long_context
    t = (
        "Confluence — Procédure de contrôle aux frontières (système border-gate)\n\n"
        "Ce document décrit l'intégration du lecteur MRZ avec le back-office. Le "
        "lecteur publie un message AMQP sur l'exchange 'mrz.scan' consommé par le "
        "service border-gate-api (Quarkus, GraalVM native). Les données sont "
        "chiffrées au repos (AES-256-GCM) dans une base PostgreSQL. Exemple de "
        "scan validé en environnement de recette : numéro de passeport X1234567 "
        "rapproché de la liste de surveillance sans correspondance. Le débit cible "
        "est de 30 scans/minute par poste. Les métriques sont exposées au format "
        "Prometheus (/q/metrics) et agrégées dans Grafana. En cas de panne du "
        "lecteur, le mode dégradé bascule sur saisie manuelle avec double "
        "validation. Les journaux d'audit sont conservés 7 ans conformément à la "
        "réglementation, dans un index Elastic 'border-audit' à accès restreint."
    )
    cases.append(_case("PASSPORT_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "X1234567")]))
    t = (
        "Confluence — Travel-document OCR pipeline architecture\n\n"
        "The OCR pipeline ingests scanned travel documents from an SFTP drop and "
        "extracts the machine-readable zone. Stages: (1) Tesseract OCR worker, "
        "(2) field validation, (3) persistence. It runs on a Celery cluster with "
        "Redis as the broker. No real passport number appears in this design doc — "
        "the integration tests use synthetic order references like ORD-X1234567 and "
        "product SKUs such as C03005988 as fixtures. The pipeline emits structured "
        "logs to Loki and exposes health at /healthz. Throughput in load tests "
        "reached 45 documents/second on a 4-node cluster. Dead-letter items go to "
        "the 'ocr-dlq' queue and are reviewed manually by the ops team during "
        "business hours."
    )
    cases.append(_case("PASSPORT_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "PASSPORT",
        "detector_label": "passport_number",
        "threshold": 0.50,
        "cases": cases,
    }


def build_driver_license() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numéro de permis de conduire : 12AB34567 (catégorie B)."
    cases.append(_case("DRIVER_LICENSE_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "12AB34567")]))
    t = "Driver's license number: D1234567 valid until 2029."
    cases.append(_case("DRIVER_LICENSE_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "D1234567")]))
    t = "Führerscheinnummer: B072RRE2I55 eingetragen."
    cases.append(_case("DRIVER_LICENSE_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "B072RRE2I55")]))
    t = "Numero della patente di guida: D1234567 della titolare."
    cases.append(_case("DRIVER_LICENSE_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "D1234567")]))

    # canonical_no_clue
    t = "12AB34567"
    cases.append(_case("DRIVER_LICENSE_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "12AB34567")]))
    t = "D1234567"
    cases.append(_case("DRIVER_LICENSE_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "D1234567")]))
    t = "B072RRE2I55"
    cases.append(_case("DRIVER_LICENSE_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "B072RRE2I55")]))
    t = "D7654321"
    cases.append(_case("DRIVER_LICENSE_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "D7654321")]))

    # look_alikes (other licenses, asset tags)
    cases.append(_case("DRIVER_LICENSE_look_alikes_en_01", "en", "look_alikes",
        "Software license key D1234567 activated on three seats."))
    cases.append(_case("DRIVER_LICENSE_look_alikes_fr_01", "fr", "look_alikes",
        "Étiquette d'inventaire ASSET-12AB34567 collée sur le portable."))
    cases.append(_case("DRIVER_LICENSE_look_alikes_de_01", "de", "look_alikes",
        "Lizenzschlüssel B072RRE2I55 für die Buchhaltungssoftware."))
    cases.append(_case("DRIVER_LICENSE_look_alikes_it_01", "it", "look_alikes",
        "Tag asset SRV-D1234567 nel rack del datacenter."))
    cases.append(_case("DRIVER_LICENSE_look_alikes_en_02", "en", "look_alikes",
        "Fishing permit number 12AB34567 issued by the county office."))
    cases.append(_case("DRIVER_LICENSE_look_alikes_fr_02", "fr", "look_alikes",
        "Numéro de série moteur B072RRE2I55 gravé sur le bloc."))

    # explicit_negatives
    cases.append(_case("DRIVER_LICENSE_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numéro de permis de conduire sera vérifié lors de la location du véhicule."))
    cases.append(_case("DRIVER_LICENSE_explicit_negatives_en_01", "en", "explicit_negatives",
        "A driver's license number is mandatory to rent a car; bring the physical card."))
    cases.append(_case("DRIVER_LICENSE_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Führerscheinnummer wird bei der Anmietung geprüft, aber nicht gespeichert."))
    cases.append(_case("DRIVER_LICENSE_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il numero della patente di guida è richiesto al noleggio, ma non viene conservato."))

    # adversarial_formatting
    t = "Permis de conduire :\nD1234567"
    cases.append(_case("DRIVER_LICENSE_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "D1234567")]))
    t = "DL no. = 12AB34567"
    cases.append(_case("DRIVER_LICENSE_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "12AB34567")]))
    # too fragmented
    cases.append(_case("DRIVER_LICENSE_adversarial_formatting_de_01", "de", "adversarial_formatting",
        "Führerschein: B 0 7 2 R R E 2 I 5 5"))

    # long_context
    t = (
        "Confluence — Module de location de véhicules (fleet-rental)\n\n"
        "Le module gère le cycle de vie d'une réservation, du devis à la "
        "restitution. Stack : Spring Boot 3, base MySQL 8, files RabbitMQ pour les "
        "notifications. À l'étape de remise des clés, l'agent scanne le permis du "
        "client via l'app mobile (React Native). Cas de recette validé : permis de "
        "conduire 12AB34567 contrôlé et photographié, état des lieux signé "
        "électroniquement. Les contrats PDF sont générés via le service éditique et "
        "archivés dans la GED. Les KPIs (taux de restitution à l'heure, dommages "
        "déclarés) remontent dans un dashboard Metabase. Les sauvegardes de la base "
        "sont quotidiennes avec rétention de 35 jours. Un job de purge RGPD anonymise "
        "les dossiers clos depuis plus de 3 ans."
    )
    cases.append(_case("DRIVER_LICENSE_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "12AB34567")]))
    t = (
        "Confluence — Telematics ingestion service design\n\n"
        "This service ingests telematics events from the rental fleet over MQTT. "
        "Each vehicle publishes GPS, odometer and fault codes every 30 seconds. "
        "Events land in a Kafka topic ('telematics-raw') and are aggregated by a "
        "Flink job into hourly rollups. No driver's license number is stored in "
        "this pipeline — only vehicle asset tags such as ASSET-12AB34567 and "
        "software license keys like B072RRE2I55 for the embedded firmware. Data is "
        "retained 90 days in ClickHouse for analytics, then downsampled. The "
        "service exposes Prometheus metrics and is deployed via Helm to the "
        "'fleet' namespace. On-call alerts route to the 'fleet-oncall' PagerDuty "
        "schedule when event lag exceeds five minutes."
    )
    cases.append(_case("DRIVER_LICENSE_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "DRIVER_LICENSE",
        "detector_label": "drivers_license_number",
        "threshold": 0.50,
        "cases": cases,
    }


def build_license_number() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numéro de licence professionnelle : LIC-2023-4471 délivré par l'ordre."
    cases.append(_case("LICENSE_NUMBER_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "LIC-2023-4471")]))
    t = "Software license number: XKQR-5T8A-Z9PM-3RNF for the Pro edition."
    cases.append(_case("LICENSE_NUMBER_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "XKQR-5T8A-Z9PM-3RNF")]))
    t = "Lizenznummer: LIC-2023-4471 für die Geschäftstätigkeit hinterlegt."
    cases.append(_case("LICENSE_NUMBER_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "LIC-2023-4471")]))
    t = "Numero di licenza commerciale: LIC-2024-8820 rilasciato al titolare."
    cases.append(_case("LICENSE_NUMBER_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "LIC-2024-8820")]))

    # canonical_no_clue
    t = "LIC-2023-4471"
    cases.append(_case("LICENSE_NUMBER_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "LIC-2023-4471")]))
    t = "XKQR-5T8A-Z9PM-3RNF"
    cases.append(_case("LICENSE_NUMBER_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "XKQR-5T8A-Z9PM-3RNF")]))
    t = "LIC-2022-1093"
    cases.append(_case("LICENSE_NUMBER_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "LIC-2022-1093")]))
    t = "LIC-2024-8820"
    cases.append(_case("LICENSE_NUMBER_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "LIC-2024-8820")]))

    # look_alikes (driver license confusion, version numbers)
    cases.append(_case("LICENSE_NUMBER_look_alikes_fr_01", "fr", "look_alikes",
        "Permis de conduire D1234567 présenté au comptoir de location."))
    cases.append(_case("LICENSE_NUMBER_look_alikes_en_01", "en", "look_alikes",
        "Release version v2023.4.471 deployed to production last night."))
    cases.append(_case("LICENSE_NUMBER_look_alikes_de_01", "de", "look_alikes",
        "Führerscheinnummer B072RRE2I55 wurde am Schalter geprüft."))
    cases.append(_case("LICENSE_NUMBER_look_alikes_it_01", "it", "look_alikes",
        "Versione del firmware 5.8.0-rnf3 installata sul dispositivo."))
    cases.append(_case("LICENSE_NUMBER_look_alikes_en_02", "en", "look_alikes",
        "Build number 2024-8820 generated by the CI pipeline."))
    cases.append(_case("LICENSE_NUMBER_look_alikes_fr_02", "fr", "look_alikes",
        "Numéro de permis de conduire 12AB34567 saisi par erreur dans ce champ."))

    # explicit_negatives
    cases.append(_case("LICENSE_NUMBER_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numéro de licence figure sur votre facture d'abonnement, section produits."))
    cases.append(_case("LICENSE_NUMBER_explicit_negatives_en_01", "en", "explicit_negatives",
        "Pass your software license number as the --license CLI flag at startup."))
    cases.append(_case("LICENSE_NUMBER_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Lizenznummer ist im Kundenportal einsehbar, wird hier nicht angezeigt."))
    cases.append(_case("LICENSE_NUMBER_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il numero di licenza è indicato nel contratto, ma non è riportato qui."))

    # adversarial_formatting
    t = "Licence :\nLIC-2023-4471"
    cases.append(_case("LICENSE_NUMBER_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "LIC-2023-4471")]))
    t = "License key -> XKQR-5T8A-Z9PM-3RNF"
    cases.append(_case("LICENSE_NUMBER_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "XKQR-5T8A-Z9PM-3RNF")]))
    # too fragmented
    cases.append(_case("LICENSE_NUMBER_adversarial_formatting_de_01", "de", "adversarial_formatting",
        "Lizenz: L I C - 2 0 2 3 - 4 4 7 1"))

    # long_context
    t = (
        "Confluence — Gestion des licences logicielles (license-server)\n\n"
        "Le serveur de licences distribue et révoque les clés des produits "
        "internes. Architecture : un service Go exposant une API gRPC, base "
        "PostgreSQL avec chiffrement transparent, cache Redis pour les "
        "vérifications. À l'activation, le client envoie sa clé et reçoit un jeton "
        "JWT signé (durée 24h). Cas validé en recette : licence professionnelle "
        "LIC-2023-4471 activée sur cinq postes sans dépassement de quota. Les "
        "renouvellements sont gérés par un cron 'license-renew' qui notifie 30 "
        "jours avant expiration. Les métriques d'usage alimentent la facturation "
        "(intégration avec le module billing). Les révocations sont propagées en "
        "moins de 60 secondes via un canal pub/sub. Les logs sont expédiés vers "
        "Loki et conservés 1 an."
    )
    cases.append(_case("LICENSE_NUMBER_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "LIC-2023-4471")]))
    t = (
        "Confluence — Release engineering handbook\n\n"
        "This handbook documents our release train. Versions follow CalVer "
        "(e.g. v2024.8.820) and are cut every two weeks from the 'release' branch. "
        "Artifacts are signed with cosign and pushed to the internal registry. No "
        "license number appears here — only build numbers like 2024-8820 and "
        "firmware versions such as 5.8.0-rnf3. The pipeline runs in GitHub Actions, "
        "with matrix jobs across Linux, macOS and Windows runners. Smoke tests gate "
        "promotion to the 'stable' channel. Rollbacks are performed by re-pointing "
        "the channel alias to the previous immutable tag. Incident reviews are "
        "tracked in the 'release-incidents' space and linked to the relevant build."
    )
    cases.append(_case("LICENSE_NUMBER_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "LICENSE_NUMBER",
        "detector_label": "license_number",
        "threshold": 0.50,
        "cases": cases,
    }


def build_tax_id() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Identifiant fiscal (TIN) du contribuable : 12-3456789."
    cases.append(_case("TAX_ID_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "12-3456789")]))
    t = "Employer tax ID (EIN): 12-3456789 on the W-9 form."
    cases.append(_case("TAX_ID_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "12-3456789")]))
    t = "Steuer-ID des Steuerpflichtigen: 61 952 3741 82."
    cases.append(_case("TAX_ID_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "61 952 3741 82")]))
    t = "Codice identificativo fiscale (TIN): 12-3456789 del soggetto."
    cases.append(_case("TAX_ID_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "12-3456789")]))

    # canonical_no_clue
    t = "12-3456789"
    cases.append(_case("TAX_ID_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "12-3456789")]))
    t = "98-7654321"
    cases.append(_case("TAX_ID_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "98-7654321")]))
    t = "61 952 3741 82"
    cases.append(_case("TAX_ID_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "61 952 3741 82")]))
    t = "44 123 9988 01"
    cases.append(_case("TAX_ID_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "44 123 9988 01")]))

    # look_alikes (phone number, SIRET)
    cases.append(_case("TAX_ID_look_alikes_fr_01", "fr", "look_alikes",
        "SIRET de l'entreprise : 123 456 789 00012 au registre du commerce."))
    cases.append(_case("TAX_ID_look_alikes_en_01", "en", "look_alikes",
        "Call the support line at +1 12-3456789 during business hours."))
    cases.append(_case("TAX_ID_look_alikes_de_01", "de", "look_alikes",
        "Telefonnummer der Hotline: 061 952 37 41 von 8 bis 18 Uhr."))
    cases.append(_case("TAX_ID_look_alikes_it_01", "it", "look_alikes",
        "Partita IVA SIRET 123 456 789 00012 nel registro imprese."))
    cases.append(_case("TAX_ID_look_alikes_en_02", "en", "look_alikes",
        "Internal ticket 12-3456789 was closed as a duplicate."))
    cases.append(_case("TAX_ID_look_alikes_fr_02", "fr", "look_alikes",
        "Numéro de téléphone direct : 06 19 52 37 41 (ne pas diffuser)."))

    # explicit_negatives
    cases.append(_case("TAX_ID_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "L'identifiant fiscal doit figurer sur la déclaration annuelle de revenus."))
    cases.append(_case("TAX_ID_explicit_negatives_en_01", "en", "explicit_negatives",
        "Provide your tax ID when prompted; we never log the EIN value itself."))
    cases.append(_case("TAX_ID_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Steuer-ID ist auf dem Steuerbescheid angegeben, hier jedoch nicht hinterlegt."))
    cases.append(_case("TAX_ID_explicit_negatives_it_01", "it", "explicit_negatives",
        "Il codice fiscale identificativo va riportato nella dichiarazione dei redditi."))

    # adversarial_formatting
    t = "EIN :\n12-3456789"
    cases.append(_case("TAX_ID_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "12-3456789")]))
    t = "Steuer-ID => 61 952 3741 82"
    cases.append(_case("TAX_ID_adversarial_formatting_de_01", "de", "adversarial_formatting", t,
        [_span(t, "61 952 3741 82")]))
    # too fragmented
    cases.append(_case("TAX_ID_adversarial_formatting_fr_01", "fr", "adversarial_formatting",
        "TIN : 1 2 - 3 4 5 6 7 8 9"))

    # long_context
    t = (
        "Confluence — Onboarding fournisseur US (vendor-tax-validation)\n\n"
        "Ce service valide les informations fiscales des fournisseurs américains "
        "avant tout paiement. Il appelle l'API de validation EIN de l'IRS via un "
        "proxy sortant, met en cache les réponses 24h dans Redis et persiste le "
        "statut dans une base PostgreSQL chiffrée. Cas de recette validé : "
        "fournisseur dont l'identifiant fiscal EIN 12-3456789 a été confirmé comme "
        "actif, déclenchant la création du compte créditeur dans l'ERP. Les appels "
        "sont tracés (OpenTelemetry vers Jaeger) et les erreurs de validation "
        "génèrent un ticket Jira automatique. Le service tourne en trois réplicas "
        "derrière un Service mesh Istio, avec mTLS strict. Les données fiscales "
        "sont purgées 7 ans après la fin de la relation commerciale, conformément "
        "à la politique de rétention."
    )
    cases.append(_case("TAX_ID_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "12-3456789")]))
    t = (
        "Confluence — Payments reconciliation runbook\n\n"
        "The reconciliation job matches outgoing payments against vendor invoices "
        "nightly. It runs as a Kubernetes CronJob ('recon-nightly') and writes a "
        "report to S3. No tax ID appears in this runbook — only internal ticket "
        "numbers like 12-3456789 (a Jira-style id, not an EIN) and phone numbers "
        "such as +1 12-3456789 for the finance hotline. Discrepancies above a "
        "configurable threshold are flagged for manual review in the 'recon-review' "
        "queue. The job uses idempotency keys to avoid double-posting. Metrics are "
        "scraped by Prometheus and visualized in the 'payments-recon' Grafana "
        "dashboard, with alerts routed to the finance-ops channel on Slack."
    )
    cases.append(_case("TAX_ID_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "TAX_ID",
        "detector_label": "tax_id",
        "threshold": 0.50,
        "cases": cases,
    }


def build_tax_number() -> Dict[str, Any]:
    cases: List[Dict[str, Any]] = []

    # canonical_with_clue
    t = "Numéro de TVA de la société : CHE-123.456.789 TVA."
    cases.append(_case("TAX_NUMBER_canonical_with_clue_fr_01", "fr", "canonical_with_clue", t,
        [_span(t, "CHE-123.456.789 TVA")]))
    t = "VAT number for invoicing: FR12345678901 on all EU orders."
    cases.append(_case("TAX_NUMBER_canonical_with_clue_en_01", "en", "canonical_with_clue", t,
        [_span(t, "FR12345678901")]))
    t = "Umsatzsteuer-Nummer: CHE-123.456.789 MWST hinterlegt."
    cases.append(_case("TAX_NUMBER_canonical_with_clue_de_01", "de", "canonical_with_clue", t,
        [_span(t, "CHE-123.456.789 MWST")]))
    t = "Numero di partita IVA: FR12345678901 indicato in fattura."
    cases.append(_case("TAX_NUMBER_canonical_with_clue_it_01", "it", "canonical_with_clue", t,
        [_span(t, "FR12345678901")]))

    # canonical_no_clue
    t = "CHE-123.456.789 TVA"
    cases.append(_case("TAX_NUMBER_canonical_no_clue_fr_01", "fr", "canonical_no_clue", t,
        [_span(t, "CHE-123.456.789 TVA")]))
    t = "FR12345678901"
    cases.append(_case("TAX_NUMBER_canonical_no_clue_en_01", "en", "canonical_no_clue", t,
        [_span(t, "FR12345678901")]))
    t = "1234567890"
    cases.append(_case("TAX_NUMBER_canonical_no_clue_de_01", "de", "canonical_no_clue", t,
        [_span(t, "1234567890")]))
    t = "CHE-987.654.321 IVA"
    cases.append(_case("TAX_NUMBER_canonical_no_clue_it_01", "it", "canonical_no_clue", t,
        [_span(t, "CHE-987.654.321 IVA")]))

    # look_alikes (partial IBAN, postcode+order, tax_id confusion)
    cases.append(_case("TAX_NUMBER_look_alikes_fr_01", "fr", "look_alikes",
        "Début d'IBAN CH93 0076 2011 6238 5295 7 copié dans le presse-papier."))
    cases.append(_case("TAX_NUMBER_look_alikes_en_01", "en", "look_alikes",
        "Shipping code 75001-12345678901 combines postcode and order id."))
    cases.append(_case("TAX_NUMBER_look_alikes_de_01", "de", "look_alikes",
        "UTR-ähnliche Referenz 1234567890 ist eigentlich eine Bestellnummer."))
    cases.append(_case("TAX_NUMBER_look_alikes_it_01", "it", "look_alikes",
        "Frammento IBAN IT60 X054 2811 1010 0000 0123 456 nel ticket."))
    cases.append(_case("TAX_NUMBER_look_alikes_en_02", "en", "look_alikes",
        "EIN 12-3456789 is a US tax ID, not a VAT number for this region."))
    cases.append(_case("TAX_NUMBER_look_alikes_fr_02", "fr", "look_alikes",
        "Code postal + commande : 1004-FR12345678901 sur l'étiquette."))

    # explicit_negatives
    cases.append(_case("TAX_NUMBER_explicit_negatives_fr_01", "fr", "explicit_negatives",
        "Le numéro de TVA intracommunautaire doit apparaître sur toutes les factures émises."))
    cases.append(_case("TAX_NUMBER_explicit_negatives_en_01", "en", "explicit_negatives",
        "Enter your VAT number in the billing settings; it is validated against VIES."))
    cases.append(_case("TAX_NUMBER_explicit_negatives_de_01", "de", "explicit_negatives",
        "Die Umsatzsteuer-Nummer muss auf jeder Rechnung ausgewiesen werden."))
    cases.append(_case("TAX_NUMBER_explicit_negatives_it_01", "it", "explicit_negatives",
        "La partita IVA va indicata in fattura, ma non è memorizzata in questo campo."))

    # adversarial_formatting
    t = "TVA :\nCHE-123.456.789 TVA"
    cases.append(_case("TAX_NUMBER_adversarial_formatting_fr_01", "fr", "adversarial_formatting", t,
        [_span(t, "CHE-123.456.789 TVA")]))
    t = "VAT no. -> FR12345678901"
    cases.append(_case("TAX_NUMBER_adversarial_formatting_en_01", "en", "adversarial_formatting", t,
        [_span(t, "FR12345678901")]))
    # too fragmented
    cases.append(_case("TAX_NUMBER_adversarial_formatting_de_01", "de", "adversarial_formatting",
        "USt: F R 1 2 3 4 5 6 7 8 9 0 1"))

    # long_context
    t = (
        "Confluence — Module de facturation multi-pays (billing-tax)\n\n"
        "Le module calcule la TVA applicable selon le pays de livraison et le "
        "statut du client (B2B/B2C). Il s'appuie sur une table de taux mise à jour "
        "trimestriellement et valide les numéros de TVA via le service VIES de la "
        "Commission européenne. Cas validé en recette : facture B2B émise à un "
        "client dont le numéro de TVA CHE-123.456.789 TVA a été reconnu, déclenchant "
        "l'autoliquidation. Les factures sont générées en PDF/A-3 avec une pièce "
        "jointe Factur-X. L'archivage légal se fait dans un coffre-fort numérique "
        "qualifié. Les écritures comptables sont poussées vers l'ERP via une file "
        "Kafka 'accounting-events'. Les anomalies de calcul sont remontées sur le "
        "canal 'billing-alerts' et un rapport mensuel de cohérence est produit."
    )
    cases.append(_case("TAX_NUMBER_long_context_fr_01", "fr", "long_context", t,
        [_span(t, "CHE-123.456.789 TVA")]))
    t = (
        "Confluence — Invoice numbering and shipping codes\n\n"
        "This page standardizes our internal numbering schemes. Invoice ids follow "
        "INV-YYYY-NNNNNN, shipping codes combine postcode and order id (e.g. "
        "75001-12345678901), and warehouse bins use a three-letter aisle prefix. No "
        "VAT number appears here — the string FR12345678901 below is a shipping "
        "order fragment, not a tax number, and CH93 0076 2011 6238 5295 7 is an "
        "IBAN example. The numbering service runs as a small stateless API behind "
        "the API gateway, backed by a Postgres sequence per scheme. Collisions are "
        "impossible by design. The page is reviewed each quarter by the logistics "
        "and finance teams to keep the schemes aligned with downstream systems."
    )
    cases.append(_case("TAX_NUMBER_long_context_en_01", "en", "long_context", t))

    return {
        "pii_type": "TAX_NUMBER",
        "detector_label": "tax_number",
        "threshold": 0.50,
        "cases": cases,
    }


BUILDERS: Dict[str, Any] = {
    "GOVERNMENT_ID": build_government_id,
    "NATIONAL_ID": build_national_id,
    "PASSPORT": build_passport,
    "DRIVER_LICENSE": build_driver_license,
    "LICENSE_NUMBER": build_license_number,
    "TAX_ID": build_tax_id,
    "TAX_NUMBER": build_tax_number,
}
