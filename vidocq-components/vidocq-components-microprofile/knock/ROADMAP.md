# Knock — Implementation plan

> MicroProfile Health 4.0 implementation in the Vidocq style: zero third-party libraries
> (Jakarta EE / MicroProfile specs allowed), JDK 25, virtual threads, strict Java Modules,
> optional CDI integration via Vauban, optional HTTP transport via Chappe.

## Design principles

| Principle | Concrete application |
|---|---|
| Zero third-party libraries | No SmallRye Health, Vert.x Health, Jackson, Gson in `knock-core`. Only the spec APIs are compiled: `knock-mp-health-api` + `jakarta.json` (JSON-P spec, implemented by Champollion). |
| Jakarta / MicroProfile specs allowed | `knock-cdi-vauban` may depend on `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. `knock-cassini` may depend on `jakarta.ws.rs` (Jakarta REST spec, implemented by Cassini). The `knock-core` core is limited to `knock-mp-health-api` + `jakarta.json`. |
| Virtual threads | No `synchronized`, no `ThreadLocal`. The registry uses concurrent structures (`ConcurrentHashMap`, `CopyOnWriteArrayList`). `HealthCheck.call()` invocations can be parallelized on a `VirtualThreadPerTaskExecutor`. |
| Java Modules strict | `module-info.java` everywhere, `internal.*` packages not exported, SPI via `provides/uses`. No unjustified `opens`. |
| Strict TDD | Red → Green → Refactor. Tests written before production code. Systematic citation of the MicroProfile Health 4.0 spec section in test JavaDoc. |
| TCK PASS 100 % | Hard contract on the MicroProfile Health 4.0 TCK before any structural merge. |
| AOT-friendly | No dynamic proxy generation, no `setAccessible(true)`. Compatible with GraalVM `native-image`. |

## Methodology: TDD + TCK as parallel safeguards

Knock is developed with **strict TDD** (Red → Green → Refactor). No production line
is written before a test justifies it. Beyond the internal TDD cycle:

- **Layer 1 — TDD unit tests**: drive the design of each class.
- **Layer 2 — `knock-core` integration tests**: multi-check scenarios, UP/DOWN aggregation,
  concurrent execution. Independent of the TCK and reproducible without Arquillian.
- **Layer 3 — official TCK** (`microprofile-health-tck:4.0`) : 100% PASS contract before
  any structural merge. Module outside the reactor (POM Model 4.0.0).

## MicroProfile Health 4.0 spec recap — key points

### Qualification annotations (§2)

| Annotation | Endpoint | Role |
|---|---|---|
| `@Liveness` | `/health/live` | Is the process alive? (otherwise restart) |
| `@Readiness` | `/health/ready` | Is the process ready to receive traffic? |
| `@Startup` | `/health/started` | Is initialization complete? |
| _(none)_ | `/health` | Aggregate of all checks |

### `HealthCheck` contract (§3)

```java
@FunctionalInterface
public interface HealthCheck {
    HealthCheckResponse call();
}
```

### JSON response format (§3.1)

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "my-check",
      "status": "UP",
      "data": { "key": "value" }
    }
  ]
}
```

- HTTP **200** if `status = UP` (or empty list).
- HTTP **503** if `status = DOWN` (at least one DOWN check).
- `data` is omitted if absent (do not serialize `"data": null`).

### Aggregation rule (§3.2)

- Global status = **DOWN** as soon as at least one individual check is DOWN.
- Empty check list → global status = **UP**.
- Exceptions thrown by `call()` must be caught and converted into a DOWN check
  (check name = exception name or `HealthCheck` class name).

---

## Phases

### M0 — Bootstrap ✅

- ✅ `.sdkmanrc` (`java=25-tem`, `maven=3.9.16`)
- ✅ `.gitignore`, `.mvn/maven.config`
- ✅ parent `pom.xml` (Model 4.1.0, multi-module, Jakarta + MicroProfile Health dependency management)
- ✅ `CLAUDE.md`
- ✅ `AGENTS.md`
- ✅ `ROADMAP.md` (this file)
- ✅ Creation of the 4 submodules with `pom.xml` + skeletal `module-info.java` files:
      `knock-api`, `knock-core`, `knock-cdi-vauban`, `knock-cassini` + `knock-tck` (outside the reactor)
