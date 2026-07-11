# Deferred Work

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
