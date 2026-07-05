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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ChangeFindingStatusPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeResult;
import pro.softcom.aisentinel.application.pii.remediation.port.in.QueryRemediationFindingsPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationFindingsResult;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.FindingStatusChangeRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.FindingStatusChangeResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationConfigDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSearchRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSearchResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper.RemediationDtoMapper;

import java.security.Principal;

/**
 * REST controller for PII remediation: server-computed findings view and finding
 * lifecycle transitions. Every endpoint except {@code GET /config} sits behind the
 * {@code pii.remediation.enabled} feature flag; the use cases re-check it
 * (defense in depth).
 */
@RestController
@RequestMapping("/api/v1/pii/remediation")
@Tag(name = "PII Remediation", description = "Bulk redaction of detected PII and finding lifecycle")
@RequiredArgsConstructor
@Slf4j
public class PiiRemediationController {

    private static final String SYSTEM_ACTOR = "system";

    private final QueryRemediationFindingsPort queryRemediationFindingsPort;
    private final ChangeFindingStatusPort changeFindingStatusPort;
    private final RemediationDtoMapper mapper;

    @GetMapping("/config")
    @Operation(summary = "Returns whether the remediation feature is enabled")
    @ApiResponse(responseCode = "200", description = "Configuration returned")
    public ResponseEntity<@NonNull RemediationConfigDto> getConfig() {
        return ResponseEntity.ok(new RemediationConfigDto(queryRemediationFindingsPort.isRemediationEnabled()));
    }

    @PostMapping("/findings/search")
    @Operation(summary = "Searches findings grouped and paginated server-side, with the selection resolved")
    @PreAuthorize("@environment.getProperty('pii.remediation.enabled', 'false') == 'true'")
    @ApiResponse(responseCode = "200", description = "Findings view returned")
    @ApiResponse(responseCode = "403", description = "Remediation disabled by configuration")
    public ResponseEntity<@NonNull RemediationSearchResponseDto> searchFindings(
            @RequestBody RemediationSearchRequestDto request) {
        RemediationFindingsResult result = queryRemediationFindingsPort.search(mapper.toQuery(request));
        return ResponseEntity.ok(mapper.toDto(result));
    }

    @PostMapping("/findings/status")
    @Operation(summary = "Applies a batch of finding lifecycle transitions")
    @PreAuthorize("@environment.getProperty('pii.remediation.enabled', 'false') == 'true'")
    @ApiResponse(responseCode = "200", description = "Batch processed; each change applied or rejected")
    @ApiResponse(responseCode = "403", description = "Remediation disabled by configuration")
    public ResponseEntity<@NonNull FindingStatusChangeResponseDto> changeFindingStatuses(
            @RequestBody FindingStatusChangeRequestDto request, Principal principal) {
        String actor = principal != null ? principal.getName() : SYSTEM_ACTOR;
        FindingStatusChangeResult result = changeFindingStatusPort.changeStatuses(
                mapper.toCommand(request, actor));
        log.info("[PII_REMEDIATION] Status batch by actor={}: {} applied, {} rejected",
                actor, result.applied().size(), result.rejected().size());
        return ResponseEntity.ok(mapper.toDto(result));
    }
}