- ✅ `run-official-tck-mp-health-4.0.sh`
- ✅ Validation that `mvn -ntp install -DskipTests` succeeds (reactor + standalone `knock-tck`)
- ✅ Smoke test `KnockTckSmokeTest` : 3/3 PASS

**Java Modules note:** upstream `microprofile-health-api:4.0.1` has no `module-info.class`.
Knock ships a modular fork `io.vidocq.knock:knock-mp-health-api` documented
in `docs/adr/ADR-001-jpms-workaround-microprofile-health.md` — summary:
`module-info.java` adds the explicit `microprofile.health.api` module, the OSGi `@Version`
annotations were removed from `package-info.java` to avoid an automatic module, and the
`src/main/module-info/` workaround remains in place for `knock-core` to avoid Java Modules detection
in `testCompile`. `target/javamodules/` continues to be used to align javac and jlink —
**jlink compatibility is guaranteed without automatic modules** (see ADR-001).

**Deliverable:** `mvn -ntp install -DskipTests` succeeds on the reactor (`knock-api`, `knock-core`,
`knock-cdi-vauban`, `knock-cassini`) and also compiles the out-of-reactor `knock-tck`
project (POM Model 4.0.0). All `module-info.java` files are in place with minimal `requires`.
Smoke test 3/3 PASS.

---

### M1 — Core: registry + aggregation + JSON serialization ✅

**Scope spec:** §2 (HealthCheck interface), §3 (response), §3.1 (JSON format), §3.2 (aggregation).

| Task | Notes | Status |
|---|---|---|
| `KnockHealthCheckResponse` record (implements `HealthCheckResponse`) | `name`, `Status`, `Optional<Map<String,Object>> data` | ✅ |
| `KnockHealthCheckResponseBuilder` (implements `HealthCheckResponseBuilder`) | Fluent builder: `up()`, `down()`, `withData(key, val)`, `build()` | ✅ |
| `HealthCheckRegistry` interface (internal SPI) | `register(ProbeType, HealthCheck)`, `unregister(String name)`, `getChecks(ProbeType)` | ✅ |
| `KnockHealthCheckRegistry` (thread-safe `ConcurrentHashMap` implementation) | Categorized by `ProbeType` (LIVENESS, READINESS, STARTUP, ALL) | ✅ |
| `KnockAggregator` | Calls all checks of the requested type, aggregates (DOWN if ≥ 1 DOWN), captures exceptions → DOWN | ✅ |
| `KnockJsonSerializer` | Serializes `HealthSnapshot` as spec §3.1 JSON via Jakarta JSON-P (`jakarta.json.Json.createObjectBuilder()`) — Champollion is the runtime implementation | ✅ |
| Parallel execution via `VirtualThreadPerTaskExecutor` | All `.call()` invocations launched in parallel, results collected via `Future<HealthCheckResponse>` | ✅ |
| Unit tests `KnockHealthCheckResponseBuilderTest` | UP/DOWN, data key-value, serialization absent if data empty — 13/13 PASS | ✅ |
| Unit tests `KnockAggregatorTest` | all UP → UP; one DOWN → DOWN; exception → DOWN; empty list → UP — 8/8 PASS | ✅ |
| Unit tests `KnockJsonSerializerTest` | UP/DOWN status, checks present/absent, data omitted if null — 8/8 PASS | ✅ |

**M1 decisions:**
- `ProbeType` enum : `LIVENESS`, `READINESS`, `STARTUP`, `ALL`.
- `HealthSnapshot` record : `ProbeType type`, `HealthCheckResponse.Status status`, `List<HealthCheckResponse> checks`.
- JSON serialization **must** go through Jakarta JSON-P (`jakarta.json.Json.createObjectBuilder()`) — Champollion is the only allowed implementation; no `StringBuilder` fallback.

**Deliverable:** `HealthCheckResponse.named("db").up().withData("latency","12ms").build()` works;
correct aggregation; JSON serialization compliant with §3.1.

---

### M2 — Vauban CDI integration (`knock-cdi-vauban`) ✅

**Scope spec:** §4 (CDI integration), §4.1 (discovery CDI beans), §4.2 (automatic registration).

