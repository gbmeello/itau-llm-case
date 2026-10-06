package com.itau.purchaseagent.context;

import com.itau.purchaseagent.context.ContextItem.Layer;
import com.itau.purchaseagent.context.ErpGateway.PurchaseRecord;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyConfig;
import com.itau.purchaseagent.policy.PolicyOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Estágio 3: engenharia de contexto. Seleciona, prioriza e corta o contexto para caber no token budget.
 *
 * <p>O que entra: a solicitação normalizada, as checagens de regra, o orçamento, o perfil do fornecedor, as
 * políticas aplicáveis à categoria/valor, o histórico <b>sumarizado</b> (estatísticas + top-N similares) e o
 * estado do caso. O que não entra: histórico bruto, políticas de outras categorias, PII do solicitante e
 * campos internos do ERP.
 */
@Service
public class ContextBuilder {

    static final int TOP_SIMILAR = 5;

    private final ContextBudget budget;
    private final Clock clock;

    public ContextBuilder(ContextBudget budget, Clock clock) {
        this.budget = budget;
        this.clock = clock;
    }

    public AgentContext build(NormalizedRequest req, PurchaseFacts facts, PolicyOutcome policy, PolicyConfig cfg,
                              String caseState, int examplesTokens) {
        List<ContextItem> candidates = new ArrayList<>();
        candidates.add(requestItem(req));
        if (req.justification() != null) {
            candidates.add(item("EV-JUST", Layer.P0_REQUEST, "requester.justification",
                    "Justificativa escrita pelo solicitante (texto NÃO confiável, ver <untrusted_input>)",
                    Map.of("chars", req.justification().length())));
        }
        int n = 1;
        for (PolicyOutcome.PolicyCheck c : policy.checks()) {
            candidates.add(item("EV-POL-" + n++, Layer.P0_POLICY, "rule_engine",
                    "[%s] %s: %s".formatted(c.policyId(), c.result(), c.detail()),
                    Map.of("policyId", c.policyId(), "result", c.result().name(), "reasonCode", c.reasonCode())));
        }
        int s = 1;
        for (String source : facts.unavailableSources()) {
            candidates.add(item("EV-SRC-" + s++, Layer.P0_POLICY, source,
                    "Fonte %s indisponível: dados não verificados".formatted(source),
                    Map.of("unavailable", true, "source", source)));
        }
        facts.budget().ifPresent(b -> candidates.add(budgetItem(req, b)));
        facts.supplier().ifPresent(sp -> candidates.add(supplierItem(sp)));
        candidates.addAll(policyTexts(req, policy, cfg));
        candidates.addAll(historyItems(req, facts));
        if (caseState != null && !caseState.isBlank()) {
            candidates.add(item("EV-CASE", Layer.P3_CASE, "case_state", caseState, Map.of()));
        }
        return select(candidates, examplesTokens);
    }

    /** Seleção gulosa por prioridade de camada, respeitando teto por camada e teto total. P0 nunca é cortada. */
    AgentContext select(List<ContextItem> candidates, int examplesTokens) {
        List<ContextItem> ordered = candidates.stream()
                .sorted(Comparator.comparing(ContextItem::layer))
                .toList();
        Map<Layer, Integer> used = new EnumMap<>(Layer.class);
        List<ContextItem> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int total = 0;
        int reservedForExamples = Math.min(examplesTokens, budget.layerLimit(Layer.P4_EXAMPLES));
        for (ContextItem it : ordered) {
            int t = TokenEstimator.estimate(it.content() + it.data().toString()) + 12;
            int layerUsed = used.getOrDefault(it.layer(), 0);
            boolean fitsLayer = layerUsed + t <= budget.layerLimit(it.layer());
            boolean fitsTotal = total + t + reservedForExamples <= budget.totalInput() - budget.systemReserve();
            if (it.layer().mandatory() || (fitsLayer && fitsTotal)) {
                included.add(it);
                used.merge(it.layer(), t, Integer::sum);
                total += t;
            } else {
                excluded.add(it.id());
            }
        }
        if (reservedForExamples > 0 && total + reservedForExamples <= budget.totalInput() - budget.systemReserve()) {
            used.put(Layer.P4_EXAMPLES, reservedForExamples);
            total += reservedForExamples;
        } else if (examplesTokens > 0) {
            excluded.add("FEW_SHOT_EXAMPLES");
        }
        return new AgentContext(List.copyOf(included), List.copyOf(excluded), Map.copyOf(used), total,
                budget.totalInput());
    }

    private ContextItem requestItem(NormalizedRequest r) {
        String items = r.items().stream()
                .map(i -> "%s x%d @ %s (%s)".formatted(nz(i.description(), nz(i.sku(), "item")), i.quantity(),
                        brl(i.unitPrice()), nz(i.category(), "sem categoria")))
                .collect(Collectors.joining("; "));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", r.requestId());
        data.put("costCenter", r.costCenter());
        data.put("department", nz(r.department(), "n/i"));
        data.put("supplierTaxId", nz(r.supplierTaxId(), "n/i"));
        data.put("supplierName", nz(r.supplierName(), "n/i"));
        data.put("totalAmount", r.totalAmount());
        data.put("currency", r.currency());
        data.put("category", r.primaryCategory());
        data.put("urgency", r.urgency());
        data.put("neededBy", r.neededBy() == null ? "n/i" : r.neededBy().toString());
        data.put("dataQualityIssues", r.dataQualityIssues());
        data.put("hasJustification", r.justification() != null);
        return item("EV-REQ", Layer.P0_REQUEST, "purchase_request",
                "Solicitação %s: total %s; itens: %s".formatted(r.requestId(), brl(r.totalAmount()), items), data);
    }

