package com.itau.purchaseagent.agent;

import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.contract.Enums.DecidedBy;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import com.itau.purchaseagent.contract.Enums.Severity;
import com.itau.purchaseagent.contract.LlmAssessment;
import com.itau.purchaseagent.contract.PurchaseDecision;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyOutcome;
import com.itau.purchaseagent.policy.PolicyOutcome.Result;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Monta o {@link PurchaseDecision} final: a parte que é responsabilidade do código, não do modelo. */
@Component
class DecisionAssembler {

    PurchaseDecision assemble(NormalizedRequest req, PolicyOutcome policy, AgentContext ctx, LlmAssessment a,
                              DecidedBy decidedBy, String caseId, String traceId, String decisionId,
                              Map<String, String> skills, UsageTracker usage, long latencyMs, String fallbackReason,
                              List<String> guardrailEvents) {
        var checks = policy.checks().stream()
                .map(c -> new PurchaseDecision.PolicyCheckView(c.policyId(), policy.policyVersion(), c.result().name(),
                        c.detail(), "RULE_ENGINE"))
                .toList();
        var evidence = ctx.included().stream()
                .map(i -> new PurchaseDecision.EvidenceView(i.id(), i.source(), i.content()))
                .toList();
        var missing = new LinkedHashSet<>(a.missingInformation());
        if (a.decision() == Decision.NEEDS_INFO) {
            missing.addAll(req.missingInformation());
            missing.addAll(policy.missingInformation());
        }
        var audit = new PurchaseDecision.Audit(traceId, decisionId, decidedBy, skills, usage.primaryModel(),
                new PurchaseDecision.Tokens(usage.input(), usage.output(), usage.cacheRead()),
                round(usage.costUsd()), latencyMs, usage.callCount(), fallbackReason, guardrailEvents);
        return new PurchaseDecision(PurchaseDecision.SCHEMA_VERSION, req.requestId(), caseId, a.decision(),
                a.riskLevel(), a.riskScore(), a.confidence(), a.summary(), a.reasons(), checks, List.copyOf(missing),
                new PurchaseDecision.DataQualityView(req.dataQualityScore(), req.dataQualityIssues()), evidence,
                erpPayload(a.decision(), policy, req), audit);
    }

    static PurchaseDecision.ErpPayload erpPayload(Decision d, PolicyOutcome policy, NormalizedRequest req) {
        return switch (d) {
            case APPROVE -> policy.approvalLevel() == 0
                    ? new PurchaseDecision.ErpPayload("AUTO_APPROVE", 0, req.costCenter(), req.requestId())
                    : new PurchaseDecision.ErpPayload("ROUTE_FOR_APPROVAL", policy.approvalLevel(), req.costCenter(),
                            req.requestId());
            case REJECT -> new PurchaseDecision.ErpPayload("REJECT", policy.approvalLevel(), req.costCenter(),
                    req.requestId());
            case ESCALATE_TO_HUMAN -> new PurchaseDecision.ErpPayload("ROUTE_FOR_APPROVAL",
                    Math.max(1, policy.approvalLevel()), req.costCenter(), req.requestId());
            case NEEDS_INFO -> new PurchaseDecision.ErpPayload("RETURN_TO_REQUESTER", policy.approvalLevel(),
                    req.costCenter(), req.requestId());
        };
    }

    /** Decisão do motor de regras (sem LLM): rejeição obrigatória. */
    LlmAssessment ruleEngineReject(PolicyOutcome policy, AgentContext ctx) {
        List<LlmAssessment.Reason> reasons = reasonsFromPolicy(policy, ctx, Result.HARD_REJECT, Severity.CRITICAL);
        boolean blockedSupplier = reasons.stream().anyMatch(r -> r.code().equals("SUPPLIER_BLOCKED"));
        String summary = "Rejeitado por regra obrigatória: " + String.join("; ",
                policy.withResult(Result.HARD_REJECT).stream().map(PolicyOutcome.PolicyCheck::detail).toList()) + ".";
        return new LlmAssessment(Decision.REJECT, blockedSupplier ? RiskLevel.CRITICAL : RiskLevel.HIGH,
                blockedSupplier ? 95 : 75, 1.0, summary, reasons, List.of());
    }

    /** Fallback quando o LLM não produz avaliação utilizável: falha fechada para humano, ainda com evidências. */
    LlmAssessment fallback(PolicyOutcome policy, AgentContext ctx, String reason) {
        List<LlmAssessment.Reason> reasons = new ArrayList<>(
                reasonsFromPolicy(policy, ctx, Result.BLOCKS_APPROVAL, Severity.HIGH));
        reasons.addAll(reasonsFromPolicy(policy, ctx, Result.WARN, Severity.MEDIUM));
        reasons.add(new LlmAssessment.Reason("OTHER", Severity.MEDIUM,
                "Análise automática indisponível (%s); decisão requer revisão humana.".formatted(reason),
                List.of("EV-REQ")));
        return new LlmAssessment(Decision.ESCALATE_TO_HUMAN, RiskLevel.MEDIUM, 40, 0.0,
                "Análise automática indisponível; solicitação encaminhada para revisão humana com as checagens de regra.",
                List.copyOf(reasons), List.of());
    }

    private List<LlmAssessment.Reason> reasonsFromPolicy(PolicyOutcome policy, AgentContext ctx, Result result,
                                                         Severity severity) {
        List<LlmAssessment.Reason> out = new ArrayList<>();
        List<PolicyOutcome.PolicyCheck> checks = policy.checks();
        for (int i = 0; i < checks.size(); i++) {
            if (checks.get(i).result() == result) {
                String evId = "EV-POL-" + (i + 1);
                out.add(new LlmAssessment.Reason(checks.get(i).reasonCode(), severity, checks.get(i).detail(),
                        ctx.evidenceIds().contains(evId) ? List.of(evId) : List.of("EV-REQ")));
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
