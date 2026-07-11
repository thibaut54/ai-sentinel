package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa.entity.FindingRemediationEntity;

import java.util.Collection;
import java.util.List;

@Repository
public interface FindingRemediationRepository extends JpaRepository<FindingRemediationEntity, String> {

    List<FindingRemediationEntity> findBySpaceKey(String spaceKey);

    List<FindingRemediationEntity> findBySpaceKeyAndStatusIn(String spaceKey, Collection<String> statuses);

    List<FindingRemediationEntity> findBySpaceKeyAndPageId(String spaceKey, String pageId);

    List<FindingRemediationEntity> findBySpaceKeyAndPageIdAndStatusIn(
            String spaceKey, String pageId, Collection<String> statuses);
}
