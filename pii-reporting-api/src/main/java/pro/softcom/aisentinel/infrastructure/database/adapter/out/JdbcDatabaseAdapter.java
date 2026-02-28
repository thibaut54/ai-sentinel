package pro.softcom.aisentinel.infrastructure.database.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.scan.port.out.LoadContentPort;
import pro.softcom.aisentinel.domain.database.DatabaseScannableContent;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.infrastructure.database.adapter.out.repository.DatabaseContentRepository;
import reactor.core.publisher.Flux;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcDatabaseAdapter implements LoadContentPort {

    private final DatabaseContentRepository repository;

    @Override
    public Flux<ScannableContent> loadContent(String sourceIdentifier) {
        log.info("Loading content from database source: {}", sourceIdentifier);
        return Flux.defer(() -> Flux.fromIterable(repository.findBySourceId(sourceIdentifier)))
                .map(entity -> DatabaseScannableContent.builder()
                        .id(entity.getId())
                        .contentBody(entity.getContent())
                        .title(entity.getTitle())
                        .sourceId(entity.getSourceId())
                        .metadata(Map.of("sourceType", "DATABASE", "table", "scannable_database_content"))
                        .build())
                .cast(ScannableContent.class);
    }
}
