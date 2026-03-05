package pro.softcom.aisentinel.infrastructure.jira.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.jira.port.in.JiraProjectPort;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for Jira operations.
 * Exposes endpoints for health check and project listing.
 */
@RestController
@RequestMapping("/api/v1/jira")
@Tag(name = "Jira", description = "Jira operations")
@RequiredArgsConstructor
@Slf4j
public class JiraController {

    private final JiraProjectPort jiraProjectPort;
    private final JiraClient jiraClient;

    @GetMapping("/health")
    @Operation(summary = "Check Jira connection")
    @ApiResponse(responseCode = "200", description = "Connection established")
    @ApiResponse(responseCode = "503", description = "Jira not accessible")
    public CompletableFuture<ResponseEntity<@NonNull JiraHealthCheckResponse>> checkHealth() {
        return jiraClient.testConnection()
                .thenApply(isConnected -> {
                    var response = new JiraHealthCheckResponse(
                            Boolean.TRUE.equals(isConnected) ? "UP" : "DOWN",
                            Boolean.TRUE.equals(isConnected)
                                    ? "Connection to Jira established"
                                    : "Jira not accessible"
                    );
                    return Boolean.TRUE.equals(isConnected)
                            ? ResponseEntity.ok(response)
                            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                });
    }

    @GetMapping("/projects")
    @Operation(summary = "Retrieve all Jira projects")
    @ApiResponse(responseCode = "200", description = "List of projects")
    public CompletableFuture<ResponseEntity<@NonNull List<JiraProject>>> getAllProjects() {
        log.info("GET request /projects");

        return CompletableFuture.supplyAsync(() -> jiraProjectPort.getAllProjects())
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error retrieving projects", ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    @GetMapping("/projects/{projectKey}")
    @Operation(summary = "Retrieve a project by its key")
    @ApiResponse(responseCode = "200", description = "Project found")
    @ApiResponse(responseCode = "404", description = "Project not found")
    public CompletableFuture<ResponseEntity<@NonNull JiraProject>> getProject(
            @Parameter(description = "Project key") @PathVariable String projectKey) {

        log.info("GET request /projects/{}", projectKey);

        return CompletableFuture.supplyAsync(() -> jiraProjectPort.getProject(projectKey))
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof NoSuchElementException) {
                        return ResponseEntity.notFound().build();
                    }
                    log.error("Error retrieving project {}", projectKey, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    public record JiraHealthCheckResponse(String status, String message) {}
}
