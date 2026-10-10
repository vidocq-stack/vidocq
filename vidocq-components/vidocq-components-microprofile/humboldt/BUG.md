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
- **Status**: FIXED (commits cdd8901 and a2a7b55, 2026-10-07)
- **Affected**: humboldt 0.1.0-SNAPSHOT → 0.3.0
- **Fix**: cdd8901 removed the `maven-dependency-plugin` copy into `target/javamodules/` and the manual
  `--module-path` compiler arguments (Maven resolves the module path natively). a2a7b55 replaced the
  non-modular `microprofile-rest-client-api` (automatic module `microprofile.rest.client.api`, the reason
  for the late module-info compilation) with cyrano's `io.vidocq.cyrano.mp.rest.client.api`, and moved
  `module-info.java` back to `src/main/java` (humboldt#18).
- **Evidence**: `grep -n "javamodules\|module-path" humboldt-rest/pom.xml` finds nothing, and with the
  descriptor in `src/main/java` and no `useModulePath` override, the `humboldt-rest` tests run on the module path.
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
- **Fix**: the suggested one. `MapConfigProperties.getComponentLoader()` returned an `InteropComponentLoader`
  owned by humboldt-otel-interop (since removed: BUG-20261004-04 fixed the default loader, which
  `MapConfigProperties` now returns), which called `Module.addUses(spiClass)` on its own module, then
  `ServiceLoader.load(spiClass, classLoader)`. `OtelSpiAutoConfiguration` gives the SPI providers and
  customizers properties whose loader searches the discovery ClassLoader (the OpenTelemetry autoconfigure
  does the same); `new MapConfigProperties(map)` keeps the upstream default (the autoconfigure SPI's class
  loader). The OpenTelemetry OTLP exporter providers pass `config.getComponentLoader()` to their builders
  (`OtlpConfigUtil.configureOtlpExporterBuilder`, seen in the exporter-otlp 1.62 bytecode — the newest in the
  local repository), so exporters created through the SPI get the module-path-safe loader for their
  `HttpSenderProvider` lookup. Out of scope, still upstream behaviour: an OpenTelemetry exporter built directly on the
  module path without `setComponentLoader(...)` uses `ComponentLoader.forClassLoader(...)` and hits the same
  error; such code must pass a loader of its own. (Closed since by BUG-20261004-04: humboldt's own
  `ServiceLoaderComponentLoader` makes that default work on the module path too.)
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

## BUG-20261004-03 — OpenTelemetry SDK and exporter jars cannot use the internal packages of `io.opentelemetry.api` and `io.opentelemetry.context` on the module path

- **Date**: 2026-10-04
- **Status**: FIXED (commits 12ed18f and 87808e5 on branch `pr/ybl/mp-7.2`, 2026-10-04). The first commit,
  cd3b6ed, fixed `io.opentelemetry.api.internal` only: an export still failed (see the investigations).
- **Component**: humboldt-otel-api (`module io.opentelemetry.api`) and humboldt-otel-context
  (`module io.opentelemetry.context`): `src/main/moditect/module-info.java`; humboldt-otel-interop
  (`OtelSpiAutoConfiguration`)
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
  - 2026-10-04 (Task FC4): the maintainer chose a qualified export. `jdeps -verbose:class` (with the 1.66 API on
    the class path, `--multi-release 25`) over every jar of `opentelemetry-bom` 1.66.0 and
    `opentelemetry-bom-alpha` 1.66.0-alpha, cross-checked by a scan of the class constant pools: the stable
    artifacts that reference `io.opentelemetry.api.internal` are `opentelemetry-sdk-common`, `-sdk-trace`,
    `-sdk-metrics`, `-sdk-logs`, `-sdk-extension-autoconfigure-spi`, `-sdk-extension-jaeger-remote-sampler`,
    `-extension-trace-propagators`, `-exporter-common`, `-exporter-otlp-common` and `-exporter-otlp`. The
    others (`opentelemetry-sdk`, `-sdk-testing`, `-sdk-extension-autoconfigure`, `-exporter-logging`,
    `-exporter-logging-otlp`, the three senders, `-opentracing-shim`, `-extension-kotlin`) do not. Incubating
    artifacts that do (`-api-incubator`, `-sdk-extension-incubator`, `-sdk-profiles`,
    `-exporter-otlp-profiles`, `-exporter-prometheus`) are left out. Commit cd3b6ed exported that package, and
    its test only created and shut down the exporters.
  - 2026-10-04 (FC4 review): the fix was incomplete. A reviewer probe on the module path got
    `IllegalAccessError … io.opentelemetry.context does not export io.opentelemetry.context.internal.shaded` and
    `… does not export io.opentelemetry.api.impl`; since every signal marshals its resource, no export could
    succeed. `jdeps -verbose:class` over every stable jar of `opentelemetry-bom` 1.66.0, against the two Humboldt
    jars, cross-checked by a constant-pool scan, lists every non-exported package they reference:
    `io.opentelemetry.api.internal` (the ten modules above), `io.opentelemetry.api.impl` (`InstrumentationUtil`:
    `-exporter-common`, `-exporter-sender-jdk`, `-exporter-sender-okhttp`),
    `io.opentelemetry.api.trace.propagation.internal` (`W3CTraceContextEncoding`, for a non-empty trace state:
    `-exporter-otlp-common`) and `io.opentelemetry.context.internal.shaded` (`WeakConcurrentMap`, in
    `ResourceMarshaler` and `InstrumentationScopeMarshaler`: `-exporter-otlp-common`). No stable jar uses
    `io.opentelemetry.common.impl` or `io.opentelemetry.context.propagation.internal`.
  - 2026-10-04 (FC4 review): a qualified export reaches only target modules of the same module layer or of a
    parent layer, and `--add-exports` applies to the boot layer only. In the Vidocq runtime the OpenTelemetry jars
    land in the boot layer next to Humboldt's modules (`vidocq.app.path` mode: dependencies on the JVM module
    path, only the application archives in the Vauban child layer; trampoline mode: Vauban keeps automatic
    modules, and Humboldt's modules are read by kept `io.vidocq.humboldt.*` modules). An OpenTelemetry jar can
    still end up in a child layer: listed in `vidocq.app.path`, or made explicit by `vauban:modularize` and
    re-layered with the application. Reproduced by a two-layer test: the descriptor's export does not reach the
    child layer, and the export fails with the same `IllegalAccessError`.
- **Fix**:
  - The descriptors export each of those packages, qualified, to exactly the modules that reference it, named by
    their Automatic-Module-Name (cd3b6ed for `api.internal`; 12ed18f for `api.impl`,
    `api.trace.propagation.internal` and `context.internal.shaded`). A comment in each descriptor gives the
    reason, the artifact → module mapping, what the test covers, the layer rule and the re-check rule for
    OpenTelemetry upgrades. A target module that is absent at run time is ignored. ModiTect folds comments placed
    inside a `to` list into the module names (invalid descriptor), so the mappings sit above the statements.
  - 87808e5: `ApiLayerExports` (humboldt-otel-api) and `ContextLayerExports` (humboldt-otel-context), in
    packages exported to humboldt-otel-interop only, export at run time to the modules of a given layer and of its
    parents exactly the packages their module's descriptor exports to those names (`Module.addExports`, legal
    from inside the owning module). humboldt-otel-interop calls both for the layer of each OpenTelemetry provider
    it discovers, before instantiating it. On the class path nothing is done. An OpenTelemetry component the
    application builds itself, outside that discovery, in a child layer, does not get the exports.
  - Not covered: an artifact outside the lists (an incubating one) needs
    `--add-exports <module>/<package>=<consumer>` for each package it uses, which works in the boot layer only.
  - Remaining limitation (FC2 review): when humboldt-otel-interop sits in a child layer of
    `io.opentelemetry.api`/`io.opentelemetry.context`, it cannot call `ApiLayerExports`/`ContextLayerExports`
    (their packages are exported to the interop module by name, which reaches only the same layer or a parent
    layer). The provider is kept (the call is guarded and logged at FINE), but its layer's exports are not
    extended, so the exporter can later fail with `IllegalAccessError`. Workaround: keep interop in the same
    layer as the two API modules (as the Vidocq runtime does) or add the `--add-exports`. Not fixed on purpose:
    exporting the layer packages unqualified would let any module request the internal exports.
    `<module>` is the module that holds the package, read from the built descriptors: `io.opentelemetry.api`
    for `io.opentelemetry.api.*`, `io.opentelemetry.context` (humboldt-otel-context, where `opentelemetry-common`
    is shaded in) for `io.opentelemetry.context.internal.shaded` and `io.opentelemetry.common.impl`. On
    1.66.0-alpha, `opentelemetry-sdk-extension-incubator` references `context.internal.shaded` and
    `opentelemetry-api-incubator` references `common.impl` (checked in the class files).
