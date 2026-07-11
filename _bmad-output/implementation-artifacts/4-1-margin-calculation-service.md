---
baseline_commit: edad6e9
---

# Story 4.1: Margin Calculation Service

Status: done

## Story

As the system,
I want to calculate the sale margin for a grain type based on current stock,
so that scarce grains yield higher margins (up to 20%) and abundant ones lower margins (down to 5%).

## Acceptance Criteria

1. **Given** a `GrainType` with `currentStock=0` and `maxReferenceStock=1000`, **when** margin is calculated, **then** `margin = 0.20` (20%, maximum — stock fully depleted).
2. **Given** `currentStock >= maxReferenceStock` (e.g. `currentStock=1000, maxReferenceStock=1000`, and also test `currentStock=1200 > maxReferenceStock`), **when** margin is calculated, **then** `margin = 0.05` (5%, minimum — stock at or above reference, clamped, not negative).
3. **Given** `currentStock=500` and `maxReferenceStock=1000`, **when** margin is calculated, **then** `margin = 0.125` (12.5%, linear interpolation midpoint).
4. **And** `calculateSalePrice(GrainType)` returns `purchasePricePerTon.multiply(1 + margin)` correctly for at least one non-trivial case (e.g. `purchasePricePerTon=1000, currentStock=500, maxReferenceStock=1000` → `salePricePerTon = 1125.00`).
5. **And** `salePricePerTon` is a value computed on demand by this service — it is **not** persisted anywhere (no new column, no write to `GrainType` or any entity). This mirrors FR16b: sale price is derived at report/query time, unlike `loadCost` (FR16a) which *is* persisted on `TransportTransaction`.

## Tasks / Subtasks

