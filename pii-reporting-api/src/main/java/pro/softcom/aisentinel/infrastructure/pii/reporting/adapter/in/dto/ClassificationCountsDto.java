package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

/**
 * DTO exposing PII counts per legal classification (GDPR + nLPD) for REST API responses.
 *
 * <p>Allows the dashboard to display counts either by severity (HIGH/MEDIUM/LOW) or by
 * legal classification, depending on the active view mode in the frontend.
 *
 * @param gdprSpecialCategory        Art. 9 GDPR
 * @param gdprCriminalData           Art. 10 GDPR
 * @param gdprPersonalDataHighRisk   Art. 6 + 32 GDPR
 * @param gdprPersonalData           Art. 6 GDPR baseline
 * @param nlpdSensitiveData          Art. 5 let. c nLPD
 * @param nlpdHighRiskProfilingData  Art. 5 let. g nLPD
 * @param nlpdPersonalDataHighRisk   Art. 8 nLPD
 * @param nlpdPersonalData           Art. 5 let. a nLPD baseline
 */
public record ClassificationCountsDto(
    int gdprSpecialCategory,
    int gdprCriminalData,
    int gdprPersonalDataHighRisk,
    int gdprPersonalData,
    int nlpdSensitiveData,
    int nlpdHighRiskProfilingData,
    int nlpdPersonalDataHighRisk,
    int nlpdPersonalData
) {
    public static ClassificationCountsDto zero() {
        return new ClassificationCountsDto(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
