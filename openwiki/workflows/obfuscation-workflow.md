# Workflow : remédiation par obfuscation (caviardage)

Fonctionnalité qui permet de **masquer** les valeurs PII directement dans les pages
Confluence. Un reviewer sélectionne des findings, prévisualise un **plan**, puis lance un
**job** asynchrone qui réécrit le stockage XHTML des pages. C'est la feature majeure de la
branche `feature/issue-14-auto-obfuscation-pii`.

Contexte backend : `application/pii/remediation` + `infrastructure/pii/remediation`.
Contexte UI : `features/pii-obfuscation/`. Voir aussi
[backend-api.md](../architecture/backend-api.md) et
[frontend-ui.md](../architecture/frontend-ui.md).

## Points d'entrée REST

Controller : `PiiRemediationController` — base `/api/v1/pii/remediation` :

| Endpoint | Rôle |
|---|---|
| `GET /config` | seul endpoint joignable si la feature est désactivée |
| `POST /findings/search` | findings groupés, paginés serveur + sélection résolue |
| `POST /findings/status` / `POST /findings/status/by-selection` | transitions de cycle de vie |
| `POST /plan` | prévisualisation read-only d'obfuscation |
| `POST /jobs` | soumission d'un job asynchrone de caviardage |
| `GET /jobs/{id}` | statut + issues (outcomes) du job |

Toute la surface (sauf `/config`) est gardée par le flag `pii.remediation.enabled`.

## Identité d'un finding (concept clé)

`domain/pii/remediation/FindingReference.findingId()` = SHA-256 hex de
`(spaceKey, pageId, attachmentName, detector, piiType, valueFingerprint)`.

Il **exclut délibérément** `scanId`, les offsets et la sévérité : ainsi le statut d'un
finding et le feedback « faux positif » **survivent aux re-scans** et aux recalibrages de
sévérité. Le *fingerprint de valeur* est le discriminant.

> Conséquence : le **reporting compte des occurrences**, la **remédiation déduplique par
> valeur distincte** — une source connue d'écart de comptage. La pagination d'obfuscation
> est par groupe (`totalGroups`), jamais à plat.

## Modèle de lecture (findings)

`QueryRemediationFindingsUseCase` résout les derniers événements de scan en
`EligibleFinding` (`ScanEventFindingResolver`), joint la projection de remédiation, et
calcule **côté serveur** les groupes, les master-checkboxes tri-état, la pagination et les
totaux. La lecture se fait en **déchiffré** (gardée par `allow-secret-reveal`) pour que le
reviewer voie le clair et juge des faux positifs ; chaque vue d'espace est auditée.

## Plan → Exécution → Suivi

1. **Plan** — `PlanObfuscationUseCase` produit un `ObfuscationPlan` avec un
   `checksumOf(findingIds)`. Le DTO expose total, `bySeverity`, `pagesImpacted`,
   `falsePositivesReported`, `selectionChecksum`, `attachmentExclusions`.

