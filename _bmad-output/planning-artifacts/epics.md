---
stepsCompleted: [1]
inputDocuments:
  - 01-desafio-original.md
  - 02-solucao-proposta.md
  - 03-plano-execucao-sem-docker.md
---

# desafio-serasa - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for the Serasa backend challenge, decomposing requirements into implementable stories ordered by: E1 → E3 → E2 → E4 → E5 → E6 → E7.

## Requirements Inventory

### Functional Requirements

FR1: CRUD for Branch (id, name, location)
FR2: CRUD for GrainType (id, name, purchasePricePerTon, maxReferenceStock, currentStock)
FR3: CRUD for Truck (id, licensePlate, tare)
FR4: CRUD for Scale (id, branchId, apiKey)
FR5: CRUD for TransportTransaction (truckId, grainTypeId, branchId, status[IN_TRANSIT|AT_DOCK|WEIGHING|COMPLETED], startDate, endDate, grossWeightKg, netWeightKg, loadCost)
FR6: Endpoint POST /api/scales/readings accepts payload {id, plate, weight} with optional fields seq and timestamp
FR7: Scale authentication via header X-Scale-Key before processing the reading
FR8: Immediate 202 Accepted response after validation (fire-and-forget behavior)
FR9: Idempotency: readings with duplicate seq for the same scaleId are discarded
FR10: EstabilizationService maintains per-scaleId state in ConcurrentHashMap
FR11: Sliding window algorithm N=15 readings + standard deviation ≤ σ_max=5kg + M=3 consecutive stable windows
FR12: On stability detection: persist WeighingRecord (plate, grossWeight, tare, netWeight, dateTime, scaleId, grainType, loadCost) and close TransportTransaction
FR13: Reset scale state when weight < 50kg (truck left the scale)
FR14: alreadyPersisted lock prevents double persistence in the same weighing session
FR15: Margin calculation: linear interpolation 5%–20% inversely proportional to currentStock/maxReferenceStock
FR16a: loadCost = (netWeightKg / 1000) × purchasePricePerTon — persisted in TransportTransaction
FR16b: salePricePerTon = purchasePricePerTon × (1 + margin) — calculated on demand for reports, not stored
FR17: Report: total purchase cost by grain type / period
FR18: Report: average margin applied per grain type
FR19: Report: scale ranking by weighing volume
FR20: Report: average weighing duration per branch
FR21: Alert: grain types operating near the 20% margin ceiling (scarce stock)
FR22: ESP32 simulator: fires POST every 100ms with decreasing noise, exercises idempotency

### NonFunctional Requirements

NFR1: Stack: Java + Spring Boot, H2 in-memory, no containers
NFR2: Concurrency between scales via ConcurrentHashMap.compute() per scaleId — no global lock
NFR3: Stabilization parameters configurable via application.yml (N, σ_max, M)
NFR4: H2 Console enabled at /h2-console for inspection during development
NFR5: Application starts with ./mvnw spring-boot:run, zero external setup
NFR6: Architecture decision (Kafka vs synchronous) documented in README
NFR7: AI prompts used and AI-generated code must be shared in the delivery

### Additional Requirements

- No external starter template — Spring Boot project initialized via Spring Initializr (Web, JPA, H2, Validation)
- ConcurrentHashMap.compute() ensures per-key atomicity without manual synchronized blocks
- Data model designed for future Postgres migration (driver/config swap only)
- data.sql seed file for initial data (grain types, branches, scales, trucks)

### UX Design Requirements

N/A — backend-only project.

### FR Coverage Map

| FR | Epic | Story |
|----|------|-------|
| FR1–FR5 | E1 | 1.1–1.5 |
| FR10–FR14, NFR2–NFR3 | E3 | 3.1–3.2 |
| FR6–FR9 | E2 | 2.1–2.2 |
| FR15–FR16a–FR16b | E4 | 4.1 |
| FR17–FR21 | E5 | 5.1–5.2 |
| FR22 | E6 | 6.1–6.2 |
| NFR6–NFR7 | E7 | 7.1 |

## Epic List

- E1: Core Domain Cadastres
- E3: Stabilization Engine
- E2: Scale Ingestion Endpoint
- E4: Margin & Cost Calculation
- E5: Reports & Statistics
- E6: ESP32 Simulator & Tests
- E7: Documentation & Delivery

---

## Epic 1: Core Domain Cadastres

Set up all base entities with CRUD endpoints and seed data so that the rest of the system has valid references to work with.

### Story 1.1: Project Bootstrap

As a developer,
I want a runnable Spring Boot project with H2 configured,
So that I can immediately start building features without setup friction.

