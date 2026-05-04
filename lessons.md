# Project Lessons Learned — ai-sentinel

Lessons specifiques au projet ai-sentinel. Maintenu automatiquement apres corrections utilisateur.

Format :
```
### [SHORT_TITLE]
- **Contexte** : ce qui se passait
- **Erreur** : ce que Claude a fait de travers
- **Regle** : ce qu'il faut faire a la place
- **Exemple** : avant/apres concret (optionnel)
```

---

### DEV_CONTAINER_FIX_AT_CONTAINER_LEVEL_NOT_IDE_UI
- **Contexte** : POC Dev Container natif IntelliJ pour ai-sentinel. `pnpm install` echouait depuis IntelliJ avec un chemin Scoop Windows (`C:\...\scoop\...\pnpm.cmd`) malgre un dev container Linux fonctionnel. J'ai d'abord propose une procedure manuelle dans IntelliJ Settings (changer Node interpreter et Package manager via la GUI). L'utilisateur m'a interrompu : "le but d'un dev container c'est de ne pas avoir ce genre de chose a faire ! si on fix qqchose, c'est bien dans la config du dev container".
- **Erreur** : Avoir propose une procedure GUI multi-etapes dans IntelliJ comme s'il etait normal qu'un dev container demande une reconfiguration manuelle a chaque ouverture. C'est anti-pattern : un dev container doit "just work". Le vrai coupable etait un chemin absolu pollue dans `.idea/workspace.xml` (entree `nodejs_package_manager_path`) que IntelliJ avait ecrit lors d'une ouverture hors dev container.
- **Regle** : Quand un outil dans un dev container utilise un chemin de l'hote (Windows path dans un conteneur Linux), ne JAMAIS proposer de procedure de reconfiguration manuelle dans la GUI. Chercher d'abord d'ou vient le chemin pollue (settings projet versionnes, fichiers `.idea/`, `.vscode/`, settings utilisateur globaux qui ecrasent l'auto-detection). Le fix doit etre **automatique et auto-reparant** dans `.devcontainer/` (versionne, applique a chaque dev), pas un edit ponctuel d'un fichier que l'IDE peut reecrire a tout moment. Pattern recommande : un script de purge idempotent declenche par `postStartCommand` qui supprime les chemins polluant a chaque demarrage du conteneur.
- **Exemple** :
  - **Anti-pattern** : editer manuellement `.idea/workspace.xml` pour remplacer `C:\\...\\pnpm.cmd` par `pnpm` → IntelliJ desktop reecrit la valeur Scoop a la prochaine ouverture hors dev container, et la prochaine session conteneur retombe en panne.
  - **Bon pattern** : creer `.devcontainer/purge-host-paths.sh` qui fait `sed -i -E "/setting.*[A-Za-z]:/d" .idea/workspace.xml` pour les settings connus (`nodejs_package_manager_path`, `ts.external.directory.path`, etc.), et le cabler via `"postStartCommand": "bash .devcontainer/purge-host-paths.sh"` dans `devcontainer.json`. Le fix est versionne, idempotent, et resiste aux pollutions repetees.

