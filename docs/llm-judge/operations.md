# Guide opérationnel — LLM Judge Qwen 3.6 A3B

## Résumé exécutif

Le LLM Judge est un post-filtre appliqué après la phase de détection PII du service `pii-detector-service`. Il soumet les entités GLiNER à un modèle de langage (`qwen/qwen3.6-35b-a3b`, Q4_K_M GGUF, ~20 GB) hébergé sur LM Studio, qui rend un verdict `TRUE_POSITIVE`, `FALSE_POSITIVE` ou `UNSURE` pour chaque entité. Les entités jugées `FALSE_POSITIVE` sont écartées ; les autres sont conservées.

La motivation principale est le taux de faux positifs GLiNER mesuré à 71,5 % sur le corpus Confluence client (FP rate global tous détecteurs : 41,7 %). Les détecteurs déterministes (Regex, Presidio, OpenMed) ne sont pas auditables par le judge au MVP — seul GLiNER est concerné. La cible est un FP rate global inférieur à 15 %.

Le judge est désactivé par défaut et contrôlé par un flag DB (`llm_judge_enabled BOOLEAN DEFAULT false`). Il peut être activé ou désactivé en production sans redémarrage du service Python.

---

## 1. Architecture

### 1.1 Schéma du pipeline de détection

```
gRPC DetectPII (PIIDetectionRequest)
    |
    v
PIIDetectionServicer.DetectPII (pii_service.py)
    |
    +-- [fetch_config_from_db] --> detector_flags (incl. llm_judge_enabled)
    |
    v
CompositePIIDetector.detect_pii
    |-- GLiNER.detect_pii      --> entities_gliner
    |-- Regex.detect_pii       --> entities_regex
    |-- Presidio.detect_pii    --> entities_presidio
    |-- OpenMed.detect_pii     --> entities_openmed
    +-- DetectionMerger.merge  --> merged_entities
    |
    v
[NEW] PostFilterPipeline.apply
    +-- LLMJudgeValidator.filter(text, [e for e in merged if e.source == GLINER])
            |-- verdict FALSE_POSITIVE --> discard
            +-- verdict TRUE_POSITIVE | UNSURE --> keep
    |
    v
Type-config filter (pii_service.py)
    |
    v
PIIDetectionResponse
```

### 1.2 Point d'injection dans le code

Le hook est inséré dans `CompositePIIDetector.detect_pii` (fichier `pii-detector-service/pii_detector/application/orchestration/composite_detector.py`). Le flag `llm_judge_enabled` est lu depuis la base de données à chaque requête gRPC (dans `pii_service.py`) et transmis à l'orchestrateur. Quand le flag est `false`, `PostFilterPipeline.apply` est un no-op complet : aucun thread, aucun appel réseau, aucune métrique.

### 1.3 Politique de décision MVP

```
si entity.source != GLINER    --> keep  (détecteur déterministe, non audité)
si verdict == FALSE_POSITIVE  --> discard
sinon (TRUE_POSITIVE ou UNSURE) --> keep  (politique de prudence, préserve le recall)
```

Il n'y a pas de seuil de confidence au MVP. `UNSURE` est traité comme un keep.

---

## 2. Setup LM Studio

L'instance cible est `172.22.22.63:1234`. Les paramètres suivants ont été vérifiés empiriquement le 2026-05-25.

### 2.1 Modèle requis

| Paramètre | Valeur |
|-----------|--------|
| Identifiant LM Studio | `qwen/qwen3.6-35b-a3b` |
| Quantization | Q4_K_M GGUF |
| Taille sur disque | ~20 GB |
| Page LM Studio | https://lmstudio.ai/models/qwen/qwen3.6-35b-a3b |

### 2.2 Paramètres GUI obligatoires

