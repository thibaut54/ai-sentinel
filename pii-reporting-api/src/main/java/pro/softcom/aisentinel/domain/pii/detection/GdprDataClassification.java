package pro.softcom.aisentinel.domain.pii.detection;

/**
 * GDPR data classification for PII types.
 * <p>
 * Maps each PII type to a GDPR category (Art. 9, Art. 10 or Art. 6) along with
 * presentation metadata used by the UI (localized label, article reference,
 * color and PrimeNG badge severity).
 * <p>
 * The enum is strictly framework-agnostic: no Spring or JPA annotations.
 * Persistence and serialization are handled by outer layers.
 */
public enum GdprDataClassification {

    /** Art. 9 RGPD - special categories of personal data (health, biometric, religion, ...). */
    SPECIAL_CATEGORY("Art. 9", "Categorie speciale", "#DC2626", "red"),

    /** Art. 10 RGPD - criminal convictions and offences. */
    CRIMINAL_DATA("Art. 10", "Donnees penales", "#EA580C", "orange"),

    /** Art. 6 + 32 RGPD - personal data with high-risk requiring reinforced security. */
    PERSONAL_DATA_HIGH_RISK("Art. 6", "Donnees personnelles haut risque", "#CA8A04", "yellow"),

    /** Art. 6 RGPD - ordinary personal data. */
    PERSONAL_DATA("Art. 6", "Donnees personnelles", "#16A34A", "green");

    private final String article;
    private final String labelFr;
    private final String colorHex;
    private final String badgeSeverity;

    GdprDataClassification(String article, String labelFr, String colorHex, String badgeSeverity) {
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
