# Rapport de correction — Bugs de statuts de scan (#16 → #21)

> **Branche** : `release/v1.2.0-rc.1-integration-bugfix` · **Date** : 2026-07-10 · **Périmètre** : `pii-reporting-api` (Java/Spring WebFlux) + `pii-reporting-ui` (Angular 19) · Rien n'est commité (working tree).

## Décision d'architecture transverse

La cause racine commune aux bugs #16/#18/#19/#21 était l'absence de **persistance du scope de scan** côté serveur et l'absence de **statut de file**. Le remède structurel : au démarrage de tout scan (global ou sélection), le backend crée immédiatement un **checkpoint `NOT_STARTED`** pour chaque space du scope sous le nouveau `scanId`, et le summary expose désormais le **`scanId` par space**. Le scope devient ainsi requêtable et restaurable, et « En attente » redevient dérivable de façon fiable.

## Fix par bug

### #16 — Reload d'un scan « sélection » : perte du scope → « En attente » erroné
- **Backend** : checkpoints `NOT_STARTED` upfront (`ScanCheckpointService.initializeScanScope`, appelé par `StreamConfluenceScanUseCase` pour les deux modes) + `scanId` ajouté à `SpaceSummary`/`SpaceSummaryDto`/mapper.
- **Frontend** : `reconnectIfScanRunning()` reconstruit `currentScanSpaceKeys` depuis les statuses portant le `scanId` du scan courant quand le scope est inconnu (aussi en PAUSED, donc le resume post-reload est correct). `SpaceSummaryDto`/`SpaceScanStateDto` portent `scanId`, propagé par `loadLastSpaceStatuses`.
- **Effet** : après reload, seuls les spaces réellement dans le scan sont badgés « En attente » ; la détection de complétion (`expectedSpaces`) redevient exacte.

### #17 — Reconnexion → pipeline concurrent → double comptage des sévérités
- **Backend** : nouveau `isScanActive(scanId)` sur le port orchestrateur (impl `ScanTaskManagerAdapter` : managed scan non complété, subscription non disposée). `resumeAllSpaces` s'y **rattache** (`subscribeScan`, rejeu du replay buffer, zéro nouveau travail) quand le scan est encore vivant ; le vrai pipeline resume n'est emprunté que pour un scan pausé/mort (subscription disposée à la pause).
- **Effet** : F5 pendant un scan actif ne lance plus de second pipeline — plus de double incrément des `scan_severity_counts`.

### #18 — Reprise d'un scan « sélection » : re-scan de toute la base
- **Backend** : `resumeAllSpaces` est piloté par `findByScan(scanId)` (les checkpoints du scan = le scope persisté) : `getAllSpaces()` filtré sur ces clés, `Flux.empty()` + warn si aucun checkpoint. Les spaces du scope jamais démarrés ont un checkpoint `NOT_STARTED` (upfront) → repris intégralement (`computeRemainingPages(pages, NOT_STARTED)` = toutes les pages, vérifié).
- **Effet** : la reprise reste dans le périmètre sélectionné ; plus d'explosion silencieuse de 2 à N spaces.

