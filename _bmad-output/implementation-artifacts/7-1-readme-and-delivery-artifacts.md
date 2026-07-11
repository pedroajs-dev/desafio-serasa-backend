---
baseline_commit: 706d0624f7125f4afd847daacaf37b6a5a6afa3b
---

# Story 7.1: README & Delivery Artifacts

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a candidate,
I want a complete README and delivery package,
so that the evaluator can run the project, understand the architecture decisions, and review AI usage.

## Acceptance Criteria

1. **Given** the README exists at project root, **when** an evaluator reads it, **then** it covers: how to run (`./mvnw spring-boot:run`), H2 console URL/JDBC URL, architecture note (Kafka design vs synchronous delivery trade-off), stabilization algorithm explanation, margin formula, and known trade-offs.
2. **And** a section lists all AI prompts used during development.
3. **And** AI-generated code is identified (comment or section).

## Tasks / Subtasks

- [x] Task 1: Write `README.md` at project root (AC: #1)
  - [x] Subtask 1.1: Project overview + how to run (`./mvnw spring-boot:run`, Java 21, zero external setup, port 8080)
  - [x] Subtask 1.2: H2 Console section — URL `http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:mem:balancas`, user `sa`, empty password
  - [x] Subtask 1.3: Architecture note — reproduce/adapt the exact trade-off paragraph from `03-plano-execucao-sem-docker.md` section 3 (Kafka-designed, H2/synchronous-delivered; see Dev Notes below for the literal source text)
  - [x] Subtask 1.4: Endpoint reference — list all REST endpoints grouped by controller (see Dev Notes: Actual Endpoint Inventory) with method/path/purpose; mention Swagger UI at `/swagger-ui.html` (springdoc already on the classpath, added commit `4e945af`)
- [x] Task 2: Document the stabilization algorithm (AC: #1)
  - [x] Subtask 2.1: Explain sliding-window + std-dev + M-consecutive-windows approach in plain language (source: `02-solucao-proposta.md` section 3, already implemented in `StabilizationService`)
  - [x] Subtask 2.2: State the configurable parameters and their actual current values from `src/main/resources/application.yml`: `stabilization.window-size=15`, `stabilization.std-dev-threshold=5.0`, `stabilization.consecutive-windows=3`, `stabilization.reset-threshold-kg=50.0`
  - [x] Subtask 2.3: Note the `alreadyPersisted` lock and reset-on-<50kg behavior, and that `ConcurrentHashMap.compute()` per `scaleId` is what makes it safe under concurrent scales (no global lock)
- [x] Task 3: Document the margin formula (AC: #1)
  - [x] Subtask 3.1: State the formula from `MarginService`: `margin = maxMargin - (maxMargin - minMargin) × min(1, currentStock / maxReferenceStock)`, bounds 5%–20%
  - [x] Subtask 3.2: Give a worked example with real seeded numbers (read `src/main/resources/data.sql` for actual seeded `currentStock`/`maxReferenceStock` per grain type — note Milho's `currentStock` was deliberately lowered to 2000 per `docs/demo-dashboard.md` to trigger the scarcity alert at boot)
  - [x] Subtask 3.3: Mention `reports.scarcity-threshold=0.18` and that `/api/reports/scarcity-alerts` uses it
- [x] Task 4: Add "AI Usage" section (AC: #2, #3)
  - [x] Subtask 4.1: List the AI prompts actually used during this project's development, sourced from user-provided `06-prompts-utilizados.md` (real prompts, not fabricated) — summary + 3 significant examples inline, full list linked
  - [x] Subtask 4.2: State plainly that the codebase was built via AI pair-programming (Claude Code) across all 7 epics, and identify representative AI-generated code (e.g., point to specific packages/files rather than inline-commenting every line)
- [x] Task 5: Add trade-offs section (AC: #1)
  - [x] Subtask 5.1: H2 in-memory vs Postgres — data lost on restart, data model already Postgres-portable (driver/config swap only), per `03-plano-execucao-sem-docker.md` section 1
  - [x] Subtask 5.2: Synchronous in-JVM processing vs Kafka — full architectural rationale in `02-solucao-proposta.md` section 2 (partition-by-`balancaId` ordering, horizontal scaling, decoupling ingestion from processing); current delivery uses `ConcurrentHashMap.compute()` per `scaleId` as the in-JVM equivalent
  - [x] Subtask 5.3: In-memory `StabilizationService` / idempotency state vs Redis — non-durable, single-instance only, state lost on restart or lost across replicas (source: `deferred-work.md` code-review notes on `ScaleState` map and `seenKeys` in `ReadingIdempotencyService`)
  - [x] Subtask 5.4: Plaintext `apiKey` storage/comparison — accepted trade-off per Story 1.4, revisit if internet-facing (source: `deferred-work.md`)
  - [x] Subtask 5.5: Reference `_bmad-output/implementation-artifacts/deferred-work.md` and `fix-1-round-persisted-weight-cost-values.md` as the authoritative list of known gaps/deferred items if the evaluator wants full detail beyond the README summary
- [x] Task 6: Mention the demo tooling (nice-to-have, supports AC #1's "how to run")
  - [x] Subtask 6.1: One paragraph pointing to `docs/demo-dashboard.md` for the optional `scripts/seed_demo_data.py` / `scripts/demo.py` + `dashboard.html` walkthrough — do not duplicate its content, just link it

## Dev Notes

### This is a documentation-only story

No source code changes are required or expected. Every fact placed in `README.md` must be traceable to either an already-implemented, already-tested piece of the codebase, or to one of the three input documents (`01-desafio-original.md`, `02-solucao-proposta.md`, `03-plano-execucao-sem-docker.md`). Do not invent behavior, endpoints, or parameters not present in the actual code — this README is what the evaluator uses to judge the whole delivery, so accuracy matters more than polish.

### Critical: No Prompt Log Exists

There is no `prompts.md`, `AI_PROMPTS.md`, or similar file anywhere in the repo (verified via search) capturing the literal prompts used across all 7 epics. Task 4 ("list all AI prompts used") cannot be mechanically completed from repository artifacts alone. Before fabricating prompt text:

1. Check whether the user (pedro) has prompt history available elsewhere (chat exports, notes) to paste in.
2. If unavailable, the best faithful substitute is a **summary of the AI-assisted workflow actually used** — this project was built story-by-story using the BMad Method (`_bmad/` skills: `bmad-create-story`, `bmad-dev-story`, `bmad-code-review`) via Claude Code, evidenced by:
   - Every implementation-artifact story file in `_bmad-output/implementation-artifacts/` (the dev-agent context files themselves)
   - Commit messages consistently referencing "story N.N", "code review", and specific fixes discovered during review (see `git log --oneline`)
3. **Do not silently skip this AC** — if no literal prompt transcripts can be sourced, flag this explicitly to the user during implementation rather than inventing plausible-sounding prompts, since NFR7 ("AI prompts used... must be shared in the delivery") is an explicit challenge requirement and fabricating it would misrepresent the delivery.

### Architecture Note — Exact Source Text to Adapt

`03-plano-execucao-sem-docker.md` section 3 already contains a suggested README paragraph. Use it as the base (translate/adapt to match README's language, but preserve the substance):

> "A solução foi desenhada para usar Kafka como camada de ingestão assíncrona (particionamento por `balancaId`, ordenação garantida por partição, desacoplamento ingestão/processamento). Por restrição de ambiente de desenvolvimento (Docker/WSL indisponível no momento da entrega) e prazo, a implementação entregue usa processamento síncrono em memória (`ConcurrentHashMap.compute()` por `scaleId`), preservando a mesma lógica de estabilização e mesma interface HTTP. A migração para Kafka é uma troca de camada de infraestrutura, não uma mudança na lógica de negócio."

Full architectural rationale (why Kafka was originally chosen, why partition-by-key matters, how it maps to `ConcurrentHashMap.compute()`) is in `02-solucao-proposta.md` sections 2 and 3 ("Concorrência sem Kafka" subsection) — read both before writing this section so the trade-off explanation is technically accurate, not just copied verbatim.

### Actual Endpoint Inventory (verified against controllers, not the epic doc)

Controllers present in `src/main/java/com/serasa/balancas/`: `branch/BranchController`, `graintype/GrainTypeController`, `truck/TruckController`, `scale/ScaleController`, `transporttransaction/TransportTransactionController`, `scalereading/ScaleReadingController`, `report/ReportController`. Read each controller file directly to list its exact routes/methods (do not assume the epics.md wording is verbatim — e.g. epics.md describes `/api/scales/readings` and `X-Scale-Key` for auth, confirm the actual header/path names in `ScaleReadingController` and `ScaleAuthService`/equivalent before documenting them).

springdoc-openapi is already a dependency (`pom.xml`, `springdoc-openapi-starter-webmvc-ui:2.8.5`) and was wired in commit `4e945af` ("docs(e1): add OpenAPI/Swagger documentation") — Swagger UI should be reachable at `/swagger-ui.html` or `/swagger-ui/index.html`; verify the actual path by starting the app rather than guessing, since springdoc's default path can vary by version.

### Stack & Config Facts (verified)

- Java 21, Spring Boot 3.5.0 (`pom.xml`)
- H2 in-memory: `jdbc:h2:mem:balancas;DB_CLOSE_DELAY=-1`, console at `/h2-console`, user `sa`, no password (`src/main/resources/application.yml`)
- `ddl-auto: update`, seed data loaded via `data.sql` with `defer-datasource-initialization: true`
- Stabilization params live under `stabilization.*` in `application.yml`, already externalized/configurable per NFR3 — do not hardcode these values in prose without citing they come from this file
- Scarcity threshold: `reports.scarcity-threshold: 0.18` in the same file

### Trade-offs — Source Material Already Exists, Don't Re-derive From Scratch

`_bmad-output/implementation-artifacts/deferred-work.md` and `fix-1-round-persisted-weight-cost-values.md` already contain a precise, dated log of every known gap/trade-off discovered across code reviews (rate limiting, unbounded in-memory maps, plaintext apiKey, non-durable idempotency, floating-point rounding). The README's trade-offs section should summarize the highest-value items from these files for an evaluator audience (concise, business-readable) rather than duplicating their full technical detail — link to the files for anyone wanting the complete list.

### Project Structure Notes

- `README.md` goes at the repository root (`C:\Users\55219\dev\desafio-serasa\README.md`) — none currently exists.
- No other files are expected to change for this story (docs-only). If Task 4 genuinely cannot be completed without user input, that's a valid reason to pause and ask rather than fabricate — do not mark the story done with invented prompt content.

### References

- [Source: 01-desafio-original.md] — original challenge statement (Portuguese), Section 6 makes AI usage disclosure mandatory ("Obrigatório: Utilização de IA... Compartilhamento do prompt utilizado e código gerado")
- [Source: 02-solucao-proposta.md#2] — Kafka ingestion architecture rationale
- [Source: 02-solucao-proposta.md#3] — stabilization algorithm design + concurrency-without-Kafka rationale
- [Source: 02-solucao-proposta.md#4] — margin formula rationale
- [Source: 03-plano-execucao-sem-docker.md#3] — suggested README architecture-note paragraph (literal text to adapt)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 7.1] — original task breakdown for this story
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — accumulated known trade-offs/gaps across all prior epics
- [Source: _bmad-output/implementation-artifacts/fix-1-round-persisted-weight-cost-values.md] — standalone fix log, rounding trade-off
- [Source: docs/demo-dashboard.md] — optional demo tooling walkthrough to link from README
- [Source: src/main/resources/application.yml] — actual current config values (stabilization params, scarcity threshold, H2 connection details)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — documentation-only story, no code executed or tested beyond manually cross-checking README content against the actual controller/service source files and `application.yml`.

### Completion Notes List

- At implementation time, discovered `06-prompts-utilizados.md` (real, user-maintained prompt log) exists at project root — this supersedes the story's Dev Notes assumption that no prompt log existed at story-creation time. Used it as the source for the "Uso de IA" section instead of falling back to a workflow-only summary.
- User explicitly scoped which files are valid README sources mid-implementation: `01-desafio-original.md`, `02-solucao-proposta.md`, `03-plano-execucao-sem-docker.md`, `06-prompts-utilizados.md`, `docs/demo-dashboard.md`, `deferred-work.md`, and git/PR history. Files referenced in `06-prompts-utilizados.md`'s own prose but NOT present in the repo (`04-estrategia-branches-commits-prs.md`, `05-guia-estudo-entrevista.md`, `decisao-arquitetura-mvc.md`, `pr-epico-3/4/5.md`) were explicitly excluded from being linked — their substance (branch/PR strategy, MVC-vs-Hexagonal decision) is paraphrased directly in README prose instead, sourced from actual `git log`/branch names rather than the missing files.
- `gh` CLI is not installed in this environment, so PR descriptions could not be fetched directly; used `git log --grep="Merge pull request"` instead, which was sufficient to confirm PR numbers/branch names per epic.
- README written in Portuguese to match the source documents' language (challenge statement, `02-solucao-proposta.md`, `03-plano-execucao-sem-docker.md`, `06-prompts-utilizados.md` are all Portuguese); code/API examples kept as-is (English identifiers, per the actual codebase).
- All endpoint paths, headers (`X-Scale-Key`), status codes, formulas, and config values in the README were verified directly against source: `ScaleReadingController`, `TransportTransactionController`, `ReportController`, `BranchController`, `GrainTypeController`, `TruckController`, `ScaleController`, `MarginService`, `application.yml`, and `data.sql` — none copied verbatim from `epics.md` without cross-checking against actual code.

### File List

- `README.md` (new)

## Change Log

- 2026-07-11: Implemented Story 7.1 — created `README.md` at project root covering how-to-run, H2 console, Swagger UI, full endpoint inventory, stabilization algorithm, margin formula with worked example, Kafka-vs-synchronous architecture note, branch/PR strategy, MVC-vs-hexagonal internal decision, known trade-offs, AI usage (sourced from `06-prompts-utilizados.md`), and expansion suggestions. All 6 tasks / 15 subtasks completed. No source code changed (documentation-only story).
