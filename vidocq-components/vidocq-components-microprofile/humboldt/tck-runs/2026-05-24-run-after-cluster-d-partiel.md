# Run TCK MP Telemetry 2.1 — post Cluster D partiel (ResourceProvider SPI)

**Date** : 2026-05-24 01:25
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="24" failed="38" skipped="23">
```

## Évolution

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post M7c.12 | 85 | 23 | 39 | 23 |
| **post Cluster D (partiel)** | 85 | **24** | **38** | **23** |

**+1 PASS** (23 → 24).

## Item livré

### `HumboldtDeployableContainer.loadResourceProviderAttrs()`
- Scanne `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider` dans le WAR
- Pour chaque provider, instancie et appelle `createResource(MapConfigProperties(mpProps))`
- Extrait les attributes OTel sous forme de chaîne CSV `key1=val1,key2=val2`
- Concatène à `OTEL_RESOURCE_ATTRIBUTES` de `envMap` → HumboldtAutoConfigure les fusionne avec le Resource humboldt

### `HumboldtDeployableContainer.resolveSpiSampler()`
- Scanne `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider`
- Si `otel.traces.sampler` matche le `getName()` d'un provider, instancie le Sampler OTel
- **Heuristique de probe** : appelle `shouldSample()` avec contexte dummy → mappe `DROP` → `"always_off"`, autre → `"always_on"`
- Limite : ne fonctionne que pour les samplers simples (always_off/always_on). Les samplers conditionnels (TestSampler du TCK qui sample selon attr `SAMPLE_ME`) nécessitent un vrai bridge OTel→humboldt (~60 LOC + modification HumboldtAutoConfigure.configure signature)

## Tests Cluster D — statut détaillé

| Test | Statut | Cause |
|---|---|---|
| ✅ `ExporterSpiTest.testExporter` | PASS (déjà depuis M7b.4b.3) | bridge OtelSpanExporterBridge existant |
| ✅ `ResourceSpiTest.testResource` | **PASS (M7c.13a / cette session)** | attrs OTel mergés via OTEL_RESOURCE_ATTRIBUTES |
| ❌ `SamplerSpiTest.testSampler` | FAIL | TestSampler est conditionnel (DROP par défaut, RECORD_AND_SAMPLE si attr `SAMPLE_ME=true`). L'heuristique probe-DROP capture le 1er cas mais pas le 2ème (`assertTrue(span2.isSampled())` échoue) |
| ❌ `CustomizerSpiTest.testCustomizer` | FAIL | Requiert l'implémentation complète de `AutoConfigurationCustomizer` (6 méthodes `addXxxCustomizer` + chaînes Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider) |
| ❌ `SPIPropagationTest.testSPIPropagator` | FAIL | Requiert le scan `META-INF/services/io.opentelemetry.context.propagation.TextMapPropagator` + injection dans le composite propagator humboldt |

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Cumul vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| **post Cluster D partiel** | **24** | **+19** |

**Cumul session : 5 → 24 PASS (+380%)**, **60 → 38 FAIL (-37%)**.

## Prochaines étapes

| Step | Item | Gain estimé | Effort |
|---|---|---|---|
| 1 | **OtelSamplerBridge complet** + overload HumboldtAutoConfigure.configure(...Sampler) | +1 (testSampler) | Moyen (~80 LOC) |
| 2 | **AutoConfigurationCustomizer adapter** | +1 (testCustomizer) | Gros (~300 LOC) |
| 3 | **SPI Propagator scanning** | +1 (testSPIPropagator) | Petit-moyen (~50 LOC) |
| 4 | **M4b** SDK metric complet | +24 | Très gros (~2-3j) |
| 5 | Investigation tests RestSpan/SpanDefault qui restent FAIL | +3-5 | Moyen |
