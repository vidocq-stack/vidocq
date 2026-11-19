# Humboldt — Roadmap M0 → M9

Detailed plan: [`PLAN.md`](PLAN.md) (§13 milestones). This page is the short version to track overall progress.

## M0 — Java Modules skeleton and tooling _(in progress)_

- [x] full PLAN.md (1190 lines)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0
- [x] `humboldt-api` minimal with `module-info.java` + facade stub + SPI stubs
- [x] Workflows Forgejo (ci, pr, notify-slack, update-dep-graph, upstream-pr)
- [x] Bilingual Antora docs (en/fr)
- [x] CLAUDE.md, README FR/EN, BUG/BENCH/TCK/ROADMAP
- [ ] `git init` + first commit
- [ ] Verification build `./mvnw -ntp install -DskipTests`

## M1 — Common + Context (Virtual-Threads) _(in progress)_

- [x] `humboldt-sdk-common`: `Clock` (system), `IdGenerator` (Random128 — traceId 32 hex, spanId 16 hex), `Resource` (immutable, OTel semantic merge)
- [x] `humboldt-context`: `HumboldtContextStorageProvider` + `HumboldtContextStorage` (ThreadLocal, OTel contract compliant, log WARNING on out-of-order attach/detach)
- [x] Binding ServiceLoader : `provides` Java Modules + fallback `META-INF/services`
- [x] Tests : attach/close, nested LIFO, isolation virtual thread, propagation via `Context.wrap()`
- [ ] ADR M8 (deferred): possible migration to `ScopedValue` (JEP 506) if the memory benchmark is critical on >100K VTs

## M2 — SDK Trace _(completed 2026-05-20)_

- [x] `humboldt-sdk-trace`: `SdkTracerProvider` (builder Resource + Sampler + IdGenerator + Clock + N processors, cache Tracer by scope name), `SdkTracer`, `SdkSpanBuilder`, `SdkSpan` (mutable until end, synchronized, ignores mutations post-end)
- [x] Model data immutable : `SpanData` (record 12-field), `EventData`, `LinkData`, `StatusData`, `InstrumentationScope`
- [x] 4 Samplers: `AlwaysOn`, `AlwaysOff`, `ParentBased(root)`, `TraceIdRatioBased(ratio)` (per-trace consistency, threshold on the low 64 bits of the traceId)
- [x] `SpanProcessor` : `SimpleSpanProcessor` (synchronous, ignores unsampled), `BatchSpanProcessor` (bounded queue, **virtual thread worker**, batch on threshold/scheduleDelay/flush/shutdown)
- [x] Utility exporters: `InMemorySpanExporter` (tests), `LoggingSpanExporter` (System.getLogger)
- [x] `CompletableResultCode` — equivalent OTel SDK without dep (async result with succeed/fail/whenComplete/join)
- [x] E2E tests : 8 samplers + 9 tracer/span (parent-child, links, kind/status, events, recordException, scope cache, Resource, noParent) + 3 processors (drop unsampled, batch threshold, drain on shutdown) = **20 tests**
- [ ] TCK audit : deferred to M7 (official TCK runner, in-reactor behind the `tck` profile since 2026-07-15)

## M3 — W3C propagator + OTLP HTTP-JSON exporter _(completed 2026-05-20)_

- [x] `humboldt-propagator-w3c` : `W3CPropagators.get()` facade combining the public OTel API's W3CTraceContextPropagator + W3CBaggagePropagator (no reimplementation — both are concrete on the API side). 5 tests: composite fields, inject W3C traceparent format, extract restore remote SpanContext, baggage roundtrip, stable singleton
- [x] `humboldt-exporter-otlp-http` MVP: manual OTLP/JSON encoder (`OtlpJsonEncoder` via StringBuilder, resourceSpans/scopeSpans/spans/attributes schema, AnyValue stringValue/boolValue/intValue/doubleValue/arrayValue, minimal JSON escaping), `java.net.http.HttpClient` transport with virtual thread executor, builder (endpoint, headers, requestTimeout, connectTimeout, maxRetries), bounded exponential retry (100ms × 2^attempt, ceiling 5s) on 5xx and network errors
- [x] E2E tests with in-process JDK fake server `com.sun.net.httpserver.HttpServer`: valid OTLP/JSON POST, retry 503→503→200, custom Authorization header, capped backoff. 4 EncoderTest unit tests (single span, parentSpanId, events+links+status, JSON escaping, empty collection)
- [ ] **M3b** (deferred) : OTLP/HTTP-protobuf, transport via chappe-client, Jaeger E2E tests via testcontainers — see [`PLAN.md`](PLAN.md) §3.4
- [ ] **Tracing TCK gate** : deferred to M7 (official TCK runner, in-reactor behind the `tck` profile since 2026-07-15)

## M4 — SDK Metric (synchronous MVP) _(completed 2026-05-20)_

