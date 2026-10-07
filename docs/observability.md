# Confiabilidade e observabilidade

## 1. Três perguntas que a observabilidade precisa responder

1. **O agente está saudável?** (latência, erro, fallback, custo)
2. **O modelo está se comportando como esperado?** (grounding, overrides, distribuição de decisões)
3. **Por que esta decisão específica foi tomada?** (auditoria por `decisionId` / `traceId`)

## 2. Métricas (Micrometer → `/actuator/prometheus`)

| Métrica | Tags | Para quê |
|---|---|---|
| `llm.request.latency` (p50/p95/p99) | purpose, model, outcome | SLO de latência por papel (analista, reparo, compliance) |
| `llm.tokens` | purpose, model, direction (input/output/cache_read) | Consumo e taxa de cache hit |
| `llm.cost.usd` | purpose, model | Custo acumulado por papel e modelo |
| `llm.errors` | purpose, type (RATE_LIMITED, OVERLOADED, TIMEOUT, REFUSAL, CIRCUIT_OPEN…) | Saúde do provedor |
| `llm.retries` | type | Pressão de rate limit |
| `agent.decisions` | decision, decidedBy, riskLevel | Distribuição de decisões (detecção de drift) |
| `agent.decision.latency` | decidedBy | Latência ponta a ponta |
| `agent.decision.cost.usd` | — | Custo por decisão (FinOps) |
| `agent.fallbacks` | reason | Quanto o sistema está decidindo sem o LLM |
| `agent.guardrail.events` | event (APPROVE_BLOCKED_BY_POLICY, APPROVE_WITH_HIGH_RISK, REPAIR_ATTEMPT_1, COMPLIANCE_DISAGREED…) | Comportamento inesperado do modelo |
| `agent.validation.failures`, `agent.grounding.errors` | stage | Alucinação e saída fora do contrato |
| `agent.compliance.disagreements` | — | Divergência entre analista e auditor |
| `context.tokens.by_layer`, `context.tokens.total` | layer | Calibração do budget |
| `context.truncations` | — | Contexto cortado (risco de decisão com informação faltante) |
| `api.rate_limited` | route | Consumidor batendo no limite (abuso, loop ou capacidade) |

## 3. Logs estruturados

- Formato **ECS/JSON** (`logging.structured.format.console: ecs`), com `traceId` no MDC, propagado do header `X-Trace-Id` ou gerado.
- Uma linha de log por decisão (`decision ...`) com requestId, decisionId, decision, risk, decidedBy, llmCalls, tokens, custo, latência, tokens de contexto, IDs excluídos, eventos de guardrail e versões de skills.
- **Não se loga** prompt completo nem justificativa (podem conter PII ou dados sensíveis). O conteúdo exato que o modelo viu é reconstituível pela auditoria (`evidence` + versões das skills).

## 4. Auditoria

`GET /v1/decisions/{decisionId}` devolve a decisão completa, os IDs de contexto incluídos e excluídos, as versões de skills, os tokens, o custo, a latência e o traceId. O `DecisionRecord` é imutável e é gravado em toda decisão, inclusive em fallbacks.

## 5. Alertas

Regras prontas em [`deploy/prometheus/alerts.yml`](../deploy/prometheus/alerts.yml), carregadas pelo Prometheus do `docker-compose` e validadas no CI com `promtool check rules`. Cada alerta tem `severity` e `runbook`.

| Alerta | Condição | Severidade | Ação |
|---|---|---|---|
| Fallback alto | `agent.fallbacks` > 5% das decisões em 15 min | Alta | Verificar provedor/circuit breaker; aprovadores humanos recebem mais volume |
| Grounding | `agent.grounding.errors` após reparo > 0 em 1 h | Alta | Possível regressão de prompt/modelo: congelar ativação de skills e rodar eval |
| Overrides de guardrail | `APPROVE_BLOCKED_BY_POLICY` > 1% | Média | O modelo está tentando aprovar contra a regra: revisar prompt |
| Drift de decisões | % de APPROVE varia ±15 pp vs. média de 7 dias | Média | Mudança de dados, de prompt ou de modelo |
| Custo | `llm.cost.usd` por hora > orçamento | Média | Verificar loops de reparo, cache e volume |
| Latência | p95 `agent.decision.latency` > 15 s | Média | Provedor lento: avaliar effort e timeout |
| Circuit aberto | `llm.errors{type=CIRCUIT_OPEN}` > 0 | Alta | Provedor fora do ar |
| Compliance divergente | `agent.compliance.disagreements` > 10% dos revisados | Baixa | Calibração entre analista e revisor |

## 6. Comportamento sem evidência suficiente

Quando o modelo não tem evidência, o comportamento esperado é `NEEDS_INFO` (dado faltante) ou `ESCALATE_TO_HUMAN` (julgamento). Isso é garantido em três níveis: instrução no prompt, verificação de grounding em código e falha fechada (`FALLBACK` → `ESCALATE`). Fontes indisponíveis aparecem como `EV-SRC-*` e bloqueiam a aprovação automática. Ver [AGENT.md §5–6](../AGENT.md).

## 7. Traces

Hoje o `traceId` é propagado em header, MDC, log e auditoria. A evolução natural é o Micrometer Tracing com OpenTelemetry e spans por estágio (`intake`, `facts`, `policy`, `context`, `llm.analyst`, `validate`, `llm.compliance`), com o atributo `gen_ai.*` (convenções semânticas OTel para GenAI) nos spans de LLM.
