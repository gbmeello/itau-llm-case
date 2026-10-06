package com.itau.purchaseagent.llm;

import java.util.Map;

/**
 * Tabela de preço (USD por 1M tokens) para estimativa de custo por decisão (FinOps).
 * Fonte: tabela de modelos da Anthropic consultada em 2026-10-06. Cache read ≈ 10% do input.
 */
public final class LlmPricing {

    record Price(double input, double output, double cacheRead, double cacheWrite) {}

    private static final Map<String, Price> PRICES = Map.of(
            "claude-sonnet-5-5", new Price(2.00, 10.00, 0.20, 2.50),
            "claude-haiku-4-5", new Price(1.00, 5.00, 0.10, 1.25),
            "claude-opus-5-5", new Price(4.00, 20.00, 0.20, 5.00));

    private LlmPricing() {}

    public static double costUsd(LlmClient.LlmResponse r) {
        Price p = PRICES.entrySet().stream()
                .filter(e -> r.model() != null && r.model().contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new Price(0, 0, 0, 0));
        return (r.inputTokens() * p.input() + r.outputTokens() * p.output()
                + r.cacheReadTokens() * p.cacheRead() + r.cacheWriteTokens() * p.cacheWrite()) / 1_000_000.0;
    }
}
