# Fix 1: Round persisted weight/cost values to 2 decimal places

Status: done

## Context

Not part of any epic's original acceptance criteria. Discovered 2026-07-11 while manually
verifying Story 6.2's ESP32 simulator against the H2 Console: `grossWeightKg`, `netWeightKg`,
and `loadCost` were all persisted with full floating-point precision (e.g.
`loadCost = 4229.841980016929`) in `TRANSPORT_TRANSACTION`. Not caught by code review or by
any story's AC — surfaced only by inspecting real simulator-produced data end-to-end.

Story 3.2 (Weighing Record Persistence), which owns `WeighingPersistenceService`, was already
closed and merged before this was found. Logged here as a standalone fix rather than folded
back into Story 3.2/Epic 3, so the timeline accurately reflects when the gap was found (during
Story 6.2's manual testing, not as part of Epic 3's original delivery).

## Root Cause

`grossWeightKg` comes directly from `StabilizationResult.stabilizedWeightKg()` (an average of
`double` readings, inheriting floating-point residue). `netWeightKg` (`grossWeightKg - tare`)
and `loadCost` (`(netWeightKg / 1000.0) * purchasePricePerTon.doubleValue()`) compound that
residue further. All three were persisted as-is into both `WeighingRecord` and
`TransportTransaction`, with no rounding anywhere in the chain.

## Fix

- `WeighingPersistenceService.persist()`: round `grossWeightKg`, `netWeightKg`, and `loadCost`
  to 2 decimal places via `BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue()`
  (same pattern as `MarginService`, Story 4.1) before constructing `WeighingRecord` and before
  setting the fields on `TransportTransaction`.
- `ReportController.costByGrain()`: the `/api/reports/cost-by-grain` endpoint sums `loadCost`
  server-side (`SUM(t.loadCost)` in `TransportTransactionRepository.sumLoadCostByGrainType`).
  Summing many already-rounded per-transaction values can still reintroduce double-precision
  drift at the aggregate — the endpoint now rounds the summed total before returning it.
- Checked other Epic 5 reports for the same exposure: `avg-weighing-duration` (works on
  `Duration` in seconds, no weight/cost fields), `avg-margin-by-grain` / `scarcity-alerts`
  (margin computed live from `GrainType` stock ratios via `MarginService`, already
  `BigDecimal`-rounded to 4 decimals, never reads `loadCost`/`netWeightKg`), and
  `scale-ranking` (counts records only) — none of these are affected.

## Files Touched

- `src/main/java/com/serasa/balancas/weighingrecord/WeighingPersistenceService.java`
- `src/main/java/com/serasa/balancas/report/ReportController.java`
- `src/test/java/com/serasa/balancas/weighingrecord/WeighingPersistenceServiceTest.java` (new regression test)

## Regression Test

`WeighingPersistenceServiceTest.roundsGrossNetWeightAndLoadCostToTwoDecimalPlacesForRepeatingDecimalAverage`
— feeds a stabilized weight of `12500 + 1/3` (repeating decimal, mirroring a real averaged
sensor reading) and asserts `grossWeightKg=12500.33`, `netWeightKg=4000.33`, `loadCost=720.06`
on both the saved `WeighingRecord` and the reloaded `TransportTransaction`.

Full suite: 76/76 passing after this fix (was 75/76 before this test was added).