| Paramètre GUI LM Studio | Valeur requise | Pourquoi |
|-------------------------|----------------|----------|
| **Structured Output** | ACTIVE | Sans cette option, `response_format=json_schema strict` est rejeté — le JSON peut être noyé dans le raisonnement et tronqué. Vérification empirique : test 3 OK avec l'option activée, test 2 KO sans. |
| **Context Length** | >= 4096 tokens | Absorbe le system prompt (~300 tokens) + le user prompt (~150 tokens) + le raisonnement Qwen 3.6. Valeur minimale validée. |
| **Max Concurrent Predictions** | 4 | Doit être aligné sur `LLM_JUDGE_MAX_WORKERS=4` côté Python. Un écart engendre des timeouts ou un sous-utilisation du GPU. |
| **Flash Attention** | ACTIVE si GPU le supporte | Améliore les performances ; aucun impact sur la correction des réponses. |
| **KV cache type** | F16 | Équilibre mémoire/performance. Ne pas utiliser Q4 si le GPU est partagé avec d'autres processus. |

> **Avertissement** : Qwen 3.6 35B A3B est un thinking model. Même en envoyant `chat_template_kwargs.enable_thinking=false` ou le préfixe `/no_think` dans le system prompt, LM Studio ignore ces paramètres — le raisonnement est toujours actif (`reasoning_tokens > 0`). Ceci est documenté et attendu ; la configuration `max_tokens=2048` absorbe ce comportement.

### 2.3 Vérification du setup

```bash
# Vérifier que le modèle correct est exposé
curl http://172.22.22.63:1234/v1/models | jq '.data[].id'
```

La sortie doit contenir `"qwen/qwen3.6-35b-a3b"`. Si ce n'est pas le cas, charger le modèle dans l'interface LM Studio avant de démarrer le service.

```bash
# Test rapide d'inférence avec structured output
curl -s http://172.22.22.63:1234/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen/qwen3.6-35b-a3b",
    "messages": [{"role": "user", "content": "Réponds uniquement par le JSON: {\"ok\": true}"}],
    "response_format": {"type": "json_schema", "json_schema": {"name": "test", "strict": true, "schema": {"type": "object", "properties": {"ok": {"type": "boolean"}}, "required": ["ok"], "additionalProperties": false}}},
    "max_tokens": 256,
    "temperature": 0.2
  }' | jq '.choices[0].message'
```

Le champ `reasoning_content` doit contenir le JSON ; `content` peut être vide (comportement normal avec ce modèle).

---

## 3. Activation côté service

### 3.1 Variables d'environnement

| Variable | Défaut | Rôle |
|----------|--------|------|
| `LLM_JUDGE_BASE_URL` | `http://172.22.22.63:1234/v1` | Endpoint LM Studio (sans `/chat/completions`). Modifier si l'instance change d'adresse. |
| `LLM_JUDGE_PREFERRED_MODEL` | `qwen/qwen3.6-35b-a3b` | Identifiant préféré résolu via `GET /v1/models` au démarrage. Utilisé en match exact en priorité, puis fuzzy avec blacklist fine-tunes. |
| `LLM_JUDGE_TIMEOUT_SECONDS` | `120` | Timeout par appel HTTP. En cas de timeout, la politique `fail_open=true` conserve l'entité. |
| `LLM_JUDGE_MAX_BATCH_SIZE` | `1` | Nombre de verdicts par appel LM Studio. MVP : 1 (correspond à la pipeline de validation externe). |
| `LLM_JUDGE_MAX_WORKERS` | `4` | Nombre de threads HTTP concurrents. Doit correspondre au paramètre « Max Concurrent Predictions » de LM Studio. |
| `LLM_JUDGE_FAIL_OPEN` | `true` | Si `true`, tout timeout / erreur réseau / JSON invalide conserve l'entité (pas de rejet silencieux). Mettre à `false` uniquement en environnement de test contrôlé. |

### 3.2 Configuration TOML

Fichier : `pii-detector-service/config/detection-settings.toml`

```toml
[llm_judge]
enabled_default = false
base_url = "http://172.22.22.63:1234/v1"
preferred_model = "qwen/qwen3.6-35b-a3b"
max_batch_size = 1                  # 1 verdict par appel au MVP
max_workers = 4
timeout_seconds = 120
max_tokens = 2048                   # absorbe raisonnement + JSON Qwen 3.6 (§1.6 spec)
temperature = 0.2
top_p = 1.0
context_window_chars = 300
fail_open = true                    # erreur -> entité gardée (préserve le recall)
use_json_schema = true              # response_format json_schema strict (obligatoire)
```

