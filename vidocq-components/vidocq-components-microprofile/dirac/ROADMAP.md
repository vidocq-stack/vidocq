# Dirac — Implementation plan

> MicroProfile Metrics 5.1.1 implementation in the Vidocq style: zero third-party
> implementation libraries (Jakarta EE / MicroProfile APIs allowed), Java 25, virtual threads,
> strict JPMS, CDI via Vauban, REST endpoint via Cassini, configuration via Ravel.

## Design principles

| Principle | Concrete application |
|---|---|
| Zero implementation libraries | No Micrometer, Dropwizard Metrics, or SmallRye Metrics in `dirac-core`. Only compiled spec APIs. |
| Metrics / CDI separation | `dirac-core` contains the data structures and registries; `dirac-cdi-vauban` contains the CDI interceptors. |
| Thread-safety without contention | `LongAdder` for counters; `AtomicReference` for states; `ConcurrentHashMap` for registries. No `synchronized`. |
| Strict JPMS | `module-info.java` everywhere, non-exported `internal.*`, SPI via `provides/uses`. No unjustified `opens`. |
| Strict TDD | Red → Green → Refactor. Test before code. Spec section citations in tests. |
| 100% PASS TCK | Hard contract before any structural merge. Score declared in `TCK.md`. |
| Measured performance | JMH from M4, comparison vs Micrometer and SmallRye Metrics, results in `BENCH.md`. |
| AOT-friendly | No dynamic proxy. `@Gauge` resolved by `MethodHandle` at startup. Compatible with GraalVM native-image. |
| OpenMetrics standard | Export format compliant with Prometheus text exposition format 0.0.4 + OpenMetrics 1.0. |

## Methodology: TDD + TCK as parallel safeguards

Dirac is developed with **strict TDD** (Red → Green → Refactor). No production line
is written before a test justifies it. Beyond the internal TDD cycle:

- **Layer 1 — TDD unit tests**: drive the design of each metric type
  and the registry. Testable without a CDI container (that is the purpose of `dirac-core`).
- **Layer 2 — CDI integration tests**: `@Counted`, `@Timed`, `@Gauge` scenarios with
  embedded Vauban. Verify interceptor and producer resolution without the TCK.
- **Layer 3 — official TCK** (`microprofile-metrics-tck:5.1.1`): 100% PASS contract
  before any structural merge. Module outside the reactor (POM Model 4.0.0).
- **Layer 4 — JMH Benchmarks**: `dirac-bench` compares throughput, interception overhead,
  p99 latency vs Micrometer and SmallRye Metrics on the same JVM.

## Module architecture

```
dirac-mp-metrics-api io.vidocq.dirac.mp.metrics.api (artifact module: microprofile.metrics.api)
  → Local repackage of microprofile-metrics-api with explicit module-info

dirac-api            io.vidocq.dirac.api
  exports io.vidocq.dirac.api
  requires microprofile.metrics.api
  → Public SPI: MetricRegistryProducer, DiracContext,
               HistogramSnapshot, TimerSnapshot

dirac-core           io.vidocq.dirac.core
  exports io.vidocq.dirac.core to io.vidocq.dirac.cdi.vauban, io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires microprofile.metrics.api
  → Implementations: CounterImpl, GaugeImpl, HistogramImpl, TimerImpl,
                     MetricRegistryImpl, OpenMetricsFormatter, JsonMetricsFormatter,
                     BaseMetricsRegistrar

dirac-cdi-vauban     io.vidocq.dirac.cdi.vauban
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.enterprise.cdi
  requires jakarta.interceptor
  requires io.vidocq.vauban.api
  → Implementations: CountedInterceptor, TimedInterceptor, DiracExtension (BCE),
                     MetricRegistryProducerBean, DiracAutoDiscovery

dirac-rest           io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.ws.rs
  → Implementations: MetricsResource, ContentNegotiationFilter

dirac-bench          io.vidocq.dirac.bench
  → JMH benchmarks vs Micrometer, SmallRye Metrics

dirac-tck            (outside reactor — Model 4.0.0)
  → TestNG + Arquillian + Vauban embedded + Chappe, official MP Metrics 5.1.1 TCK runner

dirac-examples       io.vidocq.dirac.examples
  → Standalone and vidocq-mps examples
```

## Phases

### M0 — Bootstrap