### ALIGN_GLINER_CHUNKER_ON_INTERNAL_WHITESPACE_SPLITTER
- **Contexte** : Sur cette branche, des warnings `gliner/data_processing/processor.py:395 UserWarning: Sentence of length 432 has been truncated to 384` apparaissaient en masse pendant un scan, avec recall qui s'effondrait sur les chunks denses (URLs, identifiants, code). Le chunker upstream `TokenWindowChunker` decoupait pourtant le texte en fenetres bornees avant l'appel `predict_entities`, avec `chunk_size=500` subword tokens et un commentaire `~370 words, sweet spot under GLiNER 384-word limit`. L'utilisateur a pointe : "on devrait chunker et pas troncater non ?".
- **Erreur** : Avoir suppose que le `chunk_size` exprime en **subword tokens** (via le tokenizer DeBERTa de GLiNER) etait equivalent au comptage interne de GLiNER. En realite, `gliner_config.json` a `"max_len": 384` ET `"words_splitter_type": "whitespace"`, et la troncation dans `processor.py:490-493` est appliquee apres un split par `WhitespaceTokenSplitter` (regex `\w+(?:[-_]\w+)*|\S` — mots + ponctuation isolee). 500 subword tokens ≈ 380 mots **en moyenne** mais peut grimper a 430+ mots sur du texte dense → depassement de la limite a 384 → troncation silencieuse de la queue du chunk → entites perdues sans aucune erreur, juste un `UserWarning`.
- **Regle** : Quand on wrappe une librairie tierce qui applique un preprocessing interne (split, normalisation, tokenization, troncation), **lire le code source de la lib** pour aligner les parametres du wrapper sur l'unite exacte que la lib utilise. Pour GLiNER specifiquement : tout chunker upstream doit compter en **whitespace tokens** (pas en subwords) avec la regex exacte de `gliner.data_processing.tokenizer.WhitespaceTokenSplitter`, et `chunk_size` doit etre **strictement < `max_len`** lu depuis `gliner_config.json`. Une approximation `subword × ratio = words` est dangereuse car le ratio depend de la densite du texte (URLs, IDs, langues sans espaces, code source). La marge de securite minimale conseillee est 4 tokens whitespace (385+ pour `max_len=384` est un bug).
- **Exemple** :
  - **Anti-pattern** : `create_chunker(tokenizer=hf_subword_tokenizer, chunk_size=500, overlap=100)` avec un commentaire qui confond mots et subwords. La precision varie avec la densite → bug intermittent dependant des donnees.
  - **Bon pattern** : `WhitespaceWordWindowChunker(chunk_size=380, overlap=80)` qui re-utilise la regex `\w+(?:[-_]\w+)*|\S` de GLiNER directement. Garantie mathematique : aucun chunk ne depasse 380 whitespace tokens, donc strictement < `max_len=384`. Pas besoin de marge basee sur des stats empiriques.

