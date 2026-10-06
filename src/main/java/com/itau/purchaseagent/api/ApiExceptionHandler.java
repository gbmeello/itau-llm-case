package com.itau.purchaseagent.api;

import com.itau.purchaseagent.registry.SkillException;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ContractViolationException.class)
    ResponseEntity<Map<String, Object>> contract(ContractViolationException e) {
        return body(HttpStatus.BAD_REQUEST, "CONTRACT_VIOLATION", e.getMessage(), e.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Corpo da requisição não é JSON válido", List.of());
    }

    @ExceptionHandler(SkillException.class)
    ResponseEntity<Map<String, Object>> skill(SkillException e) {
        HttpStatus status = switch (e.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return body(status, "SKILL_" + e.reason(), e.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage(), List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), "ERROR", e.getReason(), List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                            List<String> details) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "code", code,
                "message", message == null ? "" : message,
                "details", details,
                "traceId", String.valueOf(MDC.get("traceId"))));
    }
}
