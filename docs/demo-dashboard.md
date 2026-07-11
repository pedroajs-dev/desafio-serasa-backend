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
  open transaction, stream readings with decaying noise, send departure
  readings, poll for completion) used by both scripts below. Not meant to
  be run directly.
- `scripts/demo.py` — creates one truck, opens a transaction, streams
  weighing readings with decaying noise over ~10 seconds (mirroring
  `ScaleSimulator`'s realistic ramp), polls the transaction until it
  completes, prints a summary, and opens the dashboard in your browser.
- `scripts/seed_demo_data.py` — runs 10 weighing cycles across all 3
  seeded grain types and both branches/scales (faster ~1.6s ramp per
  cycle, still passing through the same noise-decay and stabilization
  window logic, just compressed) so the dashboard has varied, realistic
  data to show instead of a single data point. Intended to be run once
  against a freshly started app.
- `src/main/resources/static/dashboard.html` — a single static HTML page,
  served automatically by Spring Boot at `http://localhost:8080/dashboard.html`
  (same-origin as the API, no CORS setup needed). Polls the five report
  endpoints every 3 seconds and displays them in labeled sections.

## How to run

1. Start the application in one terminal:

   ```
   ./mvnw spring-boot:run
   ```

2. In a second terminal, seed varied report data once (recommended, from
   the repo root):

   ```
   python scripts/seed_demo_data.py
   ```

3. Optionally, run the single-truck demo for one more live example (can be
   run multiple times against the same app instance):

   ```
   python scripts/demo.py
   ```

   (On some systems the Python 3 executable is `python3`:
   `python3 scripts/seed_demo_data.py` / `python3 scripts/demo.py`)

   Either script opens the dashboard automatically after finishing, so you
   only need to open it manually if you skip both or the browser doesn't
   launch.

## What to expect

Both scripts check that the app is reachable at `http://localhost:8080`
(via `GET /api/branches`) first, and exit with a clear message if it isn't
running yet.

`seed_demo_data.py` prints one concise line per cycle:

```
Checking for application at http://localhost:8080 ...
Application detected. Seeding 10 weighing cycles...

  [ 1/10] plate=SEED7836050    grain=Soja   branch=Sorriso       scale=BAL-001  status=COMPLETED
  [ 2/10] plate=SEED7864121    grain=Milho  branch=Sorriso       scale=BAL-001  status=COMPLETED
  ...
  [10/10] plate=SEED8072729    grain=Soja   branch=Rondonopolis  scale=BAL-002  status=COMPLETED

--- Seed Summary ---
Weighings completed: 10/10
  Milho: 3
  Soja: 4
  Sorgo: 3

Opening dashboard at http://localhost:8080/dashboard.html — if it doesn't
open automatically, open that URL manually.
```

`demo.py` narrates a single truck's flow in more detail:

```
Checking for application at http://localhost:8080 ...
Application detected. Starting demo flow...

Created truck: plate=SIM12345, id=7
Opened transaction: id=12
Sending weighing readings...
  sent 100 readings (final weight ~32001.34 kg)
Simulating truck departure (resetting scale for next run)...
Polling transaction for completion...

--- Demo Summary ---
Truck plate:   SIM12345
Status:        COMPLETED
Net weight:    23501.34 kg
Load cost:     R$ 4230.24

Opening dashboard at http://localhost:8080/dashboard.html — if it doesn't
open automatically, open that URL manually.
```

After either script finishes, it opens your default web browser to the
dashboard, which shows live-updating cards for cost by grain, scale
ranking, average weighing duration by branch, average margin by grain, and
scarcity alerts (highlighted in red/orange when present). With the seed
data in place, the scarcity-alerts card shows an active alert for Milho
out of the box — no manual DB edit needed.

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
