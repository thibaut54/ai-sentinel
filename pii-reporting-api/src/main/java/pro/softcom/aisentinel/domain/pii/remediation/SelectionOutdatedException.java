package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Thrown when the selection checksum computed at execution time no longer matches the one
 * computed at planning time: the resolved findings changed and the plan must be recomputed.
 */
public class SelectionOutdatedException extends RuntimeException {

    public SelectionOutdatedException(String message) {
        super(message);
    }
}
