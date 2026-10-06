package com.itau.purchaseagent.observability;

import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.contract.PurchaseDecision;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Métricas de comportamento do agente. Complementam as de chamada LLM ({@code llm.*}) com o que importa
 * para o negócio e para detectar comportamento inesperado do modelo (drift de decisões, grounding, overrides).
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void decision(PurchaseDecision d) {
        registry.counter("agent.decisions", "decision", d.decision().name(), "decidedBy",
                d.audit().decidedBy().name(), "riskLevel", d.riskLevel().name()).increment();
        Timer.builder("agent.decision.latency").tag("decidedBy", d.audit().decidedBy().name())
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(d.audit().latencyMs(), TimeUnit.MILLISECONDS);
        DistributionSummary.builder("agent.decision.cost.usd").register(registry)
                .record(d.audit().estimatedCostUsd());
        if (d.audit().fallbackReason() != null) {
            registry.counter("agent.fallbacks", "reason", d.audit().fallbackReason()).increment();
        }
        d.audit().guardrailEvents().forEach(e -> registry.counter("agent.guardrail.events", "event", e).increment());
    }

    public void context(AgentContext ctx) {
        ctx.tokensByLayer().forEach((layer, tokens) -> DistributionSummary.builder("context.tokens.by_layer")
                .tag("layer", layer.name()).register(registry).record(tokens));
        DistributionSummary.builder("context.tokens.total").register(registry).record(ctx.estimatedTokens());
        if (ctx.truncated()) {
            registry.counter("context.truncations").increment(ctx.excludedIds().size());
        }
    }

    public void validationFailure(String stage, int errors) {
        registry.counter("agent.validation.failures", "stage", stage).increment();
        registry.counter("agent.grounding.errors", "stage", stage).increment(errors);
    }

    public void complianceDisagreement() {
        registry.counter("agent.compliance.disagreements").increment();
    }
}
