# Run TCK MP Telemetry 2.1 — post HBT-1 fix (BCE Cassini runtime + RequestScope)

**Date** : 2026-05-24 00:25
**Commande** : `./run-official-tck-telemetry-2.1.sh all`

## Résultat brut

```
<testng-results ignored="0" total="85" passed="19" failed="43" skipped="23">
```

## Évolution

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post M7c.11 | 85 | 16 | 41 | 28 |
| post M7c.5+6+7 | 85 | 16 | 46 | 23 |
| **post HBT-1 fix** | 85 | **19** | **43** | **23** |

**+3 PASS** (16 → 19) — premier gain TCK depuis M7c.11.

## Tests débloqués

| Test | Comment |
|---|---|
| ✅ `BaggageTest.baggage` | CDI @Inject Baggage fonctionne → assertion `baggage.getEntryValue("user") == "naruto"` passe |
| ✅ `TestApplication.rest` | Premier endpoint REST simple débloqué par activation du RequestScope |
| ✅ `RestClientSpanTest.testIntegrationWithJaxRsClient` | **Première preuve concrète que M7c.7 (filtres CLIENT humboldt-rest) fonctionne** — le test vérifie qu'un span `kind=CLIENT` est posé autour d'une requête JAX-RS Client, avec attrs OTel `url.full`/`server.address`/etc. La chaîne complète (cassini-client + ServiceLoader\<Feature\> + HumboldtClientTracingFeature + HumboldtClientRequestFilter/ResponseFilter) est validée |

## Item livré (HBT-1)

Deux causes orthogonales du blocage initial :

### 1. BCE Cassini @Path → @RequestScoped pas appliquée en runtime
- **Symptôme** : `cdi.select(BaggageResource)` throw `UnsatisfiedResolutionException` (la classe a `@Path` sans scope → Vauban l'ignore comme bean)
- **Cause** : `HumboldtDeployableContainer` enregistre les classes du WAR via `addBeanClass()` mais ne déclare jamais explicitement la BCE `CassiniScopeExtension`. Vauban a tout le mécanisme runtime (`BceProcessor.processEnhancementOnly` ligne 742 de `VaubanContainerBuilder`) pour appliquer les `@Enhancement` aux classes "unprocessed", mais seulement si la BCE est dans le bean classes set
- **Fix** : ajout d'une ligne `builder.addBeanClass(CassiniScopeExtension.class)` dans `HumboldtDeployableContainer.deploy()`. La BCE devient discoverable et applique son `@Enhancement` qui ajoute `@RequestScoped` synthétique aux classes `@Path` du WAR

### 2. RequestScope jamais activé autour d'un dispatch HTTP
- **Symptôme** (après fix #1) : `ContextNotActiveException: RequestScope is not active` lors de l'invocation d'une méthode resource sur le proxy `BaggageResource_ClientProxy`
- **Cause** : `cassini-cdi-vauban` n'active jamais le `RequestContext` par requête HTTP entrante (bug d'intégration distinct, à traiter côté Cassini en suivi)
- **Workaround dans CassiniHarness** : nouveau `RequestScopeActivatingHandler` qui wrap le `Handler` Cassini et fait `cdi.requestContext().activate()` avant chaque dispatch + `deactivate()` en finally. Local au runner TCK humboldt — la solution propre côté Cassini doit être faite séparément

## Validation non-régression

- `CassiniScopeExtension` invoquée lors du build : log JUL `@Path class ... has no CDI scope, defaulting to @RequestScoped` apparaît pour chaque resource TCK
- Aucun test précédemment PASS ne devient FAIL
- Les 9 tests qui passaient déjà (julInfoTest, julWarnTest, runtimeInstance, spanName, spanNameWithoutQueryString, testExporter, testOpenTelemetryBean, testSpanAndTracer, tracer) sont toujours PASS

## Bilan cumulé session 2026-05-23+24

| Run | PASS | Delta vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.8 | 7 | +2 |
| post M5b | 9 | +2 |
| post M7c.9 | 10 | +1 |
| post M7c.11 | 16 | +6 |
| post M7c.5+6+7 | 16 | 0 (fondations) |
| **post HBT-1 fix** | **19** | **+3** |

**Cumul total : 5 → 19 PASS (+280%)**, **60 → 43 FAIL (-28%)**.

## Prochaines étapes (mises à jour)

| Step | Item | Gain estimé | Effort |
|---|---|---|---|
| 1 | **HBT-2** Activer RequestContext dans cassini-cdi-vauban (au lieu du workaround dans CassiniHarness) | non-régression cassini | Moyen — toucher cassini-core |
| 2 | **M7c.12** Filtres CLIENT cyrano (MP Rest Client spans) | +5-12 (testIntegrationWithMpRestClient*) | Moyen |
| 3 | **M4b** SDK metric complet | +24 | Gros |
| 4 | Cluster D autoconfigure SPI | +3 | Petit |
| 5 | Investigation tests RestSpan/SpanDefault qui restent FAIL malgré la fixture serveur OK | TBD | Petit |
