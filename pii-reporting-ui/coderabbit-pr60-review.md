# CodeRabbit Review - PR #60 - Feature/zero shot detection field

> **Date review**: 23 fevrier 2026
> **Threads**: 46 (tous non resolus sauf 3 obsoletes)
> **Effort estime**: ~120 minutes

---

## Checks Pre-Merge

| Check | Statut | Action |
|-------|--------|--------|
| Titre de la PR | ECHEC | Renommer en quelque chose de descriptif |
| Couverture docstrings | ECHEC (46% vs 80%) | Ignorer - pas pertinent pour notre stack |
| Description | OK | - |

---

## SECURITE (7 issues)

### S1 - Secrets hardcodes dans `.run/PROD.run.xml` (CRITIQUE)

**Probleme**: DB_PASSWORD, DB_USER, PII_DATABASE_ENCRYPTION_KEY et `ALLOW_SECRET_REVEAL=true` commites.

**Solution**: FAIT - Fichiers supprimes de l'historique git via `git filter-repo`. `.run/` ajoute au `.gitignore`. Reste a faire : rotation des secrets et force push.

---

### S2 - Secrets hardcodes dans `.run/server.run.xml` (CRITIQUE)

**Probleme**: Mots de passe BDD commites dans le fichier de run config.

**Solution**: FAIT - Meme traitement que S1.

---

### S3 - Risque SSRF sur URL Confluence (CRITIQUE)

**Probleme**: `ManageConfluenceConnectionUseCase.java:126` - L'URL Confluence fournie par l'utilisateur est utilisee directement pour des appels HTTP cote serveur sans validation.

**Solution envisagee**:
- Ajouter une validation dans le use case avant tout appel HTTP :
  - Imposer le schema HTTPS
  - Bloquer les IP privees/loopback (127.0.0.1, 10.x, 172.16-31.x, 192.168.x, [::1])
  - Optionnel : whitelist de domaines autorises (configurable)
- Creer un value object `ConfluenceBaseUrl` dans le domaine qui encapsule cette validation

---

### S4 - PII dans les logs INFO (`PiiContextExtractor.java:156`)

**Probleme**: Les logs INFO contiennent des slices brutes de PII et du contexte masque. Risque de fuite si le masquage est incomplet.

**Solution envisagee**:
- Passer ces logs en niveau DEBUG
- Remplacer les valeurs brutes par `hash(value) + length` dans les logs
- Exemple : `"Found PII: sha256=a1b2c3..., length=11"` au lieu de `"Found PII: 123-45-6789"`

---

### S5 - PII dans les logs (`ScanEventFactory.java:229`)

**Probleme**: Le fingerprint de contenu loggue les 20 premiers/derniers caracteres, pouvant contenir du PII.

**Solution envisagee**:
- Logger uniquement la longueur + un hash SHA-256 tronque
- Conditionner au niveau DEBUG
- `log.debug("Content fingerprint: length={}, hash={}", content.length(), sha256(content).substring(0, 8))`

---

### S6 - PII brut dans les logs (`ScanEventFactory.java:253`)

**Probleme**: Les logs de diagnostic de position exposent directement `data.value()` qui est du PII brut.

**Solution envisagee**:
- Remplacer `data.value()` par `redact(data.value())` (hash + longueur)
- Conditionner au niveau DEBUG

---

### S7 - Username "admin" hardcode (`ConfluenceConnectionConfigController.java:36`)

**Probleme**: `ADMIN_USERNAME = "admin"` hardcode pour l'audit trail.

**Solution envisagee**:
- Injecter le `Principal` depuis le `SecurityContextHolder` ou via parametre de methode `@AuthenticationPrincipal`
- Fallback sur "system" si pas d'authentification (cas batch/scheduler)
- `String updatedBy = SecurityContextHolder.getContext().getAuthentication().getName();`

---

## BUGS CRITIQUES (4 issues)

### B1 - Path mappings Jest invalides (CRITIQUE)

**Probleme**: `jest.config.ts:27` - Les mappings pour `primeng/multiselect` et `primeng/types/multiselect` pointent vers des fichiers potentiellement inexistants dans `node_modules`.

**Solution envisagee**:
- Verifier la structure reelle de PrimeNG 21.x dans `node_modules`
- Corriger les chemins ou supprimer les mappings si les imports ont change
- Tester avec `npm test -- --watch=false` apres correction