### #19 — Relance d'une autre sélection : « En attente » orphelin
- **Frontend** : `resetDashboardForNewScan(selection)` réconcilie les spaces hors de la nouvelle sélection restés en `PENDING` → `NOT_STARTED` (les statuts PAUSED/OK/FAILED/RUNNING sont préservés — l'affichage PAUSED hors-scope est un état backend fidèle, voulu et testé). Source des badges : `spacesDashboardUtils.allSpaces()` (la vraie référence lue/écrite par la table — écart justifié vs spec initiale qui visait `dataManagement.spaces()`, non connectée aux badges).
- **Effet** : plus de badge « En attente » sur un space qui n'appartient à aucun scan actif.

### #20 — Checkpoints interrompus forcés `COMPLETED` (masquage de PII)
- **Backend** : nouveau statut **`ScanStatus.INTERRUPTED`** (terminal, aucune transition sortante) ; `resolveStaleActiveCheckpoints` fait `SET status='INTERRUPTED'` au lieu de `'COMPLETED'` (le DELETE était exclu : FK `scan_severity_counts → scan_checkpoints ON DELETE CASCADE` aurait détruit les compteurs). Colonne TEXT sans contrainte : aucune migration. `mapPresentationStatus` renvoie `"INTERRUPTED"`.
- **Frontend** : badge distinct (label i18n `dashboard.status.interrupted` fr « Interrompu » / en « Interrupted », style `warning`), option de filtre ajoutée ; le progrès reste figé au % réel (le forçage 100 % ne s'applique qu'à COMPLETED).
- **Effet** : un space scanné à 40 % puis abandonné affiche « Interrompu » à 40 % — plus jamais « OK / 100 % ».

### #21 — Reload en fenêtre inter-space : reconnexion sautée
- **Frontend** : le gate de `reconnectIfScanRunning()` ne dépend plus d'un instantané `RUNNING` : reconnexion si le scan courant a au moins un space **non terminal** (`RUNNING` ou `NOT_STARTED` sous le `scanId` du meta — les entrées sans `scanId` sont conservées pour rétro-compat), jamais si un `PAUSED` du scan existe, jamais si tout est terminal (COMPLETED/FAILED/INTERRUPTED).
- **Effet** : recharger pendant le « trou » entre deux spaces reconnecte quand même polling + SSE ; un scan terminé ou pausé ne se reconnecte pas.

## Correction supplémentaire découverte en vérification (hors rapports QA)

**Space vide + checkpoints upfront** : `ScanCheckpointStatusTransition` n'autorisait pas `NOT_STARTED → COMPLETED`. Un space sans page n'émet que l'événement `complete` ; avec les checkpoints upfront, il serait resté bloqué `NOT_STARTED` (complétion UI jamais détectée + reconnexion en boucle). Corrigé : transition `NOT_STARTED → COMPLETED (SYSTEM)` ajoutée, couverte par tests (`Should_AllowCompletion_When_NotStartedInitiatedBySystem`, rejet côté USER, terminalité d'INTERRUPTED).

## Ce qui a été testé (sorties lues, pas seulement rapportées)

| Vérification | Résultat |
|---|---|
| Backend `mvn -o test` (11 classes touchées : use cases scan/resume/reporting, adapter orchestrateur, mappers, filtre dashboard, contrôleur, services checkpoint, garde de transition) | **144 tests, 0 failure, 0 error, 0 skipped** (agrégat surefire XML) |
| Frontend `ng test` (scan-control 50, space-data-management 29, spaces-dashboard.utils 23, dashboard-ui-state 20) | **122 tests verts, 0 échec** |
| Compilation backend offline (`mvn -o`, phase test-compile incluse dans le run) | OK |
| Relecture croisée des hunks critiques (attach/scoped resume, DTO `scanId`, restauration scope, gate reconnexion, réconciliation badges) | Conformes au design |

Nouveaux tests couvrant précisément les scénarios de bug : scan vivant → attach sans nouveau travail ; scan inactif → seuls les spaces de `findByScan` reprennent ; checkpoints upfront initialisés pour le scope exact ; INTERRUPTED exposé par le summary ; restauration du scope au reconnect ; reconnexion en fenêtre inter-space ; non-reconnexion si terminal/pausé ; réconciliation PENDING hors sélection ; label/style INTERRUPTED.

## Ce qui n'a PAS pu être testé en session (à valider en CI / E2E)

- Les **tests d'intégration Testcontainers** (dont le SQL natif de `resolveStaleActiveCheckpoints` et l'UPSERT) — Docker indisponible en session. Les requêtes modifiées sont des changements de littéraux à structure identique.
- Le **flux E2E réel** (scan → F5 → reconnexion attach ; pause → resélection → relance) — nécessite la stack complète (Confluence + détecteurs). Les chemins sont couverts unitairement des deux côtés du contrat.
- La **rétro-compatibilité des scans pré-fix** (sans checkpoints upfront) : comportement dégradé assumé — resume limité aux spaces à checkpoint, restauration de scope partielle.

