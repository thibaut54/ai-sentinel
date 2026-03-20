package pro.softcom.aisentinel.application.jira.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Port IN for streaming Jira PII scan results.
 */
public interface StreamJiraScanPort {
    Flux<ContentScanResult> scanAllProjects();
    Flux<ContentScanResult> scanSelectedProjects(List<String> projectKeys);
}