- [x] `.sdkmanrc` (`java=25-tem`, `maven=3.9.16`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile)
- [x] `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md` (these files)
- [x] Creation of submodules with skeleton `pom.xml` + `module-info.java`:
      `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, `dirac-rest`,
      `dirac-bench`, `dirac-examples`, `dirac-tck` (outside reactor)
- [x] `LICENSE` (Apache 2.0)
- [x] `README.md`
- [x] `run-official-tck-mp-metrics-5.1.sh` (root TCK script)
- [x] Validation `./mvnw -ntp install -DskipTests` succeeds on the reactor
- [x] Validation `mvn -f dirac-tck/pom.xml -DskipTests compile` succeeds (outside reactor)

**M0 note:** `dirac-rest/module-info.java` is intentionally deferred to M7 (optional module
with no content in M0 — compiler args override incompatible with JPMS without sources).

**M0 deliverable ✅:** compilable reactor (7/7 BUILD SUCCESS), coherent skeleton `module-info.java`
on `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, non-reactor TCK compilable.

---

### M1 — MetricRegistry + Counter

**Scope spec:** §2 (MetricRegistry), §3.1 (Counter), §4.1 (@Counted).

| Task | Notes | State |
|---|---|---|
| `MetricID` record | Use the `org.eclipse.microprofile.metrics.MetricID` API type for immutable `(name, tags)` | ☑ |
| `Tag` record | Use the `org.eclipse.microprofile.metrics.Tag` API type with native validation | ☑ |
| `MetricRegistryImpl` | `ConcurrentHashMap<MetricID, Metric>`; methods `counter()`, `gauge()`, `histogram()`, `timer()`, `register()`, `remove()`, `getMetrics()` | ☑ |
| `CounterImpl` | `LongAdder`; `inc()`, `inc(long)`, `getCount()` | ☑ |
| `MetricRegistryProducerBean` CDI | Produces `MetricRegistry` for `APPLICATION`, `BASE`, `VENDOR` scopes with `@RegistryScope` qualifier | ☑ |
| `DiracExtension` BCE — skeleton | Implements `BuildCompatibleExtension` with no logic | ☑ |
| `CountedInterceptor` | Priority `4020`; resolves cached `MetricID` (BeanClass, Method) → increments APPLICATION `Counter` | ☑ |
| `@Counted` — `absolute`, `tags`, `description`, `unit`, `scope` fields | Reads annotation attributes to build `MetricID` and `Metadata` | ☑ |
| `CounterImpl` unit tests | `inc()`, `inc(long)`, concurrency under 200 virtual threads | ☑ |
| `MetricRegistryImpl` unit tests | Registration, lookup, removal, uniqueness by `MetricID` | ☑ |
| CDI integration tests | `@Counted` on a simple method with embedded Vauban; APPLICATION registry verification | ☑ |

**M1 decisions:**
- `MetricID` uses `TreeMap<String, String>` for tags (stable order for OpenMetrics format).
- The `(BeanClass, Method) → MetricID` cache is built on the first intercepted call
  (not at BCE startup, because the metric name may contain the canonical class name).
- A `Counter` registered twice with the same `MetricID` returns the same instance
  (get-or-create semantics).

**M1 deliverable ✅:** functional `@Counted`, APPLICATION registry injectable via CDI. Unit + CDI integration tests green.

---

### M2 — Gauge

**Scope spec:** §3.2 (Gauge), §4.2 (@Gauge).

| Task | Notes | State |
|---|---|---|
| `GaugeImpl<T>` | Stores a `MethodHandle` resolved at startup; `getValue()` invokes the handle | ☑ |
| `DiracExtension` BCE — `@Gauge` resolution | Scans methods annotated `@Gauge`; builds and caches `MethodHandle`s; validates signature (no parameter, non-void return type) | ☑ |
| `@Gauge` — automatic registration | The BCE registers each `@Gauge` method in the scope-targeted registry at container startup | ☑ |
| Negative validation | `@Gauge` on a method with parameters or `void` return → validation failure at startup | ☑ |
| `GaugeImpl` unit tests | Simple read, update through the underlying method | ☑ |
| CDI integration tests | `@Gauge` on a method returning a business value; verification via `MetricRegistry.getGauges()` | ☑ |

**M2 decisions:**
- `GaugeImpl` does not store the value — it invokes the `MethodHandle` on every
  `getValue()` call. That is the expected semantics: a gauge is an instantaneous read.
