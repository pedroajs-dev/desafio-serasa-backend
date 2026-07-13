# Prompts Utilizados — Desafio Serasa

Compilação dos principais prompts usados com o Claude Code ao longo da implementação, organizados por épico/story. Este documento atende ao item obrigatório 6 do desafio ("compartilhamento do prompt utilizado"), complementando o que já está registrado nas descrições de cada PR.

> Nota: nem todos os prompts trocados ao longo do dia foram registrados neste arquivo — são os mais significativos, que geraram decisões de arquitetura, correções de bugs, ou mudanças de escopo relevantes. Interações de rotina (aprovações simples, "sim", "continua") foram omitidas.

---

## Fase de planejamento (antes da implementação)

**Business formula correction identified before implementation (cost vs sale price):**
```
Before confirming, I need to fix FR16 — found two problems in the formula:

1. Confusion between cost and sale price: `custoCarga` (load cost) should NOT
   include the profit margin. Cost is what the company PAID to buy the grain
   (weight × purchase price, no margin). Margin only applies to calculate the
   SALE PRICE, a separate value.

2. Missing unit conversion: pesoLiquidoKg is in kilograms, but
   precoCompraPorTonelada is per metric ton (1000kg).

Fix FR16 into two separate requirements:
FR16a: custoCarga = (pesoLiquidoKg / 1000) × precoCompraPorTonelada
FR16b: precoVendaPorTonelada = precoCompraPorTonelada × (1 + margem)
```

---

## Épico 1 — Core Domain Cadastres

**Story 1.1 — fixar Java 21:**
```
Antes de implementar a Story 1.1, ajusta um ponto no Dev Notes / Task 1:
Fixa a versão do Java em 21 (não "17+"). Já tenho experiência recente com
Java 21 em produção e quero aproveitar features modernas da linguagem como
diferencial técnico na entrega.
```

**Story 1.5 — corrigir método de busca de transação aberta:**
```
1. Story 1.5, Task 1: troca o método de busca do repositório de
   findByTruck_LicensePlateAndStatus(...) para
   findByTruck_LicensePlateAndStatusNot(String licensePlate, TransactionStatus completedStatus).
   Motivo: o Épico 3 precisa achar "a transação aberta" do caminhão, que pode
   estar em qualquer status não-terminal.

2. Story 1.4: fecha definitivamente o tipo do Scale.id como String.
```

**Bug de transação duplicada (Story 1.5):**
```
Great findings. Let's fix all 5, prioritized:
1 & 2 (CRITICAL — blocks Epic 3 correctness): Add a check in create() that
rejects (400, via BusinessException) a new transaction if the truck already
has one in a non-COMPLETED status.
```

**Refactor de exceptions customizadas:**
```
Antes de seguir pra Story 1.4, quero introduzir uma hierarquia de exceptions
personalizadas. Cria:
1. ResourceNotFoundException (404)
2. BusinessException (400)
Cria um @RestControllerAdvice global (GlobalExceptionHandler) que captura
essas exceptions e retorna um corpo de erro padronizado.
```

**Adicionar OpenAPI/Swagger:**
```
Now that Epic 1 is complete, let's add OpenAPI documentation before opening
the PR. Add springdoc-openapi-starter-webmvc-ui. Keep annotation effort
minimal — let springdoc auto-generate the spec from existing controllers.
```

---

## Épico 3 — Stabilization Engine

**Confirmação de entendimento antes de codar (Story 3.1):**
```
Before implementing Story 3.1, summarize in bullet points what you understand
needs to be built. Don't write any code yet. Cover: sliding window mechanism,
stability criteria, consecutive-confirmation requirement, reset condition,
duplicate-persistence guard, concurrency strategy (ConcurrentHashMap.compute()),
what gets persisted.
```

**Decisão de boundary entre 3.1 e 3.2 (sem I/O dentro do compute()):**
```
3.1 should stop at "stability detected, weight = X" and NOT call 3.2's
persistence logic from inside the compute() lambda — running it there would
hold that scale's key lock for the DB round-trip duration. Design it so
compute() returns/updates state indicating "ready to persist", and the
calling method — outside the lambda, after the lock is released — invokes
the persistence step.
```

**Teste de duas sessões consecutivas (Story 3.1):**
```
Add one more test: two consecutive weighing sessions on the SAME scale
(truck A stabilizes and leaves, then truck B mounts and stabilizes with a
different weight). Confirm the second session's stabilized weight is correct
and uncontaminated by truck A's buffer contents.
```

**Decisão sobre netWeightKg negativo (Story 3.2):**
```
Let's add the guard, consistent with the other two skip cases: if
netWeightKg <= 0, log a warning and return without persisting anything.
Add a regression test with a tare GREATER than the stabilized gross weight.
```

