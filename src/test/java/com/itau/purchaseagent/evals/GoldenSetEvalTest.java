package com.itau.purchaseagent.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itau.purchaseagent.api.EvaluationService;
import com.itau.purchaseagent.context.ErpGateway;
import com.itau.purchaseagent.context.MockErpGateway;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import com.itau.purchaseagent.contract.PurchaseDecision;
import com.itau.purchaseagent.contract.SchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runner do golden set. Roda com o LLM configurado em {@code llm.provider}:
 * <ul>
 *   <li>{@code fake} (CI, {@code -Peval-fake}): valida pipeline, guardrails e fallbacks de forma determinística.</li>
 *   <li>{@code anthropic} ({@code -Peval}): mede o comportamento do modelo/prompt reais (custa tokens).</li>
 * </ul>
 * Critérios objetivos (gate de regressão): schema 100%, grounding 100%, acurácia ≥ 90%, zero decisões proibidas,
 * adversariais 100%, e acurácia não pode cair mais de 2pp em relação ao baseline salvo.
 */
@Tag("eval")
@SpringBootTest(properties = {"llm.initial-backoff-ms=20"})
class GoldenSetEvalTest {

    private static final Path CASES = Path.of("evals/golden/cases.json");
    private static final Path REPORTS = Path.of("evals/reports");

    @Autowired EvaluationService service;
    @Autowired SchemaValidator schemas;
    @Autowired ObjectMapper mapper;
    @Autowired ErpGateway erp;

    @Value("${llm.provider}") String provider;

    record TurnResult(String caseId, String category, int turn, String decision, String expected, String decidedBy,
                      boolean schemaValid, boolean grounded, boolean correct, boolean critical, boolean adversarial,
                      double costUsd, long latencyMs, long tokensIn, long tokensOut, List<String> failures) {}

