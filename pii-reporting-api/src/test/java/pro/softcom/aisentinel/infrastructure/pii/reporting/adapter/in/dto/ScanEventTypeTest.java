package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// Note: ScanEventType (this package) and pro.softcom.aisentinel.domain.pii.scan.ScanEventType
// share the same simple name — domain type is qualified inline where needed.
@DisplayName("ScanEventType (DTO)")
class ScanEventTypeTest {

    @Test
    @DisplayName("Should_ReturnNull_When_ValueIsNull")
    void Should_ReturnNull_When_ValueIsNull() {
        assertThat(ScanEventType.from(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Should_ReturnNull_When_ValueIsBlankOrEmpty")
    void Should_ReturnNull_When_ValueIsBlankOrEmpty(String value) {
        assertThat(ScanEventType.from(value)).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNull_When_ValueIsUnknown")
    void Should_ReturnNull_When_ValueIsUnknown() {
        assertThat(ScanEventType.from("unknownType")).isNull();
    }

    @Test
    @DisplayName("Should_ReturnCorrectToJson_When_AllEnumValuesUsed")
    void Should_ReturnCorrectToJson_When_AllEnumValuesUsed() {
        assertThat(ScanEventType.START.toJson()).isEqualTo("start");
        assertThat(ScanEventType.ERROR.toJson()).isEqualTo("scanError");
        assertThat(ScanEventType.COMPLETE.toJson()).isEqualTo("complete");
        assertThat(ScanEventType.ITEM.toJson()).isEqualTo("item");
        assertThat(ScanEventType.PAGE_START.toJson()).isEqualTo("pageStart");
        assertThat(ScanEventType.PAGE_COMPLETE.toJson()).isEqualTo("pageComplete");
    }

    @Test
    @DisplayName("Should_ReturnNull_When_DomainTypeIsNull")
    void Should_ReturnNull_When_DomainTypeIsNull() {
        assertThat(ScanEventType.fromDomain(null)).isNull();
    }

    @Test
    @DisplayName("Should_MapFromDomain_When_DomainTypeIsStart")
    void Should_MapFromDomain_When_DomainTypeIsStart() {
        ScanEventType result = ScanEventType.fromDomain(pro.softcom.aisentinel.domain.pii.scan.ScanEventType.START);
        assertThat(result).isEqualTo(ScanEventType.START);
    }

    @Test
    @DisplayName("Should_MapFromDomain_When_DomainTypeIsError")
    void Should_MapFromDomain_When_DomainTypeIsError() {
        ScanEventType result = ScanEventType.fromDomain(pro.softcom.aisentinel.domain.pii.scan.ScanEventType.ERROR);
        assertThat(result).isEqualTo(ScanEventType.ERROR);
    }

    @Test
    @DisplayName("Should_ConvertToDomain_When_ToDomainCalledOnItem")
    void Should_ConvertToDomain_When_ToDomainCalledOnItem() {
        pro.softcom.aisentinel.domain.pii.scan.ScanEventType domainType = ScanEventType.ITEM.toDomain();
        assertThat(domainType).isEqualTo(pro.softcom.aisentinel.domain.pii.scan.ScanEventType.ITEM);
    }

    @Test
    @DisplayName("Should_ConvertToDomain_When_ToDomainCalledOnComplete")
    void Should_ConvertToDomain_When_ToDomainCalledOnComplete() {
        pro.softcom.aisentinel.domain.pii.scan.ScanEventType domainType = ScanEventType.COMPLETE.toDomain();
        assertThat(domainType).isEqualTo(pro.softcom.aisentinel.domain.pii.scan.ScanEventType.COMPLETE);
    }

    @Test
    @DisplayName("Should_ParseCaseInsensitive_When_ValueIsMixedCase")
    void Should_ParseCaseInsensitive_When_ValueIsMixedCase() {
        assertThat(ScanEventType.from("ITEM")).isEqualTo(ScanEventType.ITEM);
    }
}