- **Validation**: `OtlpExporterModuleLayerTest` (humboldt-otel-interop; the exporter jars are test-scope
  dependencies of that module only) defines module layers with the Humboldt explicit modules and the
  OpenTelemetry 1.66 SDK, OTLP exporter and JDK sender jars as automatic modules. It exports a span (with a trace
  state), a metric and a log record through the upstream `Otlp*ExporterProvider`s to a closed local port and
  asserts a failed `CompletableResultCode` whose cause is the connection (`IOException`), with no `Error`
  thrown or reported — once with everything in one layer, once with the exporter jars in a child layer after
  humboldt-otel-interop's discovery. It also checks that each export stays qualified. RED before 12ed18f: the
  `IllegalAccessError` on `context.internal.shaded` for the three signals; with the `api.impl` and
  `api.trace.propagation.internal` exports removed again, the span fails on `W3CTraceContextEncoding` and the
  metric and the log on `InstrumentationUtil`. RED before 87808e5: in the child layer, the exports are not there
  after discovery, and the exports fail with the `IllegalAccessError`. The other targets of the lists (the OkHttp
  sender, the Jaeger remote sampler, ...) rest on `jdeps` only. Full reactor `./mvnw -ntp clean install` and the
  official TCK (class path) green.

---

## BUG-20261004-04 — the OTLP exporter's compressor registry still uses the default ComponentLoader on the module path

