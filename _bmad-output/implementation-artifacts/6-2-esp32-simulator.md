---
baseline_commit: 079dac96fab0fc82c10d38496c19330f7a161ee1
---

# Story 6.2: ESP32 Simulator

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want a runnable simulator that fires POST requests every 100ms with decreasing noise,
so that I can validate the full pipeline end-to-end using the real HTTP protocol.

## Acceptance Criteria

1. **Given** the application is running on localhost:8080, **when** the simulator is started for a configured scaleId + plate + target weight, **then** it sends `POST /api/scales/readings` every 100ms with `X-Scale-Key` header.
2. **And** initial readings have ±50kg noise, decreasing to ±2kg over ~5 seconds.
3. **And** `seq` increments with each request to exercise idempotency.
4. **And** after stabilization is expected, the simulator logs "done" and stops.

## Tasks / Subtasks

- [x] Task 1: Create standalone simulator class (AC: #1)
  - [x] New class `ScaleSimulator` in package `com.serasa.balancas.simulator`, with a `public static void main(String[] args)` — **not** a Spring bean, **not** `@SpringBootTest`. It must be runnable independently of (but talking over HTTP to) the already-running app, matching the epic task's explicit choice ("standalone Java main class").
  - [x] Use `java.net.http.HttpClient` (JDK 21 built-in, `java.net.http.HttpRequest`/`HttpResponse`) to POST — **do not add a new Maven dependency**. The project has no `RestTemplate` bean and no `spring-webflux`/`WebClient` on the classpath (verified: `pom.xml` only has `spring-boot-starter-web`, `-data-jpa`, `-validation`; no other HTTP client dependency exists anywhere in `src/main`). `java.net.http.HttpClient` is the only zero-new-dependency option and needs no Spring context, consistent with "standalone main class."
  - [x] Serialize the request body manually as JSON string (`{"id":"...","plate":"...","weight":...,"seq":...}`) or add `com.fasterxml.jackson.databind.ObjectMapper` (already on the classpath transitively via `spring-boot-starter-web`) to serialize a small local record — prefer the `ObjectMapper` approach since it's already available and avoids manual JSON-escaping bugs.
- [x] Task 2: Configure simulator parameters (AC: #1, #2, #3)
  - [x] Hardcoded/constant config at the top of `ScaleSimulator` (or `main(String[] args)` positional args with sane defaults): `baseUrl` (default `http://localhost:8080`), `scaleId`, `apiKey`, `plate`, `targetWeightKg`, `totalDurationMs`.
  - [x] Default `scaleId`/`apiKey`/`plate` must match a real seeded row in `src/main/resources/data.sql` so a manual run actually authenticates: use `scaleId = "BAL-001"`, `apiKey = "key-sorriso-001"` (`data.sql:12`), and `plate = "ABC1D23"` (`data.sql:8`) as defaults.
  - [x] Send `X-Scale-Key` header set to `apiKey`, matching `ScaleReadingController.receive()`'s expected header name exactly (`src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java:36`).
- [x] Task 3: Implement noise decay and request loop (AC: #2, #3)
  - [x] `noise = initialNoiseKg * max(0, 1 - elapsedMs / decayMs)` with `initialNoiseKg = 50.0` and `decayMs = 5000` (from the epic's "±50kg noise, decreasing to ±2kg over ~5 seconds" — note the floor is 0, not 2kg; the ±2kg-at-5s figure falls out naturally once `elapsedMs` reaches `decayMs` only if you clamp the *minimum* noise to ~2kg rather than letting it hit exactly 0 — apply `noise = max(2.0, initialNoiseKg * max(0, 1 - elapsedMs / decayMs))` so noise never fully vanishes, matching "decreasing to ±2kg" rather than "decreasing to 0kg").
  - [x] Reported weight per tick: `targetWeightKg + ThreadLocalRandom.current().nextDouble(-noise, noise)`.
  - [x] Loop every 100ms (`Thread.sleep(100)` between requests) for `totalDurationMs`, incrementing a `long seq` starting at 1 on every request — this is what exercises idempotency end-to-end (each `seq` is new, so none should be discarded as duplicates; a duplicate-seq resend is not required by the ACs and is out of scope here).
  - [x] Log each request + response status to stdout (AC from epic Task 4) — e.g. `System.out.printf("seq=%d weight=%.1f status=%d%n", seq, weight, response.statusCode())`.
- [x] Task 4: Stop condition (AC: #4)
  - [x] After the loop completes (`totalDurationMs` elapsed — the story's own stabilization math converges well before this given production `application.yml` values: N=15 × 100ms = 1.5s to fill one window, M=3 consecutive stable windows ≈ 4.5s once noise is low enough, so `totalDurationMs` of ~8000–10000ms comfortably covers stabilization), log `"done"` and return from `main`.
  - [x] This is a **fixed-duration** stop, not a "poll until stabilized" stop — the simulator has no visibility into `StabilizationService`'s internal state (it's a separate JVM process talking only over HTTP), so it cannot detect stabilization directly. Do not attempt to call back into the app to check state; simply run for the fixed duration and log "done".
- [x] Task 5 (post-implementation addition, user-requested): Demo run ends in a completed weighing, not a log-and-skip
  - [x] Before sending readings: `POST /api/trucks` with a freshly-generated unique plate (`"SIM" + currentTimeMillis % 100000`, avoiding collision with seeded plates on repeat runs) and `tare = 8500.0`.
  - [x] `POST /api/transactions` with the new truck's id, `grainTypeId = 1` (Soja), `branchId = 1` (Filial Sorriso — matches `BAL-001`'s branch).
  - [x] Send all simulated readings using that truck's plate (not the old hardcoded `ABC1D23`).
  - [x] After the reading loop finishes, `GET /api/transactions/{id}` and print the closing summary: `status`, `netWeightKg`, `loadCost`.

## Dev Notes

- **This is the last story before Epic 7 (README/delivery)** — after this, only documentation work remains. Keep the simulator self-contained; it is a delivery/demo artifact, not production code, and should not be wired into `BalancasApplication`'s Spring context or `@ComponentScan`.
- **No new Maven dependency needed.** `java.net.http.HttpClient` ships with the JDK (project targets `java.version=21`, confirmed in `pom.xml:30`) and Jackson's `ObjectMapper` is already transitively available via `spring-boot-starter-web`. Do not add `RestTemplate`, `WebClient`/`spring-webflux`, or any third-party HTTP client — none exist anywhere in this codebase today (verified via grep across `src/`), and introducing one just for a demo simulator would be disproportionate.
- **Auth contract to match exactly** (`src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java:34-42`): header name is `X-Scale-Key` (case as shown); the endpoint validates via `scaleRepository.findByIdAndApiKey(request.id(), scaleKey)` — a mismatch on `id` (scaleId) *or* `apiKey` returns 401. Use the real seeded pair (`BAL-001` / `key-sorriso-001`) as defaults so a naive `./mvnw spring-boot:run` + simulator run actually works out of the box for a reviewer.
- **Request body contract** (`src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java`): `record ScaleReadingRequest(@NotBlank String id, @NotBlank String plate, @NotNull Double weight, Long seq, Long timestamp)`. `id` is the scaleId (not a reading id) — field naming is a known source of confusion, don't rename it, just match the record exactly. `seq` and `timestamp` are optional (`Long`, nullable) but the story explicitly wants `seq` populated to exercise idempotency, so always send it.
- **Endpoint returns 202 Accepted immediately regardless of stabilization** (`ScaleReadingController.receive()` always returns `ResponseEntity.status(HttpStatus.ACCEPTED).build()` whether or not `stabilizationService.process(...)` triggered a persist) — do not expect or parse any response body; only log the HTTP status code.
- **Superseded (Task 5)**: `data.sql` seeds `Truck`/`Scale`/`Branch`/`GrainType` rows but no `TransportTransaction` — originally this meant `WeighingPersistenceService.persist()` would log-and-skip. Per explicit follow-up request, the simulator now creates its own truck (`POST /api/trucks`) and opens its own transaction (`POST /api/transactions`) before sending readings, so the demo run now ends in an actual `COMPLETED` transaction with a real `netWeightKg`/`loadCost`, not a skip. The endpoint contracts: `POST /api/trucks` body `{licensePlate, tare}` → `Truck` (id, licensePlate, tare) (`src/main/java/com/serasa/balancas/truck/TruckController.java:24-29`); `POST /api/transactions` body `{truckId, grainTypeId, branchId}` → `TransportTransactionResponse` (`src/main/java/com/serasa/balancas/transporttransaction/TransportTransactionController.java:42-59`, `TransportTransactionRequest.java`); `GET /api/transactions/{id}` → same response shape including `status`, `netWeightKg`, `loadCost` (`TransportTransactionResponse.java`). `grainTypeId=1`/`branchId=1` map to the first seeded rows in `data.sql` (Soja / Filial Sorriso), matching `BAL-001`'s branch.
- **Unique plate per run**: the truck's `licensePlate` column has a unique constraint (`Truck.java:20`, `@Column(unique = true)`) — reusing a fixed plate would make a second simulator run fail on truck creation. The plate is generated per-run from `System.currentTimeMillis()` instead of hardcoded.
- **Production stabilization params** (`src/main/resources/application.yml:21-25`): `window-size: 15`, `std-dev-threshold: 5.0`, `consecutive-windows: 3`, `reset-threshold-kg: 50.0`. These drive the `totalDurationMs` sizing suggested in Task 4 — do not hardcode a duration shorter than ~8s or stabilization may not have occurred yet when the simulator exits (it will still exit cleanly and log "done" per AC #4; it just won't have proven end-to-end stabilization in that run).
- **Testing**: this story's epic task list says "Implement simulator as a standalone Java main class (or test with `@SpringBootTest` disabled)" — no unit test is required for `ScaleSimulator` itself (it's an I/O-heavy demo tool, not business logic covered by an AC). Do not create a `ScaleSimulatorTest`; if you want a sanity check, run it manually against a locally running `./mvnw spring-boot:run` instance and confirm stdout logs 200/202 status codes and ends with "done".

### Project Structure Notes

- New package: `src/main/java/com/serasa/balancas/simulator/` (`ScaleSimulator.java`) — a new top-level feature-per-folder package, consistent with the project's existing convention (mirrors `stabilization/`, `weighingrecord/`, etc.), even though this one has no entity/repository/controller.
- No test file for this story (see Dev Notes — Testing).
- No changes to `application.yml`, `data.sql`, `pom.xml`, or `BalancasApplication.java`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 6.2: ESP32 Simulator] — acceptance criteria and task list origin.
- [Source: _bmad-output/planning-artifacts/epics.md#Requirements Inventory] — FR22 (ESP32 simulator fires POST every 100ms with decreasing noise, exercises idempotency).
- [Source: src/main/java/com/serasa/balancas/scalereading/ScaleReadingController.java] — target endpoint, auth header contract, always-202 response behavior.
- [Source: src/main/java/com/serasa/balancas/scalereading/ScaleReadingRequest.java] — exact request body shape (`id`, `plate`, `weight`, `seq`, `timestamp`).
- [Source: src/main/resources/data.sql] — seeded scaleId/apiKey/plate pairs to use as working defaults (`BAL-001`/`key-sorriso-001`/`ABC1D23`).
- [Source: src/main/resources/application.yml] — production stabilization params (`window-size: 15`, `std-dev-threshold: 5.0`, `consecutive-windows: 3`, `reset-threshold-kg: 50.0`) informing simulator run-duration sizing.
- [Source: pom.xml] — confirms no existing HTTP client dependency; `java.net.http.HttpClient` (JDK 21) is the zero-dependency choice.
- [Source: _bmad-output/implementation-artifacts/6-1-stabilizationservice-unit-tests.md] — sibling story in this epic; confirms `StabilizationService`'s sliding-window semantics this simulator is designed to exercise end-to-end over HTTP.

### Review Findings

- [x] [Review][Patch] Idempotency-guard collision on repeat runs — `seq` restarted at 1 and `SCALE_ID` is a fixed constant, but `ReadingIdempotencyService` keys duplicates on `scaleId:seq` in a process-lifetime `ConcurrentHashMap` (not per-plate). A second simulator run against the same still-running app would have reused `BAL-001:1`, `BAL-001:2`, ... already claimed by the first run, silently deduping the second run's early readings. Fixed: `seq` now seeded from `System.currentTimeMillis()` instead of `1`, so successive runs occupy disjoint `seq` ranges. [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:54-58]
- [x] [Review][Patch] No poll/retry before reading final transaction state — after the fixed 10s reading loop, `main` did a single immediate `GET /api/transactions/{id}` and printed whatever came back, risking a non-`COMPLETED` status if persistence hadn't landed yet. Fixed: added `awaitCompletedTransaction()`, polling up to 10 times at 300ms intervals until `status == "COMPLETED"` or attempts are exhausted, before printing the closing summary. [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:95-108]
- [x] [Review][Defer] Plate collision risk across runs within the same ~100s window — `"SIM" + (System.currentTimeMillis() % 100_000)` repeats every 100 seconds, and `Truck.licensePlate` is `@Column(unique = true)`; a second run in that window fails `createTruck` outright. [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:46] — deferred per user instruction, fails loudly (visible error) rather than silently corrupting a run, unlike the two patched issues above.
- [x] [Review][Defer] Single failed HTTP tick aborts the entire run — `sendReading` can throw `IOException`/`HttpTimeoutException` from `client.send(...)`; nothing in the reading loop catches it, so one network blip kills `main` mid-run, abandoning the already-created truck/transaction. [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:63] — deferred per user instruction; low likelihood against localhost, and failure is loud (uncaught exception, stack trace) rather than silent.
- [x] [Review][Defer] Weight noise has no lower clamp relative to `TRUCK_TARE_KG`, so a future change to `TARGET_WEIGHT_KG`/`INITIAL_NOISE_KG` could generate a physically implausible negative net weight; not reachable with current constants (32000±50 vs. tare 8500). [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:60-61] — deferred, pre-existing risk pattern only, not triggered by current values.
- [x] [Review][Defer] No defensive null-checks on JSON response fields (`truck.get("id")`, `transaction.get("status")`, etc.) before `.asLong()`/`.asText()` — would NPE if the backend response shape ever changed. [src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java:48,50-52,73-75] — deferred, backend contract is fixed and covered by existing controller tests; theoretical only for this throwaway demo script.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

- Manually verified end-to-end (first pass, hardcoded plate): started `./mvnw spring-boot:run`, ran `ScaleSimulator` directly (compiled with `javac` against the Maven dependency classpath), confirmed stdout logged 91 requests all with `status=202`, noise visibly decaying from ~±50kg to ~±2kg, and a final `done` line. App log showed the pre-Task-5 log-and-skip behavior as expected at that point.
- Manually verified end-to-end (second pass, after Task 5): re-ran against a fresh app instance — output showed `created truck id=4 plate=SIM39884 tare=8500,0`, `opened transaction id=1 status=IN_TRANSIT`, 93 readings all `status=202`, then `final transaction id=1 status=COMPLETED netWeightKg=23499.965168360424 loadCost=4229.993730304876`, then `done`. App log had zero warnings/errors this run — persistence succeeded on the first try, no skip path hit.
- Full regression suite (`./mvnw test`), run after each pass: 75/75 tests passed both times, 0 failures/errors — no existing test touches the new `simulator` package.

### Completion Notes List

- Implemented `ScaleSimulator` as a standalone `main` class (not a Spring bean) in a new `com.serasa.balancas.simulator` package, using `java.net.http.HttpClient` + Jackson `ObjectMapper` (both already on the classpath — no new Maven dependency added).
- First pass sent readings against the hardcoded seeded plate `ABC1D23`, which had no open transaction (log-and-skip). Per explicit follow-up instruction, reworked to self-provision a truck + open transaction before sending readings (Task 5), so the demo now ends in a real `COMPLETED` transaction.
- Simulator now: creates a truck with a timestamp-derived unique plate (`POST /api/trucks`), opens a transaction for it (`POST /api/transactions`, `grainTypeId=1`, `branchId=1`), sends all readings against that plate, then `GET`s the transaction after the loop and prints `status`/`netWeightKg`/`loadCost` as the closing summary.
- Noise decay implemented as `max(2.0, 50.0 * max(0, 1 - elapsedMs/5000))`, sent every 100ms for a fixed 10-second run, with `seq` incrementing from 1 each run to exercise idempotency (all distinct, so the idempotency-skip path isn't hit by design — that's covered by story 2.2's own tests, not this story's ACs).
- Stop condition for the reading loop is still fixed-duration (10s), not stabilization-polling, per original Dev Notes rationale — the transaction status is only read *after* the loop, once stabilization has had time to occur.
- No test file added, per story's explicit Dev Notes guidance (I/O-heavy demo tool, not business logic under an AC) — verified manually instead (see Debug Log References).

### File List

- `src/main/java/com/serasa/balancas/simulator/ScaleSimulator.java` (new)

## Change Log

- 2026-07-10: Implemented `ScaleSimulator` standalone main class; verified end-to-end against a running app instance and via full regression suite (75/75 passing). Status set to review.
- 2026-07-10: Reworked simulator (Task 5, user-requested) to create its own truck and open transaction before sending readings, and to print the closed transaction's final state after the run — demo now ends in a genuine `COMPLETED` weighing instead of a log-and-skip. Re-verified end-to-end and via full regression suite (75/75 passing).
- 2026-07-11: Code review — 2 patches applied (seq now seeded from timestamp to avoid idempotency-guard collisions across runs; final transaction state now polled up to 3s before printing, instead of a single immediate GET), 2 findings deferred (plate collision window, no per-tick exception handling), 2 pre-existing findings deferred (noise clamp, JSON null-checks). Re-verified end-to-end (fresh app instance: `COMPLETED`, real `netWeightKg`/`loadCost`) and full regression suite (75/75 passing). Status set to done.