---

### B2 - `styleUrl` vs `styleUrls` (OBSOLETE)

**Probleme**: `confluence-settings.component.ts` - `styleUrl` (singulier) au lieu de `styleUrls` (pluriel).

**Solution**: Probablement deja corrige (marque outdated). A verifier : en Angular 17+, `styleUrl` (singulier, string) est supporte nativement. Pas d'action requise si on est en Angular 19.

---

### B3 - Path mappings TypeScript invalides (CRITIQUE)

**Probleme**: `tsconfig.spec.json:21` - Path mappings pour `@angular/common/http`, `primeng/multiselect`, `primeng/types/multiselect` pointent vers des `.d.ts` inexistants.

**Solution envisagee**:
- Supprimer les path mappings inutiles (PrimeNG 21.x a change sa structure d'exports)
- Ou creer les fichiers stub manquants si necessaire pour les tests
- Verifier que `npm test` passe sans ces mappings

---

### B4 - Dialog PII help sans handler `(onHide)` (CRITIQUE)

**Probleme**: `confluence-dashboard.component.html:308` - Le `p-dialog` manque un handler `(onHide)` pour synchroniser le signal quand l'utilisateur ferme le dialog via le bouton X ou `dismissableMask`.

**Solution envisagee**:
```html
<!-- Avant -->
<p-dialog [visible]="showPiiHelpDialog()" ...>

<!-- Apres -->
<p-dialog [visible]="showPiiHelpDialog()" (onHide)="showPiiHelpDialog.set(false)" ...>
```

---

## ARCHITECTURE & ROBUSTESSE BACKEND (4 issues)

### A2 - NPE sur `baseUrl()` (`ManageConfluenceConnectionPort.java:79`)

**Probleme**: `baseUrl().endsWith("/")` plante si `baseUrl` est null. Pas de validation sur les command records.

**Solution envisagee**:
- Ajouter un compact constructor avec validation dans les records :
```java
public record SaveConfluenceConnectionCommand(String baseUrl, String username, ...) {
    public SaveConfluenceConnectionCommand {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
    }
}
```

---

### A3 - `LocalDateTime` vs `OffsetDateTime` (`ConfluenceConnectionConfigEntity.java:73`)

**Probleme**: La colonne DB est `TIMESTAMP WITH TIME ZONE` mais l'entite utilise `LocalDateTime`, ce qui perd l'info timezone.

**Solution envisagee**:
- Remplacer `LocalDateTime` par `Instant` (prefere pour les timestamps d'audit)
- Ou `OffsetDateTime` si on a besoin de la timezone explicite
- Mettre a jour le mapper domain <-> entity en consequence

---

### A4 - Exception non wrappee (`model_manager.py:52`)

**Probleme**: `raise e` propage l'exception brute d'infra au lieu de la wrapper en `ModelLoadError`.

**Solution envisagee**:
```python
# Avant
except Exception as e:
    raise e

# Apres
except Exception as e:
    raise ModelLoadError(f"Failed to download model: {e}") from e
```

---

## CONFIGURATION & LOGGING (1 issue)

### C1 - Incoherence LOG_PATH/LOG_FILE (`logback-spring.xml:24`)

**Probleme**: Le pattern de rollover utilise `LOG_PATH` en dur mais `LOG_FILE` peut etre surcharge. Les logs actifs et archives peuvent finir dans des repertoires differents.

**Solution envisagee**:
- Utiliser une seule propriete `LOG_DIR` pour les deux (log actif et archives)
- Ou deriver le path du rollover a partir de `LOG_FILE`

---

## FRONTEND CSS / STYLES (12 issues)

### CSS1 - `::ng-deep` sans `:host` - fuite globale (MAJEUR, 6 occurrences)

**Fichiers concernes**:
- `app-shell.component.css:78` (`.header-theme-btn`, `.header-settings-btn`)
- `confluence-dashboard.component.css:264` (`.severity-badge-icon.p-badge`)
- `confluence-dashboard.component.css:390` (`.space-filters p-select`)
- `pii-settings.component.css:320` (multiples selecteurs PrimeNG)
- `pii-settings.component.css:452` (`.p-dialog-mask`, `.pii-search-input`, etc.)
- `pii-card-expanded.component.css:268`

**Solution envisagee**:
- Ajouter `:host` devant chaque `::ng-deep` pour scoper au composant
- Exemple : `::ng-deep .header-theme-btn` → `:host ::ng-deep .header-theme-btn`
- Pour les overrides PrimeNG vraiment globaux, deplacer dans `styles.css` global

---

### CSS2 - Stylelint va bloquer `::ng-deep` (`confluence-dashboard.component.css:29`)

**Solution envisagee**: Ajouter une config stylelint pour autoriser `::ng-deep` :
```json
// .stylelintrc
{ "rules": { "selector-pseudo-element-no-unknown": [true, { "ignorePseudoElements": ["ng-deep"] }] } }
```

---

### CSS3 - `z-index: 9999` trop large (`app-shell.component.css:89`)

**Probleme**: Affecte TOUS les toasts, pas seulement `scan-errors`.

**Solution envisagee**: Cibler avec `[key="scan-errors"]` :
```css
:host ::ng-deep .p-toast.p-component[key="scan-errors"] { z-index: 9999; }
```

---

### CSS4 - `overflow: hidden` tronque le dialog (`app-shell.component.css:142`)

**Solution envisagee**: Remplacer par `overflow-y: auto` pour permettre le scroll.

---

### CSS5 - Keyframe pas en kebab-case (`confluence-config-banner.component.css:11`)

**Solution envisagee**: Renommer `slideDown` en `slide-down`.

---

### CSS6 - Classe `.fw-600` = `font-weight: 500` (`confluence-dashboard.component.css:507`)

**Solution envisagee**: Renommer en `.fw-500` ou changer la valeur a `600`.

---

### CSS7 - `text-overflow: ellipsis` + `word-break: break-all` contradictoires (`pii-card-expanded.component.css:345`)

**Solution envisagee**: Garder `word-break: break-all` et retirer `text-overflow: ellipsis` (on veut afficher le texte complet, pas le tronquer).

---

## FRONTEND COMPOSANTS & TEMPLATES (5 issues)

### T1 - `aria-label` natif sur `<p-select>` (`language-selector.component.html:8`)

**Probleme**: L'attribut HTML `aria-label` ne se propage pas au combobox interne de PrimeNG.

**Solution envisagee**: Utiliser l'input PrimeNG `[ariaLabel]` :
```html
<!-- Avant -->
<p-select aria-label="Language">
<!-- Apres -->
<p-select [ariaLabel]="'Language'">
```

---

### T2 - Boutons icon-only sans `aria-label` (OBSOLETE)

**Probleme**: Boutons scan, pagination invisibles pour les lecteurs d'ecran.

**Solution**: Marque outdated, probablement deja corrige. A verifier.

---

### T3 - Alpha couleur fragile (`severity-cards.component.html:7`)

**Probleme**: `card.color + '33'` pour l'alpha dans le template.

**Solution envisagee**: Calculer `borderColor` en TypeScript dans le composant ou utiliser `color-mix()` CSS :
```css
border-color: color-mix(in srgb, var(--card-color) 20%, transparent);
```

---

### T4 - `<meter>` sans nom accessible (`confidence-indicator.component.html:4`)

**Solution envisagee**: Ajouter `aria-label` :
```html
<meter [value]="confidence" min="0" max="100" aria-label="Confidence level">
```

---

### T5 - `[class]` vs `[styleClass]` sur `p-tag` (`confluence-dashboard.component.html:209`)

**Probleme**: `[class]` met la classe sur l'element host, pas sur le `<span>` interne de PrimeNG. Les styles CSS ne matchent jamais.

**Solution envisagee**:
```html
<!-- Avant -->
<p-tag [class]="getStatusClass(status)">
<!-- Apres -->
<p-tag [styleClass]="getStatusClass(status)">
```

---

## FRONTEND SERVICES & LOGIQUE (2 issues)

### L1 - `document` direct dans `theme.service.ts:25` (MAJEUR)

**Probleme**: Acces direct a `document`, cassera en SSR.

**Solution envisagee**: Pas de SSR prevu pour l'instant (app interne). Ajouter un guard preventif :
```typescript
private document = inject(DOCUMENT);
```

---

### L2 - `prevPage()` peut generer une valeur negative (`confluence-dashboard.component.ts:297`)

**Solution envisagee**:
```typescript
prevPage() {
  this.first = Math.max(0, this.first - this.rows);
}
```

---

## INTERNATIONALISATION (6 issues)

### I1 - Labels de severite contradictoires EN (`en.json:30`) (MAJEUR)

**Probleme**: `severity.high = "Critical"` vs `piiItem.severity.high = "High"`.

**Solution envisagee**: Standardiser :
- `severity.critical` = "Critical"
- `severity.high` = "High"
- `severity.medium` = "Medium"
- `severity.low` = "Low"
- Supprimer le doublon et utiliser un seul jeu de cles partout

---

### I2 - Conflit `apiTokenRequired` vs placeholder (`en.json:208`) (MAJEUR)

**Probleme**: "API token is required" contredit "Leave empty to keep current token".

**Solution envisagee**: Rendre la validation conditionnelle :
- En creation : token requis
- En edition : token optionnel (garder l'existant si vide)
- Adapter le message de validation en consequence

---

### I3 - Cle `"PERSONAL"` manquante (`en.json:291`)

**Solution envisagee**: Ajouter `"PERSONAL": "Personal Information"`.

---

### I4 - "Gliner" hardcode dans `dialogTitle` (`en.json:604`)

**Solution envisagee**: Remplacer par "Add a custom label" (generique).

---

### I5 - Labels FR incoherents (`fr.json:30`)

**Probleme**: `severity.medium = "Eleve"` vs `piiItem.severity.medium = "Moyenne"`.

**Solution envisagee**: Aligner sur Critique / Haute / Moyenne / Faible.

---

### I6 - Accents manquants dans `fr.json` (2 occurrences)

**Probleme**: `fr.json:291` et `fr.json:638` - "personnalises" au lieu de "personnalises", "Creer" au lieu de "Creer", "Categorie" au lieu de "Categorie", etc.

**Solution envisagee**: Corriger tous les accents manquants dans les blocs `customLabels` et `piiCategories`.

---

## TESTS (1 issue)

### TS1 - Test `ContinueOnError` incomplet (`test_model_cache.py:99`)

**Probleme**: Verifie seulement le logging mais pas que le deuxieme modele a bien ete tente.

**Solution envisagee**: Ajouter l'assertion :
```python
assert mock_download.call_count == 2
```

---

## DOCUMENTATION (1 issue)

### D1 - Docstrings trompeuses (`model_config.py:22`)

**Probleme**: Docstrings mentionnent "load from env" et "validate" mais les methodes sont des no-ops.

**Solution envisagee**: Mettre a jour les docstrings pour refleter l'etat actuel (intentionnellement vide / placeholder).

---

## Resume & Priorisation

### Priorite 1 - Bloquants / Securite (a faire maintenant)

| # | Issue | Effort |
|---|-------|--------|
| S1/S2 | Secrets dans `.run/` | FAIT |
| B4 | Dialog `(onHide)` manquant | 2 min |
| T5 | `[class]` → `[styleClass]` sur p-tag | 2 min |
| L2 | `prevPage()` valeur negative | 2 min |
| I1/I5 | Labels severite contradictoires | 15 min |
| I2 | Validation token conditionnelle | 10 min |

### Priorite 2 - Securite backend (sprint suivant)

| # | Issue | Effort |
|---|-------|--------|
| S3 | Validation SSRF URL Confluence | 30 min |
| S4/S5/S6 | PII dans les logs | 20 min |
| S7 | Username hardcode | 10 min |
| A2 | NPE validation commands | 15 min |
| A3 | LocalDateTime → Instant | 15 min |

### Priorite 3 - CSS cleanup (a planifier)

| # | Issue | Effort |
|---|-------|--------|
| CSS1 | `::ng-deep` sans `:host` (6 fichiers) | 30 min |
| CSS2-7 | Corrections CSS mineures | 20 min |

### Priorite 4 - Nice to have

| # | Issue | Effort |
|---|-------|--------|
| T1/T4 | Accessibilite (`ariaLabel`) | 10 min |
| I3/I4/I6 | i18n corrections mineures | 15 min |
| A1 | Version PEP 440 | 2 min |
| A4 | Exception wrapping Python | 2 min |
| TS1 | Assertion test manquante | 2 min |
| D1 | Docstrings Python | 5 min |
| B1/B3 | Path mappings Jest/TS | 15 min |