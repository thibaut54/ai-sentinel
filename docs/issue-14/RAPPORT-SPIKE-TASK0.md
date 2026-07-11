# Rapport SPIKE Task 0 — Localisation des valeurs PII dans le XHTML storage Confluence (GO/NO-GO)

**Date** : 2026-07-05
**Verdict** : **GO** — matchedRate mesuré = **1.00 (56/56 paires mesurables)**, après deux corrections de stratégie découvertes pendant le spike (run initial : 0.92).

---

## 1. Méthode

Harnais Java autonome (`Spike.java`, jetable, hors repo) exécuté avec **exactement les bibliothèques de production** : jsoup **1.17.2** et re2j **1.8** tirés du `.m2` local (mêmes versions que `pii-reporting-api/pom.xml`).

Pour chaque paire (valeur PII semée, page XHTML storage) :

1. **Côté extraction (= ce que voit le détecteur)** : réplique **ligne à ligne** de `HtmlContentParser.cleanText` de production (parse HTML jsoup, suppression `ac:parameter`/`ac:image`/`ac:emoticon`/`ri:*`, marqueurs `\n` sur les blocs, `doc.text()`, normalisations re2j). La valeur « extraite » est la sous-chaîne exacte du texte nettoyé que le détecteur rapporterait (recherche normalisée, bornée par frontières de mot, jamais à travers un `\n`). Si la valeur n'apparaît pas dans le texte nettoyé → aucun finding possible → paire exclue du dénominateur (`DETECTOR_INVISIBLE`).
2. **Côté caviardage (= simulateur du futur `StorageContentRedactor`)** : parse du XHTML brut avec **jsoup en mode XML** (`Parser.xmlParser()`, `prettyPrint(false)`), collecte des nœuds texte en ignorant les sous-arbres non-contenu (`ac:parameter`, `ac:image`, `ac:emoticon`, `ri:*`), concaténation normalisée avec **séparateurs de bloc** (durs `\n` pour p/div/li/tr/h*/table/blockquote/pre/br/ac:rich-text-body/ac:layout-cell/ac:task-body ; souples pour td/th), matching de la valeur normalisée, **re-projection** du remplacement sur les nœuds d'origine (token `[PII]` dans le premier nœud touché, suppression des fragments dans les suivants). Jamais de regex sur la chaîne brute.
3. **Vérification** : ré-extraction production sur le XHTML caviardé → la valeur ne doit plus être extractible (comparaison bornée), le token doit apparaître, et le **nombre d'éléments du document doit être inchangé** (préservation de structure). Résidu de la valeur dans le markup (attributs) tracé séparément.

Garde-fous actifs pendant la mesure : longueur normalisée minimale **4**, frontières de mot (caractère non alphanumérique avant/après le match).

## 2. Provenance du corpus — honnêteté

**Corpus construit, pas réel.** 45 pages XHTML storage synthétiques (59 valeurs semées, toutes fictives), écrites pour ce spike dans `corpus/*.xhtml` :
- 2 squelettes repris des **seules fixtures storage réelles du repo** (`HtmlContentParserTest` : macro `create-from-template` avec `ac:parameter`, `ac:link` + `ri:*` + corps CDATA, macro `info` avec `ac:rich-text-body`) ;
- le reste modélisé sur la grammaire du storage format Confluence (macros structurées, layouts, task-lists, CDATA de macro code, entités, liens).

La base locale réelle ne contient que **3 événements `item`** avec valeurs **chiffrées** (AES-GCM) — inutilisable comme corpus sans déchiffrement, et trop mince statistiquement. Conséquence assumée : le taux mesuré vaut pour la **taxonomie de constructions couverte**, pas pour la distribution statistique d'un vrai espace Confluence. La couverture par catégorie (ci-dessous) est large ; le risque résiduel principal est documenté en §5.

## 3. Résultats

**matchedRate global : 1.00 (56/56 paires mesurables)** — seuil GO ≥ 0.85 largement atteint.

