package com.itau.purchaseagent.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entrada do agente (contrato {@code purchase-request.v1.json}).
 * Campos são propositalmente anuláveis: só {@code requestId}, {@code requester.costCenter}
 * e {@code items} são obrigatórios (garantido pelo JSON Schema). O resto é tratado pelo intake.
 */
public record PurchaseRequest(
        String requestId,
        String requestedAt,
        Requester requester,
        Supplier supplier,
        List<Item> items,
        BigDecimal totalAmount,
        String currency,
        String justification,
        String neededBy,
        String urgency) {

    public record Requester(String employeeId, String name, String department, String costCenter) {}

    public record Supplier(String taxId, String name) {}

    public record Item(String sku, String description, String category, Integer quantity, BigDecimal unitPrice) {}
}
