# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- For the official TCK (M7+), the artifact `org.eclipse.microprofile.telemetry:microprofile-telemetry-tck:2.1` must be available (on Central or installed in the local M2 — procedure documented in `TCK.md` when the runner is created)

## Essential commands

```bash
# Build the reactor (without tests)
./mvnw -ntp install -DskipTests

# Build with unit tests
./mvnw -ntp verify

# Build a single module
./mvnw -ntp -pl humboldt-api install

# TCK (coming in M7)
# ./run-official-tck-telemetry-2.1.sh         # smoke
# ./run-official-tck-telemetry-2.1.sh all     # full suite
```

> `humboldt-tck` (delivered in M7) will be **outside the reactor** (`pom.xml` Model 4.0.0 standalone, without `<parent>`) to work around the ShrinkWrap Maven Resolver 3.3 / Model 4.1.0 incompatibility — same constraint as `cassini-tck`, `champollion-tck`, `foy-tck`. Do not reintegrate this module into the reactor.

## Architecture

Humboldt is a MicroProfile Telemetry 2.1 implementation (tracing + metrics + logs) based on the public OpenTelemetry API, **without bundling `opentelemetry-sdk` or third-party exporters** — the entire SDK is rewritten behind the standard OTel API surface.

```
humboldt-api                       ← public facade + stable SPI (M0)
humboldt-sdk-common                ← Resource, Clock, Attributes, IdGenerator (M1)
humboldt-context                   ← Virtual-Threads-friendly ContextStorage (ScopedValue) (M1)
humboldt-sdk-trace                 ← SdkTracerProvider, SpanProcessor, samplers, BatchSpanProcessor VT (M2)
humboldt-exporter-otlp-http        ← OTLP/HTTP-protobuf exporter via chappe-client (M3)
humboldt-propagator-w3c            ← W3C TraceContext + Baggage (M3)
humboldt-sdk-metric                ← Counter/Histogram/UpDownCounter/Gauge, PeriodicReader (M4)
humboldt-sdk-log                   ← LogRecordProcessor, JUL/SLF4J bridge (M5)
humboldt-cdi                       ← @WithSpan interceptor via Vauban (M6)
humboldt-rest                      ← JAX-RS filter via Cassini (M6)
humboldt-runtime                   ← autoconfig aggregator (M6)
humboldt-tck                       ← official TCK runner outside reactor (M7)
```

**Flow of an instrumented incoming HTTP request**:
`Chappe Filter → humboldt-rest (Cassini) → humboldt-context (ScopedValue<Context>) → @WithSpan resource method → humboldt-sdk-trace → humboldt-exporter-otlp-http → chappe-client → Collector`

**Key architectural decisions** (see `PLAN.md`):

- **OTel API accepted as a dependency** (`opentelemetry-api`, `opentelemetry-context`, `opentelemetry-semconv`). SDK and third-party exporters **rejected**.
- **gRPC OTLP = post-MVP** via future module `chappe-grpc` (see `chappe/tasks/todo.md` Phase 7) — never via grpc-java/Netty.
- **Bilingual en/fr documentation from M0** under `docs/en/` and `docs/fr/`.
- **Static codegen** (Class-File API JEP 484 + APT) rather than runtime reflection or bytecode agents.

## Architectural constraints not to break

1. **No `import io.opentelemetry.sdk.*`** in humboldt — this code is rewritten, not consumed.
2. **No Netty / grpc-java / OkHttp / Guava dependency** — any exception must go through the `dependency-gatekeeper` agent.
3. **`@WithSpan` must work on virtual threads without pinning** — use `ScopedValue<Context>` (JEP 506), never direct `ThreadLocal` on the hot path.
4. **`humboldt-tck/pom.xml` stays at Model 4.0.0** once created (ShrinkWrap constraint).
5. **MicroProfile Telemetry 2.1 conformance**: any patch to the core must preserve the TCK score once achieved.

## Conventions

- **Explicit Java modules**: all modules have a minimal `module-info.java` with targeted `exports`.
- **Packages**: `io.vidocq.humboldt.spi.*` = stable public SPI; `io.vidocq.humboldt.internal.*` = internal code.
- **Maven groupId**: `io.vidocq.humboldt`.
- **Logging**: `System.getLogger(Class.class.getName())` exclusively, never SLF4J/Log4j in humboldt code itself (humboldt provides a *bridge* to SLF4J, it does not consume it).
- **TDD**: test (or TCK scenario) before the code. See `tasks/todo.md` for M0..M9 breakdown.

## Roadmap

The detailed plan is in `PLAN.md` (§13 milestones M0..M9). In summary:

- **M0** — JPMS skeleton + workflows + documentation (in progress)
- **M1** — `humboldt-sdk-common` + `humboldt-context` (Resource, Clock, ScopedValueContextStorage)
- **M2** — `humboldt-sdk-trace` (first complete signal, partial tracing TCK)
- **M3** — `humboldt-propagator-w3c` + `humboldt-exporter-otlp-http` (tracing TCK PASS)
- **M4** — `humboldt-sdk-metric` (metrics TCK PASS)
- **M5** — `humboldt-sdk-log` (logs TCK PASS)
- **M6** — `humboldt-cdi` + `humboldt-rest` + `humboldt-runtime` (`@WithSpan`, auto-instrumentation)
- **M7** — `humboldt-tck` outside reactor, TCK 100%
- **M8** — benchmarks vs SmallRye, perf ADRs
- **M9** — complete Antora documentation, release 1.0