| Catégorie | Matched | Notes |
|---|---|---|
| plain-paragraph | 7/7 | emails, téléphones, AVS, IBAN |
| inline-split (strong/em/span/a au milieu de la valeur) | 5/5 | re-projection cross-nœuds OK |
| table-cell | 5/5 | y compris IBAN avec espaces + `<strong>` |
| cross-cell (valeur à cheval sur 2 `<td>`) | 1/1 | grâce au séparateur souple td/th (l'extraction prod joint les cellules par un espace) |
| list (ul/ol) | 4/4 | |
| macro-rich-text-body (info/panel) | 3/3 | `ac:parameter` intact |
| nbsp-phone (`&nbsp;`, `&#8239;`) | 3/3 | normalisation espaces exotiques |
| entities (`&amp;`, `&#39;`, `&eacute;`…) | 3/3 + 1/1 mixte | jsoup décode des deux côtés |
| task-body, layout, heading, blockquote, pre, br-lines, inline-code, emoticon-adjacent, punctuation, comment-split, deep-nesting, status-mention-adjacent | 12/12 | |
| multi-occurrence (3× même valeur, dont encodages mixtes nbsp/espace) | 2/2 | toutes occurrences remplacées |
| guard-word-boundary (pièges `marianna@…` ⊃ `anna@…`, `1756.…971` ⊃ `756.…97`) | 2/2 | zéro sur-caviardage |
| **cdata-link-body** (`ac:plain-text-link-body` CDATA) | 1/1 | après correction (voir §4) |
| **code-cdata** (macro `code`, CDATA multi-lignes) | 1/1 | après correction (voir §4) |

Hors dénominateur (aucun finding possible, vérifié empiriquement) :
- `macro-parameter` : valeur uniquement dans `ac:parameter` → supprimée avant détection en prod → invisible au détecteur ;
- `cross-block` : valeur à cheval sur deux `<p>` → `\n` dans le texte extrait → pas un finding mono-ligne ;
- `guard-min-length` : valeur de 2 caractères refusée par le garde-fou (comportement voulu : `SKIPPED`, pas de caviardage risqué).

## 4. Cas d'échec analysés (run initial à 0.92 → corrections)

1. **CDATA est extrait par la production** (découverte contre-intuitive). L'hypothèse « le parser HTML jsoup transforme `<![CDATA[…]]>` en commentaire → invisible » est **fausse** : `cleanText` de prod **surface le contenu CDATA** (corps de lien `ac:plain-text-link-body`, corps de macro `code`). Le redactor doit donc **inclure les `CDataNode`** dans le flux de matching (en mode XML jsoup, `CDataNode extends TextNode` ; la réécriture conserve l'enveloppe `<![CDATA[…]]>`, vérifié : `<![CDATA[[PII]]]>` reste bien parsé). Avant correction : 2 × `VALUE_NOT_FOUND`.
2. **Vérification post-caviardage naïve** : contrôler l'absence de la valeur par `contains` échoue quand une autre valeur la contient en sous-chaîne (`marianna@…` après caviardage de `anna@…`). La vérification (et le matching) doivent être **bornés par frontières de mot**. Le caviardage lui-même était correct ; c'était le contrôle qui était faux — piège à reproduire dans les tests du vrai composant.
3. **Résidus en attributs** (2 cas flaggés, non comptés en échec car invisibles au détecteur mais **réellement sensibles**) :
   - `<a href="mailto:support.rh@example.ch">` : le corps est caviardé, le `href` conserve l'email ;
   - `ac:parameter` de titre de macro dupliquant une valeur du corps (`title="Sophie Blanc"`).
   → Le vrai `StorageContentRedactor` doit ajouter une **passe attributs** ciblée (au minimum `href` `mailto:`/`tel:`, et valeur textuelle des `ac:parameter`) avec le même matching normalisé — remplacement du token dans l'attribut ou suppression de l'attribut selon le type.

## 5. Règles de normalisation retenues (des deux côtés)

