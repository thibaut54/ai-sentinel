# Workflow : scan Confluence

Cycle de vie complet d'un scan PII : de la requête UI jusqu'à la persistance des résultats.
Acteurs : UI Angular → backend Java (contexte *pii reporting*) → client Confluence →
détecteur gRPC → PostgreSQL. Voir aussi
[backend-api.md](../architecture/backend-api.md) et
[detection-service.md](../architecture/detection-service.md).

## Points d'entrée REST

Controller : `infrastructure/pii/reporting/adapter/in/ConfluencePersonallyIdentifiableInformationScanController.java`
— base `/api/v1/stream`, réponses **SSE** (`text/event-stream`) :

- `GET /confluence/space/{spaceKey}/events` — un espace
- `GET /confluence/spaces/events` — tous les espaces
- `GET /confluence/spaces/events/selected` — espaces sélectionnés
- `POST /{scanId}/resume`, `POST /{scanId}/pause`

Côté lecture/reporting :
`LastConfluencePersonallyIdentifiableInformationScanController` (`/api/v1/scans/last`,
`/last/spaces`, `/last/items`, `/dashboard/spaces-summary`).

## Déroulé d'un scan

1. **Démarrage** — `StreamConfluenceScanUseCase` (`application/pii/reporting/usecase`)
   génère un `scanId` (UUID), purge les checkpoints précédents, et construit un flux réactif
   `Flux<ConfluenceContentScanResult>` : `MULTI_START` → corps par espace (`concatMap`,
   séquentiel) → `MULTI_COMPLETE`. Exécution et souscription sont **découplées** via
   `PersonallyIdentifiableInformationScanExecutionOrchestratorPort` (`startScan` /
   `subscribeScan`), dont l'adapter est `ScanTaskManagerAdapter`.

2. **Corps du scan** — `AbstractStreamConfluenceScanUseCase.runScanFlux` est le cœur :
   - récupère pages et pièces jointes via `ConfluenceAccessor`,
   - nettoie le HTML avec `HtmlContentParser`,
   - appelle `PiiDetectorClient.analyzeContent` par page (pièces jointes via
     `AttachmentProcessor` + Tika/PDFBox/OCR),
   - traite les pages en `flatMapSequential(..., pageConcurrency)` ; `index()` estampille
     l'ordre source **avant** la région concurrente pour garder l'ordre/les checkpoints
     stables (`pageConcurrency=1` = ancien `concatMap`).
   - Timeouts : reactor `.timeout(scanTimeoutConfig)` + `DEADLINE_EXCEEDED` gRPC, tous deux
     mappés en événements d'erreur.

3. **Persistance par événement** — chaque événement émis :
   - **checkpoint persisté de façon synchrone**
     (`ContentScanOrchestrator.persistCheckpointSynchronously`) pour éviter les re-scans à
     un refresh,
   - compteurs de sévérité, append à l'event store et stats d'espace enregistrés **en
     asynchrone** sur `boundedElastic` avec retry/backoff.

   > Ce découpage **sync-checkpoint / async-le-reste** est un correctif délibéré d'une
   > race de double-comptage de sévérité au rafraîchissement.

4. **Stockage** :
   - `JpaScanEventStoreAdapter` → `scan_events` (event sourcing, payload JSONB),
   - `ScanCheckpointPersistenceAdapter` → `scan_checkpoints` (verrou optimiste `version`),
   - `ScanSeverityCountPersistenceAdapter` → `scan_severity_counts`,
   - `ScanEventSequencer` / `ScanEventBuffer` séquencent les événements.
   - Init-scripts : `001` (checkpoints), `002` (events), `005` (severity counts),
     `011` (space/detector stats).

5. **Pause / reprise** — `PauseScanUseCase`, `StreamConfluenceResumeScanUseCase`. Les
   transitions de statut sont gardées par `ScanCheckpointStatusTransition` /
   `IllegalScanStatusTransitionException` (COMPLETED/FAILED sont immuables).

## Chiffrement & audit

Les valeurs PII détectées sont **chiffrées** (AES-GCM) avant persistance ; l'accès au clair
(SSE reveal, endpoint reveal) est conditionné par `pii.reporting.allow-secret-reveal` et
**audité** dans `pii_access_audit`. Voir
[system-overview.md](../architecture/system-overview.md#chiffrement-des-données-sensibles).

## Événements SSE côté UI

L'UI ouvre le flux et réagit aux types : `multiStart`, `start`, `pageStart`, `item`,
`attachmentItem`, `pageComplete`, `scanError`, `complete`, `multiComplete`, `keepalive`
(parsés dans `NgZone.run`). Voir [frontend-ui.md](../architecture/frontend-ui.md#dashboard--reporting-featuresconfluence-dashboard).

## À surveiller lors d'une modification

- **Ordre & checkpoints** : ne pas déplacer l'`index()` hors de la zone séquentielle, sous
  peine de casser l'ordre source et la reprise.
- **Timeouts** : garder `scan.timeouts.pii-detection` (1810 s) **au-dessus** du timeout
  gRPC (30 min côté client), sinon reactor coupe avant la deadline.
- **Race sévérité** : conserver le checkpoint synchrone.
- **Concurrence** : `PII_SCAN_PAGE_CONCURRENCY` > 1 parallélise les pages — vérifier que la
  persistance reste idempotente.
- **Détecteur** : le backend passe `fetch_config_from_db=true` ; la config vit en base.

Tests pertinents : IT `DirectPiiDetectionIntegrationTest`, `GrpcDirectConnectionTest`
(profil `unit-and-integration-tests`) ; adapters de persistance `…IT` (Testcontainers).
