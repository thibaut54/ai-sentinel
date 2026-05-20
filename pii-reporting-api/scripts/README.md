# LLM-as-judge — audit des findings PII

Deux scripts Python pour auditer les findings produits par
`CorpusDataSqlComparisonIT` (ou `DataSqlComparisonIT`) via un LLM local
servi par **LM Studio**, et quantifier le taux de faux positifs par
type de PII / page Confluence / attachement.

| Script | Usage |
|---|---|
| `llm_judge_sample.py` | Smoke-test rapide sur un échantillon stratifié (~20 findings, ~3 min) |
| `llm_judge_corpus.py` | Run complet par batchs, parallélisable, reprise sur interruption |

---

## 1. Prérequis

- **Python 3.11+** (stdlib uniquement, pas de pip install requis)
- **LM Studio** lancé avec :
  - Server activé (`Developer` → `Local Server` → port `1234`)
  - Le modèle **`qwen3.6-35b-a3b`** chargé (ou un autre Qwen3 compatible JSON Schema)
  - ⚠️ **Max Concurrent Predictions** : monter à `4` ou `8` dans
    `Developer → Local Server → Settings` pour bénéficier du parallélisme
    du script. Sinon les requêtes sont sérialisées par LM Studio même si
    le script envoie en parallèle.

Vérifier que LM Studio répond :

```powershell
curl http://127.0.0.1:1234/v1/models
```

---

## 2. Run smoke-test (`llm_judge_sample.py`)

```powershell
python pii-reporting-api/scripts/llm_judge_sample.py `
  --n-per-type 2 --max-total 20
```

Échantillonne 2 findings par `piiType`, affiche le résumé console et un
JSONL `target/llm-judge-sample.jsonl`. Pratique pour valider que LM Studio
répond et que le prompt fonctionne. ~3 min.

---

## 3. Run complet (`llm_judge_corpus.py`)

### Lancement nominal

```powershell
$env:PYTHONIOENCODING = "utf-8"
python pii-reporting-api/scripts/llm_judge_corpus.py `
  --input  pii-reporting-api/target/corpus-data-sql-comparison/baseline/findings.jsonl `
  --out-dir pii-reporting-api/target/llm-judge/baseline `
  --batch-size 20 `
  --workers 8
```

Itère sur tout le `findings.jsonl`, traite par batchs de 20, parallélise
8 requêtes (à condition que LM Studio soit configuré en conséquence) et
affiche le taux FP cumulé à chaque batch :

```
batch   time    cum   TP   FP   UN  FP%cum  notes
--------------------------------------------------------------------------------
    1 254.9s     20   10   10    0   50.0%  (20 findings juges)
    2 297.5s     40    0   20    0   75.0%  (20 findings juges)
    ...
```

### Mode test rapide

```powershell
python pii-reporting-api/scripts/llm_judge_corpus.py `
  --input  ...findings.jsonl `
  --out-dir target/llm-judge/baseline-test `
  --limit 40
```

`--limit N` borne à N findings (utile pour valider l'infra avant un run
long).

### Reprise après interruption

Si le script est interrompu (`Ctrl-C`, crash, coupure réseau), il
**suffit de relancer la même commande** : `verdicts.jsonl` est lu au
démarrage, les `finding_id` déjà jugés sont skippés, le run reprend là où
il s'était arrêté. Les compteurs cumulés sont reconstruits depuis
l'existant.

---

## 4. Fichiers produits

Tous dans `--out-dir` :

| Fichier | Contenu |
|---|---|
| `verdicts.jsonl` | 1 ligne / finding : tous les verdicts (TP, FP, UNSURE) avec contexte enrichi |
| `false_positives.jsonl` | 1 ligne / FP : sous-ensemble machine-friendly des FP seulement |
| `false_positives.md` | FP en markdown lisible : groupé par finding, avec URL Confluence cliquable, contexte avant/après et value mise en évidence `>>>value<<<` |
| `progress.log` | Trace batch-par-batch (timestamp, FP cumulé) |
| `summary.json` | Stats finales : total, taux FP global, breakdown par `piiType`, top 30 fichiers contributeurs de FP, durée |

### Exemple d'entrée `false_positives.md`

