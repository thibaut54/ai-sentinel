package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pro.softcom.aisentinel.application.sharepoint.port.in.SharePointScanPort;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for SharePoint operations.
 * Exposes endpoints to browse SharePoint sites and document libraries.
 */
@RestController
@RequestMapping("/api/v1/sharepoint")
@Tag(name = "SharePoint", description = "SharePoint operations")
@ConditionalOnProperty(prefix = "sharepoint", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class SharePointController {

    private final SharePointScanPort sharePointScanPort;

    @GetMapping("/health")
    @Operation(summary = "Check SharePoint connection")
    @ApiResponse(responseCode = "200", description = "Connection established")
    @ApiResponse(responseCode = "503", description = "SharePoint not accessible")
    public CompletableFuture<ResponseEntity<@NonNull SharePointHealthCheckResponse>> checkHealth() {
        return sharePointScanPort.testConnection()
            .thenApply(isConnected -> {
                var response = new SharePointHealthCheckResponse(
                    Boolean.TRUE.equals(isConnected) ? "UP" : "DOWN",
                    Boolean.TRUE.equals(isConnected)
                        ? "Connection to SharePoint established"
                        : "SharePoint not accessible"
                );
                return Boolean.TRUE.equals(isConnected)
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            });
    }

    @GetMapping("/sites")
    @Operation(summary = "Search SharePoint sites")
    @ApiResponse(responseCode = "200", description = "List of matching sites")
    public CompletableFuture<ResponseEntity<@NonNull List<SharePointSiteDto>>> searchSites(
            @Parameter(description = "Search query") @RequestParam(defaultValue = "*") String query) {
        log.info("GET /sharepoint/sites?query={}", query);
        return sharePointScanPort.searchSites(query)
            .thenApply(sites -> ResponseEntity.ok(
                sites.stream().map(SharePointController::toDto).toList()
            ))
            .exceptionally(ex -> {
                log.error("Error searching SharePoint sites", ex);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            });
    }

    @GetMapping("/sites/{siteId}/items")
    @Operation(summary = "List root drive items for a site")
    @ApiResponse(responseCode = "200", description = "List of drive items")
    public CompletableFuture<ResponseEntity<@NonNull List<SharePointDriveItemDto>>> listRootItems(
            @Parameter(description = "Site ID") @PathVariable String siteId) {
        log.info("GET /sharepoint/sites/{}/items", siteId);
        return sharePointScanPort.listDriveItems(siteId)
            .thenApply(items -> ResponseEntity.ok(
                items.stream().map(SharePointController::toDto).toList()
            ))
            .exceptionally(ex -> {
                log.error("Error listing items for site {}", siteId, ex);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            });
    }

    @GetMapping("/drives/{driveId}/items/{itemId}/children")
    @Operation(summary = "List children of a drive item")
    @ApiResponse(responseCode = "200", description = "List of child items")
    public CompletableFuture<ResponseEntity<@NonNull List<SharePointDriveItemDto>>> listChildren(
            @Parameter(description = "Drive ID") @PathVariable String driveId,
            @Parameter(description = "Item ID") @PathVariable String itemId) {
        log.info("GET /sharepoint/drives/{}/items/{}/children", driveId, itemId);
        return sharePointScanPort.listDriveItemChildren(driveId, itemId)
            .thenApply(items -> ResponseEntity.ok(
                items.stream().map(SharePointController::toDto).toList()
            ))
            .exceptionally(ex -> {
                log.error("Error listing children for drive={}, item={}", driveId, itemId, ex);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            });
    }

    private static SharePointSiteDto toDto(SharePointSite site) {
        return new SharePointSiteDto(site.id(), site.name(), site.webUrl(), site.description());
    }

    private static SharePointDriveItemDto toDto(SharePointDriveItem item) {
        return new SharePointDriveItemDto(
            item.id(), item.name(), item.webUrl(), item.driveId(),
            item.mimeType(), item.size(), item.isFolder()
        );
    }

    public record SharePointHealthCheckResponse(String status, String message) {}

    public record SharePointSiteDto(String id, String name, String webUrl, String description) {}

    public record SharePointDriveItemDto(
        String id, String name, String webUrl, String driveId,
        String mimeType, Long size, boolean isFolder
    ) {}
}
