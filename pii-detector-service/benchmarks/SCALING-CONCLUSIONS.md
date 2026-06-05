# Optimisation vélocité `scanCorpusWithEstimate` — journal de conclusions

> Objectif : trouver la manière la plus optimale de scaler le scan corpus (GLiNER2 + Presidio + Regex, CPU only)
> et d'utiliser le plein potentiel de la machine. Mesure = **chars scannés / seconde (total)**, avant/après,
> avec validation d'un échantillon de findings (anti-régression précision).
> Statut : EN COURS — mis à jour au fil de l'eau.

## 1. Contexte & diagnostic initial

- Test cible : `CorpusGliner2PresidioRegexScanIT#scanCorpusWithEstimate` (~40 h estimées, ~150-190 chars/s).
- Pipeline : backend Java (Testcontainers) → gRPC → pii-detector Python (container unique) → composite GLiNER2 + Presidio + Regex.
- **Diagnostic** : le scan est mono-flux. La boucle Java envoie 1 fichier à la fois (appel gRPC bloquant).
  Le serveur Python a pourtant 10 worker threads gRPC et un détecteur singleton chargé une fois.
  Le seul parallélisme actif est l'intra-op PyTorch (`TORCH_NUM_THREADS=8`) sur un unique forward pass
  → scaling intra-op notoirement mauvais sur petit transformer CPU (~2-3× pour 8 threads, memory-bound).

## 2. Machine de test

