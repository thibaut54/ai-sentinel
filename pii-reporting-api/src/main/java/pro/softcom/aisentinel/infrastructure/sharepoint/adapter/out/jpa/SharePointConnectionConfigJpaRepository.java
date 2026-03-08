package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.entity.SharePointConnectionConfigEntity;

@Repository
public interface SharePointConnectionConfigJpaRepository extends JpaRepository<SharePointConnectionConfigEntity, Integer> {
}
