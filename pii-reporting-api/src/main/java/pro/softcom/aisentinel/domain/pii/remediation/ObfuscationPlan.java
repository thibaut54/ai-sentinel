package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Backend-computed preview of an obfuscation run for a resolved selection.
 *
 * <p>{@code selectionChecksum} fingerprints the exact set of resolved findings; execution
 * recomputes it and rejects the job when the selection has diverged since planning.</p>
 */
@Builder(toBuilder = true)
public record ObfuscationPlan(
        int totalFindings,
        Map<PersonallyIdentifiableInformationSeverity, Integer> bySeverity,
        int pagesImpacted,
        int falsePositivesReported,
        int attachmentExclusions,
        String selectionChecksum
) {

    public ObfuscationPlan {
        requireNonNegative(totalFindings, "totalFindings");
        requireNonNegative(pagesImpacted, "pagesImpacted");
        requireNonNegative(falsePositivesReported, "falsePositivesReported");
        requireNonNegative(attachmentExclusions, "attachmentExclusions");
        if (selectionChecksum == null || selectionChecksum.isBlank()) {
            throw new IllegalArgumentException("selectionChecksum is required");
        }
        bySeverity = bySeverity == null ? Map.of() : Map.copyOf(bySeverity);
    }

    /**
     * Computes the deterministic selection checksum: SHA-256 hex of the resolved finding ids
     * sorted in natural order and joined by a line feed, making the result independent of
     * resolution order.
     */
    public static String checksumOf(Collection<String> resolvedFindingIds) {
        String canonical = resolvedFindingIds.stream()
                .sorted()
                .collect(Collectors.joining("\n"));
        return Sha256.hexOf(canonical);
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }
}
