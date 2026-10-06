package com.itau.purchaseagent.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.agent.PurchaseApprovalAgent.AgentOutcome;
import com.itau.purchaseagent.contract.PurchaseDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final DecisionRecordRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditService(DecisionRecordRepository repo, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void record(AgentOutcome outcome, String inputHash) {
        PurchaseDecision d = outcome.decision();
        String included = outcome.context().included().stream().map(i -> i.id()).collect(Collectors.joining(","));
        repo.save(new DecisionRecordEntity(d.audit().decisionId(), d.requestId(), d.caseId(), inputHash,
                d.audit().traceId(), d.decision().name(), d.riskLevel().name(), d.audit().decidedBy().name(),
                d.audit().skillVersions().toString(), truncate(included, 2000),
                truncate(String.join(",", outcome.context().excludedIds()), 1000),
                outcome.context().estimatedTokens(), d.audit().tokens().input(), d.audit().tokens().output(),
                d.audit().estimatedCostUsd(), d.audit().latencyMs(), json(outcome.request()), json(d),
                Instant.now(clock)));
    }

    /** Idempotência/FinOps: mesma solicitação com o mesmo conteúdo não paga outra chamada ao LLM. */
    @Transactional(readOnly = true)
    public Optional<PurchaseDecision> previousDecision(String requestId, String inputHash) {
        return repo.findFirstByRequestIdAndInputHashAndCaseIdIsNullOrderByCreatedAtDesc(requestId, inputHash)
                .map(e -> read(e.getDecisionJson()));
    }

    @Transactional(readOnly = true)
    public Optional<DecisionRecordEntity> find(String decisionId) {
        return repo.findById(decisionId);
    }

    @Transactional(readOnly = true)
    public List<DecisionRecordEntity> byRequest(String requestId) {
        return repo.findByRequestIdOrderByCreatedAtDesc(requestId);
    }

    public PurchaseDecision read(String json) {
        try {
            return mapper.readValue(json, PurchaseDecision.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
