package com.itau.purchaseagent.context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Porta para os sistemas externos (ERP, cadastro de fornecedores). Implementações: in-process (mock)
 * ou via MCP. Todas as operações são somente leitura: o agente nunca escreve em sistema externo.
 */
public interface ErpGateway {

    Optional<CostCenterBudget> costCenterBudget(String costCenterId);

    Optional<SupplierProfile> supplierProfile(String taxId);

    List<PurchaseRecord> purchaseHistory(String costCenterId, LocalDate since);

    String sourceName();

    record CostCenterBudget(String id, String name, int fiscalYear, BigDecimal annualBudget, BigDecimal committed) {
        public BigDecimal available() {
            return annualBudget.subtract(committed);
        }
    }

    record SupplierProfile(String taxId, String name, String status, String riskRating, LocalDate registeredSince,
                           List<String> categories, String notes) {
        public boolean blocked() {
            return "BLOCKED".equals(status);
        }
    }

    record PurchaseRecord(String requestId, LocalDate date, String costCenter, String category, String supplierTaxId,
                          BigDecimal amount, String status) {}
}
