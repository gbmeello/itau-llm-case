package com.itau.purchaseagent.policy;

import com.itau.purchaseagent.context.ErpGateway.PurchaseRecord;
import com.itau.purchaseagent.context.ErpGateway.SupplierProfile;
import com.itau.purchaseagent.context.FactsCollector;
import com.itau.purchaseagent.context.PurchaseFacts;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.policy.PolicyOutcome.PolicyCheck;
import com.itau.purchaseagent.policy.PolicyOutcome.Result;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Estágio 2: regras de negócio determinísticas. Roda antes do LLM e define limites que o LLM não pode
 * ultrapassar ({@link Result#HARD_REJECT}, {@link Result#BLOCKS_APPROVAL}).
 */
@Service
public class PolicyEngine {

    private final Clock clock;

    public PolicyEngine(Clock clock) {
        this.clock = clock;
    }

    public PolicyOutcome evaluate(NormalizedRequest req, PurchaseFacts facts, PolicyConfig cfg) {
        List<PolicyCheck> checks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BigDecimal amount = req.totalAmount();

        PolicyConfig.ApprovalLevel level = cfg.levelFor(amount);
        checks.add(new PolicyCheck("POL-ALCADA-001", level.level() == 0 ? Result.PASS : Result.INFO,
                "APPROVAL_LEVEL_REQUIRED",
                "Valor %s exige alçada %d (%s)".formatted(brl(amount), level.level(), level.approver())));
        if (amount.compareTo(cfg.maxAgentAmount()) > 0) {
            checks.add(new PolicyCheck("POL-ALCADA-001", Result.BLOCKS_APPROVAL, "APPROVAL_LEVEL_REQUIRED",
                    "Acima de %s: decisão exclusiva do comitê executivo".formatted(brl(cfg.maxAgentAmount()))));
        }

        supplierRules(req, facts, cfg, checks);
        budgetRules(req, facts, cfg, checks, missing);
        categoryRules(req, cfg, checks);
        splitPurchaseRule(req, facts, cfg, level, checks);

        if (amount.compareTo(cfg.quotesThreshold()) > 0) {
            checks.add(new PolicyCheck("POL-COT-006", Result.INFO, "POLICY_VIOLATION",
                    "Acima de %s: exige 3 cotações ou justificativa de fornecedor único".formatted(
                            brl(cfg.quotesThreshold()))));
        }
        if (req.dataQualityIssues().stream().anyMatch(i -> i.startsWith("ITEM_PRICE_MISSING"))) {
            checks.add(new PolicyCheck("DATA-QUALITY", Result.BLOCKS_APPROVAL, "MISSING_INFORMATION",
                    "Itens sem preço: valor total não é confiável"));
        }
        if (req.suspiciousInput()) {
            checks.add(new PolicyCheck("SEC-INPUT", Result.BLOCKS_APPROVAL, "SUSPICIOUS_INPUT",
                    "Texto livre contém padrão de instrução ao sistema (possível prompt injection)"));
        }
        return new PolicyOutcome(cfg.version(), List.copyOf(checks), level.level(), level.approver(),
                List.copyOf(missing));
    }

    private void supplierRules(NormalizedRequest req, PurchaseFacts facts, PolicyConfig cfg, List<PolicyCheck> checks) {
        if (req.supplierTaxId() == null) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "MISSING_INFORMATION",
                    "Fornecedor não informado"));
            return;
        }
        if (facts.sourceUnavailable(FactsCollector.SUPPLIER)) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "SUPPLIER_UNKNOWN",
                    "Cadastro de fornecedores indisponível: situação do fornecedor não verificada"));
            return;
        }
        if (facts.supplier().isEmpty()) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "SUPPLIER_UNKNOWN",
                    "Fornecedor não homologado no cadastro"));
            return;
        }
        SupplierProfile s = facts.supplier().get();
        if (s.blocked()) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.HARD_REJECT, "SUPPLIER_BLOCKED",
                    "Fornecedor %s está BLOQUEADO".formatted(s.name())));
            return;
        }
        if (s.riskRating().equals("HIGH") || s.riskRating().equals("CRITICAL")) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "SUPPLIER_HIGH_RISK",
                    "Fornecedor com rating de risco %s".formatted(s.riskRating())));
        }
        long days = ChronoUnit.DAYS.between(s.registeredSince(), LocalDate.now(clock));
        if (days < cfg.newSupplierDays()) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.BLOCKS_APPROVAL, "SUPPLIER_NEW",
                    "Fornecedor cadastrado há %d dias (< %d)".formatted(days, cfg.newSupplierDays())));
        }
        String category = req.primaryCategory();
        if (!category.equals("UNCATEGORIZED") && !s.categories().contains(category)) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.WARN, "POLICY_VIOLATION",
                    "Fornecedor não homologado para a categoria %s".formatted(category)));
        }
        if (checks.stream().noneMatch(c -> c.policyId().equals("POL-FORN-002"))) {
            checks.add(new PolicyCheck("POL-FORN-002", Result.PASS, "WITHIN_POLICY",
                    "Fornecedor ativo, risco %s".formatted(s.riskRating())));
        }
    }

    private void budgetRules(NormalizedRequest req, PurchaseFacts facts, PolicyConfig cfg, List<PolicyCheck> checks,
                             List<String> missing) {
        if (facts.sourceUnavailable(FactsCollector.BUDGET)) {
            checks.add(new PolicyCheck("POL-ORC-003", Result.BLOCKS_APPROVAL, "BUDGET_INSUFFICIENT",
                    "Sistema de orçamento indisponível: saldo não verificado"));
            return;
        }
        if (facts.budget().isEmpty()) {
            checks.add(new PolicyCheck("POL-ORC-003", Result.BLOCKS_APPROVAL, "MISSING_INFORMATION",
                    "Centro de custo %s não encontrado".formatted(req.costCenter())));
            missing.add("Centro de custo válido");
            return;
        }
        BigDecimal available = facts.budget().get().available();
        if (req.totalAmount().compareTo(available) > 0) {
            checks.add(new PolicyCheck("POL-ORC-003", Result.HARD_REJECT, "BUDGET_INSUFFICIENT",
                    "Valor %s excede saldo disponível %s".formatted(brl(req.totalAmount()), brl(available))));
        } else if (req.totalAmount().compareTo(available.multiply(BigDecimal.valueOf(cfg.budgetWarnRatio()))) > 0) {
            checks.add(new PolicyCheck("POL-ORC-003", Result.WARN, "BUDGET_TIGHT",
                    "Valor consome mais de %d%% do saldo disponível %s".formatted(
                            Math.round(cfg.budgetWarnRatio() * 100), brl(available))));
        } else {
            checks.add(new PolicyCheck("POL-ORC-003", Result.PASS, "WITHIN_POLICY",
                    "Saldo disponível %s".formatted(brl(available))));
        }
    }

    private void categoryRules(NormalizedRequest req, PolicyConfig cfg, List<PolicyCheck> checks) {
        for (PolicyConfig.RestrictedCategory rc : cfg.restrictedCategories()) {
            BigDecimal categoryTotal = req.items().stream()
                    .filter(i -> rc.category().equals(i.category()))
                    .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (categoryTotal.compareTo(rc.maxAmount()) > 0) {
                checks.add(new PolicyCheck("POL-CAT-004", Result.BLOCKS_APPROVAL, "POLICY_VIOLATION",
                        "%s (%s em %s)".formatted(rc.reason(), brl(categoryTotal), rc.category())));
            }
        }
    }

    private void splitPurchaseRule(NormalizedRequest req, PurchaseFacts facts, PolicyConfig cfg,
                                   PolicyConfig.ApprovalLevel currentLevel, List<PolicyCheck> checks) {
        if (req.supplierTaxId() == null) {
            return;
        }
        LocalDate since = LocalDate.now(clock).minusDays(cfg.splitWindowDays());
        String category = req.primaryCategory();
        List<PurchaseRecord> related = facts.history().stream()
                .filter(p -> !p.date().isBefore(since))
                .filter(p -> req.supplierTaxId().equals(p.supplierTaxId()))
                .filter(p -> category.equals(p.category()))
                .filter(p -> !"REJECTED".equals(p.status()))
                .toList();
        if (related.isEmpty()) {
            return;
        }
        BigDecimal cumulative = related.stream().map(PurchaseRecord::amount)
                .reduce(req.totalAmount(), BigDecimal::add);
        PolicyConfig.ApprovalLevel cumulativeLevel = cfg.levelFor(cumulative);
        if (cumulativeLevel.level() > currentLevel.level()) {
            checks.add(new PolicyCheck("POL-FRAC-005", Result.BLOCKS_APPROVAL, "SPLIT_PURCHASE_SUSPECTED",
                    "%d compra(s) do mesmo fornecedor/categoria em %d dias; acumulado %s exige alçada %d".formatted(
                            related.size(), cfg.splitWindowDays(), brl(cumulative), cumulativeLevel.level())));
        }
    }

    static String brl(BigDecimal v) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", v);
    }
}
