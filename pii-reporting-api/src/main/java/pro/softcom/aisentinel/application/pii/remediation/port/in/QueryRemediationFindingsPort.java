package pro.softcom.aisentinel.application.pii.remediation.port.in;

/**
 * In-port for the remediation read model: grouped, paginated findings with
 * server-side selection resolution.
 *
 * <p>All aggregation rules (grouping, tri-state master checkboxes, selected counts,
 * pagination, totals) live behind this port so the frontend never computes anything.</p>
 */
public interface QueryRemediationFindingsPort {

    /**
     * Checks whether the remediation feature is enabled by configuration.
     * This is the only remediation operation allowed while the feature flag is off,
     * so the UI can decide whether to show its entry points.
     */
    boolean isRemediationEnabled();

    /**
     * Searches findings of the latest scan for the requested scope, joined with their
     * remediation lifecycle, grouped and paginated server-side.
     *
     * <p>Only events carrying a {@code valueFingerprint} are visible to remediation;
     * older events are reported through
     * {@link RemediationFindingsResult#nonEligibleLegacyCount()}.</p>
     *
     * @throws pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException
     *         when the feature flag is off
     */
    RemediationFindingsResult search(RemediationFindingsQuery query);
}
