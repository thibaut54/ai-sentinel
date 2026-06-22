package pro.softcom.aisentinel.integration.bench;

import java.util.Map;

/**
 * Groups canonical concepts into the spec's high-level categories so the report
 * can show per-category precision/recall/F1. Mirrors the grouping in
 * {@code label_mapping.toml} and the spec's "Labels activés dans l'app".
 *
 * <p>A concept not listed here falls back to {@link #OTHER}.
 */
final class CanonicalConcepts {

    static final String GOV_TAX = "GOV_TAX";
    static final String BANKING_PAYMENT = "BANKING_PAYMENT";
    static final String DIGITAL_IDENTITY = "DIGITAL_IDENTITY";
    static final String SECRETS_CREDENTIALS = "SECRETS_CREDENTIALS";
    static final String OTHER = "OTHER";

    private static final Map<String, String> CATEGORY_BY_CONCEPT = Map.ofEntries(
        // Government / tax IDs
        Map.entry("GOVERNMENT_ID", GOV_TAX),
        Map.entry("NATIONAL_ID_NUMBER", GOV_TAX),
        Map.entry("PASSPORT_NUMBER", GOV_TAX),
        Map.entry("DRIVERS_LICENSE_NUMBER", GOV_TAX),
        Map.entry("LICENSE_NUMBER", GOV_TAX),
        Map.entry("TAX_ID", GOV_TAX),
        Map.entry("TAX_NUMBER", GOV_TAX),
        Map.entry("AVS_NUMBER", GOV_TAX),
        // Banking / payment
        Map.entry("IBAN", BANKING_PAYMENT),
        Map.entry("BANK_ACCOUNT", BANKING_PAYMENT),
        Map.entry("ROUTING_NUMBER", BANKING_PAYMENT),
        Map.entry("CARD_NUMBER", BANKING_PAYMENT),
        Map.entry("CARD_EXPIRY", BANKING_PAYMENT),
        Map.entry("CARD_CVV", BANKING_PAYMENT),
        Map.entry("PIN", BANKING_PAYMENT),
        Map.entry("CRYPTO", BANKING_PAYMENT),
        Map.entry("ACCOUNT_NAME", BANKING_PAYMENT),
        // Digital identity
        Map.entry("USERNAME", DIGITAL_IDENTITY),
        Map.entry("IP_ADDRESS", DIGITAL_IDENTITY),
        Map.entry("ACCOUNT_ID", DIGITAL_IDENTITY),
        Map.entry("SENSITIVE_ACCOUNT_ID", DIGITAL_IDENTITY),
        Map.entry("PHONE_NUMBER", DIGITAL_IDENTITY),
        Map.entry("MAC_ADDRESS", DIGITAL_IDENTITY),
        // Secrets / credentials
        Map.entry("PASSWORD", SECRETS_CREDENTIALS),
        Map.entry("SECRET", SECRETS_CREDENTIALS),
        Map.entry("API_KEY", SECRETS_CREDENTIALS),
        Map.entry("ACCESS_TOKEN", SECRETS_CREDENTIALS),
        Map.entry("RECOVERY_CODE", SECRETS_CREDENTIALS)
    );

    private CanonicalConcepts() {
    }

    static String categoryOf(String canonical) {
        return CATEGORY_BY_CONCEPT.getOrDefault(canonical, OTHER);
    }
}
