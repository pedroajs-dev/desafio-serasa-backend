---
baseline_commit: c107d8682a78a32329d5c28f80f15ffd0fe93d04
---

# Story 1.2: Branch & GrainType Cadastres

Status: done

## Story

As an admin,
I want to register branches and grain types,
so that trucks and transactions can reference valid locations and commodities.

## Acceptance Criteria

1. **Given** valid payloads, **when** `POST /api/branches` and `POST /api/grain-types` are called, **then** entities are persisted and returned with generated id.
2. **And** `GET /api/branches` and `GET /api/grain-types` return all records.
3. **And** `GrainType` includes `purchasePricePerTon`, `maxReferenceStock`, `currentStock`.

## Tasks / Subtasks

- [x] Task 1: Create `Branch` entity + repository + REST controller (AC: #1, #2)
  - [x] `Branch` JPA entity: `id` (Long, auto-generated), `name` (String, not null), `location` (String)
  - [x] `BranchRepository extends JpaRepository<Branch, Long>`
  - [x] `BranchController`: `POST /api/branches` (201 + body), `GET /api/branches` (200 + list)
  - [x] Bean Validation on request DTO (`@NotBlank name`)
- [x] Task 2: Create `GrainType` entity + repository + REST controller (AC: #1, #2, #3)
  - [x] `GrainType` JPA entity: `id` (Long), `name` (String, not null), `purchasePricePerTon` (BigDecimal or double, not null, positive), `maxReferenceStock` (double, not null), `currentStock` (double, defaults to 0 or provided)
  - [x] `GrainTypeRepository extends JpaRepository<GrainType, Long>`
  - [x] `GrainTypeController`: `POST /api/grain-types` (201 + body), `GET /api/grain-types` (200 + list)
- [x] Task 3: Add seed data in `data.sql` (AC: #2)
  - [x] Create `src/main/resources/data.sql` (auto-executed by Spring Boot against H2 on `ddl-auto: update`)
  - [x] Seed at least 2 branches and 3 grain types with realistic values (varying `purchasePricePerTon`, `maxReferenceStock`, `currentStock` so Epic 4 margin calculations have meaningful test data across the 5%–20% range)

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

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -q test` initially failed with 7 errors: `data.sql` ran before Hibernate created the schema ("Table BRANCH not found") — fixed by adding `spring.jpa.defer-datasource-initialization: true`.
- After that fix, tests failed again with primary-key violations: all `@SpringBootTest` contexts shared the same named in-memory H2 DB (`jdbc:h2:mem:balancas;DB_CLOSE_DELAY=-1`), so `data.sql` executed once per context against the same persisted data — fixed by adding `src/test/resources/application.yml` overriding the datasource URL to `jdbc:h2:mem:balancas-test-${random.uuid}` (full config duplicated since Spring does not merge classpath `application.yml` files, it replaces).
- A further PK violation surfaced on `POST` in tests: `data.sql` used explicit `id` values (1, 2, 3...) which the `IDENTITY` generator wasn't aware of, so the first generated id collided with a seeded row — fixed by dropping explicit ids from `data.sql` and letting `IDENTITY` assign them.
- `./mvnw -q test` — final run: 7/7 tests passed, exit code 0.

### Completion Notes List

- Implemented `Branch` and `GrainType` JPA entities, Spring Data repositories, and REST controllers under `com.serasa.balancas.branch` / `com.serasa.balancas.graintype`, following the flat entity/repository/controller package style. Controllers return the entity directly (no separate DTO) per Dev Notes guidance for this story's scope.
- `GrainType.purchasePricePerTon` uses `BigDecimal` per Dev Notes (Epic 4 margin calc precision); `maxReferenceStock`/`currentStock` use `Double`.
- Seed data added in `src/main/resources/data.sql`: 2 branches, 3 grain types (Soja/Milho/Sorgo) with varied price/stock ratios spanning the 5%-20% scarcity range for later Epic 4/5 use.
- `spring.jpa.defer-datasource-initialization: true` added to `application.yml` so `data.sql` runs after Hibernate's `ddl-auto: update` creates the schema.
- Added `src/test/resources/application.yml` giving each `@SpringBootTest` context an isolated, randomly-named in-memory H2 DB, avoiding cross-context data collisions with the shared named `balancas` DB used at runtime.
- Tests: `BranchControllerTest` and `GrainTypeControllerTest` (create success, validation-failure 400, list-includes-seed-data) — 7 tests total, all passing.
- Code review finding fixed: both controllers accepted the raw entity on `POST`, so a client-supplied `id` caused `save()` to merge/overwrite an existing row instead of inserting. Fixed by nulling the `id` before `save()` in both `BranchController.create` and `GrainTypeController.create`; added a regression test per controller (`ignoresClientSuppliedIdOnCreate`). 9 tests total, all passing.

### File List

- `src/main/java/com/serasa/balancas/branch/Branch.java` (new)
- `src/main/java/com/serasa/balancas/branch/BranchRepository.java` (new)
- `src/main/java/com/serasa/balancas/branch/BranchController.java` (new)
- `src/main/java/com/serasa/balancas/graintype/GrainType.java` (new)
- `src/main/java/com/serasa/balancas/graintype/GrainTypeRepository.java` (new)
- `src/main/java/com/serasa/balancas/graintype/GrainTypeController.java` (new)
- `src/main/resources/data.sql` (new)
- `src/main/resources/application.yml` (modified — added `defer-datasource-initialization: true`)
- `src/test/resources/application.yml` (new)
- `src/test/java/com/serasa/balancas/branch/BranchControllerTest.java` (new)
- `src/test/java/com/serasa/balancas/graintype/GrainTypeControllerTest.java` (new)

## Change Log

- 2026-07-10: Story implemented — Branch and GrainType cadastres with entity/repository/controller, seed data, and tests. All 7 tests passing. Status set to `review`.
- 2026-07-10: Code review found id-spoofing risk in both POST endpoints (client-supplied `id` causes overwrite instead of insert). Fixed and covered by regression tests. 9 tests passing. Approved and marked `done`.
