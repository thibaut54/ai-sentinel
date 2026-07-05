package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PersonallyIdentifiableInformationType")
class PersonallyIdentifiableInformationTypeTest {

    @Test
    @DisplayName("Should_MapEmailToCorrectDataType_When_EmailEnumUsed")
    void Should_MapEmailToCorrectDataType_When_EmailEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.EMAIL.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.EMAIL);
    }

    @Test
    @DisplayName("Should_MapPasswordToCorrectDataType_When_PasswordEnumUsed")
    void Should_MapPasswordToCorrectDataType_When_PasswordEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.PASSWORD.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.PASSWORD);
    }

    @Test
    @DisplayName("Should_ContainAllExpectedValues_When_EnumValuesListed")
    void Should_ContainAllExpectedValues_When_EnumValuesListed() {
        PersonallyIdentifiableInformationType[] values = PersonallyIdentifiableInformationType.values();
        assertThat(values).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("Should_MapUnknownToUnknownDataType_When_UnknownEnumUsed")
    void Should_MapUnknownToUnknownDataType_When_UnknownEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.UNKNOWN.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.UNKNOWN);
    }

    @Test
    @DisplayName("Should_MapPersonNameToNameDataType_When_PersonNameEnumUsed")
    void Should_MapPersonNameToNameDataType_When_PersonNameEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.PERSON_NAME.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.NAME);
    }

    @Test
    @DisplayName("Should_MapCreditCardToCorrectDataType_When_CreditCardEnumUsed")
    void Should_MapCreditCardToCorrectDataType_When_CreditCardEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.CREDIT_CARD_NUMBER.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.CREDIT_CARD);
    }

    @Test
    @DisplayName("Should_MapIbanToCorrectDataType_When_IbanEnumUsed")
    void Should_MapIbanToCorrectDataType_When_IbanEnumUsed() {
        assertThat(PersonallyIdentifiableInformationType.IBAN.dataType())
                .isEqualTo(ContentPiiDetection.PersonallyIdentifiableInformationType.IBAN);
    }

    @Test
    @DisplayName("Should_HaveNonNullDataType_When_AnyEnumValueUsed")
    void Should_HaveNonNullDataType_When_AnyEnumValueUsed() {
        for (PersonallyIdentifiableInformationType type : PersonallyIdentifiableInformationType.values()) {
            assertThat(type.dataType())
                    .as("dataType() for %s must not be null", type)
                    .isNotNull();
        }
    }
}
