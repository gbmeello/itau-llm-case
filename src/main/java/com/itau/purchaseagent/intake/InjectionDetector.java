package com.itau.purchaseagent.intake;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Heurística barata para sinalizar texto livre que tenta instruir o modelo.
 * Não é a defesa principal (essa é estrutural: dados delimitados, regras fora do LLM, validação de saída);
 * serve para forçar revisão humana e gerar métrica.
 */
@Component
public class InjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignor\\w*\\s+(as\\s+|todas\\s+as\\s+|all\\s+|the\\s+|previous\\s+|anteriores\\s+)?(instruc|instruct|regra|rule|polit|polic)"),
            Pattern.compile("(disregard|desconsider\\w*)\\s+.{0,30}(instruc|instruct|regra|rule|polit|polic)"),
            Pattern.compile("(system\\s*prompt|prompt\\s+do\\s+sistema)"),
            Pattern.compile("(voce|you)\\s+(agora\\s+e|are\\s+now)"),
            Pattern.compile("(aprov\\w*|approve)\\s+(automaticamente|automatically|sem\\s+(analise|restric)|without)"),
            Pattern.compile("\"?decision\"?\\s*[:=]\\s*\"?(approve|aprov)"),
            Pattern.compile("</?\\s*(untrusted_input|system|evidence|instructions?)\\b"),
            Pattern.compile("(responda|retorne|return|respond)\\s+(apenas|somente|only)\\s+.{0,20}(approve|aprov)"));

    public boolean isSuspicious(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        return PATTERNS.stream().anyMatch(p -> p.matcher(normalized).find());
    }
}
