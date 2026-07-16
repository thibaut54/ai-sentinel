# Backend API (`pii-reporting-api/`)

Backend **Spring Boot 4.0.0-M2 / Java 25** (`pom.xml`, artefact
`pro.softcom:ai-sentinel:1.2.0-SNAPSHOT`) en **architecture hexagonale stricte**, vérifiée
par ArchUnit. Il orchestre les scans, appelle le détecteur en gRPC, intègre Confluence,
persiste les résultats et pilote la remédiation.

## Architecture hexagonale

Sous `src/main/java/pro/softcom/aisentinel/` :

- **`domain/`** — records/enums/services de domaine purs (aucune dépendance framework).
  Ex. `domain/pii/remediation/FindingReference.java`, `domain/pii/security/EncryptionService.java`.
- **`application/<contexte>/`** — découpé en `port/in`, `port/out`, `usecase`, `service`,
  `exception`.
- **`infrastructure/<contexte>/adapter/in`** (controllers REST, DTOs, mappers) et
  **`adapter/out`** (JPA, clients HTTP/gRPC, config).

Les frontières sont **imposées** par
`src/test/java/.../architecture/HexagonalArchitectureTest.java`. En contribuant, séparez la
logique métier des dépendances techniques, passez par des ports/adapters, et gardez une
complexité cyclomatique basse.

Stack notable (`pom.xml`) : Web MVC + WebFlux (SSE), Spring Security, Data JPA/JDBC,
PostgreSQL, **Armeria** (client gRPC) + grpc-netty-shaded, **jsoup** (XHTML), **PDFBox** +
**Tika** (extraction pièces jointes), **re2j** (regex linéaire anti-ReDoS), Lombok,
springdoc/OpenAPI. Tests : JUnit 5, Mockito, Awaitility, Testcontainers, ArchUnit.

## Bounded contexts

| Contexte | Racine package | Responsabilité & classes clés |
|---|---|---|
| **PII scan** (transport détecteur) | `application/pii/scan`, `infrastructure/pii/scan` | Appel gRPC au détecteur. Port `PiiDetectorClient` ; adapters `GrpcPiiDetectorArmeriaClientAdapter` (défaut) + variante netty ; `ArmeriaPiiGrpcClientConfiguration`. Map proto → `ContentPiiDetection`. |
| **PII reporting** | `application/pii/reporting`, `infrastructure/pii/reporting` | Orchestration de scan, SSE, checkpoints, event store, compteurs de sévérité, reveal. Voir [scan-workflow](../workflows/scan-workflow.md). |
| **PII remediation / obfuscation** | `application/pii/remediation`, `infrastructure/pii/remediation` | Plan/exécution/suivi de jobs de caviardage, cycle de vie des findings. Voir [obfuscation-workflow](../workflows/obfuscation-workflow.md). |
| **Intégration Confluence** | `application/confluence`, `infrastructure/confluence` | Port `ConfluenceClient` ; adapters HTTP séparés Cloud vs Data Center (`Confluence{Cloud,DataCenter}HttpClientAdapter`, `DelegatingConfluenceClient`) ; pagination, retry, download d'attachements ; cache d'espaces ; `ConfluenceAccessor`. Config connexion en base (chiffrée). |
| **Config détection / types PII** | `application/pii/detection`, `infrastructure/pii/detection` | CRUD des configs par détecteur et de la config globale (dont **endpoint LM Studio** `lm_studio_host/port`). `PiiTypeConfigController`, `PiiDetectionConfigController`, `ManagePiiTypeConfigsUseCase`, `ManagePiiDetectionConfigUseCase`. **Inbox de labels découverts** (MINISTRAL open-vocab) : `DiscoveredLabelController`, `ManageDiscoveredLabelsUseCase`, collecte via `DiscoveredLabelCollector`. |
| **Export** | `application/pii/export`, `infrastructure/pii/export` | Export Excel des résultats, déclenché sur l'événement `SpaceScanCompleted`. |
| **Config / sécurité / audit** | `application/config`, `application/pii/security`, `infrastructure/config` | Config de polling, audit d'accès PII (nLPD), chiffrement (voir [system-overview](system-overview.md#chiffrement-des-données-sensibles)). |

## Surface REST

| Controller | Base path | Endpoints (résumé) |
|---|---|---|
| `ConfigController` | `/api/v1/config` | `GET /polling` |
| `ConfluenceController` | `/api/v1/confluence` | `health`, `pages/{id}`, `spaces/{key}[/search|/pages]`, `spaces`, `spaces/update-info` |
| `ConfluenceConnectionConfigController` | `/api/v1/confluence/connection-config` | `GET`, `PUT`, `POST /test` |
| `Confluence…ScanController` | `/api/v1/stream` | flux SSE de scan + `POST /{scanId}/resume|/pause` |
| `LastConfluence…ScanController` | `/api/v1/scans` | `GET /last`, `/last/spaces`, `/last/items`, `/dashboard/spaces-summary` |
| `ScanSpaceStatsController` | `/api/v1/scans/dashboard/spaces` | `GET /{spaceKey}/stats` |
| `ScanPurgeController` | `/api/v1/scans` | `POST /purge` |
| `PiiAccessController` | `/api/v1/pii` | `GET /config/reveal-allowed`, `POST /reveal-page` |
| `PiiRemediationController` | `/api/v1/pii/remediation` | voir [obfuscation-workflow](../workflows/obfuscation-workflow.md) |
| `PiiTypeConfigController` | `/api/v1/pii-detection/pii-types` | CRUD types + `grouped[/by-category]`, `PUT /bulk` |
| `PiiDetectionConfigController` | `/api/v1/pii-detection/config` | `GET`, `PUT` (dont endpoint LM Studio) |
| `DiscoveredLabelController` | `/api/v1/pii-detection/discovered-labels` | `GET` (en attente), `POST /{label}/promote`, `POST /{label}/ignore` |

