package com.itau.purchaseagent.intake;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Solicitação após normalização. É o que o resto do pipeline enxerga.
 * PII (nome do solicitante) não é carregada adiante: só IDs.
 */
public record NormalizedRequest(
        String requestId,
        String employeeId,
        String department,
        String costCenter,
        String supplierTaxId,
        String supplierName,
        List<Item> items,
        BigDecimal totalAmount,
        String currency,
        String justification,
        LocalDate neededBy,
        String urgency,
        List<String> dataQualityIssues,
        List<String> missingInformation,
        boolean suspiciousInput) {

    public record Item(String sku, String description, String category, int quantity, BigDecimal unitPrice) {}

    /** Categoria dominante (por valor), usada para selecionar políticas e histórico relevantes. */
    public String primaryCategory() {
        return items.stream()
                .filter(i -> i.category() != null)
                .max((a, b) -> a.unitPrice().multiply(BigDecimal.valueOf(a.quantity()))
                        .compareTo(b.unitPrice().multiply(BigDecimal.valueOf(b.quantity()))))
                .map(Item::category)
                .orElse("UNCATEGORIZED");
    }

    public double dataQualityScore() {
        return Math.max(0.0, 1.0 - 0.1 * dataQualityIssues.size());
    }
}
