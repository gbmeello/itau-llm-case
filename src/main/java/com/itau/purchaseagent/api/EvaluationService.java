package com.itau.purchaseagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itau.purchaseagent.agent.AgentProperties;
import com.itau.purchaseagent.agent.PromptRenderer;
import com.itau.purchaseagent.agent.PurchaseApprovalAgent;
import com.itau.purchaseagent.agent.PurchaseApprovalAgent.AgentOutcome;
import com.itau.purchaseagent.audit.AuditService;
import com.itau.purchaseagent.audit.CaseEntity;
import com.itau.purchaseagent.audit.CaseRepository;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.PurchaseDecision;
import com.itau.purchaseagent.contract.PurchaseRequest;
import com.itau.purchaseagent.contract.SchemaValidator;
import com.itau.purchaseagent.llm.LlmClient;
import com.itau.purchaseagent.llm.LlmException;
import com.itau.purchaseagent.registry.SkillRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Caso de uso da API: valida contrato, aplica idempotência, chama o agente, audita e gerencia casos multi-turno. */
@Service
public class EvaluationService {

    public record Result(PurchaseDecision decision, boolean replayed) {}

    private final SchemaValidator schemas;
    private final ObjectMapper mapper;
    private final ObjectMapper canonical;
    private final PurchaseApprovalAgent agent;
    private final AuditService audit;
    private final CaseRepository cases;
    private final SkillRegistry skills;
    private final PromptRenderer renderer;
    private final LlmClient llm;
    private final AgentProperties props;

    public EvaluationService(SchemaValidator schemas, ObjectMapper mapper, PurchaseApprovalAgent agent,
                             AuditService audit, CaseRepository cases, SkillRegistry skills, PromptRenderer renderer,
                             LlmClient llm, AgentProperties props) {
        this.schemas = schemas;
        this.mapper = mapper;
        this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.agent = agent;
        this.audit = audit;
        this.cases = cases;
        this.skills = skills;
        this.renderer = renderer;
        this.llm = llm;
        this.props = props;
    }

    public Result evaluate(JsonNode body) {
        PurchaseRequest request = parse(body);
        String hash = AuditService.hash(canonicalJson(body));
        var previous = audit.previousDecision(request.requestId(), hash);
        if (previous.isPresent()) {
            return new Result(previous.get(), true);
        }
        AgentOutcome outcome = agent.evaluate(request, null, null);
        PurchaseDecision decision = outcome.decision();
        if (decision.decision() == Decision.NEEDS_INFO) {
            String caseId = UUID.randomUUID().toString();
            decision = withCase(decision, caseId);
            outcome = new AgentOutcome(decision, outcome.request(), outcome.context(), outcome.policy());
            cases.save(new CaseEntity(caseId, request.requestId(), body.toString(),
                    "Rodada 1: NEEDS_INFO. Pendências: " + String.join("; ", decision.missingInformation()),
                    decision.audit().decisionId(), Instant.now()));
        }
        audit.record(outcome, hash);
        return new Result(decision, false);
    }

    /**
     * Nova rodada de um caso NEEDS_INFO. A resposta do solicitante complementa a justificativa (continua sendo dado
     * não confiável) e o estado do caso é re-sumarizado com teto fixo, então o contexto não cresce por rodada.
     */
    public Result continueCase(String caseId, String message, JsonNode updates) {
        CaseEntity c = cases.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado"));
        if (c.getStatus() == CaseEntity.Status.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Caso encerrado");
        }
        if (message != null && message.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem acima de 2000 caracteres");
        }
        ObjectNode merged = (ObjectNode) readTree(c.getRequestJson());
        if (updates != null && updates.isObject()) {
            merge(merged, updates);
        }
        merged.put("requestId", c.getRequestId());
        if (message != null && !message.isBlank()) {
            String prev = merged.path("justification").isTextual() ? merged.get("justification").asText() + "\n" : "";
            String added = "[Complemento rodada " + (c.getRound() + 1) + "] " + message.strip();
            // Mantém o texto dentro do limite do contrato (4.000): preserva o complemento mais recente.
            int room = Math.max(0, 4000 - added.length());
            merged.put("justification", (prev.length() > room ? prev.substring(prev.length() - room) : prev) + added);
        }
        PurchaseRequest request = parse(merged);
        String summary = summarize(c.getStateSummary(), message, updates);
        AgentOutcome outcome = agent.evaluate(request, caseId, summary);
        PurchaseDecision decision = outcome.decision();

        boolean exhausted = decision.decision() == Decision.NEEDS_INFO && c.getRound() + 1 >= props.maxCaseRounds();
        if (exhausted) {
            decision = escalateExhausted(decision);
            outcome = new AgentOutcome(decision, outcome.request(), outcome.context(), outcome.policy());
        }
        boolean close = decision.decision() != Decision.NEEDS_INFO;
        String newState = summary + " | Rodada " + (c.getRound() + 1) + ": " + decision.decision();
        c.advance(merged.toString(), newState.length() > 1900 ? newState.substring(newState.length() - 1900) : newState,
                decision.audit().decisionId(), close, Instant.now());
        cases.save(c); // sem @Transactional: a chamada ao LLM não segura conexão de banco
        audit.record(outcome, AuditService.hash(canonicalJson(merged)));
        return new Result(decision, false);
    }