- **Date**: 2026-10-04
- **Status**: FIXED (commit 5380bc3 on branch `pr/ybl/mp-7.2`, 2026-10-04)
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
  - 2026-10-04 (Task FC4): `CompressorUtil` lives in `opentelemetry-exporter-otlp` 1.66
    (`io.opentelemetry.exporter.otlp.internal`); its `<clinit>` calls
    `ComponentLoader.forClassLoader(CompressorUtil.class.getClassLoader())`, and `HttpExporterBuilder`
    defaults its `componentLoader` the same way. The upstream `ServiceLoaderComponentLoader` 1.66 (`javap`): a
    package-private, non-final class, a package-private `(ClassLoader)` constructor, `load` =
    `ServiceLoader.load(spiClass, classLoader)`, `toString` = `ServiceLoaderComponentLoader{classLoader=…}`.
- **Fix**: the suggested one. humboldt-otel-context gains `src/main/java/io/opentelemetry/common/
  ServiceLoaderComponentLoader.java` with the same contract, whose `load` first calls
  `ServiceLoaderComponentLoader.class.getModule().addUses(spiClass)` (a no-op on the class path). The shade
  keeps that class (and its source) from the module's own jar and excludes the upstream class and source from
  `opentelemetry-common`. Build adjustments in that module: `maven.javadoc.skip` (a source tree with one
  package-private class makes the snapshot/release `javadoc:jar` fail with "No public or protected classes
  found to document"; the module keeps its own empty javadoc jar, which now excludes `io/**`), and
  `opentelemetry-common` declared (optional, already shaded in) since `src/main/java` compiles against it. This
  also closes the residual of BUG-20261004-01: an OpenTelemetry component built directly on the module path
  without `setComponentLoader(...)` now finds its services.
- **Validation**: `OtlpExporterModuleLayerTest` (humboldt-otel-interop, on the shaded jar): the span exporter
  provider with `otel.exporter.otlp.compression` = `gzip` and `none`, and an `OtlpHttpSpanExporter` built
  directly with `setCompression("gzip")` (sender and compressor through the default loader) — RED before the
  fix (with BUG-20261004-03 fixed): `ServiceConfigurationError: io.opentelemetry.sdk.common.export.Compressor:
  module io.opentelemetry.context does not declare `uses`` for the three; GREEN after.
  `ServiceLoaderComponentLoaderTest` (humboldt-otel-context, class path) guards the upstream contract.
  `-Psnapshot package` of the module checked: the published sources jar holds Humboldt's source, javadoc
  skipped. Full reactor `./mvnw -ntp clean install` green.

## BUG-20261007-01 — On a pull request, the CI TCK run tested `main`'s CDI, REST and runtime modules

- **Date**: 2026-10-07
- **Status**: FIXED (branch `pr/ybl/mp-7.2`, 2026-10-07)
- **Component**: humboldt-tck (`pom.xml`)
- **Affected**: every pull request since the TCK runner came back in the reactor
- **Symptom**: humboldt#17 CI failed one Telemetry 2.2-RC3 test, `RestClientSpanTest.spanChild`
  (`code.function.name` expected `...RestClientSpanTest$SpanBean.spanChild`, found `null`), while the same
  commit passes 85/85 locally.
- **Minimal reproduction**: in a copy of the repository, `./mvnw versions:set -DnewVersion=0.4.0-SIMCI
  -DprocessAllModules=true`, `./mvnw install -DskipTests`, then `./mvnw -P tck,tck-official -pl humboldt-tck
  dependency:list -DincludeGroupIds=io.vidocq.humboldt`: `humboldt-cdi`, `-rest`, `-runtime` and `-otel-interop`
  resolve at `0.4.0-SNAPSHOT`, the rest at `0.4.0-SIMCI`.
- **Cause**: the pull-request CI renames the reactor version before installing it, then runs
  `mvn -P tck,tck-official -pl humboldt-tck test`. `humboldt-tck/pom.xml` pinned
  `<humboldt.version>0.4.0-SNAPSHOT</humboldt.version>`, which `versions:set` does not rewrite, so the four
  modules declared with it came from the snapshot published from `main` — whose `WithSpanInterceptor` predates
  `code.function.name`. A passing TCK on a pull request therefore proved nothing about those modules.
- **Fix**: `<humboldt.version>${project.version}</humboldt.version>`. Same reproduction: every Humboldt
  artifact resolves at `0.4.0-SIMCI`. A local build is unchanged (`0.4.0-SNAPSHOT`).

## BUG-20261009-01 — `@WithSpan` and the server filters are not discovered under Weld SE

- **Date**: 2026-10-09
- **Status**: FIXED (branch `fix/23-bean-archives`, 2026-10-09)
- **Component**: humboldt-cdi, humboldt-rest
- **Affected**: any CDI container that does not scan implicit bean archives (Weld SE by default)
- **Symptom**: outside Vauban, a `@WithSpan` method opens no span, `@Inject Tracer` is unsatisfied, and the
  server request/response filters and the span finalizer are not registered as CDI beans. No error is reported.
- **Minimal reproduction**: `jar tf humboldt-cdi-*.jar | grep beans.xml` and the same for `humboldt-rest`: no
  `META-INF/beans.xml`.
- **Cause**: both jars were implicit bean archives. Vauban discovers their beans anyway, so every test and the
  TCK, which run on Vauban, hid the gap (humboldt#23, umbrella Vidocq/vidocq-workspace#15).
- **Fix**: both jars ship `META-INF/beans.xml` with `bean-discovery-mode="annotated"`; every class they need
  discovered already carries a bean-defining annotation (`@Interceptor`, `@ApplicationScoped`, `@Dependent`).
  `BeanArchiveTest` in each module pins the file; it fails on `main` with `missing target/classes/META-INF/beans.xml`.
  The Weld SE and Open Liberty integration tests are in `humboldt-it-other-containers` (BUG-20261010-01, -02).

## BUG-20261010-01 — the telemetry producers do not load under Weld or Open Liberty

- **Date**: 2026-10-10
- **Status**: FIXED (branch `test/weld-openliberty-it`, 2026-10-10)
- **Component**: humboldt-cdi
- **Affected**: any CDI container other than Vauban
- **Symptom**: Weld skips `HumboldtTelemetryProducers` with an INFO message,
  `WELD-000119: ... Type io.vidocq.vauban.api.ProxyLink not found`, then fails the deployment:
  `WELD-001408: Unsatisfied dependencies for type Span with qualifiers @Default`.
- **Minimal reproduction**: `humboldt-it-weld` (`WeldPortabilityTest`) on `main`.
- **Cause**: the Vauban build weaves a `protected <init>(io.vidocq.vauban.api.ProxyLink)` entry constructor into
  every normal-scoped bean, and `vauban-api` was `provided` (`requires static`), so it is absent outside Vauban.
  Same cause as Knock's BUG-20261010-01, Heisenberg's BUG-006 and Cervantes' CERV-008.
- **Fix**: `vauban-api` is a runtime dependency of `humboldt-cdi` (plain `requires`), its Jakarta CDI dependencies
  excluded. `humboldt-it-weld`: 5 tests.

## BUG-20261010-02 — the span finalizer turns every exception into a 500, and fails on RESTEasy

- **Date**: 2026-10-10
- **Status**: FIXED (branch `test/weld-openliberty-it`, 2026-10-10)
- **Component**: humboldt-rest
- **Affected**: every Jakarta REST runtime; RESTEasy (Open Liberty, WildFly) fails outright
- **Symptom**: on Open Liberty, any exception escaping a resource, a `NotAcceptableException` or a
  `NotFoundException` included, answers 500 and logs
  `RESTEASY003880: Unable to find contextual data of type: jakarta.ws.rs.container.ContainerRequestContext`
  from `HumboldtSpanFinalizer.finalizeSpan`; the SERVER span is never ended, so it is never exported. On any
  runtime, a `WebApplicationException` without an entity (404, 406, ...) becomes a 500.
- **Minimal reproduction**: `humboldt-it-openliberty`, `webApplicationExceptionKeepsItsStatus` (500 instead of
  404) and `failingResourceRecordsTheExceptionOnItsServerSpan` (no SERVER span) on `main`.
- **Cause**: `HumboldtSpanFinalizer` is an `ExceptionMapper<Throwable>`. It injected `@Context
  ContainerRequestContext`, which Jakarta REST does not define as injectable into a provider (Cassini supplies it,
  RESTEasy does not), to end the span itself, working around a Cassini that once skipped the response filters
  after an `ExceptionMapper`; Cassini now runs them (§6.7.4). It also answered 500 for every throwable.
- **Fix**: the finalizer returns a `WebApplicationException`'s own response, records any other exception on the
  current SERVER span (made current by the request filter, on the same thread) and answers 500; the response
  filter ends the span on every runtime.
