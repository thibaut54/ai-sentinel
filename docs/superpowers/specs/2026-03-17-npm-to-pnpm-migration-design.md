# Migration npm vers pnpm — Design Spec

**Date:** 2026-03-17
**Statut:** Approuve
**Scope:** `pii-reporting-ui` uniquement (backend Java et detector Python non impactes)

## Contexte et motivation

AI Sentinel est un outil open source destine a etre deploye dans des administrations publiques. Par nature, il s'installe sur des machines disposant de droits admin et d'acces a Confluence, Jira et SharePoint des organisations clientes. Cela en fait une **cible a haute valeur** pour les attaques supply chain.

npm utilise un `node_modules` flat (hoisted) qui permet a n'importe quel package d'acceder a des dependances non declarees (phantom dependencies). Un attaquant pourrait exploiter ce mecanisme pour injecter du code malveillant via une dependance transitive.

**pnpm** utilise un store content-addressable avec des symlinks qui garantissent que chaque package ne peut acceder qu'a ses dependances explicitement declarees. C'est une **protection architecturale** contre les phantom dependencies.

## Decisions de design

### Approche choisie : Migration directe pnpm strict

Migration complete en une passe — pas de phase intermediaire avec `shamefully-hoist`. Le mode strict de pnpm est active des le depart.

**Alternatives rejetees :**
- **Migration progressive (shamefully-hoist)** : Rejetee car n'apporte aucun gain de securite tant que le hoist est actif, et risque d'oublier la phase 2.
- **npm avec audit renforce** : Rejetee car ne resout pas le probleme structurel des phantom dependencies.

### Enforcement contributeurs : Corepack strict

Le champ `packageManager` dans `package.json` + corepack activee bloquent npm/yarn. Un contributeur qui tente `npm install` obtient une erreur. C'est necessaire pour un projet open source ou n'importe qui peut soumettre des PR.

## Specification technique

### 1. Fichiers de configuration pnpm

#### `pii-reporting-ui/package.json` — Ajout du champ `packageManager`

```json
"packageManager": "pnpm@10.12.1"
```

Version pinnee (pas de range) pour la reproductibilite.

#### `pii-reporting-ui/.npmrc` — Configuration pnpm stricte (nouveau)

```ini
# Securite : pas de node_modules flat, chaque package ne voit que ses dependances declarees
# L'isolation stricte (symlinks) est le comportement par defaut de pnpm — rien a activer.
# auto-install-peers evite de devoir declarer manuellement les peer deps transitives.
auto-install-peers=true
```

- L'isolation stricte des `node_modules` (pas de phantom deps) est le **comportement par defaut** de pnpm — pas besoin de `strict-peer-dependencies` qui est orthogonal (resolution de versions, pas isolation) et risque de bloquer l'install avec les peer dep ranges parfois mal specifies de l'ecosysteme Angular/PrimeNG.
- `auto-install-peers=true` : installe automatiquement les peer deps manquantes.
- Le blocage de npm/yarn est assure par corepack + `packageManager`, pas par `.npmrc`.

#### `pii-reporting-ui/pnpm-lock.yaml` — Nouveau lockfile

Genere par `pnpm import` (conversion depuis `package-lock.json`) puis `pnpm install`.

#### `pii-reporting-ui/package-lock.json` — Supprime

Remplace par `pnpm-lock.yaml`.

#### `pii-reporting-ui/.gitignore` — Ajout

Ajouter `package-lock.json` pour empecher qu'un contributeur le recommit par accident.

### 2. CI GitHub Actions

#### `.github/workflows/run-tests.yml` — Job `test-ui`

```yaml
test-ui:
  name: Test Reporting UI (Angular Vitest)
  runs-on: ubuntu-latest
  steps:
    - name: Checkout repository
      uses: actions/checkout@v4

    - name: Set up Node.js with corepack
      uses: actions/setup-node@v4
      with:
        node-version: '22.x'

    - name: Enable corepack and install pnpm
      run: |
        corepack enable
        corepack install
      working-directory: pii-reporting-ui

    - name: Get pnpm store directory
      id: pnpm-store
      run: echo "path=$(pnpm store path)" >> $GITHUB_OUTPUT
      working-directory: pii-reporting-ui

    - name: Cache pnpm store
      uses: actions/cache@v4
      with:
        path: ${{ steps.pnpm-store.outputs.path }}
        key: pnpm-store-${{ hashFiles('pii-reporting-ui/pnpm-lock.yaml') }}
        restore-keys: pnpm-store-

    - name: Install dependencies
      run: pnpm install --frozen-lockfile
      working-directory: pii-reporting-ui

    - name: Run Vitest tests with coverage
      run: pnpm test:coverage
      working-directory: pii-reporting-ui

    - name: Upload coverage reports
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: ui-test-coverage
        path: pii-reporting-ui/coverage/vitest/
```

