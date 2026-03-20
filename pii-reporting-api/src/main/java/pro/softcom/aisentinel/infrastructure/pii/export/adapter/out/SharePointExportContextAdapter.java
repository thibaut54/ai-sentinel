package pro.softcom.aisentinel.infrastructure.pii.export.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.pii.export.ExportContext;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.util.List;
import java.util.Map;

/**
 * Provides export context for SharePoint sites.
 * Used by the composite adapter when SourceType is SHAREPOINT.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SharePointExportContextAdapter {

    private final SharePointClient sharePointClient;

    public ExportContext findContext(SourceType sourceType, String sourceIdentifier) {
        log.debug("Retrieving export context for SharePoint site: {}", sourceIdentifier);

        SharePointSite site = sharePointClient.getSite(sourceIdentifier).join();

        String siteName = (site != null && site.name() != null && !site.name().isBlank())
                ? site.name() : sourceIdentifier;
        String siteUrl = (site != null && site.webUrl() != null)
                ? site.webUrl() : "";

        return ExportContext.builder()
                .reportName(siteName)
                .reportIdentifier(sourceIdentifier)
                .sourceUrl(siteUrl)
                .sourceType(sourceType)
                .contacts(List.of())
                .additionalMetadata(Map.of("siteId", sourceIdentifier))
                .build();
    }
}
