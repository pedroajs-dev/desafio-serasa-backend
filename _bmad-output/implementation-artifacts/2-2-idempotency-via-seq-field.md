---
baseline_commit: 7df332d
---

# Story 2.2: Idempotency via seq Field

Status: done

## Story

As the system,
I want to discard duplicate readings identified by scaleId + seq,
so that ESP32 retries do not corrupt the stabilization buffer.

## Acceptance Criteria

1. **Given** a reading with an optional `seq` field, **when** the same `scaleId` + `seq` combination has already been processed, **then** the reading is silently discarded (never reaches `StabilizationService.process()`) **and** the endpoint still returns `202 Accepted`.
2. **And** if `seq` is absent (null) from the payload, the reading is processed normally — no idempotency check applies, unconditionally forwarded to `StabilizationService.process()` as before this story.
3. **And** auth (`X-Scale-Key`) is still checked *before* the idempotency check, consistent with Story 2.1's ordering rule that unauthenticated requests must never touch any scale's state — a wrong-key request with a duplicate `seq` must return `401`, not silently `202`.
4. **And** the existing behavior for authenticated, non-duplicate readings (routing to `StabilizationService.process()` → `WeighingPersistencePort.persist()` on a `202`) is unchanged — this story only adds a discard path in front of it.

## Tasks / Subtasks

