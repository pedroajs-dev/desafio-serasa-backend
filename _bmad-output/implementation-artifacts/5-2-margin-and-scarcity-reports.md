---
baseline_commit: 6bbc5b9
---

# Story 5.2: Margin & Scarcity Reports

Status: done

## Story

As an admin,
I want to see margin analysis and scarcity alerts,
so that I can identify pricing opportunities and low-stock risks.

## Acceptance Criteria

1. **Given** grain types exist with a `currentStock` and `maxReferenceStock`, **when** `GET /api/reports/avg-margin-by-grain` is called, **then** it returns one entry per grain type with the margin computed by `MarginService.calculateMargin(grainType)` from that grain type's **current** `currentStock` (not a historical average — there is no margin history persisted anywhere in the system, so "average" here means the single current-state margin per grain type, consistent with FR16b's "computed on demand, not stored" model).
2. **Given** the same grain types, **when** `GET /api/reports/scarcity-alerts` is called, **then** it returns only grain types whose calculated margin is `>= scarcityThreshold` (default `0.18`, i.e. 18%), each with its computed margin.
3. **And** the `0.18` scarcity threshold is configurable via `application.yml` (not hardcoded in Java), following the same `@ConfigurationProperties` pattern already used for `stabilization.*`.
4. **And** both endpoints return structured DTOs (not raw `GrainType` entities).
5. **And** grain types are read via `GrainTypeRepository.findAll()` — no new query methods needed, `MarginService.calculateMargin(GrainType)` already exists and does the per-entity calculation (Story 4.1).

## Tasks / Subtasks

