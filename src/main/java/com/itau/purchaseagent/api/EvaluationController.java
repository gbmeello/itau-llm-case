package com.itau.purchaseagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.itau.purchaseagent.audit.AuditService;
import com.itau.purchaseagent.audit.CaseEntity;
import com.itau.purchaseagent.audit.DecisionRecordEntity;
import com.itau.purchaseagent.contract.PurchaseDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class EvaluationController {

    private final EvaluationService service;
    private final AuditService audit;

    public EvaluationController(EvaluationService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/purchase-requests/evaluate")
    public ResponseEntity<PurchaseDecision> evaluate(@RequestBody JsonNode body) {
        EvaluationService.Result r = service.evaluate(body);
        return ResponseEntity.ok().header("X-Idempotent-Replay", String.valueOf(r.replayed())).body(r.decision());
    }

    public record CaseMessage(String message, JsonNode updates) {}

    @PostMapping("/cases/{caseId}/messages")
    public PurchaseDecision continueCase(@PathVariable String caseId, @RequestBody CaseMessage body) {
        return service.continueCase(caseId, body.message(), body.updates()).decision();
    }

    @GetMapping("/cases/{caseId}")
    public Map<String, Object> getCase(@PathVariable String caseId) {
        CaseEntity c = service.getCase(caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", c.getCaseId());
        out.put("requestId", c.getRequestId());
        out.put("status", c.getStatus());
        out.put("round", c.getRound());
        out.put("stateSummary", c.getStateSummary());
        out.put("lastDecisionId", c.getLastDecisionId());
        out.put("updatedAt", c.getUpdatedAt());
        return out;
    }

    /** Visão de auditoria: decisão + o que entrou/saiu do contexto + versões + custo. */
    @GetMapping("/decisions/{decisionId}")
    public Map<String, Object> decision(@PathVariable String decisionId) {
        DecisionRecordEntity e = audit.find(decisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Decisão não encontrada"));
        return view(e, true);
    }

    @GetMapping("/decisions")
    public List<Map<String, Object>> decisions(@RequestParam String requestId) {
        return audit.byRequest(requestId).stream().map(e -> view(e, false)).toList();
    }

    private Map<String, Object> view(DecisionRecordEntity e, boolean full) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisionId", e.getDecisionId());
        out.put("requestId", e.getRequestId());
        out.put("caseId", e.getCaseId());
        out.put("traceId", e.getTraceId());
        out.put("decision", e.getDecision());
        out.put("riskLevel", e.getRiskLevel());
        out.put("decidedBy", e.getDecidedBy());
        out.put("skillVersions", e.getSkillVersions());
        out.put("contextIncluded", e.getContextIncluded());
        out.put("contextExcluded", e.getContextExcluded());
        out.put("contextTokensEstimated", e.getContextTokens());
        out.put("tokensIn", e.getTokensIn());
        out.put("tokensOut", e.getTokensOut());
        out.put("costUsd", e.getCostUsd());
        out.put("latencyMs", e.getLatencyMs());
        out.put("createdAt", e.getCreatedAt());
        if (full) {
            out.put("decisionPayload", audit.read(e.getDecisionJson()));
        }
        return out;
    }
}
