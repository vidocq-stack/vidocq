# AGENTS.md

> This file is the contribution guide for AI agents (GitHub Copilot, Copilot Chat,
> Copilot Workspace). It must remain synchronized with `CLAUDE.md` — any change in
> one must be reflected in the other.

## Repository mission

- Heisenberg implements **MicroProfile Fault Tolerance 4.1** in Java 25, with **zero third-party implementation libraries**: only the spec APIs (`microprofile-fault-tolerance-api`, `jakarta.enterprise.cdi-api`, `jakarta.interceptor-api`, `microprofile-config-api`) are compiled into `heisenberg-core` and `heisenberg-cdi-vauban`.
- Strict Java Modules architecture: `heisenberg-api` wraps the spec, `heisenberg-core` contains pure Java 25 engines without CDI, `heisenberg-cdi-vauban` contains the CDI interceptor + Vauban BCE, `heisenberg-tck` is in-reactor behind the `tck` Maven profile.
- **No SmallRye Fault Tolerance, Hystrix, or Resilience4j** in production code.
- Virtual threads (Project Loom) for `@Asynchronous` and `@Timeout` — `Thread.ofVirtual() + join(Duration)` (Java 21+, finalized). `StructuredTaskScope` (JEP 505) has been available since Java 25 but is not currently used to maximize compatibility with Java 21+.
- Use `ROADMAP.md` to track milestone progress (M0..M9).
- If this file rules must be updated, align `CLAUDE.md` in the same operation — both files are mirrors intended for different tools.

## Actual code state to know before modifying

- Consult `ROADMAP.md` for the detailed state of each milestone (M0..M9).
- **Current state: M0–M9 completed, official TCK at 100% PASS.**
  Latest reproducible `all` run (2026-05-28T09:05:59Z, via
  `run-official-tck-mp-fault-tolerance-4.1.sh all`) :
  `463 run / 463 PASS / 0 fail / 0 errors / 0 skip = 100 % PASS`
  (reproduces the 2026-05-24 result recorded in `ROADMAP.md` ; supersedes
  the ~82% 2026-05-16 run from `TCK.md`).
  **§9 MP Metrics (Dirac): 100% PASS. §10 OpenTelemetry (Humboldt): 100% PASS.**
- All engines exist and are wired in `heisenberg-core`:
  `RetryEngine`, `TimeoutEngine`, `CircuitBreakerEngine` (`CircuitBreakerState`
  CLOSED/OPEN/HALF_OPEN), `BulkheadEngine`, `BulkheadStateRegistry`,
  `CircuitBreakerStateRegistry`, `FallbackResolver`, `FallbackPolicy`,
  `PolicyComposer`, `AsynchronousEngine`, `AnnotationReader`, `ConfigResolver`,
  immutable configs (`RetryConfig`, `TimeoutConfig`, `CircuitBreakerConfig`,
  `BulkheadConfig`, `FallbackConfig`).
