package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort.TestSharePointConnectionCommand;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort.UpdateSharePointConnectionCommand;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.SharePointConnectionConfigResponseDto;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.TestSharePointConnectionRequestDto;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.UpdateSharePointConnectionConfigRequestDto;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;

/**
 * REST API endpoint for managing SharePoint connection configuration.
 */
@RestController
@RequestMapping("/api/v1/sharepoint/connection-config")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "SharePoint Connection Config", description = "Manage SharePoint connection configuration")
public class SharePointConnectionConfigController {

    private static final String MASKED_SECRET = "***";
    private static final String SYSTEM_USER = "system";

    private final ManageSharePointConnectionPort manageSharePointConnectionPort;

    @GetMapping
    @Operation(summary = "Get current SharePoint connection configuration")
    public CompletableFuture<ResponseEntity<@NonNull SharePointConnectionConfigResponseDto>> getConfig() {
        log.debug("GET /api/v1/sharepoint/connection-config - Retrieving current configuration");

        return CompletableFuture.supplyAsync(() -> {
            try {
                SharePointConnectionSettings settings = manageSharePointConnectionPort.getConnectionSettings();
                SharePointConnectionConfigResponseDto response = toResponseDto(settings);
                return ResponseEntity.ok(response);

            } catch (Exception ex) {
                log.error("Failed to retrieve SharePoint connection configuration: {}", ex.getMessage(), ex);
                return ResponseEntity.internalServerError().<SharePointConnectionConfigResponseDto>build();
            }
        });
    }

    @PutMapping
    @Operation(summary = "Update SharePoint connection configuration")
    public CompletableFuture<ResponseEntity<@NonNull SharePointConnectionConfigResponseDto>> updateConfig(
            @Valid @RequestBody UpdateSharePointConnectionConfigRequestDto request,
            Principal principal) {

        String updatedBy = principal != null ? principal.getName() : SYSTEM_USER;
        log.info("PUT /api/v1/sharepoint/connection-config - Updating: tenantId={}, clientId={}, enabled={}, updatedBy={}",
                request.tenantId(), request.clientId(), request.enabled(), updatedBy);

        return CompletableFuture.supplyAsync(() -> {
            try {
                UpdateSharePointConnectionCommand command = new UpdateSharePointConnectionCommand(
                        request.tenantId(),
                        request.clientId(),
                        request.clientSecret(),
                        request.enabled(),
                        updatedBy
                );

                SharePointConnectionSettings updatedSettings = manageSharePointConnectionPort.updateConnectionSettings(command);
                SharePointConnectionConfigResponseDto response = toResponseDto(updatedSettings);

                log.info("Configuration updated successfully by user: {}", updatedBy);
                return ResponseEntity.ok(response);

            } catch (IllegalArgumentException ex) {
                log.warn("Invalid configuration request: {}", ex.getMessage());
                return ResponseEntity.badRequest().<SharePointConnectionConfigResponseDto>build();

            } catch (Exception ex) {
                log.error("Failed to update SharePoint connection configuration: {}", ex.getMessage(), ex);
                return ResponseEntity.internalServerError().<SharePointConnectionConfigResponseDto>build();
            }
        });
    }

    @PostMapping("/test")
    @Operation(summary = "Test SharePoint connection")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<ResponseEntity<@NonNull ConnectionTestResultDto>> testConnection(
            @Valid @RequestBody TestSharePointConnectionRequestDto request) {

        log.info("POST /api/v1/sharepoint/connection-config/test - Testing connection: tenantId={}", request.tenantId());

        return CompletableFuture.supplyAsync(() -> {
            try {
                TestSharePointConnectionCommand command = new TestSharePointConnectionCommand(
                        request.tenantId(),
                        request.clientId(),
                        request.clientSecret()
                );

                boolean success = manageSharePointConnectionPort.testConnection(command);
                String message = success
                        ? "Connection to SharePoint established successfully"
                        : "Failed to connect to SharePoint";

                return ResponseEntity.ok(new ConnectionTestResultDto(success, message));

            } catch (Exception ex) {
                log.error("Connection test failed: {}", ex.getMessage(), ex);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ConnectionTestResultDto(false, "Connection test failed: " + ex.getMessage()));
            }
        });
    }

    private SharePointConnectionConfigResponseDto toResponseDto(SharePointConnectionSettings settings) {
        return new SharePointConnectionConfigResponseDto(
                settings.tenantId(),
                settings.clientId(),
                MASKED_SECRET,
                settings.enabled(),
                settings.updatedAt(),
                settings.updatedBy(),
                manageSharePointConnectionPort.isConfigured()
        );
    }

    public record ConnectionTestResultDto(boolean success, String message) {
    }
}
