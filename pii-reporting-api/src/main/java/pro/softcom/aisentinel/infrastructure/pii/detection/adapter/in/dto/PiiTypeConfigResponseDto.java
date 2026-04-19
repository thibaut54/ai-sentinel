package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import pro.softcom.aisentinel.domain.pii.detection.GdprDataClassification;
import pro.softcom.aisentinel.domain.pii.detection.NlpdDataClassification;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;

import java.time.LocalDateTime;

/**
 * Response DTO for PII type configuration.
 * <p>
 * The {@code gdprClassification} and {@code nlpdClassification} are exposed as
 * strings (enum names) so the API contract stays stable and the domain enums
 * remain internal to the core.
 */
public record PiiTypeConfigResponseDto(
        Long id,
        String piiType,
        String detector,
        boolean enabled,
        double threshold,
        String category,
        String countryCode,
        String detectorLabel,
        boolean isCustom,
        String severity,
        String gdprClassification,
        String nlpdClassification,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static PiiTypeConfigResponseDto fromDomain(PiiTypeConfig config) {
        return new PiiTypeConfigResponseDto(
                config.getId(),
                config.getPiiType(),
                config.getDetector(),
                config.isEnabled(),
                config.getThreshold(),
                config.getCategory(),
                config.getCountryCode(),
                config.getDetectorLabel(),
                config.isCustom(),
                config.getSeverity(),
                nameOrDefault(config.getGdprClassification()),
                nameOrDefault(config.getNlpdClassification()),
                config.getUpdatedAt(),
                config.getUpdatedBy()
        );
    }

    private static String nameOrDefault(GdprDataClassification gdpr) {
        return gdpr != null ? gdpr.name() : GdprDataClassification.PERSONAL_DATA.name();
    }

    private static String nameOrDefault(NlpdDataClassification nlpd) {
        return nlpd != null ? nlpd.name() : NlpdDataClassification.PERSONAL_DATA.name();
    }
}
