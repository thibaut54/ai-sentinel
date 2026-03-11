package pro.softcom.aisentinel.infrastructure.database.strategy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.database.DatabaseScannableContent;
import pro.softcom.aisentinel.domain.pii.scan.model.DatabaseSourceType;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.infrastructure.database.util.DynamicDataSourceUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PostgresContentProvider implements ContentProviderStrategy {

    @Override
    public boolean supports(DatabaseSourceType type) {
        return DatabaseSourceType.POSTGRES.equals(type);
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
        String tableName = config.properties().get("table");

        if (tableName == null || !tableName.matches("^[a-zA-Z0-9_]+$")) {
            return Flux.error(new IllegalArgumentException("Invalid or missing table name: " + tableName));
        }

        return Flux.<ScannableContent>create(sink -> {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            try {
                jdbcTemplate.query("SELECT * FROM " + tableName, rs -> {
                    if (sink.isCancelled()) return;
                    sink.next(mapRow(rs, tableName));
                });
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ScannableContent mapRow(ResultSet rs, String tableName) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        Map<String, Object> rowMap = new LinkedHashMap<>();
        for (int i = 1; i <= columnCount; i++) {
            rowMap.put(metaData.getColumnName(i), rs.getObject(i));
        }

        String contentBody = rowMap.values().stream()
                .map(val -> val != null ? val.toString() : "")
                .collect(Collectors.joining(" "));

        return DatabaseScannableContent.builder()
                .id(UUID.randomUUID().toString())
                .title(tableName + " Row")
                .contentBody(contentBody)
                .sourceId(tableName)
                .metadata(rowMap)
                .build();
    }
}
