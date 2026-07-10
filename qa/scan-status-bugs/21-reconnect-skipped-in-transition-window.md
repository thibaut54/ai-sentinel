# [Bug][Dashboard] Reload pendant l'inter-space d'un scan : reconnexion sautée → scan affiché « inactif », badges figés

> **Issue GitHub** : [thibaut54/ai-sentinel#21](https://github.com/thibaut54/ai-sentinel/issues/21) · **Label** : `bug` · **Sévérité** : Basse · **Confiance** : 3/3 · **Date** : 2026-07-08

## Contexte

`reconnectIfScanRunning()` décide de rétablir polling/SSE à partir d'un **instantané ponctuel** des statuts (`lastSpaceStatuses`) plutôt que de l'état autoritatif du scan. Les spaces sont scannés **séquentiellement** ; un checkpoint ne devient RUNNING qu'au premier événement de contenu et COMPLETED qu'au `complete`. Il existe donc une **fenêtre inter-space** où **aucun** space n'est RUNNING alors que le scan progresse toujours (le code de polling la gère explicitement via `isTransitioning`).

## Scénario de reproduction

1. Lancer un scan multi-space (ex. 5 spaces) en arrière-plan.
2. Attendre l'instant où un space vient de passer **COMPLETED** et où le suivant n'est **pas encore RUNNING** (ou, plus simple : recharger juste au démarrage, avant que la 1re page du 1er space ne se termine).
3. **Recharger** la page pendant cette fenêtre.

## Comportement attendu

Après reload, le frontend rétablit polling/SSE pour tout scan encore en cours côté backend ; badges et progression continuent de se mettre à jour, le bouton Start reste désactivé.

## Comportement observé

`loadLastSpaceStatuses` capture un summary **sans space RUNNING** ; ~100 ms plus tard `reconnectIfScanRunning()` calcule `hasRunningScan=false` et **retourne prématurément** sans démarrer polling ni SSE. Le seul `forceRefresh()` en tête de méthode s'exécute avec `pollingSub` undefined, donc `isTransitioning=false` → `scanActive=false`. **Le scan est affiché « inactif/terminé », le bouton Start se réactive, badges/progression figés** à l'instantané du reload, pendant que le backend continue. Aucune récupération automatique (reconnexion tentée une seule fois à l'init).

## Cause racine

- `pii-reporting-ui/.../scan-control.service.ts:406` — `hasRunningScan = statuses.some(RUNNING)` ; `:409` — `if (!hasRunningScan || hasPausedScan) return;` (early-return dans la fenêtre inter-space).
- `:402` / `:397` — `forceRefresh()` non-awaité, ne démarre pas le polling ; `pollingSub` undefined ⇒ `isTransitioning=false` (`scan-status-polling.service.ts:115`).

> La moitié PAUSED de la condition ligne 409 est **correcte par design** (un scan en pause ne doit pas se relancer seul). Seule la moitié « fenêtre RUNNING » est défectueuse. Le test `Should_NotReconnect_When_NoRunningSpaces` codifie exactement le comportement qui se déclenche à tort dans la fenêtre de transition.

## Impact / Sévérité — **Basse**

Défaut dépendant du timing (fenêtre de transition, maximale au démarrage et à chaque frontière de space — quelques secondes, minorité du temps de scan). Corrigeable par un reload manuel hors fenêtre.

## Pistes de correction

Ne pas conditionner la reconnexion à un instantané `RUNNING`. Reconnecter dès que `meta?.scanId` existe **et** que le scan n'est ni en pause ni terminal (nombre de spaces COMPLETED/FAILED < `meta.spacesCount`), en laissant la logique `isTransitioning`/`scanCompleted$` du polling stabiliser l'état. Optionnellement, ajouter un statut de scan **niveau scan** au DTO summary pour consulter l'état autoritatif.

## Méthode de détection

Analyse de code + vérification adversariale multi-agents (**3/3 confirmé**).
