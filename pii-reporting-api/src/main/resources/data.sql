BEGIN;

-- ============================================================================
-- Drop legacy CHECK constraint on pii_type column if it exists.
-- This constraint blocks custom PII types created via zero-shot detection.
-- ============================================================================
ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS pii_type_config_pii_type_check;

-- ============================================================================
-- PII Detection Global Config (Singleton with id=1)
-- postfilter_enabled defaults to false (zero-effect rollout, deterministic format post-filter).
-- ministral_enabled defaults to false (explicit operator opt-in).
-- ============================================================================
INSERT INTO pii_detection_config (id, presidio_enabled, regex_enabled, default_threshold, postfilter_enabled, ministral_enabled, ministral_chunk_size, ministral_overlap, updated_at, updated_by)
VALUES (1, true, true, 0.30, false, false, 2048, 410, CURRENT_TIMESTAMP, 'system')
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
-- Ministral-PII detector seed (69 entities, 8 categories).
-- Specialised LLM detector: enabled by default at first install (all types active), threshold 0.50 neutral (no exploitable per-entity score).
-- ============================================================================

-- Category: IDENTITY (Identity & demographics) -- 17 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('FIRST_NAME',           'MINISTRAL', true, 0.50, 'IDENTITY', 'first_name',           false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LAST_NAME',            'MINISTRAL', true, 0.50, 'IDENTITY', 'last_name',            false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TITLE',                'MINISTRAL', true, 0.50, 'IDENTITY', 'title',                false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_OF_BIRTH',        'MINISTRAL', true, 0.50, 'IDENTITY', 'date_of_birth',        false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('AGE',                  'MINISTRAL', true, 0.50, 'IDENTITY', 'age',                  false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('GENDER',               'MINISTRAL', true, 0.50, 'IDENTITY', 'gender',               false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONALITY',          'MINISTRAL', true, 0.50, 'IDENTITY', 'nationality',          false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RACE',                 'MINISTRAL', true, 0.50, 'IDENTITY', 'race',                 false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ETHNICITY',            'MINISTRAL', true, 0.50, 'IDENTITY', 'ethnicity',            false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RACE_ETHNICITY',       'MINISTRAL', true, 0.50, 'IDENTITY', 'race_ethnicity',       false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RELIGION',             'MINISTRAL', true, 0.50, 'IDENTITY', 'religion',             false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('RELIGIOUS_BELIEF',     'MINISTRAL', true, 0.50, 'IDENTITY', 'religious_belief',     false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MARITAL_STATUS',       'MINISTRAL', true, 0.50, 'IDENTITY', 'marital_status',       false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SEXUALITY',            'MINISTRAL', true, 0.50, 'IDENTITY', 'sexuality',            false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('POLITICAL_VIEW',       'MINISTRAL', true, 0.50, 'IDENTITY', 'political_view',       false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LANGUAGE',             'MINISTRAL', true, 0.50, 'IDENTITY', 'language',             false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIOMETRIC_IDENTIFIER', 'MINISTRAL', true, 0.50, 'IDENTITY', 'biometric_identifier', false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: CONTACT (Contact & address) -- 12 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('EMAIL',           'MINISTRAL', true, 0.50, 'CONTACT', 'email',           false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',    'MINISTRAL', true, 0.50, 'CONTACT', 'phone_number',    false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FAX_NUMBER',      'MINISTRAL', true, 0.50, 'CONTACT', 'fax_number',      false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STREET_ADDRESS',  'MINISTRAL', true, 0.50, 'CONTACT', 'street_address',  false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BUILDING_NUMBER', 'MINISTRAL', true, 0.50, 'CONTACT', 'building_number', false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CITY',            'MINISTRAL', true, 0.50, 'CONTACT', 'city',            false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COUNTY',          'MINISTRAL', true, 0.50, 'CONTACT', 'county',          false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('STATE',           'MINISTRAL', true, 0.50, 'CONTACT', 'state',           false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('POSTCODE',        'MINISTRAL', true, 0.50, 'CONTACT', 'postcode',        false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ZIP_CODE',        'MINISTRAL', true, 0.50, 'CONTACT', 'zip_code',        false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COUNTRY',         'MINISTRAL', true, 0.50, 'CONTACT', 'country',         false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COORDINATE',      'MINISTRAL', true, 0.50, 'CONTACT', 'coordinate',      false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: GOV_ID (Government & legal IDs) -- 9 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('SOCIAL_SECURITY_NUMBER',     'MINISTRAL', true, 0.50, 'GOV_ID', 'social_security_number',     false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SSN',                        'MINISTRAL', true, 0.50, 'GOV_ID', 'ssn',                        false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID',                'MINISTRAL', true, 0.50, 'GOV_ID', 'national_id',                false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DRIVER_LICENSE_NUMBER',      'MINISTRAL', true, 0.50, 'GOV_ID', 'driver_license_number',      false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',                     'MINISTRAL', true, 0.50, 'GOV_ID', 'tax_id',                     false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',              'MINISTRAL', true, 0.50, 'GOV_ID', 'license_plate',              false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VEHICLE_IDENTIFIER',         'MINISTRAL', true, 0.50, 'GOV_ID', 'vehicle_identifier',         false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CERTIFICATE_LICENSE_NUMBER', 'MINISTRAL', true, 0.50, 'GOV_ID', 'certificate_license_number', false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('UNIQUE_ID',                  'MINISTRAL', true, 0.50, 'GOV_ID', 'unique_id',                  false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: MEDICAL (Healthcare) -- 3 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('MEDICAL_RECORD_NUMBER',          'MINISTRAL', true, 0.50, 'MEDICAL', 'medical_record_number',          false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HEALTH_PLAN_BENEFICIARY_NUMBER', 'MINISTRAL', true, 0.50, 'MEDICAL', 'health_plan_beneficiary_number', false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BLOOD_TYPE',                     'MINISTRAL', true, 0.50, 'MEDICAL', 'blood_type',                     false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: FINANCIAL (Financial) -- 8 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_DEBIT_CARD',   'MINISTRAL', true, 0.50, 'FINANCIAL', 'credit_debit_card',   false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CVV',                 'MINISTRAL', true, 0.50, 'FINANCIAL', 'cvv',                 false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PIN',                 'MINISTRAL', true, 0.50, 'FINANCIAL', 'pin',                 false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCOUNT_NUMBER',      'MINISTRAL', true, 0.50, 'FINANCIAL', 'account_number',      false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ROUTING_NUMBER', 'MINISTRAL', true, 0.50, 'FINANCIAL', 'bank_routing_number', false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'MINISTRAL', true, 0.50, 'FINANCIAL', 'iban',                false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SWIFT_BIC',           'MINISTRAL', true, 0.50, 'FINANCIAL', 'swift_bic',           false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'MINISTRAL', true, 0.50, 'FINANCIAL', 'salary',              false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: EMPLOYMENT (Employment & org) -- 7 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('OCCUPATION',        'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'occupation',        false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EMPLOYMENT_STATUS', 'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'employment_status', false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EMPLOYEE_ID',       'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'employee_id',       false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('EDUCATION_LEVEL',   'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'education_level',   false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ORGANIZATION',      'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'organization',      false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('COMPANY_NAME',      'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'company_name',      false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CUSTOMER_ID',       'MINISTRAL', true, 0.50, 'EMPLOYMENT', 'customer_id',       false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: DIGITAL (Digital & network) -- 10 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('IP_ADDRESS',        'MINISTRAL', true, 0.50, 'DIGITAL', 'ip_address',        false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IPV4',              'MINISTRAL', true, 0.50, 'DIGITAL', 'ipv4',              false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IPV6',              'MINISTRAL', true, 0.50, 'DIGITAL', 'ipv6',              false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',       'MINISTRAL', true, 0.50, 'DIGITAL', 'mac_address',       false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',               'MINISTRAL', true, 0.50, 'DIGITAL', 'url',               false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('USER_NAME',         'MINISTRAL', true, 0.50, 'DIGITAL', 'user_name',         false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',          'MINISTRAL', true, 0.50, 'DIGITAL', 'password',          false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('HTTP_COOKIE',       'MINISTRAL', true, 0.50, 'DIGITAL', 'http_cookie',       false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',           'MINISTRAL', true, 0.50, 'DIGITAL', 'api_key',           false, 'HIGH',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DEVICE_IDENTIFIER', 'MINISTRAL', true, 0.50, 'DIGITAL', 'device_identifier', false, 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category: TEMPORAL (Temporal) -- 3 entities
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, is_custom, severity, created_at, updated_at, updated_by)
VALUES
    ('DATE',      'MINISTRAL', true, 0.50, 'TEMPORAL', 'date',      false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('DATE_TIME', 'MINISTRAL', true, 0.50, 'TEMPORAL', 'date_time', false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TIME',      'MINISTRAL', true, 0.50, 'TEMPORAL', 'time',      false, 'LOW',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

COMMIT;
