# Story 1.3: Truck Cadastre

Status: ready-for-dev

## Story

As an admin,
I want to register trucks with their tare weight,
so that net weight can be calculated during weighing.

## Acceptance Criteria

1. **Given** a valid payload with `licensePlate` and `tare`, **when** `POST /api/trucks` is called, **then** truck is persisted with tare stored in kg.
2. **And** `GET /api/trucks/{id}` returns the truck with all fields.

## Tasks / Subtasks

- [ ] Task 1: Create `Truck` entity + repository + REST controller (AC: #1, #2)
  - [ ] `Truck` JPA entity: `id` (Long), `licensePlate` (String, not null, unique), `tare` (double, kg, not null, positive)
  - [ ] `TruckRepository extends JpaRepository<Truck, Long>`
  - [ ] `TruckController`: `POST /api/trucks` (201 + body), `GET /api/trucks/{id}` (200 + body, 404 if not found)
  - [ ] Bean Validation on request DTO (`@NotBlank licensePlate`, `@Positive tare`)
- [ ] Task 2: Add seed data (AC: #1)
  - [ ] Append to `data.sql` (created in Story 1.2): at least 3 trucks with different tare values

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

### Debug Log References

### Completion Notes List

### File List
