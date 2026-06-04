package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.PiiTypeConfigResponseDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip coherence of {@code detectorDescription} across the hexagonal
 * boundaries: domain {@code ->} entity {@code ->} domain {@code ->} DTO,
 * with both a non-null and a null description (spec §5.1 / §9).
 */
class PiiTypeConfigDescriptionRoundTripTest {

    private PiiTypeConfig domain(String description) {
        return PiiTypeConfig.builder()
                .id(1L)
                .piiType("EMAIL")
                .detector("GLINER2")
                .enabled(true)
                .threshold(0.50)
                .category("CONTACT")
                .detectorLabel("email")
                .detectorDescription(description)
                .custom(false)
                .severity("LOW")
                .updatedAt(LocalDateTime.now())
                .updatedBy("system")
                .build();
    }

    @Test
    void Should_PreserveDescription_When_RoundTripDomainEntityDomain_NonNull() {
        PiiTypeConfig source = domain("adresse e-mail");

        PiiTypeConfigEntity entity = PiiTypeConfigEntity.fromDomain(source);
        PiiTypeConfig back = entity.toDomain();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(entity.getDetectorDescription()).isEqualTo("adresse e-mail");
        softly.assertThat(back.getDetectorDescription()).isEqualTo("adresse e-mail");
        softly.assertAll();
    }

    @Test
    void Should_PreserveNullDescription_When_RoundTripDomainEntityDomain() {
        PiiTypeConfig source = domain(null);

        PiiTypeConfigEntity entity = PiiTypeConfigEntity.fromDomain(source);
        PiiTypeConfig back = entity.toDomain();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(entity.getDetectorDescription()).isNull();
        softly.assertThat(back.getDetectorDescription()).isNull();
        softly.assertAll();
    }

    @Test
    void Should_ExposeDescriptionInResponseDto_When_FromDomain() {
        PiiTypeConfigResponseDto dto = PiiTypeConfigResponseDto.fromDomain(domain("adresse e-mail"));
        assertThat(dto.detectorDescription()).isEqualTo("adresse e-mail");
    }

    @Test
    void Should_ExposeNullDescriptionInResponseDto_When_FromDomainNull() {
        PiiTypeConfigResponseDto dto = PiiTypeConfigResponseDto.fromDomain(domain(null));
        assertThat(dto.detectorDescription()).isNull();
    }
}
