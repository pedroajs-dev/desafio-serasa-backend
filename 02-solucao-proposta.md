# Solução Proposta — Desafio Serasa (Backend)

Este documento descreve as decisões de arquitetura e as justificativas para o desafio técnico. Serve como contexto para implementação (via Claude Code) e como base para o documento final de entrega.

Stack: **Java + Spring Boot**, **Postgres**, **Kafka-compatible (Redpanda)**, Docker Compose.

---

## 1. Modelo de domínio (cadastros)

- **Filial** — id, nome, localização.
- **TipoGrao** — id, nome, precoCompraPorTonelada.
- **Caminhao** — id, placa, **tara** (a tara é atributo do caminhão, não da balança — cada veículo tem seu próprio peso vazio).
- **Balanca** — id, filialId, apiKey (para autenticação, ver seção 6).
- **TransacaoTransporte** — id, caminhaoId, tipoGraoId, filialId, status (EM_ROTA, NA_DOCA, PESANDO, FINALIZADA), dataInicio, dataFim, pesoBrutoKg, pesoLiquidoKg, custoCarga.

Ponto-chave: a `TransacaoTransporte` já existe **antes** da pesagem (o caminhão sai da filial com o tipo de grão definido). A leitura estabilizada da balança **fecha** essa transação, não a cria do zero.

---

## 2. Arquitetura de ingestão

```
ESP32 (balança) --HTTP POST--> /api/balancas/leituras
                                        |
                              [valida payload + autentica balança]
                                        |
                              Producer Kafka (tópico: leituras-balanca)
                              chave de partição = balancaId
                                        |
                        Kafka/Redpanda (N partições, ordena por chave)
                                        |
                          Consumer: EstabilizacaoService
                          (mantém buffer/janela em memória por balancaId)
                                        |
                    Ao detectar estabilidade → persiste Pesagem + fecha TransacaoTransporte
```

### Por que Kafka (Redpanda)

- O protocolo é fire-and-forget vindo de N balanças simultâneas — Kafka absorve o burst sem acoplar o ESP32 à disponibilidade do processamento.
- **Particionamento por `balancaId` garante ordem das leituras daquela balança** dentro da partição, o que é pré-requisito para o algoritmo de janela deslizante funcionar corretamente sem lock manual entre threads.
- Escala horizontalmente: mais partições = mais consumers em paralelo, sem reescrever lógica de concorrência.
- Desacopla ingestão (deve ser rápida, responder 202 imediatamente) do processamento (que pode levar mais tempo, ter retry, etc).

### Endpoint de ingestão

`POST /api/balancas/leituras`

Payload mínimo exigido pelo desafio (contrato obrigatório, sempre suportado):

```json
{ "id": "BAL-001", "plate": "ABC1D23", "weight": 34521.4 }
```

Payload estendido, usado pelo simulador de ESP32 que acompanha a solução — `seq` e `timestamp` são **opcionais e retrocompatíveis** (o endpoint funciona normalmente sem eles, caindo no comportamento padrão descrito abaixo):

```json
{ "id": "BAL-001", "plate": "ABC1D23", "weight": 34521.4, "seq": 4821, "timestamp": "2026-07-10T14:32:01.123Z" }
```

> Justificativa: o protocolo dado pelo enunciado não inclui identificador de leitura, o que é uma limitação real do fire-and-forget original. Em vez de só apontar isso como sugestão de melhoria no papel, o simulador de ESP32 desta solução já demonstra na prática como esses campos opcionais resolveriam idempotência e ordenação — sem quebrar o contrato original especificado no desafio.

- Responde **202 Accepted** imediatamente após publicar no Kafka (não espera o processamento).
- Validação mínima síncrona: balança existe e está autenticada (ver seção 6), payload bem formado.
- Se `seq` estiver presente e já tiver sido processado para aquela `balancaId`, a leitura é descartada como duplicata (idempotência). Se `seq` não vier, o endpoint segue o comportamento padrão (confia na ordem de chegada via particionamento Kafka).

---

## 3. Estratégia de estabilização

### Parâmetros (calibráveis via `application.yml`)

- Tamanho da janela: `N = 15` leituras (~1,5s a 100ms/leitura).
- Threshold de desvio padrão: `σ_max = 5kg` (valor de referência — depende da precisão real do sensor).
- Confirmações consecutivas: `M = 3` janelas seguidas dentro do threshold.

### Algoritmo

Estado mantido em memória (`ConcurrentHashMap<balancaId, EstadoBalanca>`) no consumer:

```
estado por balancaId: {
  buffer: Deque<Leitura>          // capacidade N, FIFO
  janelasEstaveisConsecutivas: Int
  jaPersistiu: Boolean            // trava contra duplicar persistência na mesma pesagem
}

ao receber leitura (balancaId, placa, peso):
  estado = obterOuCriarEstado(balancaId)

  se peso ≈ 0 (ex: < 50kg):
    // caminhão saiu ou ainda não subiu na balança
    resetarEstado(estado)
    return

  estado.buffer.add(leitura)
  se estado.buffer.size < N: return   // ainda enchendo a janela
  se estado.buffer.size > N: estado.buffer.removeFirst()

  desvioPadrao = calcularStdDev(estado.buffer.pesos)

  se desvioPadrao <= σ_max:
    estado.janelasEstaveisConsecutivas += 1
  senão:
    estado.janelasEstaveisConsecutivas = 0

  se estado.janelasEstaveisConsecutivas >= M e não estado.jaPersistiu:
    pesoEstabilizado = media(estado.buffer.pesos)
    persistirPesagem(placa, balancaId, pesoEstabilizado)
    estado.jaPersistiu = true
```

### Concorrência sem Kafka (decisão final de implementação)

Dado que a entrega final usa processamento síncrono em memória (sem Kafka — ver `03-plano-execucao-sem-docker.md`), a concorrência entre múltiplas balanças enviando simultaneamente é resolvida assim:

- Estado: `ConcurrentHashMap<String balancaId, EstadoBalanca>`.
- Toda atualização do estado de uma balança passa por `map.compute(balancaId, (id, estadoAtual) -> { ...atualiza buffer, calcula desvio padrão, decide persistir... return novoEstado; })`.
- `compute()` do `ConcurrentHashMap` já garante atomicidade **por chave** — duas requisições da mesma balança quase simultâneas não corrompem o buffer uma da outra, e balanças diferentes não bloqueiam entre si (cada uma só trava a própria entrada no mapa durante a atualização, não o mapa inteiro).
- Isso elimina a necessidade de `synchronized` manual ou locks explícitos — o mesmo resultado prático que o particionamento por `balancaId` do Kafka entregaria, só que dentro de uma única instância JVM (aceitável para esta entrega; documentado como trade-off no README).

### Por que essas decisões

1. **Janela + desvio padrão, e não delta entre leituras consecutivas**: ruído pontual do sensor (1 spike) não deve travar a detecção nem gerar falso positivo — o desvio padrão sobre uma janela é mais robusto a outliers isolados.
2. **M janelas consecutivas estáveis, não apenas 1**: evita persistir um peso num platô momentâneo que ainda vai oscilar (ex: caminhão ajustando posição sobre a balança).
3. **Trava `jaPersistiu`**: sem ela, cada leitura subsequente com o caminhão parado geraria nova "detecção de estabilidade" e tentaria persistir de novo. Reseta quando o peso volta a ~0 (caminhão saiu da balança).
4. **Por que o estado pode viver em memória local (não Redis)**: como o Kafka já particiona por `balancaId` e cada partição é consumida por exatamente uma instância por vez, a consistência do buffer por balança já vem garantida pelo próprio Kafka — não há necessidade de estado compartilhado externo, a menos que se precise sobreviver a restart do consumer sem perder o buffer em andamento (trade-off documentado como possível evolução).

---

## 4. Cálculo de margem de lucro

Regra: margem varia entre 5% (mínima) e 20% (máxima), inversamente proporcional à quantidade disponível do grão na doca.

Abordagem: interpolação linear entre um estoque de referência mínimo e máximo por tipo de grão (configurável por `TipoGrao`):

```
margem = margemMax - (margemMax - margemMin) * min(1, estoqueAtual / estoqueReferenciaMax)
```

- Estoque baixo (perto de 0) → margem tende a 20%.
- Estoque no ou acima do valor de referência máximo → margem tende a 5%.

> Esta é uma interpretação razoável de uma regra ambiguamente especificada — documentar explicitamente a fórmula escolhida no README é mais importante do que a fórmula em si.

---

## 5. Relatórios sugeridos

- Custo total de compra por tipo de grão / período.
- Margem média aplicada por tipo de grão (indicador de quão "escasso" cada grão esteve).
- Ranking de balanças por volume de pesagens.
- Tempo médio de pesagem por filial (dataFim - dataInicio da transação).
- Alertas de estoque escasso (grãos operando com margem próxima do teto de 20%).

---

## 6. Extras implementados

