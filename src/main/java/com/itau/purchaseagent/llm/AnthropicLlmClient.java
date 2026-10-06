package com.itau.purchaseagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cliente real (SDK oficial). Decisões:
 * <ul>
 *   <li>Contrato de saída via structured outputs ({@code output_config.format}): Sonnet 5.5 não aceita tool_choice forçado.</li>
 *   <li>System prompt com {@code cache_control}: a parte estável (regras + schema + exemplos) é cacheada.</li>
 *   <li>Sem {@code temperature}: Sonnet 5.5 rejeita valores não-default; consistência vem de schema + validador + evals.</li>
 *   <li>Retries ficam no {@link ResilientLlmClient} (SDK configurado com maxRetries=0) para termos métrica e controle.</li>
 * </ul>
 */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final ObjectMapper mapper;

    public AnthropicLlmClient(AnthropicClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(req.model())
                .maxTokens(req.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(req.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(req.userContent());

        OutputConfig.Builder output = OutputConfig.builder();
        boolean hasOutputConfig = false;
        if (req.outputSchema() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = mapper.convertValue(req.outputSchema(), Map.class);
            JsonOutputFormat.Schema.Builder sb = JsonOutputFormat.Schema.builder();
            schema.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));
            output.format(JsonOutputFormat.builder().schema(sb.build()).build());
            hasOutputConfig = true;
        }
        if (req.effort() != null && !req.effort().isBlank()) {
            output.effort(OutputConfig.Effort.of(req.effort().toLowerCase()));
            hasOutputConfig = true;
        }
        if (hasOutputConfig) {
            params.outputConfig(output.build());
        }

        long start = System.nanoTime();
        Message message;
        try {
            message = client.messages().create(params.build());
        } catch (RateLimitException e) {
            throw new LlmException(LlmException.Kind.RATE_LIMITED, e.getMessage(), e);
        } catch (InternalServerException e) {
            throw new LlmException(e.statusCode() == 529 ? LlmException.Kind.OVERLOADED : LlmException.Kind.SERVER_ERROR,
                    e.getMessage(), e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new LlmException(LlmException.Kind.AUTH, e.getMessage(), e);
        } catch (BadRequestException e) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, e.getMessage(), e);
        } catch (AnthropicServiceException e) {
            throw new LlmException(e.statusCode() >= 500 ? LlmException.Kind.SERVER_ERROR : LlmException.Kind.UNKNOWN,
                    e.getMessage(), e);
        } catch (AnthropicIoException e) {
            throw new LlmException(LlmException.Kind.NETWORK, e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        String stopReason = message.stopReason().map(Object::toString).orElse("unknown");
        if ("refusal".equals(stopReason)) {
            throw new LlmException(LlmException.Kind.REFUSAL, "Modelo recusou a solicitação");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        var usage = message.usage();
        return new LlmResponse(text, message.model().asString(), usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L), latencyMs,
                stopReason);
    }
}
