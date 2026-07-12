# Demo Script & Operational Dashboard

This is a presentation/demo layer built on top of the same HTTP endpoints
exercised by Story 6.2's `ScaleSimulator` and the automated test suite —
it does not introduce or test a new code path. Every weighing produced by
these scripts goes through the real flow (POST readings ->
`StabilizationService` -> `WeighingPersistencePort`), never a direct DB
insert, so nothing here bypasses the core weighing-creation requirement.

It exists to give a reviewer a friendly, one-command way to see a truck
weighing flow happen end-to-end and watch the resulting reports update live.

## What's included

- `scripts/demo_lib.py` — shared HTTP helpers (health check, create truck,
  create branch/grain type/scale, open transaction, stream readings with
  decaying noise, send departure readings, poll for completion) used by
  both scripts below. Not meant to be run directly.
- `scripts/demo.py` — creates one truck, opens a transaction, streams
  weighing readings with decaying noise over ~10 seconds (mirroring
  `ScaleSimulator`'s realistic ramp), polls the transaction until it
  completes, and prints a summary. Pass `--repeat N` to run N such cycles
  back to back in one invocation (default: 1) — handy for watching the
  dashboard's cards change a few times in a row. Opens the dashboard in
  your browser once per invocation, right before the cycles start (not
  once per cycle) so you're watching from cycle 1 onward; pass
  `--no-open-browser` to suppress that for repeated manual testing.
- `scripts/seed_demo_data.py` — first creates one extra branch ("Filial
  Primavera do Leste"), grain type ("Algodao"), and scale (`BAL-003`) via
  the real HTTP endpoints (`POST /api/branches`, `/api/grain-types`,
  `/api/scales` — same pattern as truck creation, never a direct DB write;
  re-running the script reuses these instead of duplicating them), then
  runs weighing cycles across all 3 branches/scales and 4 grain types
  (faster ~1.6s ramp per cycle, still passing through the same
  noise-decay and stabilization window logic, just compressed) so the
  dashboard has varied, realistic data to show instead of a single data
  point. Pass `--count N` to control how many cycles run (default: 20)
  and `--interval SECONDS` to control the pause between them (default:
  1.2s) — the defaults make a full run take roughly a minute, long enough
  to comfortably watch several 3s auto-poll refreshes land while the
  script is still running. Intended to be run once against a freshly
  started app. Opens the dashboard in your browser once, right after the
  branch/grain-type/scale setup and before the first cycle starts, so you
  can watch it live from the beginning; pass `--no-open-browser` to
  suppress that.
- `src/main/resources/static/dashboard.html` — a single static HTML page,
  served automatically by Spring Boot at `http://localhost:8080/dashboard.html`
  (same-origin as the API, no CORS setup needed). Polls the five report
  endpoints every 3 seconds and displays them in labeled sections.
- `scripts/test_anomaly_detection.py` — a separate, standalone script (not
  part of the guided demo flow above) that deliberately triggers the weight
  anomaly WARN log added to `WeighingPersistenceService`. See
  ["Manually verifying anomaly detection"](#manually-verifying-anomaly-detection)
  below.

## How to run

1. Start the application in one terminal:

   ```
   ./mvnw spring-boot:run
   ```

2. In a second terminal, seed varied report data once (recommended, from
   the repo root):

   ```
   python scripts/seed_demo_data.py
   python scripts/seed_demo_data.py --count 30 --interval 1.5
   ```

   This opens the dashboard in your default browser automatically before
   the weighing cycles start — no need to open it yourself first, and you
   won't miss the early cycles. The default run takes roughly a minute,
   so you can watch the cards update several times live before it's done;
   pass `--count`/`--interval` to make it longer or shorter.

3. Optionally, run the single-truck demo for one more live example (can be
   run multiple times against the same app instance), or pass `--repeat`
   to run several cycles back to back so you can watch the dashboard
   change a few times in a row:

   ```
   python scripts/demo.py
   python scripts/demo.py --repeat 4
   ```

   (On some systems the Python 3 executable is `python3`:
   `python3 scripts/seed_demo_data.py` / `python3 scripts/demo.py`)

   Each invocation opens the dashboard once, before its cycles start —
   `--repeat 4` still opens exactly one tab, not four. If you're
   re-running either script repeatedly during manual testing and don't
   want a new tab piling up each time, pass `--no-open-browser`; the
   dashboard URL is still printed either way as a fallback.

## What to expect

Both scripts check that the app is reachable at `http://localhost:8080`
(via `GET /api/branches`) first, and exit with a clear message if it isn't
running yet.

`seed_demo_data.py` first reports the extra branch/grain type/scale it created
(or reused, on a second run), then prints one concise line per cycle:

```
Checking for application at http://localhost:8080 ...
Application detected.

Created branch: name='Filial Primavera do Leste', id=3
Created grain type: name='Algodao', id=4
Created scale: id=BAL-003, branch_id=3

Dashboard: http://localhost:8080/dashboard.html
It auto-refreshes every 3s — leave the tab open to watch the seeded data land live.

Seeding 20 weighing cycles (1.2s apart)...

  [ 1/20] plate=SEED792980     grain=Soja     branch=Sorriso         scale=BAL-001  status=COMPLETED
  [ 2/20] plate=SEED833041     grain=Milho    branch=Sorriso         scale=BAL-001  status=COMPLETED
  ...
  [ 7/20] plate=SEED1030996    grain=Algodao  branch=PrimaveraLeste  scale=BAL-003  status=COMPLETED
  ...
  [20/20] plate=SEED15388119   grain=Sorgo    branch=Rondonopolis    scale=BAL-002  status=COMPLETED

--- Seed Summary ---
Weighings completed: 20/20
  Algodao: 2
  Milho: 6
  Soja: 6
  Sorgo: 6
```

(the dashboard opens in your default browser right after the "Dashboard:"
line above — before the first cycle runs, not after the last one — unless
`--no-open-browser` was passed)

Re-running the script skips creating the extra branch/grain type/scale a
second time:

```
Reusing existing branch: name='Filial Primavera do Leste', id=3
Reusing existing grain type: name='Algodao', id=4
Reusing existing scale: id=BAL-003, branch_id=3
```

`demo.py --repeat 4` prints one line per cycle:

```
Checking for application at http://localhost:8080 ...
Application detected.

Dashboard: http://localhost:8080/dashboard.html
It auto-refreshes every 3s — leave the tab open to watch cycles land live.

Running 4 weighing cycle(s)...

[cycle 1/4] plate=SIM656691      status=COMPLETED  readings=101  final_weight~32000.58kg  load_cost=R$ 4229.99
[cycle 2/4] plate=SIM788352      status=COMPLETED  readings=101  final_weight~32001.04kg  load_cost=R$ 4229.91
[cycle 3/4] plate=SIM916643      status=COMPLETED  readings=101  final_weight~32001.67kg  load_cost=R$ 4230.05
[cycle 4/4] plate=SIM45544       status=COMPLETED  readings=101  final_weight~32001.80kg  load_cost=R$ 4230.10

--- Demo Summary ---
Cycles completed: 4/4
```

(the dashboard opens right after the "Dashboard:" line above — exactly
once, before cycle 1, not once per cycle, even with `--repeat 4` — unless
`--no-open-browser` was passed)

By default each script opens the dashboard in your browser once per
invocation, right before its cycles start (not after they finish), so you
can watch the very first cycle land live instead of missing it. The URL
is printed either way as a fallback (headless environment, no default
browser configured, etc.). Pass
`--no-open-browser` to suppress the auto-open, e.g. when re-running either
script repeatedly during manual testing and you don't want a new tab each
time. The dashboard shows live-updating cards for cost by grain (a
Chart.js bar chart), scale ranking, average weighing duration by branch,
average margin by grain, scarcity alerts (highlighted in red/orange when
present), and a stock-level card with a progress bar per grain type
(green/yellow/red based on how close that grain's margin is to
`reports.scarcity-threshold`, so grains approaching scarcity are visible
before they cross the threshold, not just the ones already in the
alerts card). After `seed_demo_data.py` runs, these cards show all 4
grain types (Soja, Milho, Sorgo, and the seeded Algodao), all 3 branches,
and all 3 scales — with the scarcity-alerts card still showing only Milho
out of the box (Algodao's stock/margin were chosen to stay comfortably
under `reports.scarcity-threshold`, so it doesn't trigger a second alert
unless you edit its `currentStock` down yourself). No manual DB edit
needed either way. Any card whose values changed since the previous poll
briefly flashes so it's obvious the dashboard is live rather than static.

## Notes

- Every truck's license plate and every reading's sequence number are
  derived from the current timestamp, so re-running either script
  repeatedly won't collide with unique constraints from a previous run.
- If the dashboard can't reach the API mid-poll (e.g. the app was
  restarted), affected sections show an inline warning instead of stale
  or broken data, and a banner appears if all sections fail at once.
- After each weighing cycle, both scripts send a few low-weight readings
  (~5kg) to simulate the truck driving off the scale. `StabilizationService`
  keeps its `ScaleState` for a given scale in memory for the life of the
  running JVM, not per script invocation or per cycle — once a weighing
  stabilizes, that scale stays "spent" until a reading below
  `resetThresholdKg` (50kg) arrives, by design, to prevent double-persisting
  the same weighing. Without the departure step, only the first weighing
  against a given scale (across an entire app session) would ever reach
  `COMPLETED`.
- Milho's seeded `currentStock` (`src/main/resources/data.sql`) was
  lowered from 8000 to 2000 so its calculated margin (`0.20 - 0.15 * ratio`
  = 0.1925) clears the `reports.scarcity-threshold` (0.18) from boot. This
  is a cadastral/master-data seed value (Epic 1), not a derived weighing
  artifact, so adjusting it doesn't conflict with the "no shortcuts for
  weighing creation" principle — it's a starting condition, not a
  fabricated result.

## Manually verifying anomaly detection

`WeighingPersistenceService.persist()` flags (via a `WARN` log, not a
block — see the "Detecção de anomalia de peso" section in the main
[`README.md`](../README.md)) any stabilized `grossWeightKg` that exceeds a
truck's plausible carrying capacity (`tare × (1 + anomaly-detection.max-
payload-multiplier)`, configured in `application.yml`). This behavior is
already covered by an automated test
(`WeighingPersistenceServiceTest.logsAnomalyButStillPersistsAndCompletes
TransactionWhenGrossWeightFarExceedsCapacity`), but that test can only
assert on captured log output inside the JUnit process — it can't show you
what the WARN actually looks like coming out of a real running app, which
is useful when reviewing the feature or checking log formatting/visibility
by eye.

`scripts/test_anomaly_detection.py` exists for that manual check. It:

1. Creates a truck with a known tare (8500kg).
2. Opens a transaction for that truck.
3. Reads `anomaly-detection.max-payload-multiplier` directly out of
   `src/main/resources/application.yml` (rather than hardcoding a guess),
   computes the threshold (`tare × (1 + multiplier)`), and streams
   realistic, noisy readings targeting a stabilized weight well above that
   threshold (`tare + tare × (multiplier + 1)`).
4. Polls the transaction until it reaches `COMPLETED`, confirming the
   anomalous weighing is still persisted and the transaction still
   completes normally (log-and-continue, not a block).
5. Prints a summary (plate, tare, target weight sent, computed threshold)
   and an explicit instruction to check the running application's console
   for an `Anomaly detected` line containing that plate.

Run it the same way as the other scripts, with the app already started:

```
python scripts/test_anomaly_detection.py
```

Then check the terminal running `./mvnw spring-boot:run` for a line like:

```
WARN ... c.s.b.w.WeighingPersistenceService : Anomaly detected: grossWeightKg exceeds plausible
capacity for plate=ANOM80872 (scaleId=BAL-001): grossWeightKg=42497.63, threshold=34000.0
(tare=8500.0, maxPayloadMultiplier=3.0)
```

This script only triggers the condition and reports what it sent — it has
no access to the running app's stdout, so it cannot itself assert the WARN
was logged. It's a trigger + manual-check helper, complementary to (not a
replacement for) the automated assertion in `WeighingPersistenceServiceTest`.
