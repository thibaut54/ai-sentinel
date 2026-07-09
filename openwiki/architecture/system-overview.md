# Vue d'ensemble de l'architecture

AI Sentinel est une architecture **microservices** en trois services applicatifs plus une
base PostgreSQL et un gestionnaire de secrets (Infisical). Le point d'entrée général est
[../quickstart.md](../quickstart.md).

## Composants et responsabilités

```
Navigateur
   │  HTTP (/api → proxy dev → :8080/ai-sentinel/api)
   ▼
┌─────────────────────┐   gRPC :50051    ┌──────────────────────┐
│  pii-reporting-ui    │                  │   pii-detector        │
│  Angular 21 (PrimeNG)│                  │   Python (Presidio /  │
└─────────┬───────────┘                  │   Regex / Ministral)  │
          │ REST/SSE                      └──────────┬───────────┘
          ▼                                          │  (lit sa config)
┌─────────────────────┐   HTTP           ┌───────────▼───────────┐
│  pii-reporting-api   │◄──Confluence────►│     PostgreSQL 18      │
│  Spring Boot (Java)  │  (Cloud / DC)    │  scans, findings,      │
│  hexagonal           │                  │  config, audit, jobs   │
└─────────────────────┘                  └────────────────────────┘
```

- **UI** (`pii-reporting-ui/`) : dashboard des scans, réglages (détecteurs/seuils/types),
  écran d'obfuscation. Parle au backend en REST + SSE. Voir
  [frontend-ui.md](frontend-ui.md).
- **Backend** (`pii-reporting-api/`) : orchestre les scans, appelle le détecteur en gRPC,
  récupère le contenu Confluence, persiste les résultats, expose les rapports et pilote la
  remédiation. Voir [backend-api.md](backend-api.md).
- **Détecteur** (`pii-detector-service/`) : reçoit du texte, renvoie les entités PII. Lit sa
  configuration (détecteurs actifs, seuils par type) **directement en base**. Voir
  [detection-service.md](detection-service.md).
- **PostgreSQL** : unique source de vérité (résultats, config de détection, audit d'accès,
  jobs de caviardage, connexion Confluence chiffrée).

Diagramme officiel : `docs/ai-sentinel-components.drawio.svg` (source `.drawio` à côté).

## Contrat gRPC — `proto/pii_detection.proto`

Un seul service, un seul RPC (unaire) :

```protobuf
service PIIDetectionService {
  rpc DetectPII (PIIDetectionRequest) returns (PIIDetectionResponse) {}
}
```

- **`PIIDetectionRequest`** : `content` (string), `threshold` (float, optionnel),
  `fetch_config_from_db` (bool — quand vrai, le détecteur relit détecteurs/seuils en base
  avant de traiter).
- **`PIIDetectionResponse`** : `entities` (repeated `PIIEntity`),
  `summary` (`map<string,int32>` type→compte), `masked_content` (string),
  `discarded_entities` (rejets du post-filtre, pour mesurer la réduction de faux positifs),
  `detector_stats` (durée/compte par détecteur).
- **`PIIEntity`** : `text`, `type`, `type_label`, `start`, `end`, `score`, `source`.
- **`DetectorSource`** (enum) : `UNKNOWN_SOURCE=0`, `PRESIDIO=2`, `REGEX=3`, `PREFILTER=7`,
  `MINISTRAL=8`. Les tags 1/4/5/6 sont **réservés** (GLINER, OPENMED, GLINER2, JUDGE —
  retirés). Le champ `PIIEntity.field 8` (`judge_status`) est aussi réservé.

Le contrat est compilé côté Python (`pii_detector/proto/generate_pb.py`) et côté Java
(`protobuf-maven-plugin` sur `../proto`). Un stub betterproto historique
(`pii-detector-service/pii_detector/pii_detection.py`) est **obsolète/mort**.

