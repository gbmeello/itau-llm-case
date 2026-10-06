package com.itau.purchaseagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.context.ContextBuilder;
import com.itau.purchaseagent.context.FactsCollector;
import com.itau.purchaseagent.context.PurchaseFacts;
import com.itau.purchaseagent.context.TokenEstimator;
import com.itau.purchaseagent.contract.Enums.DecidedBy;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import com.itau.purchaseagent.contract.Enums.Severity;
import com.itau.purchaseagent.contract.LlmAssessment;
import com.itau.purchaseagent.contract.PurchaseDecision;
import com.itau.purchaseagent.contract.PurchaseRequest;
import com.itau.purchaseagent.contract.SchemaValidator;
import com.itau.purchaseagent.intake.IntakeService;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.llm.LlmClient;
import com.itau.purchaseagent.llm.LlmClient.LlmRequest;
import com.itau.purchaseagent.llm.LlmClient.LlmResponse;
import com.itau.purchaseagent.llm.LlmException;
import com.itau.purchaseagent.observability.AgentMetrics;
import com.itau.purchaseagent.policy.PolicyConfig;
import com.itau.purchaseagent.policy.PolicyEngine;
import com.itau.purchaseagent.policy.PolicyOutcome;
import com.itau.purchaseagent.registry.SkillRegistry;
import com.itau.purchaseagent.registry.SkillRegistry.ActiveSkill;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Orquestrador do pipeline (multi-agente com papéis especializados):
 * <pre>
 * intake → fatos → regras → contexto → [regra rígida? decide sem LLM]
 *        → ANALISTA (Sonnet) → VALIDADOR (código) → [reparo 1x] → guardrails
 *        → COMPLIANCE (Haiku, só quando o risco justifica) → montagem do contrato → auditoria/métricas
 * </pre>
 * Qualquer falha do LLM termina em decisão válida com {@code decidedBy=FALLBACK} (falha fechada).
 */
@Service
public class PurchaseApprovalAgent {

    private static final Logger log = LoggerFactory.getLogger(PurchaseApprovalAgent.class);

    private final IntakeService intake;
    private final FactsCollector facts;
    private final PolicyEngine policyEngine;
    private final ContextBuilder contextBuilder;
    private final SkillRegistry skills;
    private final PromptRenderer renderer;
    private final LlmClient llm;
    private final DecisionValidator validator;
    private final DecisionAssembler assembler;
    private final SchemaValidator schemas;
    private final AgentMetrics metrics;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final JsonNode assessmentSchema;
    private final JsonNode complianceSchema;

    public PurchaseApprovalAgent(IntakeService intake, FactsCollector facts, PolicyEngine policyEngine,
                                 ContextBuilder contextBuilder, SkillRegistry skills, PromptRenderer renderer,
                                 LlmClient llm, DecisionValidator validator, DecisionAssembler assembler,
                                 SchemaValidator schemas, AgentMetrics metrics, AgentProperties props,
                                 ObjectMapper mapper) {
        this.intake = intake;
        this.facts = facts;
        this.policyEngine = policyEngine;
        this.contextBuilder = contextBuilder;
        this.skills = skills;
        this.renderer = renderer;
        this.llm = llm;
        this.validator = validator;
        this.assembler = assembler;
        this.schemas = schemas;
        this.metrics = metrics;
        this.props = props;
        this.mapper = mapper;
        this.assessmentSchema = readSchema(SchemaValidator.LLM_ASSESSMENT_V1);
        this.complianceSchema = readSchema("compliance-review.v1.json");
    }

    /** Resultado completo para auditoria (o contrato público é só {@code decision}). */
    public record AgentOutcome(PurchaseDecision decision, NormalizedRequest request, AgentContext context,
                               PolicyOutcome policy) {}

