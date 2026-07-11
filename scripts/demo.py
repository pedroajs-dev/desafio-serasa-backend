#!/usr/bin/env python3
"""Demo script: exercises the same HTTP endpoints as Story 6.2's ScaleSimulator
to simulate a truck weighing, then opens the live operational dashboard.

This is a presentation layer only -- it does not test a new code path, it
orchestrates the same flow already covered by ScaleSimulator and the
automated test suite.

Run with the application already started (./mvnw spring-boot:run):

    python demo.py
    python3 demo.py

Tip: run scripts/seed_demo_data.py once first (against a freshly started
app) to populate the dashboard with varied report data across all grain
types and branches -- this script alone only produces a single weighing.
"""

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


def create_truck():
    plate = "SIM" + str(int(time.time() * 1000) % 100000)
    truck_id = demo_lib.create_truck(plate, TRUCK_TARE_KG)
    return plate, truck_id


def main():
    print(f"Checking for application at {demo_lib.BASE_URL} ...")
    if not demo_lib.check_app_running():
        print(
            f"Application not detected at {demo_lib.BASE_URL} — start it first with:\n"
            f"  ./mvnw spring-boot:run"
        )
        sys.exit(1)

    print("Application detected. Starting demo flow...\n")

    plate, truck_id = create_truck()
    print(f"Created truck: plate={plate}, id={truck_id}")

    transaction_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)
    print(f"Opened transaction: id={transaction_id}")

    print("Sending weighing readings...")
    next_seq, reading_count, final_weight = demo_lib.send_weighing_readings(
        plate, SCALE_ID, API_KEY, TARGET_WEIGHT_KG,
        TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
    )
    print(f"  sent {reading_count} readings (final weight ~{final_weight:.2f} kg)")

    print("Simulating truck departure (resetting scale for next run)...")
    demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)

    print("Polling transaction for completion...")
    final_tx = demo_lib.await_completed_transaction(transaction_id)

    print("\n--- Demo Summary ---")
    print(f"Truck plate:   {plate}")
    print(f"Status:        {final_tx.get('status')}")
    net_weight = final_tx.get("netWeightKg")
    load_cost = final_tx.get("loadCost")
    print(f"Net weight:    {net_weight:.2f} kg" if net_weight is not None else "Net weight:    n/a")
    print(f"Load cost:     R$ {load_cost:.2f}" if load_cost is not None else "Load cost:     n/a")

    if final_tx.get("status") != "COMPLETED":
        print("\nNote: transaction did not reach COMPLETED within the polling window.")

    dashboard_url = f"{demo_lib.BASE_URL}/dashboard.html"
    print(f"\nOpening dashboard at {dashboard_url} — if it doesn't open automatically, open that URL manually.")
    webbrowser.open(dashboard_url)


if __name__ == "__main__":
    main()
