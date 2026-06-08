-- ============================================================================
-- IMPROVED-3 DATA SEED — for CorpusDataSqlComparisonIT#runImprovedV3WithOpenMed
-- ============================================================================
-- Base : data-improved-2.sql
-- Changements pilotes par les resultats LLM-as-judge sur le run "improved"
-- (D:\ai-sentinel-result-eval\output\improved\summary.json, 8961 verdicts) :
--
-- Corrections vs data-improved-2 :
--   GLINER NATIONAL_ID            true  -> false  (83 % FP)
--   GLINER BANK_ACCOUNT_NUMBER    true  -> false  (77.5 % FP)
--   GLINER BIC_SWIFT              true  -> false  (93.7 % FP)
--   GLINER PASSWORD               true  -> false  (89 % FP -> bascule OpenMed PASSWORD)
--   GLINER DEVICE_ID              true  -> false  (60 FP baseline)
--   GLINER HEALTH_INSURANCE_NUMBER true -> false  (0 TP / 1 FP)
--   GLINER VEHICLE_REGISTRATION   true  -> false  (0 TP, bascule OpenMed VRM)
--   PRESIDIO CREDIT_CARD          true  -> false  (43 % FP -> bascule OpenMed CREDITCARD)
--   PRESIDIO MEDICAL_LICENSE      false -> true   (62 % TP, gain net)
--
-- Ajout : bloc OPENMED restreint au COMPLEMENTAIRE des autres detecteurs.
--   Active : PASSWORD, CVV, PIN, IMEI, BITCOIN_ADDRESS, ETHEREUM_ADDRESS,
--            LITECOIN_ADDRESS, VEHICLE_VIN, VEHICLE_REGISTRATION (VRM),
--            ACCOUNT_NAME, BANK_ACCOUNT, CREDIT_CARD (en remplacement Presidio)
--   Desactive (deja bien couvert ailleurs ou bruit pur) :
--            IBAN (Presidio 0.6 % FP), IP_ADDRESS (Regex), MAC_ADDRESS (Regex),
--            BIC (Presidio), SSN, AMOUNT (bruit Excel), CURRENCY_CODE,
--            CREDIT_CARD_ISSUER, et toutes les categories CONTACT/IDENTITY
--            commodity (PERSON_NAME, EMAIL, PHONE, etc.).
-- ============================================================================

BEGIN;

ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS pii_type_config_pii_type_check;

