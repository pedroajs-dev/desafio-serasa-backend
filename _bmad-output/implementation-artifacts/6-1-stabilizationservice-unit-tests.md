---
baseline_commit: 0da439667a6642d9fbcab1562739b39da72fe1c2
---

# Story 6.1: StabilizationService Unit Tests

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want unit tests covering all stabilization edge cases,
so that the core algorithm is validated independently of HTTP and persistence.

## Acceptance Criteria

1. Continuously oscillating weight (never within stdDev threshold) → no `WeighingRecord` persisted (i.e. `StabilizationService.process()` never returns a present `Optional` across the whole run).
2. Weight stabilizes (N readings, M consecutive windows) → persisted with correct average weight.
3. Weight drops to ~0 after stabilization → state resets, ready for next truck.
4. Two consecutive sessions on same scale (truck A then truck B) → buffers do not mix.
5. Single spike in an otherwise stable window → does not break detection if overall stdDev is within threshold.
6. `alreadyPersisted` lock: subsequent readings with truck still on scale do not trigger a second persistence.
7. Concurrent readings hammering the SAME `scaleId` from many threads (real thread overlap, not serialized-by-test-order) → no buffer corruption, persistence happens exactly once, and the final stabilized weight is correct.
8. Concurrent readings hammering DIFFERENT `scaleId`s in parallel (real thread overlap across scales) → no cross-contamination between scales' buffers, no exceptions/deadlocks, each scale stabilizes independently at its own correct weight.

## Tasks / Subtasks

