package com.itau.purchaseagent.context;

import com.itau.purchaseagent.context.ContextItem.Layer;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token budget (configurável em {@code agent.context.*}).
 *
 * @param totalInput    teto total de tokens de entrada da chamada do analista
 * @param systemReserve reservado ao system prompt (regras + schema), que é cacheado
 */
@ConfigurationProperties(prefix = "agent.context")
public record ContextBudget(int totalInput, int systemReserve, int request, int policy, int facts, int policyText,
                            int history, int caseState, int examples) {

    public ContextBudget {
        if (totalInput <= 0) {
            totalInput = 8000;
            systemReserve = 1500;
            request = 1000;
            policy = 600;
            facts = 500;
            policyText = 1200;
            history = 800;
            caseState = 300;
            examples = 600;
        }
    }

    public int layerLimit(Layer layer) {
        return switch (layer) {
            case P0_REQUEST -> request;
            case P0_POLICY -> policy;
            case P1_FACTS -> facts;
            case P2_POLICY_TEXT -> policyText;
            case P3_HISTORY -> history;
            case P3_CASE -> caseState;
            case P4_EXAMPLES -> examples;
        };
    }
}
