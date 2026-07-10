# Story 1.5: TransportTransaction Cadastre

Status: ready-for-dev

## Story

As an operator,
I want to open a transport transaction when a truck departs,
so that the system knows which grain type the truck is carrying before weighing.

## Acceptance Criteria

1. **Given** valid `truckId`, `grainTypeId`, `branchId`, **when** `POST /api/transactions` is called, **then** transaction is created with status `IN_TRANSIT` and `startDate` set.
2. **And** `GET /api/transactions/{id}` returns the full transaction.
3. **And** `PATCH /api/transactions/{id}/status` allows manual status update.

## Tasks / Subtasks

- [ ] Task 1: Create `TransportTransaction` entity + repository + REST controller (AC: #1, #2)
  - [ ] `TransportTransaction` JPA entity: `id` (Long), `truck` (`@ManyToOne`), `grainType` (`@ManyToOne`), `branch` (`@ManyToOne`), `status` (enum, not null), `startDate` (LocalDateTime, set on creation), `endDate` (LocalDateTime, nullable), `grossWeightKg` (nullable, populated later by Epic 3), `netWeightKg` (nullable, populated later by Epic 3), `loadCost` (nullable, populated later by Epic 3)
  - [ ] `TransportTransactionRepository extends JpaRepository<TransportTransaction, Long>` — add a finder method for Epic 3's later use: `findByTruck_LicensePlateAndStatusNot(String licensePlate, TransactionStatus completedStatus)`, since Epic 3 Story 3.2 needs to look up "the open transaction for this truck plate" and the open transaction can be in ANY non-terminal status (`IN_TRANSIT`, `AT_DOCK`, `WEIGHING`) — this story does not implement automatic transitions between those intermediate states, so a fixed-status lookup would be fragile. Call it as `findByTruck_LicensePlateAndStatusNot(plate, TransactionStatus.COMPLETED)`.
  - [ ] `TransportTransactionController`: `POST /api/transactions` (201 + body, validate truckId/grainTypeId/branchId exist), `GET /api/transactions/{id}` (200 + body, 404 if not found)
- [ ] Task 2: Implement status enum: `IN_TRANSIT`, `AT_DOCK`, `WEIGHING`, `COMPLETED` (AC: #1, #3)
  - [ ] Define `TransactionStatus` enum with these 4 values in that lifecycle order
- [ ] Task 3: Expose PATCH endpoint for manual status transition (AC: #3)
  - [ ] `PATCH /api/transactions/{id}/status` accepting `{status: "AT_DOCK"}` body, updates the transaction's status, returns 200 with updated body, 404 if transaction not found, 400 if status value is invalid

## Dev Notes

- **Critical ordering fact (per `02-solucao-proposta.md#1`):** the `TransportTransaction` exists BEFORE the weighing happens — the truck departs the branch with the grain type already defined, and the stabilized scale reading later CLOSES this transaction (sets status `COMPLETED`, `endDate`, `grossWeightKg`, `netWeightKg`, `loadCost`), it does not create a new transaction from scratch. This story only creates the "opening" side; Epic 3 Story 3.2 will implement the closing side.
- The repository lookup method by license plate + open status is a forward-looking requirement for Epic 3 (Story 3.2: "look up the open TransportTransaction for that truck" by plate) — add it now so Epic 3 doesn't need to modify this story's repository later. If multiple open transactions could theoretically exist for the same plate, assume for this delivery scope that only one is open at a time (business invariant, not enforced here).
- No automatic status progression logic in this story (e.g., nothing transitions `IN_TRANSIT` → `AT_DOCK` automatically) — that's manual via the PATCH endpoint, per AC #3. Automatic transition to `COMPLETED` happens later in Epic 3 when weighing stabilizes.
- Do not seed `data.sql` with transactions in this story unless useful for manual testing — the epics don't require it, keep scope tight (trucks/branches/grain types from prior stories are enough to POST a transaction manually).

### Project Structure Notes

- Same base package/layering convention as Stories 1.1–1.4.
- Requires `Truck` (Story 1.3), `GrainType` (Story 1.2), and `Branch` (Story 1.2) entities to already exist for the `@ManyToOne` relationships.
- This is the last story of Epic 1 — once done, epic-1 can be marked `done` and Epic 3 (Stabilization Engine) becomes unblocked per the sprint order (E1 → E3 → E2 → E4 → E5 → E6 → E7).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.5: TransportTransaction Cadastre]
- [Source: _bmad-output/planning-artifacts/epics.md#FR5]
- [Source: 02-solucao-proposta.md#1. Modelo de domínio (cadastros)] — transaction lifecycle and ordering relative to weighing
- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.2: Weighing Record Persistence] — downstream consumer of this entity/repository (do not implement in this story, only prepare the lookup method)

## Previous Story Intelligence

- Stories 1.2–1.4 established the CRUD conventions (entity/repository/controller/DTO validation) to follow here. `Truck`, `Branch`, and `GrainType` entities must exist (created in those stories) before this story's relationships compile.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
