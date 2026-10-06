# Tarefas

- [x] **T1 Scaffold**: pom (Spring Boot 3.5.16, Java 21), app, perfis `fake`/default, `.gitignore`. Aceite: `mvn test` verde com contexto subindo.
- [x] **T2 Contratos**: records `PurchaseRequest`, `PurchaseDecision`, `LlmAssessment` + JSON Schemas v1 + validador de schema. Aceite: testes de contrato.
- [x] **T3 Intake + Policy**: normalização/dataQuality/injeção suspeita; regras de alçada, fornecedor bloqueado e orçamento. Aceite: testes unitários (TDD).
- [x] **T4 Contexto**: mock ERP (JSON), `ContextProvider`, `ContextBuilder` (evidências EV-xxx, camadas, token budget, sumarização de histórico, mascaramento de PII). Aceite: teste de truncamento por prioridade.
- [x] **T5 LLM**: `LlmClient` (Anthropic structured outputs + Fake heurístico/roteirizável), retry/circuit breaker, medição de tokens e custo. Aceite: testes do fake e da política de retry.
- [x] **T6 Agente**: orquestrador + `DecisionValidator` (schema, grounding, números, consistência com as regras) + reparo + compliance + fallback. Aceite: testes de cada caminho de falha.
- [x] **T7 API + auditoria**: `POST /v1/purchase-requests/evaluate`, casos multi-turno, `GET /v1/decisions/{id}`, API key, limites. Aceite: teste MockMvc.
- [x] **T8 Skills registry**: versões imutáveis, ativação/rollback, soft delete, seed por arquivos, versões gravadas na decisão. Aceite: teste do CRUD.
- [x] **T9 Evals**: golden set (≥25), runner, critérios, relatório, baseline. Aceite: `mvn verify -Peval-fake` ok.
- [x] **T10 Observabilidade**: métricas, logs estruturados, traceId. Aceite: métricas aparecem em `/actuator/prometheus`.
- [x] **T11 Docs**: README, AGENT.md, architecture.md (Mermaid), ADRs, AI-USAGE.md, CHANGELOG-SKILLS.md, presentation.md.
- [ ] **T12 Repo + CI**: GitHub Actions (build + test + eval-fake), push.
- [ ] **T13 (stretch) MCP**: não implementado no prazo; ADR-0006 documenta o design e os trade-offs; `ErpGateway` é o ponto de troca.