    @Test
    void goldenSet() throws IOException {
        JsonNode cases = mapper.readTree(CASES.toFile());
        String runId = DateTimeFormatter.ofPattern("HHmmss").format(LocalDateTime.now());
        List<TurnResult> results = new ArrayList<>();
        for (JsonNode c : cases) {
            if (c.path("fakeOnly").asBoolean(false) && !"fake".equals(provider)) {
                continue;
            }
            boolean erpDown = c.path("simulate").path("erpUnavailable").asBoolean(false);
            ((MockErpGateway) erp).setUnavailable(erpDown);
            try {
                ObjectNode request = c.get("request").deepCopy();
                request.put("requestId", request.get("requestId").asText() + "-" + runId);
                PurchaseDecision d = service.evaluate(request).decision();
                results.add(check(c, 0, d, c.get("expect")));
                int turn = 1;
                for (JsonNode f : c.path("followUps")) {
                    if (d.caseId() == null) {
                        break;
                    }
                    d = service.continueCase(d.caseId(), f.path("message").asText(null), f.get("updates")).decision();
                    results.add(check(c, turn++, d, f.get("expect")));
                }
            } finally {
                ((MockErpGateway) erp).setUnavailable(false);
            }
        }

        Map<String, Object> summary = summarize(results);
        writeReport(results, summary);

        assertThat((double) summary.get("schemaValidRate")).as("schema válido").isEqualTo(1.0);
        assertThat((double) summary.get("groundingRate")).as("grounding").isEqualTo(1.0);
        assertThat((long) summary.get("criticalErrors")).as("decisões proibidas (ex.: APPROVE indevido)").isZero();
        assertThat((double) summary.get("adversarialPassRate")).as("adversariais").isEqualTo(1.0);
        assertThat((double) summary.get("accuracy")).as("acurácia").isGreaterThanOrEqualTo(0.9);
        Path baseline = Path.of("evals/baseline-" + provider + ".json");
        if (Files.exists(baseline)) {
            double base = mapper.readTree(baseline.toFile()).path("accuracy").asDouble();
            assertThat((double) summary.get("accuracy")).as("regressão vs baseline").isGreaterThanOrEqualTo(base - 0.02);
        }
        if (Boolean.getBoolean("eval.updateBaseline")) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(baseline.toFile(), summary);
        }
    }

    private TurnResult check(JsonNode c, int turn, PurchaseDecision d, JsonNode expect) {
        List<String> failures = new ArrayList<>();
        boolean schemaValid = schemas.validate(SchemaValidator.DECISION_V1, mapper.valueToTree(d)).isEmpty();
        Set<String> evidence = d.evidence().stream().map(PurchaseDecision.EvidenceView::id).collect(Collectors.toSet());
        boolean grounded = !d.reasons().isEmpty() && d.reasons().stream()
                .allMatch(r -> !r.evidenceIds().isEmpty() && evidence.containsAll(r.evidenceIds()));

        List<String> allowed = strings(expect.path("decisionIn"));
        if (!allowed.contains(d.decision().name())) {
            failures.add("decision=" + d.decision() + " esperado " + allowed);
        }
        List<String> decidedBy = strings(expect.path("decidedByIn"));
        if (!decidedBy.isEmpty() && !decidedBy.contains(d.audit().decidedBy().name())) {
            failures.add("decidedBy=" + d.audit().decidedBy() + " esperado " + decidedBy);
        }
        List<String> erpActions = strings(expect.path("erpActionIn"));
        if (!erpActions.isEmpty() && !erpActions.contains(d.erpPayload().action())) {
            failures.add("erpAction=" + d.erpPayload().action() + " esperado " + erpActions);
        }
        Set<String> codes = d.reasons().stream().map(r -> r.code()).collect(Collectors.toSet());
        for (String code : strings(expect.path("reasonCodesInclude"))) {
            if (!codes.contains(code)) {
                failures.add("faltou reason " + code);
            }
        }
        if (expect.path("mustFlagMissing").asBoolean(false) && d.missingInformation().isEmpty()) {
            failures.add("missingInformation vazio");
        }
        for (String issue : strings(expect.path("dataQualityIssuesInclude"))) {
            if (!d.dataQuality().issues().contains(issue)) {
                failures.add("faltou dataQuality " + issue);
            }
        }
        for (String ev : strings(expect.path("guardrailEventsInclude"))) {
            if (!d.audit().guardrailEvents().contains(ev)) {
                failures.add("faltou evento " + ev);
            }
        }
        if (expect.has("minRiskLevel") && !d.riskLevel().atLeast(RiskLevel.valueOf(expect.get("minRiskLevel").asText()))) {
            failures.add("riskLevel=" + d.riskLevel() + " abaixo de " + expect.get("minRiskLevel").asText());
        }
        if (expect.has("maxLlmCalls") && d.audit().llmCalls() > expect.get("maxLlmCalls").asInt()) {
            failures.add("llmCalls=" + d.audit().llmCalls());
        }
        boolean critical = strings(expect.path("forbiddenDecisions")).contains(d.decision().name());
        if (critical) {
            failures.add("DECISÃO PROIBIDA " + d.decision());
        }
        if (!schemaValid) {
            failures.add("schema inválido");
        }
        if (!grounded) {
            failures.add("grounding falhou");
        }
        return new TurnResult(c.get("id").asText(), c.get("category").asText(), turn, d.decision().name(),
                String.join("|", allowed), d.audit().decidedBy().name(), schemaValid, grounded, failures.isEmpty(),
                critical, expect.path("adversarial").asBoolean(false), d.audit().estimatedCostUsd(),
                d.audit().latencyMs(), d.audit().tokens().input(), d.audit().tokens().output(), failures);
    }

    private Map<String, Object> summarize(List<TurnResult> r) {
        Map<String, Object> s = new LinkedHashMap<>();
        double n = r.size();
        List<Long> latencies = r.stream().map(TurnResult::latencyMs).sorted().toList();
        List<TurnResult> adv = r.stream().filter(TurnResult::adversarial).toList();
        s.put("provider", provider);
        s.put("turns", r.size());
        s.put("accuracy", r.stream().filter(TurnResult::correct).count() / n);
        s.put("schemaValidRate", r.stream().filter(TurnResult::schemaValid).count() / n);
        s.put("groundingRate", r.stream().filter(TurnResult::grounded).count() / n);
        s.put("criticalErrors", r.stream().filter(TurnResult::critical).count());
        s.put("adversarialPassRate", adv.isEmpty() ? 1.0 : adv.stream().filter(TurnResult::correct).count() / (double) adv.size());
        s.put("avgCostUsd", r.stream().mapToDouble(TurnResult::costUsd).average().orElse(0));
        s.put("totalCostUsd", r.stream().mapToDouble(TurnResult::costUsd).sum());
        s.put("avgTokensIn", r.stream().mapToLong(TurnResult::tokensIn).average().orElse(0));
        s.put("avgTokensOut", r.stream().mapToLong(TurnResult::tokensOut).average().orElse(0));
        s.put("p95LatencyMs", latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1));
        s.put("decisionDistribution", r.stream().collect(Collectors.groupingBy(TurnResult::decision, java.util.TreeMap::new,
                Collectors.counting())));
        return s;
    }

    private void writeReport(List<TurnResult> results, Map<String, Object> summary) throws IOException {
        Files.createDirectories(REPORTS);
        StringBuilder md = new StringBuilder("# Relatório de eval: golden set\n\n");
        md.append("- Provider: `").append(provider).append("`\n");
        md.append("- Gerado em: ").append(LocalDateTime.now().withNano(0)).append("\n\n## Resumo\n\n| Métrica | Valor |\n|---|---|\n");
        summary.forEach((k, v) -> md.append("| ").append(k).append(" | ")
                .append(v instanceof Double dv ? String.format(java.util.Locale.ROOT, "%.4f", dv) : v).append(" |\n"));
        md.append("\n## Casos\n\n| Caso | Categoria | Turno | Decisão | Esperado | Decidido por | OK | Falhas |\n|---|---|---|---|---|---|---|---|\n");
        results.stream().sorted(Comparator.comparing(TurnResult::caseId).thenComparing(TurnResult::turn))
                .forEach(t -> md.append("| %s | %s | %d | %s | %s | %s | %s | %s |\n".formatted(t.caseId(), t.category(),
                        t.turn(), t.decision(), t.expected(), t.decidedBy(), t.correct() ? "✅" : "❌",
                        String.join("; ", t.failures()))));
        Files.writeString(REPORTS.resolve("latest-" + provider + ".md"), md);
        mapper.writerWithDefaultPrettyPrinter().writeValue(REPORTS.resolve("latest-" + provider + ".json").toFile(),
                Map.of("summary", summary, "results", results));
        System.out.println(md);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
