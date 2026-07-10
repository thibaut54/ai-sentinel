# [Bug][Dashboard] Reload pendant un scan « sélection » : perte du scope → spaces hors-scope affichés « En attente » (file d'attente fausse)

> **Issue GitHub** : [thibaut54/ai-sentinel#16](https://github.com/thibaut54/ai-sentinel/issues/16) · **Label** : `bug` · **Sévérité** : Haute · **Confiance** : 2/3 · **Date** : 2026-07-08

## Contexte

Le tableau de bord Confluence affiche un badge de statut par *space*. **« En attente » (PENDING) doit signifier « ce space est réellement dans la file du scan en cours et sera scanné »**. Les scans tournent en arrière-plan côté backend ; le frontend dérive le statut par polling de `GET /api/v1/scans/dashboard/spaces-summary`. Le scope d'un scan « sélection » n'est mémorisé que dans le signal *in-memory* `currentScanSpaceKeys` (`null` = scan global).

## Scénario de reproduction

1. Lancer un scan **sélection** sur un sous-ensemble (ex. 2 spaces sur 50).
2. Laisser le scan tourner en arrière-plan, puis **recharger** la page (F5) ou rouvrir le dashboard.

## Comportement attendu

Seuls les 2 spaces réellement dans le scope sont suivis/badgés ; tous les autres restent **« Non démarré » (NOT_STARTED)** puisqu'ils ne seront jamais scannés. « En attente » ne s'affiche que pour des spaces réellement en file.

## Comportement observé

Au reload, `currentScanSpaceKeys` repart à `null` (**jamais restauré** : `initializeDataLoading` → `reconnectIfScanRunning()` ne le réhydrate pas). `buildScanScope()` renvoie alors `null`, donc `isInScanScope()` est vrai pour **tous** les spaces. Résultat : `applySpaceUiState` et `markUnreportedSpacesAsPending` badgent **« En attente »** tout space non démarré / absent du summary — y compris ceux qui ne font pas partie du scan. **L'invariant « En attente = en file » est violé.**

## Cause racine

- `currentScanSpaceKeys` in-memory, jamais persisté/restauré — `pii-reporting-ui/src/app/features/confluence-dashboard/services/space-data-management.service.ts:63`, positionné seulement dans `executeStartSelected`/`executeStartAll`/`onScanComplete`.
- Réhydratation absente au reconnect — `pii-reporting-ui/src/app/features/confluence-dashboard/confluence-dashboard.component.ts:184` → `scan-control.service.ts:395` (`reconnectIfScanRunning`).
- Dérivation du statut sur scope `null` — `pii-reporting-ui/src/app/core/services/scan-status-polling.service.ts:162` (`buildScanScope`), `:211` (`NOT_STARTED`→`PENDING`), `:236` (`markUnreportedSpacesAsPending`).
- **Contrat backend sans scope ni statut file** — `pii-reporting-api/.../ScanReportingUseCase.java:151` (`getGlobalScanSummary`) agrège le *dernier checkpoint par space toutes campagnes confondues* ; aucun statut backend `PENDING`/`QUEUED` : « En attente » est une pure dérivation frontend qui ne peut structurellement pas refléter la vraie file.

## Interaction avec d'autres bugs (honnêteté sur le symptôme)

Le backend ne persiste aucun scope et le resume/reconnect opère sur `getAllSpaces()` : selon ce chemin, le reload « promeut » silencieusement le scan sélection en scan de **toute la base** (voir #18 « Reprise d'un scan sélection re-scanne toute la base » et #17 « Reconnexion → relance concurrente »). **Nuance vérifiée : 1 vérificateur sur 3 conteste le symptôme « gel définitif / scan qui ne se termine jamais »**, précisément parce que le backend finit par tout scanner (donc `detectScanCompletion` peut finir par se déclencher). Les **trois** vérificateurs s'accordent en revanche sur le badge **« En attente » erroné** appliqué aux spaces hors-scope, qui est le cœur de ce ticket.

## Impact / Sévérité — **Haute**

Après tout reload d'un scan ciblé, le statut « En attente » n'est plus fiable : l'utilisateur ne peut plus distinguer les spaces réellement en file de ceux qui ne seront pas scannés.

## Pistes de correction

1. Persister `currentScanSpaceKeys` (ex. `sessionStorage`, clé = `scanId`) et le réhydrater dans `reconnectIfScanRunning()` **avant** de démarrer le polling ; **ou**
2. Exposer le scope/queue du scan courant dans le DTO summary (liste des `spaceKeys` du `scanId`, ou flag *in-scope* par space) pour reconstruire `buildScanScope()` ; **et**
3. Baser `detectScanCompletion` (`:250`) sur les spaces réellement rapportés plutôt que `spaces().length`, et **ne pas** badger « En attente » quand le scope n'a pas pu être restauré.

## Méthode de détection

Analyse de code (frontend + backend) + vérification adversariale multi-agents (**2/3 confirmé**, nuance ci-dessus). Bug non-happy-path : reconnexion à un scan de fond.
