package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiTypeConfigRepository;
import pro.softcom.aisentinel.application.pii.reporting.ScanPiiTypeCountService;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanPiiTypeCountRepository;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSeverityCountRepository;

/**
 * Configuration for application layer services.
 *
 * This configuration lives in the infrastructure layer to keep the application layer
 * independent of Spring Framework, following hexagonal architecture principles.
 */
@Configuration
public class ApplicationServicesConfiguration {

    /**
     * Creates the severity calculation service bean.
     * Uses DB-configured severity from pii_type_config table, falling back to static rules.
     */
    @Bean
    public SeverityCalculationService severityCalculationService(PiiTypeConfigRepository piiTypeConfigRepository) {
        return new SeverityCalculationService(piiTypeConfigRepository);
    }

    /**
     * Creates the scan severity count service bean.
     * This service manages atomic operations on severity counts during scans.
     */
    @Bean
    public ScanSeverityCountService scanSeverityCountService(
            ScanSeverityCountRepository scanSeverityCountRepository) {
        return new ScanSeverityCountService(scanSeverityCountRepository);
    }

    /**
     * Creates the scan PII type count service bean.
     * This service manages atomic operations on per-type occurrence counts during scans.
     */
    @Bean
    public ScanPiiTypeCountService scanPiiTypeCountService(
            ScanPiiTypeCountRepository scanPiiTypeCountRepository) {
        return new ScanPiiTypeCountService(scanPiiTypeCountRepository);
    }
}
