package com.itau.purchaseagent.policy;

import java.math.BigDecimal;
import java.util.List;

/** Parâmetros de política (skill do tipo POLICY, versionada no registry). */
public record PolicyConfig(
        String version,
        BigDecimal autoApprovalLimit,
        BigDecimal maxAgentAmount,
        List<ApprovalLevel> approvalLevels,
        double budgetWarnRatio,
        int newSupplierDays,
        int splitWindowDays,
        BigDecimal quotesThreshold,
        List<RestrictedCategory> restrictedCategories,
        List<PolicyText> policyTexts) {

    public record ApprovalLevel(int level, BigDecimal maxAmount, String approver) {}

    public record RestrictedCategory(String category, BigDecimal maxAmount, String reason) {}

    public record PolicyText(String id, List<String> appliesTo, BigDecimal minAmount, String text) {
        public boolean appliesTo(String category, BigDecimal amount) {
            boolean categoryMatch = appliesTo.contains("*") || appliesTo.contains(category);
            return categoryMatch && amount.compareTo(minAmount) >= 0;
        }
    }

    public ApprovalLevel levelFor(BigDecimal amount) {
        return approvalLevels.stream()
                .filter(l -> l.maxAmount() == null || amount.compareTo(l.maxAmount()) <= 0)
                .findFirst()
                .orElse(approvalLevels.getLast());
    }
}
