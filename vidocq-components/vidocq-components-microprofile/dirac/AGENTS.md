# AGENTS.md

> This file is the contribution guide for AI agents (GitHub Copilot, Copilot Chat,
> Copilot Workspace). It must remain in sync with `CLAUDE.md` — any modification in
> one must be reflected in the other.

## Repository Mission

- Dirac implements **MicroProfile Metrics 5.1.1** in Java 25 with **zero third-party
  implementation libraries**: only the spec APIs (`microprofile-metrics-api`,
  `jakarta.enterprise.cdi-api`, `jakarta.interceptor-api`, `jakarta.annotation-api`) are
  compiled in `dirac-core` and `dirac-cdi-vauban`.
- Strict Java Modules architecture: `dirac-api` wraps the spec, `dirac-core` pure Java 25 implementations
  without CDI, `dirac-cdi-vauban` CDI interceptors + BCE Vauban,
  `dirac-rest` optional JAX-RS endpoint, `dirac-tck` in-reactor behind the `tck` Maven profile.
- **No Micrometer, Dropwizard Metrics, SmallRye Metrics** in production code.
- Virtual threads (Project Loom) for concurrent benchmarks and load tests —
  `Executors.newVirtualThreadPerTaskExecutor()`. Never a platform pool.
- Use `ROADMAP.md` to track milestone progress (M0..M8).
- If the rules in this file need updating, align `CLAUDE.md` in the same
  operation — both files are mirrors intended for different tools.

## Actual Code State to Know Before Modifying

- See `ROADMAP.md` for the detailed status of each milestone (M0..M8).
- Milestones marked ✅ are complete; those marked 🚧 are in progress.
- **Current actual state: M1 through M7 complete.** The repository contains a functional core
  for `Counter`, `MetricRegistry`, `Gauge`, `Histogram` (core), `Timer`, associated CDI components,
  OpenMetrics and JSON formatters (`OpenMetricsFormatter`, `JsonMetricsFormatter`), and the REST endpoint
  `MetricsResource` with `ContentNegotiationFilter`. M8 (official TCK) is the next step.
- Actual placeholders to complete before creating parallel classes:
  - `dirac-api/src/main/java/io/vidocq/dirac/api/DiracException.java`
  (Note: `MetricsEndpoint.java` replaced by `MetricsResource.java` in M7.)
- Any implementation description below (`CounterImpl`, `TimerImpl`, etc.) remains the
  **target** as long as the corresponding concrete file doesn't yet exist in `src/main/java`.
- Target Java modules:
  - `io.vidocq.dirac.api` (`dirac-api`)
  - `io.vidocq.dirac.core` (`dirac-core`)
  - `io.vidocq.dirac.cdi.vauban` (`dirac-cdi-vauban`)
  - `io.vidocq.dirac.rest` (`dirac-rest`, optional)
  - `io.vidocq.dirac.bench` (`dirac-bench`)
  - `io.vidocq.dirac.examples` (`dirac-examples`)
  - `io.vidocq.dirac.tck` (`dirac-tck`, in-reactor behind the `tck` Maven profile)

## MicroProfile Metrics 5.1.1 Metric Types

MicroProfile Metrics 5.x simplified the spec compared to 4.x:

- **Removed in 5.0**: `Meter`, `@Metered`, `ConcurrentGauge`, `@ConcurrentGauge`,
  `SimpleTimer`, `@SimplyTimed` — **do not implement these**.
- **Present in 5.1.1 (metrics API)**:
  - `Counter` / `@Counted` — monotonic incremental counter (LongAdder)
  - `Gauge<T>` / `@Gauge` — instantaneous value exposed via an annotated method
  - `Histogram` — value distribution (percentiles)
  - `Timer` / `@Timed` — call duration (combines histogram + counter)
- **Concrete API note**: the `microprofile-metrics-api:5.1.1` JAR in this repository does not
  expose the annotation `org.eclipse.microprofile.metrics.annotation.Histogram`.
- **Scopes**: `APPLICATION` (default, injectable), `BASE` (JVM metrics), `VENDOR`
- **MetricID**: `(String name, SortedMap<String, String> tags)` — immutable value
- **Tag**: `(String name, String value)` — immutable pair

## Target Module Architecture

