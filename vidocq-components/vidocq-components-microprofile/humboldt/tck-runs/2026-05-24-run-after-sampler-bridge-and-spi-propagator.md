# Run TCK MP Telemetry 2.1 — post OtelSamplerBridge + SPI Propagator (+2 PASS)

**Date** : 2026-05-24 11:53
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="26" failed="36" skipped="23">
```

## Progress

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post Cluster D partial | 85 | 24 | 38 | 23 |
| **post sampler-bridge + spi-propagator** | 85 | **26** | **36** | **23** |

**+2 PASS** (24 → 26), **-2 FAIL** (38 → 36).

## Tests unblocked

| Test | Mechanism |
|---|---|
| ✅ `SamplerSpiTest.testSampler` | OtelSamplerBridge delegates to an arbitrary OTel Sampler (supports conditional samplers like TestSampler that sample based on attr `SAMPLE_ME`) |
| ✅ `PropagatorSpiTest.testSPIPropagator` | Scan ConfigurablePropagatorProvider + composite with W3C TraceContext/Baggage + Humboldt*Filter architectural fix (uses GlobalOpenTelemetry.getPropagators() instead of hardcoded W3CPropagators) |

## Items delivered

### 1. OtelSamplerBridge (humboldt-tck)
- New `OtelSamplerBridge` class that adapts `io.opentelemetry.sdk.trace.samplers.Sampler` → `io.vidocq.humboldt.sdk.trace.samplers.Sampler`
- 1:1 delegation: maps humboldt `LinkData` → OTel + calls `delegate.shouldSample()` + maps `SamplingDecision` → `SamplingResult.Decision`
- Allows using any custom OTel Sampler (conditional, ratio, parent-based, etc.) without reimplementing the logic on the humboldt side

### 2. HumboldtAutoConfigure overloads
- `configure(env, extraExporters, overrideSampler)` — passes a humboldt.Sampler override (uses `parseSampler(env)` if null)
- `configure(env, extraExporters, overrideSampler, overridePropagators)` — also passes a `ContextPropagators` override (uses `W3CPropagators.get()` if null)

### 3. `resolveSpiSampler` refactored (HumboldtDeployableContainer)
- Before: probe-DROP heuristic returning `"always_off"` or `"always_on"` (envvar string)
- After: returns a `humboldt.Sampler` directly (via OtelSamplerBridge) passed to `HumboldtAutoConfigure.configure(...)`

### 4. `resolveSpiPropagators` (HumboldtDeployableContainer)
- Scans `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider` in the WAR
- If `otel.propagators` contains a name (e.g. "test-propagator"), instantiates the provider and calls `getPropagator(MapConfigProperties(mpProps))`
- Composes a final `ContextPropagators` via `TextMapPropagator.composite(...)` with builtins (`tracecontext`, `baggage`) + custom SPI ones

### 5. Humboldt*Filter architectural fix (humboldt-rest)
- `HumboldtServerRequestFilter` used hardcoded `W3CPropagators.textMap()` through a private wrapper → impossible to use a custom propagator
- `HumboldtClientRequestFilter` also used static `W3CPropagators.textMap()`
- **Fix**: both filters now use `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()` (server side) and `otel.getPropagators().getTextMapPropagator()` (client side)
- Default behavior remains W3C (since humboldt sets W3CPropagators by default in GlobalOpenTelemetry), but any configured custom propagator is now automatically applied

## Non-regression validation

- All previously PASS tests preserved
- 0 new FAIL (the remaining 36 FAIL were already FAIL before)

## Cumulative session summary 2026-05-23+24

| Run | PASS | Cumulative vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partial | 24 | +19 |
| **post sampler-bridge + spi-propagator** | **26** | **+21** |

**Session cumulative: 5 → 26 PASS (+420%)**, **60 → 36 FAIL (-40%)**.

## Remaining Cluster D

| Test | Estimated effort |
|---|---|
| ❌ `CustomizerSpiTest.testCustomizer` | Large (~300 LOC) — implement full `AutoConfigurationCustomizer` adapter (6 `addXxxCustomizer` methods + Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider chains) |

## Next steps ROI

| Step | Item | Estimated gain | Effort |
|---|---|---|---|
| 1 | **M4b** full SDK metric (Counter/Histogram/Observable Long+Double) | +24 | Very large (~2-3d) |
| 2 | Investigate `RestSpan*` tests still FAIL despite fixture OK | +3-5 | Medium |
| 3 | `testIntegrationWithJaxRsClientAsync` / `Error` still FAIL (async unsupported in cassini-client MVP) | +2 | Medium |
| 4 | `b3*Propagation` / `jaegerPropagation` (optional non-W3C propagators) | +3 | Small |
| 5 | `CustomizerSpiTest` (AutoConfigurationCustomizer adapter) | +1 | Large |
