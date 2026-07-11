#!/usr/bin/env python3
"""Seeds the running application with realistic report data by exercising
the real weighing flow -- no shortcuts, no direct DB writes. Every weighing
here is produced the same way ScaleSimulator and demo.py produce theirs:
POST readings to /api/scales/readings and let StabilizationService decide
when the weighing is stable enough to persist.

Run this ONCE against a freshly started application (./mvnw spring-boot:run)
to populate varied data across all grain types and both branches before a
reviewer opens the dashboard. Running it again is safe (truck plates and
reading seqs are timestamp-derived), but repeated runs will just pile up
more weighings rather than replacing the seed set.

    python seed_demo_data.py
    python3 seed_demo_data.py

Each cycle uses a faster reading ramp than demo.py's ~10s realistic ramp
(still passes through the same noise-decay -> stabilization window logic,
just compressed) so that 10 cycles complete in well under a minute.
"""

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

# grain/branch/scale pairing matches seed data: BAL-001 <-> branch 1
# (Filial Sorriso), BAL-002 <-> branch 2 (Filial Rondonopolis).
CYCLES = [
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "target_weight_kg": 32000.0, "tare_kg": 8500.0},
    {"grain_name": "Milho", "grain_type_id": 2, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "target_weight_kg": 28000.0, "tare_kg": 9000.0},
    {"grain_name": "Sorgo", "grain_type_id": 3, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "target_weight_kg": 24000.0, "tare_kg": 8200.0},
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "target_weight_kg": 30000.0, "tare_kg": 8700.0},
    {"grain_name": "Milho", "grain_type_id": 2, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "target_weight_kg": 26000.0, "tare_kg": 8900.0},
    {"grain_name": "Sorgo", "grain_type_id": 3, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "target_weight_kg": 22000.0, "tare_kg": 8400.0},
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "target_weight_kg": 35000.0, "tare_kg": 9100.0},
    {"grain_name": "Milho", "grain_type_id": 2, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "target_weight_kg": 27500.0, "tare_kg": 8600.0},
    {"grain_name": "Sorgo", "grain_type_id": 3, "branch_id": 1, "branch_name": "Sorriso",       "scale_id": "BAL-001", "api_key": "key-sorriso-001",       "target_weight_kg": 19000.0, "tare_kg": 8300.0},
    {"grain_name": "Soja",  "grain_type_id": 1, "branch_id": 2, "branch_name": "Rondonopolis",  "scale_id": "BAL-002", "api_key": "key-rondonopolis-002",  "target_weight_kg": 31000.0, "tare_kg": 8800.0},
]


def run_cycle(index, cycle):
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
        f"  [{index + 1:2}/{len(CYCLES)}] plate={plate:<14} grain={cycle['grain_name']:<6} "
        f"branch={cycle['branch_name']:<13} scale={cycle['scale_id']}  status={status}"
    )
    return status


def main():
    print(f"Checking for application at {demo_lib.BASE_URL} ...")
    if not demo_lib.check_app_running():
        print(
            f"Application not detected at {demo_lib.BASE_URL} — start it first with:\n"
            f"  ./mvnw spring-boot:run"
        )
        sys.exit(1)

    print(f"Application detected. Seeding {len(CYCLES)} weighing cycles...\n")

    completed_by_grain = {}
    completed_count = 0
    for index, cycle in enumerate(CYCLES):
        status = run_cycle(index, cycle)
        if status == "COMPLETED":
            completed_count += 1
            completed_by_grain[cycle["grain_name"]] = completed_by_grain.get(cycle["grain_name"], 0) + 1

    print("\n--- Seed Summary ---")
    print(f"Weighings completed: {completed_count}/{len(CYCLES)}")
    for grain_name, count in sorted(completed_by_grain.items()):
        print(f"  {grain_name}: {count}")

    if completed_count < len(CYCLES):
        print("\nNote: some cycles did not reach COMPLETED within the polling window.")

    dashboard_url = f"{demo_lib.BASE_URL}/dashboard.html"
    print(f"\nOpening dashboard at {dashboard_url} — if it doesn't open automatically, open that URL manually.")
    webbrowser.open(dashboard_url)


if __name__ == "__main__":
    main()
