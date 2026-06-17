package pro.softcom.aisentinel.domain.pii.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("ExportContext")
class ExportContextTest {

    @Test
    @DisplayName("Should_CreateValidContext_When_AllFieldsProvided")
    void Should_CreateValidContext_When_AllFieldsProvided() {
        // Arrange
        DataSourceContact contact = new DataSourceContact("John Doe", "john@example.com");

        // Act
        ExportContext context = ExportContext.builder()
                .reportName("My Report")
                .reportIdentifier("RPT-001")
                .sourceUrl("https://wiki.example.com")
                .contacts(List.of(contact))
                .additionalMetadata(Map.of("key", "value"))
                .build();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(context.reportName()).isEqualTo("My Report");
            softly.assertThat(context.reportIdentifier()).isEqualTo("RPT-001");
            softly.assertThat(context.contacts()).hasSize(1);
            softly.assertThat(context.additionalMetadata()).containsEntry("key", "value");
        });
    }

    @Test
    @DisplayName("Should_ThrowException_When_ReportNameIsNull")
    void Should_ThrowException_When_ReportNameIsNull() {
        var builder = ExportContext.builder()
                .reportName(null)
                .reportIdentifier("RPT-001");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report name");
    }

    @Test
    @DisplayName("Should_ThrowException_When_ReportNameIsBlank")
    void Should_ThrowException_When_ReportNameIsBlank() {
        var builder = ExportContext.builder()
                .reportName("   ")
                .reportIdentifier("RPT-001");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report name");
    }

    @Test
    @DisplayName("Should_ThrowException_When_ReportIdentifierIsNull")
    void Should_ThrowException_When_ReportIdentifierIsNull() {
        var builder = ExportContext.builder()
                .reportName("My Report")
                .reportIdentifier(null);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report identifier");
    }

    @Test
    @DisplayName("Should_ThrowException_When_ReportIdentifierIsBlank")
    void Should_ThrowException_When_ReportIdentifierIsBlank() {
        var builder = ExportContext.builder()
                .reportName("My Report")
                .reportIdentifier("");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report identifier");
    }

    @Test
    @DisplayName("Should_DefaultContactsToEmptyList_When_ContactsIsNull")
    void Should_DefaultContactsToEmptyList_When_ContactsIsNull() {
        // Act
        ExportContext context = ExportContext.builder()
                .reportName("My Report")
                .reportIdentifier("RPT-001")
                .contacts(null)
                .build();

        // Assert
        assertThat(context.contacts()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Should_DefaultMetadataToEmptyMap_When_AdditionalMetadataIsNull")
    void Should_DefaultMetadataToEmptyMap_When_AdditionalMetadataIsNull() {
        // Act
        ExportContext context = ExportContext.builder()
                .reportName("My Report")
                .reportIdentifier("RPT-001")
                .additionalMetadata(null)
                .build();

        // Assert
        assertThat(context.additionalMetadata()).isNotNull().isEmpty();
    }
}
