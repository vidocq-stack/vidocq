# M7b — Architecture analysis (2026-05-21)

## Key finding from the TCK audit

The MicroProfile Telemetry 2.1 TCK (`microprofile-telemetry-tracing-tck:2.1`)
requires the implementation to support the **OpenTelemetry SDK autoconfigure
extension mechanism**. Evidence from decompiling the tests:

```java
// ExporterSpiTest.createDeployment() — javap -c extract
ShrinkWrap.create(WebArchive.class)
    .addClasses(
        InMemorySpanExporter.class,                  // io.opentelemetry.sdk.trace.export.SpanExporter
        InMemorySpanExporterProvider.class,          // io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
        TestCustomizer.class)
    .addAsServiceProvider(
        ConfigurableSpanExporterProvider.class,       // ← OTel SDK SPI
        InMemorySpanExporterProvider.class)
    .addAsResource(
        new StringAsset("otel.sdk.disabled=false\notel.traces.exporter=in-memory"),
        "META-INF/microprofile-config.properties");
```

Key signatures:

```
public class InMemorySpanExporter implements io.opentelemetry.sdk.trace.export.SpanExporter
public class InMemorySpanExporterProvider implements io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
```

And injection happens through CDI:

```java
@Inject InMemorySpanExporter exporter;
exporter.assertSpanCount(1);
```

## Conflict with the Humboldt architecture constraint

CLAUDE.md §"Architecture constraints that must not be violated" item 1:

> **No `import io.opentelemetry.sdk.*`** in humboldt — we rewrite this code,
> we do not consume it.

The TCK assumes that:
- The implementation embeds OTel SDK autoconfigure
- User exporters are `io.opentelemetry.sdk.trace.export.SpanExporter`
- The trace pipeline consumes `io.opentelemetry.sdk.trace.data.SpanData`
- Configuration goes through `io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties`

This is **strictly incompatible** with Humboldt's current philosophy of
reimplementing the SDK without consuming it.

## Architectural options

### Option A — Embed OTel SDK autoconfigure in humboldt-runtime (rejected by current architecture)

- Humboldt becomes a wrapper around the official OTel SDK
- We can target 100% TCK without gymnastics
- **Destroys the zero-OTel-SDK philosophy** + 4 `humboldt-sdk-*` modules become
  dead code or duplicates
- Breaks the ADRs and Humboldt's value proposition (static codegen, VT-friendly,
  future HTTP exporter via chappe-client)

### Option B — OTel SDK ↔ Humboldt SDK bridge (full adapter)

- humboldt-runtime integrates the OTel SDK autoconfigure SPI only as a **discovery
  mechanism** (the OTel SDK is never used to process spans)
- When `otel.traces.exporter=<custom>` is configured, we instantiate the
  OTel SDK provider and get an `io.opentelemetry.sdk.trace.export.SpanExporter`
- An adapter `OtelSpanExporterBridge implements io.vidocq.humboldt.sdk.trace.SpanExporter`
  wraps it: for each batch, it converts `humboldt.SpanData → opentelemetry.SpanData`
  via `io.opentelemetry.sdk.testing.trace.TestSpanData.builder()` or an
  equivalent impl, then delegates
- The TCK's `InMemorySpanExporter` therefore receives the real Humboldt spans converted
  to the OTel SDK format
- Work: ~600-800 lines of conversion code + 1 new module (`humboldt-sdk-bridge-otel`)
  + isomorphism tests
- Will remain **OPTIONAL** — not used in prod, activated only via an
  explicit dependency (and triggered by autoconfigure when the env requests it)

### Option C — Adapter limited to the TCK runner (out-of-reactor)

- All OTel SDK bridge code lives in `humboldt-tck/` (out-of-reactor, already
  isolated from prod)
- No new module in the reactor
- humboldt-runtime exposes only an **SPI hook** (already existing? to create) to
  dynamically inject an "external" `SpanExporter` into the trace pipeline
  at startup
- The TCK runner provides the `OtelSpanExporterBridge` + an Arquillian harness
  that knows how to:
  1. Parse `META-INF/microprofile-config.properties` from the ShrinkWrap war
  2. Load `ConfigurableSpanExporterProvider`s from the war via ServiceLoader
  3. Wrap OTel exporters into humboldt exporters through the bridge
  4. Start Vauban CDI + Cassini JAX-RS + Chappe HTTP + AutoConfiguredHumboldt
     in-process, with the `InMemorySpanExporter` bean exposed in the BeanManager
- Work: ~800-1200 lines (adapter + custom Arquillian container)
- **Advantage**: zero pollution of the Humboldt runtime. The philosophy is preserved.
- **Risk**: TCK code fairly complex to maintain, sensitive to spec evolution
  (each TCK version bump potentially painful).

## Recommendation

**Option C** — confine the OTel SDK bridge to the out-of-reactor TCK runner.

Justification:
- Preserves the Humboldt philosophy (the application runtime does not embed the OTel SDK)
- Consistent with the decision already taken for `humboldt-tck/` (standalone pom,
  `opentelemetry-sdk` in test scope)
- Allows aiming for 100% TCK without compromising the architecture
- If maintenance later becomes too heavy, we can migrate toward
  Option B (the bridge becomes an official optional module) without breaking
  the philosophy

## Decision expected from Yann

Before coding M7b.4 (Arquillian container) and M7b.3 (re-registration of
exporters), I need to know which of the 3 paths to take. The code, the scope
of the runner, and future version breakages differ radically.

## Execution plan if Option C is validated

1. **M7b.3-bis** — Define/expose an extension point in humboldt-runtime
   (probably `HumboldtAutoConfigure.withExtraSpanExporter(humboldt.SpanExporter)`)
   so an external harness can inject an exporter without touching the env
   vars (~30 LOC)

2. **M7b.4a** — Create in `humboldt-tck/` an `OtelSpanExporterBridge` that
   converts `humboldt.SpanData → otel.SpanData` (reuse the public OTel record
   `io.opentelemetry.sdk.testing.trace.TestSpanData`)
   (~250 LOC + isomorphism tests)

3. **M7b.4b** — Embedded Humboldt Arquillian container:
   - `HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig>`
   - At each `deploy(Archive)`: extract WAR in memory, isolated ClassLoader,
     boot Vauban CDI Lite, register Cassini filters/providers, start Chappe
     on a random port, configure AutoConfiguredHumboldt with bridged exporters
   - Register the service via `META-INF/services/org.jboss.arquillian.container.spi.client.container.DeployableContainer`
   - (~400-600 LOC)

4. **M7b.5** — Enable `OpenTelemetryBeanTest` (just one) and target a START
   without crashes (assertions may fail, we are only trying to validate the
   chain).
