#!/usr/bin/env python3
"""Seeds the running application with realistic report data by exercising
the real weighing flow -- no shortcuts, no direct DB writes. Every weighing
here is produced the same way ScaleSimulator and demo.py produce theirs:
POST readings to /api/scales/readings and let StabilizationService decide
when the weighing is stable enough to persist.

Beyond the two branches/grain types/scales already seeded in data.sql, this
script also creates one additional branch, grain type, and scale via the
real HTTP endpoints (POST /api/branches, /api/grain-types, /api/scales) --
same pattern already used for trucks -- so the dashboard has a 4th branch,
4th grain type, and 3rd scale to show, not just more of the same three.
Re-running the script reuses those three resources instead of duplicating
them (matched by name/id), so it's still safe to run more than once.

Run this against a freshly started application (./mvnw spring-boot:run) to
populate varied data across all grain types and branches before a reviewer
opens the dashboard. Running it again is safe (truck plates and reading
seqs are timestamp-derived), but repeated runs will just pile up more
weighings rather than replacing the seed set.

    python seed_demo_data.py
    python3 seed_demo_data.py

Pass --count N to control how many weighing cycles run (default: 20) and
--interval SECONDS to control the pause between them (default: 1.2s), so
the whole run takes roughly a minute -- long enough to comfortably watch
the dashboard's 3s auto-poll pick up several changes in a row rather than
finishing in ~15s:

    python seed_demo_data.py --count 30 --interval 1.5

Each cycle uses a faster reading ramp than demo.py's ~10s realistic ramp
(still passes through the same noise-decay -> stabilization window logic,
just compressed).

The dashboard opens automatically in your browser once, after all cycles
finish. Pass --no-open-browser to suppress that (handy when re-running
this repeatedly during manual testing); the URL is still printed either
way.
"""

import argparse
import sys
import time
import webbrowser

import demo_lib

# Cycle ramp: fast but still exercises noise decay + the stabilization
# window (window-size=15, consecutive-windows=3 in application.yml) rather
# than sending a flat constant weight from the first reading.
TICK_INTERVAL_S = 0.05
TOTAL_DURATION_S = 1.6
INITIAL_NOISE_KG = 40.0
MIN_NOISE_KG = 2.0
DECAY_S = 0.4

DEFAULT_COUNT = 20
DEFAULT_INTERVAL_S = 1.2

# grain/branch/scale pairing matches seed data: BAL-001 <-> branch 1
# (Filial Sorriso), BAL-002 <-> branch 2 (Filial Rondonopolis).
EXISTING_SCENARIOS = [
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "base_weight_kg": 32000.0, "tare_kg": 8500.0},
    {"grain_name": "Milho", "grain_type_id": 2, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "base_weight_kg": 28000.0, "tare_kg": 9000.0},
    {"grain_name": "Sorgo", "grain_type_id": 3, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "base_weight_kg": 24000.0, "tare_kg": 8200.0},
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "base_weight_kg": 30000.0, "tare_kg": 8700.0},
    {"grain_name": "Milho", "grain_type_id": 2, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "base_weight_kg": 26000.0, "tare_kg": 8900.0},
    {"grain_name": "Sorgo", "grain_type_id": 3, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "base_weight_kg": 22000.0, "tare_kg": 8400.0},
]

# New branch/grain type/scale, created via the real endpoints at startup
# (see ensure_new_resources()) rather than hardcoded ids from data.sql.
NEW_BRANCH_NAME = "Filial Primavera do Leste"
NEW_BRANCH_LOCATION = "Primavera do Leste - MT"
NEW_BRANCH_SHORT_NAME = "PrimaveraLeste"

NEW_GRAIN_NAME = "Algodao"
NEW_GRAIN_PURCHASE_PRICE_PER_TON = 250.00
NEW_GRAIN_MAX_REFERENCE_STOCK = 25000.0
# ratio = 15000/25000 = 0.60 -> margin = 0.20 - 0.15*0.60 = 0.11, comfortably
# under the reports.scarcity-threshold (0.18) from application.yml, so this
# grain does NOT show up in the scarcity-alerts card -- by design, to show
# a healthy 4th grain type rather than manufacturing a second alert.
NEW_GRAIN_CURRENT_STOCK = 15000.0

NEW_SCALE_ID = "BAL-003"
NEW_SCALE_API_KEY = "key-primavera-003"
NEW_SCENARIO_BASE_WEIGHT_KG = 18000.0
NEW_SCENARIO_TARE_KG = 8600.0


def ensure_branch():
    for branch in demo_lib.list_branches():
        if branch["name"] == NEW_BRANCH_NAME:
            return branch["id"], False
    return demo_lib.create_branch(NEW_BRANCH_NAME, NEW_BRANCH_LOCATION), True


def ensure_grain_type():
    for grain_type in demo_lib.list_grain_types():
        if grain_type["name"] == NEW_GRAIN_NAME:
            return grain_type["id"], False
    grain_id = demo_lib.create_grain_type(
        NEW_GRAIN_NAME, NEW_GRAIN_PURCHASE_PRICE_PER_TON,
        NEW_GRAIN_MAX_REFERENCE_STOCK, NEW_GRAIN_CURRENT_STOCK,
    )
    return grain_id, True


