package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity for SharePoint connection configuration.
 * Single-row configuration table (id always = 1).
 */
@Setter
@Getter
@Entity
@Table(name = "sharepoint_connection_config")
@Builder
@AllArgsConstructor
public class SharePointConnectionConfigEntity {

    @Id
    @Column(nullable = false)
    private Integer id;

    @NotNull
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @NotNull
    @Column(name = "client_id", nullable = false)
    private String clientId;

    @NotNull
    @Column(name = "client_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String clientSecretEncrypted;

    @NotNull
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected SharePointConnectionConfigEntity() {
        // Required by JPA
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharePointConnectionConfigEntity that = (SharePointConnectionConfigEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