**Acceptance Criteria:**

**Given** the repository is cloned
**When** `./mvnw spring-boot:run` is executed
**Then** the application starts on port 8080 with no errors
**And** H2 Console is accessible at `/h2-console` with JDBC URL `jdbc:h2:mem:balancas`
**And** `application.yml` contains configurable stabilization params (windowSize, stdDevThreshold, consecutiveWindows)

**Tasks:**
- [ ] Init project via Spring Initializr: Web, JPA, H2, Validation
- [ ] Configure `application.yml` (H2, JPA ddl-auto: update, H2 console, stabilization params)
- [ ] Verify app starts and H2 console loads

---

### Story 1.2: Branch & GrainType Cadastres

As an admin,
I want to register branches and grain types,
So that trucks and transactions can reference valid locations and commodities.

**Acceptance Criteria:**

**Given** valid payloads
**When** POST /api/branches and POST /api/grain-types are called
**Then** entities are persisted and returned with generated id
**And** GET /api/branches and GET /api/grain-types return all records
**And** GrainType includes purchasePricePerTon, maxReferenceStock, currentStock

**Tasks:**
- [ ] Create `Branch` entity + repository + REST controller (CRUD)
- [ ] Create `GrainType` entity + repository + REST controller (CRUD)
- [ ] Add seed data in `data.sql` (at least 2 branches, 3 grain types)

---

### Story 1.3: Truck Cadastre

As an admin,
I want to register trucks with their tare weight,
So that net weight can be calculated during weighing.

**Acceptance Criteria:**

**Given** a valid payload with licensePlate and tare
**When** POST /api/trucks is called
**Then** truck is persisted with tare stored in kg
**And** GET /api/trucks/{id} returns the truck with all fields

**Tasks:**
- [ ] Create `Truck` entity + repository + REST controller (CRUD)
- [ ] Add seed data (at least 3 trucks with different tare values)

---

### Story 1.4: Scale Cadastre

As an admin,
I want to register scales with their branch and API key,
So that incoming readings can be authenticated and associated to a branch.

**Acceptance Criteria:**

**Given** a valid payload with branchId and apiKey
**When** POST /api/scales is called
**Then** scale is persisted linked to the branch
**And** GET /api/scales returns all scales with their branchId
**And** apiKey is stored (plaintext for this delivery — note trade-off in README)

**Tasks:**
- [ ] Create `Scale` entity + repository + REST controller (CRUD)
- [ ] Add seed data (at least 2 scales linked to seeded branches)

---

### Story 1.5: TransportTransaction Cadastre

As an operator,
I want to open a transport transaction when a truck departs,
So that the system knows which grain type the truck is carrying before weighing.

**Acceptance Criteria:**

**Given** valid truckId, grainTypeId, branchId
**When** POST /api/transactions is called
**Then** transaction is created with status IN_TRANSIT and startDate set
**And** GET /api/transactions/{id} returns the full transaction
**And** PATCH /api/transactions/{id}/status allows manual status update

**Tasks:**
- [ ] Create `TransportTransaction` entity + repository + REST controller
- [ ] Implement status enum: IN_TRANSIT, AT_DOCK, WEIGHING, COMPLETED
- [ ] Expose PATCH endpoint for manual status transition

---

## Epic 3: Stabilization Engine

Implement the core sliding-window stabilization algorithm that detects when a scale reading is stable and triggers weighing record persistence.

### Story 3.1: StabilizationService — Core Algorithm

As the system,
I want to evaluate incoming scale readings using a sliding window with standard deviation check,
So that I can detect when the scale has stabilized and persist the weighing record exactly once.

**Acceptance Criteria:**

**Given** continuous readings for a scaleId with decreasing noise
**When** N=15 readings are buffered and stdDev ≤ σ_max for M=3 consecutive windows
**Then** the weighing record is persisted with the average weight of the stable window
**And** `alreadyPersisted` flag prevents a second persistence for the same session
**And** when weight drops below 50kg, scale state resets for the next truck
**And** two concurrent requests for different scaleIds do not block each other (compute() per key)

**Tasks:**
- [x] Create `ScaleState` value object (buffer: Deque, consecutiveStableWindows, alreadyPersisted)
- [x] Create `StabilizationService` with `ConcurrentHashMap<String, ScaleState>`
- [x] Implement `process(scaleId, plate, weightKg)` using `map.compute()` for atomicity
- [x] Implement stdDev calculation over the buffer
- [x] Implement reset logic (weight < 50kg threshold)
- [x] Inject stabilization params from `application.yml`

---

### Story 3.2: Weighing Record Persistence

