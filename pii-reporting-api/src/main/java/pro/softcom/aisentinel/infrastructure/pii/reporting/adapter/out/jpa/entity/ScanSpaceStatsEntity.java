package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity for per-space scan volume and failure statistics.
 *
 * <p>Maps to {@code scan_space_stats}. Counters are incremented atomically via
 * native upsert queries to remain consistent under concurrent attachment scans.
 */
@Entity
@Table(name = "scan_space_stats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanSpaceStatsEntity {

    @EmbeddedId
    private ScanSpaceStatsId id;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "pages_scanned", nullable = false)
    private Integer pagesScanned;

    @Column(name = "pages_failed", nullable = false)
    private Integer pagesFailed;

    @Column(name = "page_chars", nullable = false)
    private Long pageChars;

    @Column(name = "attachments_scanned", nullable = false)
    private Integer attachmentsScanned;

    @Column(name = "attachments_failed", nullable = false)
    private Integer attachmentsFailed;

    @Column(name = "attachment_chars", nullable = false)
    private Long attachmentChars;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