## Vérification LIVE sur l'app réelle (2026-07-10, backend PROD :8080 branché sur ai-sentinel-db:5433, 268 spaces réels)

Le backend tournant exécute bien le code corrigé (le champ `scanId` par space est présent dans `/dashboard/spaces-summary` — discriminant du fix B6/#16). Vérifications déterministes via API + DB :

| Bug | Test live | Résultat |
|-----|-----------|----------|
| **#20** | Checkpoint périmé `RUNNING`@40 % (AGILE) injecté, puis scan sélection d'un autre space → `resolveStaleActiveCheckpoints` | AGILE → **`INTERRUPTED`@40 %** en DB **et** dans le summary API (pas `COMPLETED`/100 %). ✅ |
| **B3 + #16** | Scan sélection d'1 space (3 pages) | checkpoint créé sous un **nouveau scanId**, `scanId` peuplé dans le summary. ✅ |
| **#18** | Resume du scanId (scan terminé) | **1 seul checkpoint** sous ce scanId (le space scanné), **pas 268** ; total DB inchangé. Resume borné au scope via `findByScan`. ✅ |
| **#17** | Resume **concurrent** déclenché pendant que le scan est actif (`NOT_STARTED` au moment du resume) | chemin **attach** emprunté (rejeu du sink, pas de 2ᵉ pipeline) : `pagesDone=3` (pas 6), 1 checkpoint → **aucun double comptage**. ✅ (space sans PII → proxy = compteur d'événements `pagesDone`, même mécanisme additif que les compteurs de sévérité) |

État DB restauré (purge API) après tests : 0 checkpoint.

### Vérification UI navigateur (chrome-devtools MCP, `ng serve` :4200 servant le worktree)

Frontend piloté via chrome-devtools une fois `ng serve` démarré. États backend façonnés de façon déterministe (API + DB) — le frontend suit exactement le même chemin de code qu'avec une action d'un autre client.

| Bug | Test navigateur | Résultat |
|-----|-----------------|----------|
| **#20-front** | space AGILE mis en INTERRUPTED (backend), reload dashboard | ligne « Agile » affiche le badge **« Interrompu »** (pas « OK/100 % »). ✅ |
| **#16-front** | scan « sélection » de 40 spaces, reload pendant qu'il est actif/pausé | page 1 mélange **correct par scope** : in-scope (« admin SonarCloud IT », « Alexandre Herbert », « André Bratschi ») = **« En attente »** ; hors-scope (tous les autres) = **« Non démarré »**. Avant le fix : tout serait « En attente ». Scope restauré depuis le `scanId` par space. ✅ |
| **#21** | reload pendant un scan actif SANS space RUNNING (fenêtre inter-space / démarrage) | panneau **« SCAN EN COURS »** (pas « inactif ») — la reconnexion ne saute plus. ✅ |
| **#19** | depuis l'état pausé (36 spaces « En attente »), relancer un scan sélection d'un autre space (« AI ») via l'UI | les 3 anciens spaces « En attente » retombent à **« Non démarré »** ; « AI » = « Terminé ». Plus de badge orphelin. ✅ |

Données de test purgées après coup (0 checkpoint).

**Observation incidente (hors périmètre des 6 fixes)** : dans l'état pausé affiché après reload, le grand libellé du panneau montrait « SCAN INACTIF » alors que le bouton « Reprendre » était actif (scanPaused=true). Cosmétique, non lié à mes changements (template du panneau non touché) — à confirmer/traiter séparément si souhaité.

## Traçabilité

Issues GitHub : [#16](https://github.com/thibaut54/ai-sentinel/issues/16) · [#17](https://github.com/thibaut54/ai-sentinel/issues/17) · [#18](https://github.com/thibaut54/ai-sentinel/issues/18) · [#19](https://github.com/thibaut54/ai-sentinel/issues/19) · [#20](https://github.com/thibaut54/ai-sentinel/issues/20) · [#21](https://github.com/thibaut54/ai-sentinel/issues/21) — rapports détaillés dans ce dossier.