- `MethodHandle` is resolved in the BCE with `MethodHandles.privateLookupIn(beanClass, lookup)`
  to access `protected` or package-private methods if necessary.

**M2 deliverable ✅:** `@Gauge` automatically registered at startup. Tests green.

---

### M3 — Histogram

**Scope spec:** §3.3 (Histogram).

| Task | Notes | State |
|---|---|---|
| `HistogramSnapshot` record | `count`, `sum`, `min`, `max`, `mean`, percentiles (p50, p75, p95, p98, p99, p999) | ☑ |
| `HistogramImpl` | Sample reservoir with exponential decay (or in-house implementation); thread-safe via `AtomicLongArray`; exposes `HistogramSnapshot` | ☑ |
| `HistogramImpl` unit tests | Distribution, percentiles, concurrency | ☑ |

**M3 decisions:**
- The reservoir uses an exponential decay algorithm with a 5-minute window
  (Prometheus default parameters: `alpha=0.015`, `size=1028`).
- Thread-safety via `AtomicLong[]` + CAS without `synchronized`.
- The currently consumed `microprofile-metrics-api:5.1.1` does not expose a
  `@Histogram` annotation in `org.eclipse.microprofile.metrics.annotation`; M3 therefore covers
  only the core-side `Histogram` implementation.

**M3 deliverable ✅:** Histogram functional on the core side (`HistogramImpl` + snapshot + registry + tests).

---

### M4 — Timer + JMH baseline benchmarks

**Scope spec:** §3.4 (Timer), §4.4 (@Timed).

| Task | Notes | State |
|---|---|---|
| `TimerSnapshot` record | Extends `HistogramSnapshot` with `elapsedTime` (total duration) | ☑ |
| `TimerImpl` | `System.nanoTime()` delta → delegates to `HistogramImpl`; exposes `TimerSnapshot` | ☑ |
| CDI `TimedInterceptor` | Priority `4021`; starts `nanoTime()` before `ctx.proceed()`, records delta after | ☑ |
| `@Timed` — `absolute`, `tags`, `description` attributes | Same pattern as `@Counted` | ☑ |
| JMH benchmarks — baseline | Interception overhead: baseline CDI method vs `@Counted` vs `@Timed` (`M4TimerBenchmarks`) | ☑ |
| JMH load under 1000 virtual threads | `Counter.increment()` throughput and `Timer.update(Duration)` with `Executors.newVirtualThreadPerTaskExecutor()` | ☑ |
| `TimerImpl` unit tests | Duration measurement, concurrency, sub-millisecond | ☑ |
| CDI integration tests | `@Timed` on a slow method; `TimerSnapshot.mean()` verification | ☑ |

**M4 decisions:**
- `TimerImpl` uses `System.nanoTime()` only — never `currentTimeMillis()`.
- `TimedInterceptor` overhead must remain < 1 µs p99 on a warmed JVM (performance target).
- M4 benchmarks implemented in `dirac-bench/src/main/java/io/vidocq/dirac/bench/M4TimerBenchmarks.java`.

**M4 deliverable ✅:** operational `@Timed` (core + CDI) and JMH baseline available in `dirac-bench`.

---

### M5 — BASE (JVM) and VENDOR metrics

**Scope spec:** §3.3 (Base Metrics), §3.4 (Vendor Metrics).

| Task | Notes | State |
|---|---|---|
| `BaseMetricsRegistrar` | Registers mandatory JVM metrics in the BASE registry at startup | ☑ |
| GC metrics | `gc.time`, `gc.total` via `ManagementFactory.getGarbageCollectorMXBeans()` (aggregate gauge exposure of MXBean counters) | ☑ |
| Thread metrics | `thread.count` (Gauge), `thread.daemon.count` (Gauge), `thread.max.count` (Gauge) via `ThreadMXBean` | ☑ |
| Heap metrics | `memory.usedHeap` (Gauge), `memory.committedHeap` (Gauge), `memory.maxHeap` (Gauge) via `MemoryMXBean` | ☑ |
| Uptime metrics | `jvm.uptime` (Gauge, ms) via `RuntimeMXBean` | ☑ |
| Class loading metrics | `classloader.loadedClasses` (Gauge), `classloader.unloadedClasses` (Gauge) | ☑ |
| CPU metrics | `cpu.availableProcessors` (Gauge), `cpu.systemLoadAverage` (Gauge) via `OperatingSystemMXBean` | ☑ |
| Startup registration | `MetricRegistryProducerBean` calls `BaseMetricsRegistrar.register(MetricRegistry base)` on initialization | ☑ |
| Unit tests | Verification of the expected BASE metrics registration (`BaseMetricsRegistrarTest`) | ☑ |
| Integration tests | Non-empty BASE registry after embedded Vauban startup (`BaseMetricsCdiIntegrationTest`) | ☑ |

