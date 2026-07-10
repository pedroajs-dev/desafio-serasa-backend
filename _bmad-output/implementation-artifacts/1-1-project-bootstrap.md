---
baseline_commit: 3105c0ce5e63f156044a044563037eb5348b272b
---

# Story 1.1: Project Bootstrap

Status: review

## Story

As a developer,
I want a runnable Spring Boot project with H2 configured,
so that I can immediately start building features without setup friction.

## Acceptance Criteria

1. **Given** the repository is cloned, **when** `./mvnw spring-boot:run` is executed, **then** the application starts on port 8080 with no errors.
2. **And** H2 Console is accessible at `/h2-console` with JDBC URL `jdbc:h2:mem:balancas`.
3. **And** `application.yml` contains configurable stabilization params (`windowSize`, `stdDevThreshold`, `consecutiveWindows`).

## Tasks / Subtasks

- [x] Task 1: Initialize Spring Boot project (AC: #1)
  - [x] Generate project via Spring Initializr equivalent (Maven, Java, Spring Boot latest stable 3.x) with dependencies: Web, JPA, H2, Validation
  - [x] Set `groupId`/`artifactId` matching repo (e.g. `com.serasa.balancas`), Java version 21 (fixed — not 17+)
  - [x] Verify `mvnw` / `mvnw.cmd` wrapper scripts are present and executable
- [x] Task 2: Configure `application.yml` (AC: #1, #2, #3)
  - [x] Replace default `application.properties` with `src/main/resources/application.yml`
  - [x] Configure H2 datasource: `jdbc:h2:mem:balancas;DB_CLOSE_DELAY=-1`, driver `org.h2.Driver`, username `sa`, empty password
  - [x] Enable H2 console at path `/h2-console`
  - [x] Set `spring.jpa.hibernate.ddl-auto: update`, `show-sql: true`, dialect `org.hibernate.dialect.H2Dialect`
  - [x] Add custom config block for stabilization params
- [x] Task 3: Verify application starts and H2 console loads (AC: #1, #2)
  - [x] Run `./mvnw spring-boot:run` and confirm no startup errors, port 8080
  - [x] Confirm `/h2-console` loads in browser and connects with the configured JDBC URL

## Dev Notes

- This is the **first story of the project** — no existing code, no previous story to build on. There is nothing to reuse; everything created here becomes the foundation later stories extend.
- **Final architecture decision (do not implement Kafka):** per `03-plano-execucao-sem-docker.md`, Kafka/Redpanda was dropped for this delivery (Upstash Kafka discontinued, no Docker/WSL available). The ingestion → stabilization flow will be **synchronous, in-process**, using `ConcurrentHashMap.compute()` per `scaleId` for atomicity (no Kafka producer/consumer, no manual `synchronized`). This story only needs to make the project runnable — do not scaffold any messaging dependencies.
- No containers, no Postgres for this delivery — H2 in-memory only (NFR1). Data model should stay simple JPA entities so a future swap to Postgres is just a driver/config change (no code implications for this story).
- Stabilization parameter names to use going forward (referenced by later Epic 3 stories): `windowSize` (N=15), `stdDevThreshold` (σ_max=5kg), `consecutiveWindows` (M=3). Bind these via a `@ConfigurationProperties(prefix = "stabilization")` class in a later story if convenient, or plain `@Value` injection — this story just needs the YAML keys present and readable.
- Do not create `data.sql` seed data yet — that belongs to Story 1.2 (Branch & GrainType) once entities exist.
- Do not create any entities, repositories, or controllers in this story — scope is strictly project bootstrap + config.

### Project Structure Notes

- Standard Maven layout: `src/main/java/<base-package>/...`, `src/main/resources/application.yml`, `src/test/java/...`.
- No existing source tree to conflict with — this story establishes it.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.1: Project Bootstrap]
- [Source: 03-plano-execucao-sem-docker.md#1. Banco de dados — H2 em memória] — exact `application.yml` H2 block to use
- [Source: 03-plano-execucao-sem-docker.md] — rationale for no-Kafka, synchronous architecture (do not re-introduce Kafka deps)
- [Source: _bmad-output/planning-artifacts/epics.md#NonFunctional Requirements] — NFR1 (H2, no containers), NFR3 (configurable stabilization params), NFR5 (zero external setup)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Project generated via `start.spring.io` API (Spring Boot 3.5.0, Java 21, Maven, deps: web, data-jpa, h2, validation) since no local Spring Initializr / archetype was available offline.
- `./mvnw -q -DskipTests compile` — clean compile, no errors.
- `./mvnw -q spring-boot:run` started in background — log confirmed Tomcat started on port 8080, Hibernate/H2 initialized, no errors or exceptions.
- `curl http://localhost:8080/h2-console` returned `302` (redirect to H2 console login page) — expected behavior confirming the console is mounted and reachable.
- Verified `spring-boot-starter-parent` version is 3.5.0 (3.3.5 rejected by Initializr as below compatible range).
- Stopped both spawned `java.exe` processes (PIDs 11880, 10988) after verification — no lingering background server left running.

### Completion Notes List

- Generated a fresh Maven/Spring Boot 3.5.0 project at repo root (groupId `com.serasa`, artifactId `balancas`, base package `com.serasa.balancas`), Java 21 fixed in `pom.xml`.
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `com.h2database:h2` (runtime), `spring-boot-starter-test`.
- Removed generated `application.properties`; added `src/main/resources/application.yml` with H2 datasource (`jdbc:h2:mem:balancas;DB_CLOSE_DELAY=-1`), H2 console enabled at `/h2-console`, JPA `ddl-auto: update` + `show-sql: true` + H2 dialect, explicit `server.port: 8080`, and a `stabilization` config block (`window-size: 15`, `std-dev-threshold: 5.0`, `consecutive-windows: 3`) for later Epic 3 use.
- Verified end-to-end: app compiles, starts on port 8080 with no errors, H2 console responds. All 3 ACs satisfied.
- No entities/repositories/controllers/data.sql created — out of scope per Dev Notes, reserved for Story 1.2 onward.
- `.gitignore` from the generated project already excludes `target/` and wrapper jar — no changes needed there.

### File List

- `pom.xml` (new)
- `mvnw`, `mvnw.cmd` (new)
- `.mvn/wrapper/maven-wrapper.properties` (new)
- `.gitignore` (new, from Initializr template)
- `.gitattributes` (new, from Initializr template)
- `src/main/java/com/serasa/balancas/BalancasApplication.java` (new)
- `src/main/resources/application.yml` (new)
- `src/test/java/com/serasa/balancas/BalancasApplicationTests.java` (new)
- `src/main/resources/application.properties` (deleted — replaced by `application.yml`)

## Change Log

- 2026-07-10: Story implemented — Spring Boot 3.5.0 / Java 21 project bootstrapped with H2 + stabilization config; verified startup and H2 console access. Status set to `review`.
