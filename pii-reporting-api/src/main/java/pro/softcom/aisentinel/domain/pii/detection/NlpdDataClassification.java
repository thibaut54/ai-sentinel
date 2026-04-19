package pro.softcom.aisentinel.domain.pii.detection;

/**
 * Swiss nLPD (RS 235.1) data classification for PII types.
 * <p>
 * Four symmetric values mapping to Art. 5 let. a / c / g and Art. 8 of the nLPD.
 * Each value carries presentation metadata (localized label, article reference,
 * color hex and PrimeNG badge severity).
 * <p>
 * The enum is strictly framework-agnostic: no Spring or JPA annotations.
 * Persistence and serialization are handled by outer layers.
 */
public enum NlpdDataClassification {

    /** Art. 5 let. c nLPD - sensitive data (health, biometric, religion, criminal, social assistance, ...). */
    SENSITIVE_DATA("Art. 5 let. c", "Donnees sensibles", "#DC2626", "red"),

    /** Art. 5 let. g nLPD - high-risk profiling. */
    HIGH_RISK_PROFILING_DATA("Art. 5 let. g", "Profilage a risque eleve", "#EA580C", "orange"),

    /** Art. 8 nLPD - personal data with high risk requiring reinforced security. */
    PERSONAL_DATA_HIGH_RISK("Art. 8", "Donnees personnelles haut risque", "#CA8A04", "yellow"),

    /** Art. 5 let. a nLPD - ordinary personal data. */
    PERSONAL_DATA("Art. 5 let. a", "Donnees personnelles", "#16A34A", "green");

    private final String article;
    private final String labelFr;
    private final String colorHex;
    private final String badgeSeverity;

    NlpdDataClassification(String article, String labelFr, String colorHex, String badgeSeverity) {
        this.article = article;
        this.labelFr = labelFr;
        this.colorHex = colorHex;
        this.badgeSeverity = badgeSeverity;
    }

    public String getArticle() {
        return article;
    }

    public String getLabelFr() {
        return labelFr;
    }

    public String getColorHex() {
        return colorHex;
    }

    public String getBadgeSeverity() {
        return badgeSeverity;
    }
}
