# Backlog SonarQube — ai-sentinel-pii-detector-service

> Backlog genere depuis SonarQube (http://localhost:9000), projet **ai-sentinel-pii-detector-service**.
> Genere le 2026-06-10. Perimetre : issues a l'etat **OPEN / CONFIRMED / REOPENED** (issues resolues/fermees exclues).
> Effort total estime par Sonar : **1h53min**. Total issues : **12** (toutes CODE_SMELL / MAINTAINABILITY).

## Quality Gate : ❌ ERROR

| Condition | Comparateur | Seuil | Valeur | Statut |
|-----------|-------------|-------|--------|--------|
| `new_coverage` | < | 80 | 81.5 | ✅ OK |
| `new_duplicated_lines_density` | > | 3 | 0.07 | ✅ OK |
| `new_security_hotspots_reviewed` | < | 100 | 0.0 | ❌ ERROR |
| `new_violations` | > | 0 | 12 | ❌ ERROR |

> ⚠️ Le gate echoue sur deux conditions : **les 12 violations ci-dessous** (objectif 0 sur le nouveau code) et **les security hotspots non revus** (0% revus, objectif 100%). Le detail des hotspots n'a pas pu etre recupere (`Insufficient privileges` sur le token MCP) — a revoir directement dans l'UI SonarQube : _Security Hotspots > To Review_.

## Synthese

### Par severite

| Severite | Nb |
|----------|----|
| 🟥 BLOCKER | 2 |
| 🟧 CRITICAL | 6 |
| 🟨 MAJOR | 2 |
| ⬜ MINOR | 2 |

### Par regle

| Regle | Libelle | Sev. | Nb | Effort |
|-------|---------|------|----|--------|
| `python:S3776` | Cognitive Complexity trop elevee | 🟧 CRITICAL | 6 | 1h06min |
| `python:S1845` | Methode et champ homonymes (clash `name`/`NAME`) | 🟥 BLOCKER | 2 | 20min |
| `python:S107` | Trop de parametres | 🟨 MAJOR | 1 | 20min |
| `python:S5886` | Type de retour incoherent avec le type hint | 🟨 MAJOR | 1 | 5min |
| `python:S5713` | Classe d'exception redondante (deja capturee) | ⬜ MINOR | 2 | 2min |

### Par fichier

| Fichier | Nb |
|---------|----|
| `pii_detector/infrastructure/detector/openmed_detector.py` | 4 |
| `pii_detector/infrastructure/validation/llm_validator.py` | 5 |
| `pii_detector/application/orchestration/composite_detector.py` | 1 |
| `pii_detector/infrastructure/prefilter/format_prefilter_validator.py` | 1 |
| `pii_detector/infrastructure/adapter/in/grpc/pii_service.py` | 1 |

---

## Detail des issues (groupees par regle)

### `python:S1845` — Methode et champ homonymes

**Severite max** : 🟥 BLOCKER | **Occurrences** : 2 | **Effort** : 20min

Tags : _convention, confusing_

- [ ] `pii_detector/infrastructure/prefilter/format_prefilter_validator.py:134` (BLOCKER) — Rename method "name" to prevent any misunderstanding/clash with field "NAME" defined on line 129.
- [ ] `pii_detector/infrastructure/validation/llm_validator.py:610` (BLOCKER) — Rename method "name" to prevent any misunderstanding/clash with field "NAME" defined on line 445.

### `python:S3776` — Cognitive Complexity trop elevee

**Severite max** : 🟧 CRITICAL | **Occurrences** : 6 | **Effort** : 1h06min

Tags : _brain-overload_

- [ ] `pii_detector/infrastructure/detector/openmed_detector.py:297` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **27** to the 15 allowed.
- [ ] `pii_detector/infrastructure/detector/openmed_detector.py:505` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **25** to the 15 allowed.
- [ ] `pii_detector/infrastructure/detector/openmed_detector.py:419` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **21** to the 15 allowed.
- [ ] `pii_detector/infrastructure/detector/openmed_detector.py:343` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **18** to the 15 allowed.
- [ ] `pii_detector/application/orchestration/composite_detector.py:57` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **19** to the 15 allowed.
- [ ] `pii_detector/infrastructure/validation/llm_validator.py:636` (CRITICAL) — Refactor this function to reduce its Cognitive Complexity from **16** to the 15 allowed.

### `python:S107` — Trop de parametres

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 20min

Tags : _brain-overload_

- [ ] `pii_detector/infrastructure/validation/llm_validator.py:448` (MAJOR) — Method "__init__" has 15 parameters, which is greater than the 13 authorized.

### `python:S5886` — Type de retour incoherent avec le type hint

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 5min

Tags : _typing_

- [ ] `pii_detector/infrastructure/adapter/in/grpc/pii_service.py:912` (MAJOR) — Return a value of type "list" instead of "tuple" or update function "_execute_detection" type hint (definition line 851, hint line 859).

### `python:S5713` — Classe d'exception redondante

**Severite max** : ⬜ MINOR | **Occurrences** : 2 | **Effort** : 2min

Tags : _error-handling, bad-practice, unused_

- [ ] `pii_detector/infrastructure/validation/llm_validator.py:842` (MINOR) — Remove this redundant Exception class; it derives from another which is already caught (parent line 841).
- [ ] `pii_detector/infrastructure/validation/llm_validator.py:843` (MINOR) — Remove this redundant Exception class; it derives from another which is already caught (parent line 841).

---

## Suggestion d'ordre de traitement

1. **Quick wins (3min)** — `python:S5713` x2 + `python:S5886` : suppressions/ajustements triviaux.
2. **BLOCKER (20min)** — `python:S1845` x2 : renommer les methodes `name` (clash avec le champ `NAME`).
3. **CRITICAL (1h06min)** — `python:S3776` x6 : extraire des sous-fonctions, surtout `openmed_detector.py` (4 fonctions, complexite jusqu'a 27).
4. **MAJOR (20min)** — `python:S107` : reduire le nombre de parametres de `LlmValidator.__init__` (dataclass de config / regroupement d'arguments).
5. **Hors issues** — revoir les **security hotspots** dans l'UI Sonar (bloquant pour le gate, non listables via le token MCP actuel).
