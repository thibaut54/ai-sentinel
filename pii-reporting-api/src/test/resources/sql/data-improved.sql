-- ============================================================================
-- IMPROVED DATA SEED — for DataSqlComparisonIT only
-- ============================================================================
-- Identique a main/resources/data.sql sauf pour les PII identifies comme
-- producteurs de faux positifs massifs sur le corpus reel.
--
-- Changements vs data.sql (rechercher "-- IMPROVED:" pour les voir tous) :
--   1. SESSION_ID              GLINER : true  -> false  (FP=1624, label http_cookie trop generique)
--   2. DEVICE_ID               GLINER : true  -> false  (FP=213,  label device_identifier trop generique)
--   3. API_KEY                 GLINER : true  -> false  (REGEX 0.95 deja actif, plus precis)
--   4. VEHICLE_REGISTRATION    GLINER : true  -> false  (FP=1029 cote LICENSE_PLATE)
--   5. MEDICAL_LICENSE         PRESIDIO : false -> true (FP=941 cote GLINER, Presidio plus precis)
--   6. DRIVER_LICENSE_NUMBER   GLINER : true  -> false  (baseline: 0 TP / 37 FP, gain net pur)
--   7. MEDICAL_RECORD_NUMBER   GLINER : true  -> false  (baseline: 0 TP / 22 FP, gain net pur)
--   8. HEALTH_INSURANCE_NUMBER GLINER : true  -> false  (baseline: 0 TP / 1 FP,  gain net pur)
--   9. SSN                     GLINER : true  -> false  (baseline: 3 TP / 96 FP soit 97% FP-rate ;
--                                                       corpus CH/EU, US_SSN PRESIDIO reste opt-in country-specific)
-- ============================================================================

BEGIN;

ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS pii_type_config_pii_type_check;

