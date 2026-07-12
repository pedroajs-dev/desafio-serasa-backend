---
title: 'Duplicate-Resource Prevention on Cadastro Creation Endpoints'
type: 'bugfix'
created: '2026-07-12'
baseline_commit: e91edcd90525ddb0039e31545bfedf02a94ed690
status: 'done'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Manual Swagger smoke testing on a fresh clone of `main` found that `POST /api/trucks` leaks an unhandled 500 (raw `DataIntegrityViolationException`) on a duplicate `licensePlate`, and `POST /api/branches` / `POST /api/grain-types` silently accept unlimited exact duplicates with no validation at all.

**Approach:** Add a new `DuplicateResourceException` (sibling to `ResourceNotFoundException`/`BusinessException` in `com.serasa.balancas.common.exception`), map it to `409 Conflict` in `GlobalExceptionHandler`, and introduce a thin `@Service` layer (`TruckService`/`BranchService`/`GrainTypeService`) for these three domains that check-before-insert. The controllers become thin and delegate to the service, aligning these domains with the documented Controller -> Service/Repository layering (README "Decisão de arquitetura interna: MVC"). This deliberately corrects the current state where creation logic lives inline in the controllers.

## Boundaries & Constraints

**Always:**
- 409 uses a distinct new exception type, never `BusinessException` (400 is already domain-rule semantics, e.g. "truck already has an open transaction").
- Duplicate-check + persistence logic lives in the new `@Service.create()` method (not the controller); controllers only delegate. Uniqueness check happens before `repository.save()`, using a new `existsBy...` repository method (case-sensitive exact match on the natural key).
- Truck key = `licensePlate`. Branch key = `name` alone (no evidence two branches share a name at different locations; `location` is not required/unique today). GrainType key = `name`.
- Error message format: `"<Entity> with <field> <value> already exists"` (e.g. `"Truck with license plate ABC1234 already exists"`).
- The `id`-nulling on create (`setId(null)`) moves into the service alongside the persistence logic — controller keeps no business logic.
- New regression tests follow the existing `@SpringBootTest @AutoConfigureMockMvc` + real MockMvc pattern (no mocking), matching `TruckControllerTest`/`BranchControllerTest`/`GrainTypeControllerTest` style. "Only one row" is asserted per specific key value (repository count for that plate/name), not a global table count, since the H2 test DB is shared/seeded with no per-test rollback.

**Ask First:** None anticipated — scope and approach are fully specified above.

**Never:**
- Do not touch `ScaleController`, `TransportTransactionController`, or any other existing 400/404 mapping/behavior. (The transaction guard staying inline is out of scope; only Truck/Branch/GrainType are being re-layered here.)
- Do not reuse `BusinessException`/400 for duplicates.
- Do not change the HTTP contract of the existing controller endpoints (paths, status codes, response bodies) beyond adding the new 409 case.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Duplicate truck | POST `/api/trucks` twice, same `licensePlate` | 2nd call: 409, message names the plate; only 1 row persists | `DuplicateResourceException` -> `GlobalExceptionHandler` -> 409 |
| Duplicate branch | POST `/api/branches` twice, same `name` | 2nd call: 409, message names the branch; only 1 row persists | same |
| Duplicate grain type | POST `/api/grain-types` twice, same `name` | 2nd call: 409, message names the grain type; only 1 row persists | same |
| Non-duplicate creates | Distinct `licensePlate`/`name` values | 201 as before, unaffected | N/A |

</frozen-after-approval>

## Code Map

