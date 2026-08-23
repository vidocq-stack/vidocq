# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Werner Heisenberg (1901–1976) formulated the uncertainty principle — one cannot measure
> simultaneously the position and velocity of a particle with arbitrary precision. The Heisenberg
> project implements **MicroProfile Fault Tolerance 4.1**: it encapsulates the uncertainty of the network
> and remote services, and responds to it with order, measurement, and resilience.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The official TCK `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`
  must be installed in the local M2 (non-public artifact — see `heisenberg-tck/README.md`)

## Essential commands

```bash
# SDK environment
sdk env

# Reactor build (without TCK)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test
./run-official-tck-mp-fault-tolerance-4.1.sh

# TCK — full suite
./run-official-tck-mp-fault-tolerance-4.1.sh all

# TCK — targeted test
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=TestName

# TCK — direct reactor invocation (heisenberg-tck is gated by the `tck` profile)
./mvnw -Ptck -pl heisenberg-tck test                  # smoke
./mvnw -Ptck,tck-official -pl heisenberg-tck test     # full suite
```

> `heisenberg-tck` is **in-reactor, gated behind the `tck` Maven profile** (TCK harmonisation
> across the Vidocq workspace, mirroring the `vidocq-runtime-tck-*` pattern): a plain
> `mvn install` neither downloads nor runs anything TCK-related. The historical
> out-of-reactor constraint (ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0) disappeared
> with the Maven 3.9.16 / Model 4.0.0 migration; the MP Fault Tolerance TCK does not use
> the ShrinkWrap Maven resolver, so it is safe in-reactor. See ROADMAP.md § *Ratified decisions*.

## Architecture

Heisenberg is a **MicroProfile Fault Tolerance 4.1** implementation with zero implementation
libraries: only the specified Jakarta EE and MicroProfile APIs are used.

```
heisenberg-api          ← Wrapping of the MP FT 4.1 spec + Vidocq SPI (StateRegistry, PolicyContext)
heisenberg-core         ← Pure Java 25 policy engines, without CDI (RetryEngine, TimeoutEngine,
                          CircuitBreakerEngine, BulkheadEngine, FallbackResolver, PolicyComposer)
heisenberg-cdi-vauban   ← CDI interceptor + Vauban BCE (HeisenbergExtension), delegates to the core
heisenberg-bench        ← JMH benchmarks vs SmallRye Fault Tolerance
heisenberg-tck          ← Official TCK runner (in-reactor, gated by the `tck` Maven profile)
heisenberg-examples     ← Usage examples
```

**Fundamental separation:** `heisenberg-core` contains the pure state machines (retry logic,
circuit breaker state, bulkhead semaphores). `heisenberg-cdi-vauban` carries the only
CDI `@Interceptor` that orchestrates these engines. This makes the policies unit-testable
without a CDI container.

**Current state: M0–M9 completed, official TCK at 100% PASS.**
Latest reproducible `all` run (2026-05-28T09:05:59Z, via
`run-official-tck-mp-fault-tolerance-4.1.sh all`) :
`463 run / 463 PASS / 0 fail / 0 errors / 0 skip = 100 % PASS`
(reproduces the 2026-05-24 result recorded in `ROADMAP.md` ; supersedes the
2026-05-16 ~82% run from `TCK.md`). **§9 MP Metrics (Dirac) and §10 OpenTelemetry
(Humboldt): 100% PASS.** `ROADMAP.md` confirms M9 completion.

**Invocation flow:** CDI intercepts the call through `FaultToleranceInterceptor.around()` →
`PolicyComposer` builds the policy chain in the order defined by the spec →
each engine executes its logic (retry, timeout, circuit breaker, bulkhead, fallback) →
result or exception propagated according to the composition rules.

**Policy composition order** (MP FT spec §2.5, from outermost to innermost):
`@Fallback` → `@CircuitBreaker` → `@Bulkhead` → `@Timeout` → `@Retry` → actual method.

**State management:** `StateRegistryBean` + `BulkheadStateRegistryBean` (CDI `@ApplicationScoped`
singletons) maintain the state of `CircuitBreaker` and `Bulkhead` identified by
`(BeanClass, Method)` — `@RequestScoped` beans create new instances, but FT state
is shared in compliance with the spec.

**Metrics §9 / §10:** the `io.vidocq.heisenberg.api.FtMetricsRecorder` SPI is implemented
in parallel by `DiracFtMetricsRecorder` (MP Metrics via Dirac) and `OtelFtMetricsRecorder`
(OpenTelemetry via `GlobalOpenTelemetry`). The `FaultToleranceInterceptor` injects
`@Any Instance<FtMetricsRecorder>` and delegates to `MetricsRecorderResolver.resolve()` which
wraps multiple recorders in a `CompositeFtMetricsRecorder` (fan-out). This avoids
`AmbiguousResolutionException` when §9 and §10 coexist, and makes it possible to publish both
metric families simultaneously.

## Architecture constraints not to violate

