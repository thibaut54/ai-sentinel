# [Bug][Scan] Démarrer un scan « sélection » force les autres spaces interrompus à COMPLETED : « OK / 100 % » affiché pour des spaces scannés partiellement (PII masquées)

> **Issue GitHub** : [thibaut54/ai-sentinel#20](https://github.com/thibaut54/ai-sentinel/issues/20) · **Label** : `bug` · **Sévérité** : Haute (intégrité PII) · **Confiance** : 3/3 · **Date** : 2026-07-08

## Contexte

Il n'existe **aucun statut `ScanStatus` représentant un travail interrompu/aborté** côté backend. Au démarrage d'un scan « sélection », `purgePreviousScanDataForSpaces` appelle `resolveStaleActiveCheckpoints`, censé résoudre les checkpoints « fantômes » restés actifs.

## Scénario de reproduction

1. Lancer un scan **global**. Laisser A se terminer (COMPLETED) et B démarrer, jusqu'à une progression **partielle** (ex. **40 %**).
2. Cliquer **Pause** (ou interrompre : fermeture d'onglet / coupure SSE / crash) → B reste PAUSED (ou RUNNING) à ~40 %. Le dashboard montre correctement B à 40 %.
3. Sélectionner un **autre** space (C) et cliquer « Scan sélection » → `streamSelectedSpaces(['C'])` → `purgePreviousScanDataForSpaces(['C'])` → `resolveStaleActiveCheckpoints(['C'])`.

## Comportement attendu

Les spaces interrompus **hors de la nouvelle sélection** doivent refléter qu'ils sont **incomplets** (ou être nettoyés), afin que l'opérateur ne croie pas que les PII y ont été entièrement traitées.

## Comportement observé

L'UPDATE natif fait `status='COMPLETED'` pour **tout** checkpoint RUNNING/PAUSED dont le `space_key` n'est pas dans la sélection, **en laissant `progress_percentage` figé** (40 %). `getGlobalScanSummary` le mappe COMPLETED ; le frontend **force `percent=100`** pour COMPLETED. Résultat : un space scanné à ~40 % affiche un badge vert **« OK » à 100 %**, **masquant ~60 % de pages jamais scannées et toute PII qui s'y trouve**. Le faux « OK » persiste jusqu'à un purge manuel ou un re-scan.

## Cause racine

- `pii-reporting-api/.../jpa/DetectionCheckpointRepository.java:76` — `resolveStaleActiveCheckpoints` : `UPDATE scan_checkpoints SET status='COMPLETED' WHERE status IN ('RUNNING','PAUSED') AND space_key NOT IN (:spaceKeys)` (ne touche pas `progress_percentage`).
- Appelé depuis `ContentScanOrchestrator.java:150` (`purgePreviousScanDataForSpaces`) sur le chemin « scan sélection » (`StreamConfluenceScanUseCase.java:142`).
- `ScanStatus` sans état interrompu/aborté ; `ScanCheckpointService.java:193` mappe COMPLETED.
- Frontend force 100 % — `pii-reporting-ui/.../scan-status-polling.service.ts:221` (+ `scan-status.utils.ts:14` COMPLETED→OK).

> Asymétrie révélatrice : le chemin de re-scan **global** fait un `DELETE` des checkpoints actifs périmés (correct), alors que le chemin **sélection** les force à COMPLETED.

## Impact / Sévérité — **Haute** (intégrité PII)

Pour un outil de détection de PII, présenter un space **partiellement** scanné comme entièrement scanné/propre est un **défaut de masquage** : l'opérateur ne re-scannera pas, et les PII des pages non analysées restent invisibles.

## Pistes de correction

Ne pas forcer les checkpoints interrompus à COMPLETED. Soit (a) **DELETE** des checkpoints RUNNING/PAUSED hors-sélection (le space revient à NOT_STARTED), soit (b) introduire un état explicite `INTERRUPTED` (déjà présent dans l'union `UiSpaceStatus` frontend) mappé sur un badge distinct de « OK », **et cesser de forcer `percent=100`** pour les spaces non réellement terminés.

## Méthode de détection

Analyse de code + vérification adversariale multi-agents (**3/3 confirmé**). Nuance : le scan étant séquentiel, au plus **un** space est en progression partielle à la fois (le pluriel du titre est indicatif) ; le défaut tient pour ce space.
