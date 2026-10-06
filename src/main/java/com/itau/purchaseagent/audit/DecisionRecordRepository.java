package com.itau.purchaseagent.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecordEntity, String> {

    List<DecisionRecordEntity> findByRequestIdOrderByCreatedAtDesc(String requestId);

    Optional<DecisionRecordEntity> findFirstByRequestIdAndInputHashAndCaseIdIsNullOrderByCreatedAtDesc(
            String requestId, String inputHash);
}