> `context_window_chars = 300` correspond aux 300 caractères avant/après l'entité extraits du texte complet pour construire le user prompt. Cette valeur est tunable sans rebuild.

### 3.3 Activation DB (toggle runtime)

```sql
-- Activer le judge pour tous les scans
UPDATE pii_detection_config SET llm_judge_enabled = true WHERE id = 1;

-- Désactiver le judge
UPDATE pii_detection_config SET llm_judge_enabled = false WHERE id = 1;

-- Vérifier l'état courant
SELECT id, llm_judge_enabled FROM pii_detection_config;
```

Le changement est pris en compte à la prochaine requête gRPC sans redémarrage du service.

### 3.4 Résolution dynamique du modèle au démarrage

Au démarrage du service Python, `LLMJudgeValidator._resolve_model_id` appelle `GET {base_url}/v1/models` et sélectionne le modèle selon la priorité suivante :

1. Match exact sur `LLM_JUDGE_PREFERRED_MODEL` (ex. `qwen/qwen3.6-35b-a3b`)
2. Fuzzy match : contient `qwen3.6` ET `a3b`, **sans** aucun des marqueurs de fine-tune ci-dessous

```python
_FINETUNE_BLACKLIST = ("uncensored", "heretic", "distilled", "aggressive", "finetune")
```

Si aucun candidat n'est trouvé, le service lève une `RuntimeError` au démarrage (voir section Troubleshooting T1). Sur l'instance `172.22.22.63`, le modèle `qwen/qwen3.6-35b-a3b` est présent en match exact — la résolution dynamique est un filet de sécurité pour les renommages LM Studio.

### 3.5 Quickstart Docker Compose

Extrait de `docker-compose.yml` pour surcharger les valeurs par défaut du service Python :

```yaml
services:
  pii-detector:
    image: ai-sentinel/pii-detector-service:v1.1.0
    environment:
      LLM_JUDGE_BASE_URL: "http://172.22.22.63:1234/v1"
      LLM_JUDGE_PREFERRED_MODEL: "qwen/qwen3.6-35b-a3b"
      LLM_JUDGE_TIMEOUT_SECONDS: "120"
      LLM_JUDGE_MAX_BATCH_SIZE: "1"
      LLM_JUDGE_MAX_WORKERS: "4"
      LLM_JUDGE_FAIL_OPEN: "true"
    # Le flag llm_judge_enabled reste dans la DB — ne pas surcharger ici
```

---

## 4. Comportements spécifiques Qwen 3.6 A3B

Ces comportements ont été observés empiriquement le 2026-05-25. Ils sont déterminants pour comprendre les logs et déboguer les anomalies.

### 4.1 Thinking model : JSON dans `reasoning_content`

Qwen 3.6 A3B est un modèle de raisonnement. LM Studio sépare le raisonnement de la réponse finale :

- **`message.content` est fréquemment VIDE** sur les cas qui déclenchent du raisonnement
- **`message.reasoning_content` contient le raisonnement et, avec `response_format=json_schema strict`, également le JSON final**

Le parseur `_extract_json_payload` lit `reasoning_content` en priorité, puis replie sur `content`. C'est l'inverse du comportement OpenAI standard.

```python
raw = (msg.get("reasoning_content") or msg.get("content") or "").strip()
```

### 4.2 Paramètres de contrôle du raisonnement ignorés

| Paramètre envoyé | Effet observé |
|-----------------|---------------|
| `chat_template_kwargs.enable_thinking=false` | Ignoré par LM Studio — `reasoning_tokens > 0` dans tous les tests |
| `/no_think` en début de system prompt | Ignoré — `reasoning_tokens > 0` dans tous les tests |

Ces paramètres sont envoyés en best-effort pour compatibilité future, mais ne doivent pas être supposés actifs en production.

### 4.3 `response_format=json_schema strict` : seule stratégie viable

| Scénario | Résultat observé |
|----------|-----------------|
| Sans `response_format`, `max_tokens=512`, cas complexe | `content` vide, JSON tronqué dans `reasoning_content`, `finish_reason=length` |
| Avec `response_format=json_schema strict`, `max_tokens=2048` | `content` vide, JSON parfait dans `reasoning_content`, `finish_reason=stop` |

