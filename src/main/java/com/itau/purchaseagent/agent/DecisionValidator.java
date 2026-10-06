package com.itau.purchaseagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.context.ContextItem;
import com.itau.purchaseagent.contract.Enums.Decision;
import com.itau.purchaseagent.contract.Enums.RiskLevel;
import com.itau.purchaseagent.contract.Enums.Severity;
import com.itau.purchaseagent.contract.LlmAssessment;
import com.itau.purchaseagent.contract.SchemaValidator;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Estágio 5: validador determinístico da saída do LLM.
 *
 * <p>Dois tipos de problema:
 * <ul>
 *   <li><b>Reparáveis</b> (schema, IDs de evidência inexistentes, valores não encontrados nas evidências,
 *   NEEDS_INFO sem pendências): voltam ao modelo uma vez com a lista de erros.</li>
 *   <li><b>Violação de guardrail</b> (APPROVE contra regra, APPROVE com risco alto/baixa confiança, entrada
 *   suspeita): não se negocia com o modelo; a decisão é sobrescrita para ESCALATE_TO_HUMAN.</li>
 * </ul>
 */
@Component
public class DecisionValidator {

    private static final Pattern MONEY = Pattern.compile("R\\$\\s?([0-9][0-9.]*(?:,[0-9]{1,2})?)");
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9.]*(?:,[0-9]{1,2})?|[0-9]+(?:\\.[0-9]+)?");

    private final ObjectMapper mapper;
    private final SchemaValidator schemas;
    private final double minApproveConfidence;

    public DecisionValidator(ObjectMapper mapper, SchemaValidator schemas, AgentProperties props) {
        this.mapper = mapper;
        this.schemas = schemas;
        this.minApproveConfidence = props.minApproveConfidence();
    }

    public record Parsed(Optional<LlmAssessment> assessment, List<String> repairableErrors) {
        public boolean ok() {
            return assessment.isPresent() && repairableErrors.isEmpty();
        }
    }

    public record GuardrailResult(LlmAssessment assessment, List<String> events, boolean overridden) {}

    /** Parsing + checagens reparáveis. */
    public Parsed parseAndCheck(String rawOutput, AgentContext ctx) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = mapper.readTree(stripFences(rawOutput));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Parsed(Optional.empty(), List.of("Saída não é JSON válido: " + e.getOriginalMessage()));
        }
        if (node == null || !node.isObject()) {
            return new Parsed(Optional.empty(), List.of("Saída deve ser um objeto JSON"));
        }
        List<String> schemaErrors = schemas.validate(SchemaValidator.LLM_ASSESSMENT_V1, node);
        if (!schemaErrors.isEmpty()) {
            return new Parsed(Optional.empty(), schemaErrors.stream().map(e -> "Schema: " + e).toList());
        }
        LlmAssessment a = mapper.convertValue(node, LlmAssessment.class);

        if (a.riskScore() < 0 || a.riskScore() > 100) {
            errors.add("riskScore deve estar entre 0 e 100");
        }
        if (a.confidence() < 0 || a.confidence() > 1) {
            errors.add("confidence deve estar entre 0 e 1");
        }
        if (a.summary() == null || a.summary().isBlank()) {
            errors.add("summary não pode ser vazio");
        }
        if (a.reasons().isEmpty()) {
            errors.add("Inclua ao menos uma razão com evidência");
        }
        Set<String> ids = ctx.evidenceIds();
        for (int i = 0; i < a.reasons().size(); i++) {
            LlmAssessment.Reason r = a.reasons().get(i);
            if (r.evidenceIds().isEmpty()) {
                errors.add("reasons[%d] (%s) não cita evidência".formatted(i, r.code()));
            }
            List<String> unknown = r.evidenceIds().stream().filter(id -> !ids.contains(id)).toList();
            if (!unknown.isEmpty()) {
                errors.add("reasons[%d] cita evidências inexistentes %s; use apenas %s".formatted(i, unknown,
                        ids.stream().sorted().toList()));
            }
        }
        Set<BigDecimal> known = knownNumbers(ctx);
        String narrative = a.summary() + " " + a.reasons().stream().map(LlmAssessment.Reason::explanation)
                .collect(Collectors.joining(" "));
        for (BigDecimal amount : moneyMentions(narrative)) {
            if (known.stream().noneMatch(k -> close(k, amount))) {
                errors.add("Valor R$ %s citado não aparece nas evidências (não calcule nem invente valores)"
                        .formatted(amount.toPlainString()));
            }
        }
        if (a.decision() == Decision.NEEDS_INFO && a.missingInformation().isEmpty()) {
            errors.add("NEEDS_INFO exige missingInformation não vazio");
        }
        return new Parsed(Optional.of(a), List.copyOf(errors));
    }

    /** Guardrails de decisão: o LLM não negocia regras. */
    public GuardrailResult enforce(LlmAssessment a, PolicyOutcome policy, NormalizedRequest req) {
        List<String> events = new ArrayList<>();
        LlmAssessment out = normalizeRiskBand(a, events);
        if (out.decision() == Decision.APPROVE) {
            if (policy.blocksApproval()) {
                events.add("APPROVE_BLOCKED_BY_POLICY");
            }
            boolean highSeverity = out.reasons().stream()
                    .anyMatch(r -> r.severity() == Severity.HIGH || r.severity() == Severity.CRITICAL);
            if (highSeverity || out.riskLevel().atLeast(RiskLevel.HIGH)) {
                events.add("APPROVE_WITH_HIGH_RISK");
            }
            if (out.confidence() < minApproveConfidence) {
                events.add("APPROVE_LOW_CONFIDENCE");
            }
        }
        if (req.suspiciousInput() && (out.decision() == Decision.APPROVE || out.decision() == Decision.NEEDS_INFO)) {
            events.add("SUSPICIOUS_INPUT_NOT_ESCALATED");
        }
        boolean override = events.stream().anyMatch(e -> !e.startsWith("RISK_SCORE_"));
        if (override) {
            out = escalate(out, events);
        }
        return new GuardrailResult(out, List.copyOf(events), override);
    }

    private LlmAssessment escalate(LlmAssessment a, List<String> events) {
        List<LlmAssessment.Reason> reasons = new ArrayList<>(a.reasons());
        List<String> polIds = a.reasons().stream().flatMap(r -> r.evidenceIds().stream()).distinct().toList();
        reasons.add(new LlmAssessment.Reason("OTHER", Severity.HIGH,
                "Recomendação do modelo (%s) sobrescrita por guardrail: %s".formatted(a.decision(), events),
                polIds.isEmpty() ? List.of("EV-REQ") : polIds));
        RiskLevel risk = a.riskLevel().atLeast(RiskLevel.HIGH) ? a.riskLevel() : RiskLevel.HIGH;
        return new LlmAssessment(Decision.ESCALATE_TO_HUMAN, risk, Math.max(a.riskScore(), 50),
                Math.min(a.confidence(), 0.5), a.summary() + " (Encaminhado para revisão humana por regra de segurança.)",
                List.copyOf(reasons), a.missingInformation());
    }

    /** Coerência riskScore x riskLevel: ajustamos o score à faixa do nível (o nível é a informação categórica). */
    private LlmAssessment normalizeRiskBand(LlmAssessment a, List<String> events) {
        int[] band = switch (a.riskLevel()) {
            case LOW -> new int[] {0, 24};
            case MEDIUM -> new int[] {25, 49};
            case HIGH -> new int[] {50, 79};
            case CRITICAL -> new int[] {80, 100};
        };
        int score = Math.max(band[0], Math.min(band[1], a.riskScore()));
        if (score != a.riskScore()) {
            events.add("RISK_SCORE_ADJUSTED_TO_BAND");
            return new LlmAssessment(a.decision(), a.riskLevel(), score, a.confidence(), a.summary(), a.reasons(),
                    a.missingInformation());
        }
        return a;
    }

    private Set<BigDecimal> knownNumbers(AgentContext ctx) {
        Set<BigDecimal> out = new java.util.HashSet<>();
        for (ContextItem item : ctx.included()) {
            String text = item.content() + " " + item.data().values();
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                parse(m.group()).ifPresent(out::add);
            }
        }
        return out;
    }

    static List<BigDecimal> moneyMentions(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            parse(m.group(1)).ifPresent(out::add);
        }
        return out;
    }

    /** Aceita "78.000", "78.000,00", "78000.00", "25.100". */
    static Optional<BigDecimal> parse(String raw) {
        String s = raw.replaceAll("[.,]$", "");
        try {
            if (s.contains(",")) {
                return Optional.of(new BigDecimal(s.replace(".", "").replace(",", ".")));
            }
            if (s.matches("\\d{1,3}(\\.\\d{3})+")) {
                return Optional.of(new BigDecimal(s.replace(".", "")));
            }
            return Optional.of(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Tolerância de 1% (arredondamentos como "R$ 25 mil" ≈ 25.100 não passam; "R$ 25.100" passa). */
    private static boolean close(BigDecimal known, BigDecimal mentioned) {
        if (known.signum() == 0) {
            return mentioned.signum() == 0;
        }
        return known.subtract(mentioned).abs().divide(known.abs(), 4, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("0.01")) <= 0;
    }

    private static String stripFences(String s) {
        String t = s == null ? "" : s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
        }
        return t;
    }
}
