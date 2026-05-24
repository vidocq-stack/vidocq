# Run TCK MP Telemetry 2.1 — M4b SDK metric complet (+21 PASS cumulés)

**Date** : 2026-05-24 15:05
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="56" failed="6" skipped="23">
```

## Évolution M4b

| Run | total | PASS | FAIL | SKIP | Apport |
|---|---|---|---|---|---|
| post Customizer | 85 | 33 | 29 | 23 | — |
| post 2a (MetricExporter bridge) | 85 | 35 | 27 | 23 | +2 LongCounter/DoubleHistogram |
| post 2b (Counter/UpDown/Gauge L+D + Observable) | 85 | 43 | 19 | 23 | +8 nouveaux instruments |
| post 2c.1 (JVM metrics + LoggingExporter format) | 85 | 48 | 14 | 23 | +5 cpu/gc/thread |
| post 2c.2 (JVM UpDownCounter + memory descriptions) | 85 | 55 | 7 | 23 | +7 class/memory |
| **post HTTP histogram (M4b complet)** | 85 | **56** | **6** | 23 | **+1 collectsHttpRouteFromEndAttributes** |

**Total M4b : +23 PASS** (33 → 56).

## Tests débloqués (récap)

### Instruments Long+Double (M4b phase 2)
- ✅ testLongCounter, testDoubleCounter
- ✅ testLongHistogram, testDoubleHistogram
- ✅ testLongUpDownCounter, testDoubleUpDownCounter
- ✅ testLongGauge, testDoubleGauge
- ✅ testAsyncLongCounter, testAsyncDoubleCounter

### JVM metrics auto (M4b phase 2c)
- ✅ testCpuCountMetric, testCpuRecentUtilizationMetric, testCpuTimeMetric
- ✅ testGarbageCollectionCountMetric
- ✅ testThreadCountMetric
- ✅ testClassCountMetrics, testClassLoadedMetrics, testClassUnloadedMetrics
- ✅ testJvmMemoryUsedMetric, testJvmMemoryCommittedMetric
- ✅ testMemoryLimitMetric, testMemoryUsedAfterLastGcMetric

### HTTP server histogram (M4b phase 2d)
- ✅ collectsHttpRouteFromEndAttributes (humboldt-rest DoubleHistogram `http.server.request.duration`)

## Items livrés

### humboldt-sdk-metric (Phase 2 instruments)
- `DoublePointData` (sealed permits étendu)
- `InstrumentType.GAUGE` ajouté
- `DoubleSumAggregator` (ConcurrentHashMap + DoubleAdder)
- `LongLastValueAggregator` (AtomicLong)
- `DoubleLastValueAggregator` (AtomicReference<Double>)
- 6 nouveaux instruments synchrones (DoubleCounter, Long/DoubleUpDownCounter, LongHistogram, Long/DoubleGauge)
- Observable variants pour les 6 + Long/Double Counter — callbacks invoqués au début de `SdkMeter.collect()`
- `LoggingMetricExporter` (format `name=X, description=Y, unit=Z, type=W` — TCK compliant)

### humboldt-runtime (Phase 2c JVM)
- `JvmMetricsBinder` (~180 LOC) — Observable instruments OTel SemConv 1.27+ via `java.lang.management.*` + `com.sun.management.OperatingSystemMXBean` :
  - Memory : `jvm.memory.used/committed/limit/used_after_last_gc` (UpDownCounter LONG_SUM)
  - CPU : `jvm.cpu.time` (DOUBLE_SUM), `jvm.cpu.count` (UpDownCounter), `jvm.cpu.recent_utilization` (DOUBLE_GAUGE)
  - Class : `jvm.class.count` (UpDownCounter), `jvm.class.loaded/unloaded` (Counter)
  - Thread : `jvm.thread.count` (UpDownCounter)
  - GC : `jvm.gc.duration` (Histogram, instrument créé), `jvm.gc.duration.count` (Counter)
- `HumboldtAutoConfigure` :
  - case "logging" pour OTEL_METRICS_EXPORTER → `LoggingMetricExporter.create()`
  - `OTEL_METRIC_EXPORT_INTERVAL` respecté (TCK = 3s)
  - `JvmMetricsBinder.bindAll()` invoqué après build du SdkMeterProvider si un exporter est actif
  - module-info : `requires java.management + jdk.management`

### humboldt-tck (bridge M4b)
- `OtelMetricExporterBridge` (humboldt MetricExporter → OTel SDK MetricExporter)
- `MetricDataMapper` (humboldt MetricData → OTel SDK MetricData) — supports Counter/UpDownCounter/Gauge Long+Double + Histogram + Observable variants
- `loadMetricExporters(archive, mpProps)` : scan SPI `ConfigurableMetricExporterProvider` dans le WAR

### humboldt-rest (Phase 2d HTTP histogram)
- `HumboldtServerRequestFilter` :
  - Stocke `START_NANOS_PROPERTY`, `HTTP_ROUTE_PROPERTY`, `URL_SCHEME_PROPERTY` au request
  - Fix `appendPathSegment` (normalisation double slash `/<ctx>/` + `/span`)
- `HumboldtServerResponseFilter` :
  - Histogram `http.server.request.duration` (unit s, OTel SemConv 1.27+)
  - Attrs : `http.request.method`, `http.response.status_code`, `http.route`, `url.scheme`, `error.type=<status>` si ≥400
  - Histogram non-caché (re-résolu par GlobalOpenTelemetry à chaque request — évite cache figé entre deploys Arquillian)

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Cumul vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partiel | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| post b3+jaeger | 30 | +25 |
| post bean proxies + async | 32 | +27 |
| post customizer | 33 | +28 |
| post M4b 2a (MetricExporter bridge) | 35 | +30 |
| post M4b 2b (instruments Long+Double) | 43 | +38 |
| post M4b 2c (JVM metrics) | 55 | +50 |
| **post M4b 2d (HTTP histogram)** | **56** | **+51** |

**Cumul session : 5 → 56 PASS (+1020%)**, **60 → 6 FAIL (-90%)**.

**Couverture TCK applicable : 56/62 = 90.3 %** (62 = 85 - 23 SKIP).

## Restants — 6 FAIL

| Catégorie | Tests | Cause |
|---|---|---|
| **JaxRsClient async (parentage)** | 2 (Async, Error) | Parentage CLIENT/SERVER subtil — passe assertSpanCount(3) mais assertEquals(spanId, parentSpanId) échoue |
| **JaxRsServer async** | 4 (CompletionStage, CompletionStageError, Suspend, SuspendError) | cassini-core M2h non livré (`@Suspended AsyncResponse` + `CompletionStage` server-side) |

**Le gate ≥95 %** demande à passer au moins 3 de ces 6. Le client async (2) est probablement plus accessible que le server async (qui dépend de cassini M2h).
