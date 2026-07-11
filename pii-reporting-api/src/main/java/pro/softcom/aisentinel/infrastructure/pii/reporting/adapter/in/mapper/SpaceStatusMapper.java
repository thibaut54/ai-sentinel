package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceScanStateDto;

import java.util.List;

@Component
public class SpaceStatusMapper {

    public List<SpaceScanStateDto> toDtoList(List<ConfluenceSpaceScanState> spaceScanStates) {
        if (spaceScanStates == null || spaceScanStates.isEmpty()) return List.of();
        return spaceScanStates.stream().map(this::toDto).toList();
    }

    public SpaceScanStateDto toDto(ConfluenceSpaceScanState spaceScanState) {
        if (spaceScanState == null) return null;
        return new SpaceScanStateDto(spaceScanState.spaceKey(), spaceScanState.status(), spaceScanState.pagesDone(), spaceScanState.attachmentsDone(), spaceScanState.lastEventAt(), spaceScanState.progressPercentage());
    }
}
