# Run TCK MP Telemetry 2.1 — post bean proxies + AsyncInvoker (+2 PASS)

**Date** : 2026-05-24 12:28
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="32" failed="30" skipped="23">
```

## Évolution

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post b3+jaeger | 85 | 30 | 32 | 23 |
| **post bean proxies + async** | 85 | **32** | **30** | **23** |

**+2 PASS** (30 → 32), **-2 FAIL** (32 → 30).

## Tests débloqués

| Test | Bug corrigé |
|---|---|
| ✅ `BaggageBeanTest.baggageBeanChange` | `HumboldtCdiEnricher.resolveValue()` capturait `Baggage.current()` au moment de l'enrich — figeait la valeur. Fix : proxy dynamique qui delegate à `Baggage.current()` à chaque appel |
| ✅ `SpanBeanTest.spanBeanChange` | Idem côté Span — proxy dynamique au lieu de `Span.current()` capturée |

## Items livrés

### 1. Proxies dynamiques pour Span/Baggage
- `HumboldtCdiEnricher.resolveValue()` : retourne maintenant un `Proxy.newProxyInstance(...)` qui invoque `Span.current()` / `Baggage.current()` à **chaque** appel de méthode au lieu de capturer la valeur initiale
- Same fix dans `HumboldtTelemetryProducers` (au cas où le producer est utilisé en runtime CDI normal, hors enricher)
- Permet aux tests TCK de muter le Context après l'injection et obtenir la nouvelle valeur via l'instance injectée

### 2. CassiniAsyncInvoker (cassini-client)
- Implémentation complète de `jakarta.ws.rs.client.AsyncInvoker` (~34 méthodes)
- Délègue chaque méthode async à la méthode sync correspondante de `CassiniInvocationBuilder` via `CompletableFuture.supplyAsync(..., virtualThreadExecutor)`
- Le pipeline des filtres CLIENT (request/response) s'exécute entièrement dans le thread async — les spans `kind=CLIENT` posés par humboldt-rest sont bien créés et terminés
- Remplace l'`UnsupportedOperationException` que `CassiniInvocationBuilder.async()` lançait précédemment

## Tests qui avancent mais ne passent pas encore

| Test | État avant | État après |
|---|---|---|
| `testIntegrationWithJaxRsClientAsync` | `ConditionTimeoutException: expected [3] but found [1]` (AsyncInvoker UOE → pas d'appel HTTP → pas de spans) | `AssertionError: expected [0000000000000000] but found [...]` (3 spans collectés, mais assertion de parentage échoue) |
| `testIntegrationWithJaxRsClientError` | idem | idem |

L'AsyncInvoker fonctionne (les tests atteignent maintenant `readSpans()` avec 3 spans collectés), mais l'assertion `clientSpan.getSpanId() == serverSpan.getParentSpanId()` échoue. Probablement un problème de parentage CLIENT/SERVER lié à l'absence de Span.makeCurrent() autour de l'appel async, OU le span CLIENT humboldt-rest ne se ferme pas correctement avant que serverSpan extracts le contexte. À investiguer dans un item séparé.

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Cumul vs baseline |
|---|---|---|
| baseline | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partiel | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| post b3+jaeger | 30 | +25 |
| **post bean proxies + async** | **32** | **+27** |

**Cumul session : 5 → 32 PASS (+540%)**, **60 → 30 FAIL (-50%)**.

## Restant — catégorisation finale

| Catégorie | Tests | Effort estimé |
|---|---|---|
| **Métriques** (M4b SDK metric complet) | 23 | Très gros (~2-3j) |
| **Async server + Client async parentage** | 6 | Gros (cassini M2h + investigation parentage) |
| **Customizer SPI** (AutoConfigurationCustomizer) | 1 | Gros (~300 LOC) |

**Total restant : 30 FAIL**. Le gros morceau pour le gate 95% est M4b. Les 6 async dépendent en partie du livrable Cassini M2h (`@Suspended AsyncResponse` + `CompletionStage` server-side).