- [x] Task 1: Scarcity threshold configuration (AC: #3)
  - [x] Add `com.serasa.balancas.report.ReportProperties` — a `@ConfigurationProperties(prefix = "reports")` record with a single field `double scarcityThreshold`, mirroring `StabilizationProperties`'s record-based pattern exactly (see `src/main/java/com/serasa/balancas/stabilization/StabilizationProperties.java`) — no `@Service`/`@Component` annotation needed, `@ConfigurationPropertiesScan` on `BalancasApplication` already picks up all `@ConfigurationProperties` records project-wide
  - [x] Add to `src/main/resources/application.yml`:
    ```yaml
    reports:
      scarcity-threshold: 0.18
    ```
    (place as a new top-level key, peer of `stabilization:`, `spring:`, `server:`)
- [x] Task 2: `avg-margin-by-grain` report (AC: #1, #4, #5)
  - [x] Create `AvgMarginByGrainResponse(Long grainTypeId, String grainTypeName, BigDecimal margin)` record in `com.serasa.balancas.report`
  - [x] Add `GET /api/reports/avg-margin-by-grain` to `ReportController`: `grainTypeRepository.findAll()`, map each `GrainType` through `marginService.calculateMargin(grainType)` into the DTO, return `List<AvgMarginByGrainResponse>` — do this mapping inline in the controller method (a `.stream().map(...).toList()`), no new service class needed since it's a single-line transformation per entity
- [x] Task 3: `scarcity-alerts` report (AC: #2, #3, #4, #5)
  - [x] Create `ScarcityAlertResponse(Long grainTypeId, String grainTypeName, BigDecimal margin, Double currentStock, Double maxReferenceStock)` record in `com.serasa.balancas.report` — include stock fields so the admin can see *why* it's scarce, not just the margin number
  - [x] Add `GET /api/reports/scarcity-alerts` to `ReportController`: `grainTypeRepository.findAll()`, compute margin per grain type via `marginService.calculateMargin(grainType)`, filter where `margin.doubleValue() >= reportProperties.scarcityThreshold()`, map matches into `ScarcityAlertResponse`, return the filtered list
- [x] Task 4: Wire dependencies into `ReportController` (AC: #1, #2)
  - [x] Add `GrainTypeRepository`, `MarginService`, and `ReportProperties` as constructor-injected fields on the existing `ReportController` (`src/main/java/com/serasa/balancas/report/ReportController.java`) — do not create a new controller, this story extends the one built in Story 5.1
- [x] Task 5: Integration tests (AC: #1–#5)
  - [x] Extend `src/test/java/com/serasa/balancas/report/ReportControllerTest.java` (created in Story 5.1) with new test methods — reuse its existing `createGrainType()`-style helpers where possible, following the same unique-per-test-entity pattern (`UUID` suffixes) already established there to avoid seed-data (`data.sql`) collisions
  - [x] Test `avg-margin-by-grain`: create 2 grain types with different `currentStock`/`maxReferenceStock` ratios (e.g. one scarce, one abundant), assert each appears with the correct margin computed via the same formula as `MarginServiceTest` (`margin = maxMargin - (maxMargin - minMargin) * min(1, currentStock / maxReferenceStock)`, clamped `[0,1]`)
  - [x] Test `scarcity-alerts`: create one grain type with `currentStock` low enough to push margin `>= 0.18` (e.g. `currentStock=0` → margin `0.20`) and one grain type with margin comfortably below threshold (e.g. `currentStock >= maxReferenceStock` → margin `0.05`); assert only the scarce one appears in the response
  - [x] Test `scarcity-alerts` boundary: a grain type whose margin computes to exactly `0.18` is included (`>=`, not `>`)

## Dev Notes

- **This story extends the existing `ReportController`, `ReportControllerTest`, and `report` package created in Story 5.1 — do not create a new controller or duplicate the package.** Story 5.1 (done, code-reviewed, no findings) already established `com.serasa.balancas.report` with `CostByGrainResponse`, `ScaleRankingResponse`, `AvgWeighingDurationResponse` as plain Java records, and `ReportController` at `@RequestMapping("/api/reports")` constructor-injected with `TransportTransactionRepository` and `WeighingRecordRepository`. This story adds two more endpoints to that same controller and two more DTOs to that same package — add `GrainTypeRepository` and `MarginService` as two more constructor-injected dependencies alongside the existing two.
- **`MarginService` already exists and does exactly the calculation needed — do not reimplement or duplicate margin math.** See `src/main/java/com/serasa/balancas/margin/MarginService.java` (Story 4.1, code-reviewed, no findings): `calculateMargin(GrainType grainType)` returns a `BigDecimal` (rounded to 4 decimal places, `HALF_UP`), already guards against `maxReferenceStock <= 0` (degrades to minimum margin rather than dividing by zero). Call this directly per `GrainType` — no new margin logic belongs in the `report` package.
- **"Average" margin is a misnomer carried over from the epics doc — there is no historical margin data to average.** `GrainType` has only a live `currentStock` field (no time-series/history table exists anywhere in the schema — confirmed via `src/main/java/com/serasa/balancas/graintype/GrainType.java` and `GrainTypeRepository` which is a bare `JpaRepository` with no custom queries). `MarginService.calculateMargin` is a pure function of the *current* `currentStock`/`maxReferenceStock` ratio — there is exactly one margin value per grain type at any point in time. AC #1 is written to make this explicit so the dev agent doesn't try to invent a rolling average or historical tracking mechanism that doesn't belong in this story's scope.
- **Threshold must be configurable, matching `stabilization.*`'s existing pattern exactly** (AC #3) — see `src/main/java/com/serasa/balancas/stabilization/StabilizationProperties.java`: a `@ConfigurationProperties(prefix = "...")` record with zero boilerplate beyond the record declaration, auto-registered via `@ConfigurationPropertiesScan` already present on `BalancasApplication` (`src/main/java/com/serasa/balancas/BalancasApplication.java:14`). Do not use `@Value("${reports.scarcity-threshold}")` field injection instead — the project has an established record-based `@ConfigurationProperties` convention and this story should match it, not introduce a second pattern.
- **`GrainTypeRepository` needs no new methods** — `findAll()` (inherited from `JpaRepository`) is sufficient; there's no date-range or status filtering concern here unlike Story 5.1's `TransportTransaction` queries, since `GrainType` has no `status` or `createdDate` field. Filtering (the `>= scarcityThreshold` check) happens in Java after calling `MarginService`, not in a repository query — pushing the threshold comparison into JPQL would require duplicating the margin formula in SQL, which the project has deliberately avoided (see `MarginService`'s Dev Notes precedent: the linear-interpolation formula lives in exactly one place).
- **No Lombok, plain records for DTOs** — matches Story 5.1's `CostByGrainResponse`/`ScaleRankingResponse`/`AvgWeighingDurationResponse` style exactly (simple `public record X(...) {}`, no builder, no Lombok annotations — this project has zero Lombok dependency anywhere).
- **BigDecimal, not Double, for the margin field** — `MarginService.calculateMargin` returns `BigDecimal`, so `AvgMarginByGrainResponse.margin` and `ScarcityAlertResponse.margin` should be `BigDecimal` to match the source type directly, no unnecessary `.doubleValue()` conversion in the DTO itself (only convert to `double` transiently for the `>=` threshold comparison in Task 3, since `ReportProperties.scarcityThreshold()` is a primitive `double` to match `StabilizationProperties`'s all-primitive style).
- **Test file already exists from Story 5.1** (`src/test/java/com/serasa/balancas/report/ReportControllerTest.java`) — add new `@Test` methods to it rather than creating a second test class; it already has `@SpringBootTest` + `@AutoConfigureMockMvc` wiring and helper methods (`createGrainType()`, `uniqueSuffix()`) that new tests can reuse directly. Add `@Autowired private GrainTypeRepository grainTypeRepository;` if not already present (it is — Story 5.1's test already autowires it for its own `createGrainType()` helper).

### Project Structure Notes

- New: `src/main/java/com/serasa/balancas/report/ReportProperties.java`
- New: `src/main/java/com/serasa/balancas/report/AvgMarginByGrainResponse.java`
- New: `src/main/java/com/serasa/balancas/report/ScarcityAlertResponse.java`
- Modified: `src/main/java/com/serasa/balancas/report/ReportController.java` — add two endpoints, inject `GrainTypeRepository`, `MarginService`, `ReportProperties`
- Modified: `src/main/resources/application.yml` — add `reports.scarcity-threshold: 0.18`
- Modified: `src/test/java/com/serasa/balancas/report/ReportControllerTest.java` — add new test methods for both endpoints
- No entity changes, no repository query methods needed (`GrainTypeRepository.findAll()` suffices), no `data.sql` changes required

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 5.2: Margin & Scarcity Reports] — acceptance criteria and task list origin; FR18, FR21 in the Requirements Inventory.
- [Source: src/main/java/com/serasa/balancas/margin/MarginService.java] — `calculateMargin(GrainType)` is the exact calculation this story consumes; already handles the `maxReferenceStock <= 0` edge case and rounding.
- [Source: src/main/java/com/serasa/balancas/graintype/GrainType.java] — entity fields consumed: `id`, `name`, `currentStock`, `maxReferenceStock` (via `MarginService`).
- [Source: src/main/java/com/serasa/balancas/graintype/GrainTypeRepository.java] — bare `JpaRepository<GrainType, Long>`, `findAll()` is all this story needs.
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationProperties.java] — exact `@ConfigurationProperties` record pattern to replicate for `ReportProperties`.
- [Source: src/main/java/com/serasa/balancas/BalancasApplication.java:14] — `@ConfigurationPropertiesScan` already active project-wide; no new scan configuration needed.
- [Source: src/main/java/com/serasa/balancas/report/ReportController.java] — existing controller this story extends (Story 5.1); constructor-injection pattern, `@RequestMapping("/api/reports")`, direct `List<X>` returns.
- [Source: _bmad-output/implementation-artifacts/5-1-core-aggregation-reports.md] — previous story; confirms `report` package conventions (plain records, JPQL constructor-expressions where the calculation is DB-native, Java-side computation where it isn't — margin calculation here is the latter case, since it's not a SQL-expressible formula and already lives in `MarginService`).
- [Source: src/test/java/com/serasa/balancas/margin/MarginServiceTest.java] — precedent for margin-value test assertions (`assertEquals(0, actual.compareTo(expected))` style for `BigDecimal` comparison, since `BigDecimal.equals()` is scale-sensitive).

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

- Discovered `src/test/resources/application.yml` fully overrides `src/main/resources/application.yml` under `@SpringBootTest` (test classpath resource takes precedence, not a merge) and was missing the new `reports:` block, causing `ReportProperties.scarcityThreshold()` to bind to `0.0` and the `scarcity-alerts` endpoint to return every grain type unfiltered. Fixed by adding the same `reports.scarcity-threshold: 0.18` block to the test resource file. Verified via a temporary debug test that printed the bound value (0.0 before fix, 0.18 after) before removing the debug test.

### Completion Notes List

- Added `com.serasa.balancas.report.ReportProperties`, a `@ConfigurationProperties(prefix = "reports")` record with `scarcityThreshold`, mirroring `StabilizationProperties`'s pattern exactly. Auto-registered via the existing `@ConfigurationPropertiesScan` on `BalancasApplication`.
- Added `reports.scarcity-threshold: 0.18` to both `src/main/resources/application.yml` and `src/test/resources/application.yml` (the latter required a separate fix — see Debug Log).
- Extended `ReportController` (from Story 5.1) with two new endpoints and three new constructor-injected dependencies (`GrainTypeRepository`, `MarginService`, `ReportProperties`), reusing `MarginService.calculateMargin(GrainType)` from Story 4.1 with no reimplementation of margin math.
- `GET /api/reports/avg-margin-by-grain` maps every `GrainType` through `MarginService.calculateMargin` inline (`.stream().map(...).toList()`), no new service class.
- `GET /api/reports/scarcity-alerts` filters grain types where `margin.doubleValue() >= reportProperties.scarcityThreshold()`, including stock fields in the response so the admin can see why a grain type is scarce.
- Both new DTOs (`AvgMarginByGrainResponse`, `ScarcityAlertResponse`) are plain Java records with `BigDecimal margin` (matching `MarginService`'s return type directly, no unnecessary `Double` conversion).
- Extended `ReportControllerTest` (from Story 5.1) with 3 new test methods, including a boundary test asserting a grain type computing to exactly `0.18` margin is included (`>=`, not `>`) — used `currentStock=2.0, maxReferenceStock=15.0` (ratio `2/15`) to hit the exact threshold after `MarginService`'s 4-decimal `HALF_UP` rounding.
- Full regression suite passes: 75 tests total (0 failures, 0 errors) — up from 72, including 3 new `ReportControllerTest` cases.

### File List

- `src/main/java/com/serasa/balancas/report/ReportProperties.java` (new)
- `src/main/java/com/serasa/balancas/report/AvgMarginByGrainResponse.java` (new)
- `src/main/java/com/serasa/balancas/report/ScarcityAlertResponse.java` (new)
- `src/main/java/com/serasa/balancas/report/ReportController.java` (modified — added `avg-margin-by-grain` and `scarcity-alerts` endpoints, injected `GrainTypeRepository`, `MarginService`, `ReportProperties`)
- `src/main/resources/application.yml` (modified — added `reports.scarcity-threshold: 0.18`)
- `src/test/resources/application.yml` (modified — added `reports.scarcity-threshold: 0.18`, required for test-time config binding to match production config; also added a comment noting this file fully replaces, not merges with, `src/main/resources/application.yml` under `@SpringBootTest`)
- `src/test/java/com/serasa/balancas/report/ReportControllerTest.java` (modified — added `createGrainType(currentStock, maxReferenceStock)` overload and 3 new test methods)

### Change Log

- 2026-07-10: Story drafted for Epic 5, Story 5.2 — margin and scarcity reports (avg-margin-by-grain, scarcity-alerts), extending the Story 5.1 `ReportController`.
- 2026-07-10: Implemented Story 5.2 — `avg-margin-by-grain` and `scarcity-alerts` endpoints added to `ReportController`; `ReportProperties` config record for the scarcity threshold; fixed a test-resource config gap that caused the threshold to bind to 0.0 under `@SpringBootTest`.
- 2026-07-10: Code review approved — no findings. Added a closing note to Story 4.1's Dev Agent Record confirming the double-vs-BigDecimal drift concern didn't materialize (avg-margin-by-grain has n=1 per grain type, nothing to accumulate). Documented the test-resource "fully replaces, not merges" gotcha with a comment in `src/test/resources/application.yml`.