    public CaseEntity getCase(String caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado"));
    }

    private String summarize(String previous, String message, JsonNode updates) {
        String round = "Mensagem do solicitante: " + (message == null ? "(nenhuma)" : message)
                + (updates == null ? "" : " | Campos atualizados: " + updates);
        try {
            var skill = skills.required(SkillRegistry.CASE_SUMMARIZER);
            var r = llm.complete(new LlmClient.LlmRequest("case-summarizer", props.reviewerModel(),
                    renderer.system(skill.content(), Map.of()),
                    "<previous_summary>\n" + previous + "\n</previous_summary>\n<new_round>\n"
                            + PromptRenderer.neutralize(round) + "\n</new_round>", null, 400, null));
            return r.text().length() > 600 ? r.text().substring(0, 600) : r.text();
        } catch (LlmException e) {
            // Fallback determinístico: resumo anterior + rodada, com teto fixo.
            String s = previous + " | " + round;
            return s.length() > 600 ? s.substring(s.length() - 600) : s;
        }
    }

    private PurchaseDecision escalateExhausted(PurchaseDecision d) {
        List<String> events = new ArrayList<>(d.audit().guardrailEvents());
        events.add("CASE_ROUNDS_EXHAUSTED");
        var audit = new PurchaseDecision.Audit(d.audit().traceId(), d.audit().decisionId(), d.audit().decidedBy(),
                d.audit().skillVersions(), d.audit().model(), d.audit().tokens(), d.audit().estimatedCostUsd(),
                d.audit().latencyMs(), d.audit().llmCalls(), d.audit().fallbackReason(), events);
        return new PurchaseDecision(d.schemaVersion(), d.requestId(), d.caseId(), Decision.ESCALATE_TO_HUMAN,
                d.riskLevel(), d.riskScore(), d.confidence(),
                d.summary() + " (Limite de rodadas de informação atingido; encaminhado para revisão humana.)",
                d.reasons(), d.policyChecks(), d.missingInformation(), d.dataQuality(), d.evidence(),
                new PurchaseDecision.ErpPayload("ROUTE_FOR_APPROVAL", Math.max(1, d.erpPayload().approvalLevel()),
                        d.erpPayload().costCenter(), d.requestId()), audit);
    }

    private static PurchaseDecision withCase(PurchaseDecision d, String caseId) {
        return new PurchaseDecision(d.schemaVersion(), d.requestId(), caseId, d.decision(), d.riskLevel(),
                d.riskScore(), d.confidence(), d.summary(), d.reasons(), d.policyChecks(), d.missingInformation(),
                d.dataQuality(), d.evidence(), d.erpPayload(), d.audit());
    }

    private PurchaseRequest parse(JsonNode body) {
        List<String> errors = schemas.validate(SchemaValidator.REQUEST_V1, body);
        if (!errors.isEmpty()) {
            throw new ContractViolationException(errors);
        }
        return mapper.convertValue(body, PurchaseRequest.class);
    }

    private static void merge(ObjectNode target, JsonNode updates) {
        updates.fields().forEachRemaining(e -> {
            JsonNode existing = target.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) {
                merge((ObjectNode) existing, e.getValue());
            } else {
                target.set(e.getKey(), e.getValue());
            }
        });
    }

    private String canonicalJson(JsonNode node) {
        try {
            return canonical.writeValueAsString(canonical.treeToValue(node, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