**Points cles :**
- Pas d'action tierce `pnpm/action-setup` — on utilise corepack natif de Node.js
- `--frozen-lockfile` = equivalent de `npm ci` (lockfile immutable, fail si desynchronise)
- Cache du pnpm store via `actions/cache@v4` (pas `cache: 'npm'` de setup-node)
- Chaque branche execute sa propre version du workflow — pas de coexistence npm/pnpm

### 3. Dockerfile

```dockerfile
# syntax=docker/dockerfile:1

FROM node:22-alpine AS build

# Activer corepack pour utiliser pnpm (lu depuis packageManager dans package.json)
RUN corepack enable

WORKDIR /app

# Copier d'abord les fichiers de dependances pour maximiser le cache Docker
COPY package.json pnpm-lock.yaml .npmrc ./
RUN corepack install
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build --configuration production

FROM nginx:alpine AS runtime

COPY --from=build /app/dist/ai-sentinel-ui-angular/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**Changements vs l'ancien :**
- `node:20` -> `node:22-alpine` (aligne avec la CI, image plus legere)
- `npm ci --legacy-peer-deps` -> `pnpm install --frozen-lockfile` (strict, pas de legacy-peer-deps)
- `corepack enable` + `corepack install` pour pre-telecharger la version exacte de pnpm avant l'install
- Copie `pnpm-lock.yaml` + `.npmrc` au lieu de `package*.json`

### 4. Audit des phantom dependencies

Apres `pnpm import`, `pnpm install` en mode strict peut echouer si le code importe des packages non declares dans `package.json`. Resolution :
1. Identifier l'import fautif
2. Ajouter explicitement dans `dependencies`/`devDependencies`
3. Ou remplacer par l'API de la dependance directe qui l'expose

Risque faible : ~15 dependances directes dans le projet.

## Fichiers impactes

| Fichier | Action |
|---------|--------|
| `pii-reporting-ui/package.json` | Ajout `packageManager` |
| `pii-reporting-ui/.npmrc` | Nouveau (config pnpm strict) |
| `pii-reporting-ui/pnpm-lock.yaml` | Nouveau (remplace package-lock.json) |
| `pii-reporting-ui/package-lock.json` | Supprime |
| `pii-reporting-ui/Dockerfile` | Migre npm -> pnpm + node:22-alpine |
| `pii-reporting-ui/.gitignore` | Ajout `package-lock.json` |
| `.github/workflows/run-tests.yml` | Job test-ui migre pnpm + cache |
| `.github/workflows/build-test-publish-docker-images.yml` | Impacte indirectement (consomme le Dockerfile modifie) — verifier le build Docker UI |

## Criteres de validation

| Check | Commande | Critere |
|-------|----------|---------|
| Install locale propre | `pnpm install` | Zero warning, zero phantom dep |
| Tests Vitest | `pnpm test:coverage` | Tous verts, couverture inchangee |
| Build production | `pnpm build --configuration production` | Build OK, taille bundle ~identique |
| Lint | `pnpm ng lint` | Pas de regression |
| Build Docker | `docker build -t ai-sentinel-ui:pnpm .` | Image construite sans erreur |
| Corepack bloque npm | `npm install` (dans pii-reporting-ui/) | Doit echouer avec erreur corepack |
| CI green | Push branche -> GitHub Actions | Job test-ui passe |
| E2E Playwright | `pnpm e2e` | Pas de regression |

## Notes operationnelles

- Le champ `engines` existant (`>=20.19 <21 || >=22.12`) est deja compatible avec Node 22 — pas de modification necessaire.
- Apres la migration, l'ancien cache npm dans GitHub Actions peut etre purge via `gh actions-cache delete` ou laisse a expirer naturellement (7 jours pour les branches non-default).

## Hors scope

- Backend Java (`pii-reporting-api`) : Maven, non impacte
- Detector Python (`pii-detector-service`) : pip, non impacte
- Passage a un monorepo pnpm workspaces : pas necessaire (un seul projet frontend)
