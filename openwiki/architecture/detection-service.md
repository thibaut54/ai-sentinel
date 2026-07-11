# Service de détection (`pii-detector-service/`)

Microservice **Python / gRPC** qui reçoit du texte et renvoie les entités PII détectées.
Il combine trois détecteurs, fusionne leurs résultats et applique un post-filtre
déterministe. Organisation **hexagonale** : `pii_detector/{domain,application,infrastructure}/`.

Contrat gRPC : voir [system-overview.md](system-overview.md#contrat-grpc--protopii_detectionproto).

## Entrée & démarrage

- Entrypoint : `pii_detector/server.py` (`python -m pii_detector.server`).
  Args : `--port` (défaut **50051**), `--workers` (défaut 10), `--debug`.
- Le serveur réel est dans `pii_detector/infrastructure/adapter/in/grpc/pii_service.py`.
  `serve()` construit un serveur gRPC `ThreadPoolExecutor`, message max 10 Mo, réflexion
  gRPC activée, keepalive HTTP/2 assoupli (scans de plusieurs minutes), bind IPv6 `[::]`
  puis fallback `0.0.0.0`.
- Le détecteur est un **singleton** de processus (`get_detector_instance`). Un pool de
  workers multi-process s'active si `PII_WORKER_PROCESSES > 1`
  (`infrastructure/model_management/detector_worker_pool.py`).
- Packaging : `pyproject.toml` (hatchling, `requires-python >=3.9`). `torch` doit être
  installé en version CPU. Le `README.md` du module documente des champs
  (`StreamDetectPII`, `mask_pii`, `chunk_size`) **absents du proto actuel** — partiellement
  aspirational.

## Les trois détecteurs

`pii_detector/infrastructure/detector/`

- **RegexDetector** (`regex_detector.py`, source `REGEX`) : patterns déterministes chargés
  depuis `config/models/regex-patterns.toml` (IPv4/IPv6, MAC, tokens GitHub/AWS/JWT →
  API_KEY, carte + Luhn, SSN FR/CH/BE/DE, AVS suisse, téléphone suisse). Agnostique à la
  langue.
- **PresidioDetector** (`presidio_detector.py`, source `PRESIDIO`) : reconnaisseurs
  règles/regex. Config `config/models/presidio-detector.toml`. **La NER spaCy
  (PERSON/LOCATION/ORG) est délibérément désactivée** pour conformité de licence
  (Apache-2.0) — seuls les reconnaisseurs regex sont utilisés. Langues en/fr/de/es/it.
- **MinistralDetector** (`ministral_detector.py`, source `MINISTRAL`) : LLM extracteur
  génératif via un endpoint **compatible OpenAI `/chat/completions` (LM Studio)** — pas un
  modèle local embarqué. Réponse en JSON-schema strict `{text,label}`, température 0.
  Vocabulaire ouvert : labels résolus par `_LabelResolver` (exact → snake_case → passthrough
  UPPER_SNAKE, jamais abandonnés). Confiance fixe `MINISTRAL_CONFIDENCE = 1.0` (un modèle
  génératif n'a pas de score probabiliste). Endpoint/modèle surchargeables via
  `LLM_MINISTRAL_BASE_URL` / `LLM_MINISTRAL_MODEL` (défauts `ministral-3b-pii-preview@q8_0`,
  `localhost:1234/v1`).

> **Historique** : GLiNER, GLiNER2, OpenMed et le « LLM-judge » ont été **retirés**. Le
> stack courant est Ministral + Presidio + Regex + post-filtre. Toute mention résiduelle de
> ces composants (proto réservé, colonnes DB, logs) est morte.

## Orchestration & fusion

`pii_detector/application/orchestration/composite_detector.py`

- `CompositePIIDetector` exécute chaque détecteur **actif** séquentiellement, puis fusionne
  via `DetectionMerger`. L'activation est résolue **par requête** depuis les flags en base
  (bascule à chaud). Chaque détecteur tourne dans un `try/except` renvoyant `[]` en cas
  d'échec (dégradation gracieuse : une panne de l'endpoint Ministral ne coule pas la requête).
- `detect_pii_with_stats` renvoie `(entities, stats)` **par valeur** — sûr sous threads gRPC
  concurrents et picklable pour le pool de workers.
- **Fusion** (`pii_detector/domain/service/detection_merger.py`) : dédup par clé
  `(start, end, pii_type, text)` en gardant le meilleur score, puis résolution de
  chevauchement **par type** (balayage préférant le span le plus long, tie-break sur le
  score). Contient des logs `[PARITY_DEBUG]`/`[FINDING_TRACKER]` marqués « temporaires ».

## Pipeline du handler `DetectPII`

`pii_service.py` (méthode `DetectPII`) :

1. Validation (non vide, ≤ `max_text_size` = 1 000 000 caractères).
2. Si `fetch_config_from_db` : lit `default_threshold` + flags détecteurs + config par type
   (`_fetch_and_apply_config`).
3. Détection composite (chemin pool de workers ou in-process).
4. **Filtrage par config de type** (`_filter_entities_by_type_config`) : retire les types
   désactivés et les scores sous le seuil ; supporte des clés spécifiques par détecteur
   comme `REGEX:IP_ADDRESS` avec repli sur le type nu.
5. **Post-filtre déterministe** (seulement si le flag `postfilter_enabled` est en base ;
   import paresseux, zéro coût sinon) — les rejets remontent dans `discarded_entities` et
   comme pseudo-stat de détecteur.
6. Construction de la réponse : entités (plafonnées à **1000**), summary, `masked_content`
   (uniquement si contenu ≤ 10 000 caractères), discarded, stats.

Seuil : 0.5 par défaut si le seuil de requête ≤ 0 ; sinon `default_threshold` de la base ;
seuils par `pii_type` depuis `pii_type_config`.

## Post-filtre déterministe

`pii_detector/infrastructure/postfilter/`

Deux passes :
1. **Denylist d'artefacts techniques** agnostique au label (`technical_artifact_denylist.py`
   : UUID, ObjectId, digests, traceparent, versions, images base64).
2. **Stratégies de checksum/parse par `pii_type`** (`registry.py` + `strategies/`) :
   `ip_address`, `mac_address`, `iban`, `card_number` (Luhn), `avs_number` (EAN-13),
   `swiss_uid`, `swift_bic`. Des alias routent plusieurs labels vers une stratégie partagée
   (`TAX_ID`/`TAX_NUMBER`→SwissUid, `PAYMENT_CARD`→CardNumber,
   `NATIONAL_ID_NUMBER`→Avs, `BIC`/`SWIFT*`→SwiftBic).

**Fail-open absolu** : toute valeur non canonique, type non mappé ou exception de stratégie
**conserve** l'entité (le rappel prime sur la précision). Le point d'entrée est un singleton
`FormatPostfilterValidator` (`format_postfilter_validator.py`).

> ⚠️ Le domaine émet `DetectorSource.POSTFILTER` (`domain/entity/detector_source.py`) mais
> le proto expose `PREFILTER=7`, pas `POSTFILTER` : la stat de post-filtre se sérialise en
> `UNKNOWN_SOURCE`. À trancher lors d'un travail sur les stats de détecteur.

## Chunking (découpage) token-based

`pii_detector/infrastructure/text_processing/semantic_chunker.py`

- `MinistralTokenChunker` : fenêtres de ≤ `chunk_size` **vrais tokens** via les offsets
  caractère du tokenizer HF (`OpenMed/Ministral-3B-PII-Preview`, surchargeable par
  `LLM_MINISTRAL_TOKENIZER`). Invariant `text[chunk.start:chunk.end] == chunk.text` pour
  rebaser correctement les offsets globaux. Le recouvrement n'est pas dédupliqué dans le
  chunker — c'est le `DetectionMerger` qui collapse.
- `FallbackChunker` : approximation par ratio de caractères (`CHARS_PER_TOKEN=4`) quand le
  tokenizer ne charge pas (offline).
- Défauts : `DEFAULT_CHUNK_SIZE_TOKENS=2048`, `DEFAULT_CHUNK_OVERLAP_TOKENS=410` (~20 %),
  alignés sur l'init-script DB `013`.

## Configuration

- `config/detection-settings.toml` : réglages transverses ; documente que
  détecteurs/seuils/chunking sont **déplacés en base** (`pii_detection_config` id=1).
- `config/models/presidio-detector.toml` : reconnaisseurs Presidio, langues.
- `config/models/regex-patterns.toml` : les patterns regex (type, pattern, score, priorité).
- **Pas de TOML Ministral** — configuré entièrement en base + env LM Studio.
- Connexion DB via env `DB_HOST/PORT/NAME/USER/PASSWORD` (défauts host=`postgres`,
  db=`ai-sentinel`). Adapter : `infrastructure/adapter/out/database_config_adapter.py`
  (lecture défensive des colonnes Ministral/postfilter avec repli si non migré).

## Développer & tester

```bash
cd pii-detector-service
python -m pii_detector.server            # démarre le serveur gRPC (:50051)
pytest                                   # tous les tests
pytest tests/unit/                       # unitaires
pytest tests/integration/                # intégration (testcontainers Postgres, tokenizer réel)
pytest --cov=pii_detector --cov-report=html
```

Structure des tests : `tests/unit/` (~28 fichiers : composite, merger, chaque détecteur,
gRPC service, postfilter, observabilité), `tests/integration/` (DB via testcontainers,
chunker réel, benchmark faux positifs Presidio). Config pytest dans `pyproject.toml`.

**À surveiller lors d'une modification** : la config vit en base (tester via
`fetch_config_from_db`), le fail-open du post-filtre est intentionnel, et les stats/enums de
`DetectorSource` doivent rester alignés proto↔domaine. Régénérer les stubs avec le
`grpcio-tools` du runtime (`generate_pb.py`) pour éviter un décalage de version.
