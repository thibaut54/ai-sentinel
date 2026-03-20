package pro.softcom.aisentinel.infrastructure.database.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.scan.model.DatabaseSourceType;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import pro.softcom.aisentinel.infrastructure.database.strategy.ContentProviderFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicSourceAdapterTest {

    @Mock
    private ContentProviderFactory contentProviderFactory;

    @InjectMocks
    private DynamicSourceAdapter adapter;

    @Test
    void loadContent_ShouldDelegatesToFactory() {
        // Arrange
        ScanSourceConfig config = new ScanSourceConfig(DatabaseSourceType.POSTGRES, Map.of("url", "jdbc:postgresql://localhost:5432/db"));
        ScannableContent content = mock(ScannableContent.class);
        when(contentProviderFactory.fetchContent(config)).thenReturn(Flux.just(content));

        // Act & Assert
        StepVerifier.create(adapter.loadContent(config))
                .expectNext(content)
                .verifyComplete();
                
        verify(contentProviderFactory).fetchContent(config);
    }
}
