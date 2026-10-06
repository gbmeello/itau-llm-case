package com.itau.purchaseagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"security.api-key=test-key", "llm.initial-backoff-ms=5", "llm.provider=fake"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class ApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String request(String requestId, String supplier, int qty, String price, String justification) {
        return """
                {"requestId":"%s","requester":{"employeeId":"E-1042","name":"Ana Souza","department":"TI","costCenter":"CC-4410"},
                 %s
                 "items":[{"sku":"MON-27","description":"Monitor 27","category":"IT_HARDWARE","quantity":%d,"unitPrice":%s}],
                 "currency":"BRL","justification":%s,"urgency":"NORMAL"}"""
                .formatted(requestId, supplier == null ? "" : "\"supplier\":{\"taxId\":\"" + supplier + "\",\"name\":\"X\"},",
                        qty, price, justification == null ? "null" : "\"" + justification + "\"");
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).header("X-API-Key", "test-key").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        mvc.perform(post("/v1/purchase-requests/evaluate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contractViolationReturns400WithDetails() throws Exception {
        postJson("/v1/purchase-requests/evaluate", "{\"requestId\":\"X\",\"items\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTRACT_VIOLATION"))
                .andExpect(jsonPath("$.details").isNotEmpty());
    }

    @Test
    void approvesSmallPurchaseWithGroundedExplanationAndAuditTrail() throws Exception {
        String rid = id("PR-IT");
        JsonNode d = body(postJson("/v1/purchase-requests/evaluate",
                request(rid, "11222333000181", 2, "1950.00", "Monitores para novos analistas, padrão homologado."))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(header().exists("X-Trace-Id")));

        assertThat(d.get("decision").asText()).isEqualTo("APPROVE");
        assertThat(d.at("/erpPayload/action").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(d.at("/audit/skillVersions/analyst").asText()).isNotBlank();
        assertThat(d.get("reasons").get(0).get("evidenceIds")).isNotEmpty();
        assertThat(d.toString()).doesNotContain("Ana Souza"); // PII não vaza para contexto/saída

        String decisionId = d.at("/audit/decisionId").asText();
        mvc.perform(get("/v1/decisions/" + decisionId).header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextIncluded").isNotEmpty())
                .andExpect(jsonPath("$.decisionPayload.requestId").value(rid));
    }

    @Test
    void identicalRequestIsReplayedWithoutNewLlmCall() throws Exception {
        String body = request(id("PR-IDEM"), "11222333000181", 1, "100.00", "Mouse para analista novo da equipe.");
        JsonNode first = body(postJson("/v1/purchase-requests/evaluate", body));
        postJson("/v1/purchase-requests/evaluate", body)
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.audit.decisionId").value(first.at("/audit/decisionId").asText()));
    }

    @Test
    void blockedSupplierIsRejectedByRulesWithoutCallingLlm() throws Exception {
        JsonNode d = body(postJson("/v1/purchase-requests/evaluate",
                request(id("PR-BLK"), "33445566000186", 1, "100.00", "Cabos de rede para reforma.")));

        assertThat(d.get("decision").asText()).isEqualTo("REJECT");
        assertThat(d.at("/audit/decidedBy").asText()).isEqualTo("RULE_ENGINE");
        assertThat(d.at("/audit/llmCalls").asInt()).isZero();
        assertThat(d.at("/audit/estimatedCostUsd").asDouble()).isZero();
    }

    @Test
    void llmOutageFallsBackToHumanReviewWithValidContract() throws Exception {
        JsonNode d = body(postJson("/v1/purchase-requests/evaluate",
                request(id("PR-FAKE-DOWN"), "11222333000181", 1, "100.00", "Mouse para analista novo da equipe."))
                .andExpect(status().isOk()));

        assertThat(d.get("decision").asText()).isEqualTo("ESCALATE_TO_HUMAN");
        assertThat(d.at("/audit/decidedBy").asText()).isEqualTo("FALLBACK");
        assertThat(d.at("/audit/fallbackReason").asText()).isEqualTo("LLM_OVERLOADED");
    }

    @Test
    void needsInfoOpensCaseAndFollowUpResolvesIt() throws Exception {
        JsonNode first = body(postJson("/v1/purchase-requests/evaluate",
                request(id("PR-CASE"), null, 3, "1950.00", "Monitores para os novos analistas de infraestrutura.")));
        assertThat(first.get("decision").asText()).isEqualTo("NEEDS_INFO");
        String caseId = first.get("caseId").asText();
        assertThat(caseId).isNotBlank();

        JsonNode second = body(postJson("/v1/cases/" + caseId + "/messages", """
                {"message":"Fornecedor é a Acme, homologada.",
                 "updates":{"supplier":{"taxId":"11222333000181","name":"Acme Hardware Ltda"}}}"""));
        assertThat(second.get("decision").asText()).isEqualTo("APPROVE");
        assertThat(second.get("caseId").asText()).isEqualTo(caseId);

        mvc.perform(get("/v1/cases/" + caseId).header("X-API-Key", "test-key"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.round").value(2));
    }

    @Test
    void skillCrudIsVersionedAndTraceableInDecisions() throws Exception {
        // update = nova versão imutável, ativada
        String newPrompt = mapper.writeValueAsString("---\nid: analyst\nversion: 9.0.0\n---\nVocê é o Analista de Risco. Use só as evidências.");
        postJson("/v1/skills/analyst/versions", "{\"version\":\"9.0.0\",\"content\":" + newPrompt
                + ",\"changelog\":\"teste\",\"activate\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        JsonNode d = body(postJson("/v1/purchase-requests/evaluate",
                request(id("PR-SKILL"), "11222333000181", 1, "100.00", "Mouse para analista novo da equipe.")));
        assertThat(d.at("/audit/skillVersions/analyst").asText()).isEqualTo("9.0.0");

        // versões são imutáveis
        postJson("/v1/skills/analyst/versions", "{\"version\":\"9.0.0\",\"content\":\"x\"}")
                .andExpect(status().isConflict());

        // rollback: reativar versão anterior
        mvc.perform(post("/v1/skills/analyst/versions/1.0.0/activate").header("X-API-Key", "test-key"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // skill essencial não pode ser removida
        mvc.perform(delete("/v1/skills/analyst").header("X-API-Key", "test-key")).andExpect(status().isConflict());

        // create + delete de skill opcional
        postJson("/v1/skills", "{\"skillId\":\"tmp-examples\",\"type\":\"EXAMPLES\",\"content\":\"exemplo\"}")
                .andExpect(status().isCreated());
        mvc.perform(delete("/v1/skills/tmp-examples").header("X-API-Key", "test-key")).andExpect(status().isNoContent());
        mvc.perform(get("/v1/skills/tmp-examples").header("X-API-Key", "test-key"))
                .andExpect(jsonPath("$[0].status").value("DELETED"));
    }

    @Test
    void exposesLlmAndAgentMetrics() throws Exception {
        postJson("/v1/purchase-requests/evaluate",
                request(id("PR-MET"), "11222333000181", 1, "100.00", "Mouse para analista novo da equipe."));

        String prom = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(prom).contains("llm_tokens_total", "llm_cost_usd_total", "agent_decisions_total",
                "context_tokens_by_layer");
    }
}