```markdown
### IP_ADDRESS — `10.217.4.11` (conf 0.95)

- **Page** : `Adresse_MAC/.../page.html`
- **Page Confluence** : Informations complémentaires SAGA Mobiles
- **URL** : https://val-dgnsiwiki.etat-de-vaud.ch/.../viewpage.action?pageId=...
- **Detector** : REGEX (score 0.980)
- **PII folder** : `Adresse_MAC`
- **Raison du juge** : L'adresse IP 10.x.x.x est une adresse privée RFC 1918
  utilisée pour l'infrastructure interne et ne constitue pas un PII personnel.

\`\`\`
...sagamobile.sae.vd.ch. 3600 IN A
>>> 10.217.4.11 <<<
sagap.sae.vd.ch. 3600 IN A 10.217.4.20...
\`\`\`
```

Pour vérification manuelle : ouvrir le `.md` dans un visualiseur (PyCharm,
VS Code, GitHub) → chaque FP a tout ce qu'il faut pour trancher en
quelques secondes (URL Confluence + contexte clair).

---

## 5. Options CLI complètes

```
--input          Chemin du findings.jsonl source (obligatoire)
--out-dir        Dossier de sortie, sera créé (obligatoire)
--lm-url         URL LM Studio (def: http://127.0.0.1:1234/v1/chat/completions)
--model          Nom du modèle LM Studio (def: qwen3.6-35b-a3b)
--batch-size     Findings par batch avant log progression (def: 20)
--workers        Requêtes parallèles dans un batch (def: 8)
--limit          Stop après N findings, 0 = tout (def: 0)
```

---

## 6. Choix du modèle

Recommandé : `qwen3.6-35b-a3b` (Q4_K_M, ~20 Go, MoE A3B = vitesse d'un 3B).

Modèles alternatifs disponibles sous LM Studio :
- `qwen3.6-35b-a3b-claude-4.7-opus-reasoning-distilled` : meilleur
  raisonnement mais beaucoup plus lent (`max_tokens` x5-10). À réserver
  pour un second pass ciblé sur les `UNSURE`.
- `huihui-qwen3.6-35b-a3b-claude-4.7-opus-abliterated` : déconseillé
  (abliterated dégrade légèrement le suivi d'instruction sans bénéfice
  pour ce use case).
- `detect-pii-4b-v2`, `distil-pii-llama-3.2-3b-instruct` : ce sont des
  **détecteurs** (extracteurs PII), pas des juges. Ne pas utiliser ici.

### Astuce Qwen3.6 — désactivation du mode thinking

Le prompt système commence par `/no_think` (token spécial Qwen3) pour
empêcher la chaîne de pensée d'absorber tout l'output. Le script gère
aussi le cas où la réponse arrive dans `reasoning_content` au lieu de
`content` (fallback).

---

## 7. Durée et coûts

Benchmark observé (40 findings, 4 workers, Qwen3.6-35B-A3B Q4_K_M, LM
Studio avec **Max Concurrent = 1**) : **552 s** → 13.8 s / finding.

Avec **Max Concurrent réellement à 4-8** dans LM Studio, on peut espérer
3-6 s / finding effectifs. Pour les 9 460 findings du corpus baseline :

| Config LM Studio | Durée estimée |
|---|---|
| Max Concurrent = 1 | ~36 h |
| Max Concurrent = 4 | ~9-12 h |
| Max Concurrent = 8 | ~5-7 h |

À adapter selon le GPU local. Le script étant reprenable, on peut lancer
de nuit et arrêter si besoin.

---

## 8. Limitations connues

1. **Échantillon stratifié vs proportionnel** : `llm_judge_sample.py`
   stratifie (1-2 par type) → taux FP biaisé vers les types rares.
   `llm_judge_corpus.py` traite **tout** le corpus → taux FP réel pondéré.
2. **Pas de gold-set** : le juge n'est pas calibré contre des verdicts
   humains. Pour publier des chiffres, prévoir une session manuelle de
   relecture de ~50 FP et 50 TP, puis calcul de Cohen's kappa.
3. **Encodage console Windows** : les accents s'affichent parfois `�`
   dans le terminal PowerShell. Les fichiers de sortie sont **UTF-8
   propre**. Définir `$env:PYTHONIOENCODING="utf-8"` avant l'exécution
   pour fixer l'affichage.
4. **Pas de jury multi-modèles** : on appelle un seul LLM. Pour un audit
   à enjeux, lancer un second run avec un autre modèle (Reasoning ou
   Llama-3.1-8B) et croiser les verdicts.
