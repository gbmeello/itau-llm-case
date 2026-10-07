package com.itau.purchaseagent.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Registro de auditoria imutável de cada decisão: o que entrou no contexto, o que foi cortado,
 * quais versões de skills/modelo foram usadas, custo e latência. Responde "por que o agente decidiu isso?".
 */
@Entity
@Table(name = "decision_record", indexes = {
        @Index(name = "ix_decision_request", columnList = "requestId"),
        @Index(name = "ix_decision_hash", columnList = "requestId,inputHash")})
public class DecisionRecordEntity {

    @Id
    @Column(length = 36)
    private String decisionId;

    @Column(nullable = false, length = 64)
    private String requestId;

    @Column(length = 36)
    private String caseId;

    @Column(nullable = false, length = 64)
    private String inputHash;

    @Column(nullable = false, length = 36)
    private String traceId;

    @Column(nullable = false, length = 24)
    private String decision;

    @Column(nullable = false, length = 16)
    private String riskLevel;

    @Column(nullable = false, length = 24)
    private String decidedBy;

    @Column(length = 400)
    private String skillVersions;

    @Column(length = 2000)
    private String contextIncluded;

    @Column(length = 1000)
    private String contextExcluded;

    private int contextTokens;
    private long tokensIn;
    private long tokensOut;
    private double costUsd;
    private long latencyMs;

    @Column(nullable = false, length = 1_048_576) // texto longo portável (H2 e PostgreSQL), sem LOB/oid
    private String normalizedRequestJson;

    @Column(nullable = false, length = 1_048_576) // texto longo portável (H2 e PostgreSQL), sem LOB/oid
    private String decisionJson;

    @Column(nullable = false)
    private Instant createdAt;

    protected DecisionRecordEntity() {}

    public DecisionRecordEntity(String decisionId, String requestId, String caseId, String inputHash, String traceId,
                                String decision, String riskLevel, String decidedBy, String skillVersions,
                                String contextIncluded, String contextExcluded, int contextTokens, long tokensIn,
                                long tokensOut, double costUsd, long latencyMs, String normalizedRequestJson,
                                String decisionJson, Instant createdAt) {
        this.decisionId = decisionId;
        this.requestId = requestId;
        this.caseId = caseId;
        this.inputHash = inputHash;
        this.traceId = traceId;
        this.decision = decision;
        this.riskLevel = riskLevel;
        this.decidedBy = decidedBy;
        this.skillVersions = skillVersions;
        this.contextIncluded = contextIncluded;
        this.contextExcluded = contextExcluded;
        this.contextTokens = contextTokens;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.costUsd = costUsd;
        this.latencyMs = latencyMs;
        this.normalizedRequestJson = normalizedRequestJson;
        this.decisionJson = decisionJson;
        this.createdAt = createdAt;
    }

    public String getDecisionId() { return decisionId; }
    public String getRequestId() { return requestId; }
    public String getCaseId() { return caseId; }
    public String getInputHash() { return inputHash; }
    public String getTraceId() { return traceId; }
    public String getDecision() { return decision; }
    public String getRiskLevel() { return riskLevel; }
    public String getDecidedBy() { return decidedBy; }
    public String getSkillVersions() { return skillVersions; }
    public String getContextIncluded() { return contextIncluded; }
    public String getContextExcluded() { return contextExcluded; }
    public int getContextTokens() { return contextTokens; }
    public long getTokensIn() { return tokensIn; }
    public long getTokensOut() { return tokensOut; }
    public double getCostUsd() { return costUsd; }
    public long getLatencyMs() { return latencyMs; }
    public String getNormalizedRequestJson() { return normalizedRequestJson; }
    public String getDecisionJson() { return decisionJson; }
    public Instant getCreatedAt() { return createdAt; }
}
