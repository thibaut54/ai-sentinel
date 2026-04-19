package pro.softcom.aisentinel.domain.pii.reporting;

/**
 * Immutable record representing PII counts aggregated by legal classification.
 *
 * <p>Tracks how many detected PIIs fall into each GDPR and nLPD classification
 * bucket, so dashboards can show a classification-oriented view alongside the
 * severity view.
 *
 * @param gdprSpecialCategory        Art. 9 GDPR - special category personal data
 * @param gdprCriminalData           Art. 10 GDPR - criminal convictions / offences
 * @param gdprPersonalDataHighRisk   Art. 6 + 32 GDPR - personal data requiring strong protection
 * @param gdprPersonalData           Art. 6 GDPR - personal data, baseline
 * @param nlpdSensitiveData          Art. 5 let. c nLPD - sensitive data
 * @param nlpdHighRiskProfilingData  Art. 5 let. g nLPD - high-risk profiling
 * @param nlpdPersonalDataHighRisk   Art. 8 nLPD - personal data requiring strong protection
 * @param nlpdPersonalData           Art. 5 let. a nLPD - personal data, baseline
 */
public record ClassificationCounts(
    int gdprSpecialCategory,
    int gdprCriminalData,
    int gdprPersonalDataHighRisk,
    int gdprPersonalData,
    int nlpdSensitiveData,
    int nlpdHighRiskProfilingData,
    int nlpdPersonalDataHighRisk,
    int nlpdPersonalData
) {
    /** Empty counts (all zeros). */
    public static ClassificationCounts zero() {
        return new ClassificationCounts(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
