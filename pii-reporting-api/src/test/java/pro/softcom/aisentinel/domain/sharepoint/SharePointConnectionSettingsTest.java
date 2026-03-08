package pro.softcom.aisentinel.domain.sharepoint;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SharePointConnectionSettings} domain record.
 */
class SharePointConnectionSettingsTest {

    private static final Long ID = 1L;
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CLIENT_ID = "00000000-0000-0000-0000-000000000002";
    private static final Instant UPDATED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final String UPDATED_BY = "admin@example.com";

    @Test
    void Should_CreateSettings_When_ValidDataProvided() {
        // Act
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, CLIENT_ID, true, UPDATED_AT, UPDATED_BY
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(settings.id()).isEqualTo(ID);
        softly.assertThat(settings.tenantId()).isEqualTo(TENANT_ID);
        softly.assertThat(settings.clientId()).isEqualTo(CLIENT_ID);
        softly.assertThat(settings.enabled()).isTrue();
        softly.assertThat(settings.updatedAt()).isEqualTo(UPDATED_AT);
        softly.assertThat(settings.updatedBy()).isEqualTo(UPDATED_BY);
        softly.assertAll();
    }

    @Test
    void Should_CreateDisabledSettings_When_EnabledIsFalse() {
        // Act
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, CLIENT_ID, false, UPDATED_AT, UPDATED_BY
        );

        // Assert
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    void Should_CreateSettings_When_IdIsNull() {
        // Act
        var settings = new SharePointConnectionSettings(
            null, TENANT_ID, CLIENT_ID, true, UPDATED_AT, UPDATED_BY
        );

        // Assert
        assertThat(settings.id()).isNull();
    }

    @Test
    void Should_CreateSettings_When_UpdatedAtIsNull() {
        // Act
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, CLIENT_ID, true, null, UPDATED_BY
        );

        // Assert
        assertThat(settings.updatedAt()).isNull();
    }

    @Test
    void Should_CreateSettings_When_UpdatedByIsNull() {
        // Act
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, CLIENT_ID, true, UPDATED_AT, null
        );

        // Assert
        assertThat(settings.updatedBy()).isNull();
    }

    @Test
    void Should_DefaultToEmpty_When_TenantIdIsNull() {
        var settings = new SharePointConnectionSettings(
            ID, null, CLIENT_ID, true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.tenantId()).isEmpty();
    }

    @Test
    void Should_KeepBlank_When_TenantIdIsBlank() {
        var settings = new SharePointConnectionSettings(
            ID, "   ", CLIENT_ID, true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.tenantId()).isEqualTo("   ");
    }

    @Test
    void Should_KeepEmpty_When_TenantIdIsEmpty() {
        var settings = new SharePointConnectionSettings(
            ID, "", CLIENT_ID, true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.tenantId()).isEmpty();
    }

    @Test
    void Should_DefaultToEmpty_When_ClientIdIsNull() {
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, null, true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.clientId()).isEmpty();
    }

    @Test
    void Should_KeepBlank_When_ClientIdIsBlank() {
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, "   ", true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.clientId()).isEqualTo("   ");
    }

    @Test
    void Should_KeepEmpty_When_ClientIdIsEmpty() {
        var settings = new SharePointConnectionSettings(
            ID, TENANT_ID, "", true, UPDATED_AT, UPDATED_BY
        );
        assertThat(settings.clientId()).isEmpty();
    }

    @Test
    void Should_NotContainClientSecret_When_Created() {
        // This test documents the design decision: clientSecret is an infrastructure concern
        // Verify via record components that no secret field exists
        assertThat(SharePointConnectionSettings.class.getRecordComponents())
            .extracting("name")
            .isNotEmpty()
            .doesNotContain("clientSecret", "secret", "password");
    }
}
