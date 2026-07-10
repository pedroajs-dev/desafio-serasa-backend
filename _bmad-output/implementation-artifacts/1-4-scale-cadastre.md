# Story 1.4: Scale Cadastre

Status: ready-for-dev

## Story

As an admin,
I want to register scales with their branch and API key,
so that incoming readings can be authenticated and associated to a branch.

## Acceptance Criteria

1. **Given** a valid payload with `branchId` and `apiKey`, **when** `POST /api/scales` is called, **then** scale is persisted linked to the branch.
2. **And** `GET /api/scales` returns all scales with their `branchId`.
3. **And** `apiKey` is stored (plaintext for this delivery — note trade-off in README).

## Tasks / Subtasks

- [ ] Task 1: Create `Scale` entity + repository + REST controller (AC: #1, #2, #3)
  - [ ] `Scale` JPA entity: `id` (String, business identifier — see Dev Notes), `branch` (`@ManyToOne` to `Branch`, not null), `apiKey` (String, not null)
  - [ ] `ScaleRepository extends JpaRepository<Scale, String>`
  - [ ] `ScaleController`: `POST /api/scales` (201 + body, validate `branchId` exists → 400/404 if not), `GET /api/scales` (200 + list including `branchId`)
  - [ ] Bean Validation on request DTO (`@NotNull branchId`, `@NotBlank apiKey`)
- [ ] Task 2: Add seed data (AC: #1, #2)
  - [ ] Append to `data.sql`: at least 2 scales linked to seeded branches (from Story 1.2), each with a distinct `apiKey` value — **note these apiKey values explicitly in this story's Completion Notes**, since Epic 2 (ingestion endpoint) and Epic 6 (ESP32 simulator) will need real seeded values to authenticate against.

## Dev Notes

- **`Scale.id` is fixed as `String` (decided, not left to dev judgment).** The challenge payload for scale readings is `{id, plate, weight}` where `id` identifies the scale (see `02-solucao-proposta.md#2`, example `"BAL-001"`). `Scale.id` MUST be the primary key, of type `String`, matching this business identifier directly (e.g. `"BAL-001"`) — do NOT use an internal `Long` id with a separate business code field. Epic 2's `ScaleAuthService` looks up `Scale` directly by this `id` from the payload, and Epic 3's `ConcurrentHashMap<String, ScaleState>` uses this same `String` as its key. Use `@Id` on the `String id` field (no `@GeneratedValue`) — the id is supplied by the admin at creation time (e.g., in the `POST /api/scales` payload or `data.sql` seed), not auto-generated.
- `apiKey` stored in plaintext is a deliberate, accepted trade-off for this delivery (per AC #3) — do not add hashing/encryption, but do add a one-line comment in code noting it's plaintext for delivery scope, and ensure the README (Epic 7) mentions it as a known trade-off.
- Authentication check itself (comparing header `X-Scale-Key` to `Scale.apiKey`) is NOT part of this story — that's Epic 2, Story 2.1 (`ScaleAuthService`). This story only cadastres/persists the scale + its key.

### Project Structure Notes

- Same base package and layering convention as Stories 1.1–1.3.
- `@ManyToOne` to `Branch` requires `Branch` entity to already exist (created in Story 1.2) — confirm it's in place before starting.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.4: Scale Cadastre]
- [Source: _bmad-output/planning-artifacts/epics.md#FR4]
- [Source: 02-solucao-proposta.md#2. Arquitetura de ingestão] — payload shape `{id, plate, weight}`, scale `id` semantics
- [Source: 02-solucao-proposta.md#6. Extras implementados] — apiKey via header `X-Scale-Key`, static per-scale key

## Previous Story Intelligence

- Story 1.2 established `data.sql` and the CRUD controller conventions; Story 1.3 followed the same pattern for `Truck`. Continue appending to the same `data.sql`, and reuse the same DTO/validation/response conventions.
- The `Branch` entity (from Story 1.2) must exist before this story's `@ManyToOne` relationship can be added.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
