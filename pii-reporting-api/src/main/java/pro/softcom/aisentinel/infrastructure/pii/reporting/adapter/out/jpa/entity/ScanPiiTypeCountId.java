package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link ScanPiiTypeCountEntity}.
 *
 * <p>Combines scan identifier, space key and PII type to uniquely identify the
 * occurrence count for a specific scan-space-type combination.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanPiiTypeCountId implements Serializable {

    @Column(name = "scan_id", nullable = false, length = 255)
    private String scanId;

    @Column(name = "space_key", nullable = false, length = 255)
    private String spaceKey;

    @Column(name = "pii_type", nullable = false, length = 255)
    private String piiType;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScanPiiTypeCountId that = (ScanPiiTypeCountId) o;
        return Objects.equals(scanId, that.scanId)
            && Objects.equals(spaceKey, that.spaceKey)
            && Objects.equals(piiType, that.piiType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, spaceKey, piiType);
    }
}