- [x] Task 1: Create `MarginService` (AC: #1, #2, #3)
  - [x] New class `com.serasa.balancas.margin.MarginService`, `@Service`, no state (pure stateless calculator — no `ConcurrentHashMap`, no fields beyond injected config)
  - [x] Method `BigDecimal calculateMargin(GrainType grainType)` implementing: `margin = maxMargin - (maxMargin - minMargin) * min(1, currentStock / maxReferenceStock)`, clamped so the ratio is never negative (if `currentStock` could be negative in theory, clamp ratio to `[0, 1]` — use `Math.max(0, Math.min(1, currentStock / maxReferenceStock))`)
  - [x] Use `minMargin = 0.05` and `maxMargin = 0.20` — hardcode as private `static final double` constants in `MarginService` (the epic doesn't reference these via `application.yml`, unlike `stabilization.*`; do not add new config properties for this story since no AC or task requires it)
  - [x] Return type: use `BigDecimal` for the margin value (consistent with `purchasePricePerTon` already being `BigDecimal` in `GrainType`) — compute the ratio in `double`, then wrap the final margin in `BigDecimal.valueOf(...)`; do not do the whole calculation in `BigDecimal` arithmetic, that adds needless complexity for a simple linear formula with no monetary rounding requirements at this step
- [x] Task 2: Add `calculateSalePrice` (AC: #4, #5)
  - [x] Method `BigDecimal calculateSalePrice(GrainType grainType)` = `grainType.getPurchasePricePerTon().multiply(BigDecimal.ONE.add(calculateMargin(grainType)))`
  - [x] Do **not** add a `salePricePerTon` field to `GrainType`, do **not** add a repository query, do **not** persist anything — this method is a pure calculation invoked on demand (AC #5); Epic 5 report endpoints will call this on demand later, not in this story
- [x] Task 3: Unit tests (AC: #1, #2, #3, #4)
  - [x] Create `src/test/java/com/serasa/balancas/margin/MarginServiceTest.java`, plain JUnit (no Spring context needed — `MarginService` has no dependencies), matching the no-Spring-context style already used for `StabilizationServiceTest`
  - [x] Test case: `currentStock=0, maxReferenceStock=1000` → margin = 0.20
  - [x] Test case: `currentStock=1000, maxReferenceStock=1000` → margin = 0.05
  - [x] Test case: `currentStock=1200, maxReferenceStock=1000` (stock above reference) → margin clamped to 0.05, not negative
  - [x] Test case: `currentStock=500, maxReferenceStock=1000` → margin = 0.125
  - [x] Test case: `calculateSalePrice` with `purchasePricePerTon=1000, currentStock=500, maxReferenceStock=1000` → salePricePerTon = 1125.00

## Dev Notes

- **This is the first story in Epic 4 — no existing `margin` package.** Create it fresh at `src/main/java/com/serasa/balancas/margin/MarginService.java`. This is a brand-new, standalone concern with zero dependencies on `stabilization`, `scalereading`, or `weighingrecord` — do not wire it into any of those packages or call sites in this story. Nothing in this story's ACs asks for an HTTP endpoint or integration point; Epic 5 (`5.1`/`5.2`, both currently `backlog`) is where `MarginService` gets consumed by report endpoints (`avg-margin-by-grain`, `scarcity-alerts`). Keep this story scoped to the calculator only.
- **Formula reference** (from epics.md Story 4.1 task list): `margin = maxMargin - (maxMargin - minMargin) × min(1, currentStock / maxReferenceStock)`. Sanity-check against the three ACs: stock=0 → ratio=0 → margin=max(0.20); stock=maxRef → ratio=1 → margin=min(0.05); stock=maxRef/2 → ratio=0.5 → margin=0.20 - 0.15×0.5=0.125. The formula is inversely proportional to stock level: less stock (scarcer) → higher margin, more stock (abundant) → lower margin.
- **`GrainType` fields already exist and are exactly what's needed** — see `src/main/java/com/serasa/balancas/graintype/GrainType.java`: `purchasePricePerTon` (`BigDecimal`, `@NotNull`), `maxReferenceStock` (`Double`, `@NotNull`), `currentStock` (`Double`, defaults to `0.0`). No entity changes needed. Do not add a `salePricePerTon` field to this entity (AC #5 explicitly forbids persisting it).
- **Division-by-zero guard**: if `maxReferenceStock` were `0`, `currentStock / maxReferenceStock` would produce `Infinity` or `NaN` in `double` arithmetic. `GrainType.maxReferenceStock` has `@NotNull` but no `@DecimalMin`/positivity constraint at the entity level, so a `0` or negative value could theoretically exist. Since no AC covers this edge case explicitly and it's a pre-existing entity validation gap (not introduced by this story), do not add new entity validation — instead guard defensively inside `calculateMargin`: if `maxReferenceStock <= 0`, treat the ratio as `1` (i.e., stock is "at or above reference" by definition when there is no valid reference), so the method degrades to the minimum margin rather than throwing or returning `NaN`. This is a one-line `if` guard, not a validation framework addition.
- **No Lombok, plain classes** — matches this project's established convention (see Stories 1.2–1.5, 2.1, 2.2, 3.1).
- **Package placement**: new top-level package `com.serasa.balancas.margin`, peer of `graintype`, `stabilization`, `scalereading`, `weighingrecord` — not nested inside `graintype`, since this is a calculation-domain concern operating *on* `GrainType`, not a `GrainType` CRUD concern.
- **Test style**: follow `StabilizationServiceTest`'s no-Spring-context, plain-JUnit pattern (`MarginService` has a no-arg constructor and no injected properties record in this story, so tests instantiate it directly with `new MarginService()`) — do not use `@SpringBootTest` or `@ExtendWith(MockitoExtension.class)`, there's nothing to mock.
- **Rounding**: AC #4's example (`1000 × 1.125 = 1125.00`) divides evenly; no AC specifies a rounding mode (e.g., `HALF_UP` to 2 decimal places) for cases that don't divide evenly. Since this isn't tested by any AC, don't add explicit `.setScale(...)` rounding unless it's needed to make the AC #4 test assertion pass cleanly (use `BigDecimal` equality carefully — prefer `assertEquals(0, actual.compareTo(expected))` over `assertEquals(expected, actual)` in the test, since `BigDecimal.equals()` is scale-sensitive and `1125` vs `1125.00` would otherwise fail a naive equals check).

### Project Structure Notes

- New: `src/main/java/com/serasa/balancas/margin/MarginService.java`
- New: `src/test/java/com/serasa/balancas/margin/MarginServiceTest.java`
- No existing files modified — this story is fully additive with zero wiring into other packages.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 4.1: Margin Calculation Service] — acceptance criteria and task list origin; also FR15/FR16a/FR16b in the Requirements Inventory section (FR16a = `loadCost`, persisted, already implemented in `WeighingPersistenceService`; FR16b = `salePricePerTon`, computed on demand, this story's scope).
- [Source: src/main/java/com/serasa/balancas/graintype/GrainType.java] — entity fields this service reads: `purchasePricePerTon` (BigDecimal), `maxReferenceStock` (Double), `currentStock` (Double).
- [Source: src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java:76] — precedent for `loadCost` calculation style (`(netWeightKg / 1000.0) * purchasePricePerTon.doubleValue()`), showing the project's existing convention of doing simple arithmetic in `double` even when a `BigDecimal` field is the source, for FR16a. This story follows the same convention for FR16b's margin/sale-price math.
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationService.java] — precedent for a stateless-ish, no-Spring-context-required, plain-JUnit-testable `@Service` (though `StabilizationService` itself is stateful; `MarginService` is fully stateless, an even simpler case).
- [Source: src/test/java/com/serasa/balancas/stabilization/StabilizationServiceTest.java] — precedent for plain-JUnit test style without `@SpringBootTest`, to mirror for `MarginServiceTest`.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

### Completion Notes List

- Created `MarginService` (`@Service`, stateless) in a new `com.serasa.balancas.margin` package with `calculateMargin(GrainType)` and `calculateSalePrice(GrainType)`.
- `calculateMargin` implements the linear interpolation formula with ratio clamped to `[0, 1]` and a `maxReferenceStock <= 0` guard (degrades to minimum margin rather than dividing by zero/producing `NaN`).
- Margin is rounded to 4 decimal places (`RoundingMode.HALF_UP`) before returning — plain `double` subtraction (`0.20 - 0.15 × 1.0`) landed on `0.049999999999999996` instead of exactly `0.05`, which failed the AC #2 boundary test with naive `BigDecimal.valueOf(...)`; rounding avoids floating-point drift without changing the specified calculation approach.
- `calculateSalePrice` composes `calculateMargin` with `purchasePricePerTon` per FR16b; nothing is persisted (no entity/repository changes) per AC #5.
- No wiring into other packages — story scoped strictly to the calculator, as directed by Dev Notes (Epic 5 will consume it later).
- Full regression suite passes: 66 tests total (0 failures, 0 errors) — up from 61, including 5 new `MarginServiceTest` cases.

### File List

- `src/main/java/com/serasa/balancas/margin/MarginService.java` (new)
- `src/test/java/com/serasa/balancas/margin/MarginServiceTest.java` (new)

### Change Log

- 2026-07-10: Implemented Story 4.1 — `MarginService` with linear-interpolation margin calculation (5%–20%) and on-demand sale price derivation.
- 2026-07-10: Code review approved — no findings. Double-vs-BigDecimal rounding approach confirmed sound; noted as a forward-looking consideration for Epic 5's `avg-margin-by-grain` report (averaging many rounded values vs. raw ones).
- 2026-07-10: Follow-up (Story 5.2 implementation) — the drift concern above did not materialize. `avg-margin-by-grain` calls `calculateMargin(GrainType)` exactly once per grain type; there is no per-transaction margin history persisted anywhere in the schema to accumulate, so there is nothing to average over (n=1 per grain type, not many rounded values summed). The "average" in the epics.md naming turned out to be a misnomer rather than a multi-value aggregation — see `_bmad-output/implementation-artifacts/5-2-margin-and-scarcity-reports.md` AC #1.
