# Uso de IA na construção do case

> O case permite o uso de IA e pede que ele seja explícito. Este documento registra **como, quando e com quais controles** a IA foi usada, e o que foi decisão humana.

## Ferramentas

- **Claude Code** (desktop app, modelo Claude Opus 5.5) como par de programação agêntico, com acesso ao terminal e aos arquivos.
- **Workspace [`agent-skills`](https://github.com/addyosmani/agent-skills)**: um conjunto de skills de engenharia (spec → plan → build → test → review → ship) que guiou o processo. Em vez de "pedir código", o trabalho seguiu fases com gates de aprovação.
- **Skill `claude-api`** (referência oficial do SDK Anthropic, carregada pelo Claude Code) para usar as APIs corretas do SDK Java e não depender de memória desatualizada.

## Linha do tempo

| Fase | O que a IA fez | O que foi decisão/validação humana |
|---|---|---|
| **1. Entendimento** | Extraiu o texto do PDF do case (via `pdftotext`) e mapeou cada requisito para um item da solução | — |
| **2. Decisões de alto nível** | Propôs as opções com trade-offs (cenário, stack, provedor de LLM, local do projeto) | **Humano escolheu:** cenário 03 (compras), **Java + Spring Boot** (a IA havia recomendado Python por maturidade do ecossistema de LLM; o humano optou por Java, _motivo: preencher_), Claude como LLM, repositório separado |
| **3. Especificação** (`/spec`, skill `spec-driven-development`) | Escreveu o [SPEC.md](../SPEC.md): premissas explícitas, mapa de capacidades, contratos, budget de tokens, critérios de sucesso, perguntas em aberto | **Humano aprovou a spec** antes de qualquer código e respondeu às perguntas em aberto (prazo, repositório, chave de API) |
| **4. Ambiente** | Instalou JDK 21 (winget), Maven 3.9.16 (download oficial com **checksum SHA-512 verificado**) e GitHub CLI | Autorizado pelo humano |
| **5. Plano** (`planning-and-task-breakdown`) | [tasks/plan.md](../tasks/plan.md) e [tasks/todo.md](../tasks/todo.md) com tarefas, critérios de aceite e riscos | — |
| **6. Pesquisa de API** (`source-driven-development`) | Consultou versões no Maven Central e a documentação do SDK. **Descobriu que o Sonnet 5.5 rejeita `tool_choice` forçado e `temperature`**, o que mudou o design para *structured outputs* ([ADR-0002](adr/0002-structured-outputs-e-validacao.md)). Usou `javap` no jar do SDK para confirmar os nomes de classes | — |
| **7. Implementação** (`incremental-implementation`) | Gerou o código por módulo (contract → intake → policy → context → llm → agent → api → registry), compilando a cada etapa | — |
| **8. Testes e evals** | Escreveu testes unitários, de integração e o golden set (34 casos) | — |
| **9. Correções encontradas pela própria verificação** | Ver tabela abaixo | — |
| **10. Documentação** | README, AGENT.md, ADRs, arquitetura, observabilidade e este arquivo | — |

## Problemas encontrados e corrigidos durante a verificação

A IA não acertou tudo de primeira. Itens detectados por compilação, testes ou execução real, e corrigidos:

| Problema | Como foi detectado | Correção |
|---|---|---|
| Uso de método inexistente (`getOriginalMessage`) numa exceção genérica | Erro de compilação | Captura do tipo correto (`JsonProcessingException`) |
| Endpoint de métricas vazio nos testes | Teste de integração falhou | O Spring Boot desliga a exportação de métricas em testes; adicionado `@AutoConfigureObservability` |
| Fake considerava "Não precisa de **cotação**" como menção positiva a cotações | Revisão do caso adversarial GS-026 | Regex exige menção afirmativa ("três cotações", "cotações anexadas"…) |
| Timestamps de auditoria e de skills usando o "hoje" fixo das regras de negócio | Execução real do demo (todas as datas iguais a 12:00:00Z) | Relógio fixo só para regras; auditoria usa o horário real |
| Payload com acentos corrompido no `curl` do Git Bash (Windows) | Execução do script de demo (400 MALFORMED_JSON) | Payloads montados em arquivo UTF-8 e enviados com `--data-binary` |
| Prompt do analista v1.0.0 não proibia valores calculados, o que conflita com o validador de grounding | Revisão cruzada prompt × validador | `analyst@1.1.0` ([CHANGELOG-SKILLS.md](../CHANGELOG-SKILLS.md)) |

## Controles aplicados sobre o que a IA produziu

- **Gates por fase:** nenhum código antes da spec aprovada.
- **Verificação executável:** compilação, 36 testes, golden set e execução real da API com scripts de demo. Nada foi declarado "pronto" sem rodar.
- **Honestidade sobre limites:** o eval com o Claude real **não** foi executado (sem chave de API até a entrega). Isso está declarado no README, no AGENT.md e na estratégia de testes, e o resultado do fake não é apresentado como qualidade do modelo.
- **Commits com coautoria** (`Co-Authored-By: Claude`) para rastrear o que foi gerado com IA.

## O que eu (candidato) devo dominar para a entrevista

Por ter sido construído com IA, revisei pessoalmente estes pontos e sei defendê-los:
- Por que o LLM não decide sozinho e onde estão as regras rígidas (`PolicyEngine`, `DecisionValidator.enforce`).
- Como funciona o grounding (`DecisionValidator.parseAndCheck`) e o que ele **não** cobre.
- O algoritmo de seleção de contexto (`ContextBuilder.select`) e o budget por camada.
- O fluxo de fallback e os pontos em que a decisão vira `ESCALATE_TO_HUMAN`.
- Por que structured outputs e não tool use forçado.
- O que o eval fake prova e o que não prova.

> _Espaço para anotações pessoais de revisão do candidato antes da apresentação._
