# Humboldt — Bug registry

> Every reproducible bug (internal issue, regression, incorrect behavior not yet fixed) must be tracked here. See the workspace root `CLAUDE.md` for the convention.

## Entry format

```
### [HBT-N] Short title
- **Date**: YYYY-MM-DD
- **Component**: humboldt-api / humboldt-sdk-trace / …
- **Status**: OPEN / INVESTIGATING / FIXED / WONTFIX
- **Affected**: affected version(s) (e.g. 0.1.0-SNAPSHOT)
- **Symptom**: description of the observed error
- **Reproduction**: minimal steps to reproduce
- **Root-cause hypothesis**: root-cause diagnosis (when known)
- **Fix**: PR link or applied fix
```

---

### [HBT-2] cassini-cdi-vauban never activates RequestContext around an HTTP dispatch

- **Date**: 2026-05-24
- **Component**: cassini-cdi-vauban
- **Status**: FIXED (cassini-cdi-vauban commit `00:53 2026-05-24`)
- **Affected**: cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptom**: any `@RequestScoped` resource (= any `@Path` class after the Cassini BCE) throws `ContextNotActiveException: RequestScope is not active` when invoking a resource method.
- **Reproduction**: see HBT-1 — trigger BaggageTest before the workaround.
- **Cause**: `cassini-cdi-vauban` contains no call to `VaubanContainer.requestContext().activate()` around HTTP dispatches. `@RequestScoped` beans therefore can never be instantiated.
- **Applied fix**: new `VaubanRequestScopeFilter` (`@Provider @PreMatching @Priority(Integer.MIN_VALUE)`)
  which implements both `ContainerRequestFilter` (activate) and `ContainerResponseFilter` (deactivate).
  Auto-injected in production via `VaubanBeanProvider.getResourceClasses()` (singleton returned
  by `getBean()`). For test harnesses that do not use `CassiniStackBuilder.beanProvider(...)`
  (case of `humboldt-tck/CassiniHarness`), the constructor is public — register manually via
  `.provider(new VaubanRequestScopeFilter(container))`.
- **Validation** : Cassini Jakarta REST 4.0 TCK = 2535/2535 PASS (contract respected, 0 regression).
  humboldt MP Telemetry 2.1 TCK = 19/43/23 (equivalent to the previous workaround fix, but now
  activation is handled by cassini-cdi-vauban rather than by an ad-hoc humboldt-tck Handler).

---

### [HBT-1] Cassini @Path → @RequestScoped BCE not applied to beans added at runtime via Vauban addBeanClass

- **Date**: 2026-05-24
- **Component**: humboldt-tck (Vauban runtime + cassini-cdi-vauban BCE interaction)
- **Statut** : FIXED (humboldt-tck commit `00:25 2026-05-24`)
- **Affected**: humboldt-tck 0.1.0-SNAPSHOT, vauban 0.1.0-SNAPSHOT, cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptom**: `cdi.select(BaggageResource.class)` throws `UnsatisfiedResolutionException:
  No bean found for type: ...BaggageResource`. The class has `@Path` but no
  explicit CDI scope — the BCE `CassiniScopeExtension.addDefaultScope()` expected to add
  `@RequestScoped` is NOT executed for classes registered via
  `VaubanContainerBuilder.addBeanClass()` en runtime. Consequence: TCK resources
  TCK with `@Inject Baggage/Tracer/Span/...` receive `null` → NPE → HTTP 500.
- **Reproduction** :
  1. `cd humboldt && ./run-official-tck-telemetry-2.1.sh -Dtest=BaggageTest`
  2. Observe in stderr logs: `Baggage Resource Exception: NullPointerException
     Cannot invoke "Baggage.getEntryValue" because "this.baggage" is null`
  3. `cdi.select(BaggageResource.class)` (from CassiniHarness) confirms
     `UnsatisfiedResolutionException`.
