package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.PlanObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.TrackObfuscationJobPort;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobCreatedDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobStatusDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationPlanDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSelectionDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper.RemediationJobDtoMapper;

import java.security.Principal;

/**
 * REST controller for planning and executing PII redaction jobs. Every endpoint sits
 * behind the {@code pii.remediation.enabled} feature flag; the use cases re-check it
 * (defense in depth).
 */
@RestController
@RequestMapping("/api/v1/pii/remediation")
@Tag(name = "PII Remediation Jobs", description = "Planning, execution and polling of bulk redaction jobs")
@RequiredArgsConstructor
@Slf4j
public class PiiRemediationJobController {

    private static final String SYSTEM_ACTOR = "system";

    private final PlanObfuscationPort planObfuscationPort;
    private final ExecuteObfuscationPort executeObfuscationPort;
    private final TrackObfuscationJobPort trackObfuscationJobPort;
    private final RemediationJobDtoMapper mapper;

    @PostMapping("/plan")
    @Operation(summary = "Computes the read-only preview of an obfuscation run for a selection")
    @PreAuthorize("@environment.getProperty('pii.remediation.enabled', 'false') == 'true'")
    @ApiResponse(responseCode = "200", description = "Plan computed")
    @ApiResponse(responseCode = "403", description = "Remediation disabled by configuration")
    public ResponseEntity<@NonNull ObfuscationPlanDto> plan(@RequestBody RemediationSelectionDto selection) {
        ObfuscationPlan plan = planObfuscationPort.plan(mapper.toSelection(selection));
        return ResponseEntity.ok(mapper.toDto(plan));
    }

    @PostMapping("/jobs")
    @Operation(summary = "Submits an asynchronous redaction job for a planned selection")
    @PreAuthorize("@environment.getProperty('pii.remediation.enabled', 'false') == 'true'")
    @ApiResponse(responseCode = "202", description = "Job accepted and started")
    @ApiResponse(responseCode = "403", description = "Remediation disabled by configuration")
    @ApiResponse(responseCode = "409", description = "Selection outdated or a job is already running for the space")
    public ResponseEntity<@NonNull ObfuscationJobCreatedDto> submitJob(
            @RequestBody ObfuscationJobRequestDto request, Principal principal) {
        String actor = principal != null ? principal.getName() : SYSTEM_ACTOR;
        String jobId = executeObfuscationPort.execute(mapper.toSubmission(request, actor));
        log.info("[PII_REMEDIATION] Redaction job {} submitted by actor={}", jobId, actor);
        return ResponseEntity.accepted().body(new ObfuscationJobCreatedDto(jobId));
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Returns the status, progression and outcomes of a redaction job")
    @PreAuthorize("@environment.getProperty('pii.remediation.enabled', 'false') == 'true'")
    @ApiResponse(responseCode = "200", description = "Job returned")
    @ApiResponse(responseCode = "403", description = "Remediation disabled by configuration")
    @ApiResponse(responseCode = "404", description = "Unknown job")
    public ResponseEntity<@NonNull ObfuscationJobStatusDto> getJob(@PathVariable("id") String id) {
        return trackObfuscationJobPort.findJob(id)
                .map(job -> ResponseEntity.ok(mapper.toDto(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
