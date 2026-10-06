package com.itau.purchaseagent.intake;

/** Validação de dígitos verificadores de CNPJ (14 dígitos numéricos). */
final class Cnpj {

    private static final int[] W1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] W2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpj() {}

    static boolean isValid(String digits) {
        if (digits == null || !digits.matches("\\d{14}") || digits.chars().distinct().count() == 1) {
            return false;
        }
        return digit(digits, W1) == digits.charAt(12) - '0' && digit(digits, W2) == digits.charAt(13) - '0';
    }

    private static int digit(String d, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (d.charAt(i) - '0') * weights[i];
        }
        int mod = sum % 11;
        return mod < 2 ? 0 : 11 - mod;
    }
}
