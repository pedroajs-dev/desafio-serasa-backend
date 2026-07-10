---
baseline_commit: 1093394
---

# Story 1.5: TransportTransaction Cadastre

Status: done

## Story

As an operator,
I want to open a transport transaction when a truck departs,
so that the system knows which grain type the truck is carrying before weighing.

## Acceptance Criteria

1. **Given** valid `truckId`, `grainTypeId`, `branchId`, **when** `POST /api/transactions` is called, **then** transaction is created with status `IN_TRANSIT` and `startDate` set.
2. **And** `GET /api/transactions/{id}` returns the full transaction.
3. **And** `PATCH /api/transactions/{id}/status` allows manual status update.

## Tasks / Subtasks

- [x] Task 1: Create `TransportTransaction` entity + repository + REST controller (AC: #1, #2)
  - [x] `TransportTransaction` JPA entity: `id` (Long), `truck` (`@ManyToOne`), `grainType` (`@ManyToOne`), `branch` (`@ManyToOne`), `status` (enum, not null), `startDate` (LocalDateTime, set on creation), `endDate` (LocalDateTime, nullable), `grossWeightKg` (nullable, populated later by Epic 3), `netWeightKg` (nullable, populated later by Epic 3), `loadCost` (nullable, populated later by Epic 3)
  - [x] `TransportTransactionRepository extends JpaRepository<TransportTransaction, Long>` — add a finder method for Epic 3's later use: `findByTruck_LicensePlateAndStatusNot(String licensePlate, TransactionStatus completedStatus)`, since Epic 3 Story 3.2 needs to look up "the open transaction for this truck plate" and the open transaction can be in ANY non-terminal status (`IN_TRANSIT`, `AT_DOCK`, `WEIGHING`) — this story does not implement automatic transitions between those intermediate states, so a fixed-status lookup would be fragile. Call it as `findByTruck_LicensePlateAndStatusNot(plate, TransactionStatus.COMPLETED)`.
  - [x] `TransportTransactionController`: `POST /api/transactions` (201 + body, validate truckId/grainTypeId/branchId exist), `GET /api/transactions/{id}` (200 + body, 404 if not found)
- [x] Task 2: Implement status enum: `IN_TRANSIT`, `AT_DOCK`, `WEIGHING`, `COMPLETED` (AC: #1, #3)
  - [x] Define `TransactionStatus` enum with these 4 values in that lifecycle order
- [x] Task 3: Expose PATCH endpoint for manual status transition (AC: #3)
  - [x] `PATCH /api/transactions/{id}/status` accepting `{status: "AT_DOCK"}` body, updates the transaction's status, returns 200 with updated body, 404 if transaction not found, 400 if status value is invalid

## Dev Notes

- **Critical ordering fact (per `02-solucao-proposta.md#1`):** the `TransportTransaction` exists BEFORE the weighing happens — the truck departs the branch with the grain type already defined, and the stabilized scale reading later CLOSES this transaction (sets status `COMPLETED`, `endDate`, `grossWeightKg`, `netWeightKg`, `loadCost`), it does not create a new transaction from scratch. This story only creates the "opening" side; Epic 3 Story 3.2 will implement the closing side.
- The repository lookup method by license plate + open status is a forward-looking requirement for Epic 3 (Story 3.2: "look up the open TransportTransaction for that truck" by plate) — add it now so Epic 3 doesn't need to modify this story's repository later. If multiple open transactions could theoretically exist for the same plate, assume for this delivery scope that only one is open at a time (business invariant, not enforced here).
- No automatic status progression logic in this story (e.g., nothing transitions `IN_TRANSIT` → `AT_DOCK` automatically) — that's manual via the PATCH endpoint, per AC #3. Automatic transition to `COMPLETED` happens later in Epic 3 when weighing stabilizes.
- Do not seed `data.sql` with transactions in this story unless useful for manual testing — the epics don't require it, keep scope tight (trucks/branches/grain types from prior stories are enough to POST a transaction manually).

### Project Structure Notes

- Same base package/layering convention as Stories 1.1–1.4.
- Requires `Truck` (Story 1.3), `GrainType` (Story 1.2), and `Branch` (Story 1.2) entities to already exist for the `@ManyToOne` relationships.
- This is the last story of Epic 1 — once done, epic-1 can be marked `done` and Epic 3 (Stabilization Engine) becomes unblocked per the sprint order (E1 → E3 → E2 → E4 → E5 → E6 → E7).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.5: TransportTransaction Cadastre]
- [Source: _bmad-output/planning-artifacts/epics.md#FR5]
- [Source: 02-solucao-proposta.md#1. Modelo de domínio (cadastros)] — transaction lifecycle and ordering relative to weighing
- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.2: Weighing Record Persistence] — downstream consumer of this entity/repository (do not implement in this story, only prepare the lookup method)

## Previous Story Intelligence

- Stories 1.2–1.4 established the CRUD conventions (entity/repository/controller/DTO validation) to follow here. `Truck`, `Branch`, and `GrainType` entities must exist (created in those stories) before this story's relationships compile.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

Full regression suite run via `./mvnw test` — all tests pass (BranchControllerTest, GrainTypeControllerTest, TruckControllerTest, ScaleControllerTest, TransportTransactionControllerTest, BalancasApplicationTests), no failures.

### Completion Notes List

- `TransportTransaction` created via constructor `(truck, grainType, branch)` that sets `status = IN_TRANSIT` and `startDate = now()` internally — matches AC #1 ("transaction is created with status IN_TRANSIT and startDate set") without relying on the client to supply those fields.
- `POST /api/transactions` validates `truckId`, `grainTypeId`, `branchId` each against their repositories; any missing reference throws `ResourceNotFoundException` (404), consistent with Scale's pattern from Story 1.4.
- `TransportTransactionRepository.findByTruck_LicensePlateAndStatusNot` added per Dev Notes for Epic 3 Story 3.2's future use; no automatic status transitions implemented in this story, per AC #3 — status change is manual-only via PATCH.
- No `data.sql` seed rows added for transactions, per Dev Notes (kept scope tight — existing truck/branch/grainType seed rows are sufficient to POST a transaction manually).
- Response DTO (`TransportTransactionResponse`) surfaces `truckId`/`grainTypeId`/`branchId` as flat ids rather than nested entities, consistent with `ScaleResponse`'s convention from Story 1.4.

### Code Review Findings and Fixes (2026-07-10)

A `code-review` pass surfaced 5 findings (1 CONFIRMED correctness, 1 PLAUSIBLE correctness, 1 CONFIRMED altitude, 1 PLAUSIBLE correctness, 1 CONFIRMED simplification). All 5 were fixed:

1. **Duplicate open transactions per truck (CRITICAL, blocked Epic 3 correctness).** `findByTruck_LicensePlateAndStatusNot` returns a single entity, but nothing stopped a truck from having more than one non-`COMPLETED` transaction — Epic 3 Story 3.2 would hit `IncorrectResultSizeDataAccessException` (unhandled → raw 500) the first time a truck had two open transactions. Fixed by adding a guard in `create()`: if the finder already returns an open transaction for the truck, `POST /api/transactions` now throws `BusinessException` → 400, guaranteeing the finder's single-result contract holds for Epic 3.
2. **(Same root cause as #1, folded into the fix above.)**
3. **Hand-rolled enum parsing in PATCH `/status`.** `StatusUpdateRequest.status` was `String`, manually parsed via `TransactionStatus.valueOf()` in a try/catch that rethrew as `BusinessException` — a pattern every future enum-bodied endpoint would have had to copy. Fixed by typing the field as `TransactionStatus` directly and adding a single `@ExceptionHandler(HttpMessageNotReadableException.class)` to `GlobalExceptionHandler`, so Jackson's deserialization failure is now caught centrally for all controllers, present and future.
4. **Non-numeric path variable on `{id}` endpoints leaked a raw 500.** `GET/PATCH /api/transactions/{id}` (and, retroactively, `GET /api/trucks/{id}`) had no handler for `MethodArgumentTypeMismatchException`. Fixed by adding a handler to `GlobalExceptionHandler` that returns 400 with the standard `ErrorResponse` shape — confirmed this also fixes the pre-existing gap in `TruckController` without touching that file.
5. **Repeated test boilerplate.** 4+ tests repeated an identical "build request, POST, parse JSON for id" block. Extracted into `createTruck()`/`createTransaction()` helpers in the test class — this also fixed a latent test-isolation bug: with the new duplicate-transaction guard, reusing seeded `truckId=1` across every test would have made later tests fail against earlier tests' leftover open transactions (tests share one H2 instance with no `@Transactional` rollback), so each test now creates its own truck via `TruckRepository` for guaranteed isolation.

Added a regression test (`returns400WhenTruckAlreadyHasOpenTransaction`) plus a companion (`allowsNewTransactionAfterPreviousOneCompleted`) proving the guard clears once the transaction is completed, and a `getByIdReturns400WhenIdIsNotNumeric` test for finding #4. Full suite re-run after fixes: 30/30 tests pass (up from 27). Manually verified all 5 fixes end-to-end against the running app (duplicate-transaction 400, non-numeric id 400 on both `/transactions` and `/trucks`, invalid-enum PATCH 400, and truck freed for a new transaction after PATCH to `COMPLETED`).

### File List

- src/main/java/com/serasa/balancas/transporttransaction/TransactionStatus.java (new)
- src/main/java/com/serasa/balancas/transporttransaction/TransportTransaction.java (new)
- src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRepository.java (new)
- src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRequest.java (new)
- src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionResponse.java (new)
- src/main/java/com/serasa/balancas/transporttransaction/StatusUpdateRequest.java (new, field retyped String → TransactionStatus during review fixes)
- src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionController.java (new, duplicate-open-transaction guard added during review fixes)
- src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java (modified — added HttpMessageNotReadableException and MethodArgumentTypeMismatchException handlers during review fixes)
- src/test/java/com/serasa/balancas/transporttransaction/TransportTransactionControllerTest.java (new, refactored with shared helpers and 3 new tests during review fixes)

## Change Log

- 2026-07-10: Implemented TransportTransaction cadastre (entity, status enum, repository with forward-looking finder for Epic 3, controller with create/get/patch-status endpoints, tests). Status set to review.
- 2026-07-10: Fixed all 5 code-review findings — duplicate-open-transaction guard, enum-typed PATCH DTO with centralized deserialization-error handling, MethodArgumentTypeMismatchException handling (retroactively fixes TruckController too), and de-duplicated test helpers. 30/30 tests pass; manually verified end-to-end.
- 2026-07-10: Manual final pass approved — duplicate-transaction rejection confirmed end-to-end with a clear error message; all review findings validated. Status set to done.
