# Plano de execução — Rodando sem Docker/WSL (H2 + sem Kafka)

> Atualização: o Upstash Kafka foi descontinuado (desligado definitivamente em março/2025). A alternativa gerenciada viável (Confluent Cloud) exige cadastro de cartão de crédito. Dado o prazo curto, o caminho escolhido é **H2 em memória + sem Kafka** — zero cadastro, roda imediatamente com `./mvnw spring-boot:run`.

Este é o caminho definitivo para esta entrega, dado que o WSL não foi possível resolver a tempo. A aplicação roda 100% local, sem containers e sem serviços externos.

---

## 1. Banco de dados — H2 em memória

Zero setup, já vem embutido no Spring Boot. No `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:balancas;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    database-platform: org.hibernate.dialect.H2Dialect
```

Console web disponível em `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:balancas`) — útil para inspecionar as tabelas durante o desenvolvimento e testes.

> Nota para o README: dados em memória são perdidos a cada restart da aplicação. Isso é uma decisão consciente de simplificação para o ambiente de desenvolvimento/entrega — em produção, seria Postgres (o modelo de dados já está desenhado para isso, a troca é só de configuração/driver).

---

## 2. Estabilização sem Kafka

Substitui o producer/consumer Kafka por um serviço síncrono no próprio endpoint de ingestão, mantendo a **mesma lógica de janela deslizante e desvio padrão** já definida (ver `02-solucao-proposta.md`, seção 3) — só sem a camada de mensageria no meio:

```
ESP32 --HTTP POST--> /api/balancas/leituras
                            |
                  [valida payload + autentica balança]
                            |
                  EstabilizacaoService.processar(leitura)
                  (ConcurrentHashMap<balancaId, EstadoBalanca> em memória)
                            |
              Ao detectar estabilidade → persiste Pesagem + fecha TransacaoTransporte
```

- O endpoint continua respondendo rápido (não precisa ser fire-and-forget assíncrono de verdade, mas mantém a mesma interface HTTP simples).
- Concorrência entre balanças diferentes: `ConcurrentHashMap` já resolve, cada chave (`balancaId`) tem seu próprio estado, sem contenção entre balanças distintas.
- Concorrência **dentro da mesma balança** (múltiplas requisições quase simultâneas da mesma balança, pouco provável mas possível): sincroniza o acesso ao estado daquela balança especificamente (`synchronized` no bloco que atualiza o buffer daquela chave, ou usa `ConcurrentHashMap.compute()` que já é atômico por chave).

## 3. Documentar a decisão no README (importante)

Trecho sugerido para o README do projeto:

> **Nota de arquitetura**: a solução foi desenhada para usar Kafka como camada de ingestão assíncrona (ver `02-solucao-proposta.md` para o desenho completo, incluindo particionamento por `balancaId` e justificativa). Por restrição de ambiente de desenvolvimento (Docker/WSL indisponível no momento da entrega) e prazo, a implementação entregue usa processamento síncrono em memória (`ConcurrentHashMap`), preservando a mesma lógica de estabilização e mesma interface HTTP. A migração para Kafka é uma troca de camada de infraestrutura, não uma mudança na lógica de negócio.

Isso transforma a limitação de ambiente em um ponto positivo na entrevista: mostra que você projetou pensando na arquitetura ideal, entende exatamente onde ela se encaixaria, e soube adaptar pragmaticamente ao tempo disponível sem comprometer a qualidade da lógica central (que é o que mais pesa na avaliação).

---

## 4. Se quiser retomar Kafka depois (pós-entrega)

Quando o WSL for resolvido com calma, a migração de volta para Redpanda é direta: o `EstabilizacaoService` já teria a lógica pronta, só troca a chamada síncrona por um producer Kafka + consumer, usando o `docker-compose.yml` já preparado.