- [x] Task 1: Add optional `seq` field to `ScaleReadingRequest` (AC: #1, #2)
  - [x] Add `Long seq` (nullable, no `@NotNull`/`@NotBlank`) to the existing record `src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java`
  - [x] Also add optional `Long timestamp` per the epic's parenthetical `{id, plate, weight}` "(or extended with seq/timestamp)" — nullable, unused by any logic in this story (no AC references it); just accepted so richer ESP32 payloads don't fail validation
  - [x] Do **not** add `@NotNull` to either field — both must remain fully optional per AC #2
- [x] Task 2: Create `ReadingIdempotencyService` (AC: #1, #2)
  - [x] New class `com.serasa.balancas.scalereading.ReadingIdempotencyService`, `@Service`, mirroring `StabilizationService`'s style (single in-memory concurrent structure, no persistence, no Spring config needed beyond `@Service`)
  - [x] Backing store: `ConcurrentHashMap<String, Boolean>` (or `java.util.concurrent.ConcurrentHashMap.newKeySet()`), keyed by `scaleId + ":" + seq`
  - [x] Method `boolean isDuplicate(String scaleId, Long seq)`:
    - if `seq == null`, return `false` immediately (no check, no insertion — AC #2)
    - otherwise, atomically check-and-insert the key in a single map operation (e.g. `keys.putIfAbsent(key, Boolean.TRUE) != null`, or `!set.add(key)`) — do NOT do a separate `contains()` then `add()`, that's a check-then-act race between concurrent retries of the same `scaleId:seq`
    - return `true` if the key was already present (duplicate), `false` if this is the first time seen (and it is now recorded)
- [x] Task 3: Wire into `ScaleReadingController` (AC: #1, #2, #3, #4)
  - [x] Constructor-inject `ReadingIdempotencyService` alongside the existing three dependencies in `src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java`
  - [x] After the existing auth check (unchanged, still first) and *before* the existing `stabilizationService.process(...)` call, insert: `if (readingIdempotencyService.isDuplicate(request.id(), request.seq())) { return ResponseEntity.status(HttpStatus.ACCEPTED).build(); }`
  - [x] Everything after that line (the `process().ifPresent(...)` block and final `return`) stays exactly as-is
- [x] Task 4: Tests (AC: #1, #2, #3, #4)
  - [x] In `src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java`, following the existing `@SpringBootTest @AutoConfigureMockMvc` + real-H2 convention (no mocks):
  - [x] Case: same `scaleId` + `seq` sent twice → second request returns `202` but does not create a second `WeighingRecord`/advance stabilization state further than the first call would have (assert via a call count or state-visible side effect, e.g. send 17 identical stabilizing readings all sharing one `seq` and confirm only the very first is "seen" — simplest proof: two identical requests with the same `seq`, assert both return `202` and that a duplicate does not, by itself, contribute a second entry anywhere observable; the cleanest assertion is at the `ReadingIdempotencyService` unit level, see below)
  - [x] Case: same `scaleId` with two *different* `seq` values (or one with `seq` and one without) → both processed normally, no discard
  - [x] Case: duplicate `seq` for scale A does not block the same `seq` value for a *different* scale B (key is `scaleId + seq`, not `seq` alone)
  - [x] Case: request with wrong `X-Scale-Key` and a `seq` that was already used legitimately → still `401`, not `202` (proves auth ordering, AC #3)
  - [x] Case: omitting `seq` entirely (existing "valid payload" tests) continues to return `202` exactly as before — regression check that Task 1's new optional field didn't change existing passing tests
  - [x] Add a focused unit test `ReadingIdempotencyServiceTest` (plain JUnit, no Spring context — same style as would apply to a pure in-memory service): `isDuplicate` returns `false` then `true` for the same `scaleId`+`seq` pair; returns `false` every time when `seq` is `null`; different `scaleId` with the same `seq` are independent

## Dev Notes

- **This is an additive guard in front of existing, working logic — do not restructure `ScaleReadingController` beyond inserting one `if` block.** Story 2.1 is done and reviewed; `StabilizationService.process()` → `WeighingPersistencePort.persist()` wiring is correct and must not be touched. The idempotency check's only job is to short-circuit *before* that call for a duplicate `seq`.
- **Ordering is load-bearing**: auth check → idempotency check → `stabilizationService.process()`. Story 2.1's Dev Notes established that unauthenticated requests must never reach `StabilizationService`'s per-scale state (an attacker with no valid key could disrupt another scale's buffer). The same reasoning extends here: don't move the idempotency check before auth, or a wrong-key request could probe/pollute the idempotency set for a `scaleId` it doesn't own.
- **`seq` type**: use `Long` (matches ESP32 sending a monotonically increasing integer counter; the epic doesn't specify a wire format, and `Long` is the simplest boxed-nullable numeric type consistent with `weight` already being boxed `Double` in the same record for the same "optional/nullable" reason). Do not use a primitive `long` — it can't represent "absent."
- **Concurrency correctness matters here specifically because this guards against ESP32 *retries***, which by definition can race with the original request (e.g., a retry fired because the ESP32 didn't see the 202 in time, while the original is still in flight). Use a single atomic map operation (`putIfAbsent`/`Set.add`) for the check-and-record step — never split it into a `contains()` read followed by a separate `add()` write, since two concurrent threads could both pass the read before either writes.
- **No eviction/TTL for the idempotency set in this story.** `StabilizationService`'s `ScaleState` map has the same unbounded-growth characteristic (already logged as deferred work in `_bmad-output/implementation-artifacts/deferred-work.md` from Story 2.1's review) — this story's map will have the same property and the same acceptance: bounded by the number of distinct `(scaleId, seq)` pairs ever sent by known, authenticated scales. Do not add TTL/eviction logic; it's out of scope and not requested by the AC. If you want to flag it, add one line to `deferred-work.md` under a new "Deferred from story 2.2" heading rather than building eviction.
- **Where NOT to put this logic**: do not put the idempotency check inside `StabilizationService` itself — `StabilizationService.process()` is fully tested (Story 3.1/3.2) and its Story 2.1 Dev Notes explicitly say "Do not modify `StabilizationService`... this story is pure HTTP wiring, not new business logic." The same constraint applies here: idempotency is an ingestion-layer concern (duplicate *requests*), not a stabilization-layer concern (duplicate *weight readings*, which is a different thing StabilizationService already isn't trying to solve). Keep `ReadingIdempotencyService` in the `scalereading` package, a peer of `ScaleReadingController`, not inside `stabilization`.
- **No Lombok, plain classes/records** — matches this project's established convention (see Story 2.1, 1.4).
- **Package placement**: `com.serasa.balancas.scalereading` (existing package from Story 2.1) — this is an ingestion-endpoint concern, add the new service and its test there, not a new top-level package.

### Project Structure Notes

- Modified: `src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java` (add `seq`, `timestamp`)
- Modified: `src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java` (inject `ReadingIdempotencyService`, add discard check)
- New: `src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java`
- Modified: `src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java` (new cases)
- New: `src/test/java/com/serasa/balancas/scalereading/ReadingIdempotencyServiceTest.java`

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.2: Idempotency via seq Field] — acceptance criteria and task list origin.
- [Source: _bmad-output/implementation-artifacts/2-1-ingestion-endpoint-with-authentication.md] — this story's direct predecessor: establishes `ScaleReadingController`/`ScaleReadingRequest` shape, the auth-before-everything-else ordering rule, the "no service layer except for genuinely stateful concerns" pattern (`StabilizationService` is the precedent for `ReadingIdempotencyService`), and the deferred-work convention for out-of-scope findings.
- [Source: src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java] — exact current wiring this story extends; auth check at lines 37-40, process/persist at lines 42-50, must remain intact.
- [Source: src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java] — current record shape (`id`, `plate`, `weight`, all required) to extend with two new optional fields.
- [Source: src/main/java/com/serasa/balancas/stabilization/StabilizationService.java] — precedent for a `@Service` wrapping a single `ConcurrentHashMap` keyed by scale-derived string, atomic `compute`-style update — same pattern to follow for `ReadingIdempotencyService`, at a much simpler scale (boolean presence, not full state machine).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — existing convention for logging accepted-risk unbounded-map growth; extend rather than duplicate if flagging the same concern for the idempotency set.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

### Completion Notes List

- Added optional `seq`/`timestamp` fields to `ScaleReadingRequest` (both nullable, no validation constraints).
- Added `ReadingIdempotencyService` (`@Service`, `ConcurrentHashMap.newKeySet()` keyed by `scaleId + ":" + seq`, atomic `add()` check-and-insert — no separate contains/add race).
- Wired into `ScaleReadingController`: auth check unchanged (still first), idempotency check inserted immediately after auth and before `StabilizationService.process()`; duplicate readings return `202` without reaching stabilization/persistence.
- Full regression suite passes: 58 tests total (0 failures, 0 errors) — up from 51, including 3 new `ReadingIdempotencyServiceTest` cases and 4 new `ScaleReadingControllerTest` cases (duplicate seq discarded, different seq values both processed, same seq independent across scales, wrong key with reused seq still 401).
- No TTL/eviction added for the idempotency key set, per Dev Notes — same accepted-risk profile as `StabilizationService`'s `ScaleState` map; not flagged again in `deferred-work.md` since it's the same already-logged concern class, not a new one.

### File List

- `src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java` (modified — added `seq`, `timestamp`)
- `src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java` (modified — injected `ReadingIdempotencyService`, added discard check)
- `src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java` (new — `isDuplicate` + `evict`)
- `src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java` (modified — added 4 idempotency test cases, rewrote duplicate-discard test to prove persistence side-effect, updated `readingBody` helper)
- `src/test/java/com/serasa/balancas/scalereading/ReadingIdempotencyServiceTest.java` (new — 5 unit cases incl. eviction)
- `src/test/java/com/serasa/balancas/scalereading/ScaleReadingPersistenceFailureTest.java` (new — regression: persist failure evicts seq, retry reprocessed)

### Change Log

- 2026-07-10: Implemented Story 2.2 — idempotency via optional `seq` field, discarding duplicate scaleId+seq readings before they reach `StabilizationService`.
- 2026-07-10: Addressed code review findings — all 4 patches resolved: (1) persist-failure `seq` eviction consistency via `ReadingIdempotencyService.evict()`, (2) rewrote the vacuous duplicate-discard test to prove a persistence side-effect, (3) added `@SpyBean` `process()` verification to the two secondary idempotency tests, (4) renamed `isDuplicate()` → `registerAndCheckDuplicate()`. 3 items deferred (accepted risks). Full suite: 61 tests, 0 failures.

### Review Findings

- [x] [Review][Patch] (was Decision, resolved → option c) Idempotency key recorded before successful processing — `isDuplicate()` claimed the `seq` key before `process()`/`persist()`, so a client retry reusing the same `seq` after a persist failure would be discarded (202), silently dropping the reading and contradicting Story 2.1's `markPersistenceFailed()` retry path. **Fixed:** added `ReadingIdempotencyService.evict(scaleId, seq)` and call it in the controller's `catch` block alongside `markPersistenceFailed()`, so both recovery mechanisms stay consistent. Regression test `ScaleReadingPersistenceFailureTest.failedPersistEvictsSeqSoRetryIsReprocessed` (mocks `persist()` to throw then succeed) confirms the retry with the same `seq` is reprocessed and `persist()` is invoked twice. [src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java, ScaleReadingController.java]
- [x] [Review][Patch] `duplicateSeqIsDiscardedButStillReturns202` proved nothing (no transaction opened → count trivially 0). **Fixed:** rewritten with a real transaction — a control scale sends 17 stabilizing readings with distinct `seq` values (→ 1 record, transaction `COMPLETED`), a second scale sends 17 identical readings all sharing `seq=1` (→ only the first survives dedup, buffer starved, 0 records, transaction stays `IN_TRANSIT`). Asserts exactly one record persisted, so a broken guard would fail the test. [src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java:158]
- [x] [Review][Patch] `differentSeqValuesAreBothProcessed` and `sameSeqOnDifferentScalesIsNotTreatedAsDuplicate` asserted only `202`, which a discarded duplicate also returns. **Fixed:** added a `@SpyBean StabilizationService` and `verify(...).process(...)` assertions — the distinct-seq test proves `process()` is called twice for the scale, and the cross-scale test proves `process()` is called once per scale, so a wrongly-swallowed reading would now fail the test. [src/test/java/com/serasa/balancas/scalereading/ScaleReadingControllerTest.java]
- [x] [Review][Patch] `isDuplicate()` was a query name for a mutating operation. **Fixed:** renamed to `registerAndCheckDuplicate()` with Javadoc making the state mutation and return semantics explicit; updated the controller call site and unit tests. [src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java]
- [x] [Review][Defer] Unbounded in-memory `seenKeys` set — no TTL/eviction/size cap; grows one entry per distinct `(scaleId, seq)` for process lifetime. Accepted architectural risk per Dev Notes (same profile as `StabilizationService`'s `ScaleState` map). [src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java:9] — deferred, accepted design
- [x] [Review][Defer] No distributed/persistent idempotency state — behind >1 replica duplicates on different instances both pass; a restart re-accepts previously-seen readings. Accepted single-instance/in-memory limitation for this delivery. [src/main/java/com/serasa/balancas/scalereading/ReadingIdempotencyService.java:9] — deferred, accepted design
- [x] [Review][Defer] No validation on `seq` (negative/zero/fixed) — a device sending a constant `seq` permanently poisons that scale (every subsequent reading discarded as duplicate). Out of story scope; robustness hardening. [src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java:6] — deferred, pre-existing scope

**Dismissed as noise (7):** delimiter collision in `scaleId + ":" + seq` (false positive — `seq` is a numeric `Long` always appended last, so the concatenation is injective; no two distinct pairs collide); null/blank `id` (blocked by `@NotBlank` + `@Valid` → 400 before the controller body); unused `timestamp` field (explicitly required by Task 1); "202 before payload validation" (false — `@Valid @RequestBody` runs before the method body); same-key/different-payload dropped (correct idempotency semantics — `seq` identifies the logical reading); duplicate 202 indistinguishable from processed (matches AC1's bodyless-202 contract); `Boolean` value type in `newKeySet()` (cosmetic).
