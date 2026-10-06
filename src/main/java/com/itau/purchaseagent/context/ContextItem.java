package com.itau.purchaseagent.context;

import java.util.Map;

/**
 * Unidade de contexto enviada ao modelo. Toda informação factual vira um item com {@code id} citável (EV-...),
 * o que permite verificar o grounding das razões do LLM de forma determinística.
 *
 * @param data campos estruturados (também enviados ao modelo): reduzem ambiguidade e facilitam a verificação
 */
public record ContextItem(String id, Layer layer, String source, String content, Map<String, Object> data) {

    /** Camadas em ordem de prioridade. P0 nunca é cortada. */
    public enum Layer { P0_REQUEST, P0_POLICY, P1_FACTS, P2_POLICY_TEXT, P3_HISTORY, P3_CASE, P4_EXAMPLES;

        public boolean mandatory() {
            return this == P0_REQUEST || this == P0_POLICY;
        }
    }
}
