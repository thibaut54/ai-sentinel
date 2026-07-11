package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.softcom.aisentinel.infrastructure.config.properties.PiiRemediationProperties;
import pro.softcom.aisentinel.infrastructure.config.properties.PiiReportingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class RemediationConfigAdapterTest {

    @DisplayName("Remediation is enabled only when the feature flag and secret reveal are both on")
    @ParameterizedTest(name = "enabled={0}, allowSecretReveal={1} -> {2}")
    @CsvSource({
            "true,  true,  true",
            "true,  false, false",
            "false, true,  false",
            "false, false, false"
    })
    void Should_RequireBothFlags_When_ReportingEnabled(boolean enabled, boolean allowSecretReveal, boolean expected) {
        PiiRemediationProperties remediationProperties = new PiiRemediationProperties();
        remediationProperties.setEnabled(enabled);
        PiiReportingProperties reportingProperties = new PiiReportingProperties();
        reportingProperties.setAllowSecretReveal(allowSecretReveal);
        RemediationConfigAdapter adapter = new RemediationConfigAdapter(remediationProperties, reportingProperties);

        assertThat(adapter.isRemediationEnabled()).isEqualTo(expected);
    }
}
