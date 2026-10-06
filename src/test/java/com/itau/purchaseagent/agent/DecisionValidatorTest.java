package com.itau.purchaseagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.TestData;
import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.context.ContextItem;
import com.itau.purchaseagent.context.ContextItem.Layer;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.LlmAssessment;
import com.itau.purchaseagent.contract.SchemaValidator;
import com.itau.purchaseagent.intake.InjectionDetector;
import com.itau.purchaseagent.intake.IntakeService;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyOutcome;
import com.itau.purchaseagent.policy.PolicyOutcome.PolicyCheck;
import com.itau.purchaseagent.policy.PolicyOutcome.Result;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DecisionValidator validator = new DecisionValidator(mapper, new SchemaValidator(),
            new AgentProperties(null, null, null, 0, 0.7, 1, 3));

    private final AgentContext ctx = new AgentContext(List.of(
            new ContextItem("EV-REQ", Layer.P0_REQUEST, "req", "Solicitação: total R$ 78.000,00",
                    Map.of("totalAmount", new BigDecimal("78000.00"))),
            new ContextItem("EV-HIST-STATS", Layer.P3_HISTORY, "hist", "média R$ 25.100,00", Map.of())),
            List.of(), Map.of(), 100, 8000);

    private static String json(String decision, String explanation, String evidence) {
        return """
                {"decision":"%s","riskLevel":"HIGH","riskScore":70,"confidence":0.8,"summary":"Resumo.",
                 "reasons":[{"code":"AMOUNT_ABOVE_HISTORICAL","severity":"HIGH","explanation":"%s","evidenceIds":["%s"]}],
                 "missingInformation":[]}""".formatted(decision, explanation, evidence);
    }

    @Test
    void acceptsGroundedOutput() {
        var p = validator.parseAndCheck(json("ESCALATE_TO_HUMAN", "R$ 78.000 contra média de R$ 25.100", "EV-HIST-STATS"), ctx);

        assertThat(p.ok()).isTrue();
    }

    @Test
    void rejectsNonJson() {
        var p = validator.parseAndCheck("Claro! A compra parece ok.", ctx);

        assertThat(p.ok()).isFalse();
        assertThat(p.repairableErrors().getFirst()).contains("JSON");
    }

    @Test
    void rejectsUnknownEvidenceIds() {
        var p = validator.parseAndCheck(json("ESCALATE_TO_HUMAN", "acima", "EV-999"), ctx);

        assertThat(p.repairableErrors()).anyMatch(e -> e.contains("EV-999"));
    }

    @Test
    void rejectsMonetaryValuesNotPresentInEvidence() {
        var p = validator.parseAndCheck(json("ESCALATE_TO_HUMAN", "Preço de mercado é R$ 31.000", "EV-HIST-STATS"), ctx);

        assertThat(p.repairableErrors()).anyMatch(e -> e.contains("31000"));
    }

    @Test
    void rejectsSchemaViolations() {
        var p = validator.parseAndCheck("{\"decision\":\"MAYBE\"}", ctx);

        assertThat(p.ok()).isFalse();
        assertThat(p.repairableErrors()).allMatch(e -> e.startsWith("Schema"));
    }

    @Test
    void approveAgainstBlockingPolicyIsOverriddenToEscalate() {
        NormalizedRequest req = new IntakeService(new InjectionDetector(), TestData.CLOCK).normalize(TestData.acme(1, "100"));
        PolicyOutcome blocking = new PolicyOutcome("1.0.0",
                List.of(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "SUPPLIER_HIGH_RISK", "x")), 0, "a",
                List.of());
        LlmAssessment approve = new LlmAssessment(Decision.APPROVE, com.itau.purchaseagent.contract.Enums.RiskLevel.LOW,
                10, 0.9, "ok", List.of(new LlmAssessment.Reason("WITHIN_POLICY",
                com.itau.purchaseagent.contract.Enums.Severity.LOW, "ok", List.of("EV-REQ"))), List.of());

        var result = validator.enforce(approve, blocking, req);

        assertThat(result.overridden()).isTrue();
        assertThat(result.assessment().decision()).isEqualTo(Decision.ESCALATE_TO_HUMAN);
        assertThat(result.events()).contains("APPROVE_BLOCKED_BY_POLICY");
    }

    @Test
    void lowConfidenceApproveIsOverridden() {
        NormalizedRequest req = new IntakeService(new InjectionDetector(), TestData.CLOCK).normalize(TestData.acme(1, "100"));
        PolicyOutcome pass = new PolicyOutcome("1.0.0", List.of(), 0, "a", List.of());
        LlmAssessment approve = new LlmAssessment(Decision.APPROVE, com.itau.purchaseagent.contract.Enums.RiskLevel.LOW,
                10, 0.5, "ok", List.of(), List.of());

        assertThat(validator.enforce(approve, pass, req).events()).contains("APPROVE_LOW_CONFIDENCE");
    }

    @Test
    void parsesBrazilianMoneyFormats() {
        assertThat(DecisionValidator.moneyMentions("R$ 78.000,00 e R$ 2.100 e R$ 950"))
                .extracting(BigDecimal::doubleValue).containsExactly(78000.0, 2100.0, 950.0);
    }
}
