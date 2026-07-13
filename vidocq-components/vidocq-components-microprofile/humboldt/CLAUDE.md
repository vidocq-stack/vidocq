# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The official TCK artifacts are on **public Maven Central** (no manual install needed):
  `microprofile-telemetry-tracing-tck:2.1`, `microprofile-telemetry-metrics-tck:2.1`, `microprofile-telemetry-logs-tck:2.1`
- **TCK status: 85/85 PASS (2026-06-24)** — see [`TCK.md`](TCK.md)

## Essential commands

```bash
# Build the reactor (without tests)
./mvnw -ntp install -DskipTests

# Build with unit tests
./mvnw -ntp verify

# Build a single module
./mvnw -ntp -pl humboldt-api install

# TCK — smoke test / full suite / targeted test
./run-official-tck-telemetry-2.1.sh         # smoke
./run-official-tck-telemetry-2.1.sh all     # full suite (85 tests)
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

- **M0** — Java Modules skeleton + workflows + documentation (in progress)
- **M1** — `humboldt-sdk-common` + `humboldt-context` (Resource, Clock, ScopedValueContextStorage)
- **M2** — `humboldt-sdk-trace` (first complete signal, partial tracing TCK)
- **M3** — `humboldt-propagator-w3c` + `humboldt-exporter-otlp-http` (tracing TCK PASS)
- **M4** — `humboldt-sdk-metric` (metrics TCK PASS)
- **M5** — `humboldt-sdk-log` (logs TCK PASS)
- **M6** — `humboldt-cdi` + `humboldt-rest` + `humboldt-runtime` (`@WithSpan`, auto-instrumentation)
- **M7** ✅ — `humboldt-tck` outside reactor, **TCK 85/85 PASS**
- **M8** — benchmarks vs SmallRye, perf ADRs
- **M9** — complete Antora documentation, release 1.0

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
