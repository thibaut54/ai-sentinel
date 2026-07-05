# Backlog SonarQube — ai-sentinel-pii-detector-service

> Backlog genere depuis SonarQube (http://localhost:9000), projet **ai-sentinel-pii-detector-service**.
> Genere le 2026-06-10. Perimetre : issues a l'etat **OPEN / CONFIRMED / REOPENED** (issues resolues/fermees exclues).
> Mis a jour le 2026-07-04 : issues sur `openmed_detector.py` et `llm_validator.py` retirees suite a la suppression de GLiNER/GLiNER2/OpenMed/LLM-as-judge (fichiers supprimes) ; compteurs recalcules sur les 3 issues restantes. L'issue `S1845` a ete repointee vers `format_postfilter_validator.py` (renommage `prefilter` -> `postfilter`, meme clash nom/champ). Les lignes de `S3776`/`S5886` n'ont pas ete revalidees contre un nouveau scan Sonar (le refactoring en cours sur ces fichiers a pu deplacer les lignes) — a reconfirmer lors du prochain scan.
> Effort total estime (recalcule a partir des efforts unitaires Sonar d'origine) : **24min**. Total issues : **3** (toutes CODE_SMELL / MAINTAINABILITY).

## Quality Gate : ❌ ERROR

| Condition | Comparateur | Seuil | Valeur | Statut |
|-----------|-------------|-------|--------|--------|
| `new_coverage` | < | 80 | 81.5 | ✅ OK |
| `new_duplicated_lines_density` | > | 3 | 0.07 | ✅ OK |
| `new_security_hotspots_reviewed` | < | 100 | 0.0 | ❌ ERROR |
| `new_violations` | > | 0 | 12 | ❌ ERROR |

> ⚠️ Le gate echouait sur deux conditions lors du scan original : **12 violations** (dont 3 restent applicables apres suppression des fichiers GLiNER/OpenMed/LLM-judge, cf. ci-dessous) et **les security hotspots non revus** (0% revus, objectif 100%). Le detail des hotspots n'a pas pu etre recupere (`Insufficient privileges` sur le token MCP) — a revoir directement dans l'UI SonarQube : _Security Hotspots > To Review_.

## Synthese

### Par severite

| Severite | Nb |
|----------|----|
| 🟥 BLOCKER | 1 |
| 🟧 CRITICAL | 1 |
| 🟨 MAJOR | 1 |

### Par regle

| Regle | Libelle | Sev. | Nb | Effort |
|-------|---------|------|----|--------|
| `python:S1845` | Methode et champ homonymes (clash `name`/`NAME`) | 🟥 BLOCKER | 1 | 10min |
| `python:S3776` | Cognitive Complexity trop elevee | 🟧 CRITICAL | 1 | 9min |
| `python:S5886` | Type de retour incoherent avec le type hint | 🟨 MAJOR | 1 | 5min |

### Par fichier

| Fichier | Nb |
|---------|----|
| `pii_detector/infrastructure/postfilter/format_postfilter_validator.py` | 1 |
| `pii_detector/application/orchestration/composite_detector.py` | 1 |
| `pii_detector/infrastructure/adapter/in/grpc/pii_service.py` | 1 |

---

## Detail des issues (groupees par regle)

### `python:S1845` — Methode et champ homonymes

**Severite max** : 🟥 BLOCKER | **Occurrences** : 1 | **Effort** : 10min

Tags : _convention, confusing_

- [ ] `pii_detector/infrastructure/postfilter/format_postfilter_validator.py:144` (BLOCKER) — Rename method "name" to prevent any misunderstanding/clash with field "SOURCE_NAME" defined on line 139.

### `python:S3776` — Cognitive Complexity trop elevee

**Severite max** : 🟧 CRITICAL | **Occurrences** : 1 | **Effort** : 9min

Tags : _brain-overload_

- [ ] `pii_detector/application/orchestration/composite_detector.py:57` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **19** to the 15 allowed.

### `python:S5886` — Type de retour incoherent avec le type hint

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 5min

Tags : _typing_

- [ ] `pii_detector/infrastructure/adapter/in/grpc/pii_service.py:912` (MAJOR) — Return a value of type "list" instead of "tuple" or update function "_execute_detection" type hint (definition line 851, hint line 859).

---

## Suggestion d'ordre de traitement

1. **Quick win (5min)** — `python:S5886` : ajuster le type de retour ou le type hint de `_execute_detection`.
2. **BLOCKER (10min)** — `python:S1845` : renommer la methode `name` dans `format_postfilter_validator.py` (clash avec le champ `SOURCE_NAME`).
3. **CRITICAL (9min)** — `python:S3776` : extraire une sous-fonction du constructeur de `CompositePIIDetector` pour reduire la complexite cognitive.
4. **Hors issues** — revoir les **security hotspots** dans l'UI Sonar (bloquant pour le gate, non listables via le token MCP actuel).
