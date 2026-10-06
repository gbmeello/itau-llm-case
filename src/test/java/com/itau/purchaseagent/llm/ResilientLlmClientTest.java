package com.itau.purchaseagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilientLlmClientTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmClient.LlmRequest req = new LlmClient.LlmRequest("analyst", "m", "s", "u", null, 100, null);
    private final LlmClient.LlmResponse ok = new LlmClient.LlmResponse("{}", "claude-sonnet-5-5", 1000, 100, 0, 0, 10, "end_turn");

    @Test
    void retriesTransientErrorsThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient flaky = r -> {
            if (calls.incrementAndGet() < 3) {
                throw new LlmException(LlmException.Kind.RATE_LIMITED, "429");
            }
            return ok;
        };

        var client = new ResilientLlmClient(flaky, 3, Duration.ofMillis(1), new LlmMetrics(registry));

        assertThat(client.complete(req)).isEqualTo(ok);
        assertThat(calls).hasValue(3);
        assertThat(registry.counter("llm.retries", "type", "RATE_LIMITED").count()).isEqualTo(2);
        assertThat(registry.counter("llm.cost.usd", "purpose", "analyst", "model", "claude-sonnet-5-5").count())
                .isEqualTo((1000 * 2.0 + 100 * 10.0) / 1_000_000);
    }

    @Test
    void doesNotRetryNonTransientErrors() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient bad = r -> {
            calls.incrementAndGet();
            throw new LlmException(LlmException.Kind.BAD_REQUEST, "400");
        };

        var client = new ResilientLlmClient(bad, 3, Duration.ofMillis(1), new LlmMetrics(registry));

        assertThatThrownBy(() -> client.complete(req)).isInstanceOf(LlmException.class);
        assertThat(calls).hasValue(1);
    }
}