**M5 decisions:**
- JVM values from MXBeans are exposed as instantaneous reads via `Gauge` to keep live state without a scheduler.
- The BASE registry is populated in `MetricRegistryProducerBean` during application initialization.

**M5 deliverable ✅:** BASE registry populated at startup with main JVM metrics and core + CDI test coverage.

---

### M6 — OpenMetrics / Prometheus format

**Scope spec:** §3.0 (metric exposition — Prometheus text format).

| Task | Notes | State |
|---|---|---|
| `OpenMetricsFormatter` | Serializes `MetricRegistry` in Prometheus text format (`text/plain;version=0.0.4`) | ☑ |
| `# HELP` and `# TYPE` format | Generated from `Metadata.description()` and the metric type | ☑ |
| Metric line format | `metric_name{tag1="v1",tag2="v2"} value [timestamp]` | ☑ |
| Prometheus suffixes by type | Counter: `_total`; Histogram: `_bucket`, `_count`, `_sum`; Timer: `_seconds_*` (nanos→seconds conversion) | ☑ |
| Name canonicalization | `.` → `_`; non-alphanumeric characters → `_` (spec §3.1) | ☑ |
| `JsonMetricsFormatter` | Serializes to JSON (MP Metrics spec §3.2) — without any third-party JSON library (Champollion) | ☑ |
| `OpenMetricsFormatter` unit tests | Exact output for Counter, Gauge, Histogram, Timer with and without tags | ☑ |
| `JsonMetricsFormatter` unit tests | Spec-compliant JSON structure for each type | ☑ |

**M6 decisions:**
- `OpenMetricsFormatter` builds text with `StringBuilder` — no template engine dependency.
- Timer format is currently exposed in `_seconds` series (`quantile`, `_count`, `_sum`) with nanos→seconds conversion.
- `JsonMetricsFormatter`: keys in `metricName[;tagKey=tagValue]*` format (sorted tags). Counter/Gauge → JSON scalar. Histogram → `{count, sum, p50…p999}` object. Timer → `{count, elapsedTime, p50…p999}` object (seconds). Implemented with `StringBuilder` — no dependency.

**Deliverable:** OpenMetrics and JSON implemented and tested (39 green `dirac-core` tests). ✅

---

### M7 — REST `/metrics` endpoint (dirac-rest + Cassini)

**Scope spec:** §2.3 (REST API).

| Task | Notes | State |
|---|---|---|
| JAX-RS `MetricsResource` | `@Path("/metrics")`, `@GET` → returns all scopes | ☑ |
| `GET /metrics/{scope}` | Scope = `application`, `base`, `vendor`; 404 if scope is unknown | ☑ |
| `GET /metrics/{scope}/{name}` | Individual metric; 404 if not found | ☑ |
| Content negotiation | `Accept: text/plain` → OpenMetrics; `Accept: application/json` → JSON; default → text/plain | ☑ |
| JAX-RS `ContentNegotiationFilter` | `@Provider @PreMatching` filter — null/empty/`*/*` → `text/plain` | ☑ |
| Cassini integration | `MetricsResource` is an `@ApplicationScoped` bean — automatically discovered by Cassini through CDI | ☑ |
| REST integration tests | Covered by the official M8 TCK (Arquillian deploy + Chappe); direct unit tests cover formatting logic | ☑ |

**M7 decisions:**
- `dirac-rest` depends on `jakarta.ws.rs-api` as `provided` — Cassini supplies the implementation.
- Formatting logic is extracted into package-visible methods (`formatAll`, `formatScope`, `formatMetric`)
  tested directly without a JAX-RS `RuntimeDelegate`.
