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
 * JPA entity for per-detector cumulated scan statistics.
 *
 * <p>Maps to {@code scan_detector_stats}. {@code busyMs} is the summed
 * per-detector busy time across the scan's analysis requests.
 */
@Entity
@Table(name = "scan_detector_stats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanDetectorStatsEntity {

    @EmbeddedId
    private ScanDetectorStatsId id;

    @Column(name = "busy_ms", nullable = false)
    private Long busyMs;

    @Column(name = "chars_processed", nullable = false)
    private Long charsProcessed;

    @Column(name = "detections", nullable = false)
    private Integer detections;

    @Column(name = "discarded", nullable = false)
    private Integer discarded;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