1. **Zero implementation-library import in `heisenberg-core`** — only the spec APIs:
   `microprofile-fault-tolerance-api`, `jakarta.interceptor-api`, `microprofile-config-api`.
   No SmallRye, Hystrix, or Resilience4j.
2. **No `synchronized`, no `ThreadLocal`** — virtual-thread-friendly is mandatory.
   Use `ReentrantLock` with `tryLock`, `Semaphore`, `ConcurrentHashMap`, `ScopedValue`.
3. **No `setAccessible(true)` in production** — use `MethodHandles.privateLookupIn`
   if internal access is needed ; document any `opens` in `module-info`.
4. **No `java.lang.reflect.Proxy`** for fallbacks — resolve them via `MethodHandle`.
5. **`heisenberg-tck` stays gated behind the `tck` Maven profile** — the release reactor
   must never build or download the TCK; keep the profile boundary intact.
6. **TCK 100% PASS is a contract** — any structural change to `heisenberg-core`
   or `heisenberg-cdi-vauban` must preserve this score before merge.

## Conventions

- **Explicit Java modules**: all submodules have a `module-info.java`.
- **Packages**:
  - `io.vidocq.heisenberg.api.*` — stable public SPI (PolicyContext, StateRegistry, etc.)
  - `io.vidocq.heisenberg.internal.*` — internal, non-exported code (engines, state, composition)
  - `io.vidocq.heisenberg.cdi.*` — CDI integration (interceptor, BCE)
- **Maven groupId**: `io.vidocq.heisenberg`
- **Sealed interfaces**: `PolicyResult` is a sealed interface (`Success`, `Failure`, `Fallback`)
- **Records**: prefer immutable records for policy configurations
  (`RetryConfig`, `CircuitBreakerConfig`, `BulkheadConfig`, `TimeoutConfig`)
- **Pattern matching**: use `switch` on sealed types in `PolicyComposer`
- **Virtual threads + `join(Duration)`** (Java 21+, finalized) for `@Timeout` — handles timeouts
  with best-effort cancellation of virtual threads. `StructuredTaskScope` (JEP 505, finalized in Java 25)
  is available, but the current implementation uses `Thread.join(Duration)` to remain compatible with
  Java 21+.
- **Language** — commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## TDD methodology

- **Red → Green → Refactor** — no production line without a prior test.
- Cite the MicroProfile FT 4.1 spec section in test Javadoc/comments.
- Unit tests in the same package as the tested class, named `<Class>Test`.
- No Mockito — manual test doubles or JDK interceptors (`java.lang.reflect.InvocationHandler`)
  to simulate backends.
- CDI integration tests through embedded Vauban (without Arquillian) in `heisenberg-cdi-vauban`.
- JMH benchmarks in `heisenberg-bench` — comparison against SmallRye Fault Tolerance from M3 onward.

## Default plan mode

- Enter plan mode for any non-trivial task (adding a policy, refactoring
  `PolicyComposer`, modifying `StateRegistry`).
- Document architecture decisions in `ROADMAP.md` (section “Ratified decisions”).
- Use the `virtual-threads-reviewer` agent for any concurrent code modification.
- Use the `java-modules-guardian` agent after any package addition or `module-info.java` modification.

## Available agents

- `classfile-codegen` — if a fallback requires bytecode generation (unlikely)
- `virtual-threads-reviewer` — for `TimeoutEngine` (virtual threads + `join(Duration)`), `BulkheadEngine`
  (`Semaphore` under virtual threads), any concurrent code change
- `java-modules-guardian` — after modifying `module-info.java` or adding a package
- `dependency-gatekeeper` — before adding any dependency to `pom.xml`
- `tck-runner` — to diagnose MicroProfile FT 4.1 TCK failures

## MicroProfile Fault Tolerance 4.1 TCK

- Framework: **TestNG** (not JUnit — official TCK constraint)
- Arquillian container: embedded Vauban + Chappe (HTTP transport if needed)
- TCK artifact: `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`
- Suite file: `heisenberg-tck/src/test/resources/tck-suite.xml`
- Timeout property: `org.eclipse.microprofile.fault.tolerance.tck.timeout.multiplier` (default 1.0)
- Target score: **100% PASS** (all tests in the suite)
- Challenges documented in `TCK.md` if tests are excluded with justification

## Allowed spec dependencies

```
org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1              (provided)
jakarta.interceptor:jakarta.interceptor-api:2.2                 (provided)
org.eclipse.microprofile.config:microprofile-config-api:3.1     (provided)
io.opentelemetry:opentelemetry-api:1.39.0                       (provided — §10)
org.junit:junit-bom:6.0.3                                       (test, BOM)
```

`opentelemetry-api` is `provided` on `heisenberg-cdi-vauban` only
(`requires static io.opentelemetry.api` in `module-info`). CDI silently ignores
`OtelFtMetricsRecorder` if OTel is not present at runtime.

Any new `<scope>compile</scope>` or `<scope>runtime</scope>` dependency must go through
`dependency-gatekeeper` and be explicitly justified in the PR.

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
