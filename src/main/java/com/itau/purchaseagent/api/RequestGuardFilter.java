package com.itau.purchaseagent.api;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Borda da API: traceId (propagado em logs/respostas), autenticação por API key (simplificação documentada;
 * produção usaria OAuth2/mTLS), limite de tamanho de payload e rate limit nas rotas que consomem LLM.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuardFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final byte[] apiKey;
    private final RateLimiter limiter;
    private final MeterRegistry registry;

    public RequestGuardFilter(@Value("${security.api-key}") String apiKey,
                              @Value("${security.rate-limit-per-minute:60}") int rateLimitPerMinute,
                              MeterRegistry registry) {
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.limiter = rateLimitPerMinute > 0 ? new RateLimiter(rateLimitPerMinute, System::nanoTime) : null;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || !traceId.matches("[A-Za-z0-9-]{8,64}")) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put("traceId", traceId);
        res.setHeader("X-Trace-Id", traceId);
        try {
            String uri = req.getRequestURI();
            if (uri.startsWith("/v1/")) {
                String key = req.getHeader("X-API-Key");
                if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
                    reject(res, 401, "UNAUTHORIZED", "X-API-Key ausente ou inválida", traceId);
                    return;
                }
                if (req.getContentLengthLong() > MAX_BODY_BYTES) {
                    reject(res, 413, "PAYLOAD_TOO_LARGE", "Payload acima de 64KB", traceId);
                    return;
                }
                boolean consumesLlm = "POST".equals(req.getMethod())
                        && (uri.startsWith("/v1/purchase-requests/") || uri.startsWith("/v1/cases/"));
                if (limiter != null && consumesLlm) {
                    OptionalLong wait = limiter.acquire(key);
                    if (wait.isPresent()) {
                        registry.counter("api.rate_limited", "route",
                                uri.startsWith("/v1/cases/") ? "cases" : "evaluate").increment();
                        res.setHeader("Retry-After", String.valueOf(wait.getAsLong()));
                        reject(res, 429, "RATE_LIMITED", "Limite de requisições excedido; tente novamente mais tarde",
                                traceId);
                        return;
                    }
                }
            }
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }

    private static void reject(HttpServletResponse res, int status, String code, String msg, String traceId)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":%d,\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}"
                .formatted(status, code, msg, traceId));
    }
}
