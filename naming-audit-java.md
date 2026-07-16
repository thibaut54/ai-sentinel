# Audit de nommage — code Java `pii-reporting-api`

Périmètre : `pii-reporting-api/src/main/java` (311 fichiers de production, architecture hexagonale : `domain` / `application` / `infrastructure`). Les tests sont hors périmètre.

Référentiel : skill `naming-conventions` (noms révélateurs d'intention, pas de désinformation, pas d'abréviations obscures, booléens `is/has/can/should`, méthodes = verbes, collections au pluriel, constantes `UPPER_SNAKE_CASE`).

Méthode : audit sélectif (seuls les cas défendables devant un senior sont retenus ; compteurs de boucle triviaux, lambdas ultra-locales et acronymes crypto/domaine légitimes — `pii`, `dto`, `id`, `url`, `dek`, `kek`, `iv`, `aad` — sont volontairement écartés). Les findings « misleading » ont été relus dans le code source pour vérifier le comportement affirmé.

> ⚠️ Avant renommage : certains champs sont exposés dans un **contrat JSON** (DTO) ou une **colonne SQL** (entités JPA). Un renommage nu casserait le contrat externe ou le mapping ORM — prévoir `@JsonProperty` / `@Column(name=...)` pour préserver la sérialisation/persistance existante.

---

## Synthèse

| Catégorie | Occurrences |
|-----------|-------------|
| Abréviation obscure | 17 |
| Misleading (nom trompe sur le comportement/type) | 4 |
| Bruit (nommé par le type conteneur) | 5 |
| Non-explicite | 5 |
| Lettre unique (méthode/API) | 4 |
| Convention / typo | 1 |

37 findings au total. Priorité décroissante : **misleading > abréviation métier récurrente (`nb`, `Ts`) > bruit > non-explicite > lettres uniques**.

---

## 1. Misleading — le nom trompe sur le comportement ou le type (priorité haute)

### 1.1 `saveNewConfluenceSpaces()` — méthode
`application/confluence/service/ConfluenceSpaceCacheRefreshService.java:27`
La méthode récupère **tous** les espaces (`getAllSpaces()` puis `saveAll(spaces)`), pas uniquement les « new ». Le préfixe `New` est faux et sa Javadoc parle de « rafraîchissement immédiat du cache ».
1. `refreshConfluenceSpacesCache()`
2. `reloadAllConfluenceSpaces()`
3. `syncConfluenceSpacesCache()`

### 1.2 `stream()` — méthode
`infrastructure/pii/scan/service/ScanEventBuffer.java:134` (classe interne `RingBuffer`)
Nommée `stream()` mais retourne une `List<T>`, d'où l'appel trompeur `buffer.stream().stream()` (ligne 73-74). Un lecteur attend un `java.util.stream.Stream`.
1. `snapshot()`
2. `toList()`
3. `drainInOrder()`

### 1.3 `getPiiDetection()` — méthode
`application/pii/reporting/port/out/ScanTimeOutConfig.java:7`
Retourne une `Duration` de timeout mais est nommée comme si elle renvoyait une détection PII. L'intention (timeout) est absente.
1. `getPiiDetectionTimeout()`
2. `piiDetectionTimeout()`
3. `getPiiDetectionScanTimeout()`

### 1.4 `ex` — variable locale
`infrastructure/confluence/adapter/out/CompositeAttachmentTextExtractorAdapter.java:32`
`ex` désigne par convention Java une exception, mais nomme ici un élément de stratégie d'extraction — et coexiste avec le `e` du bloc `catch`.
1. `extractor`
2. `textExtractor`
3. `candidateExtractor`

---

## 2. Abréviation obscure `nb` (nombre) — champs récurrents

Le préfixe `nb` (français « nombre ») n'est pas lisible pour un anglophone et n'est pas cherchable. À harmoniser sur `count` dans les **deux couches miroir** (domaine + DTO/entité).

### 2.1 `nbOfDetectedPIIBySeverity` / `nbOfDetectedPIIByType` — champs
`domain/pii/reporting/ConfluenceContentScanResult.java:22-23`
`infrastructure/pii/reporting/adapter/in/dto/ConfluenceContentScanResultEventDto.java:28-29`
1. `detectedPiiCountBySeverity` / `detectedPiiCountByType`
2. `piiCountsBySeverity` / `piiCountsByType`
3. `detectionCountBySeverity` / `detectionCountByType`

### 2.2 `nbSpaces` — champ
`domain/pii/reporting/FacetCount.java:10`
1. `spaceCount`
2. `spacesCount`
3. `numberOfSpaces`

### 2.3 `nbOfHighSeverity` / `nbOfMediumSeverity` / `nbOfLowSeverity` — champs
`infrastructure/pii/reporting/adapter/out/jpa/entity/ScanSeverityCountEntity.java:31,34,37`
1. `highSeverityCount` / `mediumSeverityCount` / `lowSeverityCount`
2. `highCount` / `mediumCount` / `lowCount`
3. `numberOfHighSeverity` / `numberOfMediumSeverity` / `numberOfLowSeverity`

---

## 3. Abréviation obscure `Ts` / `ts` (timestamp) — champs récurrents

Suffixe/nom `Ts` non prononçable. À harmoniser (ces champs sont des `Instant`/horodatages).

