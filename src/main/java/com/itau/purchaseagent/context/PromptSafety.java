package com.itau.purchaseagent.context;

import java.util.regex.Pattern;

/** Saneamento de texto não confiável antes de entrar no prompt: impede que ele feche/abra os blocos delimitados. */
public final class PromptSafety {

    private static final Pattern STRUCTURAL_TAGS = Pattern.compile(
            "(?i)</?\\s*(untrusted_input|evidence|purchase_request|case_state|task|system|previous_summary|new_round"
                    + "|previous_output|validation_errors|analyst_assessment)[^>]*>");

    private PromptSafety() {}

    public static String neutralize(String untrusted) {
        return untrusted == null ? null : STRUCTURAL_TAGS.matcher(untrusted).replaceAll("[tag removida]");
    }
}
