package pro.softcom.aisentinel.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ChangeFindingStatusPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.QueryRemediationFindingsPort;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.application.pii.remediation.service.ScanEventFindingResolver;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionEvaluator;
import pro.softcom.aisentinel.application.pii.remediation.usecase.ChangeFindingStatusUseCase;
import pro.softcom.aisentinel.application.pii.remediation.usecase.QueryRemediationFindingsUseCase;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;

import java.time.Clock;

/**
 * Spring wiring of the PII remediation use cases, keeping the application layer
 * framework-free.
 */
@Configuration
public class RemediationUseCasesConfig {

    @Bean
    public ScanEventFindingResolver scanEventFindingResolver(SeverityCalculationService severityCalculationService) {
        return new ScanEventFindingResolver(severityCalculationService);
    }

    @Bean
    public SelectionEvaluator selectionEvaluator() {
        return new SelectionEvaluator();
    }

    @Bean
    public QueryRemediationFindingsPort queryRemediationFindingsPort(
            RemediationConfigPort remediationConfigPort,
            ScanResultQuery scanResultQuery,
            FindingRemediationStore findingRemediationStore,
            ScanEventFindingResolver scanEventFindingResolver,
            SelectionEvaluator selectionEvaluator) {
        return new QueryRemediationFindingsUseCase(remediationConfigPort, scanResultQuery,
                findingRemediationStore, scanEventFindingResolver, selectionEvaluator);
    }

    @Bean
    public ChangeFindingStatusPort changeFindingStatusPort(
            RemediationConfigPort remediationConfigPort,
            ScanResultQuery scanResultQuery,
            FindingRemediationStore findingRemediationStore,
            ScanEventFindingResolver scanEventFindingResolver) {
        return new ChangeFindingStatusUseCase(remediationConfigPort, scanResultQuery,
                findingRemediationStore, scanEventFindingResolver, Clock.systemUTC());
    }
}
