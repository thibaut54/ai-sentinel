# Frontend UI (`pii-reporting-ui/`)

Interface **Angular 21** entièrement **standalone + signals** (aucun NgModule), avec
**PrimeNG 21** (thème Aura + preset custom `SentinelPreset`) et **Transloco** pour l'i18n.
Gestionnaire de paquets : **pnpm** (imposé). Sources : `pii-reporting-ui/`.

> ⚠️ PrimeNG, **pas** Foehn/prestations-ng (contrairement aux instructions d'agent globales).
> Le `README.md` du module est périmé (annonce Angular CLI 20.2.2 + Karma).

## Stack & outillage

- **UI kit** : PrimeNG 21, `@primeuix/themes` (Aura), primeflex, primeicons ;
  variables CSS globales dans `src/styles/_variables.css` ; preset custom dans
  `src/app/app.config.ts`.
- **i18n** : Transloco 8, langue par défaut/fallback `fr`, langues `['fr','en']`.
- **Changement de détection** : zone.js (`provideZoneChangeDetection({eventCoalescing:true})`)
  — pas zoneless.
- **Tests unitaires** : Vitest 4 (builder `@angular/build:unit-test`, jsdom) — seuils de
  couverture bas (fonctions 25 %). **E2E** : Playwright 1.56 (chromium/firefox/webkit,
  `baseURL :4200`, `webServer: pnpm start`).
- Dev serve : proxy `/api` → `http://localhost:8080` en réécrivant `^/api` → `/ai-sentinel/api`
  (`proxy.conf.json`).

Commandes : `pnpm start` (:4200), `pnpm build`, `pnpm test` / `test:watch` /
`test:coverage`, `pnpm e2e` / `e2e:headed` / `e2e:ui`.

## Structure de l'application

Routing (`src/app/app.routes.ts`) — 3 routes eager, pas de lazy loading :
`''` → `AppShellComponent`, `settings` → `PiiSettingsComponent`,
`obfuscation` → `PiiObfuscationComponent`, `**` → redirect `''`.

`app.config.ts` enregistre 3 `provideAppInitializer` qui préchargent des flags avant le
rendu : config de polling, config reveal, config remediation.

```
src/app/
├── core/
│   ├── models/         # Space, Severity, DetectedPersonallyIdentifiableInformation,
│   │                   # StreamEvent, remediation.model.ts, pii-detection-config.model.ts…
│   ├── services/       # API + polling + theme/toast/reveal/expansion
│   └── interceptors/   # error.interceptor.ts
├── shared/             # confidence-indicator, detector-tag, scan-progress-bar, banners…
└── features/
    ├── app-shell/ app-header/
    ├── confluence-dashboard/   (+ services/, components/)
    ├── confluence-settings/ pii-settings/
    ├── pii-page-card/ pii-item-card/ severity-cards/ space-scan-detail/
    ├── pii-obfuscation/        (feature majeure récente)
    └── test-ids.constants.ts
```

`AppShellComponent` héberge le header, le dashboard Confluence et un **dialogue** de
Settings (le `PiiSettingsComponent` est réutilisé en mode dialogue via `input(dialogMode)`).

## Features clés

### Dashboard / reporting (`features/confluence-dashboard/`)
`ConfluenceDashboardComponent` (OnPush) est un conteneur mince qui délègue à des services
spécialisés (`SpaceFilteringService`, `DashboardUiStateService`, `PiiItemsStorageService`,
`SpaceDataManagementService`, `ScanControlService`). Il affiche une table PrimeNG des
espaces (statut, barre de progression, badges de sévérité total/high/medium/low, score de
risque, actions start/pause/resume/purge). Les mises à jour arrivent en **SSE**
(`SentinelleApiService.startAllSpacesStream` / `startSelectedSpacesStream`, types
`multiStart/start/pageStart/item/attachmentItem/pageComplete/scanError/complete/multiComplete/keepalive`,
parsés dans `NgZone.run`). Sévérité 3 niveaux dans
`features/pii-page-card/severity.config.ts`.

Flux « reveal » : `revealPageSecrets(scanId, pageId)` → `/api/v1/pii/reveal-page` renvoie
des `RevealedSecret[]`, conditionné par le signal `revealAllowed`
(`/api/v1/pii/config/reveal-allowed`) — déclenche un audit backend.

### Obfuscation (`features/pii-obfuscation/`)
Voir le workflow complet dans [obfuscation-workflow](../workflows/obfuscation-workflow.md).
Points d'architecture UI :
- **Règle centrale (documentée dans le code)** : le backend possède **toute** l'agrégation ;
  l'UI ne dérive jamais les comptes/master-states/plan. Tout vient verbatim des réponses.
- Deux services **scopés au composant** (`providers:` du conteneur, pas root) :
  `ObfuscationSelectionService` (gestes de sélection bruts : types/sévérités cochés,
  `excludedFindingIds`/`includedFindingIds` en `ReadonlySet` de signaux) et
  `ObfuscationViewStateService` (état UI pur : `groupBy`, accordéons, pagination, filtres).
- **Pagination par GROUPE** (un groupe n'est jamais scindé entre pages ; pager sur
  `totalGroups`).
- **Concurrence optimiste** via `selectionChecksum` : un 409 déclenche un re-plan.
- **Job progress** : `ObfuscationJobProgressComponent` poll `/jobs/{id}` toutes les
  **1500 ms** (`timer` + `switchMap` + `takeWhile`).

### Settings / PII settings (`features/pii-settings/pii-settings.component.ts`)
Composant à formulaires réactifs, standalone (`/settings`) ou en dialogue. Quatre sections :
`detectors`, `thresholds`, `pii_types`, `confluence`.
- Toggles détecteurs : `presidioEnabled`, `regexEnabled`, `postfilterEnabled`,
  `ministralEnabled` (validateur « au moins un détecteur »).
- Seuils : `defaultThreshold` (0-1), Ministral `chunkSize` (256-4096, défaut 2048) et
  `overlap` (0-512, défaut 410 ; validateur `overlap < chunkSize`).
- Labels custom : ajout/suppression de types PII (code `PII_TYPE` généré depuis un libellé,
  toujours attaché au détecteur `MINISTRAL`).

## Intégration API (`core/services/`)

Tous les appels visent des chemins relatifs `/api/v1/...` (proxy en dev). **Pas** de
constante de base-URL / fichier d'environnement — les chemins sont codés par méthode.

- `sentinelle-api.service.ts` — scans/espaces/SSE/reveal.
- `remediation-api.service.ts` — client HTTP pur pour `/api/v1/pii/remediation` (docstring :
  « aucune logique métier ; toute l'agrégation est au backend »).
- `remediation-config.service.ts` + `loadRevealConfig` — façades de feature flags (signaux
  `enabled` / `revealAllowed`), chargées à l'init, **fail-closed** (false en cas d'erreur).
- `pii-detection-config.service.ts` — CRUD config détecteurs/types (Settings).
- `error.interceptor.ts` + toast — gestion globale des erreurs HTTP.
- Modèles dans `core/models/remediation.model.ts` : miroir exact du contrat REST (le
  `sensitiveValue` en clair ne transite que si feature + `allow-secret-reveal` sont actifs).

## i18n

Bilingue : `src/assets/i18n/en.json` et `fr.json` (~1245 lignes chacun), chargés à la volée.
Défaut/fallback = **fr**. Clés top-level : `common, language, severity, dashboard,
confirmations, piiHelp, settings, piiPageCard, piiItem, spaceScanDetail, errors, obfuscation,
piiTypes`.

## Tests

- ~38 specs Vitest co-localisés (services, utils, composants).
- 6 specs Playwright dans `e2e/` : `dashboard`, `scan-confirmation`, `scan-expand-items`,
  `scan-pause`, `obfuscation` (+ `helpers/dialog-selectors.ts`). La spec obfuscation exige
  une stack seedée avec le flag remediation actif (voir le mémo E2E : un « vert » peut être
  un artefact d'environnement).
- **`src/app/features/test-ids.constants.ts`** : arbre `TestIds` unique consommé par les
  composants **et** les specs Playwright (contrat anti-typo).

## À surveiller lors d'une modification

- Ne pas dériver de comptes/plans côté client dans l'obfuscation (viole l'invariant
  backend-autoritaire).
- Le parsing SSE est enveloppé dans `NgZone.run` (piège si migration zoneless).
- Feature flags fail-closed : l'entrée obfuscation et le reveal restent cachés tant que le
  backend ne confirme pas.
- Pas de script `lint` dans `package.json` ; un skill dépôt `lint-fix` invoque `ng lint`.