INSERT INTO pii_detection_config (id, gliner_enabled, presidio_enabled, regex_enabled, default_threshold, nb_of_label_by_pass, updated_at, updated_by)
VALUES (1, true, true, true, 0.30, 35, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (id) DO NOTHING;

-- Category 1: IDENTITY
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('PERSON_NAME',           'GLINER', false, 0.80, 'IDENTITY', 'name',                      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NATIONAL_ID',           'GLINER', false,  0.95, 'IDENTITY', 'national_id',               'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: SSN GLINER true -> false (baseline 3 TP / 96 FP, 97% FP-rate ; US_SSN PRESIDIO opt-in country-specific)
    ('SSN',                   'GLINER', false, 0.80, 'IDENTITY', 'ssn',                       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSPORT_NUMBER',       'GLINER', false, 0.80, 'IDENTITY', NULL,                        'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: DRIVER_LICENSE_NUMBER GLINER true -> false (baseline 0 TP / 37 FP, gain net pur)
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
    ('ACCOUNT_ID', 'GLINER', true,  0.80, 'DIGITAL', 'customer_id', 'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('URL',        'GLINER', false, 0.80, 'DIGITAL', 'url',         'LOW', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 4: FINANCIAL
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD_NUMBER',  'GLINER', false, 0.80, 'FINANCIAL', 'credit_debit_card', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BANK_ACCOUNT_NUMBER', 'GLINER', false,  0.80, 'FINANCIAL', 'account_number',    'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN',                'GLINER', false, 0.80, 'FINANCIAL', 'iban',              'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('BIC_SWIFT',           'GLINER', false,  0.80, 'FINANCIAL', 'swift_bic',         'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TAX_ID',              'GLINER', false, 0.80, 'FINANCIAL', 'tax_id',            'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SALARY',              'GLINER', false, 0.80, 'FINANCIAL', NULL,                'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 5: MEDICAL
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PATIENT_ID',             'GLINER', false, 0.80, 'MEDICAL', NULL,                             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: MEDICAL_RECORD_NUMBER GLINER true -> false (baseline 0 TP / 22 FP, gain net pur)
    ('MEDICAL_RECORD_NUMBER',  'GLINER', false, 0.80, 'MEDICAL', 'medical_record_number',          'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: HEALTH_INSURANCE_NUMBER GLINER true -> false (baseline 0 TP / 1 FP, gain net pur)
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
    -- IMPROVED: DEVICE_ID GLINER true -> false (FP=213, label device_identifier trop generique sur tech docs)
    ('DEVICE_ID',    'GLINER', false, 0.80, 'IT_CREDENTIALS', 'device_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PASSWORD',     'GLINER', true,  0.95, 'IT_CREDENTIALS', 'password',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: API_KEY GLINER true -> false (REGEX 0.95 deja actif, plus precis et deterministe)
    ('API_KEY',      'GLINER', false, 0.80, 'IT_CREDENTIALS', 'api_key',           'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('ACCESS_TOKEN', 'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SECRET_KEY',   'GLINER', false, 0.80, 'IT_CREDENTIALS', NULL,                'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: SESSION_ID GLINER true -> false (FP=1624, label http_cookie matche tout token long)
    ('SESSION_ID',   'GLINER', false, 0.80, 'IT_CREDENTIALS', 'http_cookie',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- Category 7: LEGAL_ASSET
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CASE_NUMBER',             'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_NUMBER',          'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRIMINAL_RECORD',         'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: VEHICLE_REGISTRATION GLINER true -> false (FP=1029 cote LICENSE_PLATE, label vehicle_identifier capture tout numero de serie)
    ('VEHICLE_REGISTRATION',    'GLINER', false, 0.90, 'LEGAL_ASSET', 'vehicle_identifier', 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('LICENSE_PLATE',           'GLINER', false, 1,    'LEGAL_ASSET', 'license_plate',      'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('VIN',                     'GLINER', false, 0.90, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('INSURANCE_POLICY_NUMBER', 'GLINER', false, 0.80, 'LEGAL_ASSET', NULL,                 'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- PRESIDIO PII TYPES
-- ============================================================================

INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, created_at, updated_at, updated_by)
VALUES
    ('CREDIT_CARD',     'PRESIDIO', true,  0.90, 'Financial', 'CREDIT_CARD',     'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IBAN_CODE',       'PRESIDIO', true,  0.90, 'Financial', 'IBAN_CODE',       'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CRYPTO',          'PRESIDIO', true,  0.80, 'Financial', 'CRYPTO',          'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',     'PRESIDIO', true,  0.80, 'Network',   'MAC_ADDRESS',     'LOW',    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    -- IMPROVED: MEDICAL_LICENSE PRESIDIO false -> true (FP=941 cote GLINER, Presidio plus precis via DEA regex)
    ('MEDICAL_LICENSE', 'PRESIDIO', false,  0.90, 'Medical',   'MEDICAL_LICENSE', 'HIGH',   false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('NRP',             'PRESIDIO', true,  0.90, 'Personal',  'NRP',             'MEDIUM', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

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

-- Country-specific Presidio - tous disabled (opt-in per tenant)
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
    ('IN_PAN',            'PRESIDIO', false, 0.90, 'Government ID', 'IN_PAN',                  'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_AADHAAR',        'PRESIDIO', false, 0.95, 'Government ID', 'IN_AADHAAR',              'HIGH',   false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VEHICLE_REGISTRATION', 'PRESIDIO', false, 0.85, 'Government ID', 'IN_VEHICLE_REGISTRATION', 'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_VOTER',          'PRESIDIO', false, 0.90, 'Government ID', 'IN_VOTER',                'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IN_PASSPORT',       'PRESIDIO', false, 0.95, 'Government ID', 'IN_PASSPORT',             'MEDIUM', false, 'IN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('FI_PERSONAL_IDENTITY_CODE', 'PRESIDIO', false, 0.95, 'Government ID', 'FI_PERSONAL_IDENTITY_CODE', 'MEDIUM', false, 'FI', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('KR_RRN',            'PRESIDIO', false, 0.95, 'Government ID', 'KR_RRN',                  'MEDIUM', false, 'KR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('TH_TNIN',           'PRESIDIO', false, 0.95, 'Government ID', 'TH_TNIN',                 'MEDIUM', false, 'TH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

-- ============================================================================
-- REGEX PII TYPES — all enabled (high precision, low false-positive rate)
-- ============================================================================
INSERT INTO pii_type_config
(pii_type, detector, enabled, threshold, category, detector_label, severity, is_custom, country_code, created_at, updated_at, updated_by)
VALUES
    ('AVS_NUMBER',         'REGEX', true, 0.95, 'MEDICAL',       'avs number',             'HIGH', false, 'CH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('SOCIALNUM',          'REGEX', true, 0.75, 'IDENTITY',      'social security number', 'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('CREDIT_CARD_NUMBER', 'REGEX', true, 0.90, 'FINANCIAL',     'credit card number',     'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('PHONE_NUMBER',       'REGEX', false, 0.90, 'CONTACT',       'PHONE_NUMBER',           'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('IP_ADDRESS',         'REGEX', true, 0.95, 'IT_CREDENTIALS','ip address',             'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('MAC_ADDRESS',        'REGEX', true, 0.95, 'IT_CREDENTIALS','mac address',            'LOW',  false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
    ('API_KEY',            'REGEX', true, 0.95, 'IT_CREDENTIALS','api key',                'HIGH', false, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
    ON CONFLICT (pii_type, detector) DO NOTHING;

COMMIT;
