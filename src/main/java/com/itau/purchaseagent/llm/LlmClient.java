package com.itau.purchaseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Abstração mínima sobre o provedor de LLM. Permite trocar Anthropic por Fake (testes/evals/demo sem chave)
 * e decorar com resiliência e métricas sem tocar no agente.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /**
     * @param purpose      papel da chamada (analyst, repair, compliance, summarizer). Usado em métricas e no fake.
     * @param systemPrompt parte estável do prompt (candidata a cache)
     * @param userContent  parte variável: dados da solicitação e contexto
     * @param outputSchema JSON Schema imposto via structured outputs (null = texto livre)
     */
    record LlmRequest(String purpose, String model, String systemPrompt, String userContent, JsonNode outputSchema,
                      int maxTokens, String effort) {}

    record LlmResponse(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens,
                       long cacheWriteTokens, long latencyMs, String stopReason) {}
}
