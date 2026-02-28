package pro.softcom.aisentinel.infrastructure.database.strategy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.database.DatabaseScannableContent;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.domain.pii.scan.model.SourceType;
import pro.softcom.aisentinel.infrastructure.database.util.DynamicDataSourceUtils;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PostgresContentProvider implements ContentProviderStrategy {

    @Override
    public boolean supports(SourceType type) {
        return SourceType.POSTGRES.equals(type);
    }

    @Override
    public Flux<ScannableContent> fetch(ScanSourceConfig config) {
        return Flux.using(
                () -> DynamicDataSourceUtils.createDataSource(config),
                dataSource -> fetchFromDataSource(dataSource, config),
                HikariDataSource::close
        );
    }

    private Flux<ScannableContent> fetchFromDataSource(HikariDataSource dataSource, ScanSourceConfig config) {
        return Flux.create(sink -> {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            String tableName = config.properties().get("table");

            if (tableName == null || !tableName.matches("^[a-zA-Z0-9_]+$")) {
                sink.error(new IllegalArgumentException("Invalid or missing table name: " + tableName));
                return;
            }

            try {
                jdbcTemplate.query("SELECT * FROM " + tableName, rs -> {
                    if (sink.isCancelled()) {
                        return;
                    }

                    try {
                        int columnCount = rs.getMetaData().getColumnCount();
                        Map<String, Object> rowMap = new java.util.HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            rowMap.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                        }

                        String contentBody = rowMap.values().stream()
                                .map(val -> val != null ? val.toString() : "")
                                .collect(Collectors.joining(" "));

                        ScannableContent content = DatabaseScannableContent.builder()
                                .id(UUID.randomUUID().toString())
                                .title(tableName + " Row")
                                .contentBody(contentBody)
                                .sourceId(tableName)
                                .metadata(rowMap)
                                .build();

                        sink.next(content);
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
}
