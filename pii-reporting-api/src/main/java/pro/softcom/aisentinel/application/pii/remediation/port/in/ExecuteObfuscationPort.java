package pro.softcom.aisentinel.application.pii.remediation.port.in;

import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

/**
 * In-port submitting an asynchronous redaction job for a planned selection.
 */
public interface ExecuteObfuscationPort {

    /**
     * Re-resolves the selection, verifies the plan checksum and starts the job.
     *
     * @return the identifier of the created job
     */
    String execute(ObfuscationSubmission submission);

    record ObfuscationSubmission(RemediationSelection selection, String selectionChecksum, String actor) {
    }
}
