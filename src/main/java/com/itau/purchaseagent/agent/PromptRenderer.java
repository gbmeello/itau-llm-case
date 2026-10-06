package com.itau.purchaseagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.purchaseagent.context.AgentContext;
import com.itau.purchaseagent.context.ContextItem;
import com.itau.purchaseagent.context.PromptSafety;
import com.itau.purchaseagent.intake.NormalizedRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Monta os prompts a partir das skills e do contexto selecionado.
 * <ul>
 *   <li>System = skill (estável, cacheável). Nada variável por requisição entra aqui.</li>
 *   <li>User = dados em blocos delimitados por tags XML; texto livre do solicitante isolado em {@code <untrusted_input>}.</li>
 * </ul>
 */
@Component
public class PromptRenderer {

    private final ObjectMapper mapper;

    public PromptRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Remove o frontmatter YAML do arquivo da skill e substitui placeholders {{chave}}. */
    public String system(String skillContent, Map<String, String> vars) {
        String body = stripFrontmatter(skillContent);
        for (var e : vars.entrySet()) {
            body = body.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return body.replaceAll("\\{\\{[a-z_]+}}", "").strip();
    }

    public static String stripFrontmatter(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                return content.substring(content.indexOf('\n', end + 1) + 1);
            }
        }
        return content;
    }

    public String analystUser(NormalizedRequest req, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        ContextItem reqItem = ctx.included().stream().filter(i -> i.id().equals("EV-REQ")).findFirst().orElseThrow();
        sb.append("<purchase_request>\n").append(line(reqItem)).append("\n</purchase_request>\n\n");
        if (req.justification() != null) {
            sb.append("<untrusted_input source=\"requester.justification\" evidence_id=\"EV-JUST\">\n")
                    .append(neutralize(req.justification()))
                    .append("\n</untrusted_input>\n\n");
        }
        sb.append("<evidence>\n");
        sb.append(ctx.included().stream()
                .filter(i -> !i.id().equals("EV-REQ") && i.layer() != ContextItem.Layer.P3_CASE)
                .map(this::line)
                .collect(Collectors.joining("\n")));
        sb.append("\n</evidence>\n");
        ctx.included().stream().filter(i -> i.layer() == ContextItem.Layer.P3_CASE).findFirst()
                .ifPresent(c -> sb.append("\n<case_state evidence_id=\"EV-CASE\">\n")
                        .append(neutralize(c.content())).append("\n</case_state>\n"));
        sb.append("\n<task>Avalie a solicitação seguindo as regras do sistema e responda no schema exigido.</task>");
        return sb.toString();
    }

    public String repairUser(String analystUser, String previousOutput, java.util.List<String> errors) {
        return analystUser
                + "\n\n<previous_output>\n" + previousOutput + "\n</previous_output>\n"
                + "\n<validation_errors>\n- " + String.join("\n- ", errors) + "\n</validation_errors>";
    }

    public String complianceUser(String analystUser, String assessmentJson) {
        return analystUser + "\n\n<analyst_assessment>\n" + assessmentJson + "\n</analyst_assessment>";
    }

    String line(ContextItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.id());
        m.put("source", item.source());
        m.put("content", item.content());
        if (!item.data().isEmpty()) {
            m.put("data", item.data());
        }
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Impede que o texto do usuário feche a tag de dados não confiáveis e "saia" do bloco. */
    public static String neutralize(String untrusted) {
        return PromptSafety.neutralize(untrusted);
    }
}
