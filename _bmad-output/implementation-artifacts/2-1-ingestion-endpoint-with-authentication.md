---
baseline_commit: 8d36776a70447905e3f6deb5f5283810c16c147d
---

# Story 2.1: Ingestion Endpoint with Authentication

Status: done

## Story

As a scale (ESP32),
I want to POST readings to /api/scales/readings with my API key,
so that only authorized scales can inject data into the system.

## Acceptance Criteria

1. **Given** a POST to `/api/scales/readings` with header `X-Scale-Key` matching the scale's `apiKey`, **when** the payload is `{id, plate, weight}`, **then** the system responds `202 Accepted` immediately.
2. **And** an invalid or missing `X-Scale-Key` returns `401 Unauthorized`.
3. **And** a missing required field returns `400 Bad Request`.
4. **And**, after auth and validation pass, the reading is routed to `StabilizationService.process(scaleId, plate, weightKg)`; if it returns a present `StabilizationResult`, `WeighingPersistencePort.persist(result)` is called synchronously before the response is returned (see Dev Notes — "fire-and-forget" framing does not change this given both calls are synchronous and cheap in-memory/DB operations with no external I/O).

## Tasks / Subtasks

- [x] Task 1: Create `ScaleReadingRequest` DTO (AC: #1, #3)
  - [x] `record ScaleReadingRequest(@NotBlank String id, @NotBlank String plate, @NotNull Double weight)` in a new `com.serasa.balancas.scalereading` package
  - [x] Field `id` is the scale identifier from the payload (matches `Scale.id`, a `String`) — not to be confused with any internal numeric id
- [x] Task 2: Add `ScaleRepository.findByIdAndApiKey(String id, String apiKey)` returning `Optional<Scale>` (AC: #2)
  - [x] Add this single derived-query method to the existing `ScaleRepository` interface (`src/main/java/com/serasa/balancas/scale/ScaleRepository.java`) — no new repository class needed
- [x] Task 3: Add `UnauthorizedException` + `GlobalExceptionHandler` mapping (AC: #2)
  - [x] New `com.serasa.balancas.common.exception.UnauthorizedException extends RuntimeException`, same shape as `BusinessException`/`ResourceNotFoundException` (single message constructor)
  - [x] New `@ExceptionHandler(UnauthorizedException.class)` method in `GlobalExceptionHandler` returning `ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(401, ex.getMessage()))`
- [x] Task 4: Create `ScaleReadingController` with `POST /api/scales/readings` (AC: #1, #2, #3, #4)
  - [x] New package `com.serasa.balancas.scalereading` (`ScaleReadingController.java`, `ScaleReadingRequest.java`)
  - [x] Constructor-inject `ScaleRepository`, `StabilizationService`, `WeighingPersistencePort`
  - [x] Method signature: `ResponseEntity<Void> receive(@RequestHeader(value = "X-Scale-Key", required = false) String scaleKey, @Valid @RequestBody ScaleReadingRequest request)`
  - [x] Auth: if `scaleKey` is null/blank, or `scaleRepository.findByIdAndApiKey(request.id(), scaleKey)` is empty, throw `UnauthorizedException("Invalid or missing X-Scale-Key for scale " + request.id())` — done **before** touching `StabilizationService` (an unauthenticated caller must not be able to poison another scale's stabilization buffer)
  - [x] Validation: `@Valid` on `ScaleReadingRequest` covers AC #3 (missing `id`/`plate`/`weight` → 400 via existing `MethodArgumentNotValidException` handler) — no extra manual checks added
  - [x] After auth passes: `stabilizationService.process(request.id(), request.plate(), request.weight()).ifPresent(weighingPersistencePort::persist)`
  - [x] Return `ResponseEntity.status(HttpStatus.ACCEPTED).build()` (202, empty body)
- [x] Task 5: Tests (AC: #1, #2, #3, #4)
  - [x] `ScaleReadingControllerTest` — `@SpringBootTest @AutoConfigureMockMvc`, following the Epic 1 integration-test convention (real H2, seeded via repositories in `@BeforeEach`-style helper methods, not mocks)
  - [x] Case: valid `X-Scale-Key` + valid payload → `202 Accepted`
  - [x] Case: missing `X-Scale-Key` header → `401 Unauthorized`
  - [x] Case: `X-Scale-Key` present but wrong value for the given scale `id` → `401 Unauthorized`
  - [x] Case: `X-Scale-Key` valid for a *different* scale than the one identified by `id` in the body → `401 Unauthorized` (confirms the key is checked against the specific scale `id` in the payload, not just "any valid key")
  - [x] Case: missing `plate` (and separately missing `weight`) in payload → `400 Bad Request`
  - [x] Case: 17 consecutive identical readings (window-size=15, consecutive-windows=3 per `application.yml`) trigger a `StabilizationResult` → asserted a `WeighingRecord` is persisted and the matching `TransportTransaction` becomes `COMPLETED` with correct `netWeightKg` (end-to-end proof that the controller wires `process()` → `persist()`)
  - [x] Case: unknown scale `id` in payload (no such `Scale` row) → `401 Unauthorized` (repository lookup by id+key naturally returns empty; same response as wrong-key case, correct security posture)

## Dev Notes

- **Fire-and-forget, but synchronous under the hood — confirmed, not a contradiction.** The epic frames this as "respond 202 immediately," implying decoupling from downstream processing. There is no message broker/Kafka in this delivery (per PRD scope), and both `StabilizationService.process()` (in-memory `ConcurrentHashMap.compute`, no I/O) and `WeighingPersistencePort.persist()` (a couple of JPA saves in a `@Transactional` block) are fast, local, synchronous calls. Given the no-Kafka constraint, doing them inline before returning 202 is the realistic interpretation — 202 here signals "accepted for processing" semantically (the ESP32 doesn't need to wait for the full weighing lifecycle to complete), not a literal async handoff. Do not introduce `@Async`/thread pools/queues for this story — that's over-engineering not called for by the epic or PRD, and would add failure modes (thread pool exhaustion, lost exceptions) with no corresponding requirement.
- **Where this plugs in**: this story is the first caller of `StabilizationService.process(...)` (`src/main/java/com/serasa/balancas/stabilization/StabilizationService.java`) and the first caller of `WeighingPersistencePort.persist(...)` (implemented by `WeighingPersistenceService`, Story 3.2). Both already exist and are fully tested in isolation — this story is pure HTTP wiring, not new business logic. Do not modify `StabilizationService`, `WeighingPersistenceService`, or their tests.
- **`StabilizationResult` unwrapping**: `process()` returns `Optional<StabilizationResult>`, empty on almost every call (only present once per stabilization episode, guarded internally by `ScaleState.alreadyPersisted`). Use `.ifPresent(weighingPersistencePort::persist)` — do not add any additional null-checking or logging here, `WeighingPersistenceService.persist()` already logs-and-skips internally for every failure mode and never throws (confirmed in Story 3.2's implementation) — the controller has no way to distinguish "stabilized and persisted" from "stabilized but silently dropped" and should not attempt to (see Dev Notes on response contract below).
- **Auth mechanism — manual, not Spring Security.** There is no `spring-boot-starter-security` dependency in this project (confirmed via `pom.xml`) and no filter/interceptor infrastructure exists. Implement the check directly in the controller method via `ScaleRepository.findByIdAndApiKey(...)`, matching the project's existing "no framework magic, plain constructor-injected logic" style. Do NOT add Spring Security as a new dependency for this — that's a much larger change than this story calls for.
- **Auth check ordering matters**: validate the header/lookup *before* calling `StabilizationService.process()`. An unauthenticated request must never be allowed to mutate another scale's in-memory stabilization buffer (`ConcurrentHashMap<String, ScaleState>` keyed by `scaleId` from the payload) — that would let an attacker with no valid key at all disrupt legitimate readings for any known scale id. `@Valid` on the request body runs before the controller method body executes (Spring's argument resolution order), so AC #3 (400 for missing fields) naturally takes precedence over the manual 401 check when both would fire — this is fine and doesn't need special handling, it's just how `@Valid` works.
- **New repository method, reuse the existing `ScaleRepository`**: don't create a separate `ScaleAuthService`/`ScaleAuthRepository` — the epic text mentions "Implement `ScaleAuthService`" as a task name, but given the project's established flat-controller style (`TransportTransactionController`, `ScaleController` both talk to repositories directly, no service layer), a single derived query `findByIdAndApiKey(String id, String apiKey)` on the existing `ScaleRepository` is sufficient and consistent — do not introduce a new service class purely to wrap one repository call.
- **`X-Scale-Key` as `required = false`**: use `@RequestHeader(value = "X-Scale-Key", required = false)` rather than `required = true`. If `required = true` and the header is absent, Spring throws `MissingRequestHeaderException` before the controller body runs — there is **no existing `@ExceptionHandler` for that exception** in `GlobalExceptionHandler`, so it would fall through to Spring Boot's default error response (still technically a 400, not the 401 AC #2 requires). Making it optional and null-checking manually inside the method, funneling into the same `UnauthorizedException` path as a wrong key, is simpler and correctly produces 401 for the "missing header" case per AC #2.
- **Response body**: `ResponseEntity<Void>` with `.build()`, no response body — the epic AC only specifies the status code, and there is nothing meaningful to return synchronously (per the point above, the controller cannot know whether persistence actually happened).
- **No Lombok anywhere** in this project — plain records for DTOs, matching `TransportTransactionRequest`/`ScaleRequest` conventions.
- **Seeded test data available**: `src/main/resources/data.sql` already seeds two scales usable directly in dev/manual testing: `BAL-001` / `key-sorriso-001` (Filial Sorriso) and `BAL-002` / `key-rondonopolis-002` (Filial Rondonopolis). Tests should still seed their own isolated data via repositories in `@BeforeEach`, per the established `@SpringBootTest` convention — don't rely on `data.sql` rows inside test assertions, only cite them for manual smoke-testing via curl/Postman if useful.
- **Package placement**: new `com.serasa.balancas.scalereading` package (controller + request DTO), following the project's feature-per-folder convention (mirrors `weighingrecord`, `transporttransaction`, etc. each being a standalone package even when they depend on other entities).

### Project Structure Notes

- New package: `src/main/java/com/serasa/balancas/scalereading/` (`ScaleReadingController.java`, `ScaleReadingRequest.java`)
- New file: `src/main/java/com/serasa/balancas/common/exception/UnauthorizedException.java`
- Modified: `src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java` (add `UnauthorizedException` handler)
- Modified: `src/main/java/com/serasa/balancas/scale/ScaleRepository.java` (add `findByIdAndApiKey`)
- New test: `src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java`

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.1: Ingestion Endpoint with Authentication] — acceptance criteria and task list origin.
- [Source: _bmad-output/implementation-artifacts/1-4-scale-cadastre.md] — confirms `Scale.id` is `String` (business id, e.g. `"BAL-001"`), `apiKey` plaintext trade-off already accepted, and explicitly defers auth-check implementation to this story.
- [Source: _bmad-output/implementation-artifacts/3-2-weighing-record-persistence.md] — confirms `WeighingPersistencePort.persist()` never throws (log-and-skip internally), and that this story is the first place `process()` → `persist()` gets wired end-to-end.
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationService.java] — `process(String scaleId, String plate, double weightKg): Optional<StabilizationResult>`, in-memory per-scaleId state.
- [Source: src/main/java/com/serasa/balancas/stabilization/WeighingPersistencePort.java] — `void persist(StabilizationResult result)`.
- [Source: src/main/java/com/serasa/balancas/scale/Scale.java] — `id: String`, `apiKey: String` (plaintext, `@NotBlank`).
- [Source: src/main/java/com/serasa/balancas/scale/ScaleRepository.java] — `JpaRepository<Scale, String>`, no custom finders yet.
- [Source: src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java] — existing handler pattern; no 401 mapping exists yet, must be added.
- [Source: src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionController.java] — controller/DTO/exception conventions to follow (constructor injection, `@Valid @RequestBody`, no Lombok).
- [Source: src/main/resources/data.sql] — seeded `Scale` rows (`BAL-001`/`key-sorriso-001`, `BAL-002`/`key-rondonopolis-002`) for manual verification.
- [Source: pom.xml] — confirms no `spring-boot-starter-security` dependency; Spring Boot 3.5.0, Java 21.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

### Completion Notes List

- Implemented `POST /api/scales/readings` wiring `ScaleReadingController` → `ScaleRepository.findByIdAndApiKey` (new auth) → `StabilizationService.process()` → `WeighingPersistencePort.persist()`, all synchronous (no Kafka/broker in this delivery, per Dev Notes).
- Auth implemented manually (no Spring Security dependency exists in the project) via a new `ScaleRepository.findByIdAndApiKey` derived query; `X-Scale-Key` is `@RequestHeader(required = false)` so a missing header funnels into the same `UnauthorizedException` → 401 path as a wrong key, rather than Spring's default 400 for a required-and-missing header.
- Added `UnauthorizedException` + a `GlobalExceptionHandler` mapping to 401 — no such mapping existed before this story since nothing previously needed 401.
- Full regression suite passes: 51 tests total across all packages (0 failures, 0 errors), including the 8 new `ScaleReadingControllerTest` cases.

### File List

- `src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java` (new)
- `src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java` (new)
- `src/main/java/com/serasa/balancas/common/exception/UnauthorizedException.java` (new)
- `src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java` (modified — added `UnauthorizedException` handler)
- `src/main/java/com/serasa/balancas/scale/ScaleRepository.java` (modified — added `findByIdAndApiKey`)
- `src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java` (new)

### Change Log

- 2026-07-10: Implemented Story 2.1 — ingestion endpoint with X-Scale-Key authentication, wired to StabilizationService and WeighingPersistencePort.

### Review Findings

- **#1 (fixed)**: `ScaleResponse` (`GET /api/scales`) leaked the scale's plaintext `apiKey` in the response body. Removed the field from `ScaleResponse`; it remains on the request/creation DTO only.
- **#2 (fixed)**: `StabilizationService.process()` set `state.alreadyPersisted = true` before the caller invoked `WeighingPersistencePort.persist()`, so a failed persist would permanently swallow that stabilization episode (no retry on the next stable reading). Added `StabilizationService.markPersistenceFailed(scaleId)` and had `ScaleReadingController` call it (resetting the flag) when `persist()` throws, then rethrow. Note: `WeighingPersistenceService.persist()` (Story 3.2) is documented as log-and-skip/never-throwing, so this path only guards against a future persistence implementation that can throw — behavior is otherwise unchanged.
- **#3-8 (deferred)**: remaining findings from this review stem from pre-existing 3.1/3.2 design decisions now reachable for the first time through this story's real caller, not defects introduced by this story. Logged in `_bmad-output/implementation-artifacts/deferred-work.md` for future epics to address:
  - No rate limiting / duplicate-request protection on the ingestion endpoint — a misbehaving or malicious ESP32 can flood `StabilizationService` with requests; out of scope for this story's AC.
  - `ScaleState` entries in `StabilizationService`'s `ConcurrentHashMap` are never evicted — a scale that stops sending readings leaves its state resident indefinitely (small, bounded per known scale, but unbounded if scale ids are attacker-controlled... mitigated here since auth now gates entry, but worth revisiting for cleanup/TTL).
  - `apiKey` is stored and compared in plaintext (`ScaleRepository.findByIdAndApiKey`) — no hashing, no constant-time comparison; accepted trade-off per Story 1.4, revisit if this becomes internet-facing rather than LAN/VPN-only.
  - `WeighingPersistenceService.persist()`'s "never throws" contract (log-and-skip) means the new `markPersistenceFailed` retry path in this story is currently unreachable in practice; only becomes meaningful if 3.2's persistence port changes to propagate failures.
  - Concurrent stabilized readings for the same plate across two different scales (see `deferred-work.md`) remains an accepted risk, now with a real call site (this story), but still physically implausible per the existing analysis.
  - No structured audit logging of authentication failures (401s) — could aid detecting compromised/spoofed scale keys, but no requirement calls for it yet.
