package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Verifies the Jackson mapping used by the scan_events JSONB payload adapters
 * ({@code valueToTree} on write, {@code treeToValue} on read) for the
 * valueFingerprint field, including payloads persisted before it existed.
 */
@DisplayName("Scan event payload serialization")
class ScanEventPayloadSerializationTest {

    private static final String FINGERPRINT = "3f1a9c0d5b7e2a4c6d8f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should_DeserializeValueFingerprintAsNull_When_HistoricalPayloadHasNoFingerprintField")
    void Should_DeserializeValueFingerprintAsNull_When_HistoricalPayloadHasNoFingerprintField() throws Exception {
        // Payload shape persisted before valueFingerprint was introduced
        String historicalPayload = """
            {
              "scanId": "scan-1",
              "spaceKey": "SPACE",
              "eventType": "item",
              "pageId": "p1",
              "detectedPIIs": [
                {
                  "startPosition": 9,
                  "endPosition": 25,
                  "piiType": "EMAIL",
                  "piiTypeLabel": "Email",
                  "confidence": 0.95,
                  "sensitiveValue": "ENC:v1:c2FsdA==:aXY=:Y2lwaGVydGV4dA==",
                  "maskedContext": "Contact: [EMAIL]",
                  "source": "REGEX"
                }
              ]
            }
            """;
        JsonNode payload = objectMapper.readTree(historicalPayload);

        ConfluenceContentScanResult result =
                objectMapper.treeToValue(payload, ConfluenceContentScanResult.class);

        assertSoftly(softly -> {
            softly.assertThat(result.detectedPIIs()).hasSize(1);
            DetectedPersonallyIdentifiableInformation pii = result.detectedPIIs().getFirst();
            softly.assertThat(pii.valueFingerprint()).isNull();
            softly.assertThat(pii.piiType()).isEqualTo("EMAIL");
            softly.assertThat(pii.maskedContext()).isEqualTo("Contact: [EMAIL]");
        });
    }

    @Test
    @DisplayName("Should_PreserveValueFingerprint_When_PayloadRoundTripsThroughJson")
    void Should_PreserveValueFingerprint_When_PayloadRoundTripsThroughJson() throws Exception {
        DetectedPersonallyIdentifiableInformation pii = DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(0)
                .endPosition(5)
                .piiType("EMAIL")
                .piiTypeLabel("Email")
                .confidence(0.9)
                .maskedContext("Contact: [EMAIL]")
                .source(DetectorSource.REGEX)
                .valueFingerprint(FINGERPRINT)
                .build();
        ConfluenceContentScanResult event = ConfluenceContentScanResult.builder()
                .scanId("scan-1")
                .spaceKey("SPACE")
                .eventType("item")
                .pageId("p1")
                .detectedPIIs(List.of(pii))
                .build();

        JsonNode payload = objectMapper.valueToTree(event);
        ConfluenceContentScanResult rehydrated =
                objectMapper.treeToValue(payload, ConfluenceContentScanResult.class);

        assertThat(rehydrated.detectedPIIs().getFirst().valueFingerprint())
                .isEqualTo(FINGERPRINT);
    }
}
