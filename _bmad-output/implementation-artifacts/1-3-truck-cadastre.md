---
baseline_commit: 521d81d49a95a13fb2b80ba7968521e4f1d87a79
---

# Story 1.3: Truck Cadastre

Status: done

## Story

As an admin,
I want to register trucks with their tare weight,
so that net weight can be calculated during weighing.

## Acceptance Criteria

1. **Given** a valid payload with `licensePlate` and `tare`, **when** `POST /api/trucks` is called, **then** truck is persisted with tare stored in kg.
2. **And** `GET /api/trucks/{id}` returns the truck with all fields.

## Tasks / Subtasks

- [x] Task 1: Create `Truck` entity + repository + REST controller (AC: #1, #2)
  - [x] `Truck` JPA entity: `id` (Long), `licensePlate` (String, not null, unique), `tare` (double, kg, not null, positive)
  - [x] `TruckRepository extends JpaRepository<Truck, Long>`
  - [x] `TruckController`: `POST /api/trucks` (201 + body), `GET /api/trucks/{id}` (200 + body, 404 if not found)
  - [x] Bean Validation on request DTO (`@NotBlank licensePlate`, `@Positive tare`)
- [x] Task 2: Add seed data (AC: #1)
  - [x] Append to `data.sql` (created in Story 1.2): at least 3 trucks with different tare values

## Dev Notes

- `tare` is a **truck** attribute, not a scale attribute — each vehicle has its own empty weight (per `02-solucao-proposta.md` section 1). This is critical: net weight in the future stabilization flow (Epic 3) is computed as `grossWeightKg - truck.tare`, looked up by the truck's license plate at the time of weighing.
- `licensePlate` should be unique (add `@Column(unique = true)`) since it's the lookup key used later when the stabilization engine (Epic 3) matches an incoming plate to the correct open `TransportTransaction` and truck tare.
- Follow the same entity/repository/controller convention established in Story 1.2 (Branch/GrainType).

### Project Structure Notes

- Same base package and layering convention as Stories 1.1/1.2. `data.sql` is a single shared file — append, don't overwrite, the branch/grain-type seed rows from Story 1.2.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.3: Truck Cadastre]
- [Source: _bmad-output/planning-artifacts/epics.md#FR3]
- [Source: 02-solucao-proposta.md#1. Modelo de domínio (cadastros)] — tare is per-truck, not per-scale

## Previous Story Intelligence

- Story 1.2 establishes `data.sql` at `src/main/resources/data.sql` with branch/grain-type seeds and the CRUD/controller conventions (entity/repository/controller naming, response shape, Bean Validation usage). Reuse the exact same pattern here — append new INSERTs to the same file, don't create a second seed file.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -q test` — full suite (Branch, GrainType, Truck), 13 tests, exit code 0 on first run — no new issues; the id-spoofing guard, defer-datasource-initialization, and isolated test-DB fixes from Story 1.2 already cover this story's controller.

### Completion Notes List

- Implemented `Truck` JPA entity, `TruckRepository`, and `TruckController` under `com.serasa.balancas.truck`, following the Story 1.2 convention (entity returned directly, id nulled before `save()` to prevent the client-id-spoofing issue found in Story 1.2's review).
- `licensePlate` has `@Column(unique = true)` per Dev Notes — Hibernate confirmed the unique constraint (`UKa04...`) was created on `truck.license_plate`.
- `GET /api/trucks/{id}` returns 404 via `ResponseEntity.notFound()` when the truck doesn't exist.
- Appended 3 truck seed rows to the shared `data.sql` (no explicit ids, consistent with Story 1.2's IDENTITY-collision fix).
- Tests: create success, blank-licensePlate 400, non-positive-tare 400, get-by-id success, get-by-id 404, id-spoofing-ignored — 6 tests for Truck, 13 total across the suite, all passing.

### File List

- `src/main/java/com/serasa/balancas/truck/Truck.java` (new)
- `src/main/java/com/serasa/balancas/truck/TruckRepository.java` (new)
- `src/main/java/com/serasa/balancas/truck/TruckController.java` (new)
- `src/main/resources/data.sql` (modified — appended truck seed rows)
- `src/test/java/com/serasa/balancas/truck/TruckControllerTest.java` (new)

## Change Log

- 2026-07-10: Story implemented — Truck cadastre with entity/repository/controller, seed data, and tests. All 13 tests passing (suite total). Status set to `review`.
- 2026-07-10: Code review passed with no findings. Approved and marked `done`.