### 3.1 `lastEventTs` — champs
`domain/pii/reporting/SpaceSummary.java:30`
`domain/pii/scan/ConfluenceSpaceScanState.java:9`
`infrastructure/pii/reporting/adapter/in/dto/SpaceScanStateDto.java:10`
`infrastructure/pii/reporting/adapter/in/dto/SpaceSummaryDto.java:11`
1. `lastEventTimestamp`
2. `lastEventAt`
3. `lastEventInstant`

### 3.2 `ts` — champ
`infrastructure/pii/reporting/adapter/out/jpa/entity/ScanEventEntity.java:39`
1. `timestamp`
2. `eventTimestamp`
3. `occurredAt`

---

## 4. Bruit — variable/paramètre nommé par son type conteneur

### 4.1 `detectedPIIList` — champs
`domain/pii/reporting/ConfluenceContentScanResult.java:21`
`infrastructure/pii/reporting/adapter/in/dto/ConfluenceContentScanResultEventDto.java:27`
Le suffixe `List` encode le type ; préférer un pluriel décrivant le contenu.
1. `detectedPii`
2. `detections`
3. `detectedPiiItems`

### 4.2 `list` — variable locale
`infrastructure/pii/reporting/adapter/in/LastConfluencePersonallyIdentifiableInformationScanController.java:47`
Contient des états de scan.
1. `scanStates`
2. `spaceScanStates`
3. `lastScanStates`

### 4.3 `list` — paramètre
`infrastructure/pii/reporting/adapter/in/mapper/SpaceStatusMapper.java:12`
1. `scanStates`
2. `spaceScanStates`
3. `states`

### 4.4 `tmp` — variable locale
`infrastructure/pii/scan/adapter/out/transport/NettyGrpcPiiTransport.java:90`
Nomme un canal gRPC de secours ; doit décrire son rôle.
1. `fallbackChannel`
2. `retryChannel`
3. `secondaryChannel`

---

## 5. Non-explicite

### 5.1 `computeRemainPages()` — méthode
`domain/pii/reporting/ScanRemainingPagesCalculator.java:19`
`Remain` tronqué/agrammatical, et incohérent avec `computeRemainingPages()` défini juste en dessous dans le même fichier.
1. `computeRemainingPagesPlan()`
2. `resolveResumePlan()`
3. `computeScanRemainingPages()`

### 5.2 `position` — composant de record
`domain/pii/reporting/ContentPiiDetection.java:355`
Désigne l'index de **début**, mais forme une paire asymétrique avec `end` (attendu : `start`/`end`).
1. `start`
2. `startOffset`
3. `beginIndex`

### 5.3 `time` — variable locale
`infrastructure/confluence/adapter/out/ConfluenceAttachmentHttpDownloaderAdapter.java:66`
Instant de départ nommé vaguement.
1. `startTime`
2. `startInstant`
3. `downloadStartedAt`

### 5.4 `t1` — variables locales (mesure de perf)
`infrastructure/confluence/adapter/out/ConfluenceAttachmentHttpClientAdapter.java:79`
`infrastructure/confluence/adapter/out/ConfluenceAttachmentHttpDownloaderAdapter.java:87`
1. `endTime`
2. `endInstant`
3. `completedAt`

---

## 6. Abréviations obscures diverses

### 6.1 `relEntities` — variable
`application/pii/reporting/service/PiiContextExtractor.java:178`
`rel` ambigu (relative / relevant / relationship).
1. `relativeEntities`
2. `relevantEntities`
3. `contextEntities`

### 6.2 `rs` / `re` — variables
`application/pii/reporting/service/PiiContextExtractor.java:240-241`
« relative start » / « relative end » — non cherchables.
1. `relativeStart` / `relativeEnd`
2. `relStart` / `relEnd`
3. `startOffset` / `endOffset`

### 6.3 `dp` — variables
`infrastructure/confluence/adapter/out/AbstractConfluenceHttpClientAdapter.java:201`
`infrastructure/confluence/adapter/out/ConfluenceAttachmentHttpDownloaderAdapter.java:196`
Abréviation de `downloadPath`.
1. `downloadPath`
2. `attachmentDownloadPath`
3. `downloadUri`

### 6.4 `pd` — variables
`infrastructure/shared/adapter/in/GlobalExceptionHandler.java:243` (aussi 218, 228)
Abréviation de `ProblemDetail`.
1. `problemDetail`
2. `problem`
3. `errorDetail`

---

## 7. Lettres uniques sur API / méthodes non triviales

### 7.1 `s` — paramètre
`infrastructure/pii/reporting/adapter/in/mapper/SpaceStatusMapper.java:17`
1. `scanState`
2. `state`
3. `spaceScanState`

### 7.2 `r` — paramètre
`infrastructure/pii/scan/adapter/out/FileBenchRecorderAdapter.java:99`
`BenchRecord r` — la méthode sœur utilise `sample`.
1. `record`
2. `benchRecord`
3. `sample`

### 7.3 `s` — variable
`infrastructure/pii/scan/adapter/out/transport/NettyGrpcPiiTransport.java:102`
Désigne un `stub` gRPC.
1. `stub`
2. `piiStub`
3. `blockingStub`

---

## 8. Convention / typo

### 8.1 `errrorScanResultsFlux` — variable
`application/pii/reporting/usecase/StreamConfluenceScanUseCase.java:177` (et 214)
Faute de frappe (`errror`, triple `r`) → nom non cherchable.
1. `errorScanResultsFlux`
2. `failedScanResultsFlux`
3. `scanErrorFlux`
