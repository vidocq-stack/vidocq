# Knock - Claude Code Guidelines

> "Knock, or the Triumph of Medicine" (Jules Romains, 1923) — a doctor who
> diagnoses everything and transforms every villager into a potential patient.
> This is exactly the role of a health check system: examine each component
> of the system, diagnose its state (UP/DOWN), and aggregate these diagnoses into a
> health dashboard consultable at any moment.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The MicroProfile Health 4.0 TCK is a **public Maven Central** artifact:
  `org.eclipse.microprofile.health:microprofile-health-tck:4.0`
  (unlike Jakarta TCKs, no need to install manually).

## Essential Commands

```bash
# Build reactor (includes knock-tck)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test only
./run-official-tck-mp-health-4.0.sh

# TCK — full suite
./run-official-tck-mp-health-4.0.sh all

# TCK — targeted test
./run-official-tck-mp-health-4.0.sh -Dtest=TestName
```

> `knock-tck` is now **part of the main reactor**, but its POM stays at **Model 4.0.0**
> and remains **standalone-capable** to work around ShrinkWrap Maven Resolver 3.3 vs
> Model 4.1.0 — same constraint as `cassini-tck`, `foy-tck`, `champollion-tck`, and
> `ravel-tck`. Do not upgrade it to Model 4.1.0 nor make it depend on reactor-only features.

## Architecture

Knock is a MicroProfile Health 4.0 implementation with **zero third-party libraries**
(no SmallRye Health, no Vert.x Health), only Jakarta EE / MicroProfile specs as
dependencies, virtual threads, strict JPMS.

```
knock-api          ← Re-exposes the org.eclipse.microprofile.health spec
                     (HealthCheck, HealthCheckResponse, HealthCheckResponseBuilder,
                      @Liveness, @Readiness, @Startup)
knock-core         ← Implementation: HealthCheckRegistry, UP/DOWN aggregation,
                     JSON serialization via champollion (Jakarta JSON-P/JSON-B)
knock-cdi-vauban   ← CDI Vauban integration: BCE discovering
                     @Liveness / @Readiness / @Startup beans, auto-registering in registry
knock-cassini      ← Cassini adapter (Jakarta REST): JAX-RS resources /health,
                     /health/live, /health/ready, /health/started
knock-tck          ← Official MicroProfile Health 4.0 TCK runner
                     (in the reactor, standalone-capable POM Model 4.0.0)
```

**Health check flow:**
HTTP `GET /health/live` → `knock-cassini` (JAX-RS resource `@Path("/health/live")`)
→ `HealthCheckRegistry` → collect all `@Liveness HealthCheck` beans → call `.call()`
on each instance → aggregation (DOWN if ≥ 1 DOWN) → JSON serialization via champollion
→ HTTP 200/503 response.

**MicroProfile Health 4.0 endpoints:**
- `GET /health/live`    — liveness probes (`@Liveness`)
- `GET /health/ready`   — readiness probes (`@Readiness`)
- `GET /health/started` — startup probes (`@Startup`)
- `GET /health`         — aggregate of all checks

