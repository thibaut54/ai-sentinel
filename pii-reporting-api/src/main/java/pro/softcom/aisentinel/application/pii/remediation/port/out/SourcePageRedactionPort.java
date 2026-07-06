package pro.softcom.aisentinel.application.pii.remediation.port.out;

import java.util.List;

/**
 * Out-port redacting PII values inside a source page, whatever its storage format.
 * Callers hand in plaintext/token pairs; implementations confine all format knowledge,
 * never expose plaintext values in results, logs or errors, and correlate outcomes to
 * the input pairs by position.
 */
public interface SourcePageRedactionPort {

    PageRedactionResult redactPage(String pageId, List<ValueReplacement> replacements);

    record ValueReplacement(String plaintextValue, String token) {
    }

    enum PageRedactionStatus {
        UPDATED,
        NO_MATCHES,
        STALE,
        FAILED
    }

    enum ValueRedactionStatus {
        REDACTED,
        VALUE_NOT_FOUND
    }

    /**
     * @param valueStatuses one status per input replacement, in input order; empty when
     *                      the page-level status is {@code STALE} or {@code FAILED}
     */
    record PageRedactionResult(PageRedactionStatus pageStatus, List<ValueRedactionStatus> valueStatuses) {

        public PageRedactionResult {
            valueStatuses = valueStatuses == null ? List.of() : List.copyOf(valueStatuses);
        }

        public static PageRedactionResult stale() {
            return new PageRedactionResult(PageRedactionStatus.STALE, List.of());
        }

        public static PageRedactionResult failed() {
            return new PageRedactionResult(PageRedactionStatus.FAILED, List.of());
        }
    }
}
