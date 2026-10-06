package com.itau.purchaseagent.contract;

import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import com.itau.purchaseagent.contract.Enums.Severity;
import java.util.List;

/**
 * O que o LLM analista produz (schema {@code llm-assessment.v1.json}, imposto via structured outputs).
 * Deliberadamente menor que {@link PurchaseDecision}: o modelo só emite o julgamento; evidências,
 * checagens de política, payload de ERP e auditoria são montados pelo código.
 */
public record LlmAssessment(
        Decision decision,
        RiskLevel riskLevel,
        int riskScore,
        double confidence,
        String summary,
        List<Reason> reasons,
        List<String> missingInformation) {

    public record Reason(String code, Severity severity, String explanation, List<String> evidenceIds) {}
}