**Resposta às decisões do code review (Story 3.2):**
```
1. Race condition: Accept as known risk for now. ConcurrentHashMap.compute()
   already serializes readings per scaleId — the only way to hit this race
   is two DIFFERENT scales processing the same plate concurrently.
2. Missing @Transactional: FIX.
3. NPE risk on unboxing: FIX — add null guards.
4. Missing test for "no Scale found": FIX.
5. netWeightKg == 0 boundary: FIX.
6. epics.md marking as done when not wired: FIX.
```

---

## Épico 2 — Scale Ingestion Endpoint

**Story 2.1 — escopo enxuto dado cota de sessão:**
```
Let's move to Epic 2, Story 2.1 only — not 2.2 yet. Given session budget
constraints, after implementation just run a direct code-review.

Scope: POST endpoint receiving {id, plate, weight}, X-Scale-Key header auth
against Scale.apiKey, calling StabilizationService.process() and — if a
result is present — WeighingPersistencePort.persist().
```

**Correção de vazamento de apiKey + perda de dado em falha de persistência (após code review):**
```
Apply fixes for #1 and #2 now:
#1: Remove apiKey from ScaleResponse. Keep it in the request/creation flow only.
#2: Move alreadyPersisted = true to only be set AFTER
WeighingPersistencePort.persist() completes successfully, not before.
```

**Story 2.2 — decisão sobre conflito entre idempotência e retry de persistência:**
```
Evict the idempotency key in the same catch block that calls
markPersistenceFailed() — keeps the two recovery mechanisms consistent.
Add a regression test: simulate persist() throwing, confirm the seq key is
evicted afterward and a retry with the same seq is NOT discarded.

Also fix duplicateSeqIsDiscardedButStillReturns202 — it passes even if
idempotency were broken, since no transaction is ever opened. Rewrite it
to open a real transaction first, send the same seq twice, and assert
exactly one WeighingRecord was persisted.
```

---

## Épico 4 — Margin & Cost Calculation

**Pergunta sobre double vs BigDecimal:**
```
The ratio/margin calculation is done in double, only converted to BigDecimal
at the very end. Is there a reason not to do the whole calculation in
BigDecimal from the start, given this touches financial computation?
```

---

## Épico 5 — Reports & Statistics

**Story 5.1 — escopo dos relatórios:**
```
Run bmad-create-story for 5.1 (Core Aggregation Reports) covering: total
purchase cost by grain type/period, average margin by grain type, scale
ranking by weighing volume, average weighing duration by branch. Story 5.2
will cover scarcity alerts separately.
```

**Resolução da preocupação de drift de double na média de margem (Story 5.2):**
```
Story 4.1 flagged that averaging margin across multiple grain types could
accumulate drift. Now that avg-margin-by-grain does this averaging — how
did you implement it?
[resposta revelou que não há média de fato, n=1 por grão — nota de
fechamento adicionada à 4.1]
```

---

## Épico 6 — ESP32 Simulator & Tests

**Story 6.2 — corrigir demo terminando em skip em vez de sucesso:**
```
The demo run should end in a SUCCESSFUL, COMPLETE weighing — not a
log-and-skip. Before sending readings: create a truck, open a transaction,
THEN send readings for that truck's plate. After the readings finish, GET
the transaction and print its final state (status=COMPLETED, netWeightKg,
loadCost) as the closing summary.
```

**Bug de precisão de ponto flutuante descoberto rodando a demo:**
```
Confirmed via H2 Console — grossWeightKg, netWeightKg, and loadCost are ALL
persisted unrounded (e.g. loadCost = 4229.841980016929). Fix: round all
three to 2 decimal places before persisting, using
BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue()
(same pattern as MarginService). Also check if Epic 5 report aggregations
need rounding too. Add a regression test with a repeating-decimal-producing
scenario.
```

**Story 6.1 — corrigir escopo para testes de concorrência multi-thread real (após primeira tentativa cobrir só um AC sequencial):**
```
This story only added a sequential test for one missing AC. I actually want
real concurrency testing for StabilizationService — using ExecutorService
with multiple threads, to verify ConcurrentHashMap.compute()'s atomicity
guarantee under real concurrent load, not just by code inspection.

Add two new tests:
1. Hammer the SAME scaleId concurrently from many threads — confirm no
   buffer corruption, persistence happens exactly once, correct final weight.
2. Hammer DIFFERENT scaleIds concurrently — confirm true parallelism with
   no cross-contamination and no exceptions/deadlocks.

Use ExecutorService + CountDownLatch to maximize actual thread overlap
rather than accidentally serializing the test itself. Run the full suite after.
```

---

## Correções de regra de negócio (pós-MVP)

