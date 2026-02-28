package pro.softcom.aisentinel.infrastructure.database.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.scan.port.out.LoadContentPort;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.infrastructure.database.strategy.ContentProviderFactory;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicSourceAdapter implements LoadContentPort {

    private final ContentProviderFactory contentProviderFactory;

    @Override
    public Flux<ScannableContent> loadContent(ScanSourceConfig config) {
        log.info("Loading content from source type: {}", config.type());
        return contentProviderFactory.fetchContent(config);
    }
}
