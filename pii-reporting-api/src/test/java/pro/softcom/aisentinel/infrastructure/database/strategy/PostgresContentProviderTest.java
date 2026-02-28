package pro.softcom.aisentinel.infrastructure.database.strategy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.SourceType;
import pro.softcom.aisentinel.infrastructure.database.util.DynamicDataSourceUtils;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class PostgresContentProviderTest {

    @InjectMocks
    private PostgresContentProvider provider;

    @Test
    void supports_ShouldReturnTrue_ForPostgres() {
        assertThat(provider.supports(SourceType.POSTGRES)).isTrue();
        assertThat(provider.supports(SourceType.MONGO)).isFalse();
    }

    @Test
    void fetch_ShouldReturnError_WhenTableIsInvalid() {
        ScanSourceConfig config = new ScanSourceConfig(SourceType.POSTGRES, Map.of("table", "invalid;drop table users"));
        
        try (MockedStatic<DynamicDataSourceUtils> utilities = mockStatic(DynamicDataSourceUtils.class)) {
            HikariDataSource mockDataSource = mock(HikariDataSource.class);
            utilities.when(() -> DynamicDataSourceUtils.createDataSource(any())).thenReturn(mockDataSource);

            StepVerifier.create(provider.fetch(config))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
    }
}