| Task | Notes | Status |
|---|---|---|
| Auto-registration of qualified checks | `HealthCheckRegistrar` injects `@Liveness`/`@Readiness`/`@Startup` `Instance<HealthCheck>` and registers each by probe type | ✅ |
| Registration in `HealthCheckRegistry` at CDI startup | `HealthCheckRegistrar` `@ApplicationScoped` + `@Observes @Initialized(ApplicationScoped.class)` | ✅ |
| Unqualified `HealthCheck` ignored | A `HealthCheck` bean without a probe qualifier is not a procedure — silently ignored, neither registered nor a deployment error (spec §4.2, MP Health TCK `EnforceQualifierTest`) | ✅ |
| `@Inject HealthCheckRegistry` | `KnockCdiHealthCheckRegistry @ApplicationScoped` — direct CDI bean implementing the interface | ✅ |
| Integration tests with Vauban container | Vauban SE bootstrap, auto-registered `@Liveness`/`@Readiness`/`@Startup`, aggregated DOWN, unqualified check ignored (deployment OK, empty aggregate) | ✅ |

**M2 decisions:**
- `@Produces` avoided for the registry: Vauban returns the producer proxy instead of the produced bean
  when calling `select(HealthCheckRegistry.class)`. Solution: direct CDI bean `KnockCdiHealthCheckRegistry`
  implementing `HealthCheckRegistry` — the Vauban proxy then implements the interface and the cast is clean.
- BCE validation only (`@Registration`) — registration is delegated to `HealthCheckRegistrar`
  via standard CDI injection (clearer than `@Synthesis`).
- `module-info.java` in `src/main/module-info/`: same workaround as `knock-core` (vauban-core
  test scope, absent from `target/javamodules/`).

**Deliverable:** an automatically discovered, registered, and registry-queryable
`@Liveness HealthCheck` bean. 5/5 integration tests PASS.

---

### M3 — Jakarta REST endpoints (`knock-cassini`) ✅

**Scope spec:** §3 (endpoints), §3.1 (JSON format), HTTP 200/503 codes.

| Task | Notes | Status |
|---|---|---|
| `KnockHealthResource` JAX-RS resource `@ApplicationScoped @Path("/health")` | 4 `@GET` methods: `/`, `/live`, `/ready`, `/started`; delegates to the registry via `KnockHealthService` | ✅ |
| `ProbeType` ↔ JAX-RS path mapping | `ALL` → `/health`, `LIVENESS` → `/health/live`, `READINESS` → `/health/ready`, `STARTUP` → `/health/started` | ✅ |
| Code HTTP 200 / 503 via `jakarta.ws.rs.core.Response` | `Response.status(report.httpStatus()).type(APPLICATION_JSON_TYPE).entity(json).build()` | ✅ |
| Content-Type `application/json` | `@Produces(MediaType.APPLICATION_JSON)` on the resource | ✅ |
| `@Inject HealthCheckRegistry` in the resource | Field injection + public ctor for direct tests | ✅ |
| TDD tests `KnockHealthResourceTest` | 7/7 PASS (empty 200, 503 down, probe isolation, exception → DOWN, content-type) | ✅ |
| Exported runtime SPI `io.vidocq.knock.runtime.{HealthCheckRegistries, KnockHealthService, HealthReport}` | Java Modules boundary: `internal.*` remains non-exported; adapters go through this SPI | ✅ |

**M3 decisions:**
- `KnockHealthService(registry).report(probeType)` facade returning a `HealthReport(httpStatus, json)` — single entry point for HTTP transports. Encapsulates `KnockAggregator` + `KnockJsonSerializer`.
- Boundary respected: no internal Cassini class import; `Response.type(MediaType.APPLICATION_JSON_TYPE)` (instance, not string) to avoid `RuntimeDelegate.HeaderDelegate.fromString` during tests.
- Tests without HTTP container: a minimal test `TestRuntimeDelegate` (zero third-party lib, zero internal Cassini class) installed via `RuntimeDelegate.setInstance(...)`.
- Collateral M2 refactor: `KnockCdiHealthCheckRegistry` no longer depends on `io.vidocq.knock.internal.KnockHealthCheckRegistry` — it goes through the exported `HealthCheckRegistries.newRegistry()` factory.

**Deliverable:** `GET /health/live` returns 200 `{ "status": "UP", "checks": [...] }` when
all liveness checks pass; 503 otherwise. Endpoints unit-tested (7/7 PASS).
End-to-end integration with embedded Cassini deferred to M5.

---

### M4 — MicroProfile Health 4.0 TCK ✅

**Scope:** official MicroProfile Health 4.0 validation + reproducible script.