### ALIGN_GLINER_LABELS_ON_FINETUNING_VOCABULARY_NOT_GENERIC_HEURISTICS
- **Contexte** : Conseil donne sur cette branche : "rendre les detector_label GLiNER plus descriptifs et plus specifiques pour reduire les faux positifs" (ex: `social insurance number` au lieu de `ssn`, `Swiss AVS 13-digit personal number` au lieu d'un label court). Cette heuristique avait ete appliquee aux ~50 entrees GLINER de `pii-reporting-api/src/main/resources/data.sql`. L'utilisateur a verifie le conseil via la model card NVIDIA et Perplexity et m'a confronte : "le llm gliner nvidia n'est-il pas entraine sur des labels bien precis ? rendant toute tentative d'optimisation par changement de label completement hasardeuse ?". La verif a confirme que `nvidia/gliner-PII` est fine-tune sur le dataset `nvidia/Nemotron-PII` avec un vocabulaire FIGE de 55+ labels en snake_case (`first_name`, `phone_number`, `ssn`, `street_address`, `medical_record_number`, etc.).
- **Erreur** : Avoir applique l'heuristique GLiNER generale ("labels descriptifs et specifiques pour des embeddings moins diffus") a un modele PII fine-tune. Cette heuristique vient de la doc GLiNER zero-shot (urchade/GLiNER) et du paper ACL 2024, mais elle ne s'applique PAS quand le modele a deja ete fine-tune sur un vocabulaire precis : l'encoder de labels a appris des embeddings TRES specifiques pour ces tokens vus a l'entrainement. Inventer `Swiss AVS 13-digit personal number` produit un embedding compositionnel partiellement hors-distribution dont le comportement est imprevisible (peut aider, ne rien faire, ou degrader). Le code Python (`multi_pass_gliner_detector.py:540-548`) prend le `detector_label` DB et le passe DIRECTEMENT a `predict_entities()`, donc la decision DB impacte directement la perf modele.
- **Regle** : Quand on integre un modele fine-tune (LLM, NER, classifier), AVANT toute "optimisation" des prompts/labels : (1) lire la model card pour identifier le **dataset d'entrainement**, (2) recuperer la liste exacte des labels/intents/categories du dataset (verbatim, casse exacte), (3) aligner le code applicatif sur ce vocabulaire. Une heuristique zero-shot ("plus descriptif = mieux") devient un anti-pattern quand le modele a vu un vocabulaire fixe. Pour les pii_types locaux (AVS suisse, NPA, formats nationaux non couverts par le dataset US/international), trois choix sains : (a) mapper sur le label fine-tune le plus proche + post-filtrer par regex/checksum, (b) desactiver le pii_type dans le detecteur fine-tune et le couvrir par REGEX/Presidio, (c) fine-tuner soi-meme. Inventer un label hors-vocabulaire est l'option a eviter par defaut.
- **Exemple** :
  - **Anti-pattern** : `INSERT ... VALUES ('AVS_NUMBER', 'GLINER', true, 0.80, 'MEDICAL', 'Swiss AVS 13-digit personal number', ...)` avec un modele fine-tune sur Nemotron-PII (qui n'a JAMAIS vu "Swiss" ni "AVS"). Le label compositionnel produit un embedding imprevisible.
  - **Bon pattern** : (a) consulter la model card -> liste des 55+ labels Nemotron-PII (snake_case), (b) mapper `AVS_NUMBER` -> pas de label exact -> desactiver en GLINER et activer la regex `('AVS_NUMBER', 'REGEX', true, 0.95, 'MEDICAL', 'avs number', ...)`. Mapper `SSN` -> `ssn` (exact match), `MEDICAL_RECORD_NUMBER` -> `medical_record_number` (exact match), `DRIVER_LICENSE_NUMBER` -> `certificate_license_number` (le plus proche dans le vocab). Eviter les collisions (deux pii_types vers le meme label cassent le mapping retour `label_mapping.get(gliner_label, ...)` dans `multi_pass_gliner_detector.py:555`).

### IT_TESTS_REUSE_PROD_PIPELINE_AND_FAIL_FAST_ON_WIRING
- **Contexte** : Premier jet de `CorpusBenchmarkIT` (test E2E pii-detector). J'avais deux choix d'implementation faciles mais traitres : (1) extraire le texte des fichiers HTML via `Tika.parseToString()` directement, et (2) ne pas asserter explicitement que les beans Spring + Testcontainers etaient bien up avant de lancer le test. L'utilisateur a corrige les deux points : "on veut utiliser le code qu'on a et qui clean le html, de maniere a pouvoir traverser un maximum de nos couche", puis "ajoute des logs et des assertions de debug dans le tests, pour qu'on soit sur que chaque element, le client grpc, etc, soient bien instancies et joignables".
- **Erreur** : Avoir traite l'IT comme un script de benchmark isole ("dans le doute je remplis le pre-traitement avec une lib tierce") au lieu d'un test qui doit *exercer la chaine de prod telle qu'elle est deployee*. Resultat : (a) le test mesurait la qualite de detection sur du texte nettoye par Tika, alors que la prod nettoie via `HtmlContentParser` (suppression des macros Confluence `<ac:parameter>`, conversion `<br>` -> `\n`, dedup espaces). Donc le bench mesurait un comportement different de celui qui sert en runtime. (b) Quand le smoke test echouait avec `DEADLINE_EXCEEDED`, impossible de savoir si c'etait un probleme de wiring (gRPC client non injecte / mauvais host:port), de connectivite (container down), ou de modele (inference qui depasse le timeout). Le diagnostic a coute 2-3 cycles d'iteration au lieu d'une seule erreur claire.
- **Regle** : Pour un test d'integration *destine a guider des decisions sur la qualite de prod* :
  1. **Reutiliser les composants de prod** pour tout le preprocessing (parsers, cleaners, normalisateurs, validators). Si un composant est `@Bean`, l'`@Autowired`. Eviter de re-implementer "vite fait" avec Tika/Jsoup/regex. Sinon le test mesure un pipeline fictif qui peut diverger silencieusement.
  2. **Bloc d'assertions de wiring en debut de test** qui valide chaque maillon avant de lancer le scenario metier. Pour chaque dependance critique : log son etat (`isRunning`, classe injectee, getHost/Port), puis `assertThat(...).isNotNull()/isTrue()`. Cela transforme une defaillance ambigu (timeout, NPE) en un diagnostic precis.
  3. **Ping fail-fast sur les dependances remote** : un appel minimal (mini-payload) avant le test reel. Si le canal est casse, on echoue en quelques secondes au lieu d'attendre le timeout d'inference. Le diagnostic differentie connectivite vs comportement applicatif.
- **Exemple** :
  - **Anti-pattern** :
    ```java
    @Test void bench() throws Exception {
        String text = new Tika().parseToString(file.toFile()); // pas comme en prod
        var detection = piiDetectorClient.analyzeContent(text);
        assertThat(detection.sensitiveDataFound()).isNotEmpty();
    }
    ```
    Si `piiDetectorClient` n'est pas injecte (NPE), si Tika produit autre chose que `HtmlContentParser`, ou si le container est down, on debug a l'aveugle.
  - **Bon pattern** :
    ```java
    @Test void smokeTest_singleFile() throws Exception {
        // Wiring assertions (~10 lignes de log + assertion par dependance)
        log.info("[SETUP] postgres running={}, jdbcUrl={}", postgres.isRunning(), postgres.getJdbcUrl());
        assertThat(postgres.isRunning()).isTrue();
        assertThat(piiDetector.isRunning()).isTrue();
        assertThat(piiDetectorClient).as("PiiDetectorClient autowired").isNotNull();
        assertThat(htmlContentParser).as("HtmlContentParser autowired").isNotNull();

        // Fail-fast remote ping
        var ping = piiDetectorClient.analyzeContent("ping@example.com");
        assertThat(ping).isNotNull();

        // Production pipeline (HtmlContentParser, pas Tika, pour le HTML)
        String cleaned = htmlContentParser.cleanText(Files.readString(file));
        var detection = piiDetectorClient.analyzeContent(cleaned);
        // ... assertions metier
    }
    ```
    Une defaillance pointe immediatement le maillon casse (autowiring, container, gRPC channel, inference) au lieu d'un timeout opaque.

### TESTCONTAINERS_IMAGE_FROM_DOCKERFILE_NEEDS_EXPLICIT_MINIMAL_CONTEXT
- **Contexte** : Ecriture d'un IT autonome `CorpusBenchmarkIT` qui demarre le pii-detector Python via Testcontainers `ImageFromDockerfile`. Premier essai utilisait `withFileFromPath(".", repoRoot)` pour pointer le contexte de build sur la racine du repo, comme on le fait avec un `docker build .` classique. Le test est reste plante 10+ minutes au log `Transferred 33 KB to Docker daemon` sans avancer. Cause : `pii-detector-service/models/` (3.4 GB) et `pii-detector-service/.venv/` (1.4 GB) — artefacts de dev non listes dans `.dockerignore` — etaient empiles dans le tar du contexte, qui fait 6+ GB total au lieu des ~2 MB de code source effectivement requis. La phase de tar Docker est silencieuse cote Testcontainers (juste un seul log "Transferred X KB" qui ne se rafraichit pas), donc le hang est tres trompeur.
- **Erreur** : Avoir suppose qu'`ImageFromDockerfile.withFileFromPath(".", parentDir)` se comporterait comme `docker build` au sens ou le `.dockerignore` serait honore. Testcontainers tar la totalite du chemin fourni en ignorant `.dockerignore` au niveau parent, donc tout artefact volumineux du repo est transfere dans le daemon meme s'il n'est jamais reference par le `Dockerfile`. Le symptome (silent hang, log fige sur "Transferred X KB") rend le diagnostic difficile sans tools comme `du -sh` sur le repo.
- **Regle** : Avec `ImageFromDockerfile`, ne JAMAIS pointer un repertoire large (repo root, module root) avec un seul `withFileFromPath`. Toujours **enumerer explicitement** les paths que le Dockerfile copie via plusieurs appels `withFileFromPath(<pathInContext>, <hostPath>)`. Dimensionner mentalement : "combien de MB le tar va peser ?" — si la reponse est > 100 MB, c'est probablement faux. Verifier avec `du -sh <chaque-dir-cible>` ou `Get-ChildItem -Recurse | Measure-Object Length -Sum`. Bonus : limiter le contexte accelere aussi le hash de cache Docker (la moindre modif d'un fichier dans le contexte invalide les layers suivants).
- **Exemple** :
  - **Anti-pattern** : `new ImageFromDockerfile().withFileFromPath(".", Paths.get("..").toAbsolutePath()).withDockerfilePath("pii-detector-service/Dockerfile")` -> tar de 6 GB, build hang 10+ min.
  - **Bon pattern** : enumerer les chemins du Dockerfile :
    ```java
    new ImageFromDockerfile()
        .withFileFromPath("pii-detector-service/Dockerfile", root.resolve("pii-detector-service/Dockerfile"))
        .withFileFromPath("pii-detector-service/pyproject.toml", root.resolve("pii-detector-service/pyproject.toml"))
        .withFileFromPath("pii-detector-service/pii_detector", root.resolve("pii-detector-service/pii_detector"))
        .withFileFromPath("pii-detector-service/config", root.resolve("pii-detector-service/config"))
        .withFileFromPath("proto", root.resolve("proto"))
        .withDockerfilePath("pii-detector-service/Dockerfile");
    ```
    Resultat : tar de 33 KB, build commence immediatement.

### EFFECTIVE_CONFIG_IS_LAYERED_GLOBAL_FLAGS_OVERRIDE_PER_ITEM_FLAGS
- **Contexte** : Audit qualite des detections PII sur un document de test. La table `pii_type_config` montrait 25 types avec `enabled=true` (REGEX/PRESIDIO/GLINER). J'ai d'abord analyse la couverture en supposant que ces 25 types etaient actifs. L'utilisateur m'a corrige : "prend en compte les settings qu'on a en db, tous les pii ne sont pas actif". La table parente `pii_detection_config` (id=1) avait `gliner_enabled=true, presidio_enabled=false, regex_enabled=false` — ces flags globaux desactivent en cascade tous les types dont le `detector` est REGEX ou PRESIDIO, meme si la ligne du type a `enabled=true`. Resultat : sur 25 types `enabled=true`, seulement 12 etaient effectivement actifs (les GLINER).
- **Erreur** : Avoir lu uniquement la table feuille (`pii_type_config.enabled`) sans verifier les flags globaux du detecteur dans `pii_detection_config`. C'est le pattern classique de la config a **plusieurs niveaux** ou un flag racine peut neutraliser des centaines de flags fils. Resultat : analyse de recall completement decalee (recall calcule sur 30 types attendus alors que seulement 12 sont actifs en pratique).
- **Regle** : Avant toute analyse de couverture/qualite d'un systeme de detection (PII, AV, IDS, regles metier, feature flags), enumerer **tous les niveaux de config** et calculer le set effectivement actif via la **conjonction** des flags. Pattern : `effective_enabled[type] = global[detector] AND per_type[type].enabled AND threshold_is_reachable`. Le set "enabled in DB" affiche par une jointure simple n'est PAS le set "actif au runtime". Pour un audit, ecrire la requete qui materialise ce set effectif AVANT de calculer recall/precision. Plus generalement : quand une table de config a une foreign key vers une table de "category settings" ou "global settings", ne jamais traiter la table feuille comme la verite — toujours faire le join et appliquer la regle de cascade.
- **Exemple** :
  - **Anti-pattern** : `SELECT pii_type FROM pii_type_config WHERE enabled=true` -> 25 lignes, analyse sur 25 types, recall = 11%. L'utilisateur conteste le resultat car la base affiche "trop de types actifs" par rapport a ce qu'il a configure.
  - **Bon pattern** : `SELECT t.pii_type FROM pii_type_config t JOIN pii_detection_config c ON c.id=1 WHERE t.enabled=true AND ((t.detector='GLINER' AND c.gliner_enabled) OR (t.detector='REGEX' AND c.regex_enabled) OR (t.detector='PRESIDIO' AND c.presidio_enabled))` -> 12 lignes, analyse sur 12 types, recall = 11% MAIS sur le bon denominateur. Et permet de voir que 7 types sont "vouluement enabled mais shadowed par flag global" -> action concrete possible : `UPDATE pii_detection_config SET regex_enabled=true` recupere 7 types gratuitement.

### DETECT_PACKAGE_MANAGER_BEFORE_WRITING_INSTALL_SCRIPT
- **Contexte** : POC Dev Container, setup.sh `postCreateCommand`. J'ai ecrit `npm ci --prefer-offline --no-audit` dans le script sans verifier le package manager utilise par le projet.
- **Erreur** : Assume npm par defaut. Le projet `pii-reporting-ui` utilise pnpm 10.12.1 (presence d'un `pnpm-lock.yaml` et champ `"packageManager": "pnpm@10.12.1"` dans package.json). Le `npm ci` aurait genere un `package-lock.json` parasite.
- **Regle** : Avant d'ecrire un script d'install de deps Node, **toujours** detecter le package manager via : (1) presence d'un lock file (`pnpm-lock.yaml`, `yarn.lock`, `bun.lockb`, `package-lock.json`), (2) champ `packageManager` dans `package.json`, (3) `.npmrc` qui force un manager. Ne pas se fier au nom de la commande (`npm install` peut signifier juste "install les deps", pas obligatoirement npm comme tool).
- **Exemple** : Pour pnpm, utiliser `corepack enable && corepack prepare pnpm@<version> --activate && pnpm install --frozen-lockfile` (gere automatiquement la version pnpm correcte).
