BEGIN;

-- ============================================================================
-- Drop legacy CHECK constraint on pii_type column if it exists.
-- This constraint blocks custom PII types created via zero-shot detection.
-- ============================================================================
ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS pii_type_config_pii_type_check;

-- ============================================================================
-- PII Detection Global Config (Singleton with id=1)
-- ============================================================================
INSERT INTO pii_detection_config (id, gliner_enabled, presidio_enabled, regex_enabled, default_threshold, nb_of_label_by_pass, updated_at, updated_by)
VALUES (1, true, true, true, 0.30, 35, CURRENT_TIMESTAMP, 'system')
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
    ('SSN',                   'GLINER', false,  0.80, 'IDENTITY', 'ssn',                       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',       'GLINER', false, 0.80, 'IDENTITY', NULL,                        'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER', 'GLINER', false,  0.80, 'IDENTITY', 'certificate_license_number','MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
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
    ('ACCOUNT_ID', 'GLINER', true,  0.95, 'DIGITAL', 'customer_id', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',        'GLINER', false, 0.80, 'DIGITAL', 'url',         'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 4: FINANCIAL
-- ⛔ SALARY disabled — no NVIDIA label, salary figures acceptable in HR/payroll docs
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD_NUMBER',  'GLINER', false, 0.80, 'FINANCIAL', 'credit_debit_card', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT_NUMBER', 'GLINER', true,  0.90, 'FINANCIAL', 'account_number',    'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'GLINER', false, 0.80, 'FINANCIAL', 'iban',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',           'GLINER', true,  0.90, 'FINANCIAL', 'swift_bic',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
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
    ('PATIENT_ID',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_RECORD_NUMBER',  'GLINER', false,  0.80, 'MEDICAL', 'medical_record_number',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
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
    ('DEVICE_ID',    'GLINER', true,  0.95, 'IT_CREDENTIALS', 'device_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',     'GLINER', true,  0.80, 'IT_CREDENTIALS', 'password',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',      'GLINER', true,  0.80, 'IT_CREDENTIALS', 'api_key',           'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN', 'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET_KEY',   'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SESSION_ID',   'GLINER', false,  0.80, 'IT_CREDENTIALS', 'http_cookie',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
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
    ('CREDIT_CARD',     'PRESIDIO', true,  0.90, 'Financial', 'CREDIT_CARD',     'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
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

COMMIT;