- [x] Task 1: Add the one missing scenario to the existing `StabilizationServiceTest` (AC: #1)
  - [x] New test method, e.g. `continuouslyOscillatingWeightNeverStabilizes`: feed a long run (at least 20+ readings, several full windows' worth) of weight values that alternate wide enough to keep `stdDev` above `properties.stdDevThreshold()` in *every* window, and assert every single `process()` call in the run returns `Optional.empty()` — not just the final one, since a false-positive on an intermediate call would be masked by only checking the last result.
  - [x] Pick oscillation amplitude deliberately larger than the amplitude used in `noisyWindowResetsConsecutiveCounterAndDelaysStabilization` (±100 around 1000, e.g. `900/1100` alternating) — that existing test's noisy window is *followed* by stable readings and is not meant to prove indefinite non-stabilization by itself, so don't just reuse its constant.
- [x] Task 2: Verify full existing coverage still passes and confirm no gaps remain (AC: #2, #3, #4, #5, #6)
  - [x] Re-run `StabilizationServiceTest` — ACs #2–#6 already have passing tests (see Dev Notes mapping below); this task is a verification pass, not new test-writing, unless review of the existing tests during implementation surfaces a real gap.
  - [x] Run `./mvnw test -Dtest=StabilizationServiceTest` and confirm all tests (existing + new) pass.
- [x] Task 3: Add real concurrency tests exercising `ConcurrentHashMap.compute()`'s atomicity guarantee under actual thread overlap, not just code inspection (AC: #7, #8)
  - [x] `concurrentReadingsOnSameScaleStabilizeExactlyOnce`: 20 threads via `ExecutorService`, all hammering the same `scaleId` with the same constant weight, released simultaneously via a shared `CountDownLatch` to maximize real overlap rather than accidental serialization by the test itself. Assert exactly one `StabilizationResult` is produced across all threads (not zero, not more than one) and its weight is correct.
  - [x] `concurrentReadingsOnDifferentScalesStabilizeIndependentlyWithoutCrossContamination`: 10 different `scaleId`s, each driven by its own thread in the same `ExecutorService`/latch setup, each converging to a distinct weight. Assert one result per scale with the correct, non-blended weight, and no exceptions/deadlocks (via a bounded `CountDownLatch.await` timeout).
  - [x] Ran both new tests 5x locally to check for flakiness under real thread scheduling — no failures observed.

## Dev Notes

- **Critical finding — this story is ~90% already done.** `src/test/java/com/serasa/balancas/stabilization/StabilizationServiceTest.java` already exists, was committed in story 3.1 (commit `cbfaa73`, "feat(e3): implement StabilizationService core algorithm..."), and already covers 5 of the 6 ACs listed here. Epic 6 was planned before this was noticed — don't re-implement from scratch. Do not delete or rewrite the existing file; add to it.
- **AC → existing test mapping** (all in `StabilizationServiceTest.java`):
  - AC #1 (never stabilizes) — **not covered, this is the actual gap to fill.**
  - AC #2 (stabilizes with correct average) — `stabilizesAfterConsecutiveStableWindows` (line 30).
  - AC #3 (drops to ~0, resets) — `weightBelowResetThresholdClearsStateForNextTruck` (line 92) uses a `5.0kg` reading against a `10.0kg` reset threshold, which satisfies "drops to ~0" in spirit (well below threshold).
  - AC #4 (two sessions, no buffer mixing) — `secondSessionOnSameScaleStabilizesIndependentlyAfterReset` (line 111), asserts the second session's average is exactly the new plate's weight with no blending from the first.
  - AC #5 (single spike tolerated) — `toleratesSingleOutlierWithoutPreventingStabilization` (line 44).
  - AC #6 (`alreadyPersisted` lock) — `doesNotPersistTwiceForSameStableSession` (line 78).
  - Two additional tests beyond the 6 ACs already exist too: `doesNotStabilizeBeforeWindowFills` and `independentScalesMaintainIndependentState` — leave these as-is, they're valid extra coverage.
- **Test setup already established** — reuse it exactly, don't invent new conventions:
  - `StabilizationProperties properties = new StabilizationProperties(5, 2.0, 3, 10.0)` — small N=5/σ_max=2.0/M=3/reset<10kg values chosen deliberately to keep tests fast and readable (vs. production's likely N=15/σ_max=5/M=3 from FR11). Keep using these same small numbers for the new test for consistency — don't introduce a second properties instance with different constants unless the oscillation scenario specifically requires it.
  - Plain JUnit 5 (`@Test`), no Spring context, no Mockito — `StabilizationService(properties)` is instantiated directly per test via the field initializer. This matches the story's own AC framing ("validated independently of HTTP and persistence") and was an explicit call-out in story 3.1's tasks (`@ExtendWith(MockitoExtension.class)` was considered but the service has zero external dependencies to mock, so plain JUnit is simpler and was what got built).
  - AssertJ (`assertThat`) for assertions, matching every other test in the class.
  - `SCALE_ID = "SCALE-1"`, `PLATE = "ABC1234"` constants already defined at the top of the class — reuse them for the new test unless the scenario needs a dedicated plate/scale (it doesn't).
- **`StabilizationService.process(scaleId, plate, weightKg)` semantics** (`src/main/java/com/serasa/balancas/stabilization/StabilizationService.java`): returns `Optional<StabilizationResult>`, populated only on the exact reading where `consecutiveStableWindows >= properties.consecutiveWindows()` AND `!state.alreadyPersisted` — every other call returns `Optional.empty()`. For the new oscillating-forever test, the assertion must be inside the loop (`assertThat(service.process(...)).isEmpty()` per iteration), not just checked once after the loop — the existing `noisyWindowResetsConsecutiveCounterAndDelaysStabilization` test already demonstrates this per-iteration assertion pattern for its noisy segment (lines 66–68).
- **No other files need to change.** This story does not touch `StabilizationService.java`, `ScaleState.java`, `StabilizationResult.java`, or `StabilizationProperties.java` — it is test-only.
- **Not in scope**: Story 6.2 (ESP32 simulator) is a separate story; do not start on it here.
- **Concurrency tests (AC #7, #8) — scope added after initial story draft.** `StabilizationService.process()` relies on `ConcurrentHashMap.compute()` for atomicity across concurrent scale readings (see `StabilizationService.java` line 21). The original story only covered this via code inspection/sequential tests. Real multi-threaded tests are needed to actually exercise the guarantee under thread contention, not just assume it from reading the code:
  - Use `ExecutorService` + `CountDownLatch` (all worker threads block on `latch.await()` until released together with `latch.countDown()`) to maximize genuine thread overlap — submitting tasks to an executor alone doesn't guarantee they run concurrently; the shared latch forces it.
  - Same-scale test: many threads send the *same constant weight* so ordering/interleaving is irrelevant to the outcome (stdDev is always 0 regardless of interleave order) — this isolates the atomicity assertion (exactly one persistence) from any race in *which* reading happens to arrive when.
  - Cross-scale test: independent scales, independent threads, distinct target weights per scale, to prove `ConcurrentHashMap`'s per-key isolation holds under genuine parallel writes to different keys.
  - Bound `doneLatch.await(...)` with a timeout and assert it returns `true`, so a deadlock fails the test loudly instead of hanging the build.

### Project Structure Notes

- Single file touched: `src/test/java/com/serasa/balancas/stabilization/StabilizationServiceTest.java` (existing file, add one test method).
- No new packages, no new production code, no new dependencies.
- Consistent with the project's feature-per-folder convention (`stabilization` package mirrors `src/main/java/com/serasa/balancas/stabilization/`).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 6.1: StabilizationService Unit Tests] — acceptance criteria and task list origin.
- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.1: StabilizationService — Core Algorithm] — FR11 sliding-window algorithm this test class validates (N=15, σ_max=5kg, M=3 in production `application.yml`; tests use smaller N=5/σ_max=2.0/M=3 for speed).
- [Source: src/test/java/com/serasa/balancas/stabilization/StabilizationServiceTest.java] — existing test class; already covers ACs #2–#6, only AC #1 is missing.
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationService.java] — `process()` method under test; `markPersistenceFailed()` is unrelated to this story (used by Epic 2's retry path).
- [Source: git commit cbfaa73] — confirms `StabilizationServiceTest.java` was committed as part of story 3.1, not left uncommitted (superseding the "uncommitted state" caveat noted in story 3.2's dev notes, which predates this commit).

## Change Log

- 2026-07-11: Added `continuouslyOscillatingWeightNeverStabilizes` test covering AC #1; verified ACs #2–#6 already covered. Test-only change, no production code touched.
- 2026-07-11: Scope expanded to add real concurrency tests (AC #7, #8) — `concurrentReadingsOnSameScaleStabilizeExactlyOnce` and `concurrentReadingsOnDifferentScalesStabilizeIndependentlyWithoutCrossContamination`, using `ExecutorService` + `CountDownLatch` to verify `ConcurrentHashMap.compute()`'s atomicity guarantee under genuine thread overlap rather than by code inspection alone. Test-only change.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

### Completion Notes List

- Added `continuouslyOscillatingWeightNeverStabilizes` test to `StabilizationServiceTest`, feeding 25 alternating 900/1100 readings and asserting `Optional.empty()` on every single `process()` call (AC #1). ACs #2–#6 were re-verified as already covered by existing tests, no gaps found; no production code changes needed.
- Added two real multi-threaded concurrency tests (AC #7, #8) using `ExecutorService` + `CountDownLatch` to force genuine thread overlap:
  - `concurrentReadingsOnSameScaleStabilizeExactlyOnce`: 20 threads x 20 readings of the same constant weight against one `scaleId`, released simultaneously — asserts exactly one persisted result with the correct weight, verifying `ConcurrentHashMap.compute()`'s atomicity actually holds under contention rather than by inspection.
  - `concurrentReadingsOnDifferentScalesStabilizeIndependentlyWithoutCrossContamination`: 10 distinct `scaleId`s, each with its own thread converging to its own target weight, released simultaneously — asserts one correct, non-blended result per scale and no timeouts/deadlocks.
  - Both new tests were run 5x locally back-to-back to check for flakiness under real thread scheduling; no failures observed.
- `./mvnw test -Dtest=StabilizationServiceTest`: 11/11 tests pass.
- Full regression suite (`./mvnw test`): 79/79 tests pass, build success.

### File List

- src/test/java/com/serasa/balancas/stabilization/StabilizationServiceTest.java (modified)