**Response format (spec §3.1):**
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "database-connection",
      "status": "UP",
      "data": { "responseTime": "12ms" }
    }
  ]
}
```
HTTP 200 if `status=UP`, HTTP 503 if `status=DOWN`.

## Architecture Constraints Not to Violate

1. **`knock-core` only depends on `org.eclipse.microprofile.health` + `jakarta.json`**
   (Jakarta JSON-P, provided by champollion) — no CDI, no JAX-RS. The registry,
   aggregation, and JSON serialization work in standalone SE.
2. **`knock-cdi-vauban` depends on `knock-core` + `jakarta.cdi`** but never the reverse —
   CDI discovery is an optional module invisible from the core.
3. **`knock-cassini` depends on `knock-core` + `jakarta.ws.rs`** (Jakarta REST, implemented by
   Cassini) — JAX-RS adaptation is optional and decoupled from the core. The `/health*`
   endpoints are standard JAX-RS resources, not raw Chappe handlers.
4. **champollion is the reference Jakarta JSON-P/JSON-B implementation**: `knock-core`
   declares `requires jakarta.json` (spec API); champollion is provided at runtime.
   Never Jackson, Gson, or any other third-party JSON library.
5. **Strict JPMS**: all modules have a `module-info.java`, `internal.*` packages
   not exported, SPI exposed only via `provides ... with`.
6. **No `synchronized`, no `ThreadLocal`** — virtual-thread-friendly. Use
   `ConcurrentHashMap` for the registry, `ScopedValue` if propagation context becomes needed.
7. **No `setAccessible(true)` reflection** — no functional reason in a
   health check system. Any eventual JPMS opening must be documented.
8. **MicroProfile Health 4.0 TCK PASS at 100%** is a hard contract before any structural merge.

## Conventions

- **Explicit Java modules**: all modules have a `module-info.java`.
- **Packages**:
  - `io.vidocq.knock.spi.*` = stable public SPI (third-party registry extensions)
  - `io.vidocq.knock.internal.*` = internal code (may break between versions)
- **Maven groupId**: `io.vidocq.knock`.
- **Records** for immutable objects (`HealthCheckResult`, `HealthSnapshot`);
  **sealed interfaces** for closed hierarchies (status, probe types).
- **Exhaustive pattern matching** on switch — no `if/else if` chains.
- **JUnit 6** only for tests (BOM `org.junit:junit-bom` 6.x).
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## TDD — Test-Driven Development (mandatory)

Knock is developed with **strict TDD**, in this order:

1. **Red** — write the test describing the expected behavior (cite the MicroProfile Health 4.0
   spec section in JavaDoc comments). The test must fail for the right reason
   (compilation OK, assertion KO).
2. **Green** — write the minimum code to make the test pass.
3. **Refactor** — clean up while keeping tests green. Run the full module suite
   before any commit.

Concrete rules:

- **One test per public class**, named `<Class>Test`, in the same package (`src/test/java`).
- **No Mockito** — hand-written doubles or inline `HealthCheck` implementations.
- **Spec fixture tests**: for each MicroProfile Health 4.0 spec section referenced,
  a test named `<method>_spec_section<X>_<Y>()`.

## TCK — Technology Compatibility Kit

MicroProfile Health TCK — run from the `knock-tck` module (POM Model 4.0.0, included in the
reactor but kept standalone-capable to work around ShrinkWrap Maven Resolver 3.3):

| TCK | Artifact | Target |
|---|---|---|
| MicroProfile Health 4.0 | `org.eclipse.microprofile.health:microprofile-health-tck:4.0` | 100% PASS (hard contract) |

The `run-official-tck-mp-health-4.0.sh` script:

- supports `smoke` (default), `all`, and targeted `-Dtest=TestName`;
- installs the reactor locally (`mvn install -DskipTests`) before invocation;
- produces a `target/tck-report.txt` report with the PASS/FAIL/SKIP score.

**Release discipline:**

- **No structural merge** on `knock-core`/`knock-cdi-vauban` without TCK PASS.
- Any challenges (disabled tests for spec interpretation or TCK bug) are
  documented in `TCK.md` with spec citation, test hash, and reactivation plan.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI,
  modification of registry or HTTP endpoints).
- **Balanced elegance**: prefer a simple design that passes the TCK over a perfect design
  that does not. Document trade-offs in ADRs (`docs/adr/`).
- **No laziness on specs**: cite the MicroProfile Health 4.0 section in code
  comments when the implementation directly responds to it.
- **Zero third-party libraries**: Jakarta EE and MicroProfile specs are the only
  dependencies allowed in `provided`/`compile` scope. If an implementation library
  seems necessary, the decomposition is wrong.
- Use agents **`jpms-guardian`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`** proactively on any `module-info.java` modification,
  concurrent code, or `pom.xml`.
- If the rules in this file need updating, remember to align `AGENTS.md` accordingly
  so Copilot Code can reference it easily.
