package com.itau.purchaseagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.itau.purchaseagent.TestData;
import com.itau.purchaseagent.intake.InjectionDetector;
import com.itau.purchaseagent.intake.IntakeService;
import com.itau.purchaseagent.intake.NormalizedRequest;
import com.itau.purchaseagent.intake.PiiMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"security.api-key=test-key", "security.rate-limit-per-minute=2", "llm.provider=fake"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class PiiAndRateLimitTest {

    @Autowired MockMvc mvc;

    @Test
    void masksCpfEmailPhoneAndCardButKeepsCnpj() {
        var r = PiiMasker.mask("Falar com joao.silva@empresa.com, CPF 123.456.789-09, tel (11) 91234-5678, "
                + "cartão 4111 1111 1111 1111. Fornecedor CNPJ 11.222.333/0001-81, pedido de 10 unidades.");

        assertThat(r.kinds()).containsExactly("CARTAO", "CPF", "EMAIL", "TELEFONE");
        assertThat(r.text()).doesNotContain("joao.silva@empresa.com", "123.456.789-09", "91234-5678",
                "4111 1111 1111 1111");
        assertThat(r.text()).contains("11.222.333/0001-81", "10 unidades");
    }

    @Test
    void piiNeverReachesTheLlmContext() {
        NormalizedRequest n = new IntakeService(new InjectionDetector(), TestData.CLOCK).normalize(
                TestData.request("CC-4410", TestData.ACME, "IT_HARDWARE", 1, "100",
                        "Contato do solicitante: maria@x.com, CPF 52998224725. Compra urgente."));

        assertThat(n.justification()).doesNotContain("maria@x.com", "52998224725");
        assertThat(n.dataQualityIssues()).contains("PII_MASKED:CPF,EMAIL");
    }

    @Test
    void tokenBucketRefillsOverTime() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(60, now::get);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("k")).isEmpty();
        }
        assertThat(limiter.acquire("k")).isPresent();
        assertThat(limiter.acquire("outro")).isEmpty(); // buckets por cliente
        now.addAndGet(1_000_000_000L);
        assertThat(limiter.acquire("k")).isEmpty();
    }

    @Test
    void returns429WithRetryAfterOnLlmRoutesOnly() throws Exception {
        String body = """
                {"requestId":"PR-RL","requester":{"costCenter":"CC-4410"},
                 "supplier":{"taxId":"11222333000181","name":"Acme"},
                 "items":[{"sku":"M","category":"IT_HARDWARE","quantity":1,"unitPrice":100}],
                 "justification":"Mouse para analista novo da equipe."}""";
        List<Integer> codes = new ArrayList<>();
        MvcResult last = null;
        for (int i = 0; i < 3; i++) {
            last = mvc.perform(post("/v1/purchase-requests/evaluate").header("X-API-Key", "test-key")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            codes.add(last.getResponse().getStatus());
        }
        assertThat(codes).containsExactly(200, 200, 429);
        assertThat(last.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(last.getResponse().getContentAsString()).contains("RATE_LIMITED");
        assertThat(mvc.perform(get("/v1/skills").header("X-API-Key", "test-key")).andReturn().getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString())
                .contains("api_rate_limited_total");
    }
}
