package com.itau.purchaseagent.contract;

/** Enums fechados do contrato de saída. Mudanças aqui exigem nova versão de schema. */
public final class Enums {

    private Enums() {}

    public enum Decision { APPROVE, REJECT, ESCALATE_TO_HUMAN, NEEDS_INFO }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean atLeast(RiskLevel other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    /** Quem efetivamente determinou a decisão final. */
    public enum DecidedBy { AGENT, RULE_ENGINE, VALIDATOR_OVERRIDE, COMPLIANCE_OVERRIDE, FALLBACK }
}
