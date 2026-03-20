package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to scan_checkpoints table.
 * Business note: represents the fine-grained resume cursor per scan, source type and source key.
 */
@Getter
@Setter
@Entity
@Table(name = "scan_checkpoints")
@IdClass(ScanCheckpointId.class)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanCheckpointEntity {

    @Id
    @Column(name = "scan_id", nullable = false)
    private String scanId;

    @Version
    private Long version;

    @Id
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Id
    @Column(name = "source_key", nullable = false)
    private String sourceKey;

    @Column(name = "last_processed_content_id")
    private String lastProcessedContentId;

    @Column(name = "last_processed_attachment_name")
    private String lastProcessedAttachmentName;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "progress_percentage")
    private Double progressPercentage;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
