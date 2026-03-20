package pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity for Jira connection configuration.
 * Represents the database table for storing Jira connection settings.
 * Single-row configuration table (id always = 1).
 */
@Setter
@Getter
@Entity
@Table(name = "jira_connection_config")
@Builder
@AllArgsConstructor
public class JiraConnectionConfigEntity {

    @Id
    @Column(nullable = false)
    private Integer id;

    @NotNull
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @NotNull
    @Column(name = "email", nullable = false)
    private String email;

    @NotNull
    @Column(name = "api_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiTokenEncrypted;

    @NotNull
    @Min(value = 1, message = "Connect timeout must be positive")
    @Column(name = "connect_timeout", nullable = false)
    private Integer connectTimeout;

    @NotNull
    @Min(value = 1, message = "Read timeout must be positive")
    @Column(name = "read_timeout", nullable = false)
    private Integer readTimeout;

    @NotNull
    @Min(value = 0, message = "Max retries cannot be negative")
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries;

    @NotNull
    @Min(value = 1, message = "Issues limit must be positive")
    @Column(name = "issues_limit", nullable = false)
    private Integer issuesLimit;

    @NotNull
    @Min(value = 1, message = "Max issues must be positive")
    @Column(name = "max_issues", nullable = false)
    private Integer maxIssues;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_type", nullable = false, length = 20)
    private JiraDeploymentType deploymentType;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected JiraConnectionConfigEntity() {
        // Required by JPA
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JiraConnectionConfigEntity that = (JiraConnectionConfigEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
