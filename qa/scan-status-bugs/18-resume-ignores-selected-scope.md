# [Bug][Scan] Reprise d'un scan « sélection » en pause : re-scan de toute la base Confluence (scope non persisté côté serveur)

> **Issue GitHub** : [thibaut54/ai-sentinel#18](https://github.com/thibaut54/ai-sentinel/issues/18) · **Label** : `bug` · **Sévérité** : Haute · **Confiance** : 3/3 · **Date** : 2026-07-08

## Contexte

Un scan « sélection » ne scanne qu'un sous-ensemble de spaces choisi par l'utilisateur. Le scope de sélection n'existe **que par requête** (`streamSelectedSpaces(spaceKeys)`) et dans le signal frontend `currentScanSpaceKeys` ; **il n'est jamais persisté côté serveur** (les `scan_checkpoints` sont clés par `(scanId, spaceKey)`, sans colonne de scope). Le bouton « Reprendre » est unique et sans distinction global/sélection.

## Scénario de reproduction

1. Base Confluence avec ~50 spaces. Sélectionner **2 spaces** (A, B) et lancer « Scan sélection ». → checkpoints créés pour A et B seulement.
2. Pendant que c'est **RUNNING**, cliquer **Pause** (`pauseAllRunningCheckpoints` : A,B → PAUSED ; `scanPaused=true`, bouton Reprendre actif).
3. Cliquer **Reprendre** → `resumeLastScan()` → `startAllSpacesStream(scanId)` → `GET /api/v1/stream/confluence/spaces/events?scanId=…` → `resumeAllSpaces(scanId)`.

## Comportement attendu

La reprise ne continue que les **2 spaces** du scan sélection d'origine, en scannant leurs pages restantes.

## Comportement observé

`resumeAllSpaces` **itère `confluenceAccessor.getAllSpaces()`** (les ~50 spaces). Pour les 48 spaces jamais sélectionnés, `findByScanAndSpace(scanId, key)` renvoie vide → `computeRemainingPages(pages, null)` renvoie **toutes** les pages → **scan intégral** de chaque space, avec nouveaux checkpoints/résultats/compteurs sous ce `scanId`. Le dashboard montre alors des dizaines de spaces jamais choisis passer RUNNING → « OK », et **le scope explose silencieusement de 2 à N**.

## Cause racine

- `pii-reporting-api/.../StreamConfluenceResumeScanUseCase.java:50` — `resumeAllSpaces` itère `getAllSpaces()` au lieu de `findByScan(scanId)`.
- `.../StreamConfluenceResumeScanUseCase.java:63` / `:91` — checkpoint `null` pour les spaces hors-sélection, pas de court-circuit.
- `ScanRemainingPagesCalculator.computeRemainingPages(pages, null)` → renvoie toutes les pages.
- `.../ConfluencePersonallyIdentifiableInformationScanController.java:100` — branche resume ; `pii-reporting-ui/.../scan-control.service.ts:372` — `resumeLastScan` appelle inconditionnellement `startAllSpacesStream(meta.scanId)`.

Le javadoc de `streamAllSpaces` (« For resuming a paused scan, use resumeAllSpaces(scanId) instead ») confirme que le resume a été conçu **all-spaces** : l'interaction sélection/reprise est simplement non gérée.

## Impact / Sévérité — **Haute**

Explosion silencieuse du périmètre : scan et persistance de résultats/PII pour des dizaines de spaces jamais sélectionnés ; gaspillage de ressources ; statut UI trompeur. Aggravé après reload (`currentScanSpaceKeys=null`, cf. #16 sur la perte de scope).

## Pistes de correction

Dans `resumeAllSpaces`, piloter l'itération par les checkpoints du scan (`scanCheckpointRepository.findByScan(scanId)`) au lieu de `getAllSpaces()`, ou filtrer `getAllSpaces()` sur l'ensemble des `spaceKeys` présents dans `findByScan(scanId)`.

## Méthode de détection

Analyse de code + vérification adversariale multi-agents (**3/3 confirmé**).
