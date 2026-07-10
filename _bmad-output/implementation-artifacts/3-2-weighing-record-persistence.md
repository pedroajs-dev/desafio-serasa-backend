# Story 3.2: Weighing Record Persistence

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want to persist a complete WeighingRecord and close the TransportTransaction when stability is detected,
so that the weighing data is durable and the transaction lifecycle is complete.

## Acceptance Criteria

1. **Given** stability is detected for a scaleId + plate, **when** the system looks up the open TransportTransaction for that truck, **then** a `WeighingRecord` is saved with: `grossWeightKg`, `tare` (from `Truck`), `netWeightKg`, `dateTime`, `scaleId`, `grainTypeId`, `loadCost` (FR16a).
2. **And** the `TransportTransaction` status is set to `COMPLETED` with `endDate` set.
3. **And** if no open transaction is found for that plate, the event is logged and skipped gracefully (no exception thrown, no record persisted).

## Tasks / Subtasks

- [x] Task 1: Create `WeighingRecord` entity + repository (AC: #1)
  - [x] `WeighingRecord` entity: `id` (Long, IDENTITY), `truck` (`@ManyToOne Truck`), `scale` (`@ManyToOne Scale`), `grainType` (`@ManyToOne GrainType`), `transportTransaction` (`@ManyToOne TransportTransaction`), `grossWeightKg` (Double), `tare` (Double), `netWeightKg` (Double), `loadCost` (Double), `dateTime` (LocalDateTime)
  - [x] `WeighingRecordRepository extends JpaRepository<WeighingRecord, Long>` — no custom queries needed yet (Epic 5 will add report queries later)
- [x] Task 2: Implement `WeighingPersistenceService implements WeighingPersistencePort` (AC: #1, #2, #3)
  - [x] Constructor-inject `TransportTransactionRepository`, `WeighingRecordRepository`, `ScaleRepository`
  - [x] `persist(StabilizationResult result)`:
    - [x] Look up the open transaction via `transportTransactionRepository.findByTruck_LicensePlateAndStatusNot(result.plate(), TransactionStatus.COMPLETED)`
    - [x] If `null` → log a warning (`scaleId`, `plate`) and return without throwing or persisting (AC #3)
    - [x] If found → compute `netWeightKg = result.stabilizedWeightKg() - transaction.getTruck().getTare()`
    - [x] Compute `loadCost = (netWeightKg / 1000.0) * transaction.getGrainType().getPurchasePricePerTon().doubleValue()` (FR16a)
    - [x] Look up `Scale` by `result.scaleId()` via `ScaleRepository.findById(...)` (should always exist — the reading was authenticated against it upstream; if somehow absent, treat like AC #3: log and skip rather than throw, since this is a fire-and-forget internal event, not an HTTP request)
    - [x] Build and save `WeighingRecord` (truck, scale, grainType, transportTransaction, grossWeightKg = result.stabilizedWeightKg(), tare = transaction.getTruck().getTare(), netWeightKg, loadCost, dateTime = now)
    - [x] Set `transaction.setGrossWeightKg(...)`, `setNetWeightKg(...)`, `setLoadCost(...)` on the `TransportTransaction` itself too (see Dev Notes — these fields already exist on the entity and are otherwise never populated)
    - [x] Set `transaction.setStatus(TransactionStatus.COMPLETED)`, `transaction.setEndDate(LocalDateTime.now())`, save
- [x] Task 3: Wire `WeighingPersistenceService` as the `@Service` implementation of `WeighingPersistencePort` (AC: #1)
  - [x] No controller/listener exists yet to call `StabilizationService.process(...).ifPresent(port::persist)` (Epic 2 not built) — this story stops at making the port callable and tested in isolation; Epic 2 will wire the actual call site
- [x] Task 4: Tests (AC: #1, #2, #3)
  - [x] `WeighingPersistenceServiceTest` — `@SpringBootTest` exercising the real H2 DB, matching the existing `*ControllerTest` integration-style convention (see Dev Notes)
  - [x] Case: open transaction exists → `WeighingRecord` saved with correct grossWeightKg/tare/netWeightKg/loadCost/scaleId/grainTypeId; `TransportTransaction` becomes `COMPLETED` with `endDate` set and its own gross/net/loadCost fields populated
  - [x] Case: no open transaction for the plate → no `WeighingRecord` persisted, no exception propagates, `WeighingRecordRepository.count()` unchanged
- [x] Task 5: Guard against non-positive `netWeightKg` (added post-review, same log-and-skip pattern as AC #3)
  - [x] If `netWeightKg <= 0` (e.g. bad tare/calibration causing `grossWeightKg < tare`) → log a warning (plate, scaleId, grossWeightKg, tare, netWeightKg) and return without persisting `WeighingRecord` or mutating `TransportTransaction`
  - [x] Regression test: `tare` (8500) greater than stabilized `grossWeightKg` (5000) → no `WeighingRecord` persisted, `TransportTransaction` stays `IN_TRANSIT` with `endDate`/gross/net/loadCost still null

## Dev Notes

- **Where this plugs in**: Story 3.1 (`com.serasa.balancas.stabilization`) built `StabilizationService.process(scaleId, plate, weightKg)` returning `Optional<StabilizationResult>`, and defined `WeighingPersistencePort` (interface only, unimplemented) as the seam for this story. `StabilizationResult` is `record StabilizationResult(String scaleId, String plate, double stabilizedWeightKg)`. This story implements that port — it does **not** touch `StabilizationService` itself, and there is still no caller wiring `process()` to the port (Epic 2's ingestion endpoint doesn't exist yet on this branch). Files: `src/main/java/com/serasa/balancas/stabilization/StabilizationService.java`, `StabilizationResult.java`, `WeighingPersistencePort.java`.
- **Package placement**: Follow the project's feature-per-folder convention (no separate `service`/`dto` layers — confirmed across Epic 1). Since `WeighingRecord` is a persistence concern tightly coupled to the stabilization flow (not a standalone cadastre with its own CRUD controller), place the new entity, repository, and service in a new `com.serasa.balancas.weighingrecord` package — mirroring how `transporttransaction` is its own package even though it references `truck`/`grainType`/`branch`.
- **No Lombok** anywhere in this project (confirmed via `pom.xml`) — plain classes with explicit getters/setters/constructors, matching `Truck`, `Scale`, `TransportTransaction`.
- **Entity field types must match what they reference** — reuse existing entities directly, don't re-invent:
  - `Truck.tare` is `Double` (`src/main/java/com/serasa/balancas/truck/Truck.java:25`).
  - `TransportTransaction` already has unused `grossWeightKg`, `netWeightKg`, `loadCost` fields (`Double`) — these exist on the entity today but nothing populates them yet. **Populate them when closing the transaction** even though the AC only explicitly calls out status + endDate — leaving them null while also writing the same values into `WeighingRecord` would be an inconsistent half-finished state, and Epic 5's `avg-weighing-duration`/reports work will likely read from `TransportTransaction` directly.
  - `GrainType.purchasePricePerTon` is `BigDecimal` — call `.doubleValue()` when computing `loadCost`, matching FR16a's formula: `loadCost = (netWeightKg / 1000) × purchasePricePerTon`.
  - `Scale.id` is a `String` (not `Long`) — `ScaleRepository extends JpaRepository<Scale, String>` (verify signature before calling `findById`).
- **Transaction lookup — reuse, don't reinvent**: `TransportTransactionRepository.findByTruck_LicensePlateAndStatusNot(String licensePlate, TransactionStatus completedStatus)` already exists (`src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRepository.java:7`) and is the exact method Story 1.5 built for the "is there an open transaction for this plate" check. It returns a plain `TransportTransaction` (nullable, **not** `Optional`) — null-check it directly, don't wrap in `Optional.ofNullable` unless it improves readability locally.
- **Graceful skip (AC #3) — no exception**: This is an internal event triggered by a background stabilization signal, not an HTTP request — there is no controller here to translate a `BusinessException`/`ResourceNotFoundException` into an HTTP error response (`GlobalExceptionHandler` pattern, `src/main/java/com/serasa/balancas/common/exception/`). Log via SLF4J (`LoggerFactory.getLogger(WeighingPersistenceService.class)`) at `warn` level and return — do not throw `BusinessException`/`ResourceNotFoundException` here, since nothing downstream would catch/render them.
- **Testing convention** (see `src/test/java/com/serasa/balancas/transporttransaction/TransportTransactionControllerTest.java` and siblings): Epic 1 tests are `@SpringBootTest @AutoConfigureMockMvc` integration tests hitting the real H2 in-memory DB (not mocked repositories). Story 3.1's `StabilizationServiceTest` was a plain JUnit 5 unit test instead, because that service has zero DB dependency. This story's service *does* touch the DB (3 repositories), so follow the Epic 1 pattern: a Spring-context test seeding a `Branch`/`GrainType`/`Truck`/`Scale`/`TransportTransaction` via their repositories, then asserting on `WeighingRecordRepository` and the reloaded `TransportTransaction` state. Do not introduce Mockito mocking here unless the Spring-context approach proves awkward — match what's already established.
- **Constructor injection, no Lombok, `@Service`** — mirror `StabilizationService`'s style (`src/main/java/com/serasa/balancas/stabilization/StabilizationService.java`): a plain constructor taking the three repositories, annotated `@Service`.
- **Known edge case, accepted for this delivery**: AC #3's log-and-skip when no open transaction is found for the plate is a data-loss point (the stabilized reading is dropped, not queued or retried). Approved as-is for this delivery — do not build retry/audit logic now. Flag it in Epic 7's README "suggestions for expansion" section as a future improvement (e.g. an orphaned-reading audit table).
- **`netWeightKg <= 0` guard** (added post-review): if `grossWeightKg < tare` (bad sensor calibration), the service logs a warning and skips persistence entirely — same pattern as the "no open transaction" and "no scale" cases. Confirmed as a deliberate guard, not left as silent negative-value persistence.
- **Uncommitted state**: Story 3.1's files (`stabilization/` package + its test) are on disk but **not yet committed** to git (`git status` shows them as untracked/modified alongside this story's planning-artifact edits). Don't assume a clean baseline — if a commit workflow is invoked, it will need to include 3.1's files as part of the story-cycle commit, not just 3.2's.

### Project Structure Notes

- New package: `src/main/java/com/serasa/balancas/weighingrecord/` (`WeighingRecord.java`, `WeighingRecordRepository.java`, `WeighingPersistenceService.java`)
- New test: `src/test/java/com/serasa/balancas/weighingrecord/WeighingPersistenceServiceTest.java`
- No new package for tests beyond the mirrored structure — consistent with every existing feature folder having a 1:1 test package under `src/test/java/com/serasa/balancas/<feature>/`.
- No changes required to `application.yml` or `BalancasApplication.java` for this story (those were touched in 3.1 for `@ConfigurationPropertiesScan`).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.2: Weighing Record Persistence] — acceptance criteria and task list origin.
- [Source: _bmad-output/planning-artifacts/epics.md#Requirements Inventory] — FR12 (persist WeighingRecord + close transaction), FR16a (loadCost formula).
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationService.java] — upstream `StabilizationResult` producer this story consumes.
- [Source: src/main/java/com/serasa/balancas/stabilization/WeighingPersistencePort.java] — the interface this story implements.
- [Source: src/main/java/com/serasa/balancas/transporttransaction/TransportTransaction.java] — target entity to close; note the currently-unused gross/net/loadCost fields.
- [Source: src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRepository.java] — existing lookup method to reuse.
- [Source: src/main/java/com/serasa/balancas/truck/Truck.java] — `tare` field source.
- [Source: src/main/java/com/serasa/balancas/graintype/GrainType.java] — `purchasePricePerTon` (BigDecimal) for loadCost.
- [Source: src/main/java/com/serasa/balancas/common/exception/] — existing exception/handler convention, deliberately *not* used for the AC #3 skip path.

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List

- `src/main/java/com/serasa/balancas/weighingrecord/WeighingRecord.java` (new)
- `src/main/java/com/serasa/balancas/weighingrecord/WeighingRecordRepository.java` (new)
- `src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java` (new)
- `src/test/java/com/serasa/balancas/weighingrecord/WeighingPersistenceServiceTest.java` (new)

### Review Findings

- [x] [Review][Patch] `persist()` performed two `save()` calls with no `@Transactional` boundary — partial failure could leave `WeighingRecord` committed while `TransportTransaction` stayed open. Fixed: added `@Transactional` to `persist()`. [src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java]
- [x] [Review][Patch] No null-guard before unboxing `grainType.getPurchasePricePerTon()` / `truck.getTare()` — an NPE would have violated the "never throws" contract of this fire-and-forget path. Fixed: added log-and-skip guards for both, matching the existing AC#3/Task 5 pattern. [src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java]
- [x] [Review][Patch] No test exercised the "no Scale found" skip branch. Fixed: added `skipsGracefullyWhenNoScaleFoundForScaleId`. [src/test/java/com/serasa/balancas/weighingrecord/WeighingPersistenceServiceTest.java]
- [x] [Review][Patch] No test exercised the exact `netWeightKg == 0` boundary. Fixed: added `skipsGracefullyWhenNetWeightIsExactlyZero`. [src/test/java/com/serasa/balancas/weighingrecord/WeighingPersistenceServiceTest.java]
- [x] [Review][Patch] `epics.md` marked "Implement persistence call inside StabilizationService upon stability detection" as done, contradicting Task 3's own statement that the call site is not yet wired (Epic 2 pending). Fixed: unchecked the box and added a note pointing to Epic 2. [_bmad-output/planning-artifacts/epics.md]
- [x] [Review][Defer] Concurrent stabilized readings for the same plate could in theory double-process the same open transaction (no locking). [src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java] — deferred, accepted risk: story 3.1's `ConcurrentHashMap.compute()` already serializes readings per `scaleId`, so triggering this would require two different scales processing the same plate concurrently — physically implausible (a truck can't be on two scales at once). No real call site exists yet (Epic 2 not built). Revisit once the ingestion endpoint (Epic 2) is wired.
