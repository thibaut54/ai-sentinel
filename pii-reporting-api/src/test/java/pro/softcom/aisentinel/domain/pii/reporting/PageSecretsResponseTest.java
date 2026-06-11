package pro.softcom.aisentinel.domain.pii.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PageSecretsResponse")
class PageSecretsResponseTest {

    @Test
    @DisplayName("Should_CreateValidResponse_When_AllFieldsAreValid")
    void Should_CreateValidResponse_When_AllFieldsAreValid() {
        // Arrange
        RevealedSecret secret = new RevealedSecret(0, 5, "value", "context", "masked");
        List<RevealedSecret> secrets = List.of(secret);

        // Act
        PageSecretsResponse response = new PageSecretsResponse("scan-1", "page-1", "My Page", secrets);

        // Assert
        assertThat(response.scanId()).isEqualTo("scan-1");
        assertThat(response.pageId()).isEqualTo("page-1");
        assertThat(response.secrets()).hasSize(1);
    }

    @Test
    @DisplayName("Should_ThrowException_When_ScanIdIsNull")
    void Should_ThrowException_When_ScanIdIsNull() {
        assertThatThrownBy(() -> new PageSecretsResponse(null, "page-1", "Title", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_ScanIdIsBlank")
    void Should_ThrowException_When_ScanIdIsBlank() {
        assertThatThrownBy(() -> new PageSecretsResponse("   ", "page-1", "Title", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_PageIdIsNull")
    void Should_ThrowException_When_PageIdIsNull() {
        assertThatThrownBy(() -> new PageSecretsResponse("scan-1", null, "Title", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_PageIdIsBlank")
    void Should_ThrowException_When_PageIdIsBlank() {
        assertThatThrownBy(() -> new PageSecretsResponse("scan-1", "", "Title", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_SecretsIsNull")
    void Should_ThrowException_When_SecretsIsNull() {
        assertThatThrownBy(() -> new PageSecretsResponse("scan-1", "page-1", "Title", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secrets");
    }

    @Test
    @DisplayName("Should_AcceptEmptySecretsList_When_NoSecretsFound")
    void Should_AcceptEmptySecretsList_When_NoSecretsFound() {
        // Act
        PageSecretsResponse response = new PageSecretsResponse("scan-1", "page-1", null, List.of());

        // Assert
        assertThat(response.secrets()).isEmpty();
        assertThat(response.pageTitle()).isNull();
    }
}