| Task | Notes | Status |
|---|---|---|
| `knock-tck/pom.xml` standalone-capable Model 4.0.0 | Same model as `cassini-tck`/`foy-tck`/`ravel-tck`, but included in the reactor | ✅ |
| Arquillian runner + official `microprofile-health-tck:4.0.1` harness | Custom Arquillian container `KnockDeployableContainer` (zero third-party container lib — no embedded Weld, no Undertow) | ✅ |
| Arquillian → embedded Knock adapter | Reuses `CassiniTestHarness` (cassini-tck) for Jakarta REST + chappe-http for the HTTP server; homegrown mini-CDI (~30 LOC) for injecting `@Inject HealthCheck` (no Weld) | ✅ |
| `run-official-tck-mp-health-4.0.sh` | Modes: smoke / all / `-Dtest=TestName`; `target/tck-report.txt` report | ✅ |
| **Contract score: 100% PASS** | **28 tests run / 28 PASS / 0 fails / 0 skipped** | ✅ |

**M4 decisions:**
- **No embedded Weld**: the embedded Arquillian/Weld stack crashes under JDK 25 and violates
  the "zero third-party implementation library" commitment. Instead, a custom `KnockDeployableContainer`
  (~250 LOC, test scope only) receives each Arquillian `WebArchive`, scans qualified `HealthCheck`
  classes, instantiates a registry, attaches a singleton `KnockHealthResource`
  to a JAX-RS `Application`, and starts `CassiniTestHarness` on a free port. The URI is
  exposed to the TCK via `ProtocolMetaData(HTTPContext)`.
- **Homegrown mini-CDI**: for TCK tests that do `@Inject` in a `HealthCheck`
  (`DelegateHealthSuccessfulTest`), a recursive mini-injector instantiates the field type via
  `getDeclaredConstructor().newInstance()` and writes it via `MethodHandles.privateLookupIn`
  (zero `setAccessible(true)`). No qualifiers, no scopes — strictly the minimum
  needed to pass the TCK without bundling Weld.
- **CDI producers**: `@Produces @Liveness/@Readiness/@Startup HealthCheck producer()` are
  invoked via `MethodHandles.privateLookupIn` (package-private methods in the official TCK).
- **`microprofile-config.properties`**: read from the WAR's `/META-INF/microprofile-config.properties`
  and reposted as a system property for `mp.health.default.{readiness,startup}.empty.response`.
  Systematic cleanup between deployments to avoid leaks between tests.
- **No `TCK.md`** created: nothing to document (no disabled test,
  no divergent spec interpretation).

**Deliverable:** MicroProfile Health 4.0 TCK **28/28 PASS** reproducible via
`./run-official-tck-mp-health-4.0.sh all` (`knock-tck/target/tck-report.txt` report).

---

### M5 — Vidocq ecosystem integration ✅

**Scope:** deploy Knock into Cassini and `vidocq`; make Knock the default
health check system of every Vidocq deployment.

| Task | Notes | Status |
|---|---|---|
| `docs/integration-cassini.md` documentation | Dependencies, Java Modules, JAX-RS resource example with dedicated health check | ✅ |
| `docs/integration-vidocq-runtime.md` documentation | Health check configuration in Vidocq, default `/health` access | ✅ |
| ADR-002 integration strategy | Rationale, deployment order, risks (see `docs/adr/ADR-002-vidocq-runtime-integration-strategy.md`) | ✅ |
| ServiceLoader `VaubanComponentProvider` (`META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider`) | `_VaubanComponents` exposes `HealthCheckRegistrar` + `KnockCdiHealthCheckRegistry` — zero-reflection wiring | ✅ |
| `module-info.java` `provides ... with` | Java Modules counterpart for service files (see `knock-cdi-vauban` and `knock-core`) | ✅ |
| `vidocq`: integrate Knock as the health check system | Wrapper module `vidocq-runtime-knock-health-extension` (Maven/Java Modules, no Java code) added in `vidocq-runtime-core-extensions/`. Activates Knock via a single dependency, zero-config integration (BCE + Cassini JAX-RS scanning). | ✅ |

**M5 decisions:**

- **Maven/Java Modules wrapper instead of a dedicated `VidocqExtension`** (ADR-002): no Java code
  on the Vidocq side. Knock self-deploys through two standard SPIs — the `knock-cdi-vauban`
  BCE to discover `@Liveness/@Readiness/@Startup`, and the `@Path` scanning of
  `CassiniExtension` to mount `KnockHealthResource`. Benefit: Knock remains usable
  outside Vidocq with exactly the same deps.