> ⚠️ **Incohérence connue** : le proto n'a pas de valeur `POSTFILTER`, mais le domaine
> Python émet une stat `DetectorSource.POSTFILTER`. La sérialisation retombe alors sur
> `UNKNOWN_SOURCE`. À confirmer si le backend attend `PREFILTER`. Voir
> [detection-service.md](detection-service.md#post-filtre-déterministe).

## Flux de données de bout en bout

1. L'utilisateur lance un scan depuis l'UI → SSE ouvert sur le backend.
2. Le backend récupère les pages/pièces jointes de l'espace via le client Confluence,
   nettoie le HTML, extrait le texte des attachements (Tika/PDFBox/OCR).
3. Pour chaque contenu, appel gRPC `DetectPII` au détecteur (`fetch_config_from_db=true`).
4. Le détecteur exécute les détecteurs actifs, fusionne, post-filtre, renvoie les entités.
5. Le backend chiffre les valeurs sensibles, persiste les événements de scan (event
   sourcing) + checkpoints + compteurs de sévérité, et streame la progression à l'UI.
6. Pour la remédiation, le backend recalcule les findings à partir des événements, planifie
   et exécute des jobs qui réécrivent le stockage XHTML des pages Confluence.

Détails : [scan-workflow.md](../workflows/scan-workflow.md) et
[obfuscation-workflow.md](../workflows/obfuscation-workflow.md).

## Aspects transverses

### Chiffrement des données sensibles
Les valeurs PII et le token Confluence sont chiffrés **AES-256-GCM** avec dérivation de clé
HKDF-SHA256 par enregistrement (sel unique 256 bits, IV 96 bits, tag 128 bits, AAD liant les
métadonnées). Format de jeton : `ENC:v1:<sel>:<iv>:<ciphertext>`. La clé maître (KEK) vient
de l'env `PII_DATABASE_ENCRYPTION_KEY` (propriété `pii-encryption.kek-pii-encryption-key`).
Implémentation : `pii-reporting-api/.../infrastructure/pii/reporting/adapter/out/AesGcmEncryptionAdapter.java`,
port domaine `domain/pii/security/EncryptionService.java`.

> Perdre la KEK = données indéchiffrables. C'est un secret bootstrappé par Infisical.

### Sécurité & contrôle d'accès
Le backend désactive CSRF et utilise `permitAll()` (`SecurityConfig`) : **pas d'auth
applicative**, déploiement local/derrière passerelle supposé. Le contrôle repose sur :
- des **feature flags** (`pii.remediation.enabled`, `pii.reporting.allow-secret-reveal`),
- un **audit d'accès** aux PII en clair (table `pii_access_audit`, rétention nLPD 730 jours).

### Révélation du texte en clair (reveal)
Par défaut, l'UI ne voit que du texte masqué. Si `pii.reporting.allow-secret-reveal` est
activé, un reviewer peut révéler les valeurs en clair (pour juger des faux positifs) — chaque
révélation est **auditée**. Ce flag conditionne aussi ce que le contrat REST transporte
(`sensitiveValue` vs `maskedContext` seul).

### Configuration pilotée par la base
L'activation des détecteurs, les seuils (global + par type), le chunking Ministral et le
post-filtre vivent en **base** (`pii_detection_config` ligne id=1, `pii_type_config`), pas
dans des fichiers. Ils sont modifiables à chaud depuis l'écran Settings, sans redémarrage —
le détecteur les relit par requête via `fetch_config_from_db`.

## Stack technique (source de vérité : fichiers de build)

| Couche | Techno | Fichier |
|---|---|---|
| Frontend | Angular 21, PrimeNG 21, Transloco, pnpm, Vitest, Playwright | `pii-reporting-ui/package.json` |
| Backend | Spring Boot 4.0.0-M2, Java 25, Armeria (gRPC), JPA, jsoup/PDFBox/Tika, re2j | `pii-reporting-api/pom.xml` |
| Détecteur | Python (≥3.9), grpcio, transformers, presidio-analyzer, spaCy, python-stdnum | `pii-detector-service/pyproject.toml` |
| Base | PostgreSQL 18 | `docker-compose.yml` |

> Le `README.md` racine et certains READMEs de module donnent des versions périmées ; les
> fichiers de build ci-dessus font foi.
