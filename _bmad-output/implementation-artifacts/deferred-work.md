# Deferred Work

## Deferred from: code review of story-3.2 (2026-07-10)

- Concurrent stabilized readings for the same plate could in theory double-process the same open transaction in `WeighingPersistenceService.persist()` (no locking on the `TransportTransaction` lookup). Accepted as risk for now: story 3.1's `ConcurrentHashMap.compute()` already serializes readings per `scaleId`, so triggering this would require two different scales processing the same plate concurrently — physically implausible (a truck can't be on two scales at once). No real call site exists yet (Epic 2 not built). Revisit once the ingestion endpoint (Epic 2) is wired.
