package com.itau.purchaseagent.api;

import java.util.List;

public class ContractViolationException extends RuntimeException {

    private final List<String> errors;

    public ContractViolationException(List<String> errors) {
        super("Entrada viola o contrato purchase-request.v1");
        this.errors = errors;
    }

    public List<String> errors() {
        return errors;
    }
}
