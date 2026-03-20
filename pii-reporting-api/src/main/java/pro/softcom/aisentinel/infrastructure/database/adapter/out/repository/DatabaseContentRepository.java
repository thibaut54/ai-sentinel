package pro.softcom.aisentinel.infrastructure.database.adapter.out.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pro.softcom.aisentinel.infrastructure.database.adapter.out.entity.DatabaseContentEntity;

import java.util.List;

@Repository
public interface DatabaseContentRepository extends JpaRepository<DatabaseContentEntity, String> {
    List<DatabaseContentEntity> findBySourceId(String sourceId);
}
