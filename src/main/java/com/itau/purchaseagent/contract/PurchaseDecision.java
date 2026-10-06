package com.itau.purchaseagent.contract;

import com.itau.purchaseagent.contract.Enums.DecidedBy;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import java.util.List;
import java.util.Map;

/** Saída do agente (contrato {@code purchase-decision.v1.json}). Sempre válida, mesmo em fallback. */
public record PurchaseDecision(
        String schemaVersion,
        String requestId,
        String caseId,
        Decision decision,
        RiskLevel riskLevel,
        int riskScore,
        double confidence,
        String summary,
        List<LlmAssessment.Reason> reasons,
        List<PolicyCheckView> policyChecks,
        List<String> missingInformation,
        DataQualityView dataQuality,
        List<EvidenceView> evidence,
        ErpPayload erpPayload,
        Audit audit) {

    public static final String SCHEMA_VERSION = "1.0";

    public record PolicyCheckView(String policyId, String version, String result, String detail, String source) {}

    public record DataQualityView(double score, List<String> issues) {}

    public record EvidenceView(String id, String source, String summary) {}

    public record ErpPayload(String action, int approvalLevel, String costCenter, String requestId) {}

    public record Audit(
            String traceId,
            String decisionId,
            DecidedBy decidedBy,
            Map<String, String> skillVersions,
            String model,
            Tokens tokens,
            double estimatedCostUsd,
            long latencyMs,
            int llmCalls,
            String fallbackReason,
            List<String> guardrailEvents) {}

    public record Tokens(long input, long output, long cacheRead) {}
}
