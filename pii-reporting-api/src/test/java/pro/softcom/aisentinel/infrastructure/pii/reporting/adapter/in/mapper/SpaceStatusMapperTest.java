package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ReportingScanStatus;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceScanStateDto;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceStatusMapperTest {

    private SpaceStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SpaceStatusMapper();
    }

    @Test
    void Should_MapToDtoCorrectly_When_ValidStateProvided() {
        // Arrange
        Instant now = Instant.now();
        ConfluenceSpaceScanState state = new ConfluenceSpaceScanState(
                "SPACE1", ReportingScanStatus.RUNNING, 50L, 10L, now, 75.5);

        // Act
        SpaceScanStateDto result = mapper.toDto(state);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.spaceKey()).isEqualTo("SPACE1");
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.pagesDone()).isEqualTo(50L);
        assertThat(result.attachmentsDone()).isEqualTo(10L);
        assertThat(result.lastEventTs()).isEqualTo(now);
        assertThat(result.progressPercentage()).isEqualTo(75.5);
    }

    @Test
    void Should_ReturnNull_When_StateIsNull() {
        // Act
        SpaceScanStateDto result = mapper.toDto(null);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void Should_MapListCorrectly_When_ValidListProvided() {
        // Arrange
        Instant now = Instant.now();
        List<ConfluenceSpaceScanState> states = List.of(
                new ConfluenceSpaceScanState("SPACE1", ReportingScanStatus.COMPLETED, 100L, 20L, now, 100.0),
                new ConfluenceSpaceScanState("SPACE2", ReportingScanStatus.RUNNING, 50L, 5L, now, 50.0)
        );

        // Act
        List<SpaceScanStateDto> result = mapper.toDtoList(states);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).spaceKey()).isEqualTo("SPACE1");
        assertThat(result.get(1).spaceKey()).isEqualTo("SPACE2");
    }

    @Test
    void Should_ReturnEmptyList_When_ListIsNull() {
        // Act
        List<SpaceScanStateDto> result = mapper.toDtoList(null);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_ListIsEmpty() {
        // Act
        List<SpaceScanStateDto> result = mapper.toDtoList(List.of());

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_HandleNullElementInList_When_ListContainsNull() {
        // Arrange
        Instant now = Instant.now();
        List<ConfluenceSpaceScanState> states = Arrays.asList(
                new ConfluenceSpaceScanState("SPACE1", ReportingScanStatus.COMPLETED, 100L, 20L, now, 100.0),
                null
        );

        // Act
        List<SpaceScanStateDto> result = mapper.toDtoList(states);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).spaceKey()).isEqualTo("SPACE1");
        assertThat(result.get(1)).isNull();
    }
}
