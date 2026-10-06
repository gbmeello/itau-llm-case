package com.itau.purchaseagent.registry;

public class SkillException extends RuntimeException {

    public enum Reason { NOT_FOUND, CONFLICT, INVALID }

    private final Reason reason;

    public SkillException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