Sans ce paramètre, Qwen peut épuiser `max_tokens` en raisonnant avant d'avoir produit un JSON valide. La configuration `Structured Output` dans l'interface LM Studio GUI est le prérequis nécessaire pour que ce paramètre soit accepté.

### 4.4 `max_tokens=2048`

Test de référence : 511 `reasoning_tokens` pour un faux positif complexe (acronyme DGAIC), sans schema. Avec `response_format=json_schema strict`, le test 3 de référence n'a consommé que 76 tokens. `max_tokens=2048` offre une marge de sécurité de ~4x par rapport au pire cas observé.

---

## 5. Métriques et observabilité

### 5.1 Logs Python : tag `[LLM-JUDGE]`

Chaque appel LM Studio produit un log de niveau `INFO` :

```
[LLM-JUDGE] inference request_id=abc123 prompt_tokens=119 completion_tokens=77 reasoning_tokens=76 total_tokens=196
```

Ce log permet de suivre la consommation de tokens par requête et de détecter une dérive du `reasoning_tokens` (signe que le modèle reasoning de plus en plus sur des cas qui devraient être simples).

### 5.2 Logs Python : tag `[THROUGHPUT]`

Le module `throughput_logger.py` émet des métriques par phase via une queue lock-free (non bloquante) :

```
[THROUGHPUT] phase=detection request_id=abc123 chars=15234 duration_s=1.021 chars_per_s=14921.0 entities_in=42
[THROUGHPUT] phase=llm_judge request_id=abc123 chars=15234 duration_s=3.421 chars_per_s=4453.4 entities_in=42 entities_kept=24 entities_rejected=18 batches=3 llm_total_calls=3 llm_total_call_duration_s=3.380
[THROUGHPUT] phase=total request_id=abc123 chars=15234 duration_s=4.442 chars_per_s=3430.8 entities_final=24
```

Si la queue interne (10 000 éléments max) est pleine, les métriques sont perdues sans impact sur le scan. La latence du scan n'est jamais affectée par le logging.

### 5.3 Logs Java : tag `[THROUGHPUT]`

Côté `pii-reporting-api`, le client gRPC émet un log structuré aligné sur le format Python :

```
[THROUGHPUT] phase=grpc.client request_id=abc123 chars=15234 duration_ms=4520 chars_per_s=3370
```

L'enregistrement est effectué via `Mono.subscribeOn(Schedulers.parallel())` — le thread du pipeline Reactor n'attend pas.

### 5.4 Métriques Micrometer (Spring Boot Actuator)

Endpoint : `http://<host>:8090/actuator/prometheus`

| Métrique | Tags | Description |
|---------|------|-------------|
| `pii.scan.chars.total` | `phase=grpc.client` | Total des caractères scannés cumulés |
| `pii.scan.duration` | `phase=grpc.client` | Distribution des durées de scan (p50/p95/p99) |

### 5.5 Script d'agrégation des logs

```bash
# Générer THROUGHPUT_REPORT.md à partir des logs de l'IT
python pii-detector-service/scripts/parse_throughput_logs.py \
  --log-file target/corpus-data-sql-comparison/improved-with-judge/pii-detector.log \
  --output THROUGHPUT_REPORT.md
```

Le rapport produit p50 / p95 / p99 par phase (`detection`, `llm_judge`, `total`, `grpc.client`) ainsi qu'un diff baseline vs judged.

---

## 6. Troubleshooting

### T1 — `RuntimeError: No Qwen 3.6 A3B model exposed by LM Studio`

| | |
|-|-|
| **Symptôme** | Le service Python refuse de démarrer avec ce message dans les logs |
| **Diagnostic** | `_resolve_model_id` a appelé `GET /v1/models` et n'a trouvé aucun modèle correspondant au pattern `qwen3.6 + a3b` hors blacklist |
| **Fix** | 1. Exécuter `curl http://172.22.22.63:1234/v1/models \| jq '.data[].id'` pour lister les modèles disponibles. 2. Si `qwen/qwen3.6-35b-a3b` n'apparaît pas : charger le modèle dans LM Studio GUI. 3. Si un fine-tune est présent mais pas le modèle de base : télécharger `qwen/qwen3.6-35b-a3b` (Q4_K_M GGUF, ~20 GB depuis https://lmstudio.ai/models/qwen/qwen3.6-35b-a3b). |
| **Prévention** | Vérifier `curl /v1/models` avant chaque déploiement ou redémarrage du service. |

