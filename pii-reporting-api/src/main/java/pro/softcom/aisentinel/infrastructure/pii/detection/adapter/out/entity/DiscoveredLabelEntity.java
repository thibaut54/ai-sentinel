package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity for MINISTRAL discovered labels.
 * <p>
 * Maps to the ministral_discovered_label database table. The {@code label}
 * column is unique so the atomic upsert can rely on {@code ON CONFLICT (label)}.
 */
@Setter
@Getter
@Entity
@Table(
        name = "ministral_discovered_label",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_discovered_label",
                columnNames = "label"
        ),
        indexes = @Index(name = "idx_ministral_discovered_label_status", columnList = "status")
)
@NoArgsConstructor
public class DiscoveredLabelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label", nullable = false, unique = true, length = 100)
    private String label;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DiscoveredLabelStatus status;

    // Factory method to create entity from domain model
    public static DiscoveredLabelEntity fromDomain(DiscoveredLabel domain) {
        DiscoveredLabelEntity entity = new DiscoveredLabelEntity();
        entity.label = domain.label();
        entity.occurrenceCount = domain.occurrenceCount();
        entity.firstSeen = domain.firstSeen();
        entity.lastSeen = domain.lastSeen();
        entity.status = domain.status();
        return entity;
    }

    // Conversion method to domain model
    public DiscoveredLabel toDomain() {
        return new DiscoveredLabel(label, occurrenceCount, firstSeen, lastSeen, status);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveredLabelEntity that = (DiscoveredLabelEntity) o;
        return Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label);
    }
}
