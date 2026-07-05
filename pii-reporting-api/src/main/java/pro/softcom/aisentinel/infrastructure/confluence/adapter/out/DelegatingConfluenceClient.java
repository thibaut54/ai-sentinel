package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceClient;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.confluence.ModifiedAttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.ModifiedPageInfo;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Delegates to either the Cloud or Data Center Confluence adapter
 * based on the configured {@link ConfluenceDeploymentType}.
 */
@Slf4j
public class DelegatingConfluenceClient implements ConfluenceClient {

    private final ConfluenceConnectionConfig config;
    private final ConfluenceClient cloudAdapter;
    private final ConfluenceClient dataCenterAdapter;

    public DelegatingConfluenceClient(ConfluenceConnectionConfig config,
                                      ConfluenceClient cloudAdapter,
                                      ConfluenceClient dataCenterAdapter) {
        this.config = config;
        this.cloudAdapter = cloudAdapter;
        this.dataCenterAdapter = dataCenterAdapter;
    }

    private ConfluenceClient delegate() {
        var type = config.deploymentType();
        if (type == ConfluenceDeploymentType.DATA_CENTER) {
            log.debug("Using Confluence Data Center adapter");
            return dataCenterAdapter;
        }
        log.debug("Using Confluence Cloud adapter");
        return cloudAdapter;
    }

    @Override
    public CompletableFuture<Optional<ConfluencePage>> getPage(String pageId) {
        return delegate().getPage(pageId);
    }

    @Override
    public CompletableFuture<List<ConfluencePage>> searchPages(String spaceKey, String query) {
        return delegate().searchPages(spaceKey, query);
    }

    @Override
    public CompletableFuture<ConfluencePage> updatePage(ConfluencePage page) {
        return delegate().updatePage(page);
    }

    @Override
    public CompletableFuture<Optional<ConfluenceSpace>> getSpace(String spaceKey) {
        return delegate().getSpace(spaceKey);
    }

    @Override
    public CompletableFuture<Optional<ConfluenceSpace>> getSpaceWithPermissions(String spaceKey) {
        return delegate().getSpaceWithPermissions(spaceKey);
    }

    @Override
    public CompletableFuture<Optional<ConfluenceSpace>> getSpaceById(String spaceId) {
        return delegate().getSpaceById(spaceId);
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return delegate().testConnection();
    }

    @Override
    public CompletableFuture<List<ConfluenceSpace>> getAllSpaces() {
        return delegate().getAllSpaces();
    }

    @Override
    public CompletableFuture<List<ConfluencePage>> getAllPagesInSpace(String spaceKey) {
        return delegate().getAllPagesInSpace(spaceKey);
    }

    @Override
    public CompletableFuture<List<ModifiedPageInfo>> getModifiedPagesSince(String spaceKey, Instant sinceDate) {
        return delegate().getModifiedPagesSince(spaceKey, sinceDate);
    }

    @Override
    public CompletableFuture<List<ModifiedAttachmentInfo>> getModifiedAttachmentsSince(String spaceKey, Instant sinceDate) {
        return delegate().getModifiedAttachmentsSince(spaceKey, sinceDate);
    }
}