- **Root-cause hypothesis**: Vauban applies `@Enhancement` BCEs at compile time via
  APT processor. Classes added by `addBeanClass(Class)` at runtime are
  registered in the BeanManager but do NOT go through the
  enhancement phase → their annotations are not mutated → `@Path` classes without
  scope are ignored by bean discovery (Vauban requires an explicit CDI scope
  to consider a class a bean).
- **Affected tests (FAIL HTTP 500)**: BaggageTest, baggageBeanChange, and
  probably any test that POSTs/GETs a TCK resource with a CDI-typed `@Inject`
  (testIntegrationWithJaxRsClient*, testIntegrationWithMpRestClient*).
- **Applied fix**: Vauban already has the runtime mechanism to apply BCEs
  `@Enhancement` to "unprocessed" classes (`BceProcessor.processEnhancementOnly` line 742
  of `VaubanContainerBuilder`), but ONLY if the BCE is in the bean classes set.
  `addBeanClass()` does not scan the ServiceLoader `META-INF/services`. Fix: add a
   line in `HumboldtDeployableContainer.deploy()` that explicitly declares
   `CassiniScopeExtension.class` via `addBeanClass()`. The BCE becomes discoverable and
   applies its `@Enhancement`, which adds synthetic `@RequestScoped` to `@Path` classes
  of the WAR.
- **Validation**: `BaggageTest.baggage` PASS (vs FAIL before). TCK run goes from 16 → 19 PASS.
- **Follow-up**: a generic Vauban fix (automatically scanning BCEs via ServiceLoader
  in `build()` even without `scanLocal()`) would be cleaner — separate Vauban task.

---

### [HBT-3] Java Modules workaround in `humboldt-rest` via manual copying of compile-scope JARs

- **Date** : 2026-05-25
- **Component**: humboldt-rest/pom.xml
- **Status**: ⚠️ OPEN — workaround active
- **Affected**: humboldt 0.1.0-SNAPSHOT
- **Symptom**: `humboldt-rest/pom.xml` uses `maven-dependency-plugin` (phase `initialize`)
  to copy compile-scope JARs (`humboldt-propagator-w3c`, `humboldt-otel-api`,
  `humboldt-otel-context`) into `target/javamodules/`, then passes
  `--module-path ${project.build.directory}/javamodules` manuellement au compilateur javac.
  This workaround is limited to the `humboldt-rest` submodule (the other Humboldt modules
  do not seem affected).
- **Reproduction** :
  ```bash
  grep -n "javamodules\|module-path" humboldt/humboldt-rest/pom.xml
  # reveals maven-dependency-plugin + compilerArgs
  ```
  Remove the config and recompile `humboldt-rest` to observe `module not found` errors.
- **Root-cause hypothesis**: the OTel modules (`opentelemetry-api`, `opentelemetry-context`)
  and the copied intermediate Humboldt modules do not have a `module-info.class` recognized by
  `maven-compiler-plugin` 4.x. Copying them into `target/javamodules/` allows javac to
  resolve them as automatic modules from the JAR filename.
- **Proposed fix**: check whether `opentelemetry-api` 1.x publishes a Java Modules descriptor
  explicitly in recent versions; wrap if needed. Investigate why
  `humboldt-propagator-w3c` and `humboldt-otel-*` (internal modules) are not resolved
  natively — they should have their own `module-info.class`.

---

## BUG-20260712-01 — hardcoded implementation version constant in the published api artifact

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : Humboldt.version() (humboldt-api/Humboldt.java)
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports a hardcoded
  "0.1.0-SNAPSHOT" implementation version — the constant was maintained by hand and never
  updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** : read the constant from the published 0.2.0 jar.
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3 follow-up.
    Fixed: version.properties filtered by Maven next to the class, constant loaded at class
    init (same-module Java Modules resource, no opens). No longer compile-time-inlineable, which
    also protects future consumers from the javac inlining trap.

---

## BUG-20261004-01 — OpenTelemetry ComponentLoader service lookups fail on the module path

