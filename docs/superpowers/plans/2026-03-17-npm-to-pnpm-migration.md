# Migration npm vers pnpm — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrer le package manager de `pii-reporting-ui` de npm vers pnpm pour durcir la supply chain contre les phantom dependencies.

**Architecture:** Remplacement direct npm → pnpm strict (pas de shamefully-hoist). Corepack enforce le package manager pour tous les contributeurs. CI et Dockerfile migres en meme temps.

**Tech Stack:** pnpm 10.x, corepack (Node.js natif), GitHub Actions, Docker multi-stage

**Spec:** `docs/superpowers/specs/2026-03-17-npm-to-pnpm-migration-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `pii-reporting-ui/package.json` | Modify | Ajout `packageManager` field |
| `pii-reporting-ui/.npmrc` | Create | Config pnpm (`auto-install-peers=true`) |
| `pii-reporting-ui/.gitignore` | Modify | Ajouter `package-lock.json` |
| `pii-reporting-ui/pnpm-lock.yaml` | Create | Nouveau lockfile (genere par `pnpm import`) |
| `pii-reporting-ui/package-lock.json` | Delete | Remplace par pnpm-lock.yaml |
| `pii-reporting-ui/node_modules/` | Delete+Recreate | Reinstall propre via pnpm |
| `pii-reporting-ui/Dockerfile` | Modify | npm → pnpm, node:20 → node:22-alpine |
| `.github/workflows/run-tests.yml` | Modify | Job test-ui: npm ci → pnpm install --frozen-lockfile |

---

### Task 1: Activer corepack et configurer pnpm

**Files:**
- Modify: `pii-reporting-ui/package.json`
- Create: `pii-reporting-ui/.npmrc`

- [ ] **Step 1: Activer corepack**

```bash
cd pii-reporting-ui
corepack enable
```

Expected: pas de sortie, retour code 0.

- [ ] **Step 2: Ajouter `packageManager` dans `package.json`**

Ajouter le champ `packageManager` a la racine de l'objet JSON, apres `"private": true` :

```json
"packageManager": "pnpm@10.12.1"
```

> Note: la version 10.12.1 est celle definie dans la spec. L'implementeur peut la mettre a jour vers la derniere stable (ex: `npm view pnpm version`) tant qu'elle reste en 10.x.

- [ ] **Step 3: Creer `.npmrc`**

Creer `pii-reporting-ui/.npmrc` :

```ini
# Securite : pas de node_modules flat, chaque package ne voit que ses dependances declarees
# L'isolation stricte (symlinks) est le comportement par defaut de pnpm — rien a activer.
# auto-install-peers evite de devoir declarer manuellement les peer deps transitives.
auto-install-peers=true
```

- [ ] **Step 4: Verifier que corepack bloque npm**

```bash
cd pii-reporting-ui
npm install 2>&1 || true
```

Expected: erreur contenant `This project is configured to use pnpm` ou `Usage Error: This project is configured to use pnpm`.

- [ ] **Step 5: Commit**

```bash
git add pii-reporting-ui/package.json pii-reporting-ui/.npmrc
git commit -m "chore: configure pnpm via corepack with strict isolation"
```

---

### Task 2: Migrer le lockfile et reinstaller les dependances

**Files:**
- Create: `pii-reporting-ui/pnpm-lock.yaml`
- Delete: `pii-reporting-ui/package-lock.json`
- Delete+Recreate: `pii-reporting-ui/node_modules/`

- [ ] **Step 1: Importer le lockfile npm vers pnpm**

```bash
cd pii-reporting-ui
pnpm import
```

Expected: `Lockfile was successfully imported from package-lock.json` (ou similaire). Cree `pnpm-lock.yaml`.

> Note: on garde `node_modules` et `package-lock.json` intacts pendant l'import — `pnpm import` peut en avoir besoin pour resoudre des ambiguites.

- [ ] **Step 2: Supprimer node_modules et reinstaller avec pnpm**

```bash
cd pii-reporting-ui
rm -rf node_modules
pnpm install
```

Expected: install reussie, zero erreur. Si une phantom dependency est detectee, l'ajouter dans `dependencies` ou `devDependencies` de `package.json` et relancer `pnpm install`.

- [ ] **Step 3: Supprimer package-lock.json**

```bash
cd pii-reporting-ui
rm package-lock.json
```

- [ ] **Step 4: Ajouter package-lock.json au .gitignore**

Ajouter dans `pii-reporting-ui/.gitignore`, dans la section `# Node` (apres `yarn-error.log`) :

```
# Ancien lockfile npm (remplace par pnpm-lock.yaml qui DOIT etre versionne)
package-lock.json
```

