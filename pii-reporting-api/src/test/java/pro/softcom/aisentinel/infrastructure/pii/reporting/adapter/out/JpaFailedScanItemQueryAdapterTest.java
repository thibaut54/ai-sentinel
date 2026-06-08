package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem;
import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem.ItemType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaFailedScanItemQueryAdapterTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "KEY";

    @Mock
    private DetectionEventRepository eventRepository;

    private JpaFailedScanItemQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaFailedScanItemQueryAdapter(eventRepository);
    }

    private static ScanEventEntity pageError(String pageId, String pageTitle) {
        ScanEventEntity entity = new ScanEventEntity();
        entity.setEventType("scanError");
        entity.setPageId(pageId);
        entity.setPageTitle(pageTitle);
        return entity;
    }

    private static ScanEventEntity attachmentError(String attachmentName) {
        ScanEventEntity entity = new ScanEventEntity();
        entity.setEventType("scanError");
        entity.setAttachmentName(attachmentName);
        return entity;
    }

    private void stubErrors(List<ScanEventEntity> errors) {
        when(eventRepository.findByScanIdAndSpaceKeyAndEventTypeOrderByEventSeqAsc(
            SCAN_ID, SPACE_KEY, "scanError")).thenReturn(errors);
    }

    @Test
    @DisplayName("Should_MapPageAndAttachmentErrors_When_BothPresent")
    void Should_MapPageAndAttachmentErrors_When_BothPresent() {
        stubErrors(List.of(pageError("p1", "Page One"), attachmentError("file.pdf")));

        List<FailedScanItem> items = adapter.findFailedItems(SCAN_ID, SPACE_KEY, 20);

        assertThat(items).containsExactly(
            new FailedScanItem(ItemType.PAGE, "Page One"),
            new FailedScanItem(ItemType.ATTACHMENT, "file.pdf"));
    }

    @Test
    @DisplayName("Should_FallBackToPageId_When_PageTitleMissing")
    void Should_FallBackToPageId_When_PageTitleMissing() {
        stubErrors(List.of(pageError("p1", null)));

        List<FailedScanItem> items = adapter.findFailedItems(SCAN_ID, SPACE_KEY, 20);

        assertThat(items).containsExactly(new FailedScanItem(ItemType.PAGE, "p1"));
    }

    @Test
    @DisplayName("Should_Deduplicate_When_SameItemFailsMultipleTimes")
    void Should_Deduplicate_When_SameItemFailsMultipleTimes() {
        stubErrors(List.of(pageError("p1", "Page One"), pageError("p1", "Page One")));

        List<FailedScanItem> items = adapter.findFailedItems(SCAN_ID, SPACE_KEY, 20);

        assertThat(items).containsExactly(new FailedScanItem(ItemType.PAGE, "Page One"));
    }

    @Test
    @DisplayName("Should_CapResults_When_MoreErrorsThanLimit")
    void Should_CapResults_When_MoreErrorsThanLimit() {
        stubErrors(List.of(
            pageError("p1", "One"),
            pageError("p2", "Two"),
            pageError("p3", "Three")));

        List<FailedScanItem> items = adapter.findFailedItems(SCAN_ID, SPACE_KEY, 2);

        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("Should_SkipError_When_NoIdentifyingInformation")
    void Should_SkipError_When_NoIdentifyingInformation() {
        stubErrors(List.of(pageError(null, null)));

        List<FailedScanItem> items = adapter.findFailedItems(SCAN_ID, SPACE_KEY, 20);

        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_ScanIdBlank")
    void Should_ReturnEmpty_When_ScanIdBlank() {
        lenient().when(eventRepository.findByScanIdAndSpaceKeyAndEventTypeOrderByEventSeqAsc(
            anyString(), anyString(), anyString())).thenReturn(List.of());

        assertThat(adapter.findFailedItems("  ", SPACE_KEY, 20)).isEmpty();
        verifyNoInteractions(eventRepository);
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_LimitNotPositive")
    void Should_ReturnEmpty_When_LimitNotPositive() {
        assertThat(adapter.findFailedItems(SCAN_ID, SPACE_KEY, 0)).isEmpty();
        verifyNoInteractions(eventRepository);
    }
}
