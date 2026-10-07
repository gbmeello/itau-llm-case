package com.itau.purchaseagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Cliente Anthropic real contra um servidor HTTP local (JDK): valida o que vai para a API e o mapeamento de
 * respostas e erros, sem chave e sem rede externa.
 */
class AnthropicLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();

    private AnthropicLlmClient clientReturning(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            lastBody.set(MAPPER.readTree(ex.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return new AnthropicLlmClient(AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .timeout(Duration.ofSeconds(5))
                .build(), MAPPER);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String message(String content, String stopReason) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":%s,
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":900,"cache_creation_input_tokens":0}}
                """.formatted(content, stopReason);
    }

    private static LlmClient.LlmRequest request() throws IOException {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"ok\"],"
                + "\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
        return new LlmClient.LlmRequest("analyst", "claude-sonnet-5-5", "SYSTEM ESTÁVEL", "<purchase_request>...",
                schema, 4000, "medium");
    }

    @Test
    void sendsStructuredOutputCacheControlAndNoSamplingParams() throws IOException {
        var client = clientReturning(200, message("[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]", "end_turn"));

        var r = client.complete(request());

        JsonNode body = lastBody.get();
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.at("/system/0/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema/required/0").asText()).isEqualTo("ok");
        assertThat(body.at("/output_config/effort").asText()).isEqualTo("medium");
        assertThat(body.has("temperature") || body.has("tool_choice")).isFalse();
        assertThat(r.text()).isEqualTo("{\"ok\":true}");
        assertThat(r.inputTokens()).isEqualTo(1200);
        assertThat(r.cacheReadTokens()).isEqualTo(900);
    }

    @ParameterizedTest
    @CsvSource({"429,RATE_LIMITED,true", "529,OVERLOADED,true", "500,SERVER_ERROR,true", "400,BAD_REQUEST,false",
            "401,AUTH,false"})
    void mapsHttpErrors(int status, LlmException.Kind kind, boolean retryable) throws IOException {
        var client = clientReturning(status, "{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"simulado\"}}");

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(kind);
                    assertThat(e.retryable()).isEqualTo(retryable);
                });
    }

    @Test
    void refusalIsAnErrorNotADecision() throws IOException {
        var client = clientReturning(200, message("[]", "refusal"));

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.REFUSAL));
    }
}