-- openmed_enabled = true pour ce variant.
-- llm_judge_enabled = true : ce variant declenche le LLM-as-judge in-pipeline
-- (post-filtre FP). La portee des detecteurs audites est pilotee par l'env var
-- LLM_JUDGE_AUDIT_SOURCES sur le conteneur pii-detector (cf. CorpusDataSqlComparisonIT).
INSERT INTO pii_detection_config (id, gliner_enabled, presidio_enabled, regex_enabled, openmed_enabled, default_threshold, nb_of_label_by_pass, llm_judge_enabled, prefilter_enabled, updated_at, updated_by)
VALUES (1, false, true, true, true, 0.30, 35, true, false, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (id) DO NOTHING;

-- Category 1: IDENTITY
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PERSON_NAME',           'GLINER', false, 0.80, 'IDENTITY', 'name',                      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: NATIONAL_ID GLINER true -> false (judge 83 % FP)
    ('NATIONAL_ID',           'GLINER', false, 0.80, 'IDENTITY', 'national_id',               'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SSN',                   'GLINER', false, 0.80, 'IDENTITY', 'ssn',                       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',       'GLINER', false, 0.80, 'IDENTITY', NULL,                        'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER', 'GLINER', false, 0.80, 'IDENTITY', 'certificate_license_number','MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',         'GLINER', false, 0.80, 'IDENTITY', 'date_of_birth',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('GENDER',                'GLINER', false, 0.80, 'IDENTITY', 'gender',                    'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONALITY',           'GLINER', false, 0.80, 'IDENTITY', NULL,                        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',                   'GLINER', false, 0.80, 'IDENTITY', 'age',                       'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 2: CONTACT
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('EMAIL',        'GLINER', false, 0.80, 'CONTACT', 'email',         'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER', 'GLINER', false, 0.80, 'CONTACT', 'phone_number',  'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ADDRESS',      'GLINER', false, 0.80, 'CONTACT', 'address',       'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',         'GLINER', false, 0.80, 'CONTACT', 'city',          'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ZIP_CODE',     'GLINER', false, 0.80, 'CONTACT', 'postcode',      'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 3: DIGITAL
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('USERNAME',   'GLINER', false, 1,    'DIGITAL', 'user_name',   'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_ID', 'GLINER', false,  0.95, 'DIGITAL', 'customer_id', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',        'GLINER', false, 0.80, 'DIGITAL', 'url',         'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 4: FINANCIAL
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD_NUMBER',  'GLINER', false, 0.80, 'FINANCIAL', 'credit_debit_card', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: BANK_ACCOUNT_NUMBER GLINER true -> false (judge 77.5 % FP)
    ('BANK_ACCOUNT_NUMBER', 'GLINER', false, 0.90, 'FINANCIAL', 'account_number',    'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'GLINER', false, 0.80, 'FINANCIAL', 'iban',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: BIC_SWIFT GLINER true -> false (judge 93.7 % FP)
    ('BIC_SWIFT',           'GLINER', false, 0.90, 'FINANCIAL', 'swift_bic',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',              'GLINER', false, 0.80, 'FINANCIAL', 'tax_id',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'GLINER', false, 0.80, 'FINANCIAL', NULL,                'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 5: MEDICAL
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PATIENT_ID',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_RECORD_NUMBER',  'GLINER', false, 0.80, 'MEDICAL', 'medical_record_number',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: HEALTH_INSURANCE_NUMBER GLINER true -> false (0 TP / 1 FP)
    ('HEALTH_INSURANCE_NUMBER','GLINER', false, 0.80, 'MEDICAL', 'health_plan_beneficiary_number', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DIAGNOSIS',              'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICATION',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 6: IT_CREDENTIALS
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('IP_ADDRESS',   'GLINER', false, 0.80, 'IT_CREDENTIALS', 'ipv4',              'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',  'GLINER', false, 0.80, 'IT_CREDENTIALS', 'mac_address',       'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HOSTNAME',     'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: DEVICE_ID GLINER true -> false (label trop generique)
    ('DEVICE_ID',    'GLINER', false, 0.95, 'IT_CREDENTIALS', 'device_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: PASSWORD GLINER true -> false (judge 89 % FP, bascule OpenMed)
    ('PASSWORD',     'GLINER', false, 0.80, 'IT_CREDENTIALS', 'password',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',      'GLINER', false,  0.80, 'IT_CREDENTIALS', 'api_key',           'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN', 'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET_KEY',   'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SESSION_ID',   'GLINER', false, 0.80, 'IT_CREDENTIALS', 'http_cookie',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 7: LEGAL_ASSET
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CASE_NUMBER',             'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_NUMBER',          'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRIMINAL_RECORD',         'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: VEHICLE_REGISTRATION GLINER true -> false (bascule OpenMed VRM)
    ('VEHICLE_REGISTRATION',    'GLINER', false, 0.90, 'LEGAL_ASSET', 'vehicle_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',           'GLINER', false, 1,    'LEGAL_ASSET', 'license_plate',      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VIN',                     'GLINER', false, 0.90, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('INSURANCE_POLICY_NUMBER', 'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- PRESIDIO PII TYPES
-- ============================================================================

-- Universal
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    -- IMPROVED-3: CREDIT_CARD PRESIDIO true -> false (judge 43 % FP, bascule OpenMed CREDITCARD)
    ('CREDIT_CARD',     'PRESIDIO', false, 0.90, 'Financial', 'CREDIT_CARD',     'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN_CODE',       'PRESIDIO', true,  0.75, 'Financial', 'IBAN_CODE',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRYPTO',          'PRESIDIO', true,  0.80, 'Financial', 'CRYPTO',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',     'PRESIDIO', true,  0.80, 'Network',   'MAC_ADDRESS',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED-3: MEDICAL_LICENSE PRESIDIO false -> true (judge 62 % TP, gain net)
    ('MEDICAL_LICENSE', 'PRESIDIO', true,  0.90, 'Medical',   'MEDICAL_LICENSE', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NRP',             'PRESIDIO', true,  0.90, 'Personal',  'NRP',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Universal — disabled (commonly accepted PII or high FP)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('EMAIL_ADDRESS', 'PRESIDIO', false, 0.70, 'Contact',  'EMAIL_ADDRESS', 'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',  'PRESIDIO', false, 0.90, 'Contact',  'PHONE',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',           'PRESIDIO', false, 0.70, 'Contact',  'URL',           'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',    'PRESIDIO', false, 0.80, 'Network',  'IP_ADDRESS',    'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PERSON',        'PRESIDIO', false, 0.90, 'Personal', 'PERSON',        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LOCATION',      'PRESIDIO', false, 0.75, 'Location', 'LOCATION',      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_TIME',     'PRESIDIO', false, 0.75, 'Personal', 'DATE',          'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',           'PRESIDIO', false, 0.70, 'Personal', 'AGE',           'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Country-specific — ALL disabled (opt-in per tenant)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('US_SSN',            'PRESIDIO', false, 0.95, 'Government ID', 'US_SSN',            'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_BANK_NUMBER',    'PRESIDIO', false, 0.90, 'Financial',     'US_BANK_NUMBER',    'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_DRIVER_LICENSE', 'PRESIDIO', false, 0.90, 'Government ID', 'US_DRIVER_LICENSE', 'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_ITIN',           'PRESIDIO', false, 0.95, 'Government ID', 'US_ITIN',           'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'US_PASSPORT',       'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UK_NHS',            'PRESIDIO', false, 0.95, 'Government ID', 'UK_NHS',            'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UK_NINO',           'PRESIDIO', false, 0.95, 'Government ID', 'UK_NINO',           'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ES_NIF',            'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIF',            'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ES_NIE',            'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIE',            'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_FISCAL_CODE',    'PRESIDIO', false, 0.95, 'Government ID', 'IT_FISCAL_CODE',    'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_DRIVER_LICENSE', 'PRESIDIO', false, 0.90, 'Government ID', 'IT_DRIVER_LICENSE', 'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_VAT_CODE',       'PRESIDIO', false, 0.90, 'Financial',     'IT_VAT_CODE',       'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'IT_PASSPORT',       'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_IDENTITY_CARD',  'PRESIDIO', false, 0.90, 'Government ID', 'IT_IDENTITY_CARD',  'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PL_PESEL',          'PRESIDIO', false, 0.95, 'Government ID', 'PL_PESEL',          'MEDIUM', false, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SG_NRIC_FIN',       'PRESIDIO', false, 0.95, 'Government ID', 'SG_NRIC_FIN',       'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SG_UEN',            'PRESIDIO', false, 0.90, 'Business',      'SG_UEN',            'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_ABN',            'PRESIDIO', false, 0.90, 'Business',      'AU_ABN',            'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_ACN',            'PRESIDIO', false, 0.90, 'Business',      'AU_ACN',            'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_TFN',            'PRESIDIO', false, 0.95, 'Government ID', 'AU_TFN',            'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_MEDICARE',       'PRESIDIO', false, 0.95, 'Medical',       'AU_MEDICARE',       'HIGH',   false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_PAN',            'PRESIDIO', false, 0.90, 'Government ID', 'IN_PAN',            'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_AADHAAR',        'PRESIDIO', false, 0.95, 'Government ID', 'IN_AADHAAR',        'HIGH',   false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VEHICLE_REGISTRATION', 'PRESIDIO', false, 0.85, 'Government ID', 'IN_VEHICLE_REGISTRATION', 'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VOTER',          'PRESIDIO', false, 0.90, 'Government ID', 'IN_VOTER',          'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'IN_PASSPORT',       'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FI_PERSONAL_IDENTITY_CODE', 'PRESIDIO', false, 0.95, 'Government ID', 'FI_PERSONAL_IDENTITY_CODE', 'MEDIUM', false, 'FI', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('KR_RRN',            'PRESIDIO', false, 0.95, 'Government ID', 'KR_RRN',            'MEDIUM', false, 'KR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TH_TNIN',           'PRESIDIO', false, 0.95, 'Government ID', 'TH_TNIN',           'MEDIUM', false, 'TH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- REGEX PII TYPES — all enabled (high precision)
-- ============================================================================
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',         'REGEX', true, 0.95, 'MEDICAL',       'avs number',             'HIGH', false, 'CH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SOCIALNUM',          'REGEX', true, 0.75, 'IDENTITY',      'social security number', 'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_NUMBER', 'REGEX', true, 0.90, 'FINANCIAL',     'credit card number',     'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',       'REGEX', true, 0.90, 'CONTACT',       'PHONE_NUMBER',           'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'REGEX', true, 0.95, 'IT_CREDENTIALS','ip address',             'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'REGEX', true, 0.95, 'IT_CREDENTIALS','mac address',            'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',            'REGEX', true, 0.95, 'IT_CREDENTIALS','api key',                'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- OPENMED PII TYPES — restricted to complementary types
-- ============================================================================
-- detector_label MUST match the raw OpenMed model label exactly (no snake_case).
-- Enabled only where no better detector exists OR existing detector is broken
-- (PASSWORD GLINER 89 % FP, CREDIT_CARD PRESIDIO 43 % FP).
-- AMOUNT disabled (bruit Excel) ; IBAN/IP/MAC/BIC/SSN disabled (deja bien couverts).
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    -- Enabled : complementary to other detectors
    ('PASSWORD',           'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'PASSWORD',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CVV',                'OPENMED', true,  0.85, 'FINANCIAL',      'CVV',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PIN',                'OPENMED', true,  0.85, 'FINANCIAL',      'PIN',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IMEI',               'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'IMEI',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BITCOIN_ADDRESS',    'OPENMED', true,  0.85, 'FINANCIAL',      'BITCOINADDRESS',   'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ETHEREUM_ADDRESS',   'OPENMED', true,  0.85, 'FINANCIAL',      'ETHEREUMADDRESS',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LITECOIN_ADDRESS',   'OPENMED', true,  0.85, 'FINANCIAL',      'LITECOINADDRESS',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_VIN',        'OPENMED', true,  0.85, 'LEGAL_ASSET',    'VIN',              'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_REGISTRATION','OPENMED', true, 0.85, 'LEGAL_ASSET',    'VRM',              'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_NAME',       'OPENMED', true,  0.85, 'FINANCIAL',      'ACCOUNTNAME',      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT',       'OPENMED', true,  0.85, 'FINANCIAL',      'BANKACCOUNT',      'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD',        'OPENMED', true,  0.85, 'FINANCIAL',      'CREDITCARD',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- Disabled : already well covered by Presidio/Regex, or noise
    ('SSN',                'OPENMED', false, 0.85, 'IDENTITY',       'SSN',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',               'OPENMED', false, 0.85, 'FINANCIAL',      'IBAN',             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',          'OPENMED', false, 0.85, 'FINANCIAL',      'BIC',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_ISSUER', 'OPENMED', false, 0.85, 'FINANCIAL',      'CREDITCARDISSUER', 'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MASKED_NUMBER',      'OPENMED', false, 0.80, 'FINANCIAL',      'MASKEDNUMBER',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AMOUNT',             'OPENMED', false, 0.85, 'FINANCIAL',      'AMOUNT',           'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY',           'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCY',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_CODE',      'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCYCODE',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_NAME',      'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCYNAME',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_SYMBOL',    'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCYSYMBOL',   'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'OPENMED', false, 0.85, 'IT_CREDENTIALS', 'IPADDRESS',        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'OPENMED', false, 0.85, 'IT_CREDENTIALS', 'MACADDRESS',       'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PERSON_NAME',        'OPENMED', false, 0.80, 'IDENTITY',       'FIRSTNAME',        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EMAIL',              'OPENMED', false, 0.70, 'CONTACT',        'EMAIL',            'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE',              'OPENMED', false, 0.80, 'CONTACT',        'PHONE',            'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('USERNAME',           'OPENMED', false, 0.90, 'DIGITAL',        'USERNAME',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',                'OPENMED', false, 0.80, 'DIGITAL',        'URL',              'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',      'OPENMED', false, 0.80, 'IDENTITY',       'DATEOFBIRTH',      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',               'OPENMED', false, 0.80, 'CONTACT',        'CITY',             'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ADDRESS',            'OPENMED', false, 0.80, 'CONTACT',        'STREET',           'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ZIP_CODE',           'OPENMED', false, 0.80, 'CONTACT',        'ZIPCODE',          'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EYE_COLOR',          'OPENMED', false, 0.85, 'IDENTITY',       'EYECOLOR',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT (pii_type, detector) DO NOTHING;

COMMIT;
