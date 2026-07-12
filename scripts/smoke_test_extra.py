#!/usr/bin/env python3
"""Extra smoke tests for the running application (manual-verification checks).

Automates a set of checks that were previously manual smoke tests: transaction
status transition edge cases, session-level reading idempotency, the anomaly
detection threshold boundary, non-positive net weight handling, an empty-range
report query, and actuator endpoint exposure.

Reuses demo_lib.py's HTTP helpers wherever they already cover what's needed
(create_truck, open_transaction, send_weighing_readings, etc.) instead of
reimplementing HTTP calls. Only adds the small amount of raw HTTP needed for
endpoints demo_lib doesn't already wrap (PATCH status, GET actuator/report
endpoints, and reading requests that need to bypass demo_lib's noise ramp).

This script creates real test data via the app's real endpoints (same as
demo.py / seed_demo_data.py) -- it does not touch the database directly and
does not modify any existing behavior.

Run with the application already started (./mvnw spring-boot:run):

    python scripts/smoke_test_extra.py
    python3 scripts/smoke_test_extra.py
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

import demo_lib

SCALE_ID = "BAL-001"
API_KEY = "key-sorriso-001"
GRAIN_TYPE_ID = 1
BRANCH_ID = 1

TICK_INTERVAL_S = 0.1
TOTAL_DURATION_S = 10.0
INITIAL_NOISE_KG = 50.0
MIN_NOISE_KG = 2.0
DECAY_S = 5.0

APPLICATION_YML_PATH = os.path.join(
    os.path.dirname(__file__), "..", "src", "main", "resources", "application.yml"
)

results = []


def record(name, expected, actual, passed):
    results.append({"name": name, "expected": expected, "actual": actual, "passed": passed})


def http_status(method, path, body=None, headers=None):
    """Like demo_lib.http_request, but returns (status_code, parsed_body) instead of
    raising on non-2xx, so checks can assert on error status codes without a try/except
    per call site.
    """
    url = demo_lib.BASE_URL + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            raw = resp.read()
            try:
                parsed = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                parsed = raw.decode("utf-8", errors="replace")
            return resp.status, parsed
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw.decode("utf-8", errors="replace")
        return e.code, parsed


def new_plate(prefix):
    return prefix + str(int(time.time() * 1000) % 1000000)


def read_max_payload_multiplier():
    with open(APPLICATION_YML_PATH, encoding="utf-8") as f:
        content = f.read()
    match = re.search(r"anomaly-detection:\s*\n\s*max-payload-multiplier:\s*([\d.]+)", content)
    if not match:
        print(f"Could not find anomaly-detection.max-payload-multiplier in {APPLICATION_YML_PATH}")
        sys.exit(1)
    return float(match.group(1))


# --- 1. Transaction status transition — valid and invalid ---

def check_direct_patch_to_completed():
    name = "PATCH status IN_TRANSIT -> COMPLETED (no real weighing)"
    try:
        plate = new_plate("PST")
        truck_id = demo_lib.create_truck(plate, 8000.0)
        tx_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)
        status, body = http_status("PATCH", f"/api/transactions/{tx_id}/status", {"status": "COMPLETED"})
        actual = f"HTTP {status}, body={body}"
        # No documented expectation either way -- this check reports the real, observed
        # behavior of the endpoint (it currently has no guard against skipping the real
        # weighing flow) rather than asserting a specific outcome.
        passed = status == 200 and body is not None and body.get("status") == "COMPLETED"
        expected = "reports actual behavior (no invariant currently enforced by the endpoint)"
        record(name, expected, actual, passed)
    except Exception as e:
        record(name, "n/a", f"exception: {e}", False)


def check_patch_nonexistent_transaction():
    name = "PATCH status on nonexistent transaction id"
    try:
        status, body = http_status("PATCH", "/api/transactions/999999999/status", {"status": "COMPLETED"})
        record(name, "HTTP 404", f"HTTP {status}, body={body}", status == 404)
    except Exception as e:
        record(name, "HTTP 404", f"exception: {e}", False)


def check_patch_invalid_status_value():
    name = "PATCH status with malformed status value"
    try:
        plate = new_plate("PIV")
        truck_id = demo_lib.create_truck(plate, 8000.0)
        tx_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)
        status, body = http_status("PATCH", f"/api/transactions/{tx_id}/status", {"status": "NOT_A_REAL_STATUS"})
        record(name, "HTTP 400", f"HTTP {status}, body={body}", status == 400)
    except Exception as e:
        record(name, "HTTP 400", f"exception: {e}", False)


# --- 2. True idempotency test (session-level) ---

def check_session_idempotency():
    name = "Resend final stabilized reading (same scaleId+seq) after COMPLETED"
    try:
        plate = new_plate("IDM")
        truck_id = demo_lib.create_truck(plate, 8500.0)
        tx_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)

        next_seq, _, final_weight = demo_lib.send_weighing_readings(
            plate, SCALE_ID, API_KEY, 32000.0,
            TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
        )
        final_tx = demo_lib.await_completed_transaction(tx_id)
        if final_tx.get("status") != "COMPLETED":
            record(name, "single WeighingRecord persisted, transaction unaffected by resend",
                   f"setup failed: transaction never reached COMPLETED (status={final_tx.get('status')})", False)
            return

        load_cost_before = final_tx.get("loadCost")
        gross_before = final_tx.get("grossWeightKg")
        last_seq_used = next_seq - 1

        # Re-send the exact final reading: same scale id + same seq that was already
        # processed. ReadingIdempotencyService should short-circuit this before it ever
        # reaches StabilizationService/persist(), so no second WeighingRecord should appear
        # and the transaction's persisted fields should be unchanged.
        status, _ = http_status(
            "POST", "/api/scales/readings",
            {"id": SCALE_ID, "plate": plate, "weight": final_weight, "seq": last_seq_used},
            headers={"X-Scale-Key": API_KEY},
        )

        after = demo_lib.http_request("GET", f"/api/transactions/{tx_id}")
        unchanged = (after.get("loadCost") == load_cost_before
                     and after.get("grossWeightKg") == gross_before
                     and after.get("status") == "COMPLETED")

        actual = (f"resend HTTP {status}; transaction fields "
                  f"{'unchanged' if unchanged else 'CHANGED'} "
                  f"(loadCost before={load_cost_before} after={after.get('loadCost')}, "
                  f"grossWeightKg before={gross_before} after={after.get('grossWeightKg')})")
        record(name, "transaction fields unchanged by resend (no duplicate persistence)", actual, unchanged)
        print(f"  NOTE: no list-weighing-records-by-transaction endpoint exists in the API. "
              f"To positively confirm only one WeighingRecord row exists for this transaction, "
              f"check via H2 console (http://localhost:8080/h2-console): "
              f"SELECT * FROM weighing_record WHERE transaction_id = {tx_id};  "
              f"(transaction id: {tx_id})")

        # Cleanup: send departure readings so the scale resets for later checks in this run.
        demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)
    except Exception as e:
        record(name, "transaction fields unchanged by resend", f"exception: {e}", False)


# --- 3. Anomaly detection boundary — exact threshold ---

def check_anomaly_threshold_boundary():
    name = "Stabilized gross weight AT anomaly threshold (should NOT warn)"
    try:
        multiplier = read_max_payload_multiplier()
        tare = 8500.0
        threshold_kg = tare * (1 + multiplier)

        plate = new_plate("THR")
        truck_id = demo_lib.create_truck(plate, tare)
        tx_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)

        # Target essentially the threshold itself, not comfortably below it -- narrow noise
        # band so the stabilized average lands very close to threshold_kg without regularly
        # exceeding it (the rule is strictly greater-than).
        next_seq, _, final_weight = demo_lib.send_weighing_readings(
            plate, SCALE_ID, API_KEY, threshold_kg,
            TICK_INTERVAL_S, TOTAL_DURATION_S, initial_noise_kg=1.0, min_noise_kg=0.5, decay_s=DECAY_S,
        )
        final_tx = demo_lib.await_completed_transaction(tx_id)
        demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)

        actual = (f"tare={tare}kg, threshold={threshold_kg:.2f}kg, sent final~{final_weight:.2f}kg, "
                  f"status={final_tx.get('status')}, grossWeightKg={final_tx.get('grossWeightKg')}")
        passed = final_tx.get("status") == "COMPLETED"
        record(name, "transaction COMPLETED; WARN should NOT fire (requires manual console check)", actual, passed)
        print("  NOTE: confirming the anomaly WARN did NOT fire requires checking the running "
              "application's console output -- this script has no access to the app's stdout/log "
              f"(same caveat as test_anomaly_detection.py). Look for 'Anomaly detected' with plate={plate}; "
              "it should be ABSENT for this run.")
    except Exception as e:
        record(name, "transaction COMPLETED, no WARN", f"exception: {e}", False)


# --- 4. Non-positive net weight (gross weight below tare) ---

def check_non_positive_net_weight():
    name = "Stabilized gross weight BELOW tare (negative net weight)"
    try:
        tare = 8500.0
        target_gross = tare - 500.0  # comfortably below tare -> netWeightKg < 0

        plate = new_plate("NEG")
        truck_id = demo_lib.create_truck(plate, tare)
        tx_id = demo_lib.open_transaction(truck_id, GRAIN_TYPE_ID, BRANCH_ID)

        next_seq, _, final_weight = demo_lib.send_weighing_readings(
            plate, SCALE_ID, API_KEY, target_gross,
            TICK_INTERVAL_S, TOTAL_DURATION_S, INITIAL_NOISE_KG, MIN_NOISE_KG, DECAY_S,
        )
        # Poll briefly for a status change; a negative-net-weight reading should never
        # complete, so this just waits out the same window await_completed_transaction
        # would, then reports whatever status is observed (expected: still IN_TRANSIT).
        tx = demo_lib.await_completed_transaction(tx_id)
        demo_lib.send_departure_readings(plate, SCALE_ID, API_KEY, next_seq, TICK_INTERVAL_S)

        actual = (f"tare={tare}kg, sent final~{final_weight:.2f}kg (target={target_gross}kg), "
                  f"observed status={tx.get('status')}, grossWeightKg={tx.get('grossWeightKg')}")
        passed = tx.get("status") == "IN_TRANSIT" and tx.get("grossWeightKg") is None
        record(name, "no WeighingRecord persisted; transaction remains IN_TRANSIT", actual, passed)
    except Exception as e:
        record(name, "transaction remains IN_TRANSIT, not completed", f"exception: {e}", False)


# --- 5. Reports with a date range that excludes everything ---

def check_empty_range_report():
    name = "GET /api/reports/cost-by-grain with future-only date range"
    try:
        from_date = "2999-01-01T00:00:00"
        to_date = "2999-12-31T23:59:59"
        status, body = http_status(
            "GET", f"/api/reports/cost-by-grain?from={from_date}&to={to_date}"
        )
        actual = f"HTTP {status}, body={body}"
        passed = status == 200 and isinstance(body, list) and len(body) == 0
        record(name, "HTTP 200 with an empty list", actual, passed)
    except Exception as e:
        record(name, "HTTP 200 with an empty list", f"exception: {e}", False)


# --- 6. Actuator endpoint exposure check ---

def check_actuator_exposure():
    exposed = ["health", "info", "metrics", "prometheus"]
    hidden = ["env", "beans"]

    for endpoint in exposed:
        name = f"GET /actuator/{endpoint} (should be exposed)"
        try:
            status, _ = http_status("GET", f"/actuator/{endpoint}")
            record(name, "HTTP 200", f"HTTP {status}", status == 200)
        except Exception as e:
            record(name, "HTTP 200", f"exception: {e}", False)

    for endpoint in hidden:
        name = f"GET /actuator/{endpoint} (should NOT be exposed)"
        try:
            status, _ = http_status("GET", f"/actuator/{endpoint}")
            record(name, "HTTP 404", f"HTTP {status}", status == 404)
        except Exception as e:
            record(name, "HTTP 404", f"exception: {e}", False)


def print_summary():
    print("\n--- Smoke Test Summary ---")
    name_w = max(len(r["name"]) for r in results) + 2
    expected_w = max(len(str(r["expected"])) for r in results) + 2
    header = f"{'CHECK':<{name_w}}{'EXPECTED':<{expected_w}}{'ACTUAL':<60}{'RESULT'}"
    print(header)
    print("-" * len(header))
    pass_count = 0
    for r in results:
        result_str = "PASS" if r["passed"] else "FAIL"
        if r["passed"]:
            pass_count += 1
        actual_str = str(r["actual"])
        if len(actual_str) > 58:
            actual_str = actual_str[:55] + "..."
        print(f"{r['name']:<{name_w}}{str(r['expected']):<{expected_w}}{actual_str:<60}{result_str}")
    print("-" * len(header))
    print(f"{pass_count}/{len(results)} checks passed")


def main():
    print(f"Checking for application at {demo_lib.BASE_URL} ...")
    if not demo_lib.check_app_running():
        print(
            f"Application not detected at {demo_lib.BASE_URL} — start it first with:\n"
            f"  ./mvnw spring-boot:run"
        )
        sys.exit(1)
    print("Application detected. Running smoke tests...\n")

    checks = [
        check_direct_patch_to_completed,
        check_patch_nonexistent_transaction,
        check_patch_invalid_status_value,
        check_session_idempotency,
        check_anomaly_threshold_boundary,
        check_non_positive_net_weight,
        check_empty_range_report,
        check_actuator_exposure,
    ]

    for check in checks:
        print(f"Running: {check.__name__} ...")
        try:
            check()
        except Exception as e:
            # Belt-and-suspenders: each check already has its own try/except, but this
            # ensures one check's unexpected failure never stops the remaining checks.
            record(check.__name__, "n/a", f"unhandled exception: {e}", False)

    print_summary()


if __name__ == "__main__":
    main()
