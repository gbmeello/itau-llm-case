package com.itau.purchaseagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do agente ({@code agent.*}).
 *
 * @param analystModel         modelo do analista (executor)
 * @param reviewerModel        modelo barato para compliance e sumarização
 * @param analystEffort        effort do analista (null = default do modelo)
 * @param analystMaxTokens     teto de saída do analista
 * @param minApproveConfidence abaixo disso, APPROVE vira ESCALATE
 * @param maxRepairAttempts    tentativas de reparo após validação falhar
 * @param maxCaseRounds        rodadas de NEEDS_INFO antes de escalar
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(String analystModel, String reviewerModel, String analystEffort, int analystMaxTokens,
                              double minApproveConfidence, int maxRepairAttempts, int maxCaseRounds) {

    public AgentProperties {
        analystModel = analystModel == null ? "claude-sonnet-5-5" : analystModel;
        reviewerModel = reviewerModel == null ? "claude-haiku-4-5" : reviewerModel;
        analystMaxTokens = analystMaxTokens <= 0 ? 4000 : analystMaxTokens;
        minApproveConfidence = minApproveConfidence <= 0 ? 0.7 : minApproveConfidence;
        maxRepairAttempts = maxRepairAttempts < 0 ? 1 : maxRepairAttempts;
        maxCaseRounds = maxCaseRounds <= 0 ? 3 : maxCaseRounds;
    }
}