```
dirac-api            io.vidocq.dirac.api
  exports io.vidocq.dirac.api
  requires microprofile.metrics.api
  → SPI: MetricRegistryProducer, DiracContext, HistogramSnapshot, TimerSnapshot

dirac-core           io.vidocq.dirac.core
  exports io.vidocq.dirac.core (for cdi-vauban only — qualified export)
  requires io.vidocq.dirac.api
  requires microprofile.metrics.api
  → Implementations: CounterImpl (LongAdder), GaugeImpl (MethodHandle),
                      HistogramImpl (HDR reservoir), TimerImpl (nanoTime + HistogramImpl),
                      MetricRegistryImpl (ConcurrentHashMap<MetricID, Metric>),
                      OpenMetricsFormatter (Prometheus text format),
                      BaseMetricsRegistrar (JVM metrics: GC, threads, heap, uptime)

dirac-cdi-vauban     io.vidocq.dirac.cdi.vauban
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.enterprise.cdi
  requires jakarta.interceptor
  requires io.vidocq.vauban.api
  → Implementations: CountedInterceptor (@Interceptor @Counted),
                      TimedInterceptor (@Interceptor @Timed),
                      DiracExtension (BCE CDI 4.1, resolves @Gauge at startup),
                      MetricRegistryProducerBean (@ApplicationScoped, 3 scopes),
                      DiracAutoDiscovery (ServiceLoader)

dirac-rest           io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.ws.rs
  → Implementations: MetricsResource (GET /metrics, GET /metrics/{scope},
                      GET /metrics/{scope}/{name}), ContentNegotiationFilter

dirac-bench          io.vidocq.dirac.bench
  → JMH benchmarks vs Micrometer, SmallRye Metrics

dirac-tck            (in-reactor, gated by the `tck` Maven profile)
  → TestNG + Arquillian + embedded Vauban + Chappe, official MP Metrics 5.1.1 TCK runner

dirac-examples       io.vidocq.dirac.examples
  → Standalone and vidocq-mps examples
```

- **Current actual state**: `dirac-api` exposes `DiracException`; `dirac-core` already contains
  `CounterImpl`, `MetricRegistryImpl`, `GaugeImpl`, `HistogramImpl`, `TimerImpl`, and `BaseMetricsRegistrar`;
  `dirac-cdi-vauban` contains `DiracExtension` (validation/resolution of `@Gauge`), `MetricRegistryProducerBean`,
  `CountedInterceptor`, `GaugeRegistrationBean`, and `TimedInterceptor`; `dirac-rest` still contains the placeholder
  `MetricsEndpoint`.
- The packages actually present on the production side are `io.vidocq.dirac.api`,
  `io.vidocq.dirac.internal`, `io.vidocq.dirac.cdi.internal`, and `io.vidocq.dirac.rest`.

## Boundaries Not to Break

- Never put `dirac-tck` back in the reactor: intentionally excluded due to
  ShrinkWrap Maven Resolver / Model 4.0.0 vs 4.1.0 incompatibility (common constraint
  across the entire Vidocq ecosystem).
- `dirac-core` must not import **any** CDI class (`jakarta.enterprise.*`,
  `jakarta.inject.*`) — only `microprofile-metrics-api`.
- **No `synchronized`** — use `LongAdder`, `AtomicLong`, `AtomicReference`,
  `ConcurrentHashMap`. `synchronized` pins virtual threads.
- **No `ThreadLocal`** — use `ScopedValue` (JEP 506) to propagate context
  if needed in interceptors.
- **No `java.lang.reflect.Proxy`** — `@Gauge` resolution via
  `MethodHandles.lookup().findVirtual(...)` at startup (BCE `DiracExtension`).
- **No `setAccessible(true)`** in production — open packages in the
  `module-info.java` and document why.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) for `dirac-core` and
  `dirac-cdi-vauban` tests. The TCK uses **TestNG** (upstream constraint).
- Any `<scope>compile|runtime</scope>` dependency addition requires passing through the
  `dependency-gatekeeper` agent and an explicit justification in the PR.
- **`dirac-rest` is optional** — its presence must never be required by `dirac-core`
  or `dirac-cdi-vauban` (inverted dependency or SPI).
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## Java Modules Convention — `module-info` + `target/javamodules/` Workaround

- In `dirac-core` and `dirac-cdi-vauban`, the `module-info.java` lives under
  `src/main/module-info/` (and **not** `src/main/java/`). This is intentional: prevents
  Maven Compiler Plugin from switching to Java Modules mode during `testCompile`. The `module-info.class`
  is compiled alone in the `prepare-package` phase. Same constraint as in Heisenberg.
- `dirac-api` currently keeps its `module-info.java` under `src/main/java/`.
- `dirac-rest`, `dirac-bench`, and `dirac-examples` don't yet have a `module-info.java` in
  the current state; their `pom.xml` neutralize inherited parent `compilerArgs` via
  `combine.self="override"` to avoid an invalid `--module-path`.
