package pro.softcom.aisentinel.infrastructure.database.strategy;

import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.domain.pii.scan.model.SourceType;
import reactor.core.publisher.Flux;

public interface ContentProviderStrategy {
    boolean supports(SourceType type);
    Flux<ScannableContent> fetch(ScanSourceConfig config);
}
