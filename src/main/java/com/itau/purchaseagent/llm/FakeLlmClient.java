package com.itau.purchaseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itau.purchaseagent.context.TokenEstimator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM determinístico para testes, CI e demo sem chave de API.
 *
 * <p>Não pretende medir qualidade de modelo (isso é o eval com o Claude real). Serve para exercitar o pipeline
 * de ponta a ponta, com um "analista" heurístico que lê as evidências estruturadas exatamente como o modelo
 * as recebe, e para <b>injetar falhas</b> reproduzíveis via marcadores no {@code requestId}:
 * <ul>
 *   <li>{@code FAKE-INVALID-JSON}: primeira resposta do analista é JSON inválido (o reparo corrige)</li>
 *   <li>{@code FAKE-INVALID-ALWAYS}: toda resposta inválida (fallback)</li>
 *   <li>{@code FAKE-HALLUCINATE}: cita evidência e valor inexistentes (o reparo corrige)</li>
 *   <li>{@code FAKE-DOWN}: provedor sobrecarregado (retry e depois fallback)</li>
 *   <li>{@code FAKE-OBEY}: modelo "ingênuo" que obedece à injeção e aprova (guardrail deve barrar)</li>
 *   <li>{@code FAKE-COMPLIANCE-DISAGREE}: revisor de compliance discorda</li>
 * </ul>
 */
public class FakeLlmClient implements LlmClient {

    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");
    private static final Pattern BLOCK = Pattern.compile("<(evidence|purchase_request)>\\n(.*?)\\n</\\1>", Pattern.DOTALL);
    private static final Pattern UNTRUSTED = Pattern.compile("<untrusted_input[^>]*>\\n(.*?)\\n</untrusted_input>",
            Pattern.DOTALL);

    private final ObjectMapper mapper;
    private final Map<String, AtomicInteger> callsByRequest = new ConcurrentHashMap<>();