As the system,
I want to persist a complete WeighingRecord and close the TransportTransaction when stability is detected,
So that the weighing data is durable and the transaction lifecycle is complete.

**Acceptance Criteria:**

**Given** stability is detected for a scaleId + plate
**When** the system looks up the open TransportTransaction for that truck
**Then** a WeighingRecord is saved with: grossWeightKg, tare (from Truck), netWeightKg, dateTime, scaleId, grainTypeId, loadCost (FR16a)
**And** the TransportTransaction status is set to COMPLETED with endDate
**And** if no open transaction is found for that plate, the event is logged and skipped gracefully

**Tasks:**
- [x] Create `WeighingRecord` entity + repository
- [ ] Implement persistence call inside `StabilizationService` upon stability detection (pending: Epic 2 has not been built yet to wire the real call site — see story 3.2 Task 3)
- [x] Fetch Truck tare and compute netWeightKg = grossWeightKg - tare
- [x] Compute loadCost = (netWeightKg / 1000) × grainType.purchasePricePerTon
- [x] Close TransportTransaction (status = COMPLETED, endDate = now)

---

## Epic 2: Scale Ingestion Endpoint

Expose the HTTP endpoint that receives ESP32 readings, authenticates the scale, and routes to the stabilization engine.

### Story 2.1: Ingestion Endpoint with Authentication

As a scale (ESP32),
I want to POST readings to /api/scales/readings with my API key,
So that only authorized scales can inject data into the system.

**Acceptance Criteria:**

**Given** a POST to /api/scales/readings with header X-Scale-Key matching the scale's apiKey
**When** the payload is {id, plate, weight} (or extended with seq/timestamp)
**Then** the system responds 202 Accepted immediately
**And** an invalid or missing X-Scale-Key returns 401 Unauthorized
**And** a missing required field returns 400 Bad Request

**Tasks:**
- [ ] Create `ScaleReadingController` with POST /api/scales/readings
- [ ] Implement `ScaleAuthService`: look up scale by id, compare apiKey header
- [ ] Validate required fields (id, plate, weight) with Bean Validation
- [ ] Wire to `StabilizationService.process()` after auth passes
- [ ] Return 202 on success, 401 on auth fail, 400 on invalid payload

---

### Story 2.2: Idempotency via seq Field

As the system,
I want to discard duplicate readings identified by scaleId + seq,
So that ESP32 retries do not corrupt the stabilization buffer.

**Acceptance Criteria:**

**Given** a reading with an optional seq field
**When** the same scaleId + seq combination has already been processed
**Then** the reading is silently discarded and 202 is still returned
**And** if seq is absent, the reading is processed normally (no idempotency check)

**Tasks:**
- [ ] Add optional `seq` and `timestamp` fields to the reading DTO
- [ ] Maintain a `Set<String>` of processed "scaleId:seq" keys in memory (ConcurrentHashMap or ConcurrentSkipListSet)
- [ ] Check and insert before calling StabilizationService

---

## Epic 4: Margin & Cost Calculation

Implement the dynamic margin calculation logic and expose it so reports can compute sale prices on demand.

### Story 4.1: Margin Calculation Service

As the system,
I want to calculate the sale margin for a grain type based on current stock,
So that scarce grains yield higher margins (up to 20%) and abundant ones lower margins (down to 5%).

**Acceptance Criteria:**

**Given** a GrainType with currentStock=0 and maxReferenceStock=1000
**When** margin is calculated
**Then** margin = 20% (maximum, stock fully depleted)

**Given** currentStock >= maxReferenceStock
**When** margin is calculated
**Then** margin = 5% (minimum, stock at or above reference)

**Given** currentStock = 500 and maxReferenceStock = 1000
**When** margin is calculated
**Then** margin = 12.5% (linear interpolation midpoint)

**And** salePricePerTon = purchasePricePerTon × (1 + margin) is computed correctly

**Tasks:**
- [ ] Create `MarginService.calculateMargin(GrainType)` using formula: `margin = maxMargin - (maxMargin - minMargin) × min(1, currentStock / maxReferenceStock)`
- [ ] Create `MarginService.calculateSalePrice(GrainType)` = purchasePricePerTon × (1 + margin)
- [ ] Unit test all three AC scenarios

---

## Epic 5: Reports & Statistics

Expose reporting endpoints that aggregate weighing and transaction data for administrative analysis.

### Story 5.1: Core Aggregation Reports

As an admin,
I want to query cost and throughput reports,
So that I can analyse operational efficiency and grain acquisition costs.

**Acceptance Criteria:**

