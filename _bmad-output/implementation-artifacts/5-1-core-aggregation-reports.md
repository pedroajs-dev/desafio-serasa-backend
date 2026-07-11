---
baseline_commit: 6bbc5b9
---

# Story 5.1: Core Aggregation Reports

Status: done

## Story

As an admin,
I want to query cost and throughput reports,
so that I can analyse operational efficiency and grain acquisition costs.

## Acceptance Criteria

1. **Given** completed transactions exist, **when** `GET /api/reports/cost-by-grain?from=&to=` is called, **then** it returns total `loadCost` grouped by grain type for transactions with `startDate` in the `[from, to]` range.
2. **Given** `from`/`to` are omitted, **when** `GET /api/reports/cost-by-grain` is called, **then** it returns totals across all completed transactions (no date filtering).
3. **Given** completed transactions exist, **when** `GET /api/reports/scale-ranking` is called, **then** it returns scales ordered by number of completed weighings (descending), one entry per scale that has at least one `WeighingRecord`.
4. **Given** completed transactions exist, **when** `GET /api/reports/avg-weighing-duration` is called, **then** it returns the average `(endDate - startDate)` in seconds, grouped by branch, computed only over transactions with status `COMPLETED` (both dates non-null).
5. **And** all three endpoints return structured DTOs (not raw JPA entities) — no lazy-loading proxies serialized, no internal entity graph exposed.
6. **And** `cost-by-grain` and `avg-weighing-duration` only consider `TransportTransaction` rows with status `COMPLETED` — `IN_TRANSIT`/`AT_DOCK`/`WEIGHING` transactions have null `loadCost`/`endDate` and must be excluded, not just tolerated as nulls in the aggregation.

## Tasks / Subtasks

