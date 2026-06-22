package pro.softcom.aisentinel.integration.bench;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.JudgeStatus;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;

/**
 * A detector finding projected onto the benchmark's canonical concept space.
 *
 * <p>Built from a {@link SensitiveData} by mapping its detector-specific
 * {@code type()} (e.g. PRESIDIO {@code IBAN_CODE}) to the canonical concept
 * (e.g. {@code IBAN}) via {@link ConceptMap}, so a finding can be matched
 * against gold spans regardless of which detector produced it.
 *
 * @param start        inclusive start offset (code points)
 * @param end          exclusive end offset (code points)
 * @param canonical    canonical concept label
 * @param source       detector that produced the finding
 * @param score        detector confidence (0 when unknown)
 * @param judgeStatus  how the LLM-judge processed this kept finding
 */
public record Finding(
    int start,
    int end,
    String canonical,
    DetectorSource source,
    double score,
    JudgeStatus judgeStatus
) {

    /** Projects a {@link SensitiveData} into the canonical concept space. */
    public static Finding from(SensitiveData sd, ConceptMap conceptMap) {
        return new Finding(
            sd.position(),
            sd.end(),
            conceptMap.canonical(sd.source(), sd.type()),
            sd.source(),
            sd.score() == null ? 0.0 : sd.score(),
            sd.judgeStatus()
        );
    }
}
