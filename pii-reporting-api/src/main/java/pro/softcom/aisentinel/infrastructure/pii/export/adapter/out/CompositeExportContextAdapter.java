package pro.softcom.aisentinel.infrastructure.pii.export.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.confluence.exception.ConfluenceSpaceNotFoundException;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceClient;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceSpaceRepository;
import pro.softcom.aisentinel.application.pii.export.exception.ExportContextNotFoundException;
import pro.softcom.aisentinel.application.pii.export.exception.UnsupportedSourceTypeException;
import pro.softcom.aisentinel.application.pii.export.port.out.ReadExportContextPort;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpaceDataOwner;
import pro.softcom.aisentinel.domain.confluence.DataOwners;
import pro.softcom.aisentinel.domain.pii.export.DataSourceContact;
import pro.softcom.aisentinel.domain.pii.export.ExportContext;
import pro.softcom.aisentinel.domain.pii.export.SourceType;

import java.util.List;
import java.util.Map;

/**
 * Composite adapter that dispatches export context retrieval to the appropriate
 * source-specific adapter based on the SourceType.
 * Supports Confluence, Jira and SharePoint sources.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompositeExportContextAdapter implements ReadExportContextPort {

    private final ConfluenceSpaceRepository confluenceSpaceRepository;
    private final ConfluenceClient confluenceClient;
    private final SharePointExportContextAdapter sharePointExportContextAdapter;

    @Override
    public ExportContext findContext(SourceType sourceType, String sourceIdentifier) {
        return switch (sourceType) {
            case SHAREPOINT -> sharePointExportContextAdapter.findContext(sourceType, sourceIdentifier);
            case JIRA -> buildJiraContext(sourceIdentifier);
            case CONFLUENCE -> buildConfluenceContext(sourceIdentifier);
            case DATABASE -> throw new UnsupportedSourceTypeException(sourceType.getValue());
        };
    }

    private ExportContext buildJiraContext(String sourceIdentifier) {
        return ExportContext.builder()
                .reportName(sourceIdentifier)
                .reportIdentifier(sourceIdentifier)
                .sourceUrl("")
                .sourceType(SourceType.JIRA)
                .contacts(List.of())
                .additionalMetadata(Map.of("projectKey", sourceIdentifier))
                .build();
    }

    private ExportContext buildConfluenceContext(String sourceIdentifier) {
        log.debug("Retrieving export context for Confluence space: {}", sourceIdentifier);

        try {
            ConfluenceSpace confluenceSpace = confluenceSpaceRepository.findByKey(sourceIdentifier)
                    .orElseThrow(() -> new ConfluenceSpaceNotFoundException(sourceIdentifier));

            List<DataSourceContact> contacts = extractDataSourceContacts(confluenceSpace, sourceIdentifier);

            return ExportContext.builder()
                    .reportName(confluenceSpace.name())
                    .reportIdentifier(confluenceSpace.key())
                    .sourceUrl(confluenceSpace.url())
                    .sourceType(SourceType.CONFLUENCE)
                    .contacts(contacts)
                    .additionalMetadata(buildMetadata(confluenceSpace))
                    .build();
        } catch (ConfluenceSpaceNotFoundException e) {
            throw new ExportContextNotFoundException(SourceType.CONFLUENCE.getValue(), sourceIdentifier, e);
        }
    }

    private List<DataSourceContact> extractDataSourceContacts(ConfluenceSpace confluenceSpace, String sourceIdentifier) {
        return switch (confluenceSpace.dataOwners()) {
            case DataOwners.NotLoaded() -> {
                log.debug("Data owners not loaded, fetching from Confluence API for space: {}", sourceIdentifier);
                List<ConfluenceSpaceDataOwner> owners = loadDataOwnersFromApi(sourceIdentifier);
                yield mapToContacts(owners);
            }
            case DataOwners.Loaded(var owners) -> {
                log.debug("Using already loaded data owners for space: {}", sourceIdentifier);
                yield mapToContacts(owners);
            }
        };
    }

    private List<ConfluenceSpaceDataOwner> loadDataOwnersFromApi(String spaceKey) {
        return confluenceClient.getSpaceWithPermissions(spaceKey)
                .thenApply(optionalConfluenceSpace -> optionalConfluenceSpace
                        .map(confluenceSpace -> switch (confluenceSpace.dataOwners()) {
                            case DataOwners.NotLoaded() -> List.<ConfluenceSpaceDataOwner>of();
                            case DataOwners.Loaded(var owners) -> owners;
                        })
                        .orElse(List.of())
                )
                .join();
    }

    private List<DataSourceContact> mapToContacts(List<ConfluenceSpaceDataOwner> dataOwners) {
        if (dataOwners == null || dataOwners.isEmpty()) {
            return List.of();
        }

        return dataOwners.stream()
                .map(owner -> new DataSourceContact(owner.displayName(), owner.email()))
                .toList();
    }

    private Map<String, String> buildMetadata(ConfluenceSpace confluenceSpace) {
        return Map.of(
                "spaceId", confluenceSpace.id(),
                "spaceType", confluenceSpace.type().getValue(),
                "spaceStatus", confluenceSpace.status().getValue()
        );
    }
}
