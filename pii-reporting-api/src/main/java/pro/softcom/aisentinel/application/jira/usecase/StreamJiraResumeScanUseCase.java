package pro.softcom.aisentinel.application.jira.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.jira.port.in.StreamJiraResumeScanPort;
import pro.softcom.aisentinel.application.jira.service.JiraAccessor;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.scan.ScanProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Use case for resuming a paused Jira scan from the last checkpoint.
 * Reads checkpoints per project, skips COMPLETED projects, and resumes
 * scanning from the last processed issue.
 */
@RequiredArgsConstructor
@Slf4j
public class StreamJiraResumeScanUseCase implements StreamJiraResumeScanPort {

    private final JiraAccessor jiraAccessor;
    private final PiiDetectorClient piiDetectorClient;
    private final ContentScanOrchestrator contentScanOrchestrator;
    private final ScanCheckpointRepository scanCheckpointRepository;

    @Override
    public Flux<ContentScanResult> resumeAllProjects(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            return Flux.empty();
        }

        return Mono.fromFuture(jiraAccessor.getAllProjects())
            .flatMapMany(projects ->
                Flux.fromIterable(projects)
                    .concatMap(project -> resumeProject(scanId, project.key())))
            .onErrorResume(exception -> {
                log.error("[JIRA-RESUME] Error when resuming scan: {}", exception.getMessage(), exception);
                return errorEvent(scanId, null, resolveErrorKey(exception));
            });
    }

    private Flux<ContentScanResult> resumeProject(String scanId, String projectKey) {
        return Mono.fromCallable(() -> scanCheckpointRepository
                .findByScanAndSource(scanId, SourceType.JIRA, projectKey)
                .orElse(null))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(checkpoint -> {
                if (checkpoint != null && checkpoint.scanStatus() == ScanStatus.COMPLETED) {
                    return Flux.empty();
                }
                return Mono.fromFuture(jiraAccessor.getIssuesInProject(projectKey))
                    .flatMapMany(issues -> {
                        var remaining = computeRemainingIssues(issues, checkpoint);
                        if (remaining.isEmpty()) {
                            return Flux.empty();
                        }
                        int originalTotal = issues.size();
                        int analyzedOffset = originalTotal - remaining.size();
                        return runResumeScanFlux(scanId, projectKey, remaining, analyzedOffset, originalTotal);
                    });
            })
            .onErrorResume(exception -> {
                log.error("[JIRA-RESUME] Error resuming project {}: {}",
                    projectKey, exception.getMessage(), exception);
                return errorEvent(scanId, projectKey, resolveErrorKey(exception));
            });
    }

    private Flux<ContentScanResult> runResumeScanFlux(String scanId, String projectKey,
                                                       List<JiraIssue> issues, int analyzedOffset,
                                                       int originalTotal) {
        int total = issues.size();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ContentScanResult> startEvent = Flux.just(
            contentScanOrchestrator.createStartEvent(scanId, projectKey, total,
                contentScanOrchestrator.calculateProgress(analyzedOffset, originalTotal)));

        Flux<ContentScanResult> issueEvents = Flux.fromIterable(issues)
            .publishOn(Schedulers.boundedElastic())
            .concatMap(issue -> {
                int currentIndex = index.incrementAndGet();
                ScanProgress progress = new ScanProgress(currentIndex, analyzedOffset, originalTotal, total);
                return processIssue(scanId, projectKey, issue, progress);
            })
            .onErrorContinue((exception, ignoredElement) -> log.error(
                "[JIRA-RESUME] Error processing issue: {}", exception.getMessage(), exception));

        Flux<ContentScanResult> completeEvent = Flux.just(
            contentScanOrchestrator.createCompleteEvent(scanId, projectKey));

        return Flux.concat(startEvent, issueEvents, completeEvent)
            .doOnEach(signal -> {
                if (signal.isOnNext() && signal.get() != null) {
                    ContentScanResult event = signal.get();
                    contentScanOrchestrator.persistCheckpointSynchronously(event, SourceType.JIRA);
                    Mono.fromRunnable(() -> contentScanOrchestrator.persistEventAsyncOperations(event, SourceType.JIRA))
                        .subscribeOn(Schedulers.boundedElastic())
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
                        .onErrorResume(e -> {
                            log.warn("[JIRA-RESUME] Failed to persist async operations: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .subscribe();
                }
            });
    }

    private Flux<ContentScanResult> processIssue(String scanId, String projectKey,
                                                   JiraIssue issue, ScanProgress scanProgress) {
        String content = issue.getContentBody();
        double startProgress = contentScanOrchestrator.calculateProgress(
            scanProgress.analyzedOffset() + (scanProgress.currentIndex() - 1),
            scanProgress.originalTotal());

        ContentScanResult issueStart = contentScanOrchestrator.createContentStartEvent(
            scanId, projectKey, issue, scanProgress.currentIndex(), scanProgress.originalTotal(), startProgress);

        Flux<ContentScanResult> itemEvent = createIssueItemEvent(scanId, projectKey, issue, content, scanProgress);

        double completeProgress = contentScanOrchestrator.calculateProgress(
            scanProgress.analyzedOffset() + scanProgress.currentIndex(),
            scanProgress.originalTotal());
        ContentScanResult issueComplete = contentScanOrchestrator.createContentCompleteEvent(
            scanId, projectKey, issue, completeProgress);

        return Flux.just(issueStart)
            .concatWith(itemEvent)
            .concatWith(Mono.just(issueComplete))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ContentScanResult> createIssueItemEvent(String scanId, String projectKey,
                                                          JiraIssue issue, String content,
                                                          ScanProgress scanProgress) {
        if (content == null || content.isBlank()) {
            double progress = contentScanOrchestrator.calculateProgress(
                scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                scanProgress.originalTotal());
            return Flux.just(contentScanOrchestrator.createEmptyContentItemEvent(
                scanId, projectKey, issue, progress));
        }

        return Mono.fromCallable(() -> piiDetectorClient.analyzeContent(content))
            .map(detection -> {
                double progress = contentScanOrchestrator.calculateProgress(
                    scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                    scanProgress.originalTotal());
                return contentScanOrchestrator.createContentItemEvent(
                    scanId, projectKey, issue, content, detection, progress);
            })
            .onErrorResume(exception -> {
                log.error("[JIRA-RESUME] Error analyzing issue {}: {}", issue.key(), exception.getMessage());
                double progress = contentScanOrchestrator.calculateProgress(
                    scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                    scanProgress.originalTotal());
                return Mono.just(contentScanOrchestrator.createErrorEvent(
                    scanId, projectKey, issue.id(),
                    resolveErrorKey(exception), progress));
            })
            .flux();
    }

    private static List<JiraIssue> computeRemainingIssues(List<JiraIssue> issues, ScanCheckpoint checkpoint) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        if (checkpoint == null) {
            return issues;
        }
        if (checkpoint.scanStatus() == ScanStatus.COMPLETED) {
            return List.of();
        }

        String lastProcessedId = checkpoint.lastProcessedContentId();
        if (lastProcessedId == null || lastProcessedId.isBlank()) {
            return issues;
        }

        int lastIndex = -1;
        for (int i = 0; i < issues.size(); i++) {
            if (lastProcessedId.equals(issues.get(i).id())) {
                lastIndex = i;
                break;
            }
        }

        if (lastIndex < 0) {
            return issues;
        }

        int start = lastIndex + 1;
        if (start >= issues.size()) {
            return List.of();
        }
        return issues.subList(start, issues.size());
    }

    private static String resolveErrorKey(Throwable exception) {
        if (exception instanceof pro.softcom.aisentinel.domain.jira.JiraAuthenticationException) {
            return "error.jira.auth.failed";
        }
        if (exception instanceof pro.softcom.aisentinel.domain.jira.JiraConnectionException) {
            return "error.jira.connection.failed";
        }
        if (exception instanceof pro.softcom.aisentinel.domain.jira.JiraNotFoundException) {
            return "error.jira.resource.not_found";
        }
        if (exception instanceof pro.softcom.aisentinel.domain.jira.JiraApiException) {
            return "error.jira.api.error";
        }
        if (exception instanceof pro.softcom.aisentinel.domain.pii.scan.PiiDetectionException) {
            return "error.pii.detection.service_error";
        }
        return "error.internal";
    }

    private static Flux<ContentScanResult> errorEvent(String scanId, String sourceId, String message) {
        return Flux.just(ContentScanResult.builder()
            .scanId(scanId)
            .sourceId(sourceId)
            .eventType(DetectionReportingEventType.ERROR.getLabel())
            .message(message)
            .emittedAt(Instant.now().toString())
            .build());
    }
}
