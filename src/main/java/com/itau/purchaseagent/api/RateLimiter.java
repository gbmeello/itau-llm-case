package com.itau.purchaseagent.api;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate limit por cliente (token bucket) nas rotas que consomem LLM: protege custo (FinOps) e a cota do provedor.
 * Em memória, ou seja, por instância; com várias réplicas o estado iria para o Redis ou para o API gateway.
 */
public class RateLimiter {

    private final double ratePerNano;
    private final double capacity;
    private final LongSupplier nanoClock;
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>(); // [tokens, lastNanos]

    public RateLimiter(int perMinute, LongSupplier nanoClock) {
        this.ratePerNano = perMinute / 60_000_000_000.0;
        this.capacity = perMinute;
        this.nanoClock = nanoClock;
    }

    /** Consome 1 token. Vazio = permitido; senão, segundos até haver token (para Retry-After). */
    public OptionalLong acquire(String key) {
        long now = nanoClock.getAsLong();
        double[] b = buckets.computeIfAbsent(key, k -> new double[] {capacity, now});
        synchronized (b) {
            b[0] = Math.min(capacity, b[0] + (now - (long) b[1]) * ratePerNano);
            b[1] = now;
            if (b[0] >= 1) {
                b[0] -= 1;
                return OptionalLong.empty();
            }
            return OptionalLong.of(Math.max(1, (long) Math.ceil((1 - b[0]) / ratePerNano / 1e9)));
        }
    }
}