- [x] Task 1: `cost-by-grain` report (AC: #1, #2, #5, #6)
  - [x] Add method to `TransportTransactionRepository`: `List<Object[]> sumLoadCostByGrainType(LocalDateTime from, LocalDateTime to)` using a JPQL query: `SELECT t.grainType.id, t.grainType.name, SUM(t.loadCost) FROM TransportTransaction t WHERE t.status = 'COMPLETED' AND (:from IS NULL OR t.startDate >= :from) AND (:to IS NULL OR t.startDate <= :to) GROUP BY t.grainType.id, t.grainType.name` — use `@Query` with named params `:from`/`:to`, both nullable (AC #2 requires the endpoint to work with no date filter)
  - [x] Create `CostByGrainResponse(Long grainTypeId, String grainTypeName, Double totalLoadCost)` as a simple record or plain DTO class in a new `com.serasa.balancas.report` package
  - [x] Add `GET /api/reports/cost-by-grain` to `ReportController` accepting optional `@RequestParam(required = false) LocalDateTime from` and `to` (ISO-8601, Spring's default `LocalDateTime` converter handles this — no custom formatter needed, consistent with how `TransportTransaction.startDate` is already a plain `LocalDateTime`)
- [x] Task 2: `scale-ranking` report (AC: #3, #5)
  - [x] Add method to `WeighingRecordRepository`: `List<Object[]> countByScaleOrderedDesc()` using JPQL: `SELECT w.scale.id, COUNT(w) FROM WeighingRecord w GROUP BY w.scale.id ORDER BY COUNT(w) DESC`
  - [x] Create `ScaleRankingResponse(String scaleId, Long weighingCount)` DTO
  - [x] Add `GET /api/reports/scale-ranking` to `ReportController`
- [x] Task 3: `avg-weighing-duration` report (AC: #4, #5, #6)
  - [x] Add method to `TransportTransactionRepository`: `List<Object[]> avgDurationSecondsByBranch()` using JPQL with `AVG(FUNCTION('DATEDIFF', 'SECOND', t.startDate, t.endDate))` — **verify this exact H2 JPQL/native syntax against the running H2 dialect before finalizing**; if `FUNCTION('DATEDIFF', ...)` doesn't resolve cleanly under Hibernate/H2, fall back to fetching `(branchId, startDate, endDate)` triples via a simpler JPQL projection (`SELECT t.branch.id, t.branch.name, t.startDate, t.endDate FROM TransportTransaction t WHERE t.status = 'COMPLETED'`) and computing the average duration in Java (`Duration.between(startDate, endDate).getSeconds()`, averaged per branch) inside `ReportController` or a small `ReportService` — this avoids fighting a database-specific function and keeps the calculation testable in plain Java
  - [x] Create `AvgWeighingDurationResponse(Long branchId, String branchName, Double avgDurationSeconds)` DTO
  - [x] Add `GET /api/reports/avg-weighing-duration` to `ReportController`
- [x] Task 4: Wire `ReportController` (AC: #5)
  - [x] New `@RestController` `com.serasa.balancas.report.ReportController` at `@RequestMapping("/api/reports")`, constructor-injected with `TransportTransactionRepository` and `WeighingRecordRepository` (no new `ReportService` unless Task 3's Java-side aggregation needs one — if so, keep it thin, just orchestrating the repository call + grouping)
- [x] Task 5: Integration tests (AC: #1–#6)
  - [x] Create `src/test/java/com/serasa/balancas/report/ReportControllerTest.java`, `@SpringBootTest` + `@AutoConfigureMockMvc`, following `TransportTransactionControllerTest`'s pattern (persist real entities via repositories, POST through the real API where practical, assert JSON response shape)
  - [x] Test `cost-by-grain`: create 2+ completed transactions across 2 grain types with known `loadCost`, assert totals grouped correctly; test with `from`/`to` narrowing to exclude one transaction; test with no params returns all
  - [x] Test `scale-ranking`: create weighing records across 2+ scales with different counts, assert descending order
  - [x] Test `avg-weighing-duration`: create completed transactions with known `startDate`/`endDate` deltas across 2 branches, assert averages
  - [x] Test that `IN_TRANSIT`/non-completed transactions are excluded from `cost-by-grain` and `avg-weighing-duration`

## Dev Notes

- **This is the first story in Epic 5 — no existing `report` package.** Create it fresh at `src/main/java/com/serasa/balancas/report/`, peer of `margin`, `graintype`, `transporttransaction`, etc. This story is purely additive — read-only aggregation queries over existing entities, no entity or schema changes.
- **Data model already has everything needed — no new persistence.** Critical structural fact: `TransportTransaction` (not `WeighingRecord`) already carries `loadCost` (Double), `branch` (`@ManyToOne Branch`), `grainType`, `startDate`, `endDate` directly on the entity — see `src/main/java/com/serasa/balancas/transporttransaction/TransportTransaction.java`. These fields are populated by `WeighingPersistenceService.persist()` (`src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java:90-95`) at the same moment `WeighingRecord` is saved and the transaction is marked `COMPLETED`. This means `cost-by-grain` and `avg-weighing-duration` (both grouped by grain/branch with date ranges) should query `TransportTransactionRepository` directly — do **not** join through `WeighingRecord` for these two reports, it's unnecessary indirection.
- **`scale-ranking` is the one report that must use `WeighingRecordRepository`**, since `TransportTransaction` has no `scale` reference (only `WeighingRecord` does — see `WeighingRecord.java:27-28`, `@ManyToOne Scale scale`). Count `WeighingRecord` rows grouped by `scale.id`.
- **Status filtering is not automatic** — only `TransportTransaction` rows with `status = COMPLETED` have non-null `loadCost`/`endDate` (set together in `WeighingPersistenceService.persist()`). AC #6 requires explicitly filtering `WHERE t.status = 'COMPLETED'` in the JPQL rather than relying on nulls being silently excluded by `SUM`/`AVG` — `SUM` over a nullable column ignores nulls automatically in ANSI SQL/JPQL, but an explicit status filter is clearer and matches the AC wording; include it either way for correctness and readability.
- **DTOs, not entities** (AC #5) — this project has no precedent DTO pattern for read-only aggregation results yet (existing DTOs like `TransportTransactionResponse`, `ScaleResponse` are 1:1 entity-to-response mappings). For this story, plain records or simple classes with a constructor are fine — follow the no-Lombok convention already established project-wide (see Story 4.1 Dev Notes, and `ScaleResponse`/`TransportTransactionResponse` for existing DTO style). `Object[]` projections from JPQL `GROUP BY` queries need to be mapped into these DTOs manually in the controller (or a thin service) since Spring Data JPA doesn't auto-map multi-column `GROUP BY` results to records without an explicit constructor-expression JPQL (`SELECT new com.serasa.balancas.report.CostByGrainResponse(t.grainType.id, t.grainType.name, SUM(t.loadCost)) FROM ...` is the cleaner alternative to `Object[]` — prefer this constructor-expression style if the DTO is a class with a matching constructor, since it avoids manual `Object[]` unpacking entirely).
- **`avg-weighing-duration` H2 date-diff caveat**: H2's JPQL support for date-diff functions varies by version/dialect. Rather than risk a `FUNCTION('DATEDIFF', ...)` call that may not translate cleanly through Hibernate to H2, the safer default (documented in Task 3) is to fetch `(branch, startDate, endDate)` per completed transaction and compute `Duration.between(...).getSeconds()` averaged per branch in Java. This is a small amount of data (one row per completed transaction) and avoids a fragile DB-specific function — pick this path unless a JPQL date-diff is verified working locally first.
- **No existing `ReportController` or `report` package to reference** — this is greenfield within the project's established conventions (constructor injection, `@RestController`, `@RequestMapping`, plain `List<X>` bodies returned directly a la `GrainTypeController.findAll()`, see `src/main/java/com/serasa/balancas/graintype/GrainTypeController.java:30-33`).
- **`GlobalExceptionHandler` already covers validation/type-mismatch errors** (`src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java`) — a malformed `from`/`to` query param (e.g. non-ISO date string) will already 400 via `MethodArgumentTypeMismatchException` handling; no new exception handling needed for this story.
- **Story 5.2 (backlog, not in scope here) will add** `avg-margin-by-grain` and `scarcity-alerts` to the same `ReportController`, consuming `MarginService` (`src/main/java/com/serasa/balancas/margin/MarginService.java`) — do not implement those endpoints in this story, and do not couple this story's DTOs/queries to `MarginService`.

### Project Structure Notes

- New: `src/main/java/com/serasa/balancas/report/ReportController.java`
- New: `src/main/java/com/serasa/balancas/report/CostByGrainResponse.java`
- New: `src/main/java/com/serasa/balancas/report/ScaleRankingResponse.java`
- New: `src/main/java/com/serasa/balancas/report/AvgWeighingDurationResponse.java`
- Modified: `src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRepository.java` — add `sumLoadCostByGrainType` and `avgDurationSecondsByBranch` (or equivalent) query methods
- Modified: `src/main/java/com/serasa/balancas/weighingrecord/WeighingRecordRepository.java` — add `countByScaleOrderedDesc` (or equivalent) query method
- New: `src/test/java/com/serasa/balancas/report/ReportControllerTest.java`
- No entity changes, no `data.sql` changes required (existing seed data plus test-created transactions should be sufficient for report queries, but be aware existing `data.sql` seed rows may already contribute to aggregation results in `@SpringBootTest` tests — assert on relative deltas or use unique grain types/branches per test where feasible, following `TransportTransactionControllerTest`'s pattern of generating unique test entities via `UUID` to avoid cross-test interference, see `TransportTransactionControllerTest.java:38-41`)

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 5.1: Core Aggregation Reports] — acceptance criteria and task list origin; FR17, FR19, FR20 in the Requirements Inventory.
- [Source: src/main/java/com/serasa/balancas/transporttransaction/TransportTransaction.java] — entity already has `branch`, `grainType`, `loadCost`, `startDate`, `endDate` fields populated on completion; no schema change needed.
- [Source: src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java:90-95] — shows exactly when/how `loadCost`, `status`, `endDate` are set on `TransportTransaction` (on stability detection), confirming these fields are reliably populated only for `COMPLETED` transactions.
- [Source: src/main/java/com/serasa/balancas/weighingrecord/WeighingRecord.java] — has the `scale` reference needed for `scale-ranking`; `TransportTransaction` does not have this reference.
- [Source: src/main/java/com/serasa/balancas/graintype/GrainTypeController.java] — precedent for a simple `@RestController` with constructor-injected repository and direct `List<X>` return.
- [Source: src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java] — existing handlers already cover malformed query params and validation errors; no new exception types needed.
- [Source: src/test/java/com/serasa/balancas/transporttransaction/TransportTransactionControllerTest.java] — precedent for `@SpringBootTest` + `@AutoConfigureMockMvc` integration test style with unique-entity-per-test pattern to avoid seed-data collisions.
- [Source: _bmad-output/implementation-artifacts/4-1-margin-calculation-service.md] — previous story; confirms project convention: no Lombok, plain Java DTOs/entities, `BigDecimal` for money fields but `Double` used loosely elsewhere in existing entities (`loadCost` is `Double` on both `WeighingRecord` and `TransportTransaction`, not `BigDecimal` — match this existing inconsistency rather than "fixing" it in this story's scope).

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

### Completion Notes List

- Created `com.serasa.balancas.report` package with three DTOs (`CostByGrainResponse`, `ScaleRankingResponse`, `AvgWeighingDurationResponse`) as Java records, and `ReportController` exposing `GET /api/reports/cost-by-grain`, `/scale-ranking`, `/avg-weighing-duration`.
- `cost-by-grain` and `avg-weighing-duration` query `TransportTransactionRepository` directly (not `WeighingRecord`), since `TransportTransaction` already carries `loadCost`, `branch`, `grainType`, `startDate`, `endDate`, per Dev Notes.
- `scale-ranking` queries `WeighingRecordRepository` since only `WeighingRecord` has a `scale` reference.
- `cost-by-grain` uses a JPQL constructor-expression (`SELECT new com.serasa.balancas.report.CostByGrainResponse(...)`) so Spring Data JPA maps `GROUP BY` results directly to the DTO, avoiding manual `Object[]` unpacking. Same technique for `scale-ranking`.
- `avg-weighing-duration` fetches `(branchId, branchName, startDate, endDate)` rows via JPQL and computes `Duration.between(...).getSeconds()` averaged per branch in Java (`ReportController.avgWeighingDuration()`), per the Dev Notes guidance to avoid a fragile H2-specific date-diff function.
- All three JPQL queries explicitly filter `WHERE t.status = TransactionStatus.COMPLETED`, satisfying AC #6 (non-completed transactions excluded, not just tolerated via null-skipping aggregates).
- Full regression suite passes: 72 tests total (0 failures, 0 errors) — up from 66, including 6 new `ReportControllerTest` cases covering all three endpoints, date-range filtering, non-completed exclusion, and ranking order.

### File List

- `src/main/java/com/serasa/balancas/report/ReportController.java` (new)
- `src/main/java/com/serasa/balancas/report/CostByGrainResponse.java` (new)
- `src/main/java/com/serasa/balancas/report/ScaleRankingResponse.java` (new)
- `src/main/java/com/serasa/balancas/report/AvgWeighingDurationResponse.java` (new)
- `src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionRepository.java` (modified — added `sumLoadCostByGrainType`, `findCompletedDurationsByBranch`)
- `src/main/java/com/serasa/balancas/weighingrecord/WeighingRecordRepository.java` (modified — added `countByScaleOrderedDesc`)
- `src/test/java/com/serasa/balancas/report/ReportControllerTest.java` (new)

### Change Log

- 2026-07-10: Story drafted for Epic 5, Story 5.1 — core aggregation reports (cost-by-grain, scale-ranking, avg-weighing-duration).
- 2026-07-10: Implemented Story 5.1 — `ReportController` with `cost-by-grain`, `scale-ranking`, `avg-weighing-duration` endpoints; JPQL constructor-expression DTOs, Java-side duration averaging to avoid H2 date-diff fragility.
- 2026-07-10: Code review approved — no findings.
