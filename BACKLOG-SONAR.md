# Backlog SonarQube — ai-sentinel-pii-reporting-api

> Backlog genere depuis SonarQube (http://localhost:9000), projet **ai-sentinel-pii-reporting-api**.
> Genere le 2026-06-10. Perimetre : issues a l'etat **OPEN / CONFIRMED / REOPENED** (issues resolues/fermees exclues).
> Effort total estime par Sonar : **8h37min**. Total issues : **61**.

## Synthese

### Par severite

| Severite | Nb |
|----------|----|
| 🟥 BLOCKER | 4 |
| 🟧 CRITICAL | 1 |
| 🟨 MAJOR | 21 |
| ⬜ MINOR | 35 |

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
| `java:S2925` | "Thread.sleep" should not be used in tests | 🟨 MAJOR | 4 | 1h20min |
| `java:S1068` | Unused "private" fields should be removed | 🟨 MAJOR | 3 | 15min |
| `java:S107` | Methods should not have too many parameters | 🟨 MAJOR | 2 | 40min |
| `java:S125` | Sections of code should not be commented out | 🟨 MAJOR | 2 | 10min |
| `java:S3457` | Format strings should be used correctly | 🟨 MAJOR | 1 | 1min |
| `java:S5976` | Similar tests should be grouped in a single Parameterized test | 🟨 MAJOR | 1 | 10min |
| `java:S6126` | String multiline concatenation should be replaced with Text Blocks | 🟨 MAJOR | 1 | 2min |
| `java:S1874` | "@Deprecated" code should not be used | ⬜ MINOR | 10 | 2h30min |
| `java:S1128` | Unnecessary imports should be removed | ⬜ MINOR | 9 | 9min |
| `java:S7467` | Unused exception parameter should use the unnamed variable pattern | ⬜ MINOR | 5 | 10min |
| `java:S5853` | Consecutive AssertJ "assertThat" statements should be chained | ⬜ MINOR | 5 | 25min |
| `java:S3077` | Non-primitive fields should not be "volatile" | ⬜ MINOR | 2 | 40min |
| `java:S5841` | AssertJ assertions "allMatch" and "doesNotContain" should also test for emptiness | ⬜ MINOR | 2 | 10min |
| `java:S135` | Loops should not contain more than a single "break" or "continue" statement | ⬜ MINOR | 1 | 20min |
| `java:S899` | Return values should not be ignored when they contain the operation status code | ⬜ MINOR | 1 | 15min |

---

## Detail des issues (groupees par regle)

### `java:S2699` — Tests should include assertions

**Severite max** : 🟥 BLOCKER | **Occurrences** : 4 | **Effort** : 40min

Tags : _junit, tests_

- [ ] `src/test/java/pro/softcom/aisentinel/application/pii/reporting/service/ScanSpaceStatsCollectorTest.java:168` (BLOCKER) — Add at least one assertion to this test case.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:392` (BLOCKER) — Add at least one assertion to this test case.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:398` (BLOCKER) — Add at least one assertion to this test case.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:417` (BLOCKER) — Add at least one assertion to this test case.

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

**Severite max** : 🟨 MAJOR | **Occurrences** : 4 | **Effort** : 1h20min

Tags : _tests, bad-practice_

- [ ] `src/test/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapterTest.java:100` (MAJOR) — Remove this use of "Thread.sleep()".
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:1154` (MAJOR) — Remove this use of "Thread.sleep()".
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:1199` (MAJOR) — Remove this use of "Thread.sleep()".
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusGliner2PresidioRegexScanIT.java:1013` (MAJOR) — Remove this use of "Thread.sleep()".

### `java:S1068` — Unused "private" fields should be removed

**Severite max** : 🟨 MAJOR | **Occurrences** : 3 | **Effort** : 15min

Tags : _unused_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:289` (MAJOR) — Remove this unused "TIKA" private field.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:739` (MAJOR) — Remove this unused "startedAt" private field.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityIT.java:26` (MAJOR) — Remove this unused "MAPPER" private field.

### `java:S107` — Methods should not have too many parameters

**Severite max** : 🟨 MAJOR | **Occurrences** : 2 | **Effort** : 40min

Tags : _brain-overload_

- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/reporting/usecase/StreamConfluenceResumeScanUseCase.java:37` (MAJOR) — Constructor has 8 parameters, which is greater than 7 authorized.
- [ ] `src/main/java/pro/softcom/aisentinel/application/pii/reporting/usecase/StreamConfluenceScanUseCase.java:34` (MAJOR) — Constructor has 8 parameters, which is greater than 7 authorized.

### `java:S125` — Sections of code should not be commented out

**Severite max** : 🟨 MAJOR | **Occurrences** : 2 | **Effort** : 10min

Tags : _unused_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:271` (MAJOR) — This block of commented-out lines of code should be removed.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:296` (MAJOR) — This block of commented-out lines of code should be removed.

### `java:S3457` — Format strings should be used correctly

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 1min

Tags : _cert, confusing_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:775` (MAJOR) — Not enough arguments.

### `java:S5976` — Similar tests should be grouped in a single Parameterized test

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 10min

Tags : _tests, bad-practice, clumsy_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:81` (MAJOR) — Replace these 5 tests with a single Parameterized one.

### `java:S6126` — String multiline concatenation should be replaced with Text Blocks

**Severite max** : 🟨 MAJOR | **Occurrences** : 1 | **Effort** : 2min

Tags : _java15_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusGliner2PresidioRegexScanIT.java:1173` (MAJOR) — Replace this String concatenation with Text block.

### `java:S1874` — "@Deprecated" code should not be used

**Severite max** : ⬜ MINOR | **Occurrences** : 10 | **Effort** : 2h30min

Tags : _cwe, obsolete, cert_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusBenchmarkIT.java:144` (MINOR) — Remove this use of "withFileSystemBind"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusBenchmarkIT.java:188` (MINOR) — Remove this use of "withDockerfilePath"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:166` (MINOR) — Remove this use of "withFileSystemBind"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:252` (MINOR) — Remove this use of "withDockerfilePath"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusGliner2PresidioRegexScanIT.java:272` (MINOR) — Remove this use of "withFileSystemBind"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusGliner2PresidioRegexScanIT.java:1514` (MINOR) — Remove this use of "withDockerfilePath"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/FormatPrefilterDiscardSmokeIT.java:128` (MINOR) — Remove this use of "withFileSystemBind"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/FormatPrefilterDiscardSmokeIT.java:302` (MINOR) — Remove this use of "withDockerfilePath"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/JudgeDiscardedEntitiesSmokeIT.java:116` (MINOR) — Remove this use of "withFileSystemBind"; it is deprecated.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/JudgeDiscardedEntitiesSmokeIT.java:252` (MINOR) — Remove this use of "withDockerfilePath"; it is deprecated.

### `java:S1128` — Unnecessary imports should be removed

**Severite max** : ⬜ MINOR | **Occurrences** : 9 | **Effort** : 9min

Tags : _unused_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusBenchmarkIT.java:9` (MINOR) — Remove this unused import 'org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:7` (MINOR) — Remove this unused import 'java.io.IOException'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:8` (MINOR) — Remove this unused import 'java.net.URI'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:9` (MINOR) — Remove this unused import 'java.net.http.HttpClient'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:10` (MINOR) — Remove this unused import 'java.net.http.HttpRequest'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:11` (MINOR) — Remove this unused import 'java.net.http.HttpResponse'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:15` (MINOR) — Remove this unused import 'org.mockito.ArgumentMatchers.any'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:16` (MINOR) — Remove this unused import 'org.mockito.Mockito.mock'.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeReachabilityTest.java:17` (MINOR) — Remove this unused import 'org.mockito.Mockito.when'.

### `java:S7467` — Unused exception parameter should use the unnamed variable pattern

**Severite max** : ⬜ MINOR | **Occurrences** : 5 | **Effort** : 10min

Tags : _java22_

- [ ] `src/main/java/pro/softcom/aisentinel/infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:141` (MINOR) — Replace "e" with an unnamed pattern.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:822` (MINOR) — Replace "ignored" with an unnamed pattern.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:1155` (MINOR) — Replace "ie" with an unnamed pattern.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusDataSqlComparisonIT.java:1200` (MINOR) — Replace "ie" with an unnamed pattern.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/CorpusGliner2PresidioRegexScanIT.java:1014` (MINOR) — Replace "ie" with an unnamed pattern.

### `java:S5853` — Consecutive AssertJ "assertThat" statements should be chained

**Severite max** : ⬜ MINOR | **Occurrences** : 5 | **Effort** : 25min

Tags : _tests, assertj_

- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeDeltaReporterTest.java:59` (MINOR) — Join these multiple assertions subject to one assertion chain.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeDeltaReporterTest.java:104` (MINOR) — Join these multiple assertions subject to one assertion chain.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeDeltaReporterTest.java:144` (MINOR) — Join these multiple assertions subject to one assertion chain.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeDeltaReporterTest.java:234` (MINOR) — Join these multiple assertions subject to one assertion chain.
- [ ] `src/test/java/pro/softcom/aisentinel/integration/LlmJudgeDeltaReporterTest.java:433` (MINOR) — Join these multiple assertions subject to one assertion chain.

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