- [x] `humboldt-sdk-metric` MVP: `LongCounter` (monotonic, ignores negative), `DoubleHistogram` (rejects negative/NaN), builder-based `SdkMeterProvider` (Resource + N MetricReader), `SdkMeter` cache by scope. `upDownCounterBuilder`/`gaugeBuilder` throw explicit UOE.
- [x] Aggregators CUMULATIVE : `SumAggregator` (ConcurrentHashMap<Attributes, LongAdder>), `ExplicitBucketHistogramAggregator` (15 bounds by default OTel : 0/5/10/25/50/75/100/250/500/750/1000/2500/5000/7500/10000)
- [x] `PeriodicMetricReader` on a virtual thread (default scheduleDelay 60s, immediate flush() trigger, final drain on shutdown)
- [x] `OtlpHttpMetricExporter` (POST `/v1/metrics`, encoder JSON manuel, retry exponentiel bounded, executor virtual threads) + `OtlpJsonMetricEncoder` (sum + histogram + gauge, temporality int CUMULATIVE=2, isMonotonic, count string-encoded)
- [x] `InMemoryMetricExporter` for tests (does not purge on shutdown)
- [x] Refactor: `CompletableResultCode` and `InstrumentationScope` moved to humboldt-sdk-common (shared trace/metric/log building blocks, avoids cross-SDK coupling)
- [x] Tests: 17 new ones (6 SdkMeterProvider — counter/negative/histogram/Resource/cache/UOE, 1 OtlpHttpMetric E2E end-to-end with fake server) — **total 74/74 PASS**
- [ ] **M4b** (deferred) : Observable instruments (Gauge/Counter/UpDownCounter), missing Long/Double variants (DoubleCounter, LongHistogram, LongUpDownCounter), ExponentialHistogramAggregator, ViewRegistry/advice, DELTA temporality
- [ ] **Metrics TCK gate** : deferred to M7 (official TCK runner, in-reactor behind the `tck` profile since 2026-07-15)

## M5 — SDK Log _(completed 2026-05-20)_

- [x] `humboldt-sdk-log` MVP: SdkLoggerProvider (builder, scope cache, anonymous MeterBuilder-like LoggerBuilder), SdkLogger, SdkLogRecordBuilder (timestamp/observedTimestamp TimeUnit & Instant, severity Number + severityText, body, capture current Span context), immutable `LogRecordData` record
- [x] Processors: SimpleLogRecordProcessor (synchronous), BatchLogRecordProcessor (virtual thread worker, default scheduleDelay 1s, threshold/flush/shutdown drain — aligned with BatchSpanProcessor pattern)
- [x] InMemoryLogRecordExporter (tests, does not purge on shutdown)
- [x] OtlpHttpLogExporter (POST /v1/logs JDK HttpClient + VT executor, shared retry via OtlpHttpSpanExporter.computeBackoffMillis) + OtlpJsonLogEncoder (resourceLogs/scopeLogs/logRecords with severityNumber/severityText/body.stringValue/attributes/traceId/spanId/flags)
- [x] Tests: 7 SdkLoggerProvider (emit/captures span context/independent severity/scope cache/batch drain/immediate sync/timestamp TimeUnit) + 1 OtlpHttpLog E2E = **8 new** → total **82/82 PASS**
- [ ] **M5b** (deferred) : `java.util.logging` (Handler) + SLF4J (Appender) bridges → OTel — to capture existing logs without changing code calls
- [ ] **Logs TCK gate** : deferred to M7 (official TCK runner, in-reactor behind the `tck` profile since 2026-07-15)

## M6 — CDI + JAX-RS + Runtime (split into M6a/b/c)

### M6a — humboldt-cdi (`@WithSpan` interceptor) _(completed 2026-05-21)_

- [x] Annotation `@WithSpan(value, kind)` with standard Jakarta `@InterceptorBinding` — applicable to method OR type (inherited)
- [x] `WithSpanInterceptor` `@AroundInvoke` : resolves the annotation (method > class), creates the span via `Tracer.spanBuilder(name).setSpanKind(kind).startSpan()`, attaches to the Context (`try-with-resources Scope`), `recordException()` + ERROR status on Throwable, `span.end()` in finally. Priority `Interceptor.Priority.APPLICATION + 1`.
- [x] Tracer resolved via `GlobalOpenTelemetry.get()` (protected `openTelemetry()` hook — overridable for tests without global init, or future `@Inject` Tracer in M6b)
- [x] 6 tests without CDI container (synthetic InvocationContext): default name = `Class.method`, explicit value+kind SERVER, exception → ERROR status + "exception" event with stack, current span during method, parent/child traceId shared with outer span, class annotation used if method has no annotation
- [x] **Decision** : Vauban runtime integration to be validated in M6b. M6a uses standard jakarta.cdi-api + jakarta.interceptor-api, so it is compatible with any Lite or Full container.

