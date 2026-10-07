package com.itau.purchaseagent.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Caso aberto quando o agente pede informação (NEEDS_INFO). Guarda estado resumido, não a conversa inteira. */
@Entity
@Table(name = "approval_case")
public class CaseEntity {

    public enum Status { OPEN, CLOSED }

    @Id
    @Column(length = 36)
    private String caseId;

    @Column(nullable = false, length = 64)
    private String requestId;

    @Column(nullable = false, length = 8)
    private String status;

    private int round;

    /** Resumo incremental (teto fixo de tamanho): é o que entra no contexto das próximas rodadas. */
    @Column(length = 2000)
    private String stateSummary;

    @Column(nullable = false, length = 1_048_576) // texto longo portável (H2 e PostgreSQL), sem LOB/oid
    private String requestJson;

    @Column(length = 36)
    private String lastDecisionId;

    private Instant updatedAt;

    protected CaseEntity() {}

    public CaseEntity(String caseId, String requestId, String requestJson, String stateSummary, String lastDecisionId,
                      Instant now) {
        this.caseId = caseId;
        this.requestId = requestId;
        this.status = Status.OPEN.name();
        this.round = 1;
        this.requestJson = requestJson;
        this.stateSummary = stateSummary;
        this.lastDecisionId = lastDecisionId;
        this.updatedAt = now;
    }

    public void advance(String requestJson, String stateSummary, String lastDecisionId, boolean close, Instant now) {
        this.round++;
        this.requestJson = requestJson;
        this.stateSummary = stateSummary;
        this.lastDecisionId = lastDecisionId;
        this.status = close ? Status.CLOSED.name() : Status.OPEN.name();
        this.updatedAt = now;
    }

    public String getCaseId() { return caseId; }
    public String getRequestId() { return requestId; }
    public Status getStatus() { return Status.valueOf(status); }
    public int getRound() { return round; }
    public String getStateSummary() { return stateSummary; }
    public String getRequestJson() { return requestJson; }
    public String getLastDecisionId() { return lastDecisionId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
