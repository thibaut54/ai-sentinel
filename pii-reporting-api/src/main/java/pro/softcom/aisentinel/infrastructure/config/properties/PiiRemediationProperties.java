package pro.softcom.aisentinel.infrastructure.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the PII remediation (bulk redaction) feature.
 * Business intent: redaction rewrites source Confluence pages irreversibly, so the whole
 * feature ships behind an explicit opt-in flag.
 */
@Configuration
@ConfigurationProperties(prefix = "pii.remediation")
@Data
public class PiiRemediationProperties {

    /**
     * Master switch for the remediation endpoints and use cases.
     *
     * <p>When false (default), every remediation endpoint except {@code GET /config}
     * returns 403 and the use cases reject execution (defense in depth).</p>
     */
    private boolean enabled = false;
}