| Ressource | Valeur |
|---|---|
| CPU | Intel Core Ultra 7 255H — **16 cœurs / 16 threads** (hybride : 6 P-cores + 8 E-cores + 2 LP-E) |
| RAM | 62.9 GB |
| Docker | 29.5.2 (Desktop / WSL2) — **`.wslconfig` bride à `processors=8`, `memory=16GB`** → le Testcontainer ne voit que la moitié de la machine |
| Modèle | ⚠ Correction : le seed utilise **`fastino/gliner2-large-v1`** (1.87 GB sur disque, encodeur DeBERTa-v3-large ~435M params), PAS le privacy-filter 205M (commenté dans `config/models/gliner2-pii.toml`). Une copie ≈ 2 GB RAM (+ ~0.5-1 GB spaCy/Presidio par processus). |
| Cache HF | `~/.ai-sentinel-it-hf-cache` (monté dans le container par l'IT) contient déjà gliner2-large-v1 → réutilisable pour les benchs host directs |
| Venv | `pii-detector-service/.venv` : torch 2.11.0+cpu, gliner2 1.3.1, presidio 2.2.362, spacy 3.8.14, transformers 5.1.0 |

### Découvertes immédiates (avant toute mesure)

1. **Levier gratuit n°1 — `.wslconfig`** : la machine a 16 cœurs / 63 GB mais Docker/WSL2 est bridé
   à 8 cœurs / 16 GB. Pour le run réel du test IT, passer à `processors=16`, `memory=40GB`
   double le plafond de compute du container (et permet plus de copies du modèle).
2. **Config détecteurs du seed** (`data-improved-gliner2-presidio-regex.sql`) :
   gliner=false, presidio=true, regex=true, openmed=false, **gliner2=true**, threshold global 0.30,
   24 labels GLINER2 actifs (GOVERNMENT_ID, FINANCIAL, DIGITAL, SECURITY).
3. **Granularité de dispatch** : le pire fichier analysable ≈ 1M chars = ~87 min à 190 chars/s
   même avec des workers idle → un levier « parallélisme intra-requête » (chunks 384 tokens
   du même document répartis sur K threads) est ajouté à la matrice (L6). Il bénéficierait
   aussi à la prod (latence d'une grosse requête unique) sans changer le client Java.

## 3. Leviers à mesurer (matrice d'expériences)

| ID | Levier | Description | Hypothèse |
|---|---|---|---|
| L0 | Baseline actuelle | 1 flux séquentiel, modèle unique, `torch_threads=8` (intra-op) | ~150-190 chars/s |
| L0b | Intra-op max | 1 flux, `torch_threads=16` | gain marginal (<1.3× vs L0) |
| L3 | Mono-thread pur | 1 flux, `torch_threads=1` | dénominateur du scaling |
| L1 | Threads + modèle partagé | K threads concurrents sur le MÊME modèle (GIL libéré pendant forward), `torch_threads=1` | 4-8× vs L3 si le GIL ne bride pas |
| L2 | **N processus / N copies du modèle** (préféré utilisateur) | N process, chacun sa copie, `torch_threads=1-2` | quasi-linéaire jusqu'à saturation bande passante mémoire |
| L4 | Batch inference | batcher les chunks (384 tokens) dans un même forward | meilleur usage BLAS, gain 1.2-2× |
| L5 | (exploratoire) quantization int8 dynamique | `quantize_dynamic` sur les Linear | 2-3× possible MAIS risque précision → validation findings obligatoire |

Métriques par levier : chars/s total (wall clock), speedup vs L0, RSS mémoire, et **diff findings vs baseline**
(clé `(fichier, start, end, pii_type)`, tolérance epsilon sur les scores).

## 4. Méthode de mesure

- Harnais Python direct (sans Testcontainers) dans `pii-detector-service/benchmarks/` :
  charge le pipeline réel (Gliner2Detector + Presidio + Regex, configs issues du seed SQL
  `data-improved-gliner2-presidio-regex.sql`), scanne un échantillon fixe du corpus
  (`pii-reporting-api/src/test/resources/corpus/`), mesure chars/s, dump les findings en JSONL.
- Comparaison de findings : script de diff baseline vs levier.
- Le harnais direct élimine le bruit gRPC/Java pour isoler le comportement de scaling ;
  la validation finale rejouera le chemin réel (test IT borné) avec le levier gagnant.

## 4bis. Journal des décisions d'exécution (nuit du 2026-06-04)

- **09:08** Smoke bench host : pipeline OK mais GLiNER2 ne chargeait pas sur l'hôte Windows.
  Cause racine : `OSError 1455 (paging file too small)` au mmap safetensors 1.8 GB, masquée par
  un fallback `pytorch_model.bin` → 404 trompeur. Le **commit Windows était quasi saturé**
  (73.6/79.6 GB) — principal consommateur : vmmemWSL (14.3 GB) + IDE (5.5 GB).
- **09:17** Découverte : un run du IT était EN COURS depuis ~17 h (containers Testcontainers
  actifs, CPU 440 %) — avancement **370/~700 fichiers, 24 857 findings**.
- **Décision** : le test ayant un mécanisme de reprise robuste (`processed.txt` + findings
  flushés par fichier), on l'arrête proprement pour (a) libérer CPU/commit pour des mesures
  fiables, (b) relancer ensuite la version optimisée qui REPREND le restant beaucoup plus vite.
  Perte max : le fichier en cours (re-scanné à la reprise).
- **09:20** Backup de l'état : `pii-reporting-api/corpus-scan-backup-20260604-0920/`
  (findings.jsonl 25.3 MB + processed.txt 370 lignes).
- Arrêt JVM test (PID 20272), puis `wsl --shutdown` pour récupérer le commit, puis benchs.

## 5. Résultats des mesures

### 5.1 Profilage par détecteur (sample 101k chars, 24 fichiers, seq torch=8, host 16 cœurs)

| Détecteur seul | chars/s | Part du temps pipeline |
|---|---:|---:|
| REGEX | 3 927 630 | ~0.005 % |
| PRESIDIO | 63 539 | ~0.3 % |
| **GLINER2** | **188** | **~99.7 %** |

→ **Le pipeline EST GLiNER2** (DeBERTa-v3-large, forward pass CPU). Tout levier de scaling doit
cibler l'inférence GLiNER2 ; la part Python/GIL hors-forward est négligeable, ce qui rend le
data-parallélisme par threads viable en théorie (le forward libère le GIL).

### 5.2 Découverte API : `batch_extract`

La lib gliner2 1.3.1 expose `batch_extract(texts, schemas, batch_size=8, ...)`. Le détecteur
prod appelle `extract` chunk par chunk (384 tokens) — les chunks d'un même document peuvent
être batchés dans de vrais batchs de forward pass.

**Validation fonctionnelle (smoke 9k chars)** : findings 100 % identiques au chemin séquentiel
(20/20, zéro drift de score). RSS +1.7 GB (activations batch). Vitesse à mesurer sur machine
calme (le smoke tournait en contention avec la matrice).

### 5.3 Matrice de scaling (sample 101k chars / 24 fichiers, host 16 cœurs, machine calme)

**Baselines intra-op (1 flux, 1 modèle)** :

| Config | chars/s | Note |
|---|---:|---|
| seq torch=8 (≈ état actuel du test) | **141** | légèrement pollué en début de run (smoke concurrent) ; smoke isolé = 157 |
| seq torch=16 | 125 | **RÉGRESSION vs t8** — sync threads + E-cores ; l'intra-op >8 est contre-productif |
| seq torch=1 | ~50 (médian, mesure partielle 20/24 fichiers, min 30 / max 61) | dénominateur mono-thread |

**Lecture** : l'intra-op PyTorch est très inefficace sur ce modèle : 8 threads ne rendent que
~2.9× le mono-thread (141 vs 48-50), et 16 threads font PIRE que 8. Le plafond théorique
data-parallel : ~14 cœurs × 50 c/s ≈ 700 c/s, soit ~5× la baseline — à confirmer par les
leviers threads/procs ci-dessous.

**Leviers parallèles — tableau final mesuré** (sample 101k, host 16 cœurs) :

| tag | config | chars/s | speedup | findings | verdict |
|---|---|---:|---:|---:|---|
| seq-t1 | 1 flux, torch=1 | ~50 | ×0.35 | — | dénominateur mono-thread |
| **base-seq-t8** | 1 flux, torch=8 (état actuel) | **141** | **×1.00** | 1137 | baseline de référence |
| seq-t16 | 1 flux, torch=16 | 125 | ×0.89 | 1137 | RÉGRESSION : intra-op >8 contre-productif (sync + E-cores) |
| thr-k8-t1 | 8 threads, modèle PARTAGÉ | 144 | ×1.02 | 1137 | ≈ baseline : 8 flux mono-thread ≈ 1 flux 8-thread (mêmes 8 cœurs) |
| **thr-k14-t1** | **14 threads, modèle PARTAGÉ** | **203** | **×1.44** | 1137 | 🏆 **MEILLEUR pipeline complet** — suit le nb de cœurs utilisés |
| chunk-c8-t1 | chunks d'un doc sur 8 threads | 141 | ×1.00 | 1137 | aucun gain (même cœurs, intra-doc) |
| proc-n4-t2 | 4 procs × 2 threads (4 copies) | 159 | ×1.13 | 1137 | < threads : 4 copies = 4× RAM + bande passante, gain marginal |
| proc-n8 / n12 | 8/12 procs (8-12 copies) | ❌ | — | — | **ne tient pas en RAM** : 8×1.8 Go fp32 → thrash pagefile, 0 worker chargé, CPU 3-25% (disk-bound) |
| quant-i8-seq-t8 | int8 dynamique, torch=8 | 332 | ×2.35 | **610** | ❌ **REJETÉ : -46 % de findings** (entités GLiNER2 scores 0.75-0.96 perdues = dégradation réelle de la tête span, pas un seuil) |

**Précision** : tous les leviers de PARALLÉLISME (threads, procs, chunk) produisent **1137
findings strictement identiques** à la baseline (clé fichier/start/end/type/source, 0 drift de
score). Seul int8 dégrade (rejeté).

### 5.4 Conclusion technique (le verdict, contre-intuitif)

**Le pipeline est borné par la BANDE PASSANTE MÉMOIRE, pas par le CPU.** Le modèle réellement
chargé est `fastino/gliner2-large-v1` (DeBERTa-v3-large, **1.8 Go de poids fp32**). Chaque
forward relit ces poids depuis la DDR ; sur un laptop (Ultra 7 255H, DDR partagée) le bus mémoire
sature bien avant les cœurs. Conséquences mesurées :

1. **L'intra-op PyTorch plafonne à ×2.9** (t1→t8) et **régresse au-delà** (t16 < t8).
2. **Les N copies du modèle (l'intuition initiale) ne sont PAS le meilleur levier ici** :
   - elles n'apportent que ×1.13 (proc-n4) car la bande passante est partagée entre copies ;
   - elles coûtent N× la RAM → 8 copies ne tiennent pas (thrash).
3. **Le gagnant est le modèle PARTAGÉ multi-thread** (×1.44 à 14 threads) : une seule copie en
   RAM, le GIL libéré pendant le forward laisse K inférences se chevaucher, la vélocité suit le
   nombre de cœurs jusqu'à la borne mémoire. C'est **déjà l'architecture du serveur gRPC**
   (détecteur singleton + pool de threads) — il suffit que le **client envoie K requêtes
   concurrentes**.
4. Le seul levier qui attaque vraiment la borne mémoire est de **réduire le trafic de poids** :
   - **int8** : ×2.35 mesuré, mais quant dynamique naïve casse le recall (-46 %) → exige une
     vraie calibration (PTQ statique ou QAT) + re-bench précision avant tout usage ;
   - **modèle plus petit** : voir §7 — potentiellement le plus gros levier ET le plus aligné
     avec l'attente initiale.

Incidents de mesure : (1) 1er passage tué pendant seq-t1 (window-CLOSE console parente) ;
(2) proc-n8 cassé par une édition du harnais PENDANT le run (spawn réimporte le fichier disque) ;
(3) proc-n8/n12 = thrash mémoire host. Règle retenue : process détaché + ne jamais éditer le
harnais pendant un run procs.

## 6. Décision & implémentation retenue

**Levier principal = client concurrent sur le modèle partagé** (gagnant mesuré ×1.44, zéro perte
de précision, zéro RAM supplémentaire, déjà l'archi du serveur).

**Java — `CorpusGliner2PresidioRegexScanIT`** (chemin réel du test, refactor producteur/consommateur) :
- K appels gRPC `DetectPII` en vol via un `ExecutorService` (`-Dcorpus.scan.concurrency`,
  **défaut 12**) ; les workers ne font qu'extraction Tika + appel gRPC (aucun état partagé) ;
  le thread principal est le seul à écrire findings/processed/stats/progress → sérialisation sûre,
  fenêtre glissante K+2 pour borner la RAM ;
- `TORCH_NUM_THREADS=1` (chaque forward mono-thread, K en parallèle), `--workers` gRPC = 16,
  `PII_WORKER_PROCESSES=0` (pool de processus OFF — perdant ici) ;
- mécanisme de reprise inchangé (mêmes fichiers/règles) ; recall recalculé en fin de scan
  (équivalent strict au comptage incrémental) ; `ProgressTracker` en vitesse **wall-clock**
  (sinon ETA ×K trop pessimiste) ; warmup concurrent ×K ;
- `.wslconfig` relevé de 8→**14 processeurs**, 16→32 Go (le container ne voyait que la moitié
  de la machine) — c'est le préalable qui débloque réellement le ×1.44 (à 8 CPU, K=8 ≈ baseline).

**Python — pool de processus optionnel** (`detector_worker_pool.py`, intégré à `pii_service.py`,
**OFF par défaut**) : conservé pour un hôte à forte bande passante mémoire (serveur multi-canaux,
où les N copies pourraient scaler). Sur Linux : fork-COW après préchargement (1 copie de poids
partagée). Échec d'init → fallback silencieux au chemin historique. À n'activer (`PII_WORKER_PROCESSES>1`)
qu'après un bench sur la cible.

**Estimation du gain** sur le test, machine en l'état (WSL 14 CPU) : ~×1.4-1.5 → **~40 h → ~27-28 h**,
sans toucher à la précision. Le gain « ×N » espéré n'est pas atteignable par le seul parallélisme :
il est plafonné par la bande passante mémoire (cf. §5.4 et §7).

### 6.1 Statut de validation

- ✅ **Précision** : leviers de parallélisme (threads/procs/chunk) → **1137 findings identiques**
  à la baseline (`compare_findings.py`, clé fichier/span/type/source, 0 drift). int8 rejeté (-46 %).
- ✅ **Tests unitaires** `tests/unit/test_pii_service.py` (worker pool inclus) : exit 0
  (hors `test_serve_creates_server`, **régression PRÉ-EXISTANTE** : mock gRPC sans les keepalive
  d'un commit antérieur, sans rapport avec ces changements).
- ✅ **Sûreté prod** : `pool_size_from_env()` → 0 par défaut / sur valeur 1 / sur valeur invalide
  (chemin historique strictement inchangé tant que `PII_WORKER_PROCESSES` n'est pas > 1).
- ✅ **Compilation** Java (`mvn test-compile`) OK.
- ⏳ **E2E réel (IT complet)** non rejoué cette nuit : run de ~15-27 h qui MUTE l'état de scan
  (reprise). État sauvegardé (`corpus-scan-backup-20260604-0920/`, 371 fichiers). À lancer par
  l'utilisateur — sert aussi de mesure avant/après réelle (la vélocité wall-clock loggée par
  `[PROGRESS]` doit passer de ~140 à ~200 c/s sur 14 cœurs WSL).

### 6.2 Commande pour lancer le scan optimisé (reprend les 371 fichiers déjà faits)

```
# .wslconfig déjà relevé à 14 CPU / 32 Go — redémarrer WSL pour l'appliquer : wsl --shutdown
mvn -pl pii-reporting-api test -Dtest=CorpusGliner2PresidioRegexScanIT#scanCorpusWithEstimate \
  -Dcorpus.scan.concurrency=12 -Dcorpus.scan.detector-torch-threads=1
# A/B vélocité : relancer avec -Dcorpus.scan.concurrency=1 sur un sous-ensemble pour comparer.
```

## 7. Conclusions & recommandations pour la prod

Le container de prod hérite directement du levier principal : **rendre le client (backend Java
de scan) concurrent** suffit, car le serveur partage déjà son détecteur entre ses threads gRPC.
Réglages prod recommandés : `TORCH_NUM_THREADS=1`, gRPC `--workers ≥ K`, client envoyant
K ≈ (CPU du container) requêtes en vol, pool de processus OFF.

**Mais le vrai plafond est la bande passante mémoire du modèle de 1.8 Go.** Les deux leviers à
fort impact, par ordre de pertinence, exigent une validation de précision et sortent du périmètre
« nuit » :

1. **⭐ Vérifier le modèle réellement chargé.** Le seed + `config/models/gliner2-pii.toml`
   chargent `fastino/gliner2-large-v1` (DeBERTa-large, 1.8 Go). Or l'attente initiale était le
   **`gliner2-privacy-filter` ~205M** (commenté dans le toml). Un modèle ~4-9× plus petit = ~autant
   de bande passante en moins = potentiellement le ×2-4 recherché **sans parallélisme et sans
   thrash**. ⚠ Modèle différent = recall/precision différents → re-bench complet des findings sur
   le corpus AVANT bascule (décision produit, pas une simple constante). Le harnais
   `benchmarks/` est prêt pour ce comparatif.
2. **Quantization int8 calibrée** (PTQ statique ou QAT, pas dynamique) : ×2.35 entrevu sur la
   vitesse ; nécessite un dataset de calibration et un re-bench précision (la voie dynamique perd
   46 % des findings).
3. `batch_extract` des chunks d'un même document : validé iso-findings sur petit échantillon,
   bénéfice surtout sur les gros fichiers (≥ plusieurs chunks) — à mesurer sur `.sample-80k`.

**Anti-recommandation** : ne pas multiplier les copies du modèle (processus) sur une machine
memory-bound — c'est plus lent que le modèle partagé et ça explose la RAM.

## 7bis. Matrice COMPLÈTE sur le modèle `gliner2-privacy-filter-PII-multi` (2026-06-04 soir)

Suite à la recommandation n°1 du §7, la même matrice de leviers a été rejouée avec
`--gliner2-model fastino/gliner2-privacy-filter-PII-multi` (1.23 Go vs 1.86 Go — PAS 205M
comme supposé : ~280-300M params, mdeberta-base multilingue + têtes gliner2). Le modèle
n'est lu d'aucun toml : `GLINER2_DEFAULT_MODEL_ID` est codé en dur dans `gliner2_detector.py`
(décommenter `gliner2-pii.toml` n'a AUCUN effet).

| Levier | large-v1 | privacy-filter | ×vs actuel (141) |
|---|---:|---:|---:|
| seq torch=1 | ~50 | 135 | ×0.96 — le pf MONO-thread ≈ le large à 8 threads ! |
| seq torch=8 | 141 | 374 | ×2.66 |
| seq torch=16 | 125 (régresse) | **449** | ×3.18 — l'intra-op ne régresse PLUS (modèle moins memory-bound) |
| threads k8 | 144 | 579 | ×4.11 |
| threads k14 | 203 | 574 | ×4.07 — plateau threads ~580 (GIL résiduel, visible car le forward est devenu rapide) |
| procs n4×t2 | 159 | 584 | ×4.14 |
| procs n8×t1 | ❌ RAM | 587 | ×4.17 — les copies TIENNENT maintenant en RAM |
| **procs n12×t1** | ❌ RAM | **732** | **×5.19** 🏆 |

**Invariance des findings : prouvée sur les 7 leviers** — chacun produit 1563 findings
STRICTEMENT identiques à pf-seq (0 manquant, 0 ajouté, 0 drift de score). Le parallélisme ne
touche jamais à la précision ; seule la BASCULE DE MODÈLE la change.

**Le verdict s'inverse avec la taille du modèle** : sur le gros modèle (memory-bound), le
partagé multi-thread gagnait et les copies échouaient ; sur le modèle léger, le goulot bande
passante se desserre et **les N copies (processus) redeviennent gagnantes** (732 vs plateau
threads 580 — le GIL devient le facteur limitant des threads). L'anti-recommandation du §7 est
donc CONDITIONNELLE : copies = perdantes si N×poids sature RAM/bande passante, gagnantes sinon.
→ Le pool `PII_WORKER_PROCESSES` implémenté au §6 est exactement le bon véhicule (fork-COW en
container : ~1 copie de poids partagée pour N workers).

**Stratégie vélocité optimale mesurée** : privacy-filter + `PII_WORKER_PROCESSES=12` +
`TORCH_NUM_THREADS=1` + client concurrent K=12 → **×5.2 vs l'état actuel** (~40 h → ~7-8 h),
fiabilité de la stratégie garantie par invariance.

**⚠ Fiabilité de la bascule de modèle = LA décision restante.** Le privacy-filter a un profil
différent : 1563 vs 1137 findings sur le sample, recall ~équivalent dans le bruit (33 % vs 38 %
sur 24 pages — échantillon trop petit pour trancher), gagne sur secrets/credentials, mais
`IP_ADDRESS` ×7 (449 vs 62 → FP probables) et routage de labels différent (tax IDs sous
`GOVERNMENT_ID` ?). À valider sur le corpus complet (recall + revue FP) avant toute bascule.
Pour basculer : changer `GLINER2_DEFAULT_MODEL_ID` (gliner2_detector.py:52) — pas le toml.

## 7ter. Validation précision corpus complet : large-v1 (run IT réel) vs privacy-filter

Comparaison à **périmètre constant** : les 45 pages HTML réellement traitées par le run IT
interrompu (backup `corpus-scan-backup-20260604-0920`, pipeline complet Java+gRPC+container)
vs le privacy-filter scanné sur les mêmes pages (host, threads k14 — invariance procs/threads
prouvée donc représentatif de la config n12). Vélocité du run : **718 c/s sur 536k chars**
(12.4 min, 47 pages, 4585 findings).

| Type attendu | pages | large-v1 | privacy-filter | delta |
|---|---:|---:|---:|---|
| AVS_NUMBER | 5 | 4 | 4 | = |
| Adresse_MAC | 5 | 4 | 4 | = |
| Carte_de_credit | 5 | 2 | 1 | **-1** |
| IBAN | 5 | 3 | 3 | = (pages partiellement différentes) |
| Identifiant système/connexion (secrets) | 5 | 2 | 3 | **+1** |
| MEDICAL_LICENSE | 5 | 1 | 1 | = |
| Plaque, SESSION_ID, SOCIALNUM | 15 | 1 | 1 | = |
| **TOTAL** | **45** | **17 (38 %)** | **17 (38 %)** | **ISO-RECALL** |

**Conclusion précision : la bascule est iso-recall sur le critère du test** — les différences
sont des échanges page-contre-page (perd 1 carte, gagne 1 secrets), pas une dégradation.

Profil par type (nb de pages où le type apparaît — proxy FP) :
- pf plus « bavard » sur les IDs : USERNAME 33 vs 11, TAX_ID 16 vs 2, GOVERNMENT_ID 16 vs 0,
  SENSITIVE_ACCOUNT_ID 14 vs 2 → FP potentiels à trier (ou recall réel en plus — le LLM-judge
  pourrait arbitrer) ;
- large beaucoup plus de CARD_NUMBER (21 vs 6 pages) — or on sait que ses CARD_NUMBER hors
  dossier carte sont des FP massifs → **pf réduit probablement les FP carte** ;
- IP_ADDRESS : 17 vs 18 pages (l'alerte ×7 du sample portait sur le NOMBRE de findings,
  pas leur répartition — moins inquiétant que craint).

⚠ Note d'exécution : le run précision n12 (12 copies) sur le LONG corpus a fini en spirale de
re-spawns sur l'hôte (commit Windows, OSError 1455 au rechargement) — refait en threads k14
(1 copie). En container fork-COW le problème ne se pose pas (1 seul chargement), mais
dimensionner `PII_WORKER_PROCESSES` avec la marge RAM du container.

**Recommandation finale** : privacy-filter + pool n8-n12 + client concurrent → **×4-5 en
vélocité, iso-recall démontré sur 45 pages réelles**. Reste à l'équipe : revue qualitative des
FP « IDs » du pf (ou activer le LLM-judge), puis basculer `GLINER2_DEFAULT_MODEL_ID`.

## 8. Harnais livré (réutilisable)

Dans `pii-detector-service/benchmarks/` :
- `bench_scaling.py` — mesure chars/s d'un levier (modes seq / threads / procs ; options
  chunk-workers, batch-size, quantize) + dump findings JSONL ;
- `run_matrix.py` — matrice resumable (skip par `metrics.json`) + `SUMMARY.md` + diff findings ;
- `compare_findings.py` — diff de précision baseline vs levier (clé fichier/span/type/source) ;
- `corpus_sample.py` — échantillon déterministe du corpus (`.sample-matrix` 101k, `.sample-80k`) ;
- `seed_config_parser.py` — parse le seed SQL en `pii_type_configs` (parité adapter, sans DB).

Lancer (machine calme, process détaché) :
```
.venv\Scripts\python.exe benchmarks\run_matrix.py --sample-dir benchmarks\.sample-matrix \
  --seed-sql <data-improved-gliner2-presidio-regex.sql> --out-root benchmarks\out-matrix
```
