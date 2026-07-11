#!/usr/bin/env python3
"""Manual-verification script for the weight anomaly detection feature added to
WeighingPersistenceService.

Deliberately sends a stabilized weighing far beyond the plausible capacity for
a known-tare truck, so a human can confirm the "Anomaly detected" WARN log
fires correctly end-to-end against the running application.

This script does NOT assert on the log itself -- it has no access to the
running app's stdout/log file from here. It is a trigger + manual-check
helper, not an automated test. The automated assertion already lives in
WeighingPersistenceServiceTest (see doesNotLogAnomalyWhenGrossWeightIsWithin
PlausibleCapacity / logsAnomalyButStillPersistsAndCompletesTransactionWhen
GrossWeightFarExceedsCapacity).

Run with the application already started (./mvnw spring-boot:run):

    python scripts/test_anomaly_detection.py
    python3 scripts/test_anomaly_detection.py
"""

import os
import re
import sys
import time

import demo_lib

SCALE_ID = "BAL-001"
API_KEY = "key-sorriso-001"
GRAIN_TYPE_ID = 1
BRANCH_ID = 1

TRUCK_TARE_KG = 8500.0

TICK_INTERVAL_S = 0.1
TOTAL_DURATION_S = 10.0
INITIAL_NOISE_KG = 50.0
MIN_NOISE_KG = 2.0
DECAY_S = 5.0

APPLICATION_YML_PATH = os.path.join(
    os.path.dirname(__file__), "..", "src", "main", "resources", "application.yml"
)


def read_max_payload_multiplier():
    """Reads anomaly-detection.max-payload-multiplier straight out of
    application.yml, mirroring the threshold formula in
    WeighingPersistenceService.persist(): tare * (1 + multiplier).

    Avoids hardcoding a guess at the configured value -- if application.yml
    changes, this script's target weight tracks it automatically.
    """
    with open(APPLICATION_YML_PATH, encoding="utf-8") as f:
        content = f.read()
    match = re.search(r"anomaly-detection:\s*\n\s*max-payload-multiplier:\s*([\d.]+)", content)
    if not match:
        print(f"Could not find anomaly-detection.max-payload-multiplier in {APPLICATION_YML_PATH}")
        sys.exit(1)
    return float(match.group(1))


def create_truck():
    plate = "ANOM" + str(int(time.time() * 1000) % 100000)
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

    print("Application detected. Starting anomaly-detection trigger flow...\n")

    multiplier = read_max_payload_multiplier()
    threshold_kg = TRUCK_TARE_KG * (1 + multiplier)
    # Comfortably clear of the threshold (not just barely over it) so the
    # trigger is unambiguous even accounting for reading noise.
    target_weight_kg = TRUCK_TARE_KG + TRUCK_TARE_KG * (multiplier + 1)
    print(f"Read anomaly-detection.max-payload-multiplier={multiplier} from application.yml")
    print(f"Truck tare={TRUCK_TARE_KG}kg -> anomaly threshold={threshold_kg}kg")
    print(f"Targeting stabilized gross weight={target_weight_kg}kg (well above threshold)\n")

    plate, truck_id = create_truck()
    print(f"Created truck: plate={plate}, id={truck_id}, tare={TRUCK_TARE_KG}kg")

    transaction_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)
    print(f"Opened transaction: id={transaction_id}")

    print("Sending weighing readings targeting an anomalous gross weight...")
    next_seq, reading_count, final_weight = demo_lib.send_weighing_readings(
        plate, SCALE_ID, API_KEY, target_weight_kg,
        TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
    )
    print(f"  sent {reading_count} readings (final weight ~{final_weight:.2f} kg)")

    print("Simulating truck departure (resetting scale for next run)...")
    demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)

    print("Polling transaction for completion...")
    final_tx = demo_lib.await_completed_transaction(transaction_id)

    print("\n--- Anomaly Detection Trigger Summary ---")
    print(f"Truck plate:         {plate}")
    print(f"Tare:                {TRUCK_TARE_KG} kg")
    print(f"Target gross weight: {target_weight_kg} kg (sent ~{final_weight:.2f} kg)")
    print(f"Computed threshold:  {threshold_kg} kg (tare * (1 + {multiplier}))")
    print(f"Transaction status:  {final_tx.get('status')}")

    if final_tx.get("status") != "COMPLETED":
        print("\nNote: transaction did not reach COMPLETED within the polling window.")

    print(
        f"\n>>> Check the application console output for a line containing "
        f"'Anomaly detected' with plate={plate} — this confirms the WARN fired as expected.\n"
    )
    print(
        "Note: this script only triggers the condition and reports what it sent — it does not\n"
        "read the running application's stdout/log, so it cannot assert the WARN was logged.\n"
        "The automated assertion for this behavior lives in WeighingPersistenceServiceTest."
    )


if __name__ == "__main__":
    main()
