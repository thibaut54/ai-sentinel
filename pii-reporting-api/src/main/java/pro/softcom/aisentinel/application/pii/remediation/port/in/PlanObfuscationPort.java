package pro.softcom.aisentinel.application.pii.remediation.port.in;

import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

/**
 * In-port computing the read-only preview of an obfuscation run: the selection is
 * resolved server-side into concrete findings and summarised with a checksum that
 * execution later verifies.
 */
public interface PlanObfuscationPort {

    ObfuscationPlan plan(RemediationSelection selection);
}
