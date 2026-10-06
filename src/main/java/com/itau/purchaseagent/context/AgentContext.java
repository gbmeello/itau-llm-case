package com.itau.purchaseagent.context;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Contexto final selecionado para o modelo, com a contabilidade do que entrou e do que foi cortado (auditoria). */
public record AgentContext(
        List<ContextItem> included,
        List<String> excludedIds,
        Map<ContextItem.Layer, Integer> tokensByLayer,
        int estimatedTokens,
        int budgetTokens) {

    public Set<String> evidenceIds() {
        return included.stream().map(ContextItem::id).collect(Collectors.toUnmodifiableSet());
    }

    public List<ContextItem> layer(ContextItem.Layer layer) {
        return included.stream().filter(i -> i.layer() == layer).toList();
    }

    public boolean truncated() {
        return !excludedIds.isEmpty();
    }
}