- **Autenticação da balança**: API key estática por balança (cadastro), validada via header (ex: `X-Balanca-Key`) no endpoint de ingestão antes de publicar no Kafka.
- **Idempotência/retentativa**: o protocolo dado pelo enunciado não inclui um identificador de leitura (sequence number), limitação real do protocolo original. Em vez de só documentar isso como gap, o endpoint aceita `seq`/`timestamp` como **campos opcionais retrocompatíveis** (ver seção 3) — o contrato obrigatório do desafio continua funcionando sem eles, mas o simulador de ESP32 desta solução já demonstra a idempotência funcionando na prática quando presentes.

---

## 7. Sugestões de expansão (diferencial, não implementado)

- **Buffer local no ESP32 com reenvio**: se o servidor cair, o ESP32 hoje perde silenciosamente os dados (sem ack). Um buffer local com reenvio ao reconectar mitigaria isso — não implementado nesta solução, pois exigiria alterar o firmware real do dispositivo, fora do escopo do desafio.
- **Dead-letter queue no Kafka**: leituras malformadas ou que falham no processamento vão para uma fila separada com alerta, em vez de descartadas silenciosamente.
- **Detecção de anomalia**: peso estabilizado muito fora da faixa esperada para aquele caminhão (tara + carga máxima teórica) dispara alerta de possível fraude ou erro de sensor.
- **Dashboard em tempo real**: consumer adicional do mesmo tópico Kafka alimentando um WebSocket, dando visibilidade ao vivo do processo de pesagem.
- **Particionamento por filial**: pensar em como a solução escalaria com múltiplas filiais — tópico por filial ou sharding lógico dentro do mesmo tópico.
- **mTLS ou rotação de API key** para as balanças, em vez de key estática.

---

## 8. Estratégia de testes

### 8.1 Testes unitários do `EstabilizacaoService` (prioridade — valida a lógica central isoladamente, sem subir a aplicação)

Casos a cobrir:

- Peso oscilando continuamente (nunca dentro do threshold de desvio padrão) → nenhuma pesagem deve ser persistida.
- Peso estabiliza dentro da janela (N leituras, M confirmações consecutivas) → persiste com o peso médio correto da janela.
- Peso estabiliza e depois cai a ~0 (caminhão saiu da balança) → estado da balança reseta, pronta para o próximo caminhão.
- Duas sessões consecutivas na mesma balança (caminhão A pesa e sai, caminhão B entra) → os buffers não podem se misturar entre sessões.
- Spike isolado (1 leitura de ruído) no meio de uma janela majoritariamente estável → não deve quebrar a detecção se o desvio padrão da janela como um todo ainda ficar dentro do threshold.
- Trava `jaPersistiu`: após persistir, leituras subsequentes com o caminhão ainda parado não devem gerar uma segunda persistência para a mesma sessão.

### 8.2 Simulador de ESP32 (teste de ponta a ponta, prova a solução funcionando com o protocolo real do desafio)

Script que dispara requisições HTTP a cada 100ms simulando um caminhão sobre a balança, com ruído decrescente (começa oscilando bastante e vai assentando até um valor fixo, reproduzindo o cenário descrito no enunciado). Usa o payload estendido (`seq`/`timestamp` opcionais) descrito na seção 3, para também exercitar a idempotência.

Pode ser implementado como teste de integração Java (`RestTemplate`/`WebClient` em loop com `Thread.sleep(100)`) ou como script standalone (Python/Node) fora do projeto, dependendo do que for mais rápido de gerar via Claude Code.

### 8.3 Teste manual pontual (debug rápido)

```bash
curl -X POST http://localhost:8080/api/balancas/leituras \
  -H "Content-Type: application/json" \
  -d '{"id":"BAL-001","plate":"ABC1D23","weight":34521.4}'
```

Conferir o resultado no H2 Console (`http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:mem:balancas`) após cada rodada, validando os campos persistidos (peso bruto, tara, peso líquido, custo da carga).

### 8.4 Ordem sugerida de execução

1. Testes unitários do `EstabilizacaoService` primeiro — validam a lógica central rápido, sem dependências externas.
2. Simulador de ESP32 — valida o fluxo completo de ponta a ponta com o protocolo real.
3. curl/H2 Console — usado pontualmente para debugar um caso específico que não bateu no simulador.

## 9. Trade-offs conscientes (documentar caso o prazo aperte)

- Redpanda em vez de Kafka+Zookeeper "tradicional": mesmo protocolo/API, footprint de disco e RAM muito menor, sobe em segundos — decisão pragmática para o ambiente de desenvolvimento, sem perda de fidelidade arquitetural.
- Caso o tempo aperte, a estabilização pode ser simplificada para um `ConcurrentHashMap` + scheduled check sem Kafka, perdendo a garantia de escalabilidade horizontal — documentar essa escolha explicitamente se for adotada.