**Given** completed transactions exist
**When** GET /api/reports/cost-by-grain?from=&to= is called
**Then** returns total loadCost grouped by grainType for the period

**When** GET /api/reports/scale-ranking is called
**Then** returns scales ordered by number of completed weighings (descending)

**When** GET /api/reports/avg-weighing-duration is called
**Then** returns average (endDate - startDate) in seconds grouped by branch

**Tasks:**
- [ ] Create `ReportController` with the three endpoints
- [ ] Implement JPQL/native queries in `WeighingRecordRepository` and `TransportTransactionRepository`
- [ ] Return structured DTOs (not raw entities)

---

### Story 5.2: Margin & Scarcity Reports

As an admin,
I want to see margin analysis and scarcity alerts,
So that I can identify pricing opportunities and low-stock risks.

**Acceptance Criteria:**

**Given** completed transactions exist
**When** GET /api/reports/avg-margin-by-grain is called
**Then** returns average applied margin per grainType (computed from currentStock at time of report)

**When** GET /api/reports/scarcity-alerts is called
**Then** returns grain types where calculated margin >= 18% (near the 20% ceiling)

**Tasks:**
- [ ] Add GET /api/reports/avg-margin-by-grain using MarginService per grain type
- [ ] Add GET /api/reports/scarcity-alerts filtering grains by margin threshold
- [ ] Document threshold (18%) as configurable in application.yml

---

## Epic 6: ESP32 Simulator & Tests

Validate the full end-to-end flow with a simulator and unit tests for the stabilization core.

### Story 6.1: StabilizationService Unit Tests

As a developer,
I want unit tests covering all stabilization edge cases,
So that the core algorithm is validated independently of HTTP and persistence.

**Acceptance Criteria:**

- Continuously oscillating weight (never within stdDev threshold) → no WeighingRecord persisted
- Weight stabilizes (N readings, M consecutive windows) → persisted with correct average weight
- Weight drops to ~0 after stabilization → state resets, ready for next truck
- Two consecutive sessions on same scale (truck A then truck B) → buffers do not mix
- Single spike in an otherwise stable window → does not break detection if overall stdDev is within threshold
- `alreadyPersisted` lock: subsequent readings with truck still on scale do not trigger a second persistence

**Tasks:**
- [ ] Create `StabilizationServiceTest` with mocked WeighingRecord repository
- [ ] Implement each of the 6 scenarios above as individual test methods
- [ ] Use `@ExtendWith(MockitoExtension.class)` — no Spring context needed

---

### Story 6.2: ESP32 Simulator

As a developer,
I want a runnable simulator that fires POST requests every 100ms with decreasing noise,
So that I can validate the full pipeline end-to-end using the real HTTP protocol.

**Acceptance Criteria:**

**Given** the application is running on localhost:8080
**When** the simulator is started for a configured scaleId + plate + target weight
**Then** it sends POST /api/scales/readings every 100ms with X-Scale-Key header
**And** initial readings have ±50kg noise, decreasing to ±2kg over ~5 seconds
**And** seq increments with each request to exercise idempotency
**And** after stabilization is expected, the simulator logs "done" and stops

**Tasks:**
- [ ] Implement simulator as a standalone Java main class (or test with @SpringBootTest disabled)
- [ ] Configure: baseUrl, scaleId, apiKey, plate, targetWeight, totalDurationMs
- [ ] Implement noise decay: noise = initialNoise × max(0, 1 - elapsedMs / decayMs)
- [ ] Log each request + response status to stdout

---

## Epic 7: Documentation & Delivery

Produce the README and delivery artifacts required by the challenge, including architecture notes, prompts used, and AI-generated code disclosure.

### Story 7.1: README & Delivery Artifacts

As a candidate,
I want a complete README and delivery package,
So that the evaluator can run the project, understand the architecture decisions, and review AI usage.

**Acceptance Criteria:**

**Given** the README exists at project root
**When** an evaluator reads it
**Then** it covers: how to run (mvnw command), H2 console URL, architecture note (Kafka design vs synchronous delivery trade-off), stabilization algorithm explanation, margin formula, and known trade-offs

**And** a section lists all AI prompts used during development
**And** AI-generated code is identified (comment or section)

**Tasks:**
- [ ] Write README.md (run instructions, H2 console, architecture note from 03-plano-execucao-sem-docker.md section 3)
- [ ] Document stabilization algorithm (window size, stdDev, consecutive windows — all configurable)
- [ ] Document margin formula with example values
- [ ] Add "AI Usage" section with prompts and generated artifacts
- [ ] Add trade-offs section (H2 vs Postgres, sync vs Kafka, in-memory state vs Redis)