    public AgentOutcome evaluate(PurchaseRequest raw, String caseId, String caseState) {
        long start = System.nanoTime();
        String traceId = Optional.ofNullable(MDC.get("traceId")).orElse(UUID.randomUUID().toString());
        String decisionId = UUID.randomUUID().toString();
        UsageTracker usage = new UsageTracker();
        Map<String, String> skillRefs = new LinkedHashMap<>();
        List<String> events = new ArrayList<>();

        NormalizedRequest req = intake.normalize(raw);
        PurchaseFacts f = facts.collect(req);
        ActiveSkill policySkill = skills.required(SkillRegistry.POLICIES);
        skillRefs.put(policySkill.skillId(), policySkill.version());
        PolicyConfig cfg = parsePolicy(policySkill);
        PolicyOutcome policy = policyEngine.evaluate(req, f, cfg);

        Optional<ActiveSkill> examples = skills.active(SkillRegistry.EXAMPLES);
        String examplesText = examples.map(e -> PromptRenderer.stripFrontmatter(e.content())).orElse("");
        AgentContext ctx = contextBuilder.build(req, f, policy, cfg, caseState, TokenEstimator.estimate(examplesText));
        metrics.context(ctx);
        boolean examplesIncluded = examples.isPresent() && !ctx.excludedIds().contains("FEW_SHOT_EXAMPLES");
        if (examplesIncluded) {
            skillRefs.put(examples.get().skillId(), examples.get().version());
        }

        LlmAssessment assessment;
        DecidedBy decidedBy;
        String fallbackReason = null;

        if (policy.hardReject()) {
            // Economia e previsibilidade: violação objetiva não precisa (nem deve) passar pelo modelo.
            assessment = assembler.ruleEngineReject(policy, ctx);
            decidedBy = DecidedBy.RULE_ENGINE;
        } else {
            ActiveSkill analyst = skills.required(SkillRegistry.ANALYST);
            skillRefs.put(analyst.skillId(), analyst.version());
            String system = renderer.system(analyst.content(),
                    Map.of("examples", examplesIncluded ? examplesText : ""));
            String user = renderer.analystUser(req, ctx);
            try {
                assessment = analyze(system, user, ctx, usage, skillRefs, events);
                if (assessment == null) {
                    fallbackReason = "VALIDATION_FAILED";
                    assessment = assembler.fallback(policy, ctx, "saída do modelo inválida após reparo");
                    decidedBy = DecidedBy.FALLBACK;
                } else {
                    var guarded = validator.enforce(assessment, policy, req);
                    assessment = guarded.assessment();
                    events.addAll(guarded.events());
                    decidedBy = guarded.overridden() ? DecidedBy.VALIDATOR_OVERRIDE : DecidedBy.AGENT;
                    if (needsComplianceReview(assessment, policy, cfg, req)) {
                        var reviewed = complianceReview(user, assessment, usage, skillRefs, events);
                        if (reviewed != assessment) {
                            assessment = reviewed;
                            decidedBy = DecidedBy.COMPLIANCE_OVERRIDE;
                        }
                    }
                }
            } catch (LlmException e) {
                usage.failed();
                log.warn("llm_unavailable kind={} requestId={} msg={}", e.kind(), req.requestId(), e.getMessage());
                fallbackReason = "LLM_" + e.kind().name();
                assessment = assembler.fallback(policy, ctx, "LLM indisponível: " + e.kind());
                decidedBy = DecidedBy.FALLBACK;
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        PurchaseDecision decision = assembler.assemble(req, policy, ctx, assessment, decidedBy, caseId, traceId,
                decisionId, skillRefs, usage, latencyMs, fallbackReason, List.copyOf(events));

        List<String> contractErrors = schemas.validate(SchemaValidator.DECISION_V1, mapper.valueToTree(decision));
        if (!contractErrors.isEmpty()) {
            // Não deveria acontecer (montado por código); se acontecer é bug: loga alto e mantém a falha visível.
            log.error("decision_contract_violation requestId={} errors={}", req.requestId(), contractErrors);
        }
        metrics.decision(decision);
        log.info("decision requestId={} decisionId={} decision={} risk={} decidedBy={} llmCalls={} tokensIn={} "
                        + "tokensOut={} costUsd={} latencyMs={} contextTokens={} excluded={} events={} skills={}",
                req.requestId(), decisionId, decision.decision(), decision.riskLevel(), decidedBy, usage.callCount(),
                usage.input(), usage.output(), decision.audit().estimatedCostUsd(), latencyMs, ctx.estimatedTokens(),
                ctx.excludedIds(), events, skillRefs);
        return new AgentOutcome(decision, req, ctx, policy);
    }

    /** Analista + validação + até N reparos. Retorna null se a saída continuar inválida. */
    private LlmAssessment analyze(String system, String user, AgentContext ctx, UsageTracker usage,
                                  Map<String, String> skillRefs, List<String> events) {
        LlmResponse r = call("analyst", props.analystModel(), system, user, assessmentSchema,
                props.analystMaxTokens(), props.analystEffort(), usage);
        DecisionValidator.Parsed parsed = validator.parseAndCheck(r.text(), ctx);
        int attempts = 0;
        while (!parsed.ok() && attempts < props.maxRepairAttempts()) {
            attempts++;
            metrics.validationFailure("analyst", parsed.repairableErrors().size());
            events.add("REPAIR_ATTEMPT_" + attempts);
            log.info("analyst_output_invalid attempt={} errors={}", attempts, parsed.repairableErrors());
            ActiveSkill repair = skills.required(SkillRegistry.REPAIR);
            skillRefs.put(repair.skillId(), repair.version());
            String repairSystem = renderer.system(repair.content(), Map.of());
            LlmResponse rr = call("repair", props.analystModel(), repairSystem,
                    renderer.repairUser(user, r.text(), parsed.repairableErrors()), assessmentSchema,
                    props.analystMaxTokens(), props.analystEffort(), usage);
            parsed = validator.parseAndCheck(rr.text(), ctx);
            r = rr;
        }
        if (!parsed.ok()) {
            metrics.validationFailure("repair", parsed.repairableErrors().size());
            log.warn("analyst_output_invalid_final errors={}", parsed.repairableErrors());
            return null;
        }
        return parsed.assessment().orElseThrow();
    }

    /** Segunda opinião apenas onde o erro custa caro: aprovação acima da alçada automática ou com risco ≥ MEDIUM. */
    private boolean needsComplianceReview(LlmAssessment a, PolicyOutcome policy, PolicyConfig cfg,
                                          NormalizedRequest req) {
        if (a.decision() == Decision.APPROVE) {
            return req.totalAmount().compareTo(cfg.autoApprovalLimit()) > 0 || a.riskLevel().atLeast(RiskLevel.MEDIUM);
        }
        // Rejeição pelo modelo (sem regra rígida) também afeta o solicitante: revisar.
        return a.decision() == Decision.REJECT && !policy.hardReject();
    }

    private LlmAssessment complianceReview(String analystUser, LlmAssessment a, UsageTracker usage,
                                           Map<String, String> skillRefs, List<String> events) {
        ActiveSkill reviewer = skills.required(SkillRegistry.COMPLIANCE);
        skillRefs.put(reviewer.skillId(), reviewer.version());
        try {
            LlmResponse r = call("compliance", props.reviewerModel(), renderer.system(reviewer.content(), Map.of()),
                    renderer.complianceUser(analystUser, mapper.writeValueAsString(a)), complianceSchema, 1000, null,
                    usage);
            JsonNode review = mapper.readTree(r.text());
            if (review.path("agree").asBoolean(false)) {
                events.add("COMPLIANCE_AGREED");
                return a;
            }
            metrics.complianceDisagreement();
            List<String> concerns = new ArrayList<>();
            review.path("concerns").forEach(c -> concerns.add(c.asText()));
            events.add("COMPLIANCE_DISAGREED");
            return escalateFromCompliance(a, "Revisor de compliance discordou: " + String.join("; ", concerns));
        } catch (LlmException | JsonProcessingException e) {
            usage.failed();
            events.add("COMPLIANCE_UNAVAILABLE");
            return escalateFromCompliance(a, "Revisão de compliance indisponível; decisão sensível exige humano.");
        }
    }

    private LlmAssessment escalateFromCompliance(LlmAssessment a, String why) {
        List<LlmAssessment.Reason> reasons = new ArrayList<>(a.reasons());
        List<String> cited = a.reasons().stream().flatMap(r -> r.evidenceIds().stream()).distinct().toList();
        reasons.add(new LlmAssessment.Reason("OTHER", Severity.HIGH, why, cited.isEmpty() ? List.of("EV-REQ") : cited));
        RiskLevel risk = a.riskLevel().atLeast(RiskLevel.HIGH) ? a.riskLevel() : RiskLevel.HIGH;
        return new LlmAssessment(Decision.ESCALATE_TO_HUMAN, risk, Math.max(50, a.riskScore()),
                Math.min(a.confidence(), 0.5), a.summary() + " (Encaminhado para revisão humana pelo compliance.)",
                List.copyOf(reasons), a.missingInformation());
    }

    private LlmResponse call(String purpose, String model, String system, String user, JsonNode schema, int maxTokens,
                             String effort, UsageTracker usage) {
        LlmResponse r = llm.complete(new LlmRequest(purpose, model, system, user, schema, maxTokens, effort));
        usage.add(r);
        return r;
    }

    private PolicyConfig parsePolicy(ActiveSkill skill) {
        try {
            return mapper.readValue(skill.content(), PolicyConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Skill de política inválida: " + skill.ref(), e);
        }
    }

    private JsonNode readSchema(String name) {
        try (var in = new ClassPathResource("schemas/" + name).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
