package pro.softcom.aisentinel.application.pii.detection.port.out;

import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;

import java.util.List;
import java.util.Map;

/**
 * Port OUT for persisting open-vocabulary labels discovered by the MINISTRAL
 * detector.
 * <p>
 * Defines the contract for accumulating discovered-label occurrences and driving
 * their promotion/ignore lifecycle.
 */
public interface DiscoveredLabelStore {

    /**
     * Accumulates the given per-label occurrence counts, creating rows for
     * unseen labels ({@code PENDING}) and incrementing existing ones without
     * touching their status.
     *
     * @param labelCounts UPPER_SNAKE label to per-request occurrence count
     */
    void recordOccurrences(Map<String, Integer> labelCounts);

    /**
     * Finds all discovered labels in the given lifecycle status.
     *
     * @param status the status to filter by
     * @return the matching discovered labels
     */
    List<DiscoveredLabel> findByStatus(DiscoveredLabelStatus status);

    /**
     * Marks a discovered label as promoted (a config now exists for it).
     *
     * @param label the UPPER_SNAKE label
     */
    void markPromoted(String label);

    /**
     * Marks a discovered label as ignored (kept out of the inbox as noise).
     *
     * @param label the UPPER_SNAKE label
     */
    void markIgnored(String label);
}
