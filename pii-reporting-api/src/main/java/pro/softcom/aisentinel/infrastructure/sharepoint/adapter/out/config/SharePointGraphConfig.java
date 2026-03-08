package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.config;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * Spring configuration for Microsoft Graph SDK client.
 * Credentials are always managed via the UI and stored encrypted in DB.
 * The GraphServiceClient is created lazily by SharePointGraphClientHolder.
 */
@Configuration
@Slf4j
public class SharePointGraphConfig {

    @Bean
    public Supplier<GraphServiceClient> graphServiceClientSupplier(SharePointGraphClientHolder holder) {
        log.info("Initializing Microsoft Graph client supplier from database configuration");
        return holder::getClient;
    }
}