- `@ApplicationScoped` on `MetricsResource` → Cassini discovers it as a CDI bean without additional code.
- HTTP error codes follow the spec: 200 OK, 404 Not Found if scope/metric are absent.
- JSON `GET /metrics` format: root object with `application`, `base`, `vendor` keys.
- `OpenMetricsFormatter` and `JsonMetricsFormatter` each have an overload accepting `Map<MetricID, Metric>`
  for name filtering (`GET /metrics/{scope}/{name}`).
- `MetricsEndpoint` placeholder (M0) replaced and removed.
- HTTP integration tests (embedded Chappe) are deferred to M8 — the TCK tests this endpoint end to end.

**Deliverable ✅:** `MetricsResource` + `ContentNegotiationFilter` implemented and tested (21 green `dirac-rest` tests).

---

### M8 — Official MicroProfile Metrics 5.1.1 TCK

**Scope:** full `microprofile-metrics-tck:5.1.1` suite.

| Task | Notes | State |
|---|---|---|
| `dirac-tck/pom.xml` (Model 4.0.0) | Dependencies: TCK, Arquillian, embedded Vauban, Chappe; **outside reactor** | ☑ |
| `DiracDeployableContainer` | Arquillian Local `DeployableContainer` starting embedded Vauban + Dirac + Chappe | ☑ |
| `VaubanDiracTckBootstrap` | Archive class extraction, Vauban startup (DiracExtension + interceptors + producers), RequestContext activation | ☑ |
| `DiracTestEnricher` | `@Inject` injection on TCK test classes via Vauban `BeanManager` | ☑ |
| `DiracArquillianExtension` + `arquillian.xml` | Container discovery by Arquillian | ☑ |
| `tck-suite.xml` | Selection of MP Metrics 5.1.1 TCK packages | ☑ |
| `run-official-tck-mp-metrics-5.1.sh` | Root script: install reactor → invoke TCK + generate `tck-report.txt` | ☑ |
| Smoke TCK pass | `DiracTckSmokeTest` 1/1 PASS | ☑ |
| Full TCK pass | **127/127 PASS** on the official `tck-suite.xml` | ☑ |
| `TCK.md` | Documentation of challenges and excluded tests | ☐ |
| `dirac-tck/README.md` | TCK installation procedure + runner architecture | ☐ |

**M8 decisions:**
- The Arquillian container starts Vauban (CDI), registers test beans, exposes
  the `/metrics` endpoint via Chappe (dynamic port).
- `mp.metrics.appName` is exposed in the script for scoping tests.
- Vauban `RequestContext` is activated when each TCK archive is deployed.
- Strict `aroundInvoke` contract required by the TCK: on a metric removed from the registry,
  `CountedInterceptor`/`TimedInterceptor` throw `IllegalStateException`
  (TCK tests `removeCounterFromRegistry` / `removeTimerFromRegistry` require this behavior).
  Dirac CDI unit and integration tests therefore pre-register metrics just like production `@AroundConstruct` does.

**M8 deliverable ✅:** official MicroProfile Metrics 5.1.1 TCK passed at 127/127 (0 failures, 0 errors, 0 skipped). Reproducible report via `./run-official-tck-mp-metrics-5.1.sh all` (see `dirac-tck/target/tck-report.txt`). Remaining work: produce `TCK.md` and `dirac-tck/README.md`.

---

### M9 — Full JPMS compatibility + jlink

**Scope:** produce a custom runtime image via `jlink` for a strictly modular Dirac subset
(no automatic module in the final graph).

| Task | Notes | State |
|---|---|---|
| Define the `jlink` scope | Minimum target: `dirac-api` + `dirac-core`; extended target: + `dirac-cdi-vauban` | ☐ |
| Remove the MP Metrics blocker | Reactor artifact `dirac-mp-metrics-api` (repackage MP Metrics + `module-info.class`) used by the `jlink` smoke test | ☑ |
| Option A — internal bridge module | Introduce an explicitly modular bridge that avoids the automatic module in the image | ☐ |
| Option A (local prototype M9.2) | POC validated: local modularization of `microprofile-metrics-api` via `jdeps`/`javac`/`jar` + generated `jlink` image (`run-jlink-smoke-m92.sh`) | ☑ |
| Option B — modular API artifact | Industrialized through the reactor module `dirac-mp-metrics-api` | ☑ |
| Module-path inventory | Document explicit/automatic modules (Dirac + Jakarta + MP) | ☑ |
| Missing `module-info` | `dirac-rest`, `dirac-examples` and `dirac-bench` are modularized (JPMS workaround) | ☑ |
| `jlink-smoke` Maven profile | Parent profile `-Pjlink-smoke` added (blocking: fail if the `jlink` smoke test fails) | ☑ |
| `run-jlink-smoke.sh` script | Reproducible M9 smoke check (JPMS OK + `jlink` blocker detection) | ☑ |
| Image smoke test | `target/dirac-image-smoke` image generated and validated (`--list-modules`) | ☑ |
| `jlink` CI gate | Add a blocking job (`jlink-smoke`) | ☐ |
| Exploitation docs | Add `JLINK.md` and link it from `README.md` | ☑ |

