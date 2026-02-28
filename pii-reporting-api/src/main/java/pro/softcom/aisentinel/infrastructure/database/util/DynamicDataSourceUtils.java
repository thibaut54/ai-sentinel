package pro.softcom.aisentinel.infrastructure.database.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;

public class DynamicDataSourceUtils {

    private DynamicDataSourceUtils() {}

    public static HikariDataSource createDataSource(ScanSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.properties().get("url"));
        hikariConfig.setUsername(config.properties().get("username"));
        hikariConfig.setPassword(config.properties().get("password"));
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setIdleTimeout(30000);
        return new HikariDataSource(hikariConfig);
    }
}