Doc OpenAPI : `/ai-sentinel/swagger-ui.html`.

## Persistance (PostgreSQL)

`spring.jpa.hibernate.ddl-auto=update` fait autorité ; les `init-scripts/*.sql`
(idempotents, `CREATE TABLE IF NOT EXISTS`) gardent les environnements Docker cohérents.
`src/main/resources/data.sql` seed les configs PII après le schéma
(`defer-datasource-initialization: true`).

`pii-reporting-api/init-scripts/` :

| Script | Contenu |
|---|---|
| `000-create-database.sql` | Création DB |
| `001-scan-checkpoints.sql` | `scan_checkpoints` (PK scan_id+space_key, verrou optimiste `version`) |
| `002-scan-events.sql` | `scan_runs` + `scan_events` (JSONB `payload`, index GIN) — event sourcing |
| `003-confluence-spaces.sql` | cache `confluence_spaces` |
| `004-pii-access-audit.sql` | `pii_access_audit` (journal d'accès nLPD) |
| `005-scan-severity-counts.sql` | `scan_severity_counts` |
| `006-pii-type-config.sql` | `pii_type_config` (types par détecteur, seuils) |
| `007-pii-detection-config.sql` | `pii_detection_config` (config globale) + ALTERs |
| `008-confluence-connection-config.sql` | `confluence_connection_config` (token chiffré) |
| `011-scan-space-stats.sql` | `scan_space_stats`, `scan_detector_stats` |
| `013-add-ministral-columns.sql` | colonnes chunking Ministral + contrainte détecteur |
| `013-scan-pii-type-counts.sql` | `scan_pii_type_counts` (compteurs agrégés par type PII et par espace, UPSERT atomique) |
| `014-pii-finding-remediation.sql` | `pii_finding_remediation` + `pii_redaction_job` (JSONB) + **index unique partiel** un job actif par espace |
| `014-add-lm-studio-columns.sql` | `lm_studio_host`/`lm_studio_port` sur `pii_detection_config` (endpoint LM Studio, défaut localhost:1234) |
| `015-ministral-discovered-label.sql` | `ministral_discovered_label` (labels open-vocab droppés, comptes agrégés, statut ; **jamais de valeur PII**) |

> **Deux fichiers `013` et deux `014`** (préfixes réutilisés) coexistent : l'ordre
> alphabétique reste cohérent car chaque script est idempotent et le schéma fait
> autorité via `ddl-auto`. Les numéros 009/010/012 sont absents (appliqués via
> `ddl-auto` ou ailleurs) — à confirmer si vous ajoutez une migration.

## Configuration (`application.yml` — pas de secrets)

- DB : `jdbc:postgresql://localhost:5435/ai-sentinel` par défaut (override par env ;
  l'app locale peut lire `:5433`). `open-in-view: false`.
- gRPC détecteur : `pii-detector.client=armeria` (armeria|grpc), `host/port` (50051),
  `default-threshold: 0.75`, timeouts **30 min** (relevés pour machines CPU-bound).
- Scan : `scan.timeouts.pii-detection: 1810s` (> timeout gRPC),
  `scan.page-concurrency` (env `PII_SCAN_PAGE_CONCURRENCY`, défaut 1).
- Feature flags : `pii.reporting.allow-secret-reveal`, `pii.remediation.enabled` (défaut
  true), `pii.audit.retention-days: 730` (purge cron `0 0 3 * * ?`).
- `pii-encryption.kek-pii-encryption-key` (env).
- Serveur app 8080 avec **context-path `/ai-sentinel`** ; services internes Armeria 8090 ;
  Actuator expose Prometheus (`pii.scan.chars.total`, `pii.scan.duration`).

## Développer & tester

```bash
cd pii-reporting-api
mvn test                                     # unitaires (Mockito)
mvn test -Punit-and-integration-tests        # + tests d'intégration (**/integration/*)
mvn test -Dtest=HexagonalArchitectureTest    # frontières hexagonales
```

- ~373 fichiers `main` + ~157 de test. Nommage `Should_…_When_…`.
- IT (`src/test/java/.../integration/`) **exclus par défaut** de Surefire ; adapters
  Testcontainers-Postgres suffixés `…IT`. Corpus d'attachements réels dans
  `src/test/resources/corpus/`.
- Surefire nécessite les flags Java 25 (`-XX:+EnableDynamicAgentLoading`, `--add-opens`)
  pour Mockito.
- **À surveiller** : respecter les frontières hexagonales (ArchUnit casse sinon) ; l'identité
  des findings exclut volontairement scanId/offsets/sévérité (voir
  [obfuscation-workflow](../workflows/obfuscation-workflow.md)) ; le chiffrement est
  systématique sur les valeurs sensibles.

## Décisions notables

- **Sécurité `permitAll()`** : pas d'auth applicative (voir
  [system-overview](system-overview.md#sécurité--contrôle-daccès)).
- **Confluence Cloud vs Data Center** : deux adapters HTTP derrière un client délégant.
- **Spring Boot 4.0.0-M2 (milestone) + Java 25** : stack de pointe, non-LTS.
- **Timeouts gRPC volontairement longs** (30 min) pour détecteurs CPU-bound ; le timeout
  reactor (1810 s) est calibré pour se déclencher *après* la deadline gRPC.
