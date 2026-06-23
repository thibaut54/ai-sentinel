BEGIN;

-- ============================================================================
-- Drop legacy CHECK constraint on pii_type column if it exists.
-- This constraint blocks custom PII types created via zero-shot detection.
-- ============================================================================
ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS pii_type_config_pii_type_check;

-- ============================================================================
-- PII Detection Global Config (Singleton with id=1)
-- llm_judge_enabled defaults to false (zero-effect MVP rollout, spec §1.4).
-- gliner2_enabled defaults to false (explicit operator opt-in, spec D4).
-- prefilter_enabled defaults to false (zero-effect rollout, deterministic format pre-filter).
-- *_judge_enabled default to false: per-detector LLM-judge routing is opt-in
-- (migration 012). llm_judge_enabled is the derived OR, maintained by the API.
-- ============================================================================
INSERT INTO pii_detection_config (id, gliner_enabled, presidio_enabled, regex_enabled, openmed_enabled, gliner2_enabled, default_threshold, nb_of_label_by_pass, llm_judge_enabled, prefilter_enabled, gliner_judge_enabled, presidio_judge_enabled, regex_judge_enabled, openmed_judge_enabled, gliner2_judge_enabled, ministral_enabled, ministral_chunk_size, ministral_overlap, ministral_judge_enabled, updated_at, updated_by)
VALUES (1, true, true, true, false, false, 0.30, 35, false, false, false, false, false, false, false, false, 1024, 128, false, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- DEFAULT CONFIGURATION POLICY
-- ============================================================================
--
-- ✅  ENABLED by default : high-risk identifiers (government IDs, financial,
--     medical, credentials, legal assets, account identifiers).
--
-- ⛔  DISABLED by default — "commonly accepted PII" that appears naturally in
--     professional documents and would generate excessive false positives if
--     flagged systematically.  This includes:
--       - Contact data   : email, phone, address, city, zip code
--       - Basic identity : person name, date of birth, gender, nationality, age
--       - Web artefacts  : URL, IP address
--       - Financials     : salary amount (contextually acceptable in HR docs)
--       - Country-specific Presidio types (US/UK/ES/IT/PL/SG/AU/IN/FI/KR/TH):
--         disabled until the tenant explicitly opts in for their jurisdiction.
--
-- Thresholds above 0.80 reflect higher-confidence requirements for types that
-- are prone to false positives on short tokens (vehicle IDs, login names, etc.).
-- ============================================================================

-- ============================================================================
-- GLINER PII TYPES
-- ============================================================================
-- IMPORTANT: detector_label values MUST match the official label vocabulary
-- the nvidia/gliner-PII model was trained on (Nemotron-PII dataset, snake_case).
-- Source: https://build.nvidia.com/nvidia/gliner-pii (UI label list)
--
-- pii_types without an exact NVIDIA label are disabled here (REGEX/PRESIDIO
-- handle them when applicable). Inventing descriptive labels yields
-- unpredictable embeddings and hurts precision.
-- ============================================================================

-- Category 1: IDENTITY
-- ✅ enabled  : NATIONAL_ID, SSN, DRIVER_LICENSE_NUMBER
-- ⛔ disabled : PERSON_NAME (commonly accepted PII), DATE_OF_BIRTH, GENDER, AGE
-- ⛔ unmapped : PASSPORT_NUMBER, NATIONALITY (no NVIDIA label — disabled)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PERSON_NAME',           'GLINER', false, 0.80, 'IDENTITY', 'name',                      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID',           'GLINER', true,  0.80, 'IDENTITY', 'national_id',               'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SSN',                   'GLINER', true,  0.80, 'IDENTITY', 'ssn',                       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',       'GLINER', false, 0.80, 'IDENTITY', NULL,                        'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER', 'GLINER', true,  0.80, 'IDENTITY', 'certificate_license_number','MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',         'GLINER', false, 0.80, 'IDENTITY', 'date_of_birth',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('GENDER',                'GLINER', false, 0.80, 'IDENTITY', 'gender',                    'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONALITY',           'GLINER', false, 0.80, 'IDENTITY', NULL,                        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',                   'GLINER', false, 0.80, 'IDENTITY', 'age',                       'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 2: CONTACT
-- ⛔ ALL disabled — email, phone and address coordinates are standard in
--    professional documents and should only be flagged when explicitly enabled.
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
-- ✅ enabled  : USERNAME (threshold 0.90 — short tokens, higher confidence needed),
--               ACCOUNT_ID
-- ⛔ disabled : URL — extremely common in technical documentation
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('USERNAME',   'GLINER', false,  1, 'DIGITAL', 'user_name',   'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_ID', 'GLINER', true,  0.80, 'DIGITAL', 'customer_id', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',        'GLINER', false, 0.80, 'DIGITAL', 'url',         'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 4: FINANCIAL
-- ⛔ SALARY disabled — no NVIDIA label, salary figures acceptable in HR/payroll docs
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD_NUMBER',  'GLINER', false, 0.80, 'FINANCIAL', 'credit_debit_card', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT_NUMBER', 'GLINER', true,  0.80, 'FINANCIAL', 'account_number',    'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'GLINER', false, 0.80, 'FINANCIAL', 'iban',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',           'GLINER', true,  0.80, 'FINANCIAL', 'swift_bic',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',              'GLINER', false, 0.80, 'FINANCIAL', 'tax_id',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'GLINER', false, 0.80, 'FINANCIAL', NULL,                'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 5: MEDICAL
-- ✅ enabled  : MEDICAL_RECORD_NUMBER, HEALTH_INSURANCE_NUMBER
-- ⛔ unmapped : AVS_NUMBER (REGEX handles it), PATIENT_ID, DIAGNOSIS, MEDICATION
--              (no NVIDIA label — domain-specific entities not in Nemotron-PII vocabulary)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PATIENT_ID',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_RECORD_NUMBER',  'GLINER', true,  0.80, 'MEDICAL', 'medical_record_number',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HEALTH_INSURANCE_NUMBER','GLINER', true,  0.80, 'MEDICAL', 'health_plan_beneficiary_number', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DIAGNOSIS',              'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICATION',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 6: IT_CREDENTIALS
-- ✅ enabled  : DEVICE_ID, PASSWORD, API_KEY, SESSION_ID (mapped to http_cookie)
-- ⛔ disabled : IP_ADDRESS (REGEX handles it), MAC_ADDRESS (REGEX handles it)
-- ⛔ unmapped : HOSTNAME, ACCESS_TOKEN, SECRET_KEY (would collide with api_key)
--              SESSION_ID approximated to http_cookie (closest NVIDIA label)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('IP_ADDRESS',   'GLINER', false, 0.80, 'IT_CREDENTIALS', 'ipv4',              'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',  'GLINER', false, 0.80, 'IT_CREDENTIALS', 'mac_address',       'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HOSTNAME',     'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DEVICE_ID',    'GLINER', true,  0.80, 'IT_CREDENTIALS', 'device_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',     'GLINER', true,  0.80, 'IT_CREDENTIALS', 'password',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',      'GLINER', true,  0.80, 'IT_CREDENTIALS', 'api_key',           'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN', 'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET_KEY',   'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SESSION_ID',   'GLINER', true,  0.80, 'IT_CREDENTIALS', 'http_cookie',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 7: LEGAL_ASSET
-- ✅ enabled  : VEHICLE_REGISTRATION, LICENSE_PLATE
-- ⛔ unmapped : CASE_NUMBER, CRIMINAL_RECORD, INSURANCE_POLICY_NUMBER (no NVIDIA label)
--              LICENSE_NUMBER (would collide with DRIVER_LICENSE_NUMBER on certificate_license_number)
--              VIN (would collide with VEHICLE_REGISTRATION on vehicle_identifier)
-- Threshold 0.90 for vehicle-related types (short tokens, high false-positive risk)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CASE_NUMBER',             'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_NUMBER',          'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRIMINAL_RECORD',         'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_REGISTRATION',    'GLINER', true,  0.90, 'LEGAL_ASSET', 'vehicle_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',           'GLINER', false,  1, 'LEGAL_ASSET', 'license_plate',      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VIN',                     'GLINER', false, 0.90, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('INSURANCE_POLICY_NUMBER', 'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- PRESIDIO PII TYPES
-- ============================================================================
-- ✅ enabled  : CREDIT_CARD, IBAN_CODE, CRYPTO, MAC_ADDRESS, MEDICAL_LICENSE, NRP
-- ⛔ disabled : contact (email, phone, URL), generic personal (person, age, location,
--               date), IP address, and ALL country-specific types (opt-in per tenant)

-- Universal — enabled
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD',     'PRESIDIO', true,  0.75, 'Financial', 'CREDIT_CARD',     'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN_CODE',       'PRESIDIO', true,  0.75, 'Financial', 'IBAN_CODE',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRYPTO',          'PRESIDIO', true,  0.80, 'Financial', 'CRYPTO',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',     'PRESIDIO', true,  0.80, 'Network',   'MAC_ADDRESS',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_LICENSE', 'PRESIDIO', false,  0.90, 'Medical',   'MEDICAL_LICENSE', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NRP',             'PRESIDIO', true,  0.90, 'Personal',  'NRP',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Universal — disabled (commonly accepted PII or high false-positive rate)
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

-- Country-specific — ALL disabled by default (opt-in per tenant jurisdiction)

-- USA
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('US_SSN',            'PRESIDIO', false, 0.95, 'Government ID', 'US_SSN',            'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_BANK_NUMBER',    'PRESIDIO', false, 0.90, 'Financial',     'US_BANK_NUMBER',    'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_DRIVER_LICENSE', 'PRESIDIO', false, 0.90, 'Government ID', 'US_DRIVER_LICENSE', 'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_ITIN',           'PRESIDIO', false, 0.95, 'Government ID', 'US_ITIN',           'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'US_PASSPORT',       'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- UK
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('UK_NHS',  'PRESIDIO', false, 0.95, 'Government ID', 'UK_NHS',  'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UK_NINO', 'PRESIDIO', false, 0.95, 'Government ID', 'UK_NINO', 'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Spain
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('ES_NIF', 'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIF', 'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ES_NIE', 'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIE', 'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Italy
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('IT_FISCAL_CODE',   'PRESIDIO', false, 0.95, 'Government ID', 'IT_FISCAL_CODE',   'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_DRIVER_LICENSE','PRESIDIO', false, 0.90, 'Government ID', 'IT_DRIVER_LICENSE','MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_VAT_CODE',      'PRESIDIO', false, 0.90, 'Financial',     'IT_VAT_CODE',      'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_PASSPORT',      'PRESIDIO', false, 0.95, 'Government ID', 'IT_PASSPORT',      'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_IDENTITY_CARD', 'PRESIDIO', false, 0.90, 'Government ID', 'IT_IDENTITY_CARD', 'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Poland
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('PL_PESEL', 'PRESIDIO', false, 0.95, 'Government ID', 'PL_PESEL', 'MEDIUM', false, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Singapore
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('SG_NRIC_FIN', 'PRESIDIO', false, 0.95, 'Government ID', 'SG_NRIC_FIN', 'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SG_UEN',      'PRESIDIO', false, 0.90, 'Business',      'SG_UEN',      'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Australia
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AU_ABN',      'PRESIDIO', false, 0.90, 'Business',      'AU_ABN',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_ACN',      'PRESIDIO', false, 0.90, 'Business',      'AU_ACN',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_TFN',      'PRESIDIO', false, 0.95, 'Government ID', 'AU_TFN',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_MEDICARE', 'PRESIDIO', false, 0.95, 'Medical',       'AU_MEDICARE', 'HIGH',   false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- India
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('IN_PAN',                  'PRESIDIO', false, 0.90, 'Government ID', 'IN_PAN',                  'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_AADHAAR',              'PRESIDIO', false, 0.95, 'Government ID', 'IN_AADHAAR',              'HIGH',   false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VEHICLE_REGISTRATION', 'PRESIDIO', false, 0.85, 'Government ID', 'IN_VEHICLE_REGISTRATION', 'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VOTER',                'PRESIDIO', false, 0.90, 'Government ID', 'IN_VOTER',                'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_PASSPORT',             'PRESIDIO', false, 0.95, 'Government ID', 'IN_PASSPORT',             'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Finland
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('FI_PERSONAL_IDENTITY_CODE', 'PRESIDIO', false, 0.95, 'Government ID', 'FI_PERSONAL_IDENTITY_CODE', 'MEDIUM', false, 'FI', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Korea
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('KR_RRN', 'PRESIDIO', false, 0.95, 'Government ID', 'KR_RRN', 'MEDIUM', false, 'KR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Thailand
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('TH_TNIN', 'PRESIDIO', false, 0.95, 'Government ID', 'TH_TNIN', 'MEDIUM', false, 'TH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- REGEX PII TYPES — all enabled (high precision, low false-positive rate)
-- ============================================================================
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',         'REGEX', true, 0.95, 'MEDICAL',       'avs number',         'HIGH', false, 'CH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SOCIALNUM',          'REGEX', true, 0.75, 'IDENTITY',      'social security number', 'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_NUMBER', 'REGEX', true, 0.90, 'FINANCIAL',     'credit card number',  'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',       'REGEX', true, 0.90, 'CONTACT',       'PHONE_NUMBER',        'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'REGEX', true, 0.95, 'IT_CREDENTIALS','ip address',          'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'REGEX', true, 0.95, 'IT_CREDENTIALS','mac address',         'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',            'REGEX', true, 0.95, 'IT_CREDENTIALS','api key',             'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- OPENMED PII TYPES
-- ============================================================================
-- Source: OpenMed/privacy-filter-multilingual
-- 24 labels mapped to canonical pii_type. 12 others kept for UI visibility.
-- ============================================================================

INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    -- Enabled by default (per Thibaut's decision)
    -- NOTE: detector_label MUST match the raw OpenMed model label exactly (no
    -- snake_case) — it is the key used by the inference pipeline to map model
    -- outputs back to the canonical pii_type. pii_type itself stays in the
    -- project-wide snake_case convention for cross-detector consistency.
    ('SSN',                'OPENMED', true,  0.85, 'IDENTITY',       'SSN',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_NAME',       'OPENMED', true,  0.85, 'FINANCIAL',      'ACCOUNTNAME',      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT',       'OPENMED', true,  0.85, 'FINANCIAL',      'BANKACCOUNT',      'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',               'OPENMED', true,  0.85, 'FINANCIAL',      'IBAN',             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',          'OPENMED', true,  0.85, 'FINANCIAL',      'BIC',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD',        'OPENMED', true,  0.85, 'FINANCIAL',      'CREDITCARD',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_ISSUER', 'OPENMED', true,  0.85, 'FINANCIAL',      'CREDITCARDISSUER', 'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CVV',                'OPENMED', true,  0.85, 'FINANCIAL',      'CVV',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PIN',                'OPENMED', true,  0.85, 'FINANCIAL',      'PIN',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MASKED_NUMBER',      'OPENMED', false, 0.80, 'FINANCIAL',      'MASKEDNUMBER',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AMOUNT',             'OPENMED', true,  0.85, 'FINANCIAL',      'AMOUNT',           'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY',           'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCY',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_CODE',      'OPENMED', true,  0.85, 'FINANCIAL',      'CURRENCYCODE',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_NAME',      'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCYNAME',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CURRENCY_SYMBOL',    'OPENMED', false, 0.85, 'FINANCIAL',      'CURRENCYSYMBOL',   'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BITCOIN_ADDRESS',    'OPENMED', true,  0.85, 'FINANCIAL',      'BITCOINADDRESS',   'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ETHEREUM_ADDRESS',   'OPENMED', true,  0.85, 'FINANCIAL',      'ETHEREUMADDRESS',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LITECOIN_ADDRESS',   'OPENMED', true,  0.85, 'FINANCIAL',      'LITECOINADDRESS',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_VIN',        'OPENMED', true,  0.85, 'LEGAL_ASSET',    'VIN',              'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_REGISTRATION','OPENMED',true,  0.85, 'LEGAL_ASSET',    'VRM',              'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'IPADDRESS',        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'MACADDRESS',       'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IMEI',               'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'IMEI',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',           'OPENMED', true,  0.85, 'IT_CREDENTIALS', 'PASSWORD',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- Disabled by default (visible in UI but inactive)
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

-- ============================================================================
-- GLINER2 PII TYPES
-- ============================================================================
-- Multi-task zero-shot detector (lib gliner2, model fastino/gliner2-large-v1).
-- Global kill-switch pii_detection_config.gliner2_enabled = FALSE (spec D4 —
-- the detector does not run until an operator enables it globally).
-- Per-type defaults: GOVERNMENT_ID / FINANCIAL / DIGITAL / SECURITY enabled;
-- IDENTITY / CONTACT / TEMPORAL disabled (per product taxonomy).
-- threshold = 0.50 (recalibrated for GLiNER2; scores NOT comparable to GLiNER's
-- 0.30/0.80 — spec §4.6, to be tuned via the offline FR harness).
--
-- detector_label        : the entity label sent to GLiNER2 (schema key).
--                         snake_case validated on the POC (person_name, email...).
-- detector_description   : the natural-language inference description (schema
--                         value), rendered in FR/Swiss context (spec §2.4).
--                         Editable in the UI; NULL/empty falls back to the label.
-- ============================================================================

-- Taxonomie GLiNER2-PII complete (42 labels natifs du modele).
-- Active par defaut UNIQUEMENT : GOVERNMENT_ID, FINANCIAL, DIGITAL, SECURITY.
-- Desactive par defaut : IDENTITY (person/names), CONTACT (contact/address),
-- TEMPORAL (sensitive dates).

-- Category: IDENTITY (person / names) -- desactive par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PERSON',        'GLINER2', false, 0.50, 'IDENTITY', 'person',        'nom d''une personne (prenom et/ou nom)',  'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FULL_NAME',     'GLINER2', false, 0.50, 'IDENTITY', 'full_name',     'nom complet d''une personne (prenom + nom)', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FIRST_NAME',    'GLINER2', false, 0.50, 'IDENTITY', 'first_name',    'prenom d''une personne',                  'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MIDDLE_NAME',   'GLINER2', false, 0.50, 'IDENTITY', 'middle_name',   'deuxieme prenom d''une personne',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LAST_NAME',     'GLINER2', false, 0.50, 'IDENTITY', 'last_name',     'nom de famille d''une personne',          'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH', 'GLINER2', false, 0.50, 'IDENTITY', 'date_of_birth', 'date de naissance d''une personne',       'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: CONTACT (contact / address) -- desactive par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('EMAIL',           'GLINER2', false, 0.50, 'CONTACT', 'email',           'adresse e-mail',                                       'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',    'GLINER2', false, 0.50, 'CONTACT', 'phone_number',    'numero de telephone (format suisse ou international)', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ADDRESS',         'GLINER2', false, 0.50, 'CONTACT', 'address',         'adresse postale complete',                             'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STREET_ADDRESS',  'GLINER2', false, 0.50, 'CONTACT', 'street_address',  'rue et numero d''une adresse postale',                 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',            'GLINER2', false, 0.50, 'CONTACT', 'city',            'nom de ville ou commune',                              'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STATE_OR_REGION', 'GLINER2', false, 0.50, 'CONTACT', 'state_or_region', 'canton, etat ou region',                               'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('POSTAL_CODE',     'GLINER2', false, 0.50, 'CONTACT', 'postal_code',     'code postal (NPA suisse a 4 chiffres ou international)', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COUNTRY',         'GLINER2', false, 0.50, 'CONTACT', 'country',         'nom de pays',                                          'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: GOVERNMENT_ID (government / tax IDs) -- ACTIVE par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('GOVERNMENT_ID',          'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'government_id',          'numero d''identification delivre par une autorite publique', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID_NUMBER',     'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'national_id_number',     'numero de carte d''identite nationale',                      'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',        'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'passport_number',        'numero de passeport',                                        'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVERS_LICENSE_NUMBER', 'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'drivers_license_number', 'numero de permis de conduire',                               'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_NUMBER',         'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'license_number',         'numero de licence ou d''autorisation professionnelle',       'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',                 'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'tax_id',                 'identifiant fiscal d''une personne ou entreprise',           'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_NUMBER',             'GLINER2', true, 0.50, 'GOVERNMENT_ID', 'tax_number',             'numero fiscal (ex. numero IDE/TVA suisse)',                  'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: FINANCIAL (banking / payment) -- ACTIVE par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('BANK_ACCOUNT',   'GLINER2', true, 0.50, 'FINANCIAL', 'bank_account',   'compte bancaire',                                            'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_NUMBER', 'GLINER2', true, 0.50, 'FINANCIAL', 'account_number', 'numero de compte bancaire',                                  'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ROUTING_NUMBER', 'GLINER2', true, 0.50, 'FINANCIAL', 'routing_number', 'numero d''acheminement bancaire (clearing/ABA)',             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',           'GLINER2', true, 0.50, 'FINANCIAL', 'iban',           'numero de compte bancaire international IBAN (commence par CH pour la Suisse)', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PAYMENT_CARD',   'GLINER2', true, 0.50, 'FINANCIAL', 'payment_card',   'carte de paiement (credit ou debit)',                        'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CARD_NUMBER',    'GLINER2', true, 0.50, 'FINANCIAL', 'card_number',    'numero de carte de paiement (PAN a 13-19 chiffres)',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CARD_EXPIRY',    'GLINER2', true, 0.50, 'FINANCIAL', 'card_expiry',    'date d''expiration d''une carte de paiement (MM/AA)',         'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CARD_CVV',       'GLINER2', true, 0.50, 'FINANCIAL', 'card_cvv',       'code de securite d''une carte de paiement (CVV/CVC 3-4 chiffres)', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: DIGITAL (digital identity) -- ACTIVE par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('USERNAME',             'GLINER2', true, 0.50, 'DIGITAL', 'username',             'identifiant de connexion ou nom d''utilisateur',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',           'GLINER2', true, 0.50, 'DIGITAL', 'ip_address',           'adresse IP (IPv4 ou IPv6)',                               'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_ID',           'GLINER2', true, 0.50, 'DIGITAL', 'account_id',           'identifiant de compte utilisateur',                       'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SENSITIVE_ACCOUNT_ID', 'GLINER2', true, 0.50, 'DIGITAL', 'sensitive_account_id', 'identifiant de compte sensible (bancaire, sante, etc.)',  'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: SECURITY (secrets / credentials) -- ACTIVE par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PASSWORD',      'GLINER2', true, 0.50, 'SECURITY', 'password',      'mot de passe ou code secret',                  'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET',        'GLINER2', true, 0.50, 'SECURITY', 'secret',        'valeur secrete ou confidentielle',             'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',       'GLINER2', true, 0.50, 'SECURITY', 'api_key',       'cle d''API ou jeton de service',               'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN',  'GLINER2', true, 0.50, 'SECURITY', 'access_token',  'jeton d''acces d''authentification',           'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RECOVERY_CODE', 'GLINER2', true, 0.50, 'SECURITY', 'recovery_code', 'code de recuperation ou de secours d''un compte', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: TEMPORAL (sensitive dates) -- desactive par defaut
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('SENSITIVE_DATE',   'GLINER2', false, 0.50, 'TEMPORAL', 'sensitive_date',   'date sensible liee a une personne ou un dossier',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DOCUMENT_DATE',    'GLINER2', false, 0.50, 'TEMPORAL', 'document_date',    'date d''emission ou de redaction d''un document',  'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EXPIRATION_DATE',  'GLINER2', false, 0.50, 'TEMPORAL', 'expiration_date',  'date d''expiration d''un document ou d''un droit', 'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TRANSACTION_DATE', 'GLINER2', false, 0.50, 'TEMPORAL', 'transaction_date', 'date d''une transaction',                          'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- Ministral-PII detector seed (69 entities, 8 categories).
-- Specialised LLM detector: enabled by default at first install (all types active), threshold 0.50 neutral (no exploitable per-entity score),
-- llm_judge_enabled=false on EVERY row (permanently exempt from the judge).
-- ============================================================================

-- Category: IDENTITY (Identity & demographics) -- 17 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('FIRST_NAME',           'MINISTRAL', true, 0.50, 'IDENTITY', 'first_name',           NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LAST_NAME',            'MINISTRAL', true, 0.50, 'IDENTITY', 'last_name',            NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TITLE',                'MINISTRAL', true, 0.50, 'IDENTITY', 'title',                NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',        'MINISTRAL', true, 0.50, 'IDENTITY', 'date_of_birth',        NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',                  'MINISTRAL', true, 0.50, 'IDENTITY', 'age',                  NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('GENDER',               'MINISTRAL', true, 0.50, 'IDENTITY', 'gender',               NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONALITY',          'MINISTRAL', true, 0.50, 'IDENTITY', 'nationality',          NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RACE',                 'MINISTRAL', true, 0.50, 'IDENTITY', 'race',                 NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ETHNICITY',            'MINISTRAL', true, 0.50, 'IDENTITY', 'ethnicity',            NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RACE_ETHNICITY',       'MINISTRAL', true, 0.50, 'IDENTITY', 'race_ethnicity',       NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RELIGION',             'MINISTRAL', true, 0.50, 'IDENTITY', 'religion',             NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RELIGIOUS_BELIEF',     'MINISTRAL', true, 0.50, 'IDENTITY', 'religious_belief',     NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MARITAL_STATUS',       'MINISTRAL', true, 0.50, 'IDENTITY', 'marital_status',       NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SEXUALITY',            'MINISTRAL', true, 0.50, 'IDENTITY', 'sexuality',            NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('POLITICAL_VIEW',       'MINISTRAL', true, 0.50, 'IDENTITY', 'political_view',       NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LANGUAGE',             'MINISTRAL', true, 0.50, 'IDENTITY', 'language',             NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIOMETRIC_IDENTIFIER', 'MINISTRAL', true, 0.50, 'IDENTITY', 'biometric_identifier', NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: CONTACT (Contact & address) -- 12 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('EMAIL',           'MINISTRAL', true, 0.50, 'CONTACT', 'email',           NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',    'MINISTRAL', true, 0.50, 'CONTACT', 'phone_number',    NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FAX_NUMBER',      'MINISTRAL', true, 0.50, 'CONTACT', 'fax_number',      NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STREET_ADDRESS',  'MINISTRAL', true, 0.50, 'CONTACT', 'street_address',  NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BUILDING_NUMBER', 'MINISTRAL', true, 0.50, 'CONTACT', 'building_number', NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',            'MINISTRAL', true, 0.50, 'CONTACT', 'city',            NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COUNTY',          'MINISTRAL', true, 0.50, 'CONTACT', 'county',          NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STATE',           'MINISTRAL', true, 0.50, 'CONTACT', 'state',           NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('POSTCODE',        'MINISTRAL', true, 0.50, 'CONTACT', 'postcode',        NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ZIP_CODE',        'MINISTRAL', true, 0.50, 'CONTACT', 'zip_code',        NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COUNTRY',         'MINISTRAL', true, 0.50, 'CONTACT', 'country',         NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COORDINATE',      'MINISTRAL', true, 0.50, 'CONTACT', 'coordinate',      NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: GOV_ID (Government & legal IDs) -- 9 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('SOCIAL_SECURITY_NUMBER',     'MINISTRAL', true, 0.50, 'GOV_ID', 'social_security_number',     NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SSN',                        'MINISTRAL', true, 0.50, 'GOV_ID', 'ssn',                        NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID',                'MINISTRAL', true, 0.50, 'GOV_ID', 'national_id',                NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER',      'MINISTRAL', true, 0.50, 'GOV_ID', 'driver_license_number',      NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',                     'MINISTRAL', true, 0.50, 'GOV_ID', 'tax_id',                     NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',              'MINISTRAL', true, 0.50, 'GOV_ID', 'license_plate',              NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_IDENTIFIER',         'MINISTRAL', true, 0.50, 'GOV_ID', 'vehicle_identifier',         NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CERTIFICATE_LICENSE_NUMBER', 'MINISTRAL', true, 0.50, 'GOV_ID', 'certificate_license_number', NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UNIQUE_ID',                  'MINISTRAL', true, 0.50, 'GOV_ID', 'unique_id',                  NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: MEDICAL (Healthcare) -- 3 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('MEDICAL_RECORD_NUMBER',          'MINISTRAL', true, 0.50, 'MEDICAL', 'medical_record_number',          NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HEALTH_PLAN_BENEFICIARY_NUMBER', 'MINISTRAL', true, 0.50, 'MEDICAL', 'health_plan_beneficiary_number', NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BLOOD_TYPE',                     'MINISTRAL', true, 0.50, 'MEDICAL', 'blood_type',                     NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: FINANCIAL (Financial) -- 8 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_DEBIT_CARD',   'MINISTRAL', true, 0.50, 'FINANCIAL', 'credit_debit_card',   NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CVV',                 'MINISTRAL', true, 0.50, 'FINANCIAL', 'cvv',                 NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PIN',                 'MINISTRAL', true, 0.50, 'FINANCIAL', 'pin',                 NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_NUMBER',      'MINISTRAL', true, 0.50, 'FINANCIAL', 'account_number',      NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ROUTING_NUMBER', 'MINISTRAL', true, 0.50, 'FINANCIAL', 'bank_routing_number', NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'MINISTRAL', true, 0.50, 'FINANCIAL', 'iban',                NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SWIFT_BIC',           'MINISTRAL', true, 0.50, 'FINANCIAL', 'swift_bic',           NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'MINISTRAL', true, 0.50, 'FINANCIAL', 'salary',              NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: EMPLOYMENT (Employment & org) -- 7 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('OCCUPATION',        'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'occupation',        NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EMPLOYMENT_STATUS', 'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'employment_status', NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EMPLOYEE_ID',       'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'employee_id',       NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EDUCATION_LEVEL',   'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'education_level',   NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ORGANIZATION',      'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'organization',      NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COMPANY_NAME',      'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'company_name',      NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CUSTOMER_ID',       'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'customer_id',       NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: DIGITAL (Digital & network) -- 10 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('IP_ADDRESS',        'MINISTRAL', true, 0.50, 'DIGITAL', 'ip_address',        NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IPV4',              'MINISTRAL', true, 0.50, 'DIGITAL', 'ipv4',              NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IPV6',              'MINISTRAL', true, 0.50, 'DIGITAL', 'ipv6',              NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',       'MINISTRAL', true, 0.50, 'DIGITAL', 'mac_address',       NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',               'MINISTRAL', true, 0.50, 'DIGITAL', 'url',               NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('USER_NAME',         'MINISTRAL', true, 0.50, 'DIGITAL', 'user_name',         NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',          'MINISTRAL', true, 0.50, 'DIGITAL', 'password',          NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HTTP_COOKIE',       'MINISTRAL', true, 0.50, 'DIGITAL', 'http_cookie',       NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',           'MINISTRAL', true, 0.50, 'DIGITAL', 'api_key',           NULL, false, false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DEVICE_IDENTIFIER', 'MINISTRAL', true, 0.50, 'DIGITAL', 'device_identifier', NULL, false, false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: TEMPORAL (Temporal) -- 3 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, detector_description, llm_judge_enabled, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('DATE',      'MINISTRAL', true, 0.50, 'TEMPORAL', 'date',      NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_TIME', 'MINISTRAL', true, 0.50, 'TEMPORAL', 'date_time', NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TIME',      'MINISTRAL', true, 0.50, 'TEMPORAL', 'time',      NULL, false, false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

COMMIT;
