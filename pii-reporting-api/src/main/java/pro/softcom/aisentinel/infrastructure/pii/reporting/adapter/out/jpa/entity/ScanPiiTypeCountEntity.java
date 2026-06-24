package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

/**
 * JPA entity for persisting PII type occurrence counts aggregated by scan, space and type.
 *
 * <p>Maps to the {@code scan_pii_type_counts} table, storing pre-calculated
 * per-type statistics for performance-optimized dashboard display.
 *
 * <p>The composite primary key ensures one record per scan-space-type combination,
 * with atomic increment operations supporting concurrent scan workers.
 */
@Entity
@Table(name = "scan_pii_type_counts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanPiiTypeCountEntity {

    @EmbeddedId
    private ScanPiiTypeCountId id;

    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount;

    /**
     * Convenience method to get scan ID from composite key.
     */
    public String getScanId() {
        return id != null ? id.getScanId() : null;
    }

    /**
     * Convenience method to get space key from composite key.
     */
    public String getSpaceKey() {
        return id != null ? id.getSpaceKey() : null;
    }

    /**
     * Convenience method to get PII type from composite key.
     */
    public String getPiiType() {
        return id != null ? id.getPiiType() : null;
    }
}