**Incremento de estoque na doca ao completar pesagem (`currentStock` dinâmico):**
```
Business logic gap found re-reading the original challenge spec closely: "A margem
de lucro é inversamente proporcional à quantidade disponível de cada tipo de grão na
doca" (margin is inversely proportional to the available quantity of each grain type
AT THE DOCK). The scenario explicitly says trucks return to the branch, go to the
dock, and get weighed there — meaning a completed weighing represents real grain
arriving at the dock. Currently GrainType.currentStock is a static field, never
updated by WeighingPersistenceService.persist() — so the margin formula uses a number
that never reflects real deliveries, missing the dynamic implied by "quantidade
disponível na doca".

Fix: when a transaction completes with a real weighing (inside
WeighingPersistenceService.persist(), the same place that already sets
grossWeightKg/netWeightKg/loadCost/status/endDate), also increment the associated
GrainType's currentStock by the netWeightKg of that weighing (converted to the same
unit currentStock is stored in — check application.yml/data.sql, currentStock appears
to be in kg already, matching netWeightKg's unit, so likely a direct addition, but
verify the units match before assuming).

Requirements:
1. Add the increment logic to WeighingPersistenceService.persist(), in the same
   transactional method that already persists the WeighingRecord and updates the
   TransportTransaction — must be atomic with the rest of that persist operation (same
   @Transactional boundary), not a separate step that could get out of sync.
2. Do NOT increment stock for transactions that get CANCELLED or any non-COMPLETED
   path — only on the real completion path that already runs inside persist().
3. Do NOT modify anything related to the anomaly-detection WARN logic, the rounding
   logic, or any of the existing early-return guards (no scale found, no transaction
   found, null tare, null price, non-positive net weight) — this is additive, inserted
   alongside the existing persist logic, not a replacement of any existing behavior.
4. Add regression tests in WeighingPersistenceServiceTest: completing a weighing
   increases the grain type's currentStock by exactly netWeightKg; a CANCELLED or
   non-persisted path does not change currentStock; run multiple completions for the
   same grain type and confirm currentStock accumulates correctly (not overwritten).
5. Consider (and explicitly report your decision on) whether this could affect the
   Milho scarcity-alert demo scenario over time — since seed_demo_data.py and demo.py
   run several Milho weighings, currentStock will grow across a long demo session,
   meaning margin could eventually drop and the scarcity alert could disappear. This is
   actually a GOOD demonstration of realistic business behavior (matches what the user
   originally wanted to observe), not a bug — just make sure existing tests that assert
   a fixed margin/scarcity value for Milho (e.g. the 19.25% example in the README, or
   any test asserting scarcity-alerts contains Milho at boot) are based on the
   FRESH-BOOT state (before any weighings run), not broken by accumulated stock from a
   long-running demo session. Verify this doesn't break any existing test given tests
   run against a fresh H2 instance per test class.
6. Run the full test suite (./mvnw test) and confirm everything passes.
7. Manually verify live: note the Milho currentStock (2000kg) and margin (19.25%) at
   fresh boot, run a few real Milho weighings via the demo scripts or manually, confirm
   currentStock increases by the net weight of each, and confirm the margin recalculates
   lower as stock grows (and that the scarcity alert can disappear once margin drops
   below the 0.18 threshold, if enough stock accumulates).
8. Update the README: add a short, factual note near "Cálculo de margem e custo"
   explaining that currentStock now increases automatically as real weighings complete
   (grain physically arriving at the dock), reflecting the "quantidade disponível na
   doca" language in the original spec, and that this is why the scarcity alert is
   dynamic over the life of a running instance rather than a fixed demo fact. Keep the
   existing Milho example accurate — note that its 19.25%/2000kg figures describe the
   fresh-boot state, and will change as weighings for Milho are processed.

This is a real business-logic correction, not cosmetic — treat with the same rigor as
the CANCELLED-status fix (full test coverage, live verification, clear README
documentation).
[decisão sobre o req. 5: comportamento intencional e desejável — nenhum teste fixa
19,25%/Milho-no-boot, então a acumulação não quebra a suíte; apenas o README precisou
da ressalva de "estado de boot inicial". Verificação ao vivo confirmou: estoque sobe
exatamente pelo netWeightKg de cada pesagem, margem cai de 0,1925 para 0,05 e o Milho
sai do alerta de escassez assim que a margem fica abaixo de 0,18.]
```

---

## Padrão recorrente ao longo de todos os épicos

Ciclo repetido em praticamente toda story:
1. `bmad-create-story` (às vezes com correções de escopo antes de aprovar)
2. `bmad-dev-story`
3. Revisão manual do código gerado (pedindo para mostrar arquivos completos antes de aprovar)
4. `bmad-code-review` (ocasionalmente multi-agente para stories críticas — 1.5, 2.2, 3.2)
5. Teste manual via PowerShell (`Invoke-RestMethod`) contra a aplicação rodando
6. Commit seguindo Conventional Commits, referenciando a story
7. Atualização do `sprint-status.yaml`

Trocas de modelo pontuais: Sonnet 5 como padrão; Opus 4.8 usado especificamente para o code-review da Story 2.2 (encontrou uma fragilidade de teste que passava "por acidente" sem realmente validar o AC).
