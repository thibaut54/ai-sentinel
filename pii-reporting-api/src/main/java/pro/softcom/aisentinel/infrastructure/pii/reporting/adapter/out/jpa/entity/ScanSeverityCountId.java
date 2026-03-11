package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link ScanSeverityCountEntity}.
 *
 * <p>Combines scan identifier, source type and source key to uniquely identify severity counts
 * for a specific scan-source combination.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanSeverityCountId implements Serializable {

    @Column(name = "scan_id", nullable = false, length = 255)
    private String scanId;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_key", nullable = false, length = 255)
    private String sourceKey;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScanSeverityCountId that = (ScanSeverityCountId) o;
        return Objects.equals(scanId, that.scanId)
            && Objects.equals(sourceType, that.sourceType)
            && Objects.equals(sourceKey, that.sourceKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, sourceType, sourceKey);
    }
}
