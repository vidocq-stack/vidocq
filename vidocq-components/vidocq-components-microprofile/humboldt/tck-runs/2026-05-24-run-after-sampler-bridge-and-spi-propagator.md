# Run TCK MP Telemetry 2.1 — post OtelSamplerBridge + SPI Propagator (+2 PASS)

**Date** : 2026-05-24 11:53
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="26" failed="36" skipped="23">
```

## Évolution

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post Cluster D partiel | 85 | 24 | 38 | 23 |
| **post sampler-bridge + spi-propagator** | 85 | **26** | **36** | **23** |

**+2 PASS** (24 → 26), **-2 FAIL** (38 → 36).

## Tests débloqués

| Test | Mécanisme |
|---|---|
| ✅ `SamplerSpiTest.testSampler` | OtelSamplerBridge délègue à un OTel Sampler arbitraire (supporte les samplers conditionnels comme TestSampler qui sample selon attr `SAMPLE_ME`) |
| ✅ `PropagatorSpiTest.testSPIPropagator` | Scan ConfigurablePropagatorProvider + composite avec W3C TraceContext/Baggage + fix architectural Humboldt*Filter (utilise GlobalOpenTelemetry.getPropagators() au lieu de W3CPropagators hardcodé) |

## Items livrés

### 1. OtelSamplerBridge (humboldt-tck)
- Nouvelle classe `OtelSamplerBridge` qui adapte `io.opentelemetry.sdk.trace.samplers.Sampler` → `io.vidocq.humboldt.sdk.trace.samplers.Sampler`
- Délégation 1:1 : mapping `LinkData` humboldt → OTel + appel `delegate.shouldSample()` + mapping `SamplingDecision` → `SamplingResult.Decision`
- Permet d'utiliser n'importe quel Sampler OTel custom (conditionnel, ratio, parent-based, etc.) sans réimplémenter la logique côté humboldt

### 2. HumboldtAutoConfigure overloads
- `configure(env, extraExporters, overrideSampler)` — passe un humboldt.Sampler override (utilise `parseSampler(env)` si null)
- `configure(env, extraExporters, overrideSampler, overridePropagators)` — passe aussi un `ContextPropagators` override (utilise `W3CPropagators.get()` si null)

### 3. resolveSpiSampler refactoré (HumboldtDeployableContainer)
- Avant : heuristique probe-DROP qui retournait `"always_off"` ou `"always_on"` (string envvar)
- Après : retourne directement un `humboldt.Sampler` (via OtelSamplerBridge) qui est passé à `HumboldtAutoConfigure.configure(...)`

### 4. resolveSpiPropagators (HumboldtDeployableContainer)
- Scanne `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider` dans le WAR
- Si `otel.propagators` contient un nom (ex: "test-propagator"), instancie le provider et appelle `getPropagator(MapConfigProperties(mpProps))`
- Compose un `ContextPropagators` final via `TextMapPropagator.composite(...)` avec les builtins (`tracecontext`, `baggage`) + les SPI custom

### 5. Fix architectural Humboldt*Filter (humboldt-rest)
- `HumboldtServerRequestFilter` utilisait `W3CPropagators.textMap()` hardcodé via un wrapper privé → impossible d'utiliser un propagator custom
- `HumboldtClientRequestFilter` utilisait aussi `W3CPropagators.textMap()` static
- **Fix** : les deux filters utilisent maintenant `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()` (côté server) et `otel.getPropagators().getTextMapPropagator()` (côté client)
- Le comportement par défaut reste W3C (puisque humboldt set par défaut W3CPropagators dans GlobalOpenTelemetry), mais tout propagator custom configuré est désormais appliqué automatiquement

## Validation non-régression

- Tous les tests précédents PASS conservés
- 0 FAIL nouvelle (les 36 FAIL restants étaient déjà FAIL avant)

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Cumul vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partiel | 24 | +19 |
| **post sampler-bridge + spi-propagator** | **26** | **+21** |

**Cumul session : 5 → 26 PASS (+420%)**, **60 → 36 FAIL (-40%)**.

## Restant Cluster D

| Test | Effort estimé |
|---|---|
| ❌ `CustomizerSpiTest.testCustomizer` | Gros (~300 LOC) — implémenter `AutoConfigurationCustomizer` adapter complet (6 méthodes `addXxxCustomizer` + chaînes Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider) |

## Prochaines étapes ROI

| Step | Item | Gain estimé | Effort |
|---|---|---|---|
| 1 | **M4b** SDK metric complet (Counter/Histogram/Observable Long+Double) | +24 | Très gros (~2-3j) |
| 2 | Investigation tests `RestSpan*` qui restent FAIL malgré fixture OK | +3-5 | Moyen |
| 3 | `testIntegrationWithJaxRsClientAsync` / `Error` qui restent FAIL (async non supporté en cassini-client MVP) | +2 | Moyen |
| 4 | `b3*Propagation` / `jaegerPropagation` (propagateurs non-W3C optionnels) | +3 | Petit |
| 5 | `CustomizerSpiTest` (AutoConfigurationCustomizer adapter) | +1 | Gros |
