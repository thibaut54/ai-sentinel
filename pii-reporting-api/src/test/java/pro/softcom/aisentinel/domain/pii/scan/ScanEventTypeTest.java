package pro.softcom.aisentinel.domain.pii.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScanEventType")
class ScanEventTypeTest {

    @Test
    @DisplayName("Should_ReturnNull_When_ValueIsNull")
    void Should_ReturnNull_When_ValueIsNull() {
        assertThat(ScanEventType.fromValue(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Should_ReturnNull_When_ValueIsBlankOrEmpty")
    void Should_ReturnNull_When_ValueIsBlankOrEmpty(String value) {
        assertThat(ScanEventType.fromValue(value)).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNull_When_ValueIsUnknown")
    void Should_ReturnNull_When_ValueIsUnknown() {
        assertThat(ScanEventType.fromValue("unknownType")).isNull();
    }

    @Test
    @DisplayName("Should_ParseCaseInsensitive_When_ValueIsMixedCase")
    void Should_ParseCaseInsensitive_When_ValueIsMixedCase() {
        assertThat(ScanEventType.fromValue("ITEM")).isEqualTo(ScanEventType.ITEM);
        assertThat(ScanEventType.fromValue("item")).isEqualTo(ScanEventType.ITEM);
        assertThat(ScanEventType.fromValue("Item")).isEqualTo(ScanEventType.ITEM);
    }

    @Test
    @DisplayName("Should_ReturnCorrectValue_When_GetValueCalledOnEachEnumEntry")
    void Should_ReturnCorrectValue_When_GetValueCalledOnEachEnumEntry() {
        assertThat(ScanEventType.START.getValue()).isEqualTo("start");
        assertThat(ScanEventType.COMPLETE.getValue()).isEqualTo("complete");
        assertThat(ScanEventType.ERROR.getValue()).isEqualTo("scanError");
        assertThat(ScanEventType.PAGE_START.getValue()).isEqualTo("pageStart");
        assertThat(ScanEventType.PAGE_COMPLETE.getValue()).isEqualTo("pageComplete");
        assertThat(ScanEventType.ITEM.getValue()).isEqualTo("item");
        assertThat(ScanEventType.ATTACHMENT_ITEM.getValue()).isEqualTo("attachmentItem");
        assertThat(ScanEventType.KEEPALIVE.getValue()).isEqualTo("keepalive");
    }

    @Test
    @DisplayName("Should_ParseScanError_When_ValueIsScanError")
    void Should_ParseScanError_When_ValueIsScanError() {
        assertThat(ScanEventType.fromValue("scanError")).isEqualTo(ScanEventType.ERROR);
    }
}
