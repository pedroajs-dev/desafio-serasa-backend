# Story 1.2: Branch & GrainType Cadastres

Status: ready-for-dev

## Story

As an admin,
I want to register branches and grain types,
so that trucks and transactions can reference valid locations and commodities.

## Acceptance Criteria

1. **Given** valid payloads, **when** `POST /api/branches` and `POST /api/grain-types` are called, **then** entities are persisted and returned with generated id.
2. **And** `GET /api/branches` and `GET /api/grain-types` return all records.
3. **And** `GrainType` includes `purchasePricePerTon`, `maxReferenceStock`, `currentStock`.

## Tasks / Subtasks

- [ ] Task 1: Create `Branch` entity + repository + REST controller (AC: #1, #2)
  - [ ] `Branch` JPA entity: `id` (Long, auto-generated), `name` (String, not null), `location` (String)
  - [ ] `BranchRepository extends JpaRepository<Branch, Long>`
  - [ ] `BranchController`: `POST /api/branches` (201 + body), `GET /api/branches` (200 + list)
  - [ ] Bean Validation on request DTO (`@NotBlank name`)
- [ ] Task 2: Create `GrainType` entity + repository + REST controller (AC: #1, #2, #3)
  - [ ] `GrainType` JPA entity: `id` (Long), `name` (String, not null), `purchasePricePerTon` (BigDecimal or double, not null, positive), `maxReferenceStock` (double, not null), `currentStock` (double, defaults to 0 or provided)
  - [ ] `GrainTypeRepository extends JpaRepository<GrainType, Long>`
  - [ ] `GrainTypeController`: `POST /api/grain-types` (201 + body), `GET /api/grain-types` (200 + list)
- [ ] Task 3: Add seed data in `data.sql` (AC: #2)
  - [ ] Create `src/main/resources/data.sql` (auto-executed by Spring Boot against H2 on `ddl-auto: update`)
  - [ ] Seed at least 2 branches and 3 grain types with realistic values (varying `purchasePricePerTon`, `maxReferenceStock`, `currentStock` so Epic 4 margin calculations have meaningful test data across the 5%–20% range)

## Dev Notes

- Use `BigDecimal` for monetary fields (`purchasePricePerTon`) to avoid floating-point rounding issues in later cost/margin calculations (Epic 4 depends on this value). Plain `double` is acceptable for stock quantities.
- Return DTOs, not raw entities, from controllers is the general house style for this project (see Epic 5 report note) — for this simple CRUD story, returning the entity directly is acceptable since there's no sensitive/internal field to hide, but keep request and response separate from the entity where request validation differs (e.g., don't accept `id` or `currentStock` on create if you want stricter creation semantics — the epics don't mandate this, so returning entity directly for both request/response is fine here to keep scope tight).
- `data.sql` runs automatically on startup against the H2 in-memory DB when `spring.jpa.hibernate.ddl-auto` includes schema creation (`update` will create tables on first run; `data.sql` populates rows afterward — verify insert IDs don't conflict with `GenerationType.IDENTITY` sequencing, or use explicit IDs consistently since this is a fresh in-memory DB each run).
- No authentication/authorization required for these cadastre endpoints (only the scale ingestion endpoint in Epic 2 requires the `X-Scale-Key` header).

### Project Structure Notes

- Follow the package structure established in Story 1.1 (e.g., `com.serasa.balancas.branch`, `com.serasa.balancas.graintype`, or a flat `entity`/`repository`/`controller` split — whichever Story 1.1 established as the base package; keep consistent for all subsequent cadastre stories).
- This story is a peer to 1.3, 1.4, 1.5 — same CRUD pattern will repeat, so establish the convention cleanly here (entity/repository/controller naming, response shape) since later stories will follow it.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.2: Branch & GrainType Cadastres]
- [Source: _bmad-output/planning-artifacts/epics.md#FR1, FR2]

## Previous Story Intelligence

- Story 1.1 (Project Bootstrap) establishes the base Spring Boot project, `application.yml` with H2 config, and the base package. Read that story's Dev Agent Record (File List / Completion Notes) once implemented, to reuse the exact base package name and confirm `ddl-auto: update` is active before relying on `data.sql`.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
