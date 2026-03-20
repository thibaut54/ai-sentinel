package pro.softcom.aisentinel.application.confluence.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.in.ConfluenceSpacePort;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceClient;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.confluence.DataOwners;
import pro.softcom.aisentinel.domain.confluence.ModifiedPageInfo;
import pro.softcom.aisentinel.domain.confluence.SpaceUpdateInfo;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FetchSpaceUpdateInfoUseCase}.
 * Validates business logic for determining if Confluence spaces need re-scanning.
 * This test class mirrors the one in the service package but lives at the correct usecase path.
 */
@ExtendWith(MockitoExtension.class)
class FetchSpaceUpdateInfoUseCaseTest {

    @Mock
    private ConfluenceSpacePort confluenceSpacePort;

    @Mock
    private ConfluenceClient confluenceClient;

    @Mock
    private ScanCheckpointRepository scanCheckpointRepository;

    private FetchSpaceUpdateInfoUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FetchSpaceUpdateInfoUseCase(confluenceSpacePort, confluenceClient, scanCheckpointRepository);
    }

    @Test
    void Should_ReturnNoScanYet_When_NoCheckpointExists() {
        // Arrange
        ConfluenceSpace space = createSpace("TEST");
        when(confluenceSpacePort.getAllSpaces())
            .thenReturn(CompletableFuture.completedFuture(List.of(space)));

        // Act
        List<SpaceUpdateInfo> result = useCase.getAllSpacesUpdateInfo().join();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(result).hasSize(1);
            SpaceUpdateInfo info = result.getFirst();
            softly.assertThat(info.spaceKey()).isEqualTo("TEST");
            softly.assertThat(info.hasBeenUpdated()).isFalse();
            softly.assertThat(info.lastScanDate()).isNull();
        });
        verify(scanCheckpointRepository).findLatestBySource(SourceType.CONFLUENCE, "TEST");
    }

    @Test
    void Should_ReturnWithUpdates_When_SpaceModifiedAfterLastScan() {
        // Arrange
        Instant lastScanDate = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant lastModified = Instant.now().minus(5, ChronoUnit.DAYS);
        ConfluenceSpace space = createSpace("TEST");
        ScanCheckpoint checkpoint = createCheckpoint(lastScanDate);

        when(confluenceSpacePort.getAllSpaces())
            .thenReturn(CompletableFuture.completedFuture(List.of(space)));
        when(scanCheckpointRepository.findLatestBySource(any(SourceType.class), anyString()))
            .thenReturn(Optional.of(checkpoint));
        when(confluenceClient.getModifiedPagesSince(anyString(), any(Instant.class)))
            .thenReturn(CompletableFuture.completedFuture(List.of(
                new ModifiedPageInfo("1", "Updated Page", lastModified)
            )));
        when(confluenceClient.getModifiedAttachmentsSince(anyString(), any(Instant.class)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        // Act
        List<SpaceUpdateInfo> result = useCase.getAllSpacesUpdateInfo().join();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(result).hasSize(1);
            SpaceUpdateInfo info = result.getFirst();
            softly.assertThat(info.hasBeenUpdated()).isTrue();
            softly.assertThat(info.lastModified()).isEqualTo(lastModified);
            softly.assertThat(info.lastScanDate()).isEqualTo(lastScanDate);
        });
    }

    @Test
    void Should_ReturnEmpty_When_SpaceNotFound() {
        // Arrange
        when(confluenceSpacePort.getSpace("NOTFOUND"))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        // Act
        Optional<SpaceUpdateInfo> result = useCase.getSpaceUpdateInfo("NOTFOUND").join();

        // Assert
        assertThat(result).isEmpty();
    }

    private ConfluenceSpace createSpace(String key) {
        return new ConfluenceSpace(
            "space-" + key, key, "Test Space",
            "https://confluence.example.com/spaces/" + key,
            "Description", ConfluenceSpace.SpaceType.GLOBAL,
            ConfluenceSpace.SpaceStatus.CURRENT,
            new DataOwners.Loaded(List.of()), null
        );
    }

    private ScanCheckpoint createCheckpoint(Instant scanDate) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(scanDate, ZoneId.systemDefault());
        return ScanCheckpoint.builder()
            .scanId("scan-123")
            .sourceType(SourceType.CONFLUENCE)
            .sourceKey("TEST")
            .scanStatus(ScanStatus.COMPLETED)
            .updatedAt(localDateTime)
            .build();
    }
}
