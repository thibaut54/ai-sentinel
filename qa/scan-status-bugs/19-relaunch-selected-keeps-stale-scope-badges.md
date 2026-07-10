# [Bug][Dashboard] Relance d'un scan « sélection » sur un autre sous-ensemble : un space en file jamais démarré reste bloqué sur « En attente »

> **Issue GitHub** : [thibaut54/ai-sentinel#19](https://github.com/thibaut54/ai-sentinel/issues/19) · **Label** : `bug` · **Sévérité** : Moyenne · **Confiance** : 2/3 (périmètre restreint) · **Date** : 2026-07-08

## Contexte

« En attente » (PENDING) doit signifier « réellement en file d'attente ». Les checkpoints sont créés **paresseusement** à partir des événements de scan (`item`/`attachmentItem`/`pageComplete`→RUNNING, `complete`→COMPLETED) : un space **mis en file mais jamais démarré n'a aucun checkpoint** et est donc absent du summary backend.

> **Périmètre restreint (important).** Ce ticket ne concerne **que** le cas d'un space *en file jamais démarré*. Le cas d'un space réellement **« En pause » (PAUSED avec checkpoint)** qui reste affiché « En pause » après relance **n'est PAS un bug** : c'est l'état backend réel (dernier checkpoint par space), un comportement délibéré et couvert par un test (`Should_OnlyDeriveStateFromInScopeSpaces`). Seule la moitié « En attente » ci-dessous est défectueuse.

## Scénario de reproduction

1. Depuis un dashboard au repos où le space **B** n'a jamais été scanné (aucun checkpoint), lancer un scan **sélection** de {A, B} de sorte que A démarre en premier et **B affiche « En attente »**.
2. **Pause** du scan (`pauseScan`). B reste « En attente » (la pause ne touche que les checkpoints RUNNING ; B n'en a toujours pas).
3. Changer la sélection en **{C}** uniquement.
4. Relancer `startSelected(['C'])` et confirmer (autorisé : `canStartScan` n'exige que `!scanActive`, et en pause `scanActive=false`).

## Comportement attendu

B (hors du scan {C}) doit retomber sur **« Non démarré »** / son statut autoritatif. Un badge « En attente » ne doit jamais désigner un space qui n'est dans aucun scan actif.

## Comportement observé

B reste bloqué sur **« En attente »** pendant tout le scan {C} (et après), alors qu'il n'appartient à aucun scan actif et ne sera jamais scanné.

## Cause racine

- `resetDashboardForNewScan(['C'])` n'itère que les nouvelles clés `['C']` et ne réinitialise pas les spaces de l'ancien scope — `pii-reporting-ui/.../scan-control.service.ts:238`-`:239`.
- B est **absent du summary** (aucun checkpoint créé), donc `applySpaceUiState` ne tourne jamais pour lui (parcourt uniquement les spaces du summary).
- `markUnreportedSpacesAsPending` est **borné au scope** {C} — `pii-reporting-ui/.../scan-status-polling.service.ts:242` — donc ne réconcilie pas B.

> Correction du diagnostic initial : `scan-status-polling.service.ts:206` **n'est pas** en cause (il ne saute que le statut `RUNNING` hors-scope et applique tous les autres). Le vrai mécanisme est l'**absence de B du summary** + le garde de scope ligne 242.

## Impact / Sévérité — **Moyenne**

Badge de statut trompeur pour un space orphelin ; l'invariant « En attente = en file » est localement violé. Pas de perte de données.

## Pistes de correction

Dans la branche « sélection » de `resetDashboardForNewScan` (`:239`), réconcilier aussi les spaces **hors** de la nouvelle sélection : remettre à `NOT_STARTED` (ou dernier statut autoritatif) tout space actuellement en « En attente » optimiste et hors du nouveau scope. **Ne pas** effacer les spaces PAUSED hors-scope (comportement voulu/testé).

## Méthode de détection

Analyse de code + vérification adversariale multi-agents (**2/3 confirmé**, périmètre restreint à la moitié « En attente » comme détaillé ci-dessus).