- **Date**: 2026-10-04
- **Status**: FIXED (commit 19479e2 on branch `pr/ybl/mp-7.2`, 2026-10-04)
- **Component**: humboldt-otel-context (`module io.opentelemetry.context`), humboldt-otel-interop
- **Affected**: humboldt 0.4.0-SNAPSHOT (OpenTelemetry 1.66 upgrade, branch `pr/ybl/mp-7.2`)
- **Symptom**: since OpenTelemetry 1.66, `opentelemetry-common` is shaded into humboldt-otel-context, so
  `io.opentelemetry.common.ServiceLoaderComponentLoader` lives in the explicit module
  `io.opentelemetry.context`. Its `load(Class)` calls `ServiceLoader.load(spiClass, classLoader)` from that
  named module, which declares no `uses` for the services OpenTelemetry components look up through it.
  On the module path any such lookup throws `java.util.ServiceConfigurationError: ... module
  io.opentelemetry.context does not declare 'uses'`. On the class path (the TCK set-up) everything works,
  which is why the official TCK stays green. Upstream the class sits in an automatic module, which may use
  any service, so plain OpenTelemetry does not hit this.
- **Minimal reproduction** (reported by the final branch review, not run here):
  1. Put humboldt-otel-interop, humboldt-otel-context and an OpenTelemetry exporter that sends over HTTP
     (`opentelemetry-exporter-otlp` + `opentelemetry-exporter-sender-jdk`) on the **module path**.
  2. Let `OtelSpiAutoConfiguration` create the exporter through its `ConfigurableSpanExporterProvider`:
     it passes a `MapConfigProperties`, whose `getComponentLoader()` is the `ConfigProperties` default
     `ComponentLoader.forClassLoader(...)`.
  3. exporter-common looks up its `HttpSenderProvider` through that `ComponentLoader` →
     `ServiceConfigurationError`.
- **Cause hypothesis**: the `uses` check of `ServiceLoader.load` applies to the module of the *caller*
  (`ServiceLoaderComponentLoader`), now an explicit module; the services it is asked to load belong to
  optional OpenTelemetry modules it cannot declare. The exporter builders also default to
  `ComponentLoader.forClassLoader(...)` (seen in the exporter-common 1.62 `HttpExporterBuilder` bytecode),
  so building an OpenTelemetry HTTP exporter directly on the module path is likely affected as well.
- **Suggested fix**: override `getComponentLoader()` in `MapConfigProperties` (humboldt-otel-interop) to
  return a `ComponentLoader` implemented in the interop module, which calls
  `getClass().getModule().addUses(spiClass)` and then `ServiceLoader.load(spiClass, classLoader)` — the
  `uses` check then applies to the interop module, and `Module.addUses` is allowed there because it is the
  caller's own module. (`Module.addUses` cannot be called on `io.opentelemetry.context` from outside it.)
