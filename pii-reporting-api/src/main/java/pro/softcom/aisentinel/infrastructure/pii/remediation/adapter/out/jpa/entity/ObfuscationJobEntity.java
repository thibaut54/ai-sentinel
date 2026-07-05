package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapped to pii_redaction_job table — the audit journal of redaction jobs.
 * The one-RUNNING-job-per-space invariant is a partial unique index that JPA cannot
 * express; it lives in init-scripts/014-pii-finding-remediation.sql.
 */
@Getter
@Setter
@Entity
@Table(name = "pii_redaction_job", indexes = {
        @Index(name = "idx_pii_redaction_job_space_status", columnList = "space_key,status")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObfuscationJobEntity {

    @Id
    @Column(name = "id", nullable = false, length = 100)
    private String id;

    @Column(name = "space_key", nullable = false, length = 50)
    private String spaceKey;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_selection", columnDefinition = "jsonb", nullable = false)
    private JsonNode submittedSelection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_finding_ids", columnDefinition = "jsonb", nullable = false)
    private JsonNode resolvedFindingIds;

    @Column(name = "processed", nullable = false)
    private int processed;

    @Column(name = "total", nullable = false)
    private int total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcomes", columnDefinition = "jsonb", nullable = false)
    private JsonNode outcomes;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
