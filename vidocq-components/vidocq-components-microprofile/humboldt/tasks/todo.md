# Humboldt — Development plan

> Summary view for tracking milestone progress. The detailed plan is in `PLAN.md` and the milestone list is in `ROADMAP.md`.

## M0 — JPMS skeleton and tooling _(completed 2026-05-20)_

- [x] PLAN.md committed (1190 lines, validated by Yann on 2026-05-20)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0, `.gitignore`, LICENSE
- [x] `humboldt-api/`: `pom.xml`, `module-info.java`, `Humboldt` facade, SPI stubs (SpanExporterProvider, MetricReaderProvider, LogRecordExporterProvider, SamplerProvider, ResourceProvider), JUnit test
- [x] `.forgejo/workflows/` : ci.yml, pr.yml, notify-slack.yml, update-dep-graph.yml, upstream-pr.yml
- [x] `docs/{en,fr}/antora.yml` + `modules/ROOT/{nav,pages/index}.adoc` with Humboldt metaphor
- [x] CLAUDE.md, README.md (FR), README_EN.md (EN), BUG.md, BENCH.md, TCK.md, ROADMAP.md
- [x] `git init` + first commit signed by Yann Blazart (`d28de83`)
- [x] Verification build `mvn verify` — **2/2 tests PASS, BUILD SUCCESS** (1.5s)

## M1 — Common + Context (Virtual-Threads) _(in progress)_

- [x] `humboldt-sdk-common`: `pom.xml`, `module-info.java`, `Clock` (system), `IdGenerator.Random128` (traceId 32 hex / spanId 16 hex via `ThreadLocalRandom`, never all-zero), `Resource` (immutable, equals/hashCode, merge OTel-style)
- [x] `humboldt-sdk-common` tests: ClockTest (3), IdGeneratorTest (4 — uniqueness over 10k iterations), ResourceTest (7)
- [x] `humboldt-context`: `pom.xml`, `module-info.java` with `provides ContextStorageProvider with HumboldtContextStorageProvider`, `META-INF/services` classpath fallback
- [x] `HumboldtContextStorage` ThreadLocal-backed, conforming to the OTel contract (attach/close/current/root), WARNING log on out-of-order attach/detach, idempotent close
- [x] Tests: provider ServiceLoader, current()=root, attach/close, nested LIFO, VT isolation (without wrap), VT propagation (with `Context.wrap()`), idempotent close
- [x] parent pom: added `<subprojects>` + dependencyManagement entries for humboldt-sdk-common and humboldt-context
- [x] Full reactor verify build → **23/23 tests PASS** (2 humboldt-api + 14 sdk-common + 7 context), commit `a7f0e86`

## M2 — SDK Trace _(completed 2026-05-20)_

- [x] Immutable model: SpanData (12-field record), EventData, LinkData, StatusData, InstrumentationScope
- [x] Interfaces: ReadableSpan, SpanExporter, SpanProcessor, CompletableResultCode (async result)
- [x] 4 Samplers: AlwaysOn, AlwaysOff, ParentBased, TraceIdRatioBased (consistent per-trace via low 64 bits of traceId)
- [x] Utility exporters: InMemorySpanExporter (tests), LoggingSpanExporter (System.getLogger)
- [x] Core: SdkTracerProvider (builder), SdkTracer, SdkSpanBuilder (4 setAttribute primitives), SdkSpan (synchronized mutable until end, all defaults Span/SpanBuilder OTel 1.39)
- [x] Processors: SimpleSpanProcessor (sync, ignores unsampled spans), BatchSpanProcessor (queue + **virtual thread worker** + threshold/scheduleDelay/flush/shutdown drain)
- [x] Tests: 20/20 (8 Sampler + 9 SdkTracerProvider + 3 SpanProcessor) — parent/child traceId share, links, kind/status, events, recordException stack, drop unsampled, batch threshold, drain shutdown
- [x] parent pom: added humboldt-sdk-trace to reactor + dependencyManagement
- [x] Verify build → **43/43 tests PASS** (2 api + 14 common + 7 context + 20 trace), M2 commit

## M3 — W3C Propagator + OTLP HTTP-JSON Exporter _(completed 2026-05-20)_

