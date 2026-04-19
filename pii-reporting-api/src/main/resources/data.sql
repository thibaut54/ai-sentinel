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

-- Category 1: IDENTITY
-- ✅ enabled  : NATIONAL_ID, SSN, PASSPORT_NUMBER, DRIVER_LICENSE_NUMBER
-- ⛔ disabled : PERSON_NAME, DATE_OF_BIRTH, GENDER, NATIONALITY, AGE
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PERSON_NAME',           'GLINER', false, 0.80, 'IDENTITY', 'person name', 'PERSONAL_DATA', 'PERSONAL_DATA',             'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID',           'GLINER', true,  0.80, 'IDENTITY', 'national identity number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SSN',                   'GLINER', true,  0.80, 'IDENTITY', 'social insurance number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',   'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',       'GLINER', true,  0.80, 'IDENTITY', 'passport number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER', 'GLINER', true,  0.80, 'IDENTITY', 'driver license identification', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',         'GLINER', false, 0.80, 'IDENTITY', 'date of birth', 'PERSONAL_DATA', 'PERSONAL_DATA',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('GENDER',                'GLINER', false, 0.80, 'IDENTITY', 'gender', 'PERSONAL_DATA', 'PERSONAL_DATA',                   'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONALITY',           'GLINER', false, 0.80, 'IDENTITY', 'nationality', 'PERSONAL_DATA', 'PERSONAL_DATA',              'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',                   'GLINER', false, 0.80, 'IDENTITY', 'age', 'PERSONAL_DATA', 'PERSONAL_DATA',                      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 2: CONTACT
-- ⛔ ALL disabled — email, phone and address coordinates are standard in
--    professional documents and should only be flagged when explicitly enabled.
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('EMAIL',        'GLINER', false, 0.80, 'CONTACT', 'email address', 'PERSONAL_DATA', 'PERSONAL_DATA', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER', 'GLINER', false, 0.80, 'CONTACT', 'phone number', 'PERSONAL_DATA', 'PERSONAL_DATA',  'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ADDRESS',      'GLINER', false, 0.80, 'CONTACT', 'address', 'PERSONAL_DATA', 'PERSONAL_DATA',       'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',         'GLINER', false, 0.80, 'CONTACT', 'city', 'PERSONAL_DATA', 'PERSONAL_DATA',          'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ZIP_CODE',     'GLINER', false, 0.80, 'CONTACT', 'zip code', 'PERSONAL_DATA', 'PERSONAL_DATA',      'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 3: DIGITAL
-- ✅ enabled  : USERNAME (threshold 0.90 — short tokens, higher confidence needed),
--               ACCOUNT_ID
-- ⛔ disabled : URL — extremely common in technical documentation
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('USERNAME',   'GLINER', true,  0.90, 'DIGITAL', 'system account name', 'PERSONAL_DATA', 'PERSONAL_DATA', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_ID', 'GLINER', true,  0.80, 'DIGITAL', 'customer account', 'PERSONAL_DATA', 'PERSONAL_DATA',                                   'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',        'GLINER', false, 0.80, 'DIGITAL', 'url', 'PERSONAL_DATA', 'PERSONAL_DATA',                                               'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 4: FINANCIAL — all enabled
-- ⛔ SALARY disabled — salary figures are acceptable in HR/payroll documents
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD_NUMBER',  'GLINER', true,  0.80, 'FINANCIAL', 'credit card number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',        'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT_NUMBER', 'GLINER', true,  0.80, 'FINANCIAL', 'financial institution account number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'GLINER', true,  0.80, 'FINANCIAL', 'international banking identifier', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',           'GLINER', true,  0.80, 'FINANCIAL', 'swift code', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',              'GLINER', true,  0.80, 'FINANCIAL', 'tax identifier', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'GLINER', false, 0.80, 'FINANCIAL', 'salary amount', 'PERSONAL_DATA', 'PERSONAL_DATA',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 5: MEDICAL — all enabled
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',             'GLINER', true, 0.80, 'MEDICAL', 'Swiss AVS 13-digit personal number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PATIENT_ID',             'GLINER', true, 0.80, 'MEDICAL', 'hospital patient identifier', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA',        'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_RECORD_NUMBER',  'GLINER', true, 0.80, 'MEDICAL', 'medical file number', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA',                'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HEALTH_INSURANCE_NUMBER','GLINER', true, 0.80, 'MEDICAL', 'health insurance number', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DIAGNOSIS',              'GLINER', true, 0.80, 'MEDICAL', 'clinical diagnosis', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA',      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICATION',             'GLINER', true, 0.80, 'MEDICAL', 'medication name', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA',         'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 6: IT_CREDENTIALS
-- ✅ enabled  : MAC_ADDRESS, DEVICE_ID, PASSWORD, API_KEY, ACCESS_TOKEN,
--               SECRET_KEY, SESSION_ID
-- ⛔ disabled : IP_ADDRESS, HOSTNAME — very common in technical logs and documentation
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('IP_ADDRESS',   'GLINER', false, 0.80, 'IT_CREDENTIALS', 'IPv4 or IPv6 network address', 'PERSONAL_DATA', 'PERSONAL_DATA', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',  'GLINER', true,  0.80, 'IT_CREDENTIALS', 'mac address', 'PERSONAL_DATA', 'PERSONAL_DATA',   'LOW',  false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HOSTNAME',     'GLINER', false, 0.80, 'IT_CREDENTIALS', 'hostname', 'PERSONAL_DATA', 'PERSONAL_DATA',      'LOW',  false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DEVICE_ID',    'GLINER', true,  0.80, 'IT_CREDENTIALS', 'mobile device unique identifier', 'PERSONAL_DATA', 'PERSONAL_DATA', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',     'GLINER', true,  0.80, 'IT_CREDENTIALS', 'account password or PIN code', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',      'GLINER', true,  0.80, 'IT_CREDENTIALS', 'API authentication credential', 'PERSONAL_DATA', 'PERSONAL_DATA', 'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN', 'GLINER', true,  0.80, 'IT_CREDENTIALS', 'access token', 'PERSONAL_DATA', 'PERSONAL_DATA',  'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET_KEY',   'GLINER', false,  0.80, 'IT_CREDENTIALS', 'secret key', 'PERSONAL_DATA', 'PERSONAL_DATA',    'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SESSION_ID',   'GLINER', true,  0.80, 'IT_CREDENTIALS', 'web session', 'PERSONAL_DATA', 'PERSONAL_DATA',   'HIGH', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 7: LEGAL_ASSET — all enabled
-- Threshold 0.90 for vehicle-related types (short tokens, high false-positive risk)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CASE_NUMBER',             'GLINER', true, 0.80, 'LEGAL_ASSET', 'court case reference number', 'PERSONAL_DATA', 'PERSONAL_DATA',           'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_NUMBER',          'GLINER', true, 0.80, 'LEGAL_ASSET', 'regulatory license identifier', 'PERSONAL_DATA', 'PERSONAL_DATA',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRIMINAL_RECORD',         'GLINER', true, 0.80, 'LEGAL_ASSET', 'criminal background record', 'CRIMINAL_DATA', 'SENSITIVE_DATA',             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_REGISTRATION',    'GLINER', true, 0.90, 'LEGAL_ASSET', 'vehicle registration plate number', 'PERSONAL_DATA', 'PERSONAL_DATA',      'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',           'GLINER', true, 0.90, 'LEGAL_ASSET', 'vehicle license plate', 'PERSONAL_DATA', 'PERSONAL_DATA',                  'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VIN',                     'GLINER', true, 0.90, 'LEGAL_ASSET', 'vehicle chassis identification number', 'PERSONAL_DATA', 'PERSONAL_DATA',  'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('INSURANCE_POLICY_NUMBER', 'GLINER', true, 0.80, 'LEGAL_ASSET', 'insurance policy identifier', 'PERSONAL_DATA', 'PERSONAL_DATA',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- PRESIDIO PII TYPES
-- ============================================================================
-- ✅ enabled  : CREDIT_CARD, IBAN_CODE, CRYPTO, MAC_ADDRESS, MEDICAL_LICENSE, NRP
-- ⛔ disabled : contact (email, phone, URL), generic personal (person, age, location,
--               date), IP address, and ALL country-specific types (opt-in per tenant)

-- Universal — enabled
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD',     'PRESIDIO', true,  0.75, 'Financial', 'CREDIT_CARD', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',     'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN_CODE',       'PRESIDIO', true,  0.75, 'Financial', 'IBAN_CODE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRYPTO',          'PRESIDIO', true,  0.80, 'Financial', 'CRYPTO', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',     'PRESIDIO', true,  0.80, 'Network',   'MAC_ADDRESS', 'PERSONAL_DATA', 'PERSONAL_DATA',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MEDICAL_LICENSE', 'PRESIDIO', true,  0.90, 'Medical',   'MEDICAL_LICENSE', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NRP',             'PRESIDIO', true,  0.90, 'Personal',  'NRP', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Universal — disabled (commonly accepted PII or high false-positive rate)
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('EMAIL_ADDRESS', 'PRESIDIO', false, 0.70, 'Contact',  'EMAIL_ADDRESS', 'PERSONAL_DATA', 'PERSONAL_DATA', 'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',  'PRESIDIO', false, 0.90, 'Contact',  'PHONE', 'PERSONAL_DATA', 'PERSONAL_DATA',         'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',           'PRESIDIO', false, 0.70, 'Contact',  'URL', 'PERSONAL_DATA', 'PERSONAL_DATA',           'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',    'PRESIDIO', false, 0.80, 'Network',  'IP_ADDRESS', 'PERSONAL_DATA', 'PERSONAL_DATA',    'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PERSON',        'PRESIDIO', false, 0.90, 'Personal', 'PERSON', 'PERSONAL_DATA', 'PERSONAL_DATA',        'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LOCATION',      'PRESIDIO', false, 0.75, 'Location', 'LOCATION', 'PERSONAL_DATA', 'PERSONAL_DATA',      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_TIME',     'PRESIDIO', false, 0.75, 'Personal', 'DATE', 'PERSONAL_DATA', 'PERSONAL_DATA',          'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',           'PRESIDIO', false, 0.70, 'Personal', 'AGE', 'PERSONAL_DATA', 'PERSONAL_DATA',           'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Country-specific — ALL disabled by default (opt-in per tenant jurisdiction)

-- USA
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('US_SSN',            'PRESIDIO', false, 0.95, 'Government ID', 'US_SSN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',            'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_BANK_NUMBER',    'PRESIDIO', false, 0.90, 'Financial',     'US_BANK_NUMBER', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',    'HIGH',   false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_DRIVER_LICENSE', 'PRESIDIO', false, 0.90, 'Government ID', 'US_DRIVER_LICENSE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_ITIN',           'PRESIDIO', false, 0.95, 'Government ID', 'US_ITIN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',           'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('US_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'US_PASSPORT', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',       'MEDIUM', false, 'US', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- UK
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('UK_NHS',  'PRESIDIO', false, 0.95, 'Government ID', 'UK_NHS', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',  'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UK_NINO', 'PRESIDIO', false, 0.95, 'Government ID', 'UK_NINO', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'UK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Spain
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('ES_NIF', 'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIF', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ES_NIE', 'PRESIDIO', false, 0.90, 'Government ID', 'ES_NIE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'ES', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Italy
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('IT_FISCAL_CODE',   'PRESIDIO', false, 0.95, 'Government ID', 'IT_FISCAL_CODE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',   'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_DRIVER_LICENSE','PRESIDIO', false, 0.90, 'Government ID', 'IT_DRIVER_LICENSE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK','MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_VAT_CODE',      'PRESIDIO', false, 0.90, 'Financial',     'IT_VAT_CODE', 'PERSONAL_DATA', 'PERSONAL_DATA',      'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_PASSPORT',      'PRESIDIO', false, 0.95, 'Government ID', 'IT_PASSPORT', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',      'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IT_IDENTITY_CARD', 'PRESIDIO', false, 0.90, 'Government ID', 'IT_IDENTITY_CARD', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'IT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Poland
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('PL_PESEL', 'PRESIDIO', false, 0.95, 'Government ID', 'PL_PESEL', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Singapore
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('SG_NRIC_FIN', 'PRESIDIO', false, 0.95, 'Government ID', 'SG_NRIC_FIN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SG_UEN',      'PRESIDIO', false, 0.90, 'Business',      'SG_UEN', 'PERSONAL_DATA', 'PERSONAL_DATA',      'MEDIUM', false, 'SG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Australia
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AU_ABN',      'PRESIDIO', false, 0.90, 'Business',      'AU_ABN', 'PERSONAL_DATA', 'PERSONAL_DATA',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_ACN',      'PRESIDIO', false, 0.90, 'Business',      'AU_ACN', 'PERSONAL_DATA', 'PERSONAL_DATA',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_TFN',      'PRESIDIO', false, 0.95, 'Government ID', 'AU_TFN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',      'MEDIUM', false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AU_MEDICARE', 'PRESIDIO', false, 0.95, 'Medical',       'AU_MEDICARE', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA', 'HIGH',   false, 'AU', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- India
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('IN_PAN',                  'PRESIDIO', false, 0.90, 'Government ID', 'IN_PAN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',                  'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_AADHAAR',              'PRESIDIO', false, 0.95, 'Government ID', 'IN_AADHAAR', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',              'HIGH',   false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VEHICLE_REGISTRATION', 'PRESIDIO', false, 0.85, 'Government ID', 'IN_VEHICLE_REGISTRATION', 'PERSONAL_DATA', 'PERSONAL_DATA', 'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VOTER',                'PRESIDIO', false, 0.90, 'Government ID', 'IN_VOTER', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',                'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_PASSPORT',             'PRESIDIO', false, 0.95, 'Government ID', 'IN_PASSPORT', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',             'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Finland
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('FI_PERSONAL_IDENTITY_CODE', 'PRESIDIO', false, 0.95, 'Government ID', 'FI_PERSONAL_IDENTITY_CODE', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'FI', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Korea
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('KR_RRN', 'PRESIDIO', false, 0.95, 'Government ID', 'KR_RRN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'KR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Thailand
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('TH_TNIN', 'PRESIDIO', false, 0.95, 'Government ID', 'TH_TNIN', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'MEDIUM', false, 'TH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- REGEX PII TYPES — all enabled (high precision, low false-positive rate)
-- ============================================================================
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, gdpr_classification, nlpd_classification, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',         'REGEX', true, 0.95, 'MEDICAL',       'avs number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',         'HIGH', false, 'CH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SOCIALNUM',          'REGEX', true, 0.75, 'IDENTITY',      'social security number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK', 'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_NUMBER', 'REGEX', true, 0.90, 'FINANCIAL',     'credit card number', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK',  'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',       'REGEX', true, 0.90, 'CONTACT',       'PHONE_NUMBER', 'PERSONAL_DATA', 'PERSONAL_DATA',        'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'REGEX', true, 0.95, 'IT_CREDENTIALS','ip address', 'PERSONAL_DATA', 'PERSONAL_DATA',          'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'REGEX', true, 0.95, 'IT_CREDENTIALS','mac address', 'PERSONAL_DATA', 'PERSONAL_DATA',         'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',            'REGEX', true, 0.95, 'IT_CREDENTIALS','api key', 'PERSONAL_DATA', 'PERSONAL_DATA',             'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

COMMIT;
