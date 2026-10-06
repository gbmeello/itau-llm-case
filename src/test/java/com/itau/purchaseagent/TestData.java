package com.itau.purchaseagent;

import com.itau.purchaseagent.contract.PurchaseRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Fixtures compartilhadas pelos testes unitários. "Hoje" = 2026-10-06, coerente com o mock do ERP. */
public final class TestData {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    public static final String ACME = "11222333000181";
    public static final String BLOCKED = "33445566000186";
    public static final String RAPIDEZ = "99887755000117";

    private TestData() {}

    public static PurchaseRequest request(String costCenter, String supplierTaxId, String category, int qty,
                                          String unitPrice, String justification) {
        BigDecimal price = unitPrice == null ? null : new BigDecimal(unitPrice);
        return new PurchaseRequest("PR-T-1", null,
                new PurchaseRequest.Requester("E-1", "Fulano de Tal", "TI", costCenter),
                supplierTaxId == null ? null : new PurchaseRequest.Supplier(supplierTaxId, "Fornecedor"),
                List.of(new PurchaseRequest.Item("SKU-1", "Item de teste", category, qty, price)),
                price == null ? null : price.multiply(BigDecimal.valueOf(qty)), "BRL", justification, "2026-11-01",
                "NORMAL");
    }

    public static PurchaseRequest acme(int qty, String unitPrice) {
        return request("CC-4410", ACME, "IT_HARDWARE", qty, unitPrice,
                "Substituição de equipamentos com garantia vencida, padrão homologado.");
    }
}
