package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in;

import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManageDiscoveredLabelsPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManageDiscoveredLabelsPort.PromoteDiscoveredLabelCommand;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.DiscoveredLabelResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.PiiTypeConfigResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.PromoteDiscoveredLabelRequestDto;

import java.util.List;

/**
 * REST controller for the MINISTRAL discovered-label inbox.
 * <p>
 * Provides endpoints to:
 * - List labels awaiting review
 * - Promote a label to a custom PII type configuration
 * - Ignore a label
 */
@RestController
@RequestMapping("/api/v1/pii-detection/discovered-labels")
public class DiscoveredLabelController {

    private final ManageDiscoveredLabelsPort manageDiscoveredLabelsPort;

    private static final String PLACEHOLDER_USER = "admin";

    public DiscoveredLabelController(ManageDiscoveredLabelsPort manageDiscoveredLabelsPort) {
        this.manageDiscoveredLabelsPort = manageDiscoveredLabelsPort;
    }

    /**
     * List discovered labels awaiting operator review.
     * <p>
     * GET /api/v1/pii-detection/discovered-labels
     *
     * @return list of pending discovered labels
     */
    @GetMapping
    public ResponseEntity<@NonNull List<DiscoveredLabelResponseDto>> listPending() {
        List<DiscoveredLabel> pending = manageDiscoveredLabelsPort.listPending();
        List<DiscoveredLabelResponseDto> response = pending.stream()
                .map(DiscoveredLabelResponseDto::fromDomain)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Promote a discovered label to a custom MINISTRAL PII type configuration.
     * <p>
     * POST /api/v1/pii-detection/discovered-labels/{label}/promote
     *
     * @param label   the UPPER_SNAKE label to promote
     * @param request the promotion attributes
     * @return the created configuration with HTTP 201
     */
    @PostMapping("/{label}/promote")
    public ResponseEntity<@NonNull PiiTypeConfigResponseDto> promote(
            @PathVariable String label,
            @Valid @RequestBody PromoteDiscoveredLabelRequestDto request
    ) {
        var command = new PromoteDiscoveredLabelCommand(
                label,
                request.category(),
                request.severity(),
                request.threshold(),
                request.detectorLabel(),
                request.countryCode(),
                PLACEHOLDER_USER
        );
        PiiTypeConfig created = manageDiscoveredLabelsPort.promote(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PiiTypeConfigResponseDto.fromDomain(created));
    }

    /**
     * Ignore a discovered label, keeping it out of the inbox.
     * <p>
     * POST /api/v1/pii-detection/discovered-labels/{label}/ignore
     *
     * @param label the UPPER_SNAKE label to ignore
     * @return 204 No Content on success
     */
    @PostMapping("/{label}/ignore")
    public ResponseEntity<Void> ignore(@PathVariable String label) {
        manageDiscoveredLabelsPort.ignore(label);
        return ResponseEntity.noContent().build();
    }
}