- `src/main/java/com/serasa/balancas/common/exception/DuplicateResourceException.java` -- new exception class (extends `RuntimeException`, mirrors `ResourceNotFoundException`/`BusinessException` shape)
- `src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java` -- add `@ExceptionHandler(DuplicateResourceException.class)` -> 409, `ErrorResponse.of(409, ex.getMessage())`
- `src/main/java/com/serasa/balancas/truck/TruckService.java` -- NEW `@Service`; `create()` does duplicate check + `setId(null)` + `save()`
- `src/main/java/com/serasa/balancas/truck/TruckController.java` -- inject `TruckService`, delegate create; no inline logic
- `src/main/java/com/serasa/balancas/truck/TruckRepository.java` -- add `boolean existsByLicensePlate(String licensePlate)`
- `src/main/java/com/serasa/balancas/branch/BranchService.java` -- NEW `@Service`; `create()` does duplicate check + `setId(null)` + `save()`
- `src/main/java/com/serasa/balancas/branch/BranchController.java` -- inject `BranchService`, delegate create; no inline logic
- `src/main/java/com/serasa/balancas/branch/BranchRepository.java` -- add `boolean existsByName(String name)`
- `src/main/java/com/serasa/balancas/graintype/GrainTypeService.java` -- NEW `@Service`; `create()` does duplicate check + `setId(null)` + `save()`
- `src/main/java/com/serasa/balancas/graintype/GrainTypeController.java` -- inject `GrainTypeService`, delegate create; no inline logic
- `src/main/java/com/serasa/balancas/graintype/GrainTypeRepository.java` -- add `boolean existsByName(String name)`
- `src/test/java/com/serasa/balancas/truck/TruckControllerTest.java` -- add duplicate-plate regression test
- `src/test/java/com/serasa/balancas/branch/BranchControllerTest.java` -- add duplicate-name regression test
- `src/test/java/com/serasa/balancas/graintype/GrainTypeControllerTest.java` -- add duplicate-name regression test
- `README.md` (lines ~49-67, endpoint table) -- append 409 duplicate note to the three affected rows, matching the existing inline-rejection style used for the transactions row

## Tasks & Acceptance

**Execution:**
- [x] `common/exception/DuplicateResourceException.java` -- create new exception class -- distinct 409 semantics per intent
- [x] `common/exception/GlobalExceptionHandler.java` -- add `@ExceptionHandler` mapping `DuplicateResourceException` -> 409 -- wires the new exception to a clean response
- [x] `truck/TruckRepository.java` -- add `boolean existsByLicensePlate(String)` -- lookup for the duplicate guard
- [x] `truck/TruckService.java` + `TruckController.java` -- new service `create()` with `existsByLicensePlate` guard (throw `DuplicateResourceException`) + `setId(null)` + `save()`; controller delegates -- fixes Finding 1 (raw 500 -> clean 409), re-layers Truck
- [x] `branch/BranchRepository.java` -- add `boolean existsByName(String)` -- lookup for the duplicate guard
- [x] `branch/BranchService.java` + `BranchController.java` -- new service `create()` with `existsByName` guard + `setId(null)` + `save()`; controller delegates -- fixes Finding 2a, re-layers Branch
- [x] `graintype/GrainTypeRepository.java` -- add `boolean existsByName(String)` -- lookup for the duplicate guard
- [x] `graintype/GrainTypeService.java` + `GrainTypeController.java` -- new service `create()` with `existsByName` guard + `setId(null)` + `save()`; controller delegates -- fixes Finding 2b, re-layers GrainType
- [x] `test/.../TruckControllerTest.java` -- regression test: create same truck twice, assert 409 + message, assert 1 row via repository count -- covers Finding 1 I/O matrix row
- [x] `test/.../BranchControllerTest.java` -- regression test: create same branch twice, assert 409 + message, assert 1 row -- covers Finding 2a I/O matrix row
- [x] `test/.../GrainTypeControllerTest.java` -- regression test: create same grain type twice, assert 409 + message, assert 1 row -- covers Finding 2b I/O matrix row
- [x] `README.md` -- append 409 duplicate-rejection note to `/api/trucks`, `/api/branches`, `/api/grain-types` rows -- documents new documented behavior

**Acceptance Criteria:**
- Given a truck already exists with a given `licensePlate`, when `POST /api/trucks` is called again with that same `licensePlate`, then response is 409 with a message naming the plate, and exactly one truck row exists for it.
- Given a branch already exists with a given `name`, when `POST /api/branches` is called again with that same `name`, then response is 409 with a message naming the branch, and exactly one branch row exists for it.
- Given a grain type already exists with a given `name`, when `POST /api/grain-types` is called again with that same `name`, then response is 409 with a message naming the grain type, and exactly one grain-type row exists for it.
- Given existing 400 (`BusinessException`, e.g. truck already has open transaction) and 404 (`ResourceNotFoundException`) behaviors elsewhere in the app, when the full test suite runs, then all pre-existing tests still pass unchanged.

