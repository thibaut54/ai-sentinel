package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link ScanDetectorStatsEntity}: scan, space, detector.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanDetectorStatsId implements Serializable {

    @Column(name = "scan_id", nullable = false, length = 255)
    private String scanId;

    @Column(name = "space_key", nullable = false, length = 255)
    private String spaceKey;

    @Column(name = "detector", nullable = false, length = 64)
    private String detector;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScanDetectorStatsId that = (ScanDetectorStatsId) o;
        return Objects.equals(scanId, that.scanId)
            && Objects.equals(spaceKey, that.spaceKey)
            && Objects.equals(detector, that.detector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, spaceKey, detector);
    }
}
