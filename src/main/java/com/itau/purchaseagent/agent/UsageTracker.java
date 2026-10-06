package com.itau.purchaseagent.agent;

import com.itau.purchaseagent.llm.LlmClient.LlmResponse;
import com.itau.purchaseagent.llm.LlmPricing;
import java.util.ArrayList;
import java.util.List;

/** Acumula consumo de todas as chamadas LLM de uma decisão (custo por decisão é a unidade de FinOps). */
final class UsageTracker {

    private final List<LlmResponse> calls = new ArrayList<>();
    private int failedCalls;

    void add(LlmResponse r) {
        calls.add(r);
    }

    void failed() {
        failedCalls++;
    }

    long input() {
        return calls.stream().mapToLong(LlmResponse::inputTokens).sum();
    }

    long output() {
        return calls.stream().mapToLong(LlmResponse::outputTokens).sum();
    }

    long cacheRead() {
        return calls.stream().mapToLong(LlmResponse::cacheReadTokens).sum();
    }

    double costUsd() {
        return calls.stream().mapToDouble(LlmPricing::costUsd).sum();
    }

    int callCount() {
        return calls.size() + failedCalls;
    }

    String primaryModel() {
        return calls.isEmpty() ? null : calls.getFirst().model();
    }
}