- [x] `humboldt-propagator-w3c`: W3CPropagators composite facade (TraceContext + Baggage). 5 tests: composite fields, inject/extract traceparent, baggage roundtrip
- [x] `humboldt-exporter-otlp-http`: manual OTLP/JSON encoder via StringBuilder + java.net.http.HttpClient transport + capped exponential retry. 5 EncoderTest + 4 E2E with in-process JDK fake HttpServer (valid POST, retry 503, custom headers, capped backoff)
- [x] parent pom: added both modules to reactor + dependencyManagement
- [x] Verify build → **57/57 tests PASS** (M2 43 + M3 14), 7 modules SUCCESS, 4.1s, M3 commit
- [x] Decision documented: **OTLP/HTTP-protobuf deferred to M3b** (chappe-client transport + Jaeger testcontainers tests) — M3 MVP delivers OTLP/JSON to validate the E2E pipeline architecture

## M3b — OTLP/HTTP-protobuf + chappe-client (post-MVP, conditional)

To be started when chappe-client is mature enough AND protobuf necessity is proven (TCK audit in M7). See `PLAN.md` §3.4 (decision A protobuf-java vs B hand-rolled).

## M4 — SDK Metric (synchronous MVP) _(completed 2026-05-20)_

- [x] Preliminary refactor: CompletableResultCode + InstrumentationScope moved to humboldt-sdk-common (avoids cross-SDK coupling)
- [x] `humboldt-sdk-metric`: SdkMeterProvider (builder, anonymous MeterBuilder, cache by scope), SdkMeter (functional counter+histogram builders, upDownCounter/gauge UOE), SdkLongCounter (rejects negative), SdkDoubleHistogram (rejects NaN/negative)
- [x] Aggregators: SumAggregator (LongAdder per attribute-set), ExplicitBucketHistogramAggregator (15 default buckets, synchronized record)
- [x] PeriodicMetricReader (worker virtual thread, scheduleDelay/flush/shutdown drain)
- [x] OtlpHttpMetricExporter + OtlpJsonMetricEncoder (sum/histogram/gauge with temporality int and isMonotonic), InMemoryMetricExporter for tests
- [x] Tests: 6 SdkMeterProvider + 1 OtlpHttpMetric E2E = **17 M4 tests** → **total 74/74 PASS**
- [x] M4b decision documented: Observable instruments, missing double/long, ExponentialHistogram, ViewRegistry, DELTA deferred
- [x] parent pom + exporter module-info + M4 commit

## M5 — SDK Log (MVP) _(completed 2026-05-20)_

- [x] humboldt-sdk-log skeleton + LogRecordData + interfaces (LogRecordProcessor + LogRecordExporter) + InMemoryLogRecordExporter
- [x] Core impl: SdkLoggerProvider (builder + scope cache + anonymous LoggerBuilder), SdkLogger, SdkLogRecordBuilder (collect-then-emit, captures current Span from Context if not set), Simple/Batch processors (Batch on VT worker)
- [x] OtlpHttpLogExporter (POST /v1/logs) + OtlpJsonLogEncoder (severity number+text, body.stringValue, traceId/spanId/flags if span active)
- [x] Tests: 7 SdkLoggerProvider + 1 E2E exporter = 8 new → **total 82/82 PASS**
- [x] M5b decision documented: JUL/SLF4J bridges deferred
- [x] parent pom + exporter module-info + M5 commit

## M6a — humboldt-cdi (@WithSpan interceptor) _(completed 2026-05-21)_

- [x] @WithSpan annotation (value + kind, method OR type, standard Jakarta @InterceptorBinding)
- [x] WithSpanInterceptor (@AroundInvoke, method>class resolution, recordException+ERROR status, span.end finally, APPLICATION+1 priority)
- [x] Protected openTelemetry() hook for tests/future @Inject
- [x] 6 tests without CDI container (synthetic InvocationContext, TestableInterceptor with local SdkTracerProvider)
- [x] Verify build → **88/88 tests PASS** (82 M0-M5 + 6 M6a), 10 modules SUCCESS, 6.2s
- [x] Decision: Vauban runtime validation integration in M6b

## M6b — humboldt-rest (JAX-RS filters) _(completed 2026-05-21)_

