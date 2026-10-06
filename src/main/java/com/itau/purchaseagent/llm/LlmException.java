package com.itau.purchaseagent.llm;

/** Falha ao obter resposta utilizável do LLM. {@code retryable} orienta a política de retry. */
public class LlmException extends RuntimeException {

    public enum Kind { RATE_LIMITED, OVERLOADED, SERVER_ERROR, TIMEOUT, NETWORK, REFUSAL, BAD_REQUEST, AUTH, CIRCUIT_OPEN, UNKNOWN }

    private final Kind kind;

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return switch (kind) {
            case RATE_LIMITED, OVERLOADED, SERVER_ERROR, TIMEOUT, NETWORK -> true;
            default -> false;
        };
    }
}
