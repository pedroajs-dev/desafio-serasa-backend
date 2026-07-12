# Desafio Serasa — Backend de Pesagem de Grãos

Solução para o desafio técnico backend: ingestão, estabilização e armazenamento de leituras de peso de balanças (ESP32) em filiais de transporte de grãos, com cálculo de custo/margem e relatórios administrativos.

## Sumário

- [Como rodar](#como-rodar)
- [Endpoints](#endpoints)
- [Estratégia de estabilização](#estratégia-de-estabilização)
- [Cálculo de margem e custo](#cálculo-de-margem-e-custo)
- [Detecção de anomalia de peso](#detecção-de-anomalia-de-peso)
- [Nota de arquitetura: Kafka (desenho) vs. processamento síncrono (entrega)](#nota-de-arquitetura-kafka-desenho-vs-processamento-síncrono-entrega)
- [Estratégia de branches, PRs e revisão](#estratégia-de-branches-prs-e-revisão)
- [Decisão de arquitetura interna: MVC em vez de Hexagonal/Ports & Adapters](#decisão-de-arquitetura-interna-mvc-em-vez-de-hexagonalports--adapters)
- [Observabilidade](#observabilidade)
- [Trade-offs conhecidos](#trade-offs-conhecidos)
- [Uso de IA](#uso-de-ia)
- [Disciplina de processo](#disciplina-de-processo)
- [Sugestões de expansão (não implementadas)](#sugestões-de-expansão-não-implementadas)

## Como rodar

Pré-requisito: Java 21. Nenhum outro setup externo é necessário (sem Docker, sem containers).

Para uma demonstração guiada com dados variados e um dashboard visual:

1. Suba a aplicação (se ainda não estiver rodando):
```bash
   ./mvnw spring-boot:run
```
2. Em outro terminal, rode:
```bash
   python scripts/seed_demo_data.py
```
   O navegador abre automaticamente no dashboard antes dos ciclos começarem, e os cards atualizam ao vivo conforme as pesagens completam — balanças, tipos de grão e filiais variados, incluindo o alerta de escassez do Milho já ativo.

Veja [`docs/demo-dashboard.md`](docs/demo-dashboard.md) para mais detalhes, opções (`--count`, `--interval`, `--no-open-browser`), o script complementar `scripts/demo.py` (um ciclo único e detalhado), e `scripts/test_anomaly_detection.py` (dispara manualmente o WARN de detecção de anomalia — ver seção "Detecção de anomalia de peso" abaixo).

A aplicação sobe em `http://localhost:8080`.

- **H2 Console**: `http://localhost:8080/h2-console`
  JDBC URL: `jdbc:h2:mem:balancas`, usuário `sa`, senha em branco.
  Útil para inspecionar as tabelas (`grain_type`, `truck`, `scale`, `transport_transaction`, `weighing_record`, etc.) durante os testes.
- **Swagger UI**: `http://localhost:8080/swagger-ui.html` — documentação OpenAPI gerada automaticamente pelo `springdoc-openapi` a partir dos controllers existentes.
- Dados de exemplo (filiais, tipos de grão, caminhões, balanças) são carregados automaticamente no boot via `src/main/resources/data.sql`.

## Endpoints

| Método | Caminho | Descrição |
|---|---|---|
| POST | `/api/branches` | Cria filial; rejeita nome duplicado (409 Conflict) |
| GET | `/api/branches` | Lista filiais |
| POST | `/api/grain-types` | Cria tipo de grão; rejeita nome duplicado (409 Conflict) |
| GET | `/api/grain-types` | Lista tipos de grão |
| POST | `/api/trucks` | Cria caminhão (placa + tara); rejeita placa duplicada (409 Conflict) |
| GET | `/api/trucks/{id}` | Busca caminhão por id |
| POST | `/api/scales` | Cria balança (vinculada a uma filial, com apiKey) |
| GET | `/api/scales` | Lista balanças |
| POST | `/api/transactions` | Abre uma transação de transporte (status `IN_TRANSIT`); rejeita se o caminhão já tiver uma transação aberta |
| GET | `/api/transactions/{id}` | Busca transação por id |
| PATCH | `/api/transactions/{id}/status` | Atualiza manualmente o status da transação |
| POST | `/api/scales/readings` | Endpoint de ingestão — recebe as leituras do ESP32 (ver seção abaixo) |
| GET | `/api/reports/cost-by-grain?from=&to=` | Custo total de compra por tipo de grão no período |
| GET | `/api/reports/scale-ranking` | Ranking de balanças por volume de pesagens concluídas |
| GET | `/api/reports/avg-weighing-duration` | Tempo médio de pesagem (segundos) por filial |
| GET | `/api/reports/avg-margin-by-grain` | Margem média aplicada por tipo de grão |
| GET | `/api/reports/scarcity-alerts` | Tipos de grão com margem calculada acima do limiar de escassez |

### Ingestão de leituras (`POST /api/scales/readings`)

```json
{ "id": "BAL-001", "plate": "ABC1D23", "weight": 34521.4 }
```

Campos opcionais e retrocompatíveis: `seq` (inteiro, para idempotência) e `timestamp`.

- Autenticação via header `X-Scale-Key`, comparado com o `apiKey` cadastrado para aquela balança (`id`). Ausente ou inválido → `401 Unauthorized`.
- Payload inválido (campo obrigatório faltando) → `400 Bad Request`.
- Sucesso → `202 Accepted`. O processamento (estabilização +, quando aplicável, persistência do `WeighingRecord` e fechamento da transação) acontece de forma síncrona dentro da própria requisição antes da resposta — não há fila/mensageria de fato nesta entrega (ver nota de arquitetura abaixo). Do ponto de vista do ESP32 o comportamento continua fire-and-forget, já que ele nunca aguarda nem processa o corpo da resposta.
- Se `seq` estiver presente e a combinação `id + seq` já tiver sido processada para aquela balança, a leitura é descartada silenciosamente (ainda responde `202`).

## Estratégia de estabilização

Cada balança mantém um estado independente em um `ConcurrentHashMap<String scaleId, ScaleState>` dentro de `StabilizationService`. Toda atualização de estado passa por `map.compute(scaleId, ...)`, que garante atomicidade **por chave**: duas leituras quase simultâneas da mesma balança não corrompem o buffer uma da outra, e balanças diferentes nunca se bloqueiam entre si (não há lock global).

Algoritmo, por leitura recebida `(scaleId, plate, weightKg)`:

1. Se `weightKg` < `resetThresholdKg` (caminhão saiu da balança), o estado daquela balança é resetado e a leitura é ignorada.
2. Caso contrário, a leitura entra num buffer FIFO (`Deque`) de tamanho fixo `windowSize`.
3. Quando o buffer atinge `windowSize` leituras, calcula-se o desvio padrão da janela.
4. Se o desvio padrão ≤ `stdDevThreshold`, incrementa um contador de janelas estáveis consecutivas; caso contrário, o contador zera.
5. Ao atingir `consecutiveWindows` janelas estáveis seguidas — e apenas na primeira vez (trava `alreadyPersisted`) — persiste o peso médio da janela como `WeighingRecord` e fecha a `TransportTransaction` aberta daquele caminhão.

Parâmetros atualmente configurados em `src/main/resources/application.yml` (bloco `stabilization`):

```yaml
stabilization:
  window-size: 15
  std-dev-threshold: 5.0
  consecutive-windows: 3
  reset-threshold-kg: 50.0
```

Usar desvio padrão sobre uma janela (em vez de comparar leituras consecutivas) torna a detecção robusta a um spike isolado de ruído; exigir múltiplas janelas consecutivas estáveis evita persistir um platô momentâneo que ainda vai oscilar; e a trava `alreadyPersisted` evita persistir duas vezes a mesma pesagem enquanto o caminhão permanece parado sobre a balança.

## Cálculo de margem e custo

- **Custo da carga** (`loadCost`, persistido em `TransportTransaction`): `(netWeightKg / 1000) × purchasePricePerTon` — **não** inclui margem, é apenas o custo de compra do grão.
- **Margem de venda**, calculada sob demanda (não persistida), variando entre 5% (mínima) e 20% (máxima), inversamente proporcional ao estoque disponível:

  ```
  margin = maxMargin - (maxMargin - minMargin) × min(1, currentStock / maxReferenceStock)
  ```

- **Preço de venda por tonelada**: `salePricePerTon = purchasePricePerTon × (1 + margin)`.

Exemplo com dados reais de `data.sql` (Milho): `purchasePricePerTon = 95.50`, `currentStock = 2000`, `maxReferenceStock = 40000` → `ratio = 0.05` → `margin = 0.20 - 0.15 × 0.05 = 0.1925` (19,25%) → `salePricePerTon = 95.50 × 1.1925 = 113.88375 ≈ 113.88`.

Esse valor de estoque do Milho foi deliberadamente reduzido no seed (de um valor maior para 2000) para que a margem calculada já ultrapasse o limiar de escassez (`reports.scarcity-threshold: 0.18`, em `application.yml`) desde o boot da aplicação, sem precisar de nenhuma edição manual no banco — o endpoint `/api/reports/scarcity-alerts` já mostra o Milho como alerta ativo assim que a aplicação sobe.

## Detecção de anomalia de peso

Em `WeighingPersistenceService.persist()`, após o `netWeightKg` ser calculado e validado como positivo, o `grossWeightKg` estabilizado é comparado a um teto de carga plausível para aquele caminhão: `tare × (1 + anomaly-detection.max-payload-multiplier)`. Com o valor padrão configurado em `application.yml` (`max-payload-multiplier: 3.0`), o teto é `tare × 4` — por exemplo, para um caminhão com tara de 8500kg, um peso bruto acima de 34000kg dispara o alerta.

Se o teto for ultrapassado, um log `WARN` é emitido com `plate`, `scaleId`, `grossWeightKg` e o teto calculado, para investigação manual (possível glitch de sensor ou tentativa de fraude). **Isso é apenas detecção/log — não bloqueia nem rejeita a pesagem**: o `WeighingRecord` é persistido e a `TransportTransaction` é fechada normalmente, para que um sensor com ruído não deixe um caminhão real parado em trânsito. Não é um sistema de prevenção de fraude, apenas um sinal de nível `WARN` para acompanhamento.

A cobertura automatizada dessa regra está em `WeighingPersistenceServiceTest` (um caso dentro da faixa plausível não gera o log; um caso muito acima do teto gera o `WARN` e ainda assim persiste normalmente). Para observar o `WARN` de fato saindo no console de uma aplicação rodando (não apenas capturado dentro do teste JUnit), existe um script dedicado, `scripts/test_anomaly_detection.py`: ele cria um caminhão, abre uma transação, envia leituras visando um peso bruto propositalmente muito acima do teto calculado (lido de `application.yml`, não fixado no código) e imprime um resumo pedindo para conferir a linha `Anomaly detected` no console da aplicação. Ver a seção ["Manually verifying anomaly detection"](docs/demo-dashboard.md#manually-verifying-anomaly-detection) em `docs/demo-dashboard.md` para o passo a passo completo — o script só dispara a condição e reporta o que enviou, não lê o stdout da aplicação, então não substitui o teste automatizado.

## Nota de arquitetura: Kafka (desenho) vs. processamento síncrono (entrega)

A solução foi originalmente desenhada para usar Kafka (Redpanda) como camada de ingestão assíncrona: o endpoint publicaria a leitura em um tópico particionado por `scaleId`, garantindo ordem das leituras daquela balança dentro da partição — pré-requisito para o algoritmo de janela deslizante funcionar sem lock manual entre threads — e desacoplando a ingestão (que deve responder rápido) do processamento de estabilização (que pode ter retry, levar mais tempo, etc.), além de escalar horizontalmente adicionando partições/consumers.

Por restrição de ambiente de desenvolvimento (Docker/WSL indisponível no momento da entrega) e prazo, a implementação entregue usa processamento **síncrono em memória**: o próprio endpoint chama `StabilizationService.process()` diretamente, com `ConcurrentHashMap.compute()` por `scaleId` cumprindo o mesmo papel que o particionamento por chave cumpriria no Kafka — isolamento de estado por balança sem lock global — só que dentro de uma única instância JVM, em vez de distribuído entre partições/consumers. A migração para Kafka é uma troca de camada de infraestrutura (endpoint publica no tópico, um consumer roda a mesma lógica de `StabilizationService`), não uma mudança na lógica de negócio.

## Estratégia de branches, PRs e revisão

Cada um dos 6 épicos numerados do desafio foi desenvolvido em sua própria branch de feature, aberta como Pull Request contra main e mesclada após revisão, com as correções de code review já incorporadas antes do merge. Branches fora da numeração de épicos (como chore/demo-script-dashboard, tooling de demonstração, e a própria branch de documentação deste README) seguem o mesmo fluxo de PR + revisão, mas não fazem parte do escopo formal do desafio

## Decisão de arquitetura interna: MVC em vez de Hexagonal/Ports & Adapters

O código segue uma organização por pacote de domínio (`branch`, `truck`, `scale`, `transporttransaction`, `stabilization`, `margin`, `report`, ...) com Controller → Service/Repository, próxima de um MVC em camadas, em vez de uma arquitetura hexagonal completa com portas e adaptadores em todas as bordas. Exceção pontual: `StabilizationService` depende de uma interface `WeighingPersistencePort` (não diretamente do repositório JPA) para manter o `compute()` da estabilização livre de I/O de banco dentro do lock por chave — um ponto isolado onde a inversão de dependência trouxe benefício concreto, sem generalizar o padrão para o restante do sistema, dado o escopo e prazo do desafio.

## Observabilidade

Spring Boot Actuator (`spring-boot-starter-actuator`) está habilitado, com `micrometer-registry-prometheus` para exportar métricas no formato Prometheus. Endpoints expostos via `management.endpoints.web.exposure.include` em `application.yml`: `/actuator/health`, `/actuator/info`, `/actuator/metrics` e `/actuator/prometheus` — este último já pronto para ser raspado (scrape) por uma instância externa do Prometheus, que **não** está incluída nem rodando neste projeto; é só o endpoint compatível. Nenhuma autenticação foi adicionada nesses endpoints (aplicação local de demonstração, fora do escopo de hardening). Por enquanto são apenas as métricas de infraestrutura padrão do Actuator/Micrometer (JVM, HTTP, HikariCP, etc.) — métricas de negócio customizadas via `MeterRegistry` seguem como sugestão de expansão (ver abaixo).

## Trade-offs conhecidos

- **H2 em memória, não Postgres**: dados são perdidos a cada restart da aplicação — decisão consciente de simplificação para o ambiente de entrega. O modelo de dados já foi desenhado pensando em Postgres; a migração seria apenas troca de driver/config, sem mudança de schema/lógica.
- **Estado da estabilização e idempotência em memória (não Redis/externo)**: `ScaleState` (em `StabilizationService`) e o conjunto de chaves já processadas (em `ReadingIdempotencyService`) vivem em `ConcurrentHashMap`s locais à JVM, sem TTL/eviction e sem persistência entre restarts. Válido para uma única instância; múltiplas réplicas ou reinícios do processo perderiam essas garantias — precisaria de um store compartilhado (Redis, por exemplo) para sobreviver a isso.
- **`apiKey` de balança em texto plano**: armazenado e comparado sem hashing (aceitável para um cenário de rede local/VPN fechada entre as balanças e o servidor; revisitar se a ingestão passar a ser exposta diretamente à internet).
- **Sem rate limiting no endpoint de ingestão**: uma balança malfuncionando ou maliciosa pode inundar o `StabilizationService` de requisições; fora do escopo definido para esta entrega.
- **Arredondamento de valores financeiros**: `grossWeightKg`, `netWeightKg` e `loadCost` são arredondados para 2 casas decimais antes de persistir (evitando resíduo de ponto flutuante herdado da média de leituras `double`); agregações de relatório que somam esses valores (`cost-by-grain`) também arredondam o total antes de retornar, já que a soma de muitos valores já arredondados ainda pode reintroduzir um resíduo mínimo.

A lista completa e datada de gaps identificados em cada code review (incluindo os itens acima, com mais detalhe técnico) está em [`_bmad-output/implementation-artifacts/deferred-work.md`](_bmad-output/implementation-artifacts/deferred-work.md), e o arredondamento de valores financeiros tem seu próprio log de correção em [`_bmad-output/implementation-artifacts/fix-1-round-persisted-weight-cost-values.md`](_bmad-output/implementation-artifacts/fix-1-round-persisted-weight-cost-values.md).

## Uso de IA

O BMad Method é um framework de desenvolvimento orientado a agentes de IA que decompõe o trabalho em stories individuais — cada uma com escopo, tasks e acceptance criteria explícitos — em vez de um prompt único e genérico por feature. Isso permite controlar o escopo de cada entrega da IA e revisar/aprovar antes de avançar para a próxima. O ciclo usado nesta entrega, repetido a cada story:

1. `bmad-create-story` — um agente gera o arquivo da story a partir do PRD/épicos, com escopo e critérios de aceite definidos antes de qualquer código ser escrito.
2. `bmad-dev-story` — um agente implementa a story dentro desse escopo.
3. Revisão manual do código gerado (arquivos completos, não apenas diffs).
4. `bmad-code-review` — um agente revisa criticamente o código contra um checklist, apontando gaps, riscos e testes faltantes (ocasionalmente multi-agente, para stories mais críticas).
5. Teste manual via PowerShell (`Invoke-RestMethod`) contra a aplicação rodando.
6. Commit seguindo Conventional Commits, referenciando a story.
7. Atualização do `sprint-status.yaml`, que rastreia o status de cada story (draft, in-progress, review, done).

Modelo padrão: Claude Sonnet 5; Claude Opus 4.8 foi usado pontualmente em revisões de código consideradas mais críticas (ex.: Story 2.2, idempotência).

**Código gerado por IA**: praticamente todo o código de produção em `src/main/java/com/serasa/balancas/` (controllers, services, repositories, entidades) e os testes em `src/test/java/` foram gerados pelo Claude Code seguindo o ciclo acima, com revisão manual do código completo e `bmad-code-review` antes de cada merge — não há trechos comentados individualmente como "gerado por IA" porque a autoria de IA é a regra, não a exceção, nesta entrega.

Alguns exemplos reais e significativos de prompts que geraram decisões de arquitetura ou correções de bug:

- **Correção da fórmula de negócio (custo vs. preço de venda), identificada antes da implementação**: o requisito original confundia "custo da carga" com um valor já incluindo margem, e não convertia a unidade de peso (kg) para tonelada antes de aplicar o preço de compra. O prompt corrigiu isso em duas regras separadas — `custoCarga = (pesoLiquidoKg / 1000) × precoCompraPorTonelada` (sem margem) e `precoVendaPorTonelada = precoCompraPorTonelada × (1 + margem)` (separado, sob demanda) — antes de qualquer linha de código ser escrita.
- **Decisão de concorrência com `ConcurrentHashMap.compute()`**: definição explícita, antes de implementar a Story 3.1, de que a atualização do estado de cada balança deveria passar por `map.compute(scaleId, ...)` para garantir atomicidade por chave sem lock global — e, na sequência, a decisão de que a chamada de persistência (I/O de banco) não poderia rodar dentro do `compute()`, para não segurar o lock daquela balança durante o round-trip ao banco.
- **Bug de precisão de ponto flutuante encontrado rodando a demo**: inspecionando o H2 Console após uma pesagem completa via simulador, `loadCost` apareceu persistido com precisão total (ex. `4229.841980016929`); o prompt determinou a correção — arredondar `grossWeightKg`, `netWeightKg` e `loadCost` para 2 casas decimais com `BigDecimal.setScale(2, RoundingMode.HALF_UP)` antes de persistir, e verificar se as agregações dos relatórios do Épico 5 também precisavam do mesmo tratamento.

Lista completa dos prompts mais significativos por épico em [`06-prompts-utilizados.md`](06-prompts-utilizados.md) — não é um transcript literal de cada troca; interações de rotina (aprovações simples, "sim", "continua") foram omitidas, conforme nota no início daquele arquivo.

## Disciplina de processo

Um git pre-commit hook (script versionado em [`scripts/pre-commit.sh`](scripts/pre-commit.sh)) roda a suíte de testes completa (`./mvnw test -q`) automaticamente antes de cada commit, abortando-o se algum teste falhar. Isso substitui o passo 5 do ciclo de IA acima — antes um teste manual via PowerShell — por um gate determinístico e automático: nenhum commit chega ao histórico com o build quebrado. Vale notar que é um gate local de pre-commit, não um pipeline de CI/CD completo (sem checagens em PR, sem execução remota). Para instalar o hook em um clone novo, copie ou faça symlink do script para `.git/hooks/pre-commit`:

```bash
cp scripts/pre-commit.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

## Sugestões de expansão (não implementadas)

- Migrar a ingestão para Kafka/Redpanda (ver seção de arquitetura acima), permitindo escalar horizontalmente e desacoplar ingestão de processamento.
- Buffer local no ESP32 com reenvio em caso de falha de rede (exigiria alterar o firmware real do dispositivo, fora do escopo).
- Dashboard em tempo real via WebSocket (o dashboard atual em `docs/demo-dashboard.md` faz polling HTTP a cada 3s, suficiente para demonstração).
- mTLS ou rotação de `apiKey` para as balanças, em vez de chave estática.
- Migrar o formato de erro para RFC 9457 (Problem Details): hoje usa um `ErrorResponse` customizado via `GlobalExceptionHandler`, funcional e testado, mas o padrão RFC 9457 (suportado nativamente pelo Spring Boot 3 via `ProblemDetail`) é o padrão de mercado para corpos de erro HTTP.
- Estratégia de logging estruturada: padronizar nível de log por tipo de evento (`INFO` para pesagem estabilizada/transação fechada, `WARN` para skips conhecidos, `ERROR` para falhas reais), correlacionar `scaleId`/`plate` via MDC por requisição, e adotar formato JSON para facilitar agregação em ferramentas de observabilidade.
- Métricas de negócio customizadas via `MeterRegistry` (além das métricas de infraestrutura já expostas — ver seção "Observabilidade" acima): leituras descartadas por idempotência, tempo médio até estabilização por balança, taxa de skip gracioso por tipo.
