package pro.softcom.aisentinel.application.pii.remediation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SelectionEvaluator")
class SelectionEvaluatorTest {

    private final SelectionEvaluator evaluator = new SelectionEvaluator();

    private final EligibleFinding emailFinding = finding("EMAIL", PersonallyIdentifiableInformationSeverity.MEDIUM);

    @Test
    @DisplayName("Should_SelectFinding_When_TypeCheckedAndStatusPending")
    void Should_SelectFinding_When_TypeCheckedAndStatusPending() {
        RemediationSelection selection = selectionBuilder().piiTypes(List.of("EMAIL")).build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isTrue();
    }

    @Test
    @DisplayName("Should_SelectFinding_When_SeverityChecked")
    void Should_SelectFinding_When_SeverityChecked() {
        RemediationSelection selection = selectionBuilder()
                .severities(List.of(PersonallyIdentifiableInformationSeverity.MEDIUM))
                .build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isTrue();
    }

    @Test
    @DisplayName("Should_NotSelectFinding_When_StatusIsNotPending")
    void Should_NotSelectFinding_When_StatusIsNotPending() {
        RemediationSelection selection = selectionBuilder().piiTypes(List.of("EMAIL")).build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.FALSE_POSITIVE, selection);

        assertThat(selected).isFalse();
    }

    @Test
    @DisplayName("Should_NotSelectFinding_When_ExcludedByIdEvenIfTypeChecked")
    void Should_NotSelectFinding_When_ExcludedByIdEvenIfTypeChecked() {
        RemediationSelection selection = selectionBuilder()
                .piiTypes(List.of("EMAIL"))
                .excludedFindingIds(Set.of(emailFinding.findingId()))
                .build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isFalse();
    }

    @Test
    @DisplayName("Should_SelectFinding_When_IncludedByIdWithoutCriteria")
    void Should_SelectFinding_When_IncludedByIdWithoutCriteria() {
        RemediationSelection selection = selectionBuilder()
                .includedFindingIds(Set.of(emailFinding.findingId()))
                .build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isTrue();
    }

    @Test
    @DisplayName("Should_NotSelectFinding_When_ExclusionWinsOverInclusion")
    void Should_NotSelectFinding_When_ExclusionWinsOverInclusion() {
        RemediationSelection selection = selectionBuilder()
                .includedFindingIds(Set.of(emailFinding.findingId()))
                .excludedFindingIds(Set.of(emailFinding.findingId()))
                .build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isFalse();
    }

    @Test
    @DisplayName("Should_NotSelectFinding_When_NoCriterionMatches")
    void Should_NotSelectFinding_When_NoCriterionMatches() {
        RemediationSelection selection = selectionBuilder()
                .piiTypes(List.of("PHONE"))
                .severities(List.of(PersonallyIdentifiableInformationSeverity.HIGH))
                .build();

        boolean selected = evaluator.isSelected(emailFinding, FindingRemediationStatus.PENDING, selection);

        assertThat(selected).isFalse();
    }

    private static RemediationSelection.RemediationSelectionBuilder selectionBuilder() {
        return RemediationSelection.builder().spaceKey("SPACE");
    }

    private static EligibleFinding finding(String piiType, PersonallyIdentifiableInformationSeverity severity) {
        FindingReference reference = FindingReference.builder()
                .spaceKey("SPACE")
                .pageId("p1")
                .detector("PRESIDIO")
                .piiType(piiType)
                .severity(severity)
                .valueFingerprint("fp-1")
                .build();
        return EligibleFinding.builder()
                .findingId(reference.findingId())
                .reference(reference)
                .confidence(0.9)
                .piiTypeLabel(piiType)
                .maskedContext("masked")
                .pageTitle("Page")
                .build();
    }
}
