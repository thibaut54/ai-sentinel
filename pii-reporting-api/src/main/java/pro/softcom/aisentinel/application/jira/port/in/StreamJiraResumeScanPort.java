package pro.softcom.aisentinel.application.jira.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

/**
 * Port IN for resuming a paused Jira PII scan from the last checkpoint.
 */
public interface StreamJiraResumeScanPort {
    Flux<ContentScanResult> resumeAllProjects(String scanId);
}
