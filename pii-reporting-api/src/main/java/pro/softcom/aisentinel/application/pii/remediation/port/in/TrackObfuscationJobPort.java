package pro.softcom.aisentinel.application.pii.remediation.port.in;

import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;

import java.util.Optional;

/**
 * In-port for polling the status, progression and per-finding outcomes of a
 * redaction job.
 */
public interface TrackObfuscationJobPort {

    Optional<ObfuscationJob> findJob(String jobId);
}
