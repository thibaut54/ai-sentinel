package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jspecify.annotations.NonNull;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ChangeFindingStatusPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeCommand;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeResult;
import pro.softcom.aisentinel.application.pii.remediation.port.in.QueryRemediationFindingsPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationFindingsQuery;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationFindingsQuery.GroupBy;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationFindingsQuery.StatusFilter;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationFindingsResult;
import pro.softcom.aisentinel.application.pii.remediation.port.in.RemediationTotals;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.FindingStatusChangeRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.FindingStatusChangeResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationConfigDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationScopeDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSearchRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSearchResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationTotalsDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper.RemediationDtoMapper;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PiiRemediationController - REST controller for PII remediation")
class PiiRemediationControllerTest {

    @Mock
    private QueryRemediationFindingsPort queryRemediationFindingsPort;

    @Mock
    private ChangeFindingStatusPort changeFindingStatusPort;

    @Mock
    private RemediationDtoMapper mapper;

    private PiiRemediationController controller;

    @BeforeEach
    void setUp() {
        controller = new PiiRemediationController(queryRemediationFindingsPort, changeFindingStatusPort, mapper);
    }

    @Nested
    @DisplayName("getConfig() method tests")
    class GetConfigTests {

        @Test
        @DisplayName("Should_ReturnEnabledTrue_When_FeatureFlagOn")
        void Should_ReturnEnabledTrue_When_FeatureFlagOn() {
            when(queryRemediationFindingsPort.isRemediationEnabled()).thenReturn(true);

            ResponseEntity<@NonNull RemediationConfigDto> response = controller.getConfig();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isEqualTo(new RemediationConfigDto(true));
            });
        }

        @Test
        @DisplayName("Should_ReturnEnabledFalse_When_FeatureFlagOff")
        void Should_ReturnEnabledFalse_When_FeatureFlagOff() {
            when(queryRemediationFindingsPort.isRemediationEnabled()).thenReturn(false);

            ResponseEntity<@NonNull RemediationConfigDto> response = controller.getConfig();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isEqualTo(new RemediationConfigDto(false));
            });
        }
    }

    @Nested
    @DisplayName("searchFindings() method tests")
    class SearchFindingsTests {

        @Test
        @DisplayName("Should_ReturnMappedView_When_SearchSucceeds")
        void Should_ReturnMappedView_When_SearchSucceeds() {
            RemediationSearchRequestDto request = searchRequest();
            RemediationFindingsQuery query = query();
            RemediationFindingsResult result = emptyResult();
            RemediationSearchResponseDto responseDto = new RemediationSearchResponseDto(
                    List.of(), new RemediationTotalsDto(0, 0, 0, 0), 0, 50, 0, 0);
            when(mapper.toQuery(request)).thenReturn(query);
            when(queryRemediationFindingsPort.search(query)).thenReturn(result);
            when(mapper.toDto(result)).thenReturn(responseDto);

            ResponseEntity<@NonNull RemediationSearchResponseDto> response = controller.searchFindings(request);

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isSameAs(responseDto);
            });
        }

        @Test
        @DisplayName("Should_PropagateRemediationDisabledException_When_UseCaseGuardRejects")
        void Should_PropagateRemediationDisabledException_When_UseCaseGuardRejects() {
            RemediationSearchRequestDto request = searchRequest();
            RemediationFindingsQuery query = query();
            when(mapper.toQuery(request)).thenReturn(query);
            when(queryRemediationFindingsPort.search(query))
                    .thenThrow(new RemediationDisabledException("disabled"));

            assertThatThrownBy(() -> controller.searchFindings(request))
                    .isInstanceOf(RemediationDisabledException.class);
        }
    }

    @Nested
    @DisplayName("changeFindingStatuses() method tests")
    class ChangeFindingStatusesTests {

        @Test
        @DisplayName("Should_UsePrincipalNameAsActor_When_PrincipalPresent")
        void Should_UsePrincipalNameAsActor_When_PrincipalPresent() {
            FindingStatusChangeRequestDto request = new FindingStatusChangeRequestDto(List.of());
            FindingStatusChangeCommand command = new FindingStatusChangeCommand(List.of(), "alice");
            FindingStatusChangeResult result = new FindingStatusChangeResult(List.of(), List.of());
            FindingStatusChangeResponseDto responseDto = new FindingStatusChangeResponseDto(List.of(), List.of());
            Principal principal = () -> "alice";
            when(mapper.toCommand(request, "alice")).thenReturn(command);
            when(changeFindingStatusPort.changeStatuses(command)).thenReturn(result);
            when(mapper.toDto(result)).thenReturn(responseDto);

            ResponseEntity<@NonNull FindingStatusChangeResponseDto> response =
                    controller.changeFindingStatuses(request, principal);

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isSameAs(responseDto);
            });
            verify(mapper).toCommand(request, "alice");
        }

        @Test
        @DisplayName("Should_FallBackToSystemActor_When_PrincipalMissing")
        void Should_FallBackToSystemActor_When_PrincipalMissing() {
            FindingStatusChangeRequestDto request = new FindingStatusChangeRequestDto(List.of());
            FindingStatusChangeCommand command = new FindingStatusChangeCommand(List.of(), "system");
            FindingStatusChangeResult result = new FindingStatusChangeResult(List.of(), List.of());
            when(mapper.toCommand(request, "system")).thenReturn(command);
            when(changeFindingStatusPort.changeStatuses(command)).thenReturn(result);
            when(mapper.toDto(result)).thenReturn(new FindingStatusChangeResponseDto(List.of(), List.of()));

            controller.changeFindingStatuses(request, null);

            verify(mapper).toCommand(request, "system");
        }
    }

    private static RemediationSearchRequestDto searchRequest() {
        return new RemediationSearchRequestDto(new RemediationScopeDto("SPACE", null, null),
                "type", "ALL", null, null, 0, 50, null);
    }

    private static RemediationFindingsQuery query() {
        return RemediationFindingsQuery.builder()
                .spaceKey("SPACE")
                .groupBy(GroupBy.TYPE)
                .statusFilter(StatusFilter.ALL)
                .page(0)
                .pageSize(50)
                .selection(RemediationSelection.builder().spaceKey("SPACE").build())
                .build();
    }

    private static RemediationFindingsResult emptyResult() {
        return RemediationFindingsResult.builder()
                .groups(List.of())
                .totals(RemediationTotals.empty())
                .page(0)
                .pageSize(50)
                .totalElements(0)
                .nonEligibleLegacyCount(0)
                .build();
    }
}