## Spec Change Log

- **2026-07-12 — review patches (no loopback).** Adversarial review (Blind Hunter + Edge Case Hunter) confirmed the check-then-insert guard is TOCTOU-racy and, for Branch/GrainType, had no DB backstop (silent duplicate under concurrency), while the Truck race surfaced an unhandled `DataIntegrityViolationException` → raw 500 (reopening the original Finding-1 defect). Patched in-diff: (1) added `@Column(unique=true)` on `Branch.name` and `GrainType.name` (matching Truck's existing `licensePlate` constraint) so the DB is the source of truth; (2) added a `DataIntegrityViolationException` → 409 handler as the race backstop; (3) routed controller reads through the services so controllers depend only on the service (fully realizing the thin-controller intent, removing the dual service+repo injection). Case/whitespace normalization, managed-migration for the constraints, and a concurrency regression test were deferred (see deferred-work.md). Rejected as by-design: 400-vs-409 split, idempotent-create, name-only natural key, update-path guards, input echo in messages.

## Design Notes

Controllers currently hold persistence logic inline. The analogous transaction guard (`TransportTransactionController` throwing `BusinessException` when a truck already has an open transaction) is *also* inline today — so following it literally would keep the anti-pattern. Instead this fix aligns Truck/Branch/GrainType with the layering the README documents (`Controller → Service/Repository`) by introducing thin `@Service` classes. Constructor-injection style matches the existing controllers. Example service shape (mirrored across all three):

```java
@Service
public class TruckService {
    private final TruckRepository repository;
    public TruckService(TruckRepository repository) { this.repository = repository; }

    public Truck create(Truck truck) {
        if (repository.existsByLicensePlate(truck.getLicensePlate())) {
            throw new DuplicateResourceException(
                "Truck with license plate " + truck.getLicensePlate() + " already exists");
        }
        truck.setId(null);
        return repository.save(truck);
    }
}
```

## Verification

**Commands:**
- `./mvnw test` -- expected: all existing tests pass, plus 3 new duplicate-resource regression tests pass (409 + single-row assertions)

**Manual checks (if no CLI):**
- Start the app, POST the same truck/branch/grain-type payload twice via Swagger or curl; confirm 409 with a clear message on the second attempt.
- Check the H2 Console; confirm only one row exists per entity after the duplicate attempts.

## Suggested Review Order

**Exception → 409 wiring (entry point)**

- New exception type carrying the distinct duplicate semantics; 409, not 400.
  [`DuplicateResourceException.java:3`](../../src/main/java/com/serasa/balancas/common/exception/DuplicateResourceException.java#L3)

- Maps the new exception to 409; the DataIntegrity handler right below is the concurrency backstop.
  [`GlobalExceptionHandler.java:35`](../../src/main/java/com/serasa/balancas/common/exception/GlobalExceptionHandler.java#L35)

**Duplicate-guard logic (new service layer)**

- The check-then-insert guard; Branch/GrainType services mirror this shape.
  [`TruckService.java:16`](../../src/main/java/com/serasa/balancas/truck/TruckService.java#L16)

- Controller now depends only on the service — thin delegate, no inline persistence.
  [`TruckController.java:25`](../../src/main/java/com/serasa/balancas/truck/TruckController.java#L25)

- Derived existence query backing the guard (Branch/GrainType repos add `existsByName`).
  [`TruckRepository.java:7`](../../src/main/java/com/serasa/balancas/truck/TruckRepository.java#L7)

**DB backstop (schema)**

- Unique constraint so the race can't silently persist a duplicate (Truck already had one on `licensePlate`).
  [`Branch.java:18`](../../src/main/java/com/serasa/balancas/branch/Branch.java#L18)
  [`GrainType.java:21`](../../src/main/java/com/serasa/balancas/graintype/GrainType.java#L21)

**Tests & docs (peripheral)**

- Regression: create twice → 201 then 409 + exact message, assert one row per key.
  [`TruckControllerTest.java:88`](../../src/test/java/com/serasa/balancas/truck/TruckControllerTest.java#L88)

- Endpoint table documents the new 409 duplicate rejection.
  [`README.md:51`](../../README.md#L51)
