package pro.softcom.aisentinel.infrastructure.database.strategy;

import pro.softcom.aisentinel.domain.pii.scan.model.DatabaseSourceType;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import reactor.core.publisher.Flux;

public interface ContentProviderStrategy {
    boolean supports(DatabaseSourceType type);
    Flux<ScannableContent> fetch(ScanSourceConfig config);
}
