#!/usr/bin/env python3
"""Demo script: exercises the same HTTP endpoints as Story 6.2's ScaleSimulator
to simulate one or more truck weighings.

This is a presentation layer only -- it does not test a new code path, it
orchestrates the same flow already covered by ScaleSimulator and the
automated test suite.

Run with the application already started (./mvnw spring-boot:run):

    python demo.py
    python3 demo.py

Pass --repeat N to run N weighing cycles back to back in one invocation
(default: 1), e.g. to watch the dashboard's cards change a few times in a
row without needing an open-ended background loop:

    python demo.py --repeat 4

The dashboard opens automatically in your browser once per invocation
(after all cycles finish, not once per cycle) -- it already polls every
3s, so it picks up each cycle live as this script runs. Pass
--no-open-browser to suppress that (handy when re-running this
repeatedly during manual testing, to avoid piling up tabs); the URL is
still printed either way. Also run scripts/seed_demo_data.py once first
(against a freshly started app) to populate the dashboard with varied
report data across all grain types and branches -- this script alone
only produces single-truck cycles for one grain type/branch.
"""

import argparse
import sys
import time
import webbrowser

import demo_lib

SCALE_ID = "BAL-001"
API_KEY = "key-sorriso-001"
GRAIN_TYPE_ID = 1
BRANCH_ID = 1

TRUCK_TARE_KG = 8500.0
TARGET_WEIGHT_KG = 32000.0

TICK_INTERVAL_S = 0.1
TOTAL_DURATION_S = 10.0
INITIAL_NOISE_KG = 50.0
MIN_NOISE_KG = 2.0
DECAY_S = 5.0


def create_truck(cycle_num):
    plate = "SIM" + str(int(time.time() * 1000) % 100000) + str(cycle_num)
    truck_id = demo_lib.create_truck(plate, TRUCK_TARE_KG)
    return plate, truck_id


def run_cycle(cycle_num, total):
    plate, truck_id = create_truck(cycle_num)
    transaction_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)

    next_seq, reading_count, final_weight = demo_lib.send_weighing_readings(
        plate, SCALE_ID, API_KEY, TARGET_WEIGHT_KG,
        TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
    )
    demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)
    final_tx = demo_lib.await_completed_transaction(transaction_id)

    status = final_tx.get("status")
    load_cost = final_tx.get("loadCost")
    load_cost_str = f"R$ {load_cost:.2f}" if load_cost is not None else "n/a"
    print(
        f"[cycle {cycle_num}/{total}] plate={plate:<14} status={status:<10} "
        f"readings={reading_count:<4} final_weight~{final_weight:.2f}kg  load_cost={load_cost_str}"
    )
    return final_tx


def parse_args():
    parser = argparse.ArgumentParser(description="Simulate one or more truck weighing cycles.")
    parser.add_argument(
        "--repeat", type=int, default=1,
        help="Number of weighing cycles to run back to back in this invocation (default: 1).",
    )
    parser.add_argument(
        "--no-open-browser", dest="open_browser", action="store_false", default=True,
        help="Don't open the dashboard in a browser tab (it's opened by default, once per invocation).",
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

    dashboard_url = f"{demo_lib.BASE_URL}/dashboard.html"
    print(
        f"Dashboard: {dashboard_url}\n"
        "It auto-refreshes every 3s — leave the tab open to watch cycles land live."
    )
    if args.open_browser:
        webbrowser.open(dashboard_url)

    print(f"\nRunning {args.repeat} weighing cycle(s)...\n")

    completed_count = 0
    for cycle_num in range(1, args.repeat + 1):
        final_tx = run_cycle(cycle_num, args.repeat)
        if final_tx.get("status") == "COMPLETED":
            completed_count += 1

    print(f"\n--- Demo Summary ---\nCycles completed: {completed_count}/{args.repeat}")
    if completed_count < args.repeat:
        print("Note: some cycles did not reach COMPLETED within the polling window.")


if __name__ == "__main__":
    main()
