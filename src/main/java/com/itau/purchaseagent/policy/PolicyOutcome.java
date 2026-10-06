package com.itau.purchaseagent.policy;

import java.util.List;

/** Resultado do motor de regras. É a "verdade" determinística que o LLM não pode contrariar. */
public record PolicyOutcome(String policyVersion, List<PolicyCheck> checks, int approvalLevel, String approver,
                            List<String> missingInformation) {

    public enum Result {
        PASS,
        INFO,
        WARN,
        /** O agente não pode aprovar: pode pedir informação, escalar ou rejeitar. */
        BLOCKS_APPROVAL,
        /** Rejeição obrigatória, decidida sem LLM. */
        HARD_REJECT
    }

    public record PolicyCheck(String policyId, Result result, String reasonCode, String detail) {}

    public boolean hardReject() {
        return checks.stream().anyMatch(c -> c.result() == Result.HARD_REJECT);
    }

    public boolean blocksApproval() {
        return hardReject() || checks.stream().anyMatch(c -> c.result() == Result.BLOCKS_APPROVAL);
    }

    public List<PolicyCheck> withResult(Result result) {
        return checks.stream().filter(c -> c.result() == result).toList();
    }
}