- Décodage des entités **délégué au parser** (jsoup décode `&eacute;`, `&nbsp;`, `&#8239;`… dans les nœuds texte, même en mode XML) — ne jamais décoder à la main sur la chaîne brute.
- Espaces exotiques → espace simple : ` ` (nbsp), ` ` (nbsp fine), ` `, ` `, tab, CR.
- Caractères invisibles supprimés : `​`–`‍`, `﻿`, `­` (soft hyphen).
- **Effondrement des suites d'espaces** en un seul, pas d'espace de tête/queue.
- Matching **sensible à la casse** (valeur et storage issus de la même source).
- Séparateurs de bloc **durs** (`\n`, infranchissables) entre blocs p/div/li/tr/h*/table/blockquote/pre/br + conteneurs Confluence (`ac:rich-text-body`, `ac:layout-cell`, `ac:task-body`) ; **souples** (espace) entre `td`/`th` — aligné sur ce que produit l'extraction prod (les cellules sont jointes par un espace, donc un finding peut chevaucher deux cellules).
- Risque résiduel connu (non couvert par le corpus) : valeur dont le texte extrait diffère du storage par une transformation que la normalisation ne rattrape pas (ex. contenu généré par macro dynamique non présent dans le storage — TOC, include, excerpt). Ces cas produiront `SKIPPED_VALUE_NOT_FOUND`, jamais un caviardage erroné — le mode de défaillance est le bon.

## 6. Garde-fous recommandés (validés par la mesure)

- **Longueur normalisée minimale** configurable, défaut 4 (les valeurs plus courtes → `SKIPPED`, jamais tentées).
- **Frontières de mot** : caractère non alphanumérique (ou bord de flux) avant et après le match — indispensable (pièges sous-chaîne email et numérique démontrés).
- **Préservation de structure** : après réécriture, le nombre d'éléments du document doit être inchangé (assertion peu coûteuse contre toute corruption).
- Ne **jamais** toucher : contenu des `ac:parameter` (sauf passe attributs explicite §4.3), `ri:*`, `ac:image`, `ac:emoticon`.
- Valeur introuvable → `SKIPPED_VALUE_NOT_FOUND` + finding laissé `PENDING` (jamais d'approximation).

## 7. Algorithme recommandé pour `StorageContentRedactor`

1. Parse : `Jsoup.parse(storage, "", Parser.xmlParser())` + `prettyPrint(false)` (préserve `ac:`/`ri:`, CDATA, autofermants, espaces — le round-trip est fidèle).
2. Collecte en ordre document des `TextNode` (CDATA inclus), en sautant les sous-arbres non-contenu, en émettant les séparateurs de bloc durs/souples.
3. Construction d'un **flux normalisé avec table de correspondance** char-normalisé → (indexNœud, offsetBrut) ; les séparateurs et espaces effondrés sont non mappés.
4. `indexOf` de la valeur normalisée (toutes occurrences, sans chevauchement) + garde-fous.
5. **Re-projection** : par match, regrouper les positions par nœud → intervalle brut [min, max+1] par nœud ; token dans le nœud du début de match, fragments supprimés dans les suivants ; appliquer les éditions par nœud **en ordre décroissant d'offset**.
6. Passe attributs ciblée (`href` mailto/tel, `ac:parameter` textuels) avec le même matching normalisé.
7. Sérialisation `doc.outerHtml()`. Nota : jsoup ré-encode a minima (mode xhtml) — les entités nommées d'origine (`&eacute;`) ressortent en UTF-8 littéral, accepté par l'API Confluence (le body storage est de l'UTF-8) ; c'est un delta de forme, pas de contenu.
8. Caviardage multi-valeurs d'une même page : appliquer séquentiellement sur le même document (les tokens `[TYPE]` posés ne re-matchent pas les valeurs suivantes).

## 8. Verdict

**GO.** 56/56 sur la taxonomie couverte (≥ 0.85 requis), aucun sur-caviardage, mode de défaillance sûr (`SKIPPED_VALUE_NOT_FOUND`). Conditions à reporter dans l'implémentation réelle : inclusion des CDATA, passe attributs, garde-fous §6, et tests reproduisant chaque catégorie du corpus (les fichiers `corpus/*.xhtml` sont directement réutilisables comme fixtures de test).

**Artefacts** : `Spike.java` (harnais), `Debug.java` (inspection), `corpus/` (45 pages), `results.csv` (59 lignes), `results-summary.txt`.
