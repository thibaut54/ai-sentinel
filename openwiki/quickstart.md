# AI Sentinel — OpenWiki Quickstart

AI Sentinel scanne des espaces **Confluence** à la recherche d'informations
personnelles (PII), affiche des rapports par espace/page, et permet de **caviarder
(obfusquer)** ces PII directement dans les pages Confluence.

Le produit combine trois moteurs de détection (Ministral, Microsoft Presidio, regex)
derrière un post-filtre déterministe, orchestrés par un backend Java, avec une interface
Angular pour piloter les scans et la remédiation.

> Cette documentation est une **carte d'opinion** au-dessus du code. Chaque affirmation
> importante renvoie à un fichier source (chemin relatif au dépôt). En cas de conflit
> entre une doc et le code, le code fait foi — voir [Caveats](#caveats--pièges-connus).

## Ce que fait le produit

- **Détection PII multi-modèle** : noms, e-mails, téléphones, IBAN, cartes, AVS/SSN,
  identifiants fiscaux, secrets techniques (tokens, clés API)… avec seuils de confiance
  configurables (global + par type) et labels personnalisés zéro-shot.
- **Scan Confluence** : parcours d'espaces/pages/pièces jointes (PDF, images via OCR),
  suivi temps réel (SSE), pause/reprise, event sourcing des résultats.
- **Rapports** : statistiques par espace et par page, sévérité (high/medium/low), score
  de risque, export Excel.
- **Remédiation par obfuscation** : sélection de findings, prévisualisation d'un plan,
  exécution asynchrone de jobs qui réécrivent le stockage XHTML des pages Confluence pour
  masquer les valeurs sensibles, avec suivi de progression.

Contexte métier : conformité RGPD / nLPD. Les PII ne quittent jamais l'infrastructure —
tout est traité localement, et les valeurs sensibles stockées sont **chiffrées** (AES-GCM).

## Architecture en un coup d'œil

Trois services conteneurisés, orchestrés par Docker Compose :

| Service | Techno | Rôle | Port |
|---|---|---|---|
| `pii-reporting-ui` | Angular 21 (standalone/signals, PrimeNG) | Interface web | 4200 |
| `pii-reporting-api` | Spring Boot 4.0.0-M2 / Java 25 (hexagonal) | Orchestration scans, rapports, remédiation, intégration Confluence | 8080 (context-path `/ai-sentinel`) |
| `pii-detector` | Python 3 / gRPC | Détection PII (Ministral + Presidio + Regex + post-filtre) | 50051 |

Support : **PostgreSQL 18** (persistance), **Infisical** (gestionnaire de secrets interne),
optionnellement **pgAdmin** et **MailHog**.

Le backend appelle le détecteur en **gRPC** (contrat dans `proto/pii_detection.proto`) et
Confluence en **HTTP** (Cloud ou Data Center). Le diagramme de composants est dans
`docs/ai-sentinel-components.drawio.svg` (référencé par `README.md`).

Détails : voir [architecture/system-overview.md](architecture/system-overview.md).

## Carte du dépôt

```
ai-sentinel/
├── pii-detector-service/     # Service Python de détection (gRPC)  → detection-service.md
│   ├── pii_detector/         #   domain/ application/ infrastructure/ (hexagonal)
│   ├── config/models/        #   presidio-detector.toml, regex-patterns.toml
│   └── tests/                #   unit/ + integration/ (pytest, testcontainers)
├── pii-reporting-api/        # Backend Spring Boot (hexagonal)      → backend-api.md
│   ├── src/main/java/pro/softcom/aisentinel/
│   │   ├── domain/ application/ infrastructure/
│   └── init-scripts/         #   000..014 *.sql (schéma Postgres)
├── pii-reporting-ui/         # Frontend Angular                    → frontend-ui.md
│   ├── src/app/{core,shared,features}/
│   └── e2e/                  #   specs Playwright
├── proto/                    # Contrat gRPC (pii_detection.proto)
├── docker/                   # Scripts bootstrap secrets + Infisical (+ stack Doccano annexe)
├── docker-compose.yml        # Stack prod (images GHCR)
├── docker-compose.dev.yml    # Stack dev (build local)
├── docker-compose-dgnsi.yml  # Overlay on-prem (CA d'entreprise)
├── benchmarks/               # Harnais d'évaluation P/R/F1 + chunking
└── docs/                     # Specs, plans, diagrammes existants
```

## Sections de la documentation

- **[architecture/system-overview.md](architecture/system-overview.md)** — vue d'ensemble
  des composants, contrat gRPC, flux de données, aspects transverses (chiffrement,
  sécurité, feature flags).
- **[architecture/detection-service.md](architecture/detection-service.md)** — le service
  Python : détecteurs, orchestration/merge, post-filtre déterministe, chunking, config.
- **[architecture/backend-api.md](architecture/backend-api.md)** — le backend Java :
  architecture hexagonale, bounded contexts, persistance, surface REST.
- **[architecture/frontend-ui.md](architecture/frontend-ui.md)** — le frontend Angular :
  structure, features (dashboard, settings, obfuscation), intégration API, i18n, tests.
- **[workflows/scan-workflow.md](workflows/scan-workflow.md)** — cycle de vie complet d'un
  scan Confluence (création, exécution, pause/reprise, persistance).
- **[workflows/obfuscation-workflow.md](workflows/obfuscation-workflow.md)** — remédiation :
  identité des findings, plan/exécution/suivi de jobs, réécriture XHTML, reveal.
- **[operations.md](operations.md)** — déploiement Docker, bootstrap Infisical, secrets,
  CI/CD, tests, benchmarks.

## Démarrage rapide (utilisateur)

Prérequis : Docker Desktop + Docker Compose. Le premier lancement suit un **bootstrap en
deux étapes** (génération des secrets Infisical) — c'est normal de voir des erreurs au
premier `up`. Voir [operations.md](operations.md#démarrage-en-deux-étapes) et `README.md`.

```bash
docker compose up -d
docker compose up -d --force-recreate pii-detector pii-reporting-api pii-reporting-ui
```

Interface : http://localhost:4200 — API : http://localhost:8080/ai-sentinel

## Démarrage rapide (développeur)

Chaque module se développe/teste indépendamment (il n'y a **pas** de POM racine ; lancez
Maven dans `pii-reporting-api/`).

| Module | Dev | Tests |
|---|---|---|
| `pii-detector-service` | `python -m pii_detector.server` (port 50051) | `pytest` (unit/ + integration/) |
| `pii-reporting-api` | Spring Boot (context-path `/ai-sentinel`) | `mvn test` (unit) ; profil `unit-and-integration-tests` pour les IT |
| `pii-reporting-ui` | `pnpm start` (:4200, proxy `/api` → :8080) | `pnpm test` (Vitest) ; `pnpm e2e` (Playwright) |

Détails de commandes et pièges de build : chaque page d'architecture a une section
« Développer & tester », et [operations.md](operations.md) centralise l'infra.

## Où intervenir pour une modification

- **Ajouter/ajuster un détecteur ou un type de PII** →
  [detection-service.md](architecture/detection-service.md) + config DB (`pii_type_config`,
  `pii_detection_config`) + settings UI.
- **Changer le flux de scan** (SSE, checkpoints, timeouts) →
  [scan-workflow.md](workflows/scan-workflow.md).
- **Toucher au caviardage** (réécriture XHTML, cycle de vie des findings) →
  [obfuscation-workflow.md](workflows/obfuscation-workflow.md).
- **Modifier une vue** → [frontend-ui.md](architecture/frontend-ui.md).
- **Déploiement / secrets / CI** → [operations.md](operations.md).

## Caveats & pièges connus

- **Versions de stack** : le `README.md` racine annonce « Spring Boot 3 » ; le
  `pii-reporting-api/pom.xml` est en réalité **Spring Boot 4.0.0-M2 / Java 25** (milestone,
  non-LTS). Le `pom.xml` fait foi. Les READMEs de module (`pii-reporting-ui/README.md`
  « Angular 20.2.2/Karma », `pii-reporting-api/README.md` « Java 24/PG 15 ») sont **périmés**.
- **Détecteurs réduits à trois** : Ministral, Regex, Presidio (+ post-filtre déterministe).
  GLiNER/GLiNER2/OpenMed et le « LLM-judge » ont été retirés ; leurs tags dans le proto sont
  réservés. Ignorez toute mention historique de ces composants.
- **Sécurité** : le backend est en `permitAll()` (pas d'auth applicative — déploiement
  local supposé). Le contrôle d'accès repose sur des feature flags + audit, pas sur une
  identité.
- **Design system** : l'UI utilise **PrimeNG**, pas Foehn/prestations-ng (contrairement à
  ce que suggèrent des instructions d'agent globales).
- **Incohérence d'enum** : le proto expose `PREFILTER=7` tandis que le domaine Python émet
  `POSTFILTER` — la stat de post-filtre peut arriver côté Java en `UNKNOWN_SOURCE`
  (à confirmer). Voir [detection-service.md](architecture/detection-service.md).
