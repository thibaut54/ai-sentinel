package pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa.entity.JiraConnectionConfigEntity;

/**
 * Spring Data JPA repository for Jira connection configuration.
 * Provides database access for the connection configuration entity.
 */
@Repository
public interface JiraConnectionConfigJpaRepository extends JpaRepository<JiraConnectionConfigEntity, Integer> {
}
