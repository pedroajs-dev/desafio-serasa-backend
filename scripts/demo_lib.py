"""Shared HTTP helpers for the demo scripts (demo.py, seed_demo_data.py).

Exercises the same endpoints as Story 6.2's ScaleSimulator -- no shortcuts,
no direct DB writes. Every weighing is produced by actually POSTing readings
through /api/scales/readings and letting StabilizationService decide when to
persist, exactly like the real ESP32 scale firmware would.
"""

import json
import random
import time
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"

DEPARTURE_WEIGHT_KG = 5.0
DEPARTURE_READING_COUNT = 3

FINAL_STATUS_MAX_ATTEMPTS = 10
FINAL_STATUS_POLL_INTERVAL_S = 0.3


def http_request(method, path, body=None, headers=None):
    url = BASE_URL + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    with urllib.request.urlopen(req, timeout=5) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def check_app_running():
    try:
        http_request("GET", "/api/branches")
        return True
    except (urllib.error.URLError, ConnectionError, TimeoutError):
        return False


def create_truck(plate, tare_kg):
    truck = http_request("POST", "/api/trucks", {"licensePlate": plate, "tare": tare_kg})
    return truck["id"]


def open_transaction(truck_id, grain_type_id, branch_id):
    tx = http_request("POST", "/api/transactions", {
        "truckId": truck_id,
        "grainTypeId": grain_type_id,
        "branchId": branch_id,
    })
    return tx["id"]


def send_weighing_readings(
    plate,
    scale_id,
    api_key,
    target_weight_kg,
    tick_interval_s,
    total_duration_s,
    initial_noise_kg,
    min_noise_kg,
    decay_s,
):
    """Stream readings with decaying noise, mirroring ScaleSimulator's ramp.

    Returns (next_seq, reading_count, final_weight_kg). next_seq lets
    callers chain departure readings (or a following cycle) without
    colliding with these seqs.
    """
    seq = int(time.time() * 1000)
    reading_count = 0
    elapsed = 0.0
    weight = target_weight_kg
    while elapsed < total_duration_s:
        noise = max(min_noise_kg, initial_noise_kg * max(0.0, 1 - elapsed / decay_s))
        weight = target_weight_kg + random.uniform(-noise, noise)
        try:
            http_request(
                "POST",
                "/api/scales/readings",
                {"id": scale_id, "plate": plate, "weight": weight, "seq": seq},
                headers={"X-Scale-Key": api_key},
            )
            reading_count += 1
        except urllib.error.URLError as e:
            print(f"  warning: reading POST failed ({e}), continuing")
        seq += 1
        elapsed += tick_interval_s
        time.sleep(tick_interval_s)
    return seq, reading_count, weight


def send_departure_readings(plate, scale_id, api_key, seq, tick_interval_s):
    """Simulate the truck driving off the scale.

    StabilizationService's ScaleState for a given scaleId lives in the
    running JVM across script invocations (and across cycles within the
    same script run). Once a weighing stabilizes, the scale stays "spent"
    until a reading below resetThresholdKg (50kg) arrives, so without this
    step, readings sent after the first completed weighing for a scale
    would never re-trigger stabilization.
    """
    for _ in range(DEPARTURE_READING_COUNT):
        try:
            http_request(
                "POST",
                "/api/scales/readings",
                {"id": scale_id, "plate": plate, "weight": DEPARTURE_WEIGHT_KG, "seq": seq},
                headers={"X-Scale-Key": api_key},
            )
        except urllib.error.URLError as e:
            print(f"  warning: departure reading POST failed ({e}), continuing")
        seq += 1
        time.sleep(tick_interval_s)
    return seq


def await_completed_transaction(transaction_id):
    tx = None
    for attempt in range(1, FINAL_STATUS_MAX_ATTEMPTS + 1):
        tx = http_request("GET", f"/api/transactions/{transaction_id}")
        if tx.get("status") == "COMPLETED":
            return tx
        time.sleep(FINAL_STATUS_POLL_INTERVAL_S)
    return tx