### M6b — humboldt-rest (JAX-RS filters) _(completed 2026-05-21)_

- [x] `HumboldtServerRequestFilter` `@Provider` : `TextMapGetter<ContainerRequestContext>` that adapts `getHeaders()`, extracts via composite `W3CPropagators.textMap()`, starts a SERVER span with extracted parent, OTel attrs `http.request.method` / `url.path` (normalized leading `'/'` — OTel convention) / `url.scheme`. Span name = `{method} {path}`. Span + Scope stored via `ContainerRequestContext.setProperty(SPAN_PROPERTY / SCOPE_PROPERTY)`
- [x] `HumboldtServerResponseFilter` `@Provider` : retrieves the span, sets `http.response.status_code` long, ERROR status if ≥500 (4xx ignored — OTel HTTP semantic), closes Scope then `span.end()` in finally, cleans up properties
- [x] Tests: 6 without JAX-RS container via `java.lang.reflect.Proxy` (route ~6 used methods, defaults for the ~40 other abstract JAX-RS 4.0 methods — robust to API changes across versions). Covers: span method+path+scheme, W3C traceparent extraction, status 500 → ERROR, status 404 → UNSET, idempotent response filter without property, property cleanup
- [ ] **E2E tests via cassini standalone** (chappe transport) deferred to M7 — require cassini-snapshot in the M2 CI

### M6c — humboldt-runtime autoconfig _(completed 2026-05-21)_

