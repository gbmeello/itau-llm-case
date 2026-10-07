package com.itau.purchaseagent.intake;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimização de dados (LGPD): mascara PII em texto livre antes de qualquer envio ao LLM.
 * A decisão de compra não depende de CPF, e-mail, telefone ou cartão de quem escreveu a justificativa.
 * CNPJ não é mascarado: é dado empresarial e relevante para a decisão.
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern CPF = Pattern.compile("(?<![\\d./-])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![\\d/-]|\\.\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?55\\s?)?\\(?\\d{2}\\)?\\s?9?\\d{4}[-\\s]?\\d{4}(?!\\d)");

    public record Result(String text, List<String> kinds) {}

    private PiiMasker() {}

    public static Result mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, List.of());
        }
        TreeSet<String> found = new TreeSet<>();
        String out = replace(EMAIL, "EMAIL", text, found, false);
        out = replace(CARD, "CARTAO", out, found, true);
        out = replace(CPF, "CPF", out, found, false);
        out = replace(PHONE, "TELEFONE", out, found, false);
        return new Result(out, List.copyOf(found));
    }

    private static String replace(Pattern p, String label, String text, TreeSet<String> found, boolean luhn) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            if (luhn && !(digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            found.add(label);
            m.appendReplacement(sb, "[" + label + "]");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int n = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                n = n * 2 > 9 ? n * 2 - 9 : n * 2;
            }
            sum += n;
        }
        return sum % 10 == 0;
    }
}
