# Run TCK MP Telemetry 2.1 — post B3 + Jaeger propagators (+4 PASS)

**Date** : 2026-05-24 11:55
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="30" failed="32" skipped="23">
```

## Évolution

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post sampler-bridge + spi-propagator | 85 | 26 | 36 | 23 |
| **post b3+jaeger** | 85 | **30** | **32** | **23** |

**+4 PASS** (26 → 30), **-4 FAIL** (36 → 32).

## Tests débloqués

| Test | Mécanisme |
|---|---|
| ✅ `B3PropagationTest.b3Propagation` | builtin `b3` → `B3Propagator.injectingSingleHeader()` |
| ✅ `B3MultiPropagationTest.b3MultiPropagation` | builtin `b3multi` → `B3Propagator.injectingMultiHeaders()` |
| ✅ `JaegerPropagationTest.jaegerPropagation` | builtin `jaeger` → `JaegerPropagator.getInstance()` |
| ✅ `RestSpanTest.span` (bonus) | Le fix architectural précédent (filtres humboldt utilisant `GlobalOpenTelemetry.getPropagators()`) débloque aussi ce test |

## Items livrés

### 1. Dep `opentelemetry-extension-trace-propagators` (humboldt-tck/pom.xml)
- Version héritée du `opentelemetry-bom`
- Fournit `B3Propagator` (single + multi headers) et `JaegerPropagator`

### 2. Builtins ajoutés dans `resolveSpiPropagators` (HumboldtDeployableContainer)
- `case "b3"` → `B3Propagator.injectingSingleHeader()`
- `case "b3multi"` → `B3Propagator.injectingMultiHeaders()`
- `case "jaeger"` → `JaegerPropagator.getInstance()`
- Combinable avec `tracecontext` et `baggage` via `TextMapPropagator.composite(...)`

### 3. Bugfix `resolveSpiPropagators`
- Avant : retournait `null` si pas de SPI provider dans le WAR → impossible d'utiliser
  les builtins seuls (B3/Jaeger sans SPI custom)
- Après : scan SPI optionnel — si pas de provider scanné, le switch sur les builtins
  est quand même évalué

## Validation non-régression

- Tous les tests précédents PASS conservés
- 0 FAIL nouvelle (les 32 FAIL restants étaient déjà FAIL avant)

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Cumul vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partiel | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| **post b3+jaeger** | **30** | **+25** |

**Cumul session : 5 → 30 PASS (+500%)**, **60 → 32 FAIL (-47%)**.

## Tests Cluster D — statut final

| Test | Statut |
|---|---|
| ✅ `ExporterSpiTest.testExporter` | PASS |
| ✅ `ResourceSpiTest.testResource` | PASS |
| ✅ `SamplerSpiTest.testSampler` | PASS |
| ✅ `PropagatorSpiTest.testSPIPropagator` | PASS |
| ✅ `B3PropagationTest.b3Propagation` | PASS |
| ✅ `B3MultiPropagationTest.b3MultiPropagation` | PASS |
| ✅ `JaegerPropagationTest.jaegerPropagation` | PASS |
| ❌ `CustomizerSpiTest.testCustomizer` | FAIL (AutoConfigurationCustomizer adapter ~300 LOC) |

**7/8 tests SPI/Propagation passent maintenant.**

## Prochaines étapes ROI

| Step | Item | Gain estimé | Effort |
|---|---|---|---|
| 1 | **M4b** SDK metric complet (Counter/Histogram/Observable Long+Double) | +24 | Très gros (~2-3j) |
| 2 | Investigation des FAIL restants (testIntegrationWithJaxRsClientAsync, Error, b3Propagation côté SERVER, RestSpan*) | +3-5 | Moyen |
| 3 | `CustomizerSpiTest` (AutoConfigurationCustomizer adapter) | +1 | Gros |