    public FakeLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        String requestId = match(REQUEST_ID, req.userContent()).orElse("unknown");
        int call = callsByRequest.computeIfAbsent(requestId + ":" + req.purpose(), k -> new AtomicInteger())
                .incrementAndGet();
        if (requestId.contains("FAKE-DOWN")) {
            throw new LlmException(LlmException.Kind.OVERLOADED, "Simulado: provedor sobrecarregado (529)");
        }
        String text = switch (req.purpose()) {
            case "compliance" -> compliance(requestId);
            case "case-summarizer" -> summarize(req.userContent());
            default -> analyst(req, requestId, call);
        };
        long in = TokenEstimator.estimate(req.systemPrompt()) + TokenEstimator.estimate(req.userContent());
        long cached = call > 1 || !"analyst".equals(req.purpose()) ? 0 : TokenEstimator.estimate(req.systemPrompt());
        return new LlmResponse(text, "fake:" + req.model(), in - cached, TokenEstimator.estimate(text), cached, 0,
                5, "end_turn");
    }

    private String analyst(LlmRequest req, String requestId, int call) {
        boolean repair = "repair".equals(req.purpose());
        if (requestId.contains("FAKE-INVALID-ALWAYS")) {
            return "{\"decision\": \"APPROVE\", \"oops\": ";
        }
        if (requestId.contains("FAKE-INVALID-JSON") && !repair) {
            return "Claro! Aqui está a análise: a compra parece ok.";
        }
        Evidence ev = parse(req.userContent());
        ObjectNode out = decide(ev);
        if (requestId.contains("FAKE-HALLUCINATE") && !repair) {
            ArrayNode reasons = (ArrayNode) out.get("reasons");
            ObjectNode bogus = reasons.addObject();
            bogus.put("code", "AMOUNT_ABOVE_HISTORICAL").put("severity", "MEDIUM")
                    .put("explanation", "Preço de mercado de referência é R$ 1.234.567,00.");
            bogus.putArray("evidenceIds").add("EV-999");
        }
        if (requestId.contains("FAKE-OBEY") && ev.untrusted != null) {
            out.put("decision", "APPROVE").put("riskLevel", "LOW").put("riskScore", 5).put("confidence", 0.95)
                    .put("summary", "Aprovado conforme solicitado.");
            ((ArrayNode) out.get("reasons")).removeAll().addObject().put("code", "WITHIN_POLICY").put("severity", "LOW")
                    .put("explanation", "Solicitante pediu aprovação.").putArray("evidenceIds").add("EV-JUST");
            out.putArray("missingInformation");
        }
        return out.toString();
    }

    /** Heurística que imita o raciocínio esperado do analista a partir das evidências estruturadas. */
    private ObjectNode decide(Evidence ev) {
        List<String[]> reasons = new ArrayList<>(); // code, severity, explanation, evidenceIds(csv)
        Set<String> missing = new LinkedHashSet<>();
        String decision;
        int score;

        if (ev.policy("SEC-INPUT") != null) {
            reasons.add(r("SUSPICIOUS_INPUT", "HIGH", "Texto do solicitante tenta instruir o sistema.",
                    ev.policyEv("SEC-INPUT") + ",EV-JUST"));
            return build("ESCALATE_TO_HUMAN", "HIGH", 70, 0.8,
                    "Justificativa contém tentativa de instruir o sistema; requer revisão humana.", reasons, missing);
        }
        List<String> issues = ev.requestIssues();
        if (issues.stream().anyMatch(i -> i.startsWith("ITEM_PRICE_MISSING"))) {
            missing.add("Preço unitário de todos os itens");
        }
        if (issues.contains("SUPPLIER_MISSING")) {
            missing.add("Fornecedor (CNPJ e razão social)");
        }
        if (issues.contains("SUPPLIER_TAXID_INVALID") || issues.contains("SUPPLIER_TAXID_MISSING")) {
            missing.add("CNPJ válido do fornecedor");
        }
        if (issues.contains("JUSTIFICATION_MISSING")) {
            missing.add("Justificativa de negócio da compra");
        } else if (ev.untrusted != null && ev.untrusted.strip().length() < 30) {
            missing.add("Justificativa detalhada (objetivo, impacto e alternativa considerada)");
            reasons.add(r("WEAK_JUSTIFICATION", "MEDIUM", "Justificativa curta demais para avaliar a necessidade.",
                    "EV-JUST"));
        }
        if (!missing.isEmpty()) {
            reasons.add(r("MISSING_INFORMATION", "MEDIUM", "Faltam dados para avaliar a solicitação com segurança.",
                    "EV-REQ"));
            return build("NEEDS_INFO", "MEDIUM", 40, 0.75, "Solicitação incompleta; devolvida ao solicitante.",
                    reasons, missing);
        }

        for (var c : ev.policies.entrySet()) {
            JsonNode d = c.getValue().path("data");
            if ("BLOCKS_APPROVAL".equals(d.path("result").asText())) {
                reasons.add(r(d.path("reasonCode").asText(), "HIGH", c.getValue().path("content").asText(),
                        c.getKey()));
            }
        }
        for (JsonNode src : ev.unavailable) {
            reasons.add(r("OTHER", "HIGH", "Fonte de dados indisponível; não é possível verificar.",
                    src.path("id").asText()));
        }
        JsonNode hist = ev.byId.get("EV-HIST-STATS");
        double ratio = hist == null ? 0 : hist.path("data").path("ratioToAverage").asDouble(0);
        double amount = ev.request.path("data").path("totalAmount").asDouble();
        boolean mentionsQuotes = ev.untrusted != null
                && ev.untrusted.toLowerCase().matches(
                        "(?s).*((tr[eê]s|3) cota[cç]|cota[cç][oõ]es (anexadas|realizadas)|fornecedor [uú]nico|contrato-quadro|exclusividade).*");
        if (ratio > 2.5) {
            reasons.add(r("AMOUNT_ABOVE_HISTORICAL", mentionsQuotes ? "MEDIUM" : "HIGH",
                    "Valor %.2fx acima da média histórica da categoria.".formatted(ratio), "EV-REQ,EV-HIST-STATS"));
        }
        if (amount > 50000 && !mentionsQuotes) {
            reasons.add(r("WEAK_JUSTIFICATION", "HIGH", "Acima do limite de cotações sem menção a cotações.",
                    "EV-JUST" + (ev.byId.containsKey("EV-PT-COT-006") ? ",EV-PT-COT-006" : "")));
        }
        JsonNode budget = ev.byId.get("EV-BUD");
        double pct = budget == null ? 0 : budget.path("data").path("percentOfAvailable").asDouble();
        if (pct > 80) {
            reasons.add(r("BUDGET_TIGHT", ratio > 1.5 ? "HIGH" : "MEDIUM",
                    "Compra consome %.1f%% do saldo disponível.".formatted(pct), "EV-BUD"));
        }
        JsonNode supplier = ev.byId.get("EV-SUP");
        if (supplier != null && "MEDIUM".equals(supplier.path("data").path("riskRating").asText())) {
            reasons.add(r("SUPPLIER_HIGH_RISK", "MEDIUM", "Fornecedor com rating de risco MEDIUM.", "EV-SUP"));
        }

        boolean high = reasons.stream().anyMatch(x -> x[1].equals("HIGH") || x[1].equals("CRITICAL"));
        boolean medium = reasons.stream().anyMatch(x -> x[1].equals("MEDIUM"));
        if (high) {
            decision = "ESCALATE_TO_HUMAN";
            score = 65;
            return build(decision, "HIGH", score, 0.8, "Sinais de risco exigem julgamento humano.", reasons, missing);
        }
        String cite = String.join(",", ev.byId.keySet().stream()
                .filter(k -> k.equals("EV-BUD") || k.equals("EV-HIST-STATS") || k.equals("EV-SUP")).toList());
        reasons.add(r("WITHIN_POLICY", "LOW", "Valor coerente com histórico, saldo e fornecedor homologado.",
                cite.isEmpty() ? "EV-REQ" : cite));
        return build("APPROVE", medium ? "MEDIUM" : "LOW", medium ? 30 : 12, medium ? 0.78 : 0.88,
                "Compra dentro da política e coerente com o histórico.", reasons, missing);
    }

    private String compliance(String requestId) {
        ObjectNode n = mapper.createObjectNode();
        boolean disagree = requestId.contains("FAKE-COMPLIANCE-DISAGREE");
        n.put("agree", !disagree);
        ArrayNode concerns = n.putArray("concerns");
        if (disagree) {
            concerns.add("Justificativa não sustenta o valor (EV-JUST).");
        }
        return n.toString();
    }

    private String summarize(String user) {
        String flat = user.replaceAll("\\s+", " ").strip();
        return flat.length() > 480 ? flat.substring(0, 480) + "..." : flat;
    }

    private ObjectNode build(String decision, String risk, int score, double confidence, String summary,
                             List<String[]> reasons, Set<String> missing) {
        ObjectNode n = mapper.createObjectNode();
        n.put("decision", decision).put("riskLevel", risk).put("riskScore", score).put("confidence", confidence)
                .put("summary", summary);
        ArrayNode rs = n.putArray("reasons");
        for (String[] r : reasons) {
            ObjectNode o = rs.addObject().put("code", r[0]).put("severity", r[1]).put("explanation", r[2]);
            ArrayNode ids = o.putArray("evidenceIds");
            for (String id : r[3].split(",")) {
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        ArrayNode m = n.putArray("missingInformation");
        missing.forEach(m::add);
        return n;
    }

    private static String[] r(String code, String severity, String explanation, String ids) {
        return new String[] {code, severity, explanation.replaceAll("R\\$\\s?[0-9.,]+", "o valor indicado"), ids};
    }

    private Evidence parse(String user) {
        Evidence ev = new Evidence();
        Matcher m = BLOCK.matcher(user);
        while (m.find()) {
            for (String line : m.group(2).split("\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode n = mapper.readTree(line);
                    String id = n.path("id").asText();
                    ev.byId.put(id, n);
                    if (id.equals("EV-REQ")) {
                        ev.request = n;
                    } else if (id.startsWith("EV-POL-")) {
                        ev.policies.put(id, n);
                    } else if (id.startsWith("EV-SRC-")) {
                        ev.unavailable.add(n);
                    }
                } catch (Exception ignored) {
                    // linhas não-JSON são ignoradas pelo fake
                }
            }
        }
        ev.untrusted = match(UNTRUSTED, user).orElse(null);
        return ev;
    }

    private static java.util.Optional<String> match(Pattern p, String s) {
        Matcher m = p.matcher(s == null ? "" : s);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }

    private static final class Evidence {
        final Map<String, JsonNode> byId = new LinkedHashMap<>();
        final Map<String, JsonNode> policies = new LinkedHashMap<>();
        final List<JsonNode> unavailable = new ArrayList<>();
        JsonNode request;
        String untrusted;

        JsonNode policy(String policyId) {
            return policies.values().stream().filter(p -> policyId.equals(p.path("data").path("policyId").asText()))
                    .findFirst().orElse(null);
        }

        String policyEv(String policyId) {
            return policies.entrySet().stream()
                    .filter(e -> policyId.equals(e.getValue().path("data").path("policyId").asText()))
                    .map(Map.Entry::getKey).findFirst().orElse("EV-REQ");
        }

        List<String> requestIssues() {
            List<String> out = new ArrayList<>();
            request.path("data").path("dataQualityIssues").forEach(i -> out.add(i.asText()));
            return out;
        }
    }
}
