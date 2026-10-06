package com.itau.purchaseagent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Borda da API: traceId (propagado em logs/respostas), autenticação por API key (simplificação documentada;
 * produção usaria OAuth2/mTLS) e limite de tamanho de payload.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuardFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final byte[] apiKey;

    public RequestGuardFilter(@Value("${security.api-key}") String apiKey) {
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
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
            if (req.getRequestURI().startsWith("/v1/")) {
                String key = req.getHeader("X-API-Key");
                if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
                    reject(res, 401, "UNAUTHORIZED", "X-API-Key ausente ou inválida", traceId);
                    return;
                }
                if (req.getContentLengthLong() > MAX_BODY_BYTES) {
                    reject(res, 413, "PAYLOAD_TOO_LARGE", "Payload acima de 64KB", traceId);
                    return;
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
        res.getWriter().write("{\"status\":%d,\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}"
                .formatted(status, code, msg, traceId));
    }
}