- [x] HumboldtServerRequestFilter (TextMapGetter MultivaluedMap, extract W3C traceparent, start SERVER span, OTel attrs http.request.method/url.path/url.scheme — url.path normalized with leading '/')
- [x] HumboldtServerResponseFilter (http.response.status_code long, ERROR status if ≥500, close Scope + span.end finally, cleanup properties)
- [x] Tests: 6 without JAX-RS container via java.lang.reflect.Proxy (routes the 6 used methods, defaults for the ~40 JAX-RS 4.0 API abstracts)
- [x] Verify build → **94/94 tests PASS** (88 M0-M6a + 6 M6b), 11 modules SUCCESS, 6.5s
- [ ] E2E tests via cassini deferred to M7

## M6c — humboldt-runtime autoconfig _(completed 2026-05-21)_

- [x] EnvConfig (env vars > system props, getBoolean/getLong/getDouble fallback safe)
- [x] HumboldtAutoConfigure.configure() : OTEL_SERVICE_NAME / RESOURCE_ATTRIBUTES / EXPORTER_OTLP_ENDPOINT (+ per-signal) / TRACES_SAMPLER (+ ARG) / EXPORTER_OTLP_HEADERS / TRACES|METRICS|LOGS_EXPORTER ∈ otlp|none|in-memory|logging
- [x] AutoConfiguredHumboldt implements OpenTelemetry (can be set globally) + AutoCloseable, exposes SDK providers and in-memory exporters for tests, aggregated flush()+shutdown()
- [x] Auto pipeline: Simple processor + 60min PeriodicReader for in-memory; Batch processor + 60s PeriodicReader for OTLP
- [x] W3C composite propagators installed by default
- [x] Tests: 15 (8 EnvConfig + 7 HumboldtAutoConfigure) → **total 109/109 PASS**, 12 modules SUCCESS, 7s
- [x] Deferred to M7: OTEL_EXPORTER_OTLP_TIMEOUT/PROTOCOL, MP_TELEMETRY_SDK_DISABLED, Ravel integration

## M6d — MPS extension + Vauban runtime validation _(completed 2026-05-21)_

See ROADMAP.md M6d.1 → M6d.7-bis (vidocq-runtime-humboldt-telemetry-extension out-of-reactor from humboldt, Vauban CDI Lite validation for @WithSpan, E2E REST via cassini+chappe+humboldt, SERVER span fix on exception, Cassini BCE `@Provider`/`@Path` fix).

## M7 — Official MicroProfile Telemetry 2.1 TCK

### M7a — Scaffold _(completed 2026-05-21)_

- [x] `humboldt-tck/` out-of-reactor (pom Model 4.0.0) with 3 TCK imports + arquillian + shrinkwrap + humboldt-runtime/cdi/rest + opentelemetry-sdk
- [x] `HumboldtTckSmokeTest` 4/4 PASS — classpath OK, AutoConfiguredHumboldt functional, TCK class present, official OTel `@WithSpan` present
- [x] `run-official-tck-telemetry-2.1.sh` script at the root

### M7b — Humboldt Arquillian Adapter _(in progress)_

- [x] **M7b.1** Build baseline (12 modules SUCCESS)
- [x] **M7b.2** TCK audit — Humboldt zero-SDK vs TCK expecting OTel SDK autoconfigure conflict. Decision: **Option C** (bridge confined to `humboldt-tck/` out-of-reactor) — see `tasks/m7b-architecture-analysis.md`
- [x] **M7b.3** Hook `HumboldtAutoConfigure.configure(env, List<SpanExporter>)` (~14 LOC + 1 test, 16/16 runtime PASS)
- [x] **M7b.4a** `SpanDataMapper` + `OtelSpanExporterBridge` in `humboldt-tck/src/main/` (~140 LOC + 11 tests, 15/15 humboldt-tck PASS)
- [x] **M7b.4b** `HumboldtDeployableContainer` Arquillian from-scratch (2026-05-21) — broken into 4 sub-steps M7b.4b.1→4 (skeleton, Vauban boot, OTel SDK autoconfigure bridge, CDI TestEnricher). ~450 LOC + 21/21 local humboldt-tck tests
- [x] **M7b.5** 1st official TCK test `OpenTelemetryBeanTest` (2026-05-21) — **2/2 PASS** via `mvn -Ptck-cdi-bean test`. Profile targets only `org.eclipse.microprofile.telemetry.tracing.tck.cdi.OpenTelemetryBeanTest`

## Session lessons

To be recorded as they arise in `tasks/lessons.md`.