- [ ] **Step 5: Verifier que les tests passent**

```bash
cd pii-reporting-ui
pnpm test:coverage
```

Expected: tous les tests verts, couverture inchangee.

- [ ] **Step 6: Verifier que le build prod fonctionne**

```bash
cd pii-reporting-ui
pnpm build --configuration production
```

Expected: build reussi, output dans `dist/ai-sentinel-ui-angular/browser/`.

- [ ] **Step 7: Commit**

```bash
git add pii-reporting-ui/pnpm-lock.yaml pii-reporting-ui/.gitignore pii-reporting-ui/package.json
git rm --cached pii-reporting-ui/package-lock.json 2>/dev/null || true
git commit -m "chore: migrate lockfile from npm to pnpm and reinstall dependencies"
```

> Note: `package.json` est inclus car il peut avoir ete modifie si des phantom deps ont ete ajoutees en Step 2.

---

### Task 3: Migrer le Dockerfile

**Files:**
- Modify: `pii-reporting-ui/Dockerfile`

- [ ] **Step 1: Remplacer le contenu du Dockerfile**

Remplacer tout le contenu de `pii-reporting-ui/Dockerfile` par :

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

# Copy built Angular app
COPY --from=build /app/dist/ai-sentinel-ui-angular/browser /usr/share/nginx/html

# Integrate Nginx reverse-proxy configuration into the image
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

- [ ] **Step 2: Verifier le build Docker**

```bash
cd pii-reporting-ui
docker build -t ai-sentinel-ui:pnpm .
```

Expected: image construite sans erreur. Si le build Docker n'est pas possible localement (pas de Docker Desktop), passer cette verification — elle sera couverte par la CI.

- [ ] **Step 3: Commit**

```bash
git add pii-reporting-ui/Dockerfile
git commit -m "chore: migrate Dockerfile from npm to pnpm with node:22-alpine"
```

---

### Task 4: Migrer le workflow CI GitHub Actions

**Files:**
- Modify: `.github/workflows/run-tests.yml`

- [ ] **Step 1: Remplacer le job `test-ui` dans `run-tests.yml`**

Remplacer tout le job `test-ui:` (de `test-ui:` jusqu'a `path: pii-reporting-ui/coverage/vitest/` inclus) par :

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

- [ ] **Step 2: Verifier la syntaxe YAML (optionnel)**

```bash
cd pii-reporting-ui
pnpm dlx yaml-lint ../.github/workflows/run-tests.yml
```

Expected: pas d'erreur (YAML valide). Si `yaml-lint` n'est pas disponible, une verification visuelle suffit — la CI validera la syntaxe au push.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/run-tests.yml
git commit -m "ci: migrate test-ui job from npm to pnpm with corepack and store cache"
```

---

### Task 5: Validation finale

- [ ] **Step 1: Clean install from scratch**

```bash
cd pii-reporting-ui
rm -rf node_modules
pnpm install
```

Expected: install propre, zero warning.

- [ ] **Step 2: Tests unitaires**

```bash
cd pii-reporting-ui
pnpm test:coverage
```

Expected: tous verts.

- [ ] **Step 3: Build production**

```bash
cd pii-reporting-ui
pnpm build --configuration production
```

Expected: build OK.

- [ ] **Step 4: Lint**

```bash
cd pii-reporting-ui
pnpm ng lint
```

Expected: pas de regression (noter: si le lint n'est pas configure, ignorer cette step).

- [ ] **Step 5: Corepack enforcement**

```bash
cd pii-reporting-ui
npm install 2>&1 || true
```

Expected: erreur corepack.

- [ ] **Step 6: Verifier que `build-test-publish-docker-images.yml` n'a pas besoin de modifications**

Le workflow `build-and-publish-ui` utilise `context: ./pii-reporting-ui` et `file: ./pii-reporting-ui/Dockerfile`. Verifier que le context Docker contient bien `.npmrc` et `pnpm-lock.yaml` (ils sont dans `pii-reporting-ui/`, donc oui). Aucune modification du workflow necessaire — il consomme le Dockerfile modifie tel quel.

- [ ] **Step 7: Push et verifier la CI**

```bash
git push origin task/vitest
```

Aller sur GitHub → Actions → verifier que le job `Test Reporting UI (Angular Vitest)` passe.

- [ ] **Step 8 (optionnel): Build Docker**

```bash
cd pii-reporting-ui
docker build -t ai-sentinel-ui:pnpm .
```

Expected: image OK.

- [ ] **Step 9 (optionnel): E2E Playwright**

```bash
cd pii-reporting-ui
pnpm e2e
```

Expected: pas de regression.
