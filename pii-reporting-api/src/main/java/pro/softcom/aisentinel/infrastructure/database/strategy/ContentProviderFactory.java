package pro.softcom.aisentinel.infrastructure.database.strategy;

import org.springframework.stereotype.Service;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ContentProviderFactory {

    private final List<ContentProviderStrategy> strategies;

    public ContentProviderFactory(List<ContentProviderStrategy> strategies) {
        this.strategies = strategies;
    }

    public Flux<ScannableContent> fetchContent(ScanSourceConfig config) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(config.type()))
                .findFirst()
                .map(strategy -> strategy.fetch(config))
                .orElseThrow(() -> new IllegalArgumentException("No provider found for type: " + config.type()));
    }
}
