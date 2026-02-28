package pro.softcom.aisentinel.infrastructure.database.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.infrastructure.database.adapter.out.entity.DatabaseContentEntity;
import pro.softcom.aisentinel.infrastructure.database.adapter.out.repository.DatabaseContentRepository;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcDatabaseAdapterTest {

    @Mock
    private DatabaseContentRepository repository;

    @InjectMocks
    private JdbcDatabaseAdapter adapter;

    @Test
    void loadContent_ShouldReturnMappedContent_WhenRepositoryReturnsEntities() {
        // Arrange
        String sourceIdentifier = "test-source";
        DatabaseContentEntity entity = new DatabaseContentEntity("1", "body", "title", sourceIdentifier);
        when(repository.findBySourceId(sourceIdentifier)).thenReturn(List.of(entity));

        // Act & Assert
        StepVerifier.create(adapter.loadContent(sourceIdentifier))
                .assertNext(content -> {
                    assertThat(content.getId()).isEqualTo("1");
                    assertThat(content.getContentBody()).isEqualTo("body");
                    assertThat(content.getTitle()).isEqualTo("title");
                    assertThat(content.getSourceId()).isEqualTo(sourceIdentifier);
                    assertThat(content.getMetadata())
                            .containsEntry("sourceType", "DATABASE")
                            .containsEntry("table", "scannable_database_content");
                })
                .verifyComplete();
    }
}
