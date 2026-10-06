package com.itau.purchaseagent.llm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Decorator de resiliência: retry com backoff exponencial + jitter só para erros transitórios,
 * e circuit breaker para não martelar o provedor fora do ar (falha rápido → fallback do agente).
 */
public class ResilientLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final LlmMetrics metrics;

    public ResilientLlmClient(LlmClient delegate, int maxAttempts, Duration initialBackoff, LlmMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.retry = Retry.of("llm", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(initialBackoff, 2.0, 0.5))
                .retryOnException(e -> e instanceof LlmException le && le.retryable())
                .build());
        this.circuitBreaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(e -> e instanceof LlmException le && le.retryable())
                .build());
        retry.getEventPublisher().onRetry(e -> metrics.retry(e.getLastThrowable()));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Supplier<LlmResponse> call = () -> {
            long start = System.nanoTime();
            try {
                LlmResponse r = delegate.complete(request);
                metrics.success(request.purpose(), r);
                return r;
            } catch (LlmException e) {
                metrics.failure(request.purpose(), e.kind().name(), (System.nanoTime() - start) / 1_000_000);
                throw e;
            }
        };
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
        } catch (CallNotPermittedException e) {
            metrics.failure(request.purpose(), LlmException.Kind.CIRCUIT_OPEN.name(), 0);
            throw new LlmException(LlmException.Kind.CIRCUIT_OPEN, "Circuit breaker aberto para o LLM", e);
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
