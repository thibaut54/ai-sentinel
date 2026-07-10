# [Bug][Scan] Reconnexion à un scan en cours : relance concurrente via l'endpoint resume → double comptage des compteurs de sévérité PII

> **Issue GitHub** : [thibaut54/ai-sentinel#17](https://github.com/thibaut54/ai-sentinel/issues/17) · **Label** : `bug` · **Sévérité** : Haute · **Confiance** : 3/3 · **Date** : 2026-07-08

## Contexte

Un scan multi-space survit à la déconnexion SSE : `ScanTaskManagerAdapter.startScan` s'abonne de façon **indépendante** (`subscribeOn(boundedElastic).subscribe()`) et continue même si le navigateur ferme l'`EventSource`. À la reconnexion, le frontend devrait se **rattacher** au flux existant (`subscribeScan`), pas relancer du travail.

## Scénario de reproduction

1. Lancer un scan **global** (bouton Start) sur une base Confluence de plusieurs spaces/pages (assez long).
2. Pendant que le scan est **RUNNING** (au moins un space RUNNING, aucun PAUSED, plusieurs encore à faire), **recharger** la page (F5).
3. À l'init, `reconnectIfScanRunning()` passe le garde et appelle `startAllSpacesStream(meta.scanId)` → `GET /api/v1/stream/confluence/spaces/events?scanId=X`.

## Comportement attendu

La reconnexion se rattache au *sink* de rejeu du scan de fond existant (`subscribeScan`) : les événements re-streament vers le client **sans lancer de nouveau travail**.

## Comportement observé

Le contrôleur traite tout `scanId` non vide comme un **resume** et exécute `resumeAllSpaces(scanId)` — un **second pipeline réactif complet**, pendant que le scan d'origine (abonnement indépendant) tourne toujours. Les deux pipelines émettent des événements `item` pour les mêmes pages. Les compteurs de sévérité étant **additifs et non idempotents** (`incrementCounts`, SQL `... SET nb_of_high_severity = nb_of_high_severity + :deltaHigh`), les pages qui se chevauchent sont **comptées deux fois** → le dashboard affiche des compteurs PII **gonflés** (jusqu'à ~×2 sur les spaces pas encore démarrés au reload), et ces valeurs **persistent en base** après complétion.

## Cause racine

- Aucun endpoint n'appelle `ScanTaskManagerAdapter.subscribeScan()` pour se rattacher à un scan vivant — `pii-reporting-api/.../ScanTaskManagerAdapter.java:123` (câblé uniquement pour les scans frais).
- L'unique endpoint paramétré par `scanId` route tout resume vers un nouveau pipeline — `pii-reporting-api/.../ConfluencePersonallyIdentifiableInformationScanController.java:100` ; `.../StreamConfluenceResumeScanUseCase.java:39` (`resumeAllSpaces` ne consulte jamais `managedScans`).
- Déclencheur frontend — `pii-reporting-ui/.../scan-control.service.ts:417` (`reconnectIfScanRunning` appelle `startAllSpacesStream(meta.scanId)`).
- Compteurs additifs — `ScanSeverityCountJpaRepository.incrementCounts` (UPSERT `+ delta`).

Un commentaire existant dans `AbstractStreamConfluenceScanUseCase` (« severity counts doubled on refresh ») ne couvrait que l'ancien modèle (scan stoppé à la déconnexion, repris séquentiellement) — pas le modèle actuel où le scan reste vivant.

## Impact / Sévérité — **Haute**

Défaut d'intégrité de données à surface UI directe : compteurs PII par space faux, déclenché par une action banale (rafraîchir la page pendant un scan actif).

## Pistes de correction

- À la reconnexion d'un scan encore vivant, **se rattacher au *sink* existant** via `subscribeScan(scanId)` (rejeu du buffer) ; ne tomber sur `resumeAllSpaces` que si **aucun** `ManagedScan` actif n'existe pour ce `scanId` (vraie reprise après pause/redémarrage).
- Alternativement : avant de lancer un resume, disposer/refuser si un `ManagedScan` actif existe déjà, **ou** rendre `incrementCounts` idempotent par page (delta clé par `pageId`).

## Méthode de détection

Analyse de code inter-couches + vérification adversariale multi-agents (**3/3 confirmé**).
