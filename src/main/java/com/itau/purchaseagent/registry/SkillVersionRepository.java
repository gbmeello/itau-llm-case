package com.itau.purchaseagent.registry;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, Long> {

    List<SkillVersionEntity> findBySkillIdOrderByCreatedAtAsc(String skillId);

    Optional<SkillVersionEntity> findBySkillIdAndVersion(String skillId, String version);

    Optional<SkillVersionEntity> findBySkillIdAndStatus(String skillId, SkillVersionEntity.Status status);

    List<SkillVersionEntity> findByStatus(SkillVersionEntity.Status status);

    boolean existsBySkillId(String skillId);
}
