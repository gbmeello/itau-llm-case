package com.itau.purchaseagent;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.context.ErpGateway;
import com.itau.purchaseagent.context.MockErpGateway;
import com.itau.purchaseagent.llm.AnthropicLlmClient;
import com.itau.purchaseagent.llm.FakeLlmClient;
import com.itau.purchaseagent.llm.LlmClient;
import com.itau.purchaseagent.llm.LlmMetrics;
import com.itau.purchaseagent.llm.ResilientLlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** Relógio injetável: "hoje" fixo deixa evals e testes reprodutíveis (fornecedor novo, fracionamento etc.). */
    @Bean
    Clock clock(@Value("${agent.fixed-date:}") String fixedDate) {
        if (fixedDate != null && !fixedDate.isBlank()) {
            return Clock.fixed(Instant.parse(fixedDate + "T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
        return Clock.system(ZoneId.of("America/Sao_Paulo"));
    }

    @Bean
    ErpGateway erpGateway(ObjectMapper mapper) {
        return new MockErpGateway(mapper);
    }

    @Bean
    LlmMetrics llmMetrics(MeterRegistry registry) {
        return new LlmMetrics(registry);
    }

    /**
     * {@code llm.provider=fake} (default: roda sem chave) ou {@code anthropic} (exige ANTHROPIC_API_KEY).
     * Em ambos os casos o cliente é decorado com retry + circuit breaker + métricas.
     */
    @Bean
    LlmClient llmClient(@Value("${llm.provider:fake}") String provider,
                        @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
                        @Value("${llm.max-attempts:3}") int maxAttempts,
                        @Value("${llm.initial-backoff-ms:500}") long backoffMs,
                        ObjectMapper mapper, LlmMetrics metrics) {
        LlmClient base;
        if ("anthropic".equalsIgnoreCase(provider)) {
            base = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
                    .fromEnv()
                    .maxRetries(0) // retry controlado (e medido) pelo ResilientLlmClient
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build(), mapper);
            log.info("llm_provider=anthropic timeoutSeconds={}", timeoutSeconds);
        } else {
            base = new FakeLlmClient(mapper);
            log.warn("llm_provider=fake: respostas determinísticas simuladas (sem chamada ao Claude)");
        }
        return new ResilientLlmClient(base, maxAttempts, Duration.ofMillis(backoffMs), metrics);
    }
}
