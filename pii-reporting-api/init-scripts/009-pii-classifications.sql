-- ============================================================
-- Migration 009 : Classifications juridiques RGPD + nLPD
-- Reference mapping : docs/superpowers/specs/pii-classification-mapping.md
--
-- NOTE : ce script est execute par docker-entrypoint-initdb.d uniquement a
-- l'initialisation d'un volume Postgres vide. Sur un environnement existant
-- (prod), il doit etre applique manuellement ou via un outil de migration
-- versionne (Flyway/Liquibase a introduire dans un PR separe).
--
-- Prerequis Postgres >= 11 : ADD COLUMN NOT NULL DEFAULT ne reecrit pas la
-- table (metadata-only). Sur une version plus ancienne, ce script prend un
-- lock ACCESS EXCLUSIVE long.
-- ============================================================

-- 1. Ajouter les colonnes avec default conservateur (retrocompatibilite)
ALTER TABLE pii_type_config
    ADD COLUMN IF NOT EXISTS gdpr_classification VARCHAR(30) NOT NULL DEFAULT 'PERSONAL_DATA',
    ADD COLUMN IF NOT EXISTS nlpd_classification VARCHAR(30) NOT NULL DEFAULT 'PERSONAL_DATA';

-- ============================================================
-- SEED RGPD (Art. 9 / 10 / 6)
-- ============================================================

-- Art. 9 RGPD - SPECIAL_CATEGORY (sante, biometrie, genetique, opinions, origine)
UPDATE pii_type_config SET gdpr_classification = 'SPECIAL_CATEGORY'
WHERE pii_type IN (
    'NRP', 'PATIENT_ID', 'MEDICAL_RECORD_NUMBER', 'HEALTH_INSURANCE_NUMBER',
    'DIAGNOSIS', 'MEDICATION', 'MEDICAL_LICENSE', 'AU_MEDICARE',
    'BIOMETRIC', 'BIOMETRIC_DATA', 'FINGERPRINT', 'FACIAL', 'IRIS', 'VOICE',
    'DNA', 'GENETIC_DATA',
    'ETHNIC_ORIGIN', 'RELIGION', 'RELIGIOUS_BELIEF',
    'POLITICAL_OPINION', 'SEXUAL_ORIENTATION', 'TRADE_UNION_MEMBERSHIP'
);

-- Art. 10 RGPD - CRIMINAL_DATA
UPDATE pii_type_config SET gdpr_classification = 'CRIMINAL_DATA'
WHERE pii_type IN ('CRIMINAL_RECORD');

-- Art. 6 + 32 RGPD - PERSONAL_DATA_HIGH_RISK
UPDATE pii_type_config SET gdpr_classification = 'PERSONAL_DATA_HIGH_RISK'
WHERE pii_type IN (
    'SSN', 'AVS_NUMBER', 'NATIONAL_ID', 'PASSPORT_NUMBER', 'DRIVER_LICENSE_NUMBER',
    'CREDIT_CARD_NUMBER', 'CREDIT_CARD', 'BANK_ACCOUNT_NUMBER', 'IBAN', 'IBAN_CODE',
    'BIC_SWIFT', 'CRYPTO', 'TAX_ID', 'PASSWORD',
    'SOCIAL_ASSISTANCE_DATA', 'ADMINISTRATIVE_PROCEEDING',
    'US_SSN', 'US_BANK_NUMBER', 'US_DRIVER_LICENSE', 'US_ITIN', 'US_PASSPORT',
    'UK_NHS', 'UK_NINO',
    'ES_NIF', 'ES_NIE',
    'IT_FISCAL_CODE', 'IT_DRIVER_LICENSE', 'IT_PASSPORT', 'IT_IDENTITY_CARD',
    'PL_PESEL',
    'SG_NRIC_FIN',
    'AU_TFN',
    'IN_PAN', 'IN_AADHAAR', 'IN_VOTER', 'IN_PASSPORT',
    'FI_PERSONAL_IDENTITY_CODE',
    'KR_RRN',
    'TH_TNIN'
);

-- Les autres types gardent le default PERSONAL_DATA (incluant secrets techniques / contextuels)