- In `heisenberg-cdi-vauban` (interceptor + BCE):
  `FaultToleranceInterceptor` (priority 4010), `FaultTolerancePriority3850Interceptor`
  (TCK variant for priority 3850), `FaultToleranceBinding` (marker),
  `HeisenbergExtension` (CDI 4.1 BCE — validations + interceptor binding; static work a build
  may freeze), `InterceptorPriorityExtension` (CDI 4.1 BCE — `@Priority` rewriting from
  `mp.fault.tolerance.interceptor.priority`; does nothing when `ExtensionPhase.isBuildTime()`,
  runs when the container starts — heisenberg#66),
  `StateRegistryBean` + `BulkheadStateRegistryBean` (`@ApplicationScoped`),
  `HeisenbergAutoDiscovery` (ServiceLoader bridge to Ravel),
  **§9 / §10 metrics recorders** (`DiracFtMetricsRecorder` + `OtelFtMetricsRecorder`,
  fan-out via `CompositeFtMetricsRecorder` and `MetricsRecorderResolver`).
- Effective Java modules: `io.vidocq.heisenberg.api`, `io.vidocq.heisenberg.core`,
  `io.vidocq.heisenberg.cdi.vauban` (note: suffix `.vauban`, not just `.cdi`).
- `heisenberg-core` exports its internal package **as a qualified export**:
  `exports io.vidocq.heisenberg.internal to io.vidocq.heisenberg.cdi.vauban;` — any
  new internal class remains invisible outside `cdi-vauban` without modifying
  `module-info.java`.
- The BCEs are **CDI 4.1 Build Compatible Extensions**
  (`jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`) — not
  the old portable `jakarta.enterprise.inject.spi.Extension`. Each one is declared twice:
  `provides … with io.vidocq.heisenberg.cdi.internal.HeisenbergExtension,
  io.vidocq.heisenberg.cdi.internal.InterceptorPriorityExtension` in the `cdi-vauban`
  module-info (module path) and in `META-INF/services` (class path); `BeanArchiveTest`
  checks that both lists agree.
- Work that depends on the configuration the application starts with goes in an extension
  that asks `io.vidocq.vauban.api.ExtensionPhase.isBuildTime()` and does nothing at build
  time: Vauban freezes what extensions produce at build and, with Vidocq/vauban#288, runs an
  extension again at start only if it asked. Keep such work out of `HeisenbergExtension`,
  whose result must stay freezable.
- The effective flow in `heisenberg-cdi-vauban`:
  `@Retry @Timeout @CircuitBreaker @Bulkhead @Fallback` on a CDI method →
  `FaultToleranceInterceptor.around(InvocationContext)` →
  `AnnotationReader.read(ctx)` → `PolicyComposer.invoke(...)` →
  execution of the chain: `FallbackPolicy` → `CircuitBreakerEngine` → `BulkheadEngine`
  → `TimeoutEngine` → `RetryEngine` → `ctx.proceed()`.
- `StateRegistryBean` / `BulkheadStateRegistryBean` (`@ApplicationScoped`) store
  `CircuitBreakerState` and `BulkheadSemaphore` state through
  `ConcurrentHashMap<StateKey, ...>` where
  `StateKey = (beanClass.getName() + "#" + method.getName() + descriptor)`.
- The exported SPI is `io.vidocq.heisenberg.api.*`:
  `FaultToleranceException`, `FtMetricsRecorder` (with `NOOP` and enums
  `RetryResult` / `CBCallResult` / `CBState`).

## Boundaries not to break

- `heisenberg-tck` is in-reactor but **gated behind the `tck` Maven profile** (TCK
  harmonisation, `vidocq-runtime-tck-*` pattern): never add it to the default
  `<modules>` — a plain `mvn install` must neither download nor run anything
  TCK-related. The historical ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0
  constraint disappeared with the Maven 3.9.16 / Model 4.0.0 migration.
- `heisenberg-core` must import **no** CDI class (`jakarta.enterprise.*`,
  `jakarta.inject.*`) — only `microprofile-fault-tolerance-api`,
  `jakarta.interceptor-api` (for `InvocationContext`), and `microprofile-config-api`.
- **No `synchronized`** — use `ReentrantLock.tryLock(timeout)`, `Semaphore`,
  `AtomicReference` for `CircuitBreaker` state. `synchronized` blocks pin virtual threads.
- **No `ThreadLocal`** — use `ScopedValue` (JEP 506) to propagate execution context
  across virtual calls.
- **No `java.lang.reflect.Proxy`** — all `fallbackMethod` resolution must go through
  `MethodHandles.lookup().findVirtual(...)`.
- **No `setAccessible(true)`** in production — open required packages in
  `module-info.java` and document why.
- **Minimum JUnit 6** (`org.junit:junit-bom` ≥ 6.0.3) for `heisenberg-core` and
  `heisenberg-cdi-vauban` tests. The TCK uses **TestNG** (upstream constraint).
- Any `<scope>compile|runtime</scope>` dependency addition requires a pass through the
  `dependency-gatekeeper` agent and explicit justification in the PR.

## Java Modules convention — `module-info` + `target/javamodules/` workaround

- Every module keeps its `module-info.java` under `src/main/java/`, and tests run on the
  module path (test classes patched into the module under test). Test-only `--add-reads` /
  `--add-opens` live in the Surefire `argLine` of the module that needs them, each one
  explained in a comment there; no production `module-info` opens anything for tests.
- A `@Fallback(fallbackMethod = ...)` in a named application module needs no `opens`: under
  Vauban, `FallbackResolver` takes its private lookup from Vauban's `ModuleLookups`, supplied by
  the generated `_VaubanComponents` of the bean's package (BUG-003). Without it, an
  `opens <package> to io.vidocq.heisenberg.core` is enough; the core adds the read edge itself.
- The compilation module-path is built through `maven-dependency-plugin` during the
  `initialize` phase, which copies required JARs into `target/javamodules/`. Any dependency added
  to the module-path must be referenced in that copy step.
- `microprofile-fault-tolerance-api:4.1` has neither `Automatic-Module-Name` nor
  `module-info.class` : its Java Modules name is `microprofile.fault.tolerance.api` (derived from the
  artifact name). This is the name that must appear in `requires`, not
  `org.eclipse.microprofile.faulttolerance`.
- `heisenberg-cdi-vauban` declares Jakarta APIs (`jakarta.cdi`, `jakarta.inject`,
  `jakarta.annotation`, `jakarta.interceptor`) as `requires static` — provided by
  the container at runtime.

## Useful workflows

```bash
# Initialize the SDK environment
sdk env

# Build the full reactor
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test (installs the reactor, then runs the TCK)
./run-official-tck-mp-fault-tolerance-4.1.sh

# TCK — full suite
./run-official-tck-mp-fault-tolerance-4.1.sh all

# TCK — targeted test (e.g. RetryTest)
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest

# JMH benchmarks
./mvnw -pl heisenberg-bench -Pbench package
java -jar heisenberg-bench/target/benchmarks.jar
```

- The TCK always goes through the root script, which first installs the reactor, then invokes
  `mvn -f heisenberg-tck/pom.xml -Ptck-official test`.
- The TCK requires the non-public artifact to be in the local M2 — see
  `heisenberg-tck/README.md` for the installation procedure.

## Observed contribution conventions

- **Strict TDD**: Red → Green → Refactor. No production line without a prior test.
  Cite the MicroProfile FT 4.1 spec section in test comments (e.g. `// §2.5.3`).
- Unit tests in the same package as the tested class, named `<Class>Test`.
- No Mockito — manual doubles (`FakeInvocationContext`, `FakeConfigSource`, etc.).
- The logic of each engine (`RetryEngine`, `CircuitBreakerEngine`, etc.) is tested
  unit by unit without a CDI container — this is the purpose of the `heisenberg-core` /
  `heisenberg-cdi-vauban` separation.
- JMH benchmarks in `heisenberg-bench` — comparison vs SmallRye Fault Tolerance on the
  same JVM. Results recorded in `BENCH.md` at the project root.
- Reproducible bugs tracked in `BUG.md` with: id, date, symptom, repro, hypothesis, status.
- **Language** — commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## What an agent should assume for upcoming tasks

- `heisenberg-core` is the foundational block: `RetryEngine`, `TimeoutEngine`,
  `CircuitBreakerEngine` (with `CircuitBreakerState`: CLOSED/OPEN/HALF_OPEN),
  `BulkheadEngine`, `FallbackResolver`, `PolicyComposer`, `AsynchronousEngine`,
  `ConfigResolver`. **All exist and are tested** (108 green unit tests).
  None imports CDI classes.
- `heisenberg-cdi-vauban` is the CDI entry point: `FaultToleranceInterceptor`
  (base priority 4010, configurable via `mp.fault.tolerance.interceptor.priority`),
  `FaultTolerancePriority3850Interceptor` (TCK variant),
  `HeisenbergExtension` (CDI 4.1 BCE `BuildCompatibleExtension` under
  `io.vidocq.heisenberg.cdi.internal`, which validates annotations at container startup),
  `InterceptorPriorityExtension` (BCE that rewrites `@Priority` according to config when the
  container starts; under Vauban it needs the fix of Vauban's BUG-20261113-12, see BUG-019),
  `StateRegistryBean` + `BulkheadStateRegistryBean` (`@ApplicationScoped`),
  `DiracFtMetricsRecorder` (§9 MP Metrics) and `OtelFtMetricsRecorder` (§10 OpenTelemetry)
  which coexist through fan-out (`CompositeFtMetricsRecorder`, `MetricsRecorderResolver`).
- External configuration follows MP FT 4.1 §9 precedence:
  `<className>/<methodName>/<AnnotationName>/<parameter>` >
  `<className>/<AnnotationName>/<parameter>` >
  `<AnnotationName>/<parameter>`.
  Resolution through `ConfigProvider.getConfig()` (Ravel in the Vidocq ecosystem).
- Policy composition order (§2.5) from outermost to innermost:
  `@Fallback` → `@CircuitBreaker` → `@Bulkhead` → `@Timeout` → `@Retry` → method.
  Follow this order strictly in `PolicyComposer`.
- `@Asynchronous` changes the return type: `CompletionStage<T>` or `Future<T>`.
  Execution is done through `Executors.newVirtualThreadPerTaskExecutor()` — no platform pool.
- **Metrics §9 (MP Metrics) and §10 (OpenTelemetry)**: both APIs can be published
  simultaneously, the `Composite` fans out each call to all present recorders.
  To add a new §10-side metric: edit `OtelFtMetrics` (names and
  attributes defined by the `TelemetryMetricDefinition` TCK, `seconds` units for
  durations, explicit bucket boundaries defined in `histogram(name, "seconds")`).
- **Arquillian test enricher** (`heisenberg-tck/src/test/java/.../VaubanTckBootstrap.java`) :
  the list of beans registered with the container is **explicit**. Any new
  `@ApplicationScoped` bean on the Heisenberg side intended to be visible in the TCK must be
  added there (otherwise Vauban will not discover it in TCK mode).
- Before any structural change to `PolicyComposer` or the `*StateRegistryBean`,
  reason with the final contract: **MicroProfile Fault Tolerance 4.1 TCK at 100% PASS**
  (current state: 463/463 = 100% PASS — reproduced 2026-05-28T09:05:59Z).

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

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

## Architecture rules

- The architecture rules (module dependencies, layers, no reflection outside the documented fallbacks, no platform thread, no `ThreadLocal`) are ArchUnit tests in `heisenberg-architecture-tests`, built on the shared `vidocq-archrules` library of `vidocq-parent` (test scope; Vidocq/vidocq#289). A new reflective path is documented in `internals.adoc` and added to the rule's fallback list with its reason, never silently.

## Build compatible extensions work at build only

A CDI build compatible extension works at build, and only there. `vidocq:generate` runs the extensions of an
application once and records what they did; the packaged application loads that record instead of running them
(Vidocq/vauban#288). An extension that breaks these rules loses work or checks in a packaged application:

- compute from the deployment's classes only: no configuration value, environment variable, outside file or network
  resource in a phase;
- check the start environment in an observer of `jakarta.enterprise.event.Startup` that throws a
  `DeploymentException`, fed with what the build collected (`withParam`); models: Ravel's `ConfigStartCheck`,
  Cervantes' `JwtConfigurationCheck`;
- add annotations, never remove them; carry data in parameters, never in fields of the extension;
- ask `ExtensionPhase.isBuildTime()` only as a last resort, in a class of its own: an extension that asks runs again
  at each start (Heisenberg's `InterceptorPriorityExtension`);
- the brick's Vidocq runtime extension adds its own beans with `VaubanContainerBuilder.addHostBean`, never
  `addBeanClass`, and its `-codegen` bundle brings the brick's CDI jar to the processor path;
- test both ways: the extensions run at start (TCK, another container) and frozen (the startup report says
  `cdi extensions  N extensions frozen at build`, and an invalid environment still stops the start).

Guide: `docs/writing-a-build-compatible-extension.md` in `Vidocq/vidocq-workspace`, and Vauban's concepts page,
"Writing an extension the build can freeze" (`docs/en/modules/ROOT/pages/concepts.adoc#writing-a-frozen-extension`).
