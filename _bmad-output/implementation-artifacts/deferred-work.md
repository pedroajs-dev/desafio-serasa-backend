# Deferred Work

## Deferred from: quick-dev review of spec-duplicate-cadastro-validation (2026-07-12)

- source_spec: `_bmad-output/implementation-artifacts/spec-duplicate-cadastro-validation.md`
  summary: Natural-key uniqueness (truck plate, branch/grain-type name) is case- and whitespace-sensitive — "Soja" vs "soja" vs " Soja " are treated as distinct.
  evidence: `existsBy...` derives an exact-match `=` query and H2 is case-sensitive with no trimming; real-world duplicates (lowercase plate resend, copy-paste trailing space) bypass the guard and the DB unique index. Deferred because case-folding/trim is a product-semantics decision with multiple valid readings, not clearly implied by the approved intent.
- source_spec: `_bmad-output/implementation-artifacts/spec-duplicate-cadastro-validation.md`
  summary: Truck/Branch/GrainType unique constraints rely on `ddl-auto: update` rather than a managed migration.
  evidence: Hibernate `update` reliably creates the constraint on a fresh in-memory H2 table but is known not to reliably add unique constraints to a pre-existing/managed schema; if this ever runs against persistent Postgres the DB backstop could silently be absent. Pre-existing whole-project concern (aligns with the H2-not-Postgres trade-off already documented in the README); belongs to a future migrations effort, not this fix.
- source_spec: `_bmad-output/implementation-artifacts/spec-duplicate-cadastro-validation.md`
  summary: No automated concurrency regression test for the duplicate-create race.
  evidence: The new tests cover only the sequential POST-then-POST path. The TOCTOU race is now backstopped by DB unique constraints + a `DataIntegrityViolationException`->409 handler (added in this change), but a dedicated multi-threaded test would be timing-dependent/flaky against the shared H2 test DB. The constraint is the real guarantee; an explicit race test is deferred.

## Deferred from: code review of story-3.2 (2026-07-10)

- Concurrent stabilized readings for the same plate could in theory double-process the same open transaction in `WeighingPersistenceService.persist()` (no locking on the `TransportTransaction` lookup). Accepted as risk for now: story 3.1's `ConcurrentHashMap.compute()` already serializes readings per `scaleId`, so triggering this would require two different scales processing the same plate concurrently — physically implausible (a truck can't be on two scales at once). No real call site exists yet (Epic 2 not built). Revisit once the ingestion endpoint (Epic 2) is wired.

## Deferred from: code review of story-2.1 (2026-07-10)

- No rate limiting / duplicate-request protection on `POST /api/scales/readings` — a misbehaving or malicious ESP32 can flood `StabilizationService` with requests. Out of scope for this story's AC.
- `ScaleState` entries in `StabilizationService`'s `ConcurrentHashMap` are never evicted — a scale that stops sending readings leaves its state resident indefinitely. Bounded by the number of known scales today since auth now gates entry to the map, but worth revisiting for cleanup/TTL if that assumption changes.
- `apiKey` is stored and compared in plaintext (`ScaleRepository.findByIdAndApiKey`) — no hashing, no constant-time comparison. Accepted trade-off per Story 1.4; revisit if this becomes internet-facing rather than LAN/VPN-only.
- `WeighingPersistenceService.persist()`'s documented "never throws" contract (log-and-skip internally, per Story 3.2) means the `StabilizationService.markPersistenceFailed()` retry path added in Story 2.1 is currently unreachable in practice. Only becomes meaningful if persistence failures start propagating as exceptions.
- No structured audit logging of authentication failures (401s) on the ingestion endpoint — could aid detecting compromised/spoofed scale keys, but no requirement calls for it yet.

## Deferred from: code review of story-2.2 (2026-07-10)

- Unbounded in-memory `seenKeys` set in `ReadingIdempotencyService` — no TTL, eviction, or size cap; grows one entry per distinct `(scaleId, seq)` for the process lifetime. Accepted architectural risk, same profile as `StabilizationService`'s `ScaleState` map (already logged above). Revisit with a bounded/TTL structure or shared store if ingestion volume or retention grows.
- No distributed/persistent idempotency state — the guard is per-JVM and non-durable. Behind more than one replica, duplicates routed to different instances both pass; a restart re-accepts previously-seen readings. Accepted single-instance/in-memory limitation for this delivery (LAN, single node); would need Redis or similar to guarantee cross-instance/cross-restart idempotency.
- No validation on the `seq` field (negative/zero/fixed values accepted) — a buggy or malicious device sending a constant `seq` permanently poisons that scale, causing every subsequent reading to be silently discarded as a duplicate. Out of Story 2.2 scope; consider `@Positive` and/or monotonicity checks if device trust becomes a concern.

## Deferred from: code review of story-6.2 (2026-07-10)

- Weight noise in `ScaleSimulator` has no lower clamp relative to `TRUCK_TARE_KG`, so a future change to `TARGET_WEIGHT_KG`/`INITIAL_NOISE_KG` could generate a physically implausible negative net weight; not reachable with current constants (32000±50 vs. tare 8500).
- No defensive null-checks on JSON response fields in `ScaleSimulator` (`truck.get("id")`, `transaction.get("status")`, etc.) before `.asLong()`/`.asText()` — would NPE if the backend response shape ever changed. Backend contract is fixed and covered by existing controller tests; theoretical only for this throwaway demo script.
- Plate collision risk in `ScaleSimulator` across runs within the same ~100s window (`"SIM" + currentTimeMillis() % 100_000`), since `Truck.licensePlate` is unique. Fails loudly (visible `IllegalStateException` from `createTruck`) rather than silently, so lower priority than the seq-collision/no-poll issues fixed in this story.
- Single failed HTTP tick aborts `ScaleSimulator`'s entire run — no try/catch around `sendReading`'s `client.send(...)` in the reading loop. Low likelihood against localhost; failure is loud (uncaught exception) rather than silent.