- [x] `EnvConfig` : reads env vars (`SCREAMING_SNAKE_CASE`) with fallback to system properties (`lower.dot.case`). `getBoolean/getLong/getDouble` helpers with defaults. `EnvConfig.of(envMap, propMap)` constructor for tests without touching the global process
- [x] `HumboldtAutoConfigure.configure()`: assembles the complete trace+metric+log pipeline from env vars (`OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` parsing comma-separated key=value, `OTEL_EXPORTER_OTLP_ENDPOINT` with per-signal overrides `_TRACES/_METRICS/_LOGS_ENDPOINT`, `OTEL_TRACES/METRICS/LOGS_EXPORTER` ∈ `otlp|none|in-memory|logging`, `OTEL_TRACES_SAMPLER` ∈ `always_on/off|traceidratio|parentbased_*`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_EXPORTER_OTLP_HEADERS`)
- [x] `AutoConfiguredHumboldt implements OpenTelemetry, AutoCloseable`: expose getTracerProvider/MeterProvider/LogsBridge/Propagators (standard OTel interface, can be passed to `GlobalOpenTelemetry.set()` or interceptors), inMemorySpanExporter()/MetricExporter()/LogRecordExporter() for tests, flush() + shutdown() with aggregated CompletableResultCode
- [x] Auto pipeline : `in-memory` exporter → Simple processor + PeriodicMetricReader 60min ; `otlp` exporter → Batch processor + PeriodicMetricReader 60s ; `none` → no processor registered
- [x] Propagators W3C composite (TraceContext + Baggage) installed by default
- [x] Tests: 15 (8 EnvConfigTest + 7 HumboldtAutoConfigureTest) — service.name default+override, RESOURCE_ATTRIBUTES parsing 3 pairs, trace+metric+log E2E pipeline via a single `configure()`, always_off sampler, traceidratio ratio description, exporter=none disables, exposed W3C propagators traceparent+baggage
- [ ] **Deferred to M7** : `OTEL_EXPORTER_OTLP_TIMEOUT`, `OTEL_EXPORTER_OTLP_PROTOCOL` (json vs protobuf), `MP_TELEMETRY_SDK_DISABLED`, `MP_TELEMETRY_PROPAGATORS`, Ravel integration for MP Config

### M6d — MPS extension + Vauban runtime validation _(structure delivered 2026-05-21)_

- [x] `vidocq-runtime-humboldt-telemetry-extension` extension created in `vidocq` (commit `0274a62`) :
  * Maven module with pom inherited from `vidocq-runtime-core-extensions`, humboldt-runtime/cdi/rest + vauban-core + vidocq-runtime-spi deps
  * `HumboldtExtension implements VidocqExtension` priority 100 — configure() reads OTel env vars via VidocqConfiguration bridge, beforeStart() = AutoConfiguredHumboldt.configure + GlobalOpenTelemetry.set, onStop() flush + shutdown 5s
  * 13 OTel/MP_TELEMETRY_* keys bridged (SCREAMING_SNAKE + lower.dot.case)
  * Disabled via `MP_TELEMETRY_SDK_DISABLED=true`
  * ServiceLoader : `META-INF/services/io.vidocq.runtime.spi.VidocqExtension` + `provides` Java Modules
  * Complete README.md with env var table + auto instrumentation enabled (`@WithSpan` BCE, JAX-RS filters)
  * Vidocq parent POM: property `humboldt.version=0.1.0-SNAPSHOT` + 4 DM entries (3 humboldt + 1 extension)
- [x] **M6d.4** — Verify full Vidocq reactor build: SUCCESS, 19 modules ✅.
- [x] **M6d.5 Vauban runtime validation** _(completed 2026-05-21)_ — E2E test via `vidocq-runtime-it-humboldt-cassini` (new module in `vidocq/vidocq-runtime-integration-tests`, modeled on `it-rest-cassini`) : **4/4 tests PASS** on the full Vidocq reactor.
  - ✅ **@WithSpan BCE** : Vauban CDI Lite correctly runs the `BuildCompatibleExtension HumboldtBuildCompatibleExtension`, the interceptor activates on OTel `@WithSpan` beans. **PLAN.md §15.1 risk resolved**.
  - ✅ **SERVER span filter** : `humboldt-rest` captures HTTP requests with OTel HTTP semantic attrs (`url.path`, `http.response.status_code`, `kind=SERVER`).
  - ✅ **Incoming W3C propagation** : `traceparent` header → SERVER span inherits `traceId` + `parentSpanId`.
  - ✅ **ERROR status + exception** : `@WithSpan` interceptor sets `status=ERROR` + exception message on Throwable.
  - 2 adjustments were needed to make it pass: `@ApplicationScoped` on humboldt-rest filters (Cassini scans via CDI bean discovery — without scope, `@Provider` beans are not discovered), publication of static `HumboldtHolder.INSTANCE` in the MPS extension for cross-ClassLoader access from the isolated Arquillian Deployment.
  - 1 issue resolved in M6d.6 (see below).
- [x] **M6d.6** _(completed 2026-05-21)_ — Fixed missing SERVER span on thrown exception. Diagnosis: Cassini `Invoker.java:365` does `return fromJaxRs(mapped.get(), ...)` without calling response filters, violating JAX-RS spec §10.2.7. Workaround in humboldt-rest : new `HumboldtSpanFinalizer @Provider implements ExceptionMapper<Throwable>` (priority USER+1000, the most generic → only runs if NO user mapper matches) that ends the span itself (recordException + ERROR + span.end()). Strict boom test restored: **4/4 tests PASS** including the assertion on SERVER span `/trace/boom` + httpStatus=500 + status=ERROR. When the Cassini bug is fixed upstream, the workaround becomes redundant but harmless (the span will already be ended by the response filter, removeProperty makes the operation idempotent).
- [x] **M6d.7** _(completed 2026-05-21, refactored 2026-05-21 into M6d.7-bis)_ — Fix: align Vauban+Cassini with JAX-RS 4.0 spec §11.2.5 (`@Provider`/`@Path` discoverable as CDI managed beans even without explicit scope). Bug found in CI: the humboldt-rest version published on the Vidocq snapshots repo (without `@ApplicationScoped` on the filters) broke the 3 tests `with_span_bce_intercepts_cdi_method`, `rest_filter_creates_server_span_with_otel_http_attrs`, `w3c_traceparent_header_propagates_trace_id` because `VaubanBeanProvider.getResourceClasses()` iterates over the BeanManager and sees only classes annotated with a CDI scope.
  - **First attempt (M6d.7)** in `vauban-core/BeanDiscovery.java` (adding `jakarta.ws.rs.ext.Provider`/`jakarta.ws.rs.Path` to `BEAN_DEFINING_ANNOTATIONS`) → **rejected**: violates separation of concerns, Vauban core is a generic CDI container that must not know JAX-RS. Commit `vauban:82edb62` **reverted** via `vauban:9986c52`.
  - **Final solution (M6d.7-bis)** in `cassini-cdi-vauban/CassiniScopeExtension.java`: the BCE already existed for `@Path → @RequestScoped`, extended for `@Provider → @Dependent` (JAX-RS semantics: providers = singleton-equivalents). **Latent bug fixed along the way**: the BCE was NEVER discovered because `provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` was missing in both `module-info.java` AND `META-INF/services/...` — Vauban APT loads BCEs via standard `ServiceLoader.load(BuildCompatibleExtension.class)` (cf. `VaubanProcessor.java:314`), so without those declarations the BCE was inert. The `@Path` beans in `it-rest-cassini` worked by accident because they have explicit `@RequestScoped`.
  - Associated cleanup in humboldt-rest: removal of `@ApplicationScoped` workaround + dep `jakarta.cdi-api` + `requires jakarta.cdi`. **4/4 tests PASS** with `@Provider` filters only.
  - Commits: `cassini:cfbd32b` (BCE + provides + cleanup), `vauban:9986c52` (revert), `humboldt:6a580b0` (cleanup workaround).

## M7 — Official MicroProfile Telemetry 2.1 TCK

- [x] **Decision (2026-07-15)** : `humboldt-tck` moved **in-reactor behind the `tck` Maven
  profile** (TCK harmonisation across the Vidocq workspace — same pattern as the
  `vidocq-runtime-tck-*` runners and the dirac pilot). Supersedes the original
  out-of-reactor decision (M7.2 scaffold below, kept as history): the ShrinkWrap Maven
  Resolver 3.3 vs Model 4.1.0 constraint disappeared with the workspace migration to
  Maven 3.9.16 / Model 4.0.0. `humboldt-tck` now inherits `humboldt-parent`; a plain
  `mvn install` neither downloads nor runs anything TCK-related;
  `run-official-tck-telemetry-2.1.sh` stays as a thin wrapper over
  `./mvnw -Ptck,<profile> -pl humboldt-tck test`. Full suite re-verified at
  **85/85 PASS** after the move.

### M7a — Audit + scaffold _(completed 2026-05-21)_

- [x] **M7.1 audit** : TCK split into 3 artifacts (tracing/metrics/logs), all public on Maven Central — no manual install required. Stack **TestNG + Arquillian + ShrinkWrap**. Official annotation = `io.opentelemetry.instrumentation.annotations.WithSpan` (different from our `io.vidocq.humboldt.cdi.WithSpan` — aliasing in M7b)
- [x] **Scaffold M7.2** : `humboldt-tck/` out of reactor (pom Model 4.0.0 standalone, without `<parent>`) with import of 3 TCK + arquillian-testng-container + shrinkwrap-resolver + humboldt-runtime/cdi/rest + opentelemetry-sdk (used only by the TCK as fixture, does not pollute the Humboldt runtime). Profiles `tck-smoke` (default) and `tck-official`
- [x] `arquillian.xml` placeholder (M7b will add the container adapter), `tck-suite.xml` TestNG suite aggregating the 3 TCK packages
- [x] `HumboldtTckSmokeTest` : 4 tests (classpath humboldt-runtime, GlobalOpenTelemetry settable + span created via AutoConfiguredHumboldt, TCK tracing class presente, OTel @WithSpan official present) — **4/4 PASS**
- [x] `run-official-tck-telemetry-2.1.sh` at the root : install reactor → smoke (default) or `all` (M7c+)
- [x] TCK.md updated (coordinates confirmed, M7b/M7c roadmap)

### M7b — Arquillian Humboldt adapter _(in progress)_

- [x] **M7b.1** Build baseline reactor (2026-05-21) — 12 modules SUCCESS
- [x] **M7b.2** TCK audit: SPI `InMemorySpanExporterProvider` (2026-05-21) — discovery of the conflict between OTel SDK autoconfigure and Humboldt's zero-SDK philosophy. Option C decision: bridge confined to the out-of-reactor TCK runner (cf. `tasks/m7b-architecture-analysis.md`)
- [x] **M7b.3** Hook `withExtraSpanExporter` in `HumboldtAutoConfigure` (2026-05-21) — overload `configure(env, List<SpanExporter>)` which attaches one `SimpleSpanProcessor` per extra exporter. ~14 LOC + 1 test → **16/16 runtime PASS**
- [x] **M7b.4a** OTel SDK ↔ Humboldt bridge (2026-05-21) — `SpanDataMapper` (convert humboldt.SpanData → otel.SpanData via `TestSpanData.builder()`) + `OtelSpanExporterBridge` (adapts an OTel `SpanExporter` into a Humboldt `SpanExporter`). In `humboldt-tck/src/main/`. ~140 LOC + 11 tests → **15/15 humboldt-tck PASS** (4 smoke + 9 mapper + 2 bridge)
- [x] **Aliasing `io.opentelemetry.instrumentation.annotations.WithSpan`** — done as of M6a (the interceptor already uses the official annotation, not our own `io.vidocq.humboldt.cdi.WithSpan`)
- [x] **M7b.4b** From-scratch embedded Arquillian container `HumboldtDeployableContainer` (2026-05-21) — broken down into 4 incremental sub-steps:
  - **M7b.4b.1** Skeleton: start/stop lifecycle, `LoadableExtension`, `META-INF/services` (~150 LOC)
  - **M7b.4b.2** Boot Vauban CDI: extract Class<?> from the ShrinkWrap war, `VaubanContainer.builder().addBeanClass(...).build()` + `AutoConfiguredHumboldt` with hardcoded env (~80 LOC)
  - **M7b.4b.3** OTel SDK autoconfigure bridge: parse `META-INF/microprofile-config.properties`, scan `META-INF/services/...ConfigurableSpanExporterProvider`, instantiate the provider, wrap via `OtelSpanExporterBridge`, inject via hook M7b.3 (~150 LOC + `MapConfigProperties`)
  - **M7b.4b.4** `HumboldtCdiEnricher implements TestEnricher`: `@Inject` injection on the test class (special case `OpenTelemetry` → `GlobalOpenTelemetry.get()`, others → `VaubanContainer.current().select(type)`) (~70 LOC)
  - **Tests**: `HumboldtArquillianBootSmokeTest`, `HumboldtCdiBootTest`, `HumboldtOtelBridgeDeployTest`, `HumboldtCdiEnricherTest` → **21/21 PASS humboldt-tck local**
- [x] **M7b.5** first official TCK test `OpenTelemetryBeanTest` (2026-05-21) — **2/2 PASS** ! `org.eclipse.microprofile.telemetry.tracing.tck.cdi.OpenTelemetryBeanTest` (`testOpenTelemetryBean` + `testSpanAndTracer`) passes via the `tck-cdi-bean` profile (`mvn -Ptck-cdi-bean test`). First green official MP Telemetry 2.1 TCK test for Humboldt

### M7c — Full run + triage _(in progress)_

- [x] **First run** (2026-05-21) — `mvn -Ptck-official test` : **138 tests / 62 failures / 73 skipped / 3 PASS** (~5 % of the 65 applicable). Triage and plan in `TCK.md`.
- [x] **M7c.4** (2026-05-21) Bump commons-io 2.16.1 in humboldt-tck/pom.xml — `Tailer.builder` errors to 0
- [x] **M7c.1** (2026-05-21) `HumboldtTelemetryProducers` (Tracer/Span/Baggage/OpenTelemetry) in humboldt-cdi, automatically registered on every deploy. **Unblocks ~24 tests**: all CDI Metrics + all JVM* + Tracing.TracerTest + Tracing.ExporterSpiTest. Stats: 153 / 80 fails / 68 skip → ~31 real PASS (~36 % of applicable)
- [x] **M7c.3** Resolved de facto by M7c.1 (`Failed to deploy` → 0)
- [x] **M7c.2** (2026-05-21) Chappe + Cassini integrated into `HumboldtDeployableContainer` via `CassiniHarness` (~180 LOC). humboldt-rest filters wired up. HTTPContext exposed via `ProtocolMetaData`. URL errors → 0. Producers extended (Meter, Logger) + fallback in enricher for those types.
- [ ] **M7c.5+** Items long-term needed for gate ≥95 % :
  - [x] **M4b** full SDK metric (2026-05-24) — DoubleCounter, LongHistogram, Long/Double UpDownCounter, Long/Double Gauge (sync) + Observable variants for the 6 + LongCounter/DoubleCounter. New aggregators DoubleSum, Long/DoubleLastValue. `DoublePointData` added to sealed PointData. `InstrumentType.GAUGE` added. `SdkMeter.registerObservableCallback(Runnable)` invoked at the start of `collect()`. `LoggingMetricExporter` (format `name=X, description=Y, unit=Z, type=W`). `JvmMetricsBinder` (humboldt-runtime, ~180 LOC) — Observable OTel SemConv 1.27+ instruments via `java.lang.management.*` + `com.sun.management.OperatingSystemMXBean` (memory/cpu/class/thread/gc). `OtelMetricExporterBridge` + `MetricDataMapper` + `loadMetricExporters` (humboldt-tck) to bridge the WAR's `ConfigurableMetricExporterProvider` SPI. `HumboldtServerResponseFilter` Histogram `http.server.request.duration` with OTel SemConv attrs (`http.request.method`, `http.response.status_code`, `http.route`, `url.scheme`, `error.type` if ≥400). Fix `appendPathSegment` double slash. **TCK run: 33 → 56 PASS (+23), 29 → 6 FAIL.** See `tck-runs/2026-05-24-run-after-m4b-complete.md`.
  - [x] **M5b** (2026-05-23) `HumboldtJulHandler` (JUL → OTel Logger bridge, severity mapping, scope cache, idempotent) + `LoggingLogRecordExporter` (file-based, format `YYYY-MM-DD HH:MM:SS.SSS LEVEL <body> scopeInfo:<scope>:<v>` TCK-compliant) + `HumboldtAutoConfigure` wiring (`logging` case, synchronous SimpleProcessor, auto-install JUL bridge except in `in-memory` mode). Test: **JulTest 2/2 PASS** (julInfoTest, julWarnTest). **TCK run: 7→9 PASS, 50→48 FAIL.** See `tck-runs/2026-05-23-run-after-m5b.md`.
-  - [~] **M7c.5** (2026-05-23, partial) Added dep `io.vidocq.cyrano:cyrano-{api,core}` to `humboldt-tck/pom.xml` → `RestClientBuilder.newBuilder()` now resolves cyrano via `META-INF/services`. **In isolation: 3/7 PASS on RestClientSpanDefaultTest** (vs 0/7 before). **In global suite: no net gain** (PASS 16/16) — setup passes but tests fail on 2 orthogonal blockers: (a) missing `jakarta.ws.rs.client.ClientBuilder` = M7c.6, (b) shared Arquillian lifecycle between tests = M7c.13. See `tck-runs/2026-05-23-run-after-m7c5.md`.
  - [x] **M7c.6** (2026-05-23) New `cassini-client` module (~600 LOC + 6 passing unit tests) — zero-dependency `jakarta.ws.rs.client.ClientBuilder` implementation (`java.net.http.HttpClient` backend + virtual threads). Reuses `CassiniUriBuilder`/`CassiniRuntimeDelegate` from cassini-core, declares `CassiniClientRuntimeDelegate` (local subclass — Java Modules constraint). Discovered via ServiceLoader + Java Modules `provides`. **TCK run: 16/46/23 unchanged** — but **structural blocker removed** (`ClassNotFoundException: Provider for jakarta.ws.rs.client.ClientBuilder` disappears from stack traces). `RestClientSpan*Test`, `BaggageTest`, `testIntegrationWithJaxRsClient*` now reach the runtime but fail on missing CLIENT instrumentation → **M7c.7**. Architectural pivot during the commit: `cyrano-jaxrs-client` was considered then abandoned (reuse cyrano-core HTTP infra) because JAX-RS Client = Jakarta REST 4.0 spec = Cassini. See `tck-runs/2026-05-23-run-after-m7c6.md`. Also fixed `cassini/ROADMAP.md` M2d which was incorrectly marked ✅.
  - [x] **M7c.12** (2026-05-24) `HumboldtMpRestClientListener implements org.eclipse.microprofile.rest.client.spi.RestClientListener` — `onNewClient(serviceInterface, builder)` registers the **same** `HumboldtClientRequestFilter`/`ResponseFilter` filters as JAX-RS Client (M7c.7) on every MP Rest Client created via `@RegisterRestClient`/`RestClientBuilder.newBuilder()`. Auto-discovered by Cyrano via `ServiceLoader<RestClientListener>` (MP Rest Client 4.0 spec §10.2). MP Telemetry §3.2 compliant. Java Modules workaround for `microprofile-rest-client-api:4.0` without `Automatic-Module-Name` : `maven-dependency-plugin` copies the JAR into `target/javamodules/` + `-p target/javamodules` at compile time. `requires static microprofile.rest.client.api` + `provides`. TCK script runs with `-Dmaven.test.skip=true` (humboldt-rest testCompile broken by strict Java Modules mode, to fix later). **TCK run: 19 → 23 PASS (+4), 43 → 39 FAIL (-4)**. Unblocked tests: `testIntegrationWithMpRestClient`, `testIntegrationWithMpRestClientAsync`, `testIntegrationWithMpRestClientAsyncError`. See `tck-runs/2026-05-24-run-after-m7c12.md`.
  - **M7c.13** Arquillian test isolation (reset container between tests). Estimate +3-5 tests.
  - [x] **M7c.7** (2026-05-23) `HumboldtClientRequestFilter` (`@Priority(HEADER_DECORATOR)`) + `HumboldtClientResponseFilter` that create a `kind=CLIENT` span with OTel SemConv 1.27+ attrs (`http.request.method`, sanitized `url.full`, `server.address`, `server.port`, `http.response.status_code`, ERROR status if ≥400). Inject W3C `traceparent` into outgoing headers via Propagators. `HumboldtClientTracingFeature implements Feature` auto-discovered by `CassiniClientBuilder` via `ServiceLoader<Feature>` (cassini-client commit #4 = auto-discovery + Java Modules `uses Feature`). MP Telemetry §3.2 compliant. **Validation after HBT-1 fix: `testIntegrationWithJaxRsClient` PASS** — auto-instrumentation works end-to-end. See `tck-runs/2026-05-24-run-after-hbt1-fix.md`.
  - [x] **HBT-1** (2026-05-24) Fix Cassini BCE @Path → @RequestScoped runtime + RequestScope activation workaround: `HumboldtDeployableContainer.deploy()` now explicitly declares `CassiniScopeExtension.class` via `addBeanClass()` (Vauban then applies the @Enhancement BCEs to the WAR's @Path classes). `CassiniHarness` wraps the Handler in a `RequestScopeActivatingHandler` that activates/deactivates `VaubanContainer.requestContext()` around each HTTP dispatch (local workaround — proper fix to be done in cassini-cdi-vauban, tracked as HBT-2). **TCK run: 16 → 19 PASS (+3), 46 → 43 FAIL (-3)**. Unblocked tests: BaggageTest.baggage, TestApplication.rest, RestClientSpanTest.testIntegrationWithJaxRsClient. See `tck-runs/2026-05-24-run-after-hbt1-fix.md` and `BUG.md`.
  - [x] **M7c.8** (2026-05-23) HumboldtServerRequestFilter sets 7 OTel SemConv 1.27+ attrs (http.request.method, http.route with URL_PATH fallback, url.path/query/scheme reconstructed via baseUri, server.address/port). Span name = templated `"METHOD route"` when ResourceInfo is non-null, otherwise full path. TCK bytecode reverse engineering + @Path introspection. **TCK run: 5→7 PASS, 60→50 FAIL.** See `tck-runs/2026-05-23-run-after-m7c8.md`.
  - [x] **M7c.9** (2026-05-23) Patch `cassini-core/Invoker.invokeInternal` : set `CURRENT_MATCH/REQUEST` ThreadLocal + call `injectProviderContexts` before each post-matching filter. `@Context ResourceInfo` is now populated in singleton filters. **Validation: Cassini Jakarta REST 4.0 TCK = 2670/2670 PASS, zero regression across the 2535 applicable tests.** **Humboldt TCK run: 9→10 PASS, 48→47 FAIL** (+1 net: `RestSpanTest.spanName` unblocked — templated http.route `/{name}` correctly propagated). The 5-8 estimate was optimistic; other failures depend on orthogonal bugs (`otel.sdk.disabled` not honored → M7c.11). See `tck-runs/2026-05-23-run-after-m7c9.md`.
  - [x] **M7c.11** (2026-05-23) `HumboldtAutoConfigure` honors `OTEL_SDK_DISABLED` with default `true` per MP Telemetry 2.1 §3.1. If disabled: providers are built without processors → OTel API usable but nothing exported. `HumboldtAutoConfigureTest` updated (explicit `OTEL_SDK_DISABLED=false` everywhere) + 2 new tests on the disabled default. **TCK run: 10→16 PASS, 47→41 FAIL (+6 net, as estimated).** Unblocked tests: 3 RestSpanDefaultTest + 3 RestSpanDisabledTest. See `tck-runs/2026-05-23-run-after-m7c11.md`.
  - [x] **Full Cluster D autoconfigure SPI** (2026-05-24) **8/8 SPI+Propagation tests pass.** Scan the WAR for `ResourceProvider`, `ConfigurableSamplerProvider`, `ConfigurablePropagatorProvider`, `AutoConfigurationCustomizerProvider`. ResourceProvider: attrs CSV-concatenated to `OTEL_RESOURCE_ATTRIBUTES`. SamplerProvider: `OtelSamplerBridge` (OTel Sampler → humboldt Sampler) passed via new overload `HumboldtAutoConfigure.configure(env, extras, overrideSampler, overridePropagators)`. PropagatorProvider: composite `ContextPropagators` with builtins `tracecontext`/`baggage`/`b3`/`b3multi`/`jaeger` (opentelemetry-extension-trace-propagators deps) + custom SPI. AutoConfigurationCustomizerProvider: `HumboldtAutoConfigurationCustomizer` (~200 LOC) implements the OTel SDK interface, collects 7 chains (Resource/Propagator/Properties/PropertiesSupplier/Sampler/SpanExporter/TracerProvider); applied at the right point in the pipeline (Resource/Propagator/Properties = real transformation, Sampler/SpanExporter/TracerProvider = side-effect invocation only — complete humboldt→OTel SDK bridges out of scope). **Related architectural fix**: `HumboldtServer/ClientRequestFilter` now use `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()` instead of hardcoded `W3CPropagators.textMap()`. **TCK run: 23 → 33 PASS (+10 cumulative)** : testResource + testSampler + testSPIPropagator + b3Propagation + b3MultiPropagation + jaegerPropagation + RestSpanTest.span + testCustomizer + BaggageBeanTest + SpanBeanTest. See `tck-runs/2026-05-24-run-after-{cluster-d-partiel,sampler-bridge-and-spi-propagator,b3-jaeger,bean-proxies-and-async,customizer}.md`.
  - [x] **M7c.10** (formerly M7c.9) CDI Span/Baggage proxy (2026-05-24) — `HumboldtCdiEnricher.resolveValue()` now returns a `java.lang.reflect.Proxy` that delegates to `Span.current()` / `Baggage.current()` on every call instead of capturing the value at enrichment time. Symmetric change in `HumboldtTelemetryProducers` (producers `@Produces Span` / `@Produces Baggage` also use proxies). **TCK run: 30 → 32 PASS (+2 : BaggageBeanTest.baggageBeanChange + SpanBeanTest.spanBeanChange).** See `tck-runs/2026-05-24-run-after-bean-proxies-and-async.md`.
- [ ] **Quality gate** : ≥95 % of applicable tests pass (reachable after M4b + M5b + M7c.5→9)

## M8 — Perf & ADRs

- [ ] JMH benchmarks vs SmallRye Telemetry + OTel SDK Java reference
- [ ] ADR-001 (static codegen), ADR-002 (hand-rolled protobuf if needed)
- [ ] Performance table published in BENCH.md

## M9 — Docs + release 1.0

- [ ] Complete Antora (all pages in `docs/{en,fr}/modules/ROOT/pages/`)
- [ ] Migration guide from SmallRye Telemetry
- [ ] Release `1.0.0`

---

For the details of the risks, dependencies, and technical tradeoffs of each milestone: see [`PLAN.md`](PLAN.md).