---

### T2 — `ValueError: Empty reasoning_content and content from LM Studio`

| | |
|-|-|
| **Symptôme** | Logs `[LLM-JUDGE]` absents, entités passées en fail_open, erreur dans les logs Python |
| **Diagnostic** | LM Studio a retourné une réponse avec `content=""` et `reasoning_content=""` — soit parce que `Structured Output` est désactivé dans le GUI, soit parce que le payload n'a pas été transmis correctement |
| **Fix** | 1. Vérifier dans LM Studio GUI que `Structured Output` est activé pour le modèle chargé. 2. Activer les logs debug du service Python (`LOG_LEVEL=DEBUG`) et inspecter le payload envoyé — chercher `response_format` dans la requête sortante. 3. Redémarrer LM Studio si l'option vient d'être modifiée (certaines versions nécessitent un rechargement du modèle). |

---

### T3 — `httpx.TimeoutException` répétés

| | |
|-|-|
| **Symptôme** | Logs `[LLM-JUDGE] timeout request_id=...` fréquents, latence globale dégradée, `[THROUGHPUT] phase=llm_judge` avec `duration_s` approchant `LLM_JUDGE_TIMEOUT_SECONDS` |
| **Diagnostic** | LM Studio est surchargé (trop de requêtes concurrentes) ou `Max Concurrent Predictions` est inférieur à `LLM_JUDGE_MAX_WORKERS` |
| **Fix** | 1. Vérifier que `Max Concurrent Predictions` dans LM Studio GUI = `LLM_JUDGE_MAX_WORKERS` (défaut : 4). 2. Si GPU partagé avec d'autres charges, réduire les deux valeurs à 2. 3. Augmenter `LLM_JUDGE_TIMEOUT_SECONDS` comme mesure temporaire si la charge est éphémère. 4. `fail_open=true` assure qu'aucune entité n'est rejetée silencieusement — le service reste fonctionnel pendant la dégradation. |

---

### T4 — `finish_reason="length"` + JSON tronqué dans les logs

| | |
|-|-|
| **Symptôme** | Logs LM Studio montrent `finish_reason=length` ; `_extract_json_payload` lève `JSONDecodeError` ; entités conservées via fail_open |
| **Diagnostic** | `max_tokens` configuré est insuffisant pour absorber le raisonnement Qwen 3.6 sur des cas complexes. Observé en test 2 avec `max_tokens=512` et 511 reasoning_tokens consommés. |
| **Fix** | Vérifier que `max_tokens=2048` est bien dans le TOML (`[llm_judge] max_tokens = 2048`). Si la valeur a été surchargée par une variable d'environnement, la rétablir. La valeur 2048 offre ~4x la marge du pire cas observé empiriquement. |
| **Surveillance** | Monitorer le champ `reasoning_tokens` dans les logs `[LLM-JUDGE]`. Si > 1000 tokens sur des cas simples, ouvrir un ticket — le modèle chargé n'est peut-être pas celui attendu. |

---

### T5 — Recall qui chute (vrais positifs rejetés par le judge)

| | |
|-|-|
| **Symptôme** | Le rapport `judge-delta.md` montre des entités IBAN, numéros AVS, IBANs, numéros de carte rejetées par le judge ; le FP rate diminue mais le recall aussi |
| **Diagnostic** | Deux causes possibles : (a) un fine-tune a été sélectionné par erreur par `_resolve_model_id` malgré la blacklist ; (b) le system prompt a été modifié sans validation sur le corpus de référence |
| **Fix** | 1. Vérifier dans les logs de démarrage l'identifiant exact du modèle résolu : chercher `[LLM-JUDGE] resolved model=`. 2. Si un fine-tune a été sélectionné, s'assurer que son identifiant contient un mot de `_FINETUNE_BLACKLIST` et mettre à jour la liste si nécessaire. 3. Si le modèle est correct, comparer le system prompt actuel avec la version de référence dans `prompt_templates.py`. 4. Consulter `judge-delta.md` pour identifier les catégories de faux rejets. |

