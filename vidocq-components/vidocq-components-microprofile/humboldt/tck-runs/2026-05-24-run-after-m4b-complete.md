# Run TCK MP Telemetry 2.1 — full M4b SDK metric (+21 cumulative PASS)

**Date** : 2026-05-24 15:05
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="56" failed="6" skipped="23">
```

## M4b progress

| Run | total | PASS | FAIL | SKIP | Contribution |
|---|---|---|---|---|---|
| post Customizer | 85 | 33 | 29 | 23 | — |
| post 2a (MetricExporter bridge) | 85 | 35 | 27 | 23 | +2 LongCounter/DoubleHistogram |
| post 2b (Counter/UpDown/Gauge L+D + Observable) | 85 | 43 | 19 | 23 | +8 new instruments |
| post 2c.1 (JVM metrics + LoggingExporter format) | 85 | 48 | 14 | 23 | +5 cpu/gc/thread |
| post 2c.2 (JVM UpDownCounter + memory descriptions) | 85 | 55 | 7 | 23 | +7 class/memory |
| **post HTTP histogram (full M4b)** | 85 | **56** | **6** | 23 | **+1 collectsHttpRouteFromEndAttributes** |

**Total M4b: +23 PASS** (33 → 56).

## Tests unblocked (recap)

### Long+Double instruments (M4b phase 2)
- ✅ testLongCounter, testDoubleCounter
- ✅ testLongHistogram, testDoubleHistogram
- ✅ testLongUpDownCounter, testDoubleUpDownCounter
- ✅ testLongGauge, testDoubleGauge
- ✅ testAsyncLongCounter, testAsyncDoubleCounter

### JVM auto metrics (M4b phase 2c)
- ✅ testCpuCountMetric, testCpuRecentUtilizationMetric, testCpuTimeMetric
- ✅ testGarbageCollectionCountMetric
- ✅ testThreadCountMetric
- ✅ testClassCountMetrics, testClassLoadedMetrics, testClassUnloadedMetrics
- ✅ testJvmMemoryUsedMetric, testJvmMemoryCommittedMetric
- ✅ testMemoryLimitMetric, testMemoryUsedAfterLastGcMetric

### HTTP server histogram (M4b phase 2d)
- ✅ collectsHttpRouteFromEndAttributes (humboldt-rest DoubleHistogram `http.server.request.duration`)

## Items delivered

### humboldt-sdk-metric (Phase 2 instruments)
- `DoublePointData` (extended sealed permits)
- `InstrumentType.GAUGE` added
- `DoubleSumAggregator` (ConcurrentHashMap + DoubleAdder)
- `LongLastValueAggregator` (AtomicLong)
- `DoubleLastValueAggregator` (AtomicReference<Double>)
- 6 new synchronous instruments (DoubleCounter, Long/DoubleUpDownCounter, LongHistogram, Long/DoubleGauge)
- Observable variants for the 6 + Long/Double Counter — callbacks invoked at the start of `SdkMeter.collect()`
- `LoggingMetricExporter` (format `name=X, description=Y, unit=Z, type=W` — TCK compliant)

### humboldt-runtime (Phase 2c JVM)
- `JvmMetricsBinder` (~180 LOC) — OTel SemConv 1.27+ Observable instruments via `java.lang.management.*` + `com.sun.management.OperatingSystemMXBean`:
  - Memory: `jvm.memory.used/committed/limit/used_after_last_gc` (UpDownCounter LONG_SUM)
  - CPU: `jvm.cpu.time` (DOUBLE_SUM), `jvm.cpu.count` (UpDownCounter), `jvm.cpu.recent_utilization` (DOUBLE_GAUGE)
  - Class: `jvm.class.count` (UpDownCounter), `jvm.class.loaded/unloaded` (Counter)
  - Thread: `jvm.thread.count` (UpDownCounter)
  - GC: `jvm.gc.duration` (Histogram, instrument created), `jvm.gc.duration.count` (Counter)
- `HumboldtAutoConfigure`:
  - `logging` case for OTEL_METRICS_EXPORTER → `LoggingMetricExporter.create()`
  - `OTEL_METRIC_EXPORT_INTERVAL` honored (TCK = 3s)
  - `JvmMetricsBinder.bindAll()` invoked after building the SdkMeterProvider if an exporter is active
  - module-info: `requires java.management + jdk.management`

### humboldt-tck (M4b bridge)
- `OtelMetricExporterBridge` (humboldt MetricExporter → OTel SDK MetricExporter)
- `MetricDataMapper` (humboldt MetricData → OTel SDK MetricData) — supports Counter/UpDownCounter/Gauge Long+Double + Histogram + Observable variants
- `loadMetricExporters(archive, mpProps)`: scans the SPI `ConfigurableMetricExporterProvider` in the WAR

### humboldt-rest (Phase 2d HTTP histogram)
- `HumboldtServerRequestFilter`:
  - Stores `START_NANOS_PROPERTY`, `HTTP_ROUTE_PROPERTY`, `URL_SCHEME_PROPERTY` on the request
  - Fixes `appendPathSegment` (double slash normalization `/<ctx>/` + `/span`)
- `HumboldtServerResponseFilter`:
  - `http.server.request.duration` histogram (unit s, OTel SemConv 1.27+)
  - Attrs: `http.request.method`, `http.response.status_code`, `http.route`, `url.scheme`, `error.type=<status>` if ≥400
  - Non-cached histogram (re-resolved via GlobalOpenTelemetry on each request — avoids stale cache between Arquillian deploys)

## Cumulative session summary 2026-05-23+24

| Run | PASS | Cumulative vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partial | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| post b3+jaeger | 30 | +25 |
| post bean proxies + async | 32 | +27 |
| post customizer | 33 | +28 |
| post M4b 2a (MetricExporter bridge) | 35 | +30 |
| post M4b 2b (Long+Double instruments) | 43 | +38 |
| post M4b 2c (JVM metrics) | 55 | +50 |
| **post M4b 2d (HTTP histogram)** | **56** | **+51** |

**Session cumulative: 5 → 56 PASS (+1020%)**, **60 → 6 FAIL (-90%)**.

**Applicable TCK coverage: 56/62 = 90.3%** (62 = 85 - 23 SKIP).

## Remaining — 6 FAIL

| Category | Tests | Cause |
|---|---|---|
| **JaxRsClient async (parentage)** | 2 (Async, Error) | Subtle CLIENT/SERVER parentage — assertSpanCount(3) passes but assertEquals(spanId, parentSpanId) fails |
| **JaxRsServer async** | 4 (CompletionStage, CompletionStageError, Suspend, SuspendError) | cassini-core M2h not delivered (`@Suspended AsyncResponse` + server-side `CompletionStage`) |

**The ≥95% gate** requires passing at least 3 of these 6. Async client (2) is probably more accessible than async server (which depends on cassini M2h).
