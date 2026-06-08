package pro.softcom.aisentinel.domain.pii.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aggregated execution stats for a single detector within a space scan.
 *
 * <p>Business purpose: lets the dashboard compare detectors by throughput
 * (characters processed per second of busy time) and detection volume.
 *
 * @param detector       detector identifier (e.g. GLINER2, PRESIDIO, REGEX); also the
 *                       JUDGE/PREFILTER post-filters, surfaced as pseudo detectors
 * @param detections     total raw entities found by this detector (or examined count
 *                       for the JUDGE/PREFILTER post-filters)
 * @param charsProcessed total characters submitted to this detector
 * @param busyMs         cumulated busy time of this detector in milliseconds
 * @param discarded      total PII discarded by this stage (0 for real detectors)
 */
public record ScanDetectorStat(
    String detector,
    int detections,
    long charsProcessed,
    long busyMs,
    int discarded
) {

    /**
     * Throughput of this detector in characters per second of busy time.
     *
     * @return characters per second rounded to one decimal, or null when no
     *         busy time was recorded (division by zero is undefined)
     */
    public Double charsPerSecond() {
        if (busyMs <= 0) {
            return null;
        }
        return BigDecimal.valueOf(charsProcessed * 1000.0 / busyMs)
            .setScale(1, RoundingMode.HALF_UP)
            .doubleValue();
    }
}
