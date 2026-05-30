# Run TCK MP Telemetry 2.1 — post B3 + Jaeger propagators (+4 PASS)

**Date** : 2026-05-24 11:55
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="30" failed="32" skipped="23">
```

## Progress

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post sampler-bridge + spi-propagator | 85 | 26 | 36 | 23 |
| **post b3+jaeger** | 85 | **30** | **32** | **23** |

**+4 PASS** (26 → 30), **-4 FAIL** (36 → 32).

## Tests unblocked

| Test | Mechanism |
|---|---|
| ✅ `B3PropagationTest.b3Propagation` | builtin `b3` → `B3Propagator.injectingSingleHeader()` |
| ✅ `B3MultiPropagationTest.b3MultiPropagation` | builtin `b3multi` → `B3Propagator.injectingMultiHeaders()` |
| ✅ `JaegerPropagationTest.jaegerPropagation` | builtin `jaeger` → `JaegerPropagator.getInstance()` |
| ✅ `RestSpanTest.span` (bonus) | The previous architectural fix (humboldt filters using `GlobalOpenTelemetry.getPropagators()`) also unblocks this test |

## Items delivered

### 1. Dep `opentelemetry-extension-trace-propagators` (humboldt-tck/pom.xml)
- Version inherited from `opentelemetry-bom`
- Provides `B3Propagator` (single + multi headers) and `JaegerPropagator`

### 2. Builtins added in `resolveSpiPropagators` (HumboldtDeployableContainer)
- `case "b3"` → `B3Propagator.injectingSingleHeader()`
- `case "b3multi"` → `B3Propagator.injectingMultiHeaders()`
- `case "jaeger"` → `JaegerPropagator.getInstance()`
- Can be combined with `tracecontext` and `baggage` via `TextMapPropagator.composite(...)`

### 3. `resolveSpiPropagators` bugfix
- Before: returned `null` if there was no SPI provider in the WAR → impossible to use
  builtins alone (B3/Jaeger without custom SPI)
- After: SPI scan optional — if no provider is scanned, the builtin switch
  is still evaluated

## Non-regression validation

- All previously PASS tests preserved
- 0 new FAIL (the remaining 32 FAIL were already FAIL before)

## Cumulative session summary 2026-05-23+24

| Run | PASS | Cumulative vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partial | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| **post b3+jaeger** | **30** | **+25** |

**Session cumulative: 5 → 30 PASS (+500%)**, **60 → 32 FAIL (-47%)**.

## Cluster D tests — final status

| Test | Status |
|---|---|
| ✅ `ExporterSpiTest.testExporter` | PASS |
| ✅ `ResourceSpiTest.testResource` | PASS |
| ✅ `SamplerSpiTest.testSampler` | PASS |
| ✅ `PropagatorSpiTest.testSPIPropagator` | PASS |
| ✅ `B3PropagationTest.b3Propagation` | PASS |
| ✅ `B3MultiPropagationTest.b3MultiPropagation` | PASS |
| ✅ `JaegerPropagationTest.jaegerPropagation` | PASS |
| ❌ `CustomizerSpiTest.testCustomizer` | FAIL (AutoConfigurationCustomizer adapter ~300 LOC) |

**7/8 SPI/Propagation tests now pass.**

## Next steps ROI

| Step | Item | Estimated gain | Effort |
|---|---|---|---|
| 1 | **M4b** full SDK metric (Counter/Histogram/Observable Long+Double) | +24 | Very large (~2-3d) |
| 2 | Investigate the remaining FAILs (testIntegrationWithJaxRsClientAsync, Error, b3Propagation on the SERVER side, RestSpan*) | +3-5 | Medium |
| 3 | `CustomizerSpiTest` (AutoConfigurationCustomizer adapter) | +1 | Large |