- **`vidocq-runtime-knock-health-extension` `requires transitive`** the 4 Knock modules + depends on
  `vidocq-runtime-cassini-rest-extension`. `champollion-jsonp` is runtime-only.
- **Same Java Modules workaround as `knock-core`** applied to the wrapper (module-info outside
  `src/main/java/`, recompilation in `prepare-package`, `--module-path target/javamodules`)
  to align compilation with the modular `microprofile.health.api` fork.

**Deliverable:** complete documentation (Cassini integration + Vidocq integration + ADR-002),
`vidocq-runtime-knock-health-extension` wrapper module installed and buildable in the Vidocq reactor,
`/health*` available in every Vidocq deployment through a single dependency.

---

## Priority order — why this one?

1. **M1 (core)** first: the registry, aggregator, and JSON serialization are the
   vital minimum. Nothing else can be tested without them.
2. **M2 (CDI)** before M3 (Jakarta REST): endpoints only make sense with discovered and
   registered checks. Validate CDI discovery before wiring the transport.
3. **M3 (Jakarta REST via Cassini)**: `knock-cassini` depends on `knock-cdi-vauban` for
   registry injection; it can be developed in parallel with M2 once the registry is
   stabilized. The standard JAX-RS API is enough — no internal Cassini class.
4. **M4 (TCK)**: conformance contract. Ongoing activity from M1/M2 onward on the covered
   sections; 100% PASS locked before M5.
5. **M5 (integration)** last: we do not pollute the other Vidocq projects before Knock is
   solid and TCK-validated.

## Known risks

| Risk | Mitigation |
|---|---|
| Arquillian TCK incompatibility vs JDK 25 (like `MalformedParameterizedTypeException` on Ravel) | Identify and patch from M4 onward; document in `TCK.md` |
| Parallel check execution and timeout | Define a timeout per check (configurable via `HealthCheckRegistry`); virtual threads let us wait without blocking |
| Champollion availability at runtime for JSON-P | Validate from M1 onward that `champollion` is on the module-path as the Jakarta JSON-P implementation; it is a mandatory runtime dependency of `knock-core` |
| Interaction between different check types on `/health` | Test ALL aggregation with a mix of LIVENESS/READINESS/STARTUP, some DOWN |
| Java Modules and ServiceLoader discovery in `knock-cdi-vauban` | Verify that CDI `provides` do not require `opens` on user modules |
| Availability of the MicroProfile Health 4.0 TCK on Maven Central | Verify availability of `org.eclipse.microprofile.health:microprofile-health-tck:4.0` |

## Decided decisions

- ✅ **Jakarta / MicroProfile specs allowed**: `knock-mp-health-api`, `jakarta.cdi-api`,
  `jakarta.inject-api`, `jakarta.annotation-api`. No SmallRye / Vert.x / Quarkus Health.
- ✅ **Standalone SE `knock-core`**: usable without CDI, without Jakarta REST, without a container.
  Depends on `jakarta.json` (JSON-P spec) with Champollion as the runtime implementation.
- ✅ **Separate `knock-cdi-vauban`**: optional module, not loaded if CDI is absent.
- ✅ **Separate `knock-cassini`**: optional module, exposes endpoints via Jakarta REST
  (`jakarta.ws.rs` spec API) with Cassini as the implementation. Never import internal Cassini classes —
  the standard JAX-RS API is enough.
- ✅ **Strict TDD** on all production modules.
- ✅ **TCK PASS 100 %** as a hard contract.
- ✅ **TCK outside the reactor** (standalone POM Model 4.0.0) — ShrinkWrap Maven Resolver 3.3 constraint.
- ✅ **Parallel check execution** via `VirtualThreadPerTaskExecutor` — virtual-thread-friendly.

## Open decisions

- [ ] Should we add a TTL cache mechanism for health responses (to avoid probing the DB
      on every Jakarta REST request)? → Out of scope for HP 4.0, defer to a post-M5 extension.
- [ ] Support a programmatic health check (without CDI, via `ServiceLoader`) for
      pure SE deployments? → Possible via `META-INF/services/HealthCheck`; to be evaluated in M2.
- [ ] Per-check timeout strategy: configurable via `@ConfigProperty` (Ravel)?
      → Consistency with the Vidocq ecosystem; to be confirmed in M3.
- [ ] MicroProfile Health 4.1 or 5.0 (when released) — design out to make version bumps
      easier without deep refactoring.