- **Investigations**:
  - 2026-10-04: found during the final review of the MicroProfile Telemetry 2.2 / OpenTelemetry 1.66
    branch. Logged only; no fix on that branch.
  - 2026-10-04: reproduced in-repo by `MapConfigPropertiesModuleLayerTest` (humboldt-otel-interop). The
    bricks' surefire runs tests on the class path, where the lookup always works, so the test defines a real
    `ModuleLayer` from the module jars (humboldt-otel-interop and `io.opentelemetry.context` explicit, the
    OpenTelemetry autoconfigure SPI and trace propagators automatic) and looks services up through
    `MapConfigProperties.getComponentLoader()` from inside it. Before the fix every lookup threw
    `ServiceConfigurationError: ... module io.opentelemetry.context does not declare 'uses'`, both for a
    service the interop module declares (`ConfigurablePropagatorProvider`) and for one no module declares
    (`java.util.spi.ToolProvider`, standing for an exporter's `HttpSenderProvider`).
- **Fix**: the suggested one. `MapConfigProperties.getComponentLoader()` returns an `InteropComponentLoader`
  owned by humboldt-otel-interop, which calls `Module.addUses(spiClass)` on its own module, then
  `ServiceLoader.load(spiClass, classLoader)`. `OtelSpiAutoConfiguration` gives the SPI providers and
  customizers properties whose loader searches the discovery ClassLoader (the OpenTelemetry autoconfigure
  does the same); `new MapConfigProperties(map)` keeps the upstream default (the autoconfigure SPI's class
  loader). The OpenTelemetry OTLP exporter providers pass `config.getComponentLoader()` to their builders
  (`OtlpConfigUtil.configureOtlpExporterBuilder`, seen in the exporter-otlp 1.62 bytecode — the newest in the
  local repository), so exporters created through the SPI get the module-path-safe loader for their
  `HttpSenderProvider` lookup. Out of scope, still upstream behaviour: an OpenTelemetry exporter built directly on the
  module path without `setComponentLoader(...)` uses `ComponentLoader.forClassLoader(...)` and hits the same
  error; such code must pass a loader of its own.
- **Validation**: `MapConfigPropertiesModuleLayerTest` 3/3 (RED before the fix: 3 failures),
  `OtelSpiAutoConfigurationTest` 3/3, full reactor `./mvnw -ntp clean install` green, official TCK
  (2.2-RC3) 85/85 — that TCK runs on the class path, so it guards the class-path behaviour only.

---

## BUG-20261004-02 — the OTLP/JSON metric encoder drops double data points and synchronous gauges

- **Date**: 2026-10-04
- **Status**: FIXED (commit 5b03d5b on branch `pr/ybl/mp-7.2`, 2026-10-04)
- **Component**: humboldt-exporter-otlp-http (`OtlpJsonMetricEncoder`)
- **Affected**: humboldt 0.4.0-SNAPSHOT (and earlier)
- **Symptom**: `writeSum` and `writeGauge` only encode `LongPointData` (`if (!(p instanceof LongPointData lp))
  continue;`), so the `DoublePointData` produced by double counters, double up-down counters and double gauges
  (`DoubleSumAggregator`, `DoubleLastValueAggregator`) is exported as an empty `dataPoints` array. `writeMetric`
  also has no `case GAUGE`: a synchronous gauge (`InstrumentType.GAUGE`, built by `SdkLongGaugeBuilder` /
  `SdkDoubleGaugeBuilder`) is written with a name and no data field at all.
- **Minimal reproduction** (found by reading the code while fixing the non-finite doubles of the same
  encoder; not run): record a value on a `DoubleCounter`, or set a synchronous gauge, of an `SdkMeterProvider`
  whose reader exports through `OtlpHttpMetricExporter`, and look at the JSON body: no data point for it.
- **Cause hypothesis**: the encoder was written when humboldt-sdk-metric produced long points and
  histograms only (M4); double points and synchronous gauges came later and the encoder was not extended.
- **Suggested fix**: encode `DoublePointData` as `"asDouble"` (through `OtlpJsonCommon.appendDouble`, so that
  non-finite values stay valid JSON) in `writeSum`/`writeGauge`, and route `GAUGE` to `writeGauge`.
- **Investigations**:
  - 2026-10-04: logged during Task FC1 of the MicroProfile 7.2 upgrade (out of its scope); no fix yet.
  - 2026-10-04 (Task FC3): reproduced end to end by `OtlpHttpMetricExporterE2ETest`
    `e2e_double_counter_and_synchronous_gauges_export_their_data_points` (a double counter, a synchronous double
    gauge and a synchronous long gauge of an `SdkMeterProvider` exporting through `OtlpHttpMetricExporter`). The
    body received before the fix: `{"name":"bytes","sum":{"dataPoints":[],...}},{"name":"temperature"},
    {"name":"queue.size"}`. Mapping checked against the OTLP JSON encoding and the OpenTelemetry Java 1.66
    marshalers (`NumberDataPointMarshaler`: `startTimeUnixNano`/`timeUnixNano` fixed64 and `asInt` sfixed64 as
    JSON strings, `asDouble` as a JSON number; `SumMarshaler`: `dataPoints`, `aggregationTemporality` as an
    integer, `isMonotonic`; `GaugeMarshaler`: `dataPoints` only; `MetricMarshaler`: long and double gauges as
    `gauge`, long and double sums as `sum`).
- **Fix**: the suggested one. `writeSum` and `writeGauge` share `writeNumberDataPoints`, which writes a
  `LongPointData` as `"asInt"` and a `DoublePointData` as `"asDouble"` (through `OtlpJsonCommon.appendDouble`);
  `writeMetric` routes `GAUGE` and `OBSERVABLE_GAUGE` to `writeGauge`. Every kind of data humboldt-sdk-metric
  produces is now encoded: long/double sums (synchronous and observable counters and up-down counters),
  long/double gauges (synchronous and observable) and explicit-bucket histograms.
- **Validation**: `OtlpJsonMetricEncoderTest` 9/9 (8 new: long sum, double counter, double up-down counter,
  observable double sum, synchronous long gauge, synchronous double gauge, observable double gauge, non-finite
  double points; RED before the fix: 7 failures, the long sum test guards the existing mapping) and the E2E test
  above (RED before the fix); full reactor `./mvnw -ntp clean install` green.

---

## BUG-20261004-03 — OpenTelemetry SDK components cannot use `io.opentelemetry.api.internal` on the module path

- **Date**: 2026-10-04
- **Status**: OPEN
- **Component**: humboldt-otel-api (`module io.opentelemetry.api`, `src/main/moditect/module-info.java`)
- **Affected**: humboldt 0.4.0-SNAPSHOT (and earlier); any OpenTelemetry SDK artifact used next to it on the
  module path, e.g. through humboldt-otel-interop in the Vidocq runtime
- **Symptom**: humboldt-otel-api repackages `opentelemetry-api` as the explicit module `io.opentelemetry.api`,
  which does not export `io.opentelemetry.api.internal`. Upstream the API jar is an automatic module and exports
  every package, and the OpenTelemetry SDK artifacts use that package across jars (`ConfigUtil`, `Utils`, ...).
  On the module path the first such access fails: `java.lang.IllegalAccessError: class
  io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties (in module io.opentelemetry.sdk.autoconfigure.spi)
  cannot access class io.opentelemetry.api.internal.ConfigUtil (in module io.opentelemetry.api) because module
  io.opentelemetry.api does not export io.opentelemetry.api.internal to module
  io.opentelemetry.sdk.autoconfigure.spi` — raised by the `ConfigProperties` default methods
  (`getString(name, default)`, ...) that every OpenTelemetry exporter provider calls. The OTLP exporter provider
  of `opentelemetry-exporter-otlp` therefore cannot create an exporter on the module path. The class path (the
  official TCK set-up) is not affected.
- **Minimal reproduction** (scratch harness, not in the repository): a `ModuleLayer` with the humboldt
  explicit modules (`io.opentelemetry.context`, `io.opentelemetry.api`, humboldt-otel-interop and the Humboldt
  SDK modules) and, as automatic modules, `opentelemetry-sdk`, `-sdk-common`, `-sdk-trace`, `-sdk-metrics`,
  `-sdk-logs`, `-sdk-extension-autoconfigure-spi`, `-extension-trace-propagators`, `-exporter-otlp`,
  `-exporter-common`, `-exporter-otlp-common` and `-exporter-sender-jdk` 1.66.0; inside it,
  `new OtlpSpanExporterProvider().createExporter(new MapConfigProperties(Map.of("otel.exporter.otlp.protocol",
  "http/protobuf"), layerLoader))` → the `IllegalAccessError` above. With
  `Controller.addExports(io.opentelemetry.api, "io.opentelemetry.api.internal", <every module>)` the same call
  returns an `OtlpHttpSpanExporter`.
- **Cause hypothesis**: the module descriptor lists the public API packages only; `internal` packages are
  OpenTelemetry's cross-artifact implementation surface, which the SDK jars rely on.
- **Suggested fix** (needs a decision: it widens the exports of a Humboldt module): `exports
  io.opentelemetry.api.internal;` in `humboldt-otel-api/src/main/moditect/module-info.java`, which restores the
  upstream visibility (a qualified export cannot list every OpenTelemetry artifact that may use it). Workaround
  for an application: `--add-exports io.opentelemetry.api/io.opentelemetry.api.internal=<module>` for each
  OpenTelemetry module that needs it.
- **Investigations**:
  - 2026-10-04: found during Task FC3 of the MicroProfile 7.2 upgrade while checking, on the module path, that
    the OpenTelemetry 1.66 OTLP exporter providers load their senders through the humboldt-otel-interop
    `ComponentLoader` (BUG-20261004-01). Logged, not fixed (module design decision).

---

## BUG-20261004-04 — the OTLP exporter's compressor registry still uses the default ComponentLoader on the module path

- **Date**: 2026-10-04
- **Status**: OPEN
- **Component**: humboldt-otel-context (`module io.opentelemetry.context`, which holds
  `io.opentelemetry.common.ServiceLoaderComponentLoader`); seen with `opentelemetry-exporter-otlp` 1.66
- **Affected**: humboldt 0.4.0-SNAPSHOT (OpenTelemetry 1.66 upgrade, branch `pr/ybl/mp-7.2`)
- **Symptom**: the OpenTelemetry 1.66 OTLP exporter providers hand `ConfigProperties.getComponentLoader()` to
  their builders before anything else (`OtlpConfigUtil.configureOtlpExporterBuilder`), and the builders resolve
  their `HttpSenderProvider`/`GrpcSenderProvider` (`SenderUtil`) and a configured compressor
  (`CompressorUtil.validateAndResolveCompressor(name, componentLoader)`) through it — so the humboldt-otel-interop
  loader of BUG-20261004-01 covers them. But the static initializer of `CompressorUtil` first builds a default
  registry with `ComponentLoader.forClassLoader(CompressorUtil.class.getClassLoader()).load(Compressor.class)`,
  i.e. through `ServiceLoaderComponentLoader` in the explicit module `io.opentelemetry.context`, which declares no
  `uses io.opentelemetry.sdk.common.export.Compressor`. On the module path, setting
  `otel.exporter.otlp.compression` (or the per-signal key) to any value — even `none` — fails:
  `ServiceConfigurationError: io.opentelemetry.sdk.common.export.Compressor: module io.opentelemetry.context does
  not declare 'uses'`, then `NoClassDefFoundError: Could not initialize class ...CompressorUtil` for every later
  exporter.
- **Minimal reproduction**: the scratch harness of BUG-20261004-03, with `io.opentelemetry.api.internal`
  exported through the layer controller: no compression → the exporter is created (sender found through the
  interop loader); `otel.exporter.otlp.compression=gzip` → the `ServiceConfigurationError` above;
  `...=none` → `NoClassDefFoundError`. For comparison, a `ConfigProperties` keeping the upstream default loader
  fails on the sender lookup (`HttpSenderProvider: module io.opentelemetry.context does not declare 'uses'`).
- **Cause hypothesis**: same root cause as BUG-20261004-01 — a `ServiceLoader.load` issued from
  `io.opentelemetry.context` — reached through a static default that no `ConfigProperties` can override.
  `Module.addUses` can only be called from inside `io.opentelemetry.context`, and a static
  `uses ... Compressor` there would need `io.opentelemetry.sdk.common` at resolution time.
- **Suggested fix** (needs a decision: it replaces a class of the repackaged upstream code): ship, in
  humboldt-otel-context, a `io.opentelemetry.common.ServiceLoaderComponentLoader` whose `load` calls
  `ServiceLoaderComponentLoader.class.getModule().addUses(spiClass)` before `ServiceLoader.load` (excluding the
  upstream class from the shaded jar). That also covers OpenTelemetry components built directly on the module
  path without `setComponentLoader(...)` — the residual noted in BUG-20261004-01. Only useful together with
  BUG-20261004-03, without which the exporter fails earlier.
- **Investigations**:
  - 2026-10-04: found during Task FC3 of the MicroProfile 7.2 upgrade (`javap -c` of
    `opentelemetry-exporter-otlp`/`-exporter-common` 1.66.0 and the scratch harness above). Logged, not fixed.
