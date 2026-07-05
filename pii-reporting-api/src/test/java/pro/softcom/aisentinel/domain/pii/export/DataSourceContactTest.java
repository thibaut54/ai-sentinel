package pro.softcom.aisentinel.domain.pii.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataSourceContact")
class DataSourceContactTest {

    @Test
    @DisplayName("Should_CreateValidContact_When_AllFieldsValid")
    void Should_CreateValidContact_When_AllFieldsValid() {
        // Act
        DataSourceContact contact = new DataSourceContact("John Doe", "john@example.com");

        // Assert
        assertThat(contact.displayName()).isEqualTo("John Doe");
        assertThat(contact.email()).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("Should_ThrowException_When_DisplayNameIsNull")
    void Should_ThrowException_When_DisplayNameIsNull() {
        assertThatThrownBy(() -> new DataSourceContact(null, "john@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display name");
    }

    @Test
    @DisplayName("Should_ThrowException_When_DisplayNameIsBlank")
    void Should_ThrowException_When_DisplayNameIsBlank() {
        assertThatThrownBy(() -> new DataSourceContact("   ", "john@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display name");
    }

    @Test
    @DisplayName("Should_ThrowException_When_EmailIsNull")
    void Should_ThrowException_When_EmailIsNull() {
        assertThatThrownBy(() -> new DataSourceContact("John Doe", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("Should_ThrowException_When_EmailIsBlank")
    void Should_ThrowException_When_EmailIsBlank() {
        assertThatThrownBy(() -> new DataSourceContact("John Doe", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }
}