- `microprofile-metrics-api:5.1.1`: verify the presence or absence of `Automatic-Module-Name`
  in the MANIFEST.MF before declaring the `requires`. If absent, the Java Modules name is derived
  from the artifact (`microprofile.metrics.api`).
- Tests run on the classpath (`useModulePath=false`); Java Modules wiring is validated
  by the smoke TCK.

## Useful Workflows

```bash
# Initialize SDK environment
sdk env

# Full reactor build
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test
./run-official-tck-mp-metrics-5.1.sh

# TCK — full suite
./run-official-tck-mp-metrics-5.1.sh all

# TCK — targeted test (e.g. CounterTest)
./run-official-tck-mp-metrics-5.1.sh -Dtest=CounterTest

# Local install of only modules consumed by the TCK runner
./mvnw -ntp -pl dirac-api,dirac-core,dirac-cdi-vauban -am install -DskipTests

# Summary report generated by TCK script
cat dirac-tck/target/tck-report.txt

# JMH benchmarks
./mvnw -ntp -pl dirac-bench package
java -jar dirac-bench/target/benchmarks.jar
```

- The TCK always goes through the root script which first installs the reactor, then invokes
  `mvn -f dirac-tck/pom.xml -P<profile> test`.
- The TCK script writes a summary in `dirac-tck/target/tck-report.txt`.
- The Arquillian configuration currently present is `dirac-tck/src/test/resources/arquillian.xml`.

## Observed Contribution Conventions

- **Strict TDD**: Red → Green → Refactor. No production line without prior test.
  Cite the MicroProfile Metrics 5.1.1 spec section in test comments.
- Unit tests in the same package as the tested class, named `<Class>Test`.
- No Mockito — manual doubles (`FakeMetricRegistry`, `FakeInvocationContext`, etc.).
- Each metric type's logic (`CounterImpl`, `TimerImpl`, etc.) is unit-tested
  without a CDI container — that is the purpose of the `dirac-core` /
  `dirac-cdi-vauban` separation.
- M0 placeholders already carry the expected extension point in their Javadoc; complete
  `DiracExtension` or `MetricsEndpoint` first before introducing a functional duplicate elsewhere.
- `BENCH.md`, `BUG.md`, `TCK.md`, and `dirac-tck/README.md` do not yet exist in this
  repository; do not use them as references or modification targets until they are created.
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## What an Agent Should Assume for Upcoming Tasks

- As of now, do not assume `MetricsResource` or `ContentNegotiationFilter` already exist:
  most items below still describe the M5+ target.
- `dirac-core` is the foundation:
  - `CounterImpl`: increments via `LongAdder.increment()` / `add(long)`, reads via `sum()`
  - `GaugeImpl<T>`: stores a `MethodHandle` resolved at startup, reads via `invoke()`
  - `HistogramImpl`: sample reservoir (HDR Histogram or custom implementation),
    exposes `HistogramSnapshot` (count, sum, min, max, percentiles)
  - `TimerImpl`: `System.nanoTime()` delta → delegates to `HistogramImpl`
  - `MetricRegistryImpl`: `ConcurrentHashMap<MetricID, Metric>` per scope, thread-safe
  - `OpenMetricsFormatter`: serializes to Prometheus text format (`# HELP`, `# TYPE`,
    `metric{tags} value timestamp` lines)
  - `BaseMetricsRegistrar`: registers mandatory JVM metrics (spec §3.3):
    GC (`gc.time`, `gc.total`), threads, heap, uptime, class loading
- `dirac-cdi-vauban` is the CDI entry point:
  - `CountedInterceptor` (priority `4020`, `@Counted`)
  - `TimedInterceptor` (priority `4021`, `@Timed`)
  - `DiracExtension` (BCE CDI 4.1 `BuildCompatibleExtension`): resolves `@Gauge` at
    startup, builds cached `MethodHandle`s, validates signatures
  - `MetricRegistryProducerBean`: produces the three registries (`APPLICATION`, `BASE`,
    `VENDOR`) as `@ApplicationScoped` beans with `@RegistryScope` qualifier
- `dirac-rest` must not be required by `dirac-core`:
  - `MetricsResource`: `@Path("/metrics")`, `@GET`, content negotiation
    (`text/plain` OpenMetrics and `application/json` per spec §3.0)
  - Delegates to `OpenMetricsFormatter` (core) or `JsonMetricsFormatter` (core)
- Before any structural modification to `MetricRegistryImpl` or interceptors,
  reason against the final contract: **MicroProfile Metrics 5.1.1 TCK at 100% PASS**.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
