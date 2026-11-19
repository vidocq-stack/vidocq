# Run TCK MP Telemetry 2.1 — post partial Cluster D (ResourceProvider SPI)

**Date** : 2026-05-24 01:25
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="24" failed="38" skipped="23">
```

## Progress

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post M7c.12 | 85 | 23 | 39 | 23 |
| **post Cluster D (partial)** | 85 | **24** | **38** | **23** |

**+1 PASS** (23 → 24).

## Item delivered

### `HumboldtDeployableContainer.loadResourceProviderAttrs()`
- Scans `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider` in the WAR
- For each provider, instantiates and calls `createResource(MapConfigProperties(mpProps))`
- Extracts OTel attributes as a CSV string `key1=val1,key2=val2`
- Concatenates them to `envMap`'s `OTEL_RESOURCE_ATTRIBUTES` → HumboldtAutoConfigure merges them with the humboldt Resource

### `HumboldtDeployableContainer.resolveSpiSampler()`
- Scans `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider`
- If `otel.traces.sampler` matches a provider `getName()`, instantiates the OTel Sampler
- **Probe heuristic**: calls `shouldSample()` with a dummy context → maps `DROP` → `"always_off"`, otherwise → `"always_on"`
- Limitation: only works for simple samplers (always_off/always_on). Conditional samplers (the TCK's TestSampler, which samples based on attr `SAMPLE_ME`) require a real OTel→humboldt bridge (~60 LOC + change to the HumboldtAutoConfigure.configure signature)

## Cluster D tests — detailed status

| Test | Status | Cause |
|---|---|---|
| ✅ `ExporterSpiTest.testExporter` | PASS (already since M7b.4b.3) | existing OtelSpanExporterBridge |
| ✅ `ResourceSpiTest.testResource` | **PASS (M7c.13a / this session)** | OTel attrs merged via OTEL_RESOURCE_ATTRIBUTES |
| ❌ `SamplerSpiTest.testSampler` | FAIL | TestSampler is conditional (DROP by default, RECORD_AND_SAMPLE if attr `SAMPLE_ME=true`). The probe-DROP heuristic captures the 1st case but not the 2nd (`assertTrue(span2.isSampled())` fails) |
| ❌ `CustomizerSpiTest.testCustomizer` | FAIL | Requires a complete `AutoConfigurationCustomizer` implementation (6 `addXxxCustomizer` methods + Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider chains) |
| ❌ `SPIPropagationTest.testSPIPropagator` | FAIL | Requires scanning `META-INF/services/io.opentelemetry.context.propagation.TextMapPropagator` + injection into the humboldt composite propagator |

## Cumulative session summary 2026-05-23+24

| Run | PASS | Cumulative vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| **post Cluster D partial** | **24** | **+19** |

**Session cumulative: 5 → 24 PASS (+380%)**, **60 → 38 FAIL (-37%)**.

## Next steps

| Step | Item | Estimated gain | Effort |
|---|---|---|---|
| 1 | **Complete OtelSamplerBridge** + overload HumboldtAutoConfigure.configure(...Sampler) | +1 (testSampler) | Medium (~80 LOC) |
| 2 | **AutoConfigurationCustomizer adapter** | +1 (testCustomizer) | Large (~300 LOC) |
| 3 | **SPI Propagator scanning** | +1 (testSPIPropagator) | Small-medium (~50 LOC) |
| 4 | **M4b** full SDK metric | +24 | Very large (~2-3d) |
| 5 | Investigate RestSpan/SpanDefault tests that remain FAIL | +3-5 | Medium |