def ensure_scale(branch_id):
    for scale in demo_lib.list_scales():
        if scale["id"] == NEW_SCALE_ID:
            return scale["id"], False
    return demo_lib.create_scale(NEW_SCALE_ID, branch_id, NEW_SCALE_API_KEY), True


def ensure_new_resources():
    """Creates the extra branch/grain type/scale via the real HTTP endpoints
    (never touching the DB directly), reusing them on a second run instead
    of duplicating. Returns the scenario dict to mix into the weighing cycles.
    """
    branch_id, branch_created = ensure_branch()
    print(
        f"{'Created' if branch_created else 'Reusing existing'} branch: "
        f"name={NEW_BRANCH_NAME!r}, id={branch_id}"
    )

    grain_type_id, grain_created = ensure_grain_type()
    print(
        f"{'Created' if grain_created else 'Reusing existing'} grain type: "
        f"name={NEW_GRAIN_NAME!r}, id={grain_type_id}"
    )

    scale_id, scale_created = ensure_scale(branch_id)
    print(
        f"{'Created' if scale_created else 'Reusing existing'} scale: "
        f"id={scale_id}, branch_id={branch_id}\n"
    )

    return {
        "grain_name": NEW_GRAIN_NAME,
        "grain_type_id": grain_type_id,
        "branch_id": branch_id,
        "branch_name": NEW_BRANCH_SHORT_NAME,
        "scale_id": scale_id,
        "api_key": NEW_SCALE_API_KEY,
        "base_weight_kg": NEW_SCENARIO_BASE_WEIGHT_KG,
        "tare_kg": NEW_SCENARIO_TARE_KG,
    }


def build_cycles(count, scenarios):
    cycles = []
    for index in range(count):
        scenario = scenarios[index % len(scenarios)]
        # Small deterministic jitter so repeated passes over the same
        # scenario pool don't send the exact same weight every time.
        jitter_kg = ((index // len(scenarios)) * 733 % 2000) - 1000
        cycles.append({**scenario, "target_weight_kg": scenario["base_weight_kg"] + jitter_kg})
    return cycles


def run_cycle(index, total, cycle):
    plate = f"SEED{int(time.time() * 1000) % 1000000}{index}"

    truck_id = demo_lib.create_truck(plate, cycle["tare_kg"])
    transaction_id = demo_lib.open_transaction(truck_id, cycle["grain_type_id"], cycle["branch_id"])

    next_seq, _, _ = demo_lib.send_weighing_readings(
        plate, cycle["scale_id"], cycle["api_key"], cycle["target_weight_kg"],
        TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
    )
    demo_lib.send_departure_readings(plate, cycle["scale_id"], cycle["api_key"], next_seq, TICK_INTERVAL_S)

    final_tx = demo_lib.await_completed_transaction(transaction_id)
    status = final_tx.get("status")

    print(
        f"  [{index + 1:2}/{total}] plate={plate:<14} grain={cycle['grain_name']:<8} "
        f"branch={cycle['branch_name']:<15} scale={cycle['scale_id']}  status={status}"
    )
    return status


def parse_args():
    parser = argparse.ArgumentParser(description="Seed the running application with demo report data.")
    parser.add_argument(
        "--count", type=int, default=DEFAULT_COUNT,
        help=f"Number of weighing cycles to run (default: {DEFAULT_COUNT}).",
    )
    parser.add_argument(
        "--interval", type=float, default=DEFAULT_INTERVAL_S,
        help=f"Seconds to pause between cycles (default: {DEFAULT_INTERVAL_S}).",
    )
    parser.add_argument(
        "--no-open-browser", dest="open_browser", action="store_false", default=True,
        help="Don't open the dashboard in a browser tab (it's opened by default, once, after seeding finishes).",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    print(f"Checking for application at {demo_lib.BASE_URL} ...")
    if not demo_lib.check_app_running():
        print(
            f"Application not detected at {demo_lib.BASE_URL} — start it first with:\n"
            f"  ./mvnw spring-boot:run"
        )
        sys.exit(1)

    print("Application detected.\n")
    new_scenario = ensure_new_resources()
    scenarios = EXISTING_SCENARIOS + [new_scenario]
    cycles = build_cycles(args.count, scenarios)

    dashboard_url = f"{demo_lib.BASE_URL}/dashboard.html"
    print(
        f"Dashboard: {dashboard_url}\n"
        "It auto-refreshes every 3s — leave the tab open to watch the seeded data land live."
    )
    if args.open_browser:
        webbrowser.open(dashboard_url)

    print(f"\nSeeding {len(cycles)} weighing cycles ({args.interval}s apart)...\n")

    completed_by_grain = {}
    completed_count = 0
    for index, cycle in enumerate(cycles):
        status = run_cycle(index, len(cycles), cycle)
        if status == "COMPLETED":
            completed_count += 1
            completed_by_grain[cycle["grain_name"]] = completed_by_grain.get(cycle["grain_name"], 0) + 1
        if index < len(cycles) - 1:
            time.sleep(args.interval)

    print("\n--- Seed Summary ---")
    print(f"Weighings completed: {completed_count}/{len(cycles)}")
    for grain_name, count in sorted(completed_by_grain.items()):
        print(f"  {grain_name}: {count}")

    if completed_count < len(cycles):
        print("\nNote: some cycles did not reach COMPLETED within the polling window.")


if __name__ == "__main__":
    main()
