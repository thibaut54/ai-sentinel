# Backlog SonarQube — ai-sentinel-pii-reporting-api

> Backlog genere depuis SonarQube (http://localhost:9000), projet **ai-sentinel-pii-reporting-api**.
> Genere le 2026-06-10. Perimetre : issues a l'etat **OPEN / CONFIRMED / REOPENED** (issues resolues/fermees exclues).
> Effort total estime par Sonar : **6h55min**. Total issues : **41**.
> Mis a jour le 2026-07-04 : les issues pointant sur des tests supprimes (CorpusBenchmarkIT, CorpusDataSqlComparisonIT, PiiDetectorBenchmarkIT, JudgeDiscardedEntitiesSmokeIT, integration/bench/*, CorpusGliner2*, LlmJudge*) ont ete retirees suite a la suppression de GLiNER/GLiNER2/OpenMed/LLM-as-judge. Les compteurs par section sont recalcules ; la synthese globale reste a rafraichir au prochain scan Sonar.

## Synthese

### Par severite

| Severite | Nb |
|----------|----|
| 🟥 BLOCKER | 4 |
| 🟧 CRITICAL | 1 |
| 🟨 MAJOR | 17 |
| ⬜ MINOR | 19 |

### Par type

| Type | Nb |
|------|----|
| CODE_SMELL | 56 |
| BUG | 5 |

### Par scope

| Scope | Nb |
|-------|----|
| TEST | 46 |
| MAIN | 15 |

### Par regle

| Regle | Libelle | Sev. | Nb | Effort |
|-------|---------|------|----|--------|
| `java:S2699` | Tests should include assertions | 🟥 BLOCKER | 4 | 40min |
| `java:S6809` | Methods with Spring proxy should not be called via "this" | 🟧 CRITICAL | 1 | 5min |
| `java:S6213` | Restricted Identifiers should not be used as Identifiers | 🟨 MAJOR | 7 | 35min |
| `java:S2925` | "Thread.sleep" should not be used in tests | 🟨 MAJOR | 3 | 1h00min |
| `java:S1068` | Unused "private" fields should be removed | 🟨 MAJOR | 2 | 10min |
| `java:S107` | Methods should not have too many parameters | 🟨 MAJOR | 2 | 40min |
| `java:S125` | Sections of code should not be commented out | 🟨 MAJOR | 2 | 10min |
| `java:S3457` | Format strings should be used correctly | 🟨 MAJOR | 1 | 1min |
| `java:S1874` | "@Deprecated" code should not be used | ⬜ MINOR | 8 | 2h00min |
| `java:S1128` | Unnecessary imports should be removed | ⬜ MINOR | 1 | 1min |
| `java:S7467` | Unused exception parameter should use the unnamed variable pattern | ⬜ MINOR | 4 | 8min |
| `java:S3077` | Non-primitive fields should not be "volatile" | ⬜ MINOR | 2 | 40min |
| `java:S5841` | AssertJ assertions "allMatch" and "doesNotContain" should also test for emptiness | ⬜ MINOR | 2 | 10min |
| `java:S135` | Loops should not contain more than a single "break" or "continue" statement | ⬜ MINOR | 1 | 20min |
| `java:S899` | Return values should not be ignored when they contain the operation status code | ⬜ MINOR | 1 | 15min |

---

## Detail des issues (groupees par regle)

### `java:S2699` — Tests should include assertions

**Severite max** : 🟥 BLOCKER | **Occurrences** : 1 | **Effort** : 40min

Tags : _junit, tests_

- [ ] `src/test/java/pro/softcom/aisentinel/application/pii/reporting/service/ScanSpaceStatsCollectorTest.java:168` (BLOCKER) — Add at least one assertion to this test case.

### `java:S6809` — Methods with Spring proxy should not be called via "this"

**Severite max** : 🟧 CRITICAL | **Occurrences** : 1 | **Effort** : 5min

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/detection/adapter/out/PiiTypeConfigPersistenceAdapter.java:86` (CRITICAL) — Call transactional methods via an injected dependency instead of directly via 'this'.

### `java:S6213` — Restricted Identifiers should not be used as Identifiers

**Severite max** : 🟨 MAJOR | **Occurrences** : 7 | **Effort** : 35min

- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/reporting/service/ScanSpaceStatsCollector.java:36` (MAJOR) — Rename this method to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/scan/port/out/PiiScanBenchRecorderPort.java:25` (MAJOR) — Rename this method to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/scan/port/out/PiiScanBenchRecorderPort.java:25` (MAJOR) — Rename this variable to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:83` (MAJOR) — Rename this method to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:83` (MAJOR) — Rename this variable to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/NoOpBenchRecorderAdapter.java:13` (MAJOR) — Rename this method to not match a restricted identifier.
- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/NoOpBenchRecorderAdapter.java:13` (MAJOR) — Rename this variable to not match a restricted identifier.

### `java:S2925` — "Thread.sleep" should not be used in tests

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 1h00min

Tags : _tests, bad-practice_

- [ ] `src/test/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapterTest.java:100` (MAJOR) — Remove this use of "Thread.sleep()".

### `java:S107` — Methods should not have too many parameters

**Severite max** : 🟨 MAJOR | **Occurrences** : 2 | **Effort** : 40min

Tags : _brain-overload_

- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/reporting/usecase/StreamConfluenceResumeScanUseCase.java:37` (MAJOR) — Constructor has 8 parameters, which is greater than 7 authorized.
- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/reporting/usecase/StreamConfluenceScanUseCase.java:34` (MAJOR) — Constructor has 8 parameters, which is greater than 7 authorized.

### `java:S7467` — Unused exception parameter should use the unnamed variable pattern

**Severite max** : ⬜ MINOR | **Occurrences** : 1 | **Effort** : 8min

Tags : _java22_

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:141` (MINOR) — Replace "e" with an unnamed pattern.

### `java:S3077` — Non-primitive fields should not be "volatile"

**Severite max** : ⬜ MINOR | **Occurrences** : 2 | **Effort** : 40min

Tags : _multi-threading, cert_

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:47` (MINOR) — Use a thread-safe type; adding "volatile" is not enough to make this field thread-safe.
- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:48` (MINOR) — Use a thread-safe type; adding "volatile" is not enough to make this field thread-safe.

### `java:S5841` — AssertJ assertions "allMatch" and "doesNotContain" should also test for emptiness

**Severite max** : ⬜ MINOR | **Occurrences** : 2 | **Effort** : 10min

Tags : _tests, assertj_

- [ ] `src/test/java/pro/softcom/aisentinel/infrastructure/pii/detection/adapter/out/entity/PiiTypeConfigConsistencyTest.java:118` (MINOR) — Test the emptiness of the list before calling this assertion predicate.
- [ ] `src/test/java/pro/softcom/aisentinel/infrastructure/pii/detection/adapter/out/entity/PiiTypeConfigConsistencyTest.java:135` (MINOR) — Test the emptiness of the list before calling this assertion predicate.

### `java:S135` — Loops should not contain more than a single "break" or "continue" statement

**Severite max** : ⬜ MINOR | **Occurrences** : 1 | **Effort** : 20min

Tags : _brain-overload_

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:121` (MINOR) — Reduce the total number of break and continue statements in this loop to use at most one.

### `java:S899` — Return values should not be ignored when they contain the operation status code

**Severite max** : ⬜ MINOR | **Occurrences** : 1 | **Effort** : 15min

Tags : _cwe, error-handling, cert_

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:152` (MINOR) — Do something with the "boolean" value returned by "offer".