**M9 decisions (proposed):**
- Prioritize a `jlink` image for `dirac-api`/`dirac-core` before the CDI variant.
- Reject any automatic module in the final `jlink` graph.
- Keep `dirac-rest` optional and outside the minimum target.

**Technical validation (M9 target):**

```bash
# Check module status (explicit vs automatic)
jar --describe-module --file dirac-api/target/dirac-api-0.1.0-SNAPSHOT.jar
jar --describe-module --file dirac-core/target/dirac-core-0.1.0-SNAPSHOT.jar

# Check JPMS resolution
java --module-path "<module-path>" --validate-modules

# Build the runtime image (minimum target)
jlink --module-path "$JAVA_HOME/jmods:<module-path>" \
  --add-modules io.vidocq.dirac.api,io.vidocq.dirac.core \
  --output target/dirac-image

# Smoke run
target/dirac-image/bin/java --list-modules
```

**M9 acceptance criteria:**
- `jlink` completes without error on the minimum target.
- The image starts and runs a simple smoke test.
- The final module graph contains no automatic module.
- The `jlink-smoke` CI job is green.

---

## Known risks

| Risk | Impact | Mitigation |
|---|---|---|
| Non-public TCK artifact | Blocked if the artifact is not in the local M2 | Documentation in `dirac-tck/README.md`; installation script |
| HDR reservoir vs in-house implementation | Percentile accuracy, HDR license | Implement an in-house EWMA reservoir first; HDR if benchmarks show a gap |
| REST endpoint TCK | The TCK tests the HTTP endpoint — Chappe + Cassini must be operational | Test the endpoint from M7 before the TCK |
| `@Gauge` and generic types | `MethodHandle` on a generic method may require a cast | Test with `Gauge<Long>`, `Gauge<Integer>`, `Gauge<Double>` from M2 |
| Metric scoping | `APPLICATION` scope must be reset between Arquillian deployments | Clean the registry in the container `undeploy()` |
| Strict OpenMetrics format | The TCK checks the exact format (spacing, suffixes) | Implement character-by-character format tests |
| Registry concurrency | Concurrent registration of the same `MetricID` | `computeIfAbsent` in `MetricRegistryImpl` — validate with 500 virtual threads |

## Confirmed decisions

- [x] Separation of `dirac-core` (pure implementations) / `dirac-cdi-vauban` (CDI interceptors)
- [x] `LongAdder` for `Counter` (high concurrency without contention)
- [x] `MethodHandle` for `@Gauge` (startup resolution, no runtime reflection)
- [x] `System.nanoTime()` for `Timer` (never `currentTimeMillis()`)
- [x] In-house EWMA reservoir for `Histogram` (avoids HDR Histogram as a third-party dependency)
- [x] OpenMetrics format prioritized over JSON (text/plain is the default format)
- [x] `dirac-rest` is optional (separate module, no dependency from `dirac-core`)
- [x] `dirac-tck` outside the reactor (common constraint across the Vidocq ecosystem — ShrinkWrap)

## Open decisions

- **HDR Histogram reservoir**: use the in-house EWMA algorithm or HdrHistogram (dependency)?
  Decision to be taken after the first M4 benchmarks.
- **OpenMetrics timestamps**: include them by default or make them optional via MP Config?
  The MP Metrics 5.1 spec does not require them.
- **`mp.metrics.appName`**: optional prefix for the APPLICATION scope — implement from M1
  or defer to the TCK?
- **`vidocq-mps` integration**: define the Dirac MPS extension after the TCK is green.
- **GraalVM native-image**: `@Gauge` uses `MethodHandle` at startup — AOT compatible
  only if the `MethodHandle` is resolved at compile time (via `classfile-codegen`).
  Evaluate whether a static generation phase is needed.
