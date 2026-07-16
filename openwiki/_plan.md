# Plan de rafraîchissement OpenWiki (init/refresh)

L'openwiki existant (gitHead 3786584e) est exact et complet. Cette passe le
rafraîchit pour couvrir les features ajoutées jusqu'à HEAD 586641d0 et corriger
quelques faits périmés. Pas de refonte : édits chirurgicaux + mise à jour métadonnées.

## Deltas à intégrer (vérifiés dans le code)

1. **Inbox de labels découverts (MINISTRAL open-vocab)** — labels sans ligne
   `pii_type_config` droppés des findings + collectés dans `ministral_discovered_label`
   (init-script 015). Promote/ignore par opérateur. Preuves :
   - `init-scripts/015-ministral-discovered-label.sql`
   - `DiscoveredLabelController` (`/api/v1/pii-detection/discovered-labels`)
   - `DiscoveredLabelCollector` (gated `enabled`, défaut OFF ; le drop détecteur est indépendant)
   - `domain/pii/detection/DiscoveredLabel.java`, `DiscoveredLabelStatus.java`
   - Python : `infrastructure/adapter/in/grpc/pii_service.py` (filtre labels inconfigurés)
   - UI : `features/pii-settings/*`

2. **Config endpoint LM Studio en base** — `lm_studio_host`/`lm_studio_port` sur
   `pii_detection_config` (init-script 014-add-lm-studio-columns) lus par le détecteur
   pour bâtir `http://host:port/v1` (défaut localhost:1234). Preuves :
   - `init-scripts/014-add-lm-studio-columns.sql`
   - `database_config_adapter.py` (lecture défensive + fallback)
   - UI `pii-settings` + `pii-detection-config.model.ts`

3. **Postfilter credential plausibility** — `strategies/credential_plausibility.py`
   (zxcvbn + detect-secrets), enregistré sous tous les labels credential + PASSPHRASE ;
   alias `IPV4`→IP_ADDRESS, `CREDIT_CARD`→CardNumber (`registry.py`). Deps `pyproject.toml`.

4. **Suppression de FP au scan** — `ScanTimeFalsePositiveSuppressor` retire les
   détections déjà marquées FALSE_POSITIVE avant persistance ; `DashboardFalsePositiveFilter`
   les exclut du dashboard (lecture).

5. **Table scan_pii_type_counts** (init-script 013-scan-pii-type-counts) — compteurs
   agrégés par type PII et par espace.

6. **Correction faits** :
   - init-scripts : DEUX 013 (add-ministral-columns + scan-pii-type-counts) et DEUX 014
     (pii-finding-remediation + add-lm-studio-columns) + 015. Corriger la table backend-api.
   - `ScanStatus` n'a PAS de PENDING/QUEUED (vérifié) — le fix scan-status portait sur le
     scope + libellé de pause, ne pas inventer d'enum.
   - UI : seuil de confiance Ministral masqué (inerte, confidence=1.0).

## Pages à éditer
- architecture/backend-api.md : init-scripts table, REST (discovered-labels), contexte détection, FP suppressor.
- architecture/detection-service.md : postfilter (credential + aliases), LM Studio host/port, drop labels inconfigurés + inbox.
- architecture/frontend-ui.md : settings (discovered labels, LM Studio, seuil Ministral masqué).
- architecture/system-overview.md : config pilotée par base (mention LM Studio + discovered labels).
- workflows/scan-workflow.md : suppression FP au scan.
- quickstart.md : caveats (rien de majeur ; garder).
- .last-update.json → HEAD 586641d0.
- AGENTS.md / CLAUDE.md : vérifier section OpenWiki.