---

### T6 — Throughput `chars/sec` divisé par plus de 2x

| | |
|-|-|
| **Symptôme** | `[THROUGHPUT] phase=llm_judge chars_per_s=` inférieur à la moitié de la valeur baseline ; `THROUGHPUT_REPORT.md` montre p95 > 6 s/requête |
| **Diagnostic** | Soit trop d'appels séquentiels (batching inefficace), soit `LLM_JUDGE_MAX_WORKERS` trop bas, soit LM Studio en dégradation partielle |
| **Fix** | 1. Vérifier `LLM_JUDGE_MAX_WORKERS` dans les variables d'environnement du conteneur. 2. Vérifier `LLM_JUDGE_MAX_BATCH_SIZE` — la valeur MVP est 1, une valeur plus haute n'est pas encore testée. 3. Inspecter les logs `[THROUGHPUT] phase=llm_judge llm_total_calls=` vs `batches=` — si `llm_total_calls` >> nombre d'entités/max_workers, le parallélisme est cassé. 4. Monitorer le GPU de `172.22.22.63` pendant le scan (commande `nvidia-smi` sur la machine cible). |

---

## 7. Escalation matrix

| Sévérité | Symptôme | Action immédiate | Owner |
|---------|----------|-----------------|-------|
| **P0** | LM Studio DOWN + `JUDGE_FAIL_OPEN=false` accidentellement — scans bloqués, service indisponible | 1. Basculer `LLM_JUDGE_FAIL_OPEN=true` via variable d'environnement + redémarrage conteneur. 2. Ou désactiver le judge : `UPDATE pii_detection_config SET llm_judge_enabled=false WHERE id=1`. 3. Relancer LM Studio sur `172.22.22.63`. | Ops on-call |
| **P1** | FP rate dégradé > 30 % mesuré via `judge-delta.md` alors que la cible est < 15 % — qualité de détection insuffisante | 1. Snapshot de `judge-delta.md` et `THROUGHPUT_REPORT.md`. 2. Vérifier l'identifiant du modèle résolu dans les logs de démarrage. 3. Ouvrir une issue avec les métriques. 4. Contacter l'équipe data pour analyse des verdicts FP/FN. | Lead data + data team |
| **P2** | Throughput dégradé > 50 % vs baseline — `chars_per_s` moyen < 2 100 chars/s mesuré sur le corpus complet | 1. Suivre T6 (section Troubleshooting). 2. Ajuster `LLM_JUDGE_MAX_WORKERS` et aligner avec `Max Concurrent Predictions` LM Studio. 3. Profiler avec `parse_throughput_logs.py` pour isoler la phase responsable. | Backend dev + ops |
| **P3** | Logs verbeux, warnings non-critiques, ou `reasoning_tokens` croissants sans impact sur la qualité | Créer un ticket de maintenance. Pas d'action immédiate requise. Surveiller lors du prochain déploiement. | Backend dev |

---

## 8. Hors scope MVP et backlog

Les éléments suivants sont explicitement exclus du périmètre MVP (cf. §1.4 et §7 de [llm-judge-qwen-spec.md](../../_bmad-output/planning-artifacts/llm-judge-qwen-spec.md)).

| Fonctionnalité | Statut | Notes |
|---------------|--------|-------|
| Backend `local` (llama-cpp-python) | Hors scope MVP | Le code est présent mais non testé. Si activé par erreur, le service loggue `WARN: local backend out of scope for MVP` et se comporte en no-op. |
| Audit d'autres détecteurs que GLiNER | Backlog | Future colonne `llm_judge_sources` en DB pour configurer quels détecteurs sont audités. |
| Pool de N endpoints LM Studio | Backlog | L'instance `172.22.22.63` est un SPOF. Le `fail_open=true` est la seule mitigation en MVP. |
| Seuil de confidence configurable | Backlog | Future colonne `llm_judge_threshold` en DB. En MVP, `UNSURE` est toujours conservé. |
| Toggle UI Angular | Hors scope MVP | Le MVP est backend-only. L'interface Angular (héritée de `feature/gemma4-as-judge`) est désactivée ou renommée pour la prochaine itération. |

---

## Annexe A.1 — Forme exacte de la réponse Qwen 3.6 observée

Réponse réelle du test 3 (2026-05-25) : acronyme DGAIC évalué comme `FALSE_POSITIVE` / `PASSWORD`, avec `response_format=json_schema strict` et `max_tokens=2048`.

```json
{
  "id": "chatcmpl-...",
  "model": "qwen/qwen3.6-35b-a3b",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "",
      "reasoning_content": "{\n  \"verdict\": \"FALSE_POSITIVE\",\n  \"confidence\": 0.9,\n  \"reason\": \"...\"\n}\n",
      "tool_calls": []
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 119,
    "completion_tokens": 77,
    "total_tokens": 196,
    "completion_tokens_details": {"reasoning_tokens": 76}
  },
  "system_fingerprint": "qwen/qwen3.6-35b-a3b"
}
```

Points clés :
- `content` est vide (`""`) — comportement normal avec ce modèle et `response_format=json_schema`
- `reasoning_content` contient le JSON valide conforme au schema
- `completion_tokens_details.reasoning_tokens` = 76 sur ce cas simple (511 observés sur un cas complexe sans schema)
- `finish_reason` = `"stop"` confirme que le JSON n'est pas tronqué

---

## Annexe A.2 — Inventaire des modèles sur `172.22.22.63` (2026-05-25)

| Identifiant | Statut MVP | Raison |
|-------------|-----------|--------|
| `qwen/qwen3.6-35b-a3b` | **Cible MVP** | Match exact, modele de base validé empiriquement |
| `qwen/qwen3.6-27b` | Non utilisé | Modèle plus petit, non validé sur ce corpus |
| `qwen/qwen3.6-27b:2` | Non utilisé | Variante chargée deux fois |
| `qwen3.6-27b-heretic-uncensored-finetune-neo-code-di-imatrix-max` | Blacklisté | Contient `heretic`, `uncensored`, `finetune` |
| `qwen3.6-27b-uncensored-heretic-v2-native-mtp-preserved` | Blacklisté | Contient `uncensored`, `heretic` |
| `unsloth/qwen3.6-27b` | Non utilisé | Repackaging Unsloth, 27B, non validé |
| `google/gemma-4-e4b` | Non utilisé | Héritage branche `feature/gemma4-as-judge` |
| `google/gemma-4-31b` | Non utilisé | Héritage branche `feature/gemma4-as-judge` |
| `qwen3.6-35b-a3b-claude-4.6-opus-reasoning-distilled` | Blacklisté | Contient `distilled` |
| `qwen3.6-35b-a3b-uncensored-hauhaucs-aggressive` | Blacklisté | Contient `uncensored`, `aggressive` |
| `text-embedding-nomic-embed-text-v1.5` | Hors scope | Modèle d'embeddings |
| `openai/gpt-oss-20b` | Hors scope | Autre fournisseur |
| `dolphin-2.5-mixtral-8x7b@q6_k` | Hors scope | Autre modèle |
| `dolphin-2.5-mixtral-8x7b@q8_0` | Hors scope | Autre modèle |
| `dolphin-2.0-mistral-7b` | Hors scope | Autre modèle |

La blacklist `_FINETUNE_BLACKLIST = ("uncensored", "heretic", "distilled", "aggressive", "finetune")` exclut correctement les 4 variantes Qwen 3.6 non validées présentes sur l'instance.

---

## Annexe A.3 — Liens documentaires

| Ressource | URL |
|-----------|-----|
| Page LM Studio du modèle | https://lmstudio.ai/models/qwen/qwen3.6-35b-a3b |
| Qwen 3.6 HuggingFace card | https://huggingface.co/Qwen/Qwen3.6-35B-A3B |
| Blog de release Qwen 3.6 | https://qwen.ai/blog?id=qwen3.6-35b-a3b |
| Docs LM Studio (Unsloth) | https://unsloth.ai/docs/basics/inference-and-deployment/lm-studio |
| Spec technique complète | [llm-judge-qwen-spec.md](../../_bmad-output/planning-artifacts/llm-judge-qwen-spec.md) |
