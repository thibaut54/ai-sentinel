package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Segment;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.JudgeStatus;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DetectionOffsetRemapper")
class DetectionOffsetRemapperTest {

    @Test
    @DisplayName("Should return the same detection unchanged for the identity mapping")
    void Should_ReturnUnchanged_When_IdentityMapping() {
        ContentPiiDetection detection = detectionWith(
            new SensitiveData("EMAIL", "Email", "john@doe.com", "", 6, 18, 0.9, "sel", DetectorSource.GLINER2));

        ContentPiiDetection result = DetectionOffsetRemapper.remap(detection, OffsetMapping.identity());

        assertThat(result).isSameAs(detection);
    }

    @Test
    @DisplayName("Should return null when the detection is null")
    void Should_ReturnNull_When_DetectionNull() {
        assertThat(DetectionOffsetRemapper.remap(null, OffsetMapping.identity())).isNull();
    }

    @Test
    @DisplayName("Should remap positions from analysis space to context space while preserving other fields")
    void Should_RemapPositions_When_TabularMapping() {
        // analysis "Nom : Dupont" -> value "Dupont" at [6,12); context "Dupont" -> value at [0,6)
        OffsetMapping mapping = new OffsetMapping(List.of(new Segment(6, 6, 0)));
        ContentPiiDetection detection = detectionWith(
            new SensitiveData("LAST_NAME", "Nom de famille", "Dupont", "ctx", 6, 12, 0.77, "sel",
                DetectorSource.GLINER2, JudgeStatus.NOT_AUDITED));

        ContentPiiDetection result = DetectionOffsetRemapper.remap(detection, mapping);

        assertThat(result.sensitiveDataFound()).hasSize(1);
        SensitiveData remapped = result.sensitiveDataFound().getFirst();
        assertThat(remapped.position()).isZero();
        assertThat(remapped.end()).isEqualTo(6);
        // Non-positional fields are preserved verbatim
        assertThat(remapped.type()).isEqualTo("LAST_NAME");
        assertThat(remapped.value()).isEqualTo("Dupont");
        assertThat(remapped.score()).isEqualTo(0.77);
        assertThat(remapped.source()).isEqualTo(DetectorSource.GLINER2);
        assertThat(remapped.judgeStatus()).isEqualTo(JudgeStatus.NOT_AUDITED);
    }

    @Test
    @DisplayName("Should drop a detection that falls on a synthetic label (unmappable range)")
    void Should_DropDetection_When_RangeUnmappable() {
        OffsetMapping mapping = new OffsetMapping(List.of(new Segment(6, 6, 0)));
        ContentPiiDetection detection = detectionWith(
            // A detection on the synthetic label "Nom :" at analysis [0,5) cannot be remapped
            new SensitiveData("NAME", "Nom", "Nom", "", 0, 5, 0.5, "sel", DetectorSource.GLINER2),
            new SensitiveData("LAST_NAME", "Nom de famille", "Dupont", "", 6, 12, 0.8, "sel", DetectorSource.GLINER2));

        ContentPiiDetection result = DetectionOffsetRemapper.remap(detection, mapping);

        assertThat(result.sensitiveDataFound()).hasSize(1);
        assertThat(result.sensitiveDataFound().getFirst().value()).isEqualTo("Dupont");
    }

    @Test
    @DisplayName("Should keep the remapped value byte-identical against the context text")
    void Should_KeepValueConsistent_When_Remapping() {
        String contextText = "Dupont Lyon";
        OffsetMapping mapping = new OffsetMapping(List.of(
            new Segment(6, 6, 0),
            new Segment(23, 4, 7)));
        ContentPiiDetection detection = detectionWith(
            new SensitiveData("LAST_NAME", "Nom de famille", "Dupont", "", 6, 12, 0.8, "sel", DetectorSource.GLINER2),
            new SensitiveData("CITY", "Ville", "Lyon", "", 23, 27, 0.8, "sel", DetectorSource.GLINER2));

        ContentPiiDetection result = DetectionOffsetRemapper.remap(detection, mapping);

        for (SensitiveData data : result.sensitiveDataFound()) {
            assertThat(contextText.substring(data.position(), data.end())).isEqualTo(data.value());
        }
    }

    private static ContentPiiDetection detectionWith(SensitiveData... data) {
        return ContentPiiDetection.builder()
            .sensitiveDataFound(List.of(data))
            .build();
    }
}
