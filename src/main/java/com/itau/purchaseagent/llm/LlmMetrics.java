package com.itau.purchaseagent.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/** Métricas de chamada ao LLM: latência, tokens, custo, erros e retries por papel (purpose) e modelo. */
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void success(String purpose, LlmClient.LlmResponse r) {
        Timer.builder("llm.request.latency")
                .tags("purpose", purpose, "model", r.model(), "outcome", "success")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(r.latencyMs(), TimeUnit.MILLISECONDS);
        registry.counter("llm.tokens", "purpose", purpose, "model", r.model(), "direction", "input")
                .increment(r.inputTokens());
        registry.counter("llm.tokens", "purpose", purpose, "model", r.model(), "direction", "output")
                .increment(r.outputTokens());
        registry.counter("llm.tokens", "purpose", purpose, "model", r.model(), "direction", "cache_read")
                .increment(r.cacheReadTokens());
        registry.counter("llm.cost.usd", "purpose", purpose, "model", r.model()).increment(LlmPricing.costUsd(r));
    }

    void failure(String purpose, String kind, long latencyMs) {
        registry.counter("llm.errors", "purpose", purpose, "type", kind).increment();
        Timer.builder("llm.request.latency")
                .tags("purpose", purpose, "model", "n/a", "outcome", "error")
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    void retry(Throwable cause) {
        String kind = cause instanceof LlmException le ? le.kind().name() : "UNKNOWN";
        registry.counter("llm.retries", "type", kind).increment();
    }
}