2. **Exécution** — `ExecuteObfuscationUseCase` :
   - re-résout la sélection et **rejette sur dérive de checksum** (`SelectionOutdatedException`
     → HTTP 409, l'UI re-planifie),
   - rejette le scope pièce jointe (`AttachmentRedactionUnsupportedException` — les
     attachements ne sont **pas** caviardables automatiquement),
   - impose **un seul job actif par espace** (pré-check + index unique partiel
     `uq_pii_redaction_job_running_per_space` de l'init-script `014`),
   - gèle les IDs de findings résolus dans la ligne de job et lance en asynchrone via un
     `Executor` injecté.

3. **Runner** — `ObfuscationJobRunner`, par page :
   - déchiffre les valeurs PII via le chemin audité `AccessPurpose.REDACTION`,
   - apparie `(piiType | valueFingerprint)` → texte en clair,
   - délègue à `SourcePageRedactionPort`,
   - enregistre **un outcome par finding** : `REDACTED`, `SKIPPED_STALE`,
     `SKIPPED_VALUE_NOT_FOUND`, `SKIPPED_ATTACHMENT`, `FAILED`.
   - Une page en échec **n'interrompt pas** le job → statut final `COMPLETED_WITH_ERRORS`.
   - Ne journalise/persiste **jamais** le texte en clair.

4. **Suivi** — statuts de job : `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`,
   `INTERRUPTED`, `FAILED`. `ObfuscationJobBootRecovery` marque les jobs `RUNNING`
   `INTERRUPTED` au démarrage (crash-recovery). L'UI poll `/jobs/{id}` toutes les 1500 ms.

## Réécriture du stockage XHTML

- **Adapter d'écriture Confluence** : `ConfluencePageRedactionAdapter` (impl
  `SourcePageRedactionPort`) lit le corps + version du stockage, délègue la réécriture, puis
  ré-écrit avec `version.number = courant+1` ; un retry sur HTTP 409 puis report `STALE`.

  > Piège documenté : le champ `version.when` provoquait un 400 (« too short ») — voir la
  > mémoire projet `confluence-obfuscation-put-debug`.

- **Réécriture** : `StorageContentRedactor` (jsoup, parseur XML) matche sur une
  **concaténation normalisée** des nœuds texte (entités décodées, espaces collabsés, NFC),
  puis reprojette le token sur les nœuds d'origine. Il gère les valeurs éclatées à travers
  du formatage inline / des cellules de table ; traite la **valeur la plus longue d'abord**
  pour éviter l'ombrage par sous-chaîne ; respecte les frontières de blocs ; ignore les
  macros `ac:parameter` / `ac:image` / `ac:emoticon`. Helper : `NormalizedText`.

  > La remédiation est **par valeur**, pas par offset : les offsets du détecteur portent sur
  > le texte extrait et ne mappent pas le markup brut — d'où le re-matching normalisé. Un
  > large corpus de cas est dans `pii-reporting-api/src/test/resources/storage-redaction-corpus/`.

## Cycle de vie & persistance

Statuts de finding : `PENDING` (implicite, pas de ligne) / `REDACTED` (terminal) /
`MANUALLY_HANDLED` / `FALSE_POSITIVE`, via `ChangeFindingStatusUseCase`. Le
mark-treated de masse porte sur **toute la sélection** (`changeFindingsStatusBySelection`),
pas seulement la page affichée. Adapters : `JpaFindingRemediationAdapter`,
`JpaObfuscationJobAdapter` (tables `pii_finding_remediation`, `pii_redaction_job`).

## Côté UI (résumé)

Route `/obfuscation` (query `spaceKey`, optionnel `pageId`/`attachmentName`,
`preselect=true`). Conteneur `PiiObfuscationComponent` (OnPush) + deux services scopés
(`ObfuscationSelectionService`, `ObfuscationViewStateService`). **Invariant** : le backend
possède toute l'agrégation ; l'UI ne dérive rien. Concurrence optimiste par
`selectionChecksum` (409 → re-plan). Détails :
[frontend-ui.md](../architecture/frontend-ui.md#obfuscation-featurespii-obfuscation).

## À surveiller lors d'une modification

- Ne pas inclure scanId/offsets/sévérité dans `findingId()` (casserait la survie du statut).
- Toujours passer par le chemin audité pour déchiffrer ; ne jamais logger le clair.
- Respecter le fail-safe « une page en échec ≠ job échoué ».
- Réécriture XHTML : préserver la sérialisation XML et l'ordre longest-value-first
  (régressions couvertes par `StorageContentRedactorTest` + le corpus).
- Garder l'invariant backend-autoritaire côté UI.

Tests pertinents : `ObfuscationJobRunnerTest`, `ExecuteObfuscationUseCaseTest`,
`PlanObfuscationUseCaseTest`, `StorageContentRedactorTest`, `ConfluencePageRedactionAdapterTest`,
`JpaObfuscationJobAdapterTest` ; E2E `pii-reporting-ui/e2e/obfuscation.spec.ts`.
