package pro.softcom.aisentinel.application.pii.detection.port.in;

import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;

import java.util.List;

/**
 * Port IN for managing open-vocabulary labels discovered by the MINISTRAL
 * detector.
 * <p>
 * Lets operators review pending discoveries and either promote them to a real
 * PII type configuration or ignore them.
 */
public interface ManageDiscoveredLabelsPort {

    /**
     * Lists the labels awaiting operator review (status {@code PENDING}).
     *
     * @return the pending discovered labels
     */
    List<DiscoveredLabel> listPending();

    /**
     * Promotes a discovered label to a custom MINISTRAL PII type configuration
     * and marks the label as promoted.
     *
     * @param command the promotion parameters
     * @return the created PII type configuration
     * @throws IllegalArgumentException if parameters are invalid or a duplicate exists
     */
    PiiTypeConfig promote(PromoteDiscoveredLabelCommand command);

    /**
     * Ignores a discovered label, keeping it out of the discovery inbox.
     *
     * @param label the UPPER_SNAKE label
     */
    void ignore(String label);

    /**
     * Command object for promoting a discovered label.
     *
     * @param label         the UPPER_SNAKE label to promote (also used as PII type)
     * @param category      the business category of the new configuration
     * @param severity      the severity (HIGH, MEDIUM or LOW; defaults to LOW when null)
     * @param threshold     the detection threshold (0.0-1.0)
     * @param detectorLabel the detector label (defaults to the lowercased label when blank)
     * @param countryCode   the optional country code
     * @param promotedBy    the operator performing the promotion
     */
    record PromoteDiscoveredLabelCommand(
        String label,
        String category,
        String severity,
        double threshold,
        String detectorLabel,
        String countryCode,
        String promotedBy
    ) {
    }
}
