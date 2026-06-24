package pro.softcom.aisentinel.application.pii.reporting;

/**
 * Maps backend scan status codes to dashboard UI status codes.
 *
 * <p>Replicates the frontend {@code scan-status.utils.ts} mapping so the facet keys and the
 * {@code statuses} filter use the same vocabulary as the UI:
 * <ul>
 *   <li>{@code COMPLETED} &rarr; {@code OK}</li>
 *   <li>null / blank (never scanned) &rarr; {@code NOT_STARTED}</li>
 *   <li>everything else passes through unchanged (RUNNING, FAILED, PAUSED, PENDING, ...)</li>
 * </ul>
 */
public final class UiStatusMapper {

    private static final String UI_NOT_STARTED = "NOT_STARTED";
    private static final String BACKEND_COMPLETED = "COMPLETED";
    private static final String UI_OK = "OK";

    private UiStatusMapper() {
    }

    /**
     * Maps a backend status to its UI counterpart.
     *
     * @param backendStatus the backend status (may be null when the space was never scanned)
     * @return the UI status code (never null)
     */
    public static String toUiStatus(String backendStatus) {
        if (backendStatus == null || backendStatus.isBlank()) {
            return UI_NOT_STARTED;
        }
        return BACKEND_COMPLETED.equals(backendStatus) ? UI_OK : backendStatus;
    }
}
