# Opérations : déploiement, secrets, CI/CD, tests

Cette page couvre le déploiement Docker, le bootstrap des secrets, la CI/CD, les tests et
les benchmarks. Point d'entrée : [quickstart.md](quickstart.md).

## Topologie Docker Compose

Trois fichiers compose (images `ghcr.io/softcom-technologies-organization/*`) :

- **`docker-compose.yml`** — stack **prod** (images pull GHCR).
- **`docker-compose.dev.yml`** — stack **dev** : `pii-detector`, `pii-reporting-api`,
  `pii-reporting-ui` sont **buildés localement** (pas d'`image:`), conteneurs suffixés `-dev`.
- **`docker-compose-dgnsi.yml`** — **overlay on-prem** appliqué en second
  (`-f docker-compose.yml -f docker-compose-dgnsi.yml`) : images `:dgnsi` locales
  (`pull_policy: never`, chargées depuis un tar) + injection d'une CA d'entreprise
  (`./certs/corporate-ca.pem`, truststore `./certs/cacerts` PKCS12 mot de passe `changeit`).

Services (stack prod) :

| Service | Container | Rôle | Ports |
|---|---|---|---|
| `secrets-bootstrap` | ai-sentinel-secrets-bootstrap | génère les fichiers de secrets | — |
| `infisical-configurator` | ai-sentinel-infisical-configurator | bootstrap projet/identité/secrets Infisical | — |
| `postgres` | ai-sentinel-db-prod | DB applicative | (non publié en prod) |
| `pii-detector` | pii-detector-service | détecteur gRPC (cache HF monté) | 50051 |
| `pii-reporting-api` | pii-reporting-api | backend | 8080, 8090 |
| `pii-reporting-ui` | pii-reporting-ui | UI (Nginx) | 4200:80 |
| `infisical-db` | infisical-db-prod | DB Infisical | — |
| `infisical-redis` | infisical-redis-prod | cache Infisical | — |
| `mailhog` | mailhog | capture SMTP (Infisical) | — |
| `infisical` | infisical-prod | gestionnaire de secrets (UI) | 8082:8080 |
| `pgadmin` | pgadmin-sentinel | admin DB (profil opt-in) | 5050:80 |

Chaîne de dépendances : `secrets-bootstrap` → `infisical-configurator` →
`postgres`/`infisical` → services applicatifs.

## Démarrage en deux étapes

Le **premier** `up` déclenche le bootstrap Infisical ; il est **normal** de voir des erreurs
sur `pii-reporting-api` tant que les secrets ne sont pas seedés. Puis on force-recreate les
services applicatifs :

```bash
docker compose up -d
docker compose up -d --force-recreate pii-detector pii-reporting-api pii-reporting-ui
```

(Équivalent dev avec `-f docker-compose.dev.yml`.) Une réinstallation complète
(`down -v --rmi all`) re-déclenche le bootstrap → répéter l'étape 2. Détails et dépannage :
`README.md`.

## Gestion des secrets

- **`secrets/`** contient des fichiers `*.txt` de credentials (jamais lus/committés en clair
  idéalement) : passwords Postgres, clés/identités Infisical, `pii_database_encryption_key`
  (la KEK — **perdre cette clé = données indéchiffrables**), credentials pgAdmin.
- **Scripts de bootstrap** (`docker/`) :
  - `docker/secrets-bootstrap-dev.sh` : génère idempotemment les fichiers de secrets depuis
    `/dev/urandom` s'ils manquent (clé chiffrement 16 o hex, auth secret 32 o base64,
    passwords 24 alphanum).
  - `docker/infisical-configurator-dev.sh` : bootstrap Infisical (projet + identité machine
    + seeding).
- `docker/` héberge aussi une stack **Doccano** (annotation) annexe, sans lien avec le
  déploiement applicatif.

> ⚠️ **Régression git à surveiller** : `secrets/.gitignore` et `secrets/README.md` sont
> supprimés (staged `D`) alors que des `*.txt` réels subsistent dans `secrets/`. Le
> `.gitignore` ignorait `*.txt` — sa suppression risque de **dé-ignorer** des secrets. À
> vérifier avant tout commit. Ne jamais committer de valeurs de secret.