-- ============================================================
-- SEED nLPD (Art. 5 let. a / c / g + Art. 8)
-- ============================================================

-- Art. 5 let. c nLPD - SENSITIVE_DATA (regroupe sante, biometrie, genetique,
-- opinions, origine ethnique, penal, admin, aide sociale)
UPDATE pii_type_config SET nlpd_classification = 'SENSITIVE_DATA'
WHERE pii_type IN (
    -- Sante (let. c ch. 2)
    'NRP', 'PATIENT_ID', 'MEDICAL_RECORD_NUMBER', 'HEALTH_INSURANCE_NUMBER',
    'DIAGNOSIS', 'MEDICATION', 'MEDICAL_LICENSE', 'AU_MEDICARE',
    -- Biometrie (let. c ch. 4) et genetique (let. c ch. 3)
    'BIOMETRIC', 'BIOMETRIC_DATA', 'FINGERPRINT', 'FACIAL', 'IRIS', 'VOICE',
    'DNA', 'GENETIC_DATA',
    -- Opinions et activites (let. c ch. 1)
    'RELIGION', 'RELIGIOUS_BELIEF', 'POLITICAL_OPINION', 'TRADE_UNION_MEMBERSHIP',
    -- Sphere intime (let. c ch. 2) - incluant origine ethnique
    'ETHNIC_ORIGIN', 'SEXUAL_ORIENTATION',
    -- Poursuites penales ET administratives (let. c ch. 5) - DIVERGENCE vs RGPD
    'CRIMINAL_RECORD', 'ADMINISTRATIVE_PROCEEDING',
    -- Aide sociale (let. c ch. 6) - UNIQUE nLPD
    'SOCIAL_ASSISTANCE_DATA'
);

-- Art. 8 nLPD - PERSONAL_DATA_HIGH_RISK
UPDATE pii_type_config SET nlpd_classification = 'PERSONAL_DATA_HIGH_RISK'
WHERE pii_type IN (
    'SSN', 'AVS_NUMBER', 'NATIONAL_ID', 'PASSPORT_NUMBER', 'DRIVER_LICENSE_NUMBER',
    'CREDIT_CARD_NUMBER', 'CREDIT_CARD', 'BANK_ACCOUNT_NUMBER', 'IBAN', 'IBAN_CODE',
    'BIC_SWIFT', 'CRYPTO', 'TAX_ID', 'PASSWORD',
    'US_SSN', 'US_BANK_NUMBER', 'US_DRIVER_LICENSE', 'US_ITIN', 'US_PASSPORT',
    'UK_NHS', 'UK_NINO',
    'ES_NIF', 'ES_NIE',
    'IT_FISCAL_CODE', 'IT_DRIVER_LICENSE', 'IT_PASSPORT', 'IT_IDENTITY_CARD',
    'PL_PESEL',
    'SG_NRIC_FIN',
    'AU_TFN',
    'IN_PAN', 'IN_AADHAAR', 'IN_VOTER', 'IN_PASSPORT',
    'FI_PERSONAL_IDENTITY_CODE',
    'KR_RRN',
    'TH_TNIN'
);

-- HIGH_RISK_PROFILING_DATA : reserve pour types futurs (pas de type PII direct v1)

-- Les autres types gardent le default PERSONAL_DATA

-- ============================================================
-- 4. Retirer le DEFAULT conservateur apres le seed pour forcer
-- les futurs INSERT a preciser explicitement une classification
-- et ainsi eviter qu'un nouveau pii_type cree via code retombe
-- silencieusement sur 'PERSONAL_DATA' (masque un oubli juridique).
-- ============================================================
ALTER TABLE pii_type_config
    ALTER COLUMN gdpr_classification DROP DEFAULT,
    ALTER COLUMN nlpd_classification DROP DEFAULT;

-- ============================================================
-- Index pour optimiser les filtres par classification
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_pii_type_config_gdpr
    ON pii_type_config(gdpr_classification);
CREATE INDEX IF NOT EXISTS idx_pii_type_config_nlpd
    ON pii_type_config(nlpd_classification);