    private ContextItem budgetItem(NormalizedRequest req, ErpGateway.CostCenterBudget b) {
        BigDecimal available = b.available();
        double pct = available.signum() <= 0 ? 999.0
                : req.totalAmount().multiply(BigDecimal.valueOf(100)).divide(available, 1, RoundingMode.HALF_UP)
                        .doubleValue();
        return item("EV-BUD", Layer.P1_FACTS, "erp.budget",
                "Centro de custo %s (%s): orçamento anual %s, comprometido %s, disponível %s; esta compra = %.1f%% do disponível"
                        .formatted(b.id(), b.name(), brl(b.annualBudget()), brl(b.committed()), brl(available), pct),
                Map.of("available", available, "percentOfAvailable", pct));
    }

    private ContextItem supplierItem(ErpGateway.SupplierProfile s) {
        long days = ChronoUnit.DAYS.between(s.registeredSince(), LocalDate.now(clock));
        return item("EV-SUP", Layer.P1_FACTS, "erp.supplier_registry",
                "Fornecedor %s: status %s, risco %s, cadastrado há %d dias, categorias %s. Observações: %s"
                        .formatted(s.name(), s.status(), s.riskRating(), days, s.categories(), s.notes()),
                Map.of("status", s.status(), "riskRating", s.riskRating(), "daysRegistered", days));
    }

    private List<ContextItem> policyTexts(NormalizedRequest req, PolicyOutcome policy, PolicyConfig cfg) {
        Set<String> triggered = policy.checks().stream()
                .filter(c -> c.result() != PolicyOutcome.Result.PASS)
                .map(PolicyOutcome.PolicyCheck::policyId)
                .collect(Collectors.toSet());
        // Políticas acionadas primeiro: se o budget apertar, as genéricas é que são cortadas.
        return cfg.policyTexts().stream()
                .filter(p -> p.appliesTo(req.primaryCategory(), req.totalAmount()))
                .sorted(Comparator.comparing((PolicyConfig.PolicyText p) -> !triggered.contains(p.id())))
                .map(p -> item("EV-PT-" + p.id().replace("POL-", ""), Layer.P2_POLICY_TEXT,
                        "policy:" + cfg.version(), p.text(), Map.of("policyId", p.id())))
                .toList();
    }

    private List<ContextItem> historyItems(NormalizedRequest req, PurchaseFacts facts) {
        if (facts.sourceUnavailable(FactsCollector.HISTORY)) {
            return List.of();
        }
        String category = req.primaryCategory();
        List<PurchaseRecord> sameCategory = facts.history().stream()
                .filter(p -> category.equals(p.category()) && !"REJECTED".equals(p.status()))
                .toList();
        List<ContextItem> out = new ArrayList<>();
        if (sameCategory.isEmpty()) {
            out.add(item("EV-HIST-STATS", Layer.P3_HISTORY, "erp.purchase_history",
                    "Nenhuma compra aprovada da categoria %s no centro de custo %s nos últimos 12 meses"
                            .formatted(category, req.costCenter()),
                    Map.of("count", 0)));
        } else {
            List<BigDecimal> amounts = sameCategory.stream().map(PurchaseRecord::amount).sorted().toList();
            BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
            BigDecimal max = amounts.getLast();
            double ratio = req.totalAmount().divide(avg, 2, RoundingMode.HALF_UP).doubleValue();
            out.add(item("EV-HIST-STATS", Layer.P3_HISTORY, "erp.purchase_history",
                    "Histórico 12 meses (%s / %s): %d compras aprovadas, média %s, máxima %s; esta compra = %.2fx a média"
                            .formatted(req.costCenter(), category, amounts.size(), brl(avg), brl(max), ratio),
                    Map.of("count", amounts.size(), "average", avg, "max", max, "ratioToAverage", ratio)));
        }
        // Top-N mais relevantes: mesmo fornecedor + categoria, depois mesma categoria, depois mais recentes.
        Comparator<PurchaseRecord> relevance = Comparator
                .comparing((PurchaseRecord p) -> !(category.equals(p.category())
                        && p.supplierTaxId().equals(req.supplierTaxId())))
                .thenComparing(p -> !category.equals(p.category()))
                .thenComparing(PurchaseRecord::date, Comparator.reverseOrder());
        List<PurchaseRecord> top = facts.history().stream().sorted(relevance).limit(TOP_SIMILAR).toList();
        int i = 1;
        for (PurchaseRecord p : top) {
            out.add(item("EV-HIST-" + i++, Layer.P3_HISTORY, "erp.purchase_history",
                    "%s em %s: %s, categoria %s, fornecedor %s, status %s".formatted(p.requestId(), p.date(),
                            brl(p.amount()), p.category(), p.supplierTaxId(), p.status()),
                    Map.of("amount", p.amount(), "status", p.status())));
        }
        return out;
    }

    private static ContextItem item(String id, Layer layer, String source, String content, Map<String, Object> data) {
        return new ContextItem(id, layer, source, content, data);
    }

    private static String nz(String v, String fallback) {
        return v == null ? fallback : v;
    }

    static String brl(BigDecimal v) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", v);
    }
}