## CI/CD (`.github/workflows/`)

- **`ci-tests.yml`** — sur push branches feature + PR vers main/develop ; appelle le
  workflow réutilisable `run-tests.yml`.
- **`run-tests.yml`** — réutilisable ; trois jobs parallèles : **détecteur** (Python 3.12,
  pytest+coverage, génère les protobuf), **api** (JDK 25 Temurin, `mvn clean test -Pci-build`),
  **ui** (Node 22 + pnpm, `pnpm test:coverage` Vitest). Un gate `tests-status` agrège.
- **`build-test-publish-docker-images.yml`** — sur push `main`/`develop`/`release/**` +
  `workflow_dispatch` ; lance les tests puis build/push 3 images vers GHCR. Version dérivée
  de `pyproject.toml`/`pom.xml`/`package.json` (`main`→`<version>`+`latest`,
  `develop`→`<version>`). Publication manuelle : `docs/MANUAL_DOCKER_PUBLISH.md`.
- **Renovate** : `renovate.json` (extends `config:recommended` + `:semanticCommits`, TZ
  Europe/Zurich, majors gatés au Dependency Dashboard, PRs groupées par écosystème).
  Runners locaux on-demand : `renovate-local.sh` / `renovate-local.ps1`.

## Tests par module

| Module | Commande | Framework |
|---|---|---|
| Détecteur | `pytest` (+ `--cov`) | pytest, testcontainers |
| Backend | `mvn test` ; `mvn test -Punit-and-integration-tests` | JUnit 5, Mockito, ArchUnit, Testcontainers |
| Frontend | `pnpm test` / `pnpm e2e` | Vitest, Playwright |

Détails et pièges par module : voir les sections « Développer & tester » de
[detection-service.md](architecture/detection-service.md#développer--tester),
[backend-api.md](architecture/backend-api.md#développer--tester),
[frontend-ui.md](architecture/frontend-ui.md#tests).

## Benchmarks (`benchmarks/`)

- **`benchmarks/pii-dataset-eval/`** — benchmark span-level P/R/F1 : `build_datasets.py`
  télécharge des datasets HF à révision figée (gretelai synthetic-pii-finance, ai4privacy
  pii-masking-300k), normalise via `label_mapping.toml` ; l'IT Java `PiiDetectorBenchmarkIT`
  score PRESIDIO/REGEX isolés + le pipeline fusionné.
- **`benchmarks/pii-chunking-eval/`** — benchmark de stratégie de chunking Ministral
  (doc entière vs token/ligne + overlap, Pareto F1-vs-latence) ; ce dossier ne contient que
  des **artefacts de sortie** (untracked). L'implémentation du chunker est dans
  `pii-detector-service/.../semantic_chunker.py` (voir
  [detection-service.md](architecture/detection-service.md#chunking-découpage-token-based)).

## Documentation existante (pointeurs)

- `docs/ai-sentinel-components.drawio.svg` — diagramme de composants.
- `docs/MANUAL_DOCKER_PUBLISH.md` — publication manuelle d'images.
- `docs/superpowers/specs/` & `plans/` — specs/plans de features (classification PII,
  filtre de types, migration pnpm, scan indépendant par type de source).
- `docs/issue-14/RAPPORT-SPIKE-TASK0.md` — spike de la feature d'auto-obfuscation.
- `LICENSES-SOURCES.md` — licences des datasets/modèles tiers.
- `BACKLOG-SONAR*.md` — backlogs SonarQube par module.
- `lessons.md` — journal de leçons projet.

## Accès utiles (déploiement par défaut)

- UI : http://localhost:4200 — API : http://localhost:8080/ai-sentinel
- Infisical : http://localhost:8082 — pgAdmin : http://localhost:5050 (profil opt-in)
- gRPC détecteur : `localhost:50051` (réflexion activée → testable via `grpcurl`)
