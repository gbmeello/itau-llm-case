package com.itau.purchaseagent.context;

/**
 * Estimativa local de tokens (sem chamada de rede) para o controle de budget em runtime.
 * ~3,5 caracteres/token é conservador para português + JSON; o valor real vem de {@code usage} na resposta
 * e é exportado em métrica para calibrar esta razão.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {}

    public static int estimate(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
