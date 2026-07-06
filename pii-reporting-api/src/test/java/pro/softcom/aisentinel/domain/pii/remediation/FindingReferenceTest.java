package pro.softcom.aisentinel.domain.pii.remediation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("FindingReference")
class FindingReferenceTest {

    private static FindingReference.FindingReferenceBuilder reference() {
        return FindingReference.builder()
                .spaceKey("SPACE")
                .pageId("12345")
                .detector("MINISTRAL")
                .piiType("EMAIL_ADDRESS")
                .severity(PersonallyIdentifiableInformationSeverity.MEDIUM)
                .valueFingerprint("a1b2c3d4e5f6");
    }

    @Nested
    @DisplayName("findingId")
    class FindingId {

        @Test
        @DisplayName("Should_ReturnSameId_When_ComputedTwiceOnSameReference")
        void Should_ReturnSameId_When_ComputedTwiceOnSameReference() {
            FindingReference findingReference = reference().build();

            assertThat(findingReference.findingId()).isEqualTo(findingReference.findingId());
        }

        @Test
        @DisplayName("Should_ReturnSameId_When_TwoReferencesShareIdentityFields")
        void Should_ReturnSameId_When_TwoReferencesShareIdentityFields() {
            assertThat(reference().build().findingId()).isEqualTo(reference().build().findingId());
        }

        @Test
        @DisplayName("Should_Return64CharacterLowercaseHex_When_Computed")
        void Should_Return64CharacterLowercaseHex_When_Computed() {
            assertThat(reference().build().findingId()).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Should_ChangeId_When_AnyIdentityFieldDiffers")
        void Should_ChangeId_When_AnyIdentityFieldDiffers() {
            String baseId = reference().build().findingId();

            assertSoftly(softly -> {
                softly.assertThat(reference().spaceKey("OTHER").build().findingId())
                        .as("spaceKey").isNotEqualTo(baseId);
                softly.assertThat(reference().pageId("99999").build().findingId())
                        .as("pageId").isNotEqualTo(baseId);
                softly.assertThat(reference().attachmentName("report.pdf").build().findingId())
                        .as("attachmentName").isNotEqualTo(baseId);
                softly.assertThat(reference().detector("REGEX").build().findingId())
                        .as("detector").isNotEqualTo(baseId);
                softly.assertThat(reference().piiType("PHONE_NUMBER").build().findingId())
                        .as("piiType").isNotEqualTo(baseId);
                softly.assertThat(reference().valueFingerprint("ffffff").build().findingId())
                        .as("valueFingerprint").isNotEqualTo(baseId);
            });
        }

        @Test
        @DisplayName("Should_KeepSameId_When_OnlySeverityDiffers")
        void Should_KeepSameId_When_OnlySeverityDiffers() {
            String baseId = reference().build().findingId();

            String recalibratedId = reference()
                    .severity(PersonallyIdentifiableInformationSeverity.HIGH)
                    .build()
                    .findingId();

            assertThat(recalibratedId).isEqualTo(baseId);
        }

        @Test
        @DisplayName("Should_DifferentiateIds_When_SameValueAppearsOnPageBodyAndAttachment")
        void Should_DifferentiateIds_When_SameValueAppearsOnPageBodyAndAttachment() {
            String pageBodyId = reference().build().findingId();
            String attachmentId = reference().attachmentName("report.pdf").build().findingId();

            assertThat(attachmentId).isNotEqualTo(pageBodyId);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("Should_RejectConstruction_When_SpaceKeyIsBlank")
        void Should_RejectConstruction_When_SpaceKeyIsBlank() {
            assertThatThrownBy(() -> reference().spaceKey(" ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spaceKey");
        }

        @Test
        @DisplayName("Should_RejectConstruction_When_PageIdIsMissing")
        void Should_RejectConstruction_When_PageIdIsMissing() {
            assertThatThrownBy(() -> reference().pageId(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageId");
        }

        @Test
        @DisplayName("Should_RejectConstruction_When_DetectorIsMissing")
        void Should_RejectConstruction_When_DetectorIsMissing() {
            assertThatThrownBy(() -> reference().detector(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("detector");
        }

        @Test
        @DisplayName("Should_RejectConstruction_When_PiiTypeIsMissing")
        void Should_RejectConstruction_When_PiiTypeIsMissing() {
            assertThatThrownBy(() -> reference().piiType(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("piiType");
        }

        @Test
        @DisplayName("Should_RejectConstruction_When_SeverityIsMissing")
        void Should_RejectConstruction_When_SeverityIsMissing() {
            assertThatThrownBy(() -> reference().severity(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("severity");
        }

        @Test
        @DisplayName("Should_RejectConstruction_When_ValueFingerprintIsBlank")
        void Should_RejectConstruction_When_ValueFingerprintIsBlank() {
            assertThatThrownBy(() -> reference().valueFingerprint("").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valueFingerprint");
        }

        @Test
        @DisplayName("Should_AcceptNullAttachmentName_When_FindingIsOnPageBody")
        void Should_AcceptNullAttachmentName_When_FindingIsOnPageBody() {
            assertThat(reference().build().attachmentName()).isNull();
        }

        @Test
        @DisplayName("Should_RejectBlankAttachmentName_When_Provided")
        void Should_RejectBlankAttachmentName_When_Provided() {
            assertThatThrownBy(() -> reference().attachmentName(" ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attachmentName");
        }
    }
}
