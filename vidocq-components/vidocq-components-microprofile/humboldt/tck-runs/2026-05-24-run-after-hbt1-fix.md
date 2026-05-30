# Run TCK MP Telemetry 2.1 — post HBT-1 fix (BCE Cassini runtime + RequestScope)

**Date** : 2026-05-24 00:25
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="19" failed="43" skipped="23">
```

## Progress

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post M7c.11 | 85 | 16 | 41 | 28 |
| post M7c.5+6+7 | 85 | 16 | 46 | 23 |
| **post HBT-1 fix** | 85 | **19** | **43** | **23** |

**+3 PASS** (16 → 19) — first TCK gain since M7c.11.

## Tests unblocked

| Test | Comment |
|---|---|
| ✅ `BaggageTest.baggage` | CDI @Inject Baggage works → assertion `baggage.getEntryValue("user") == "naruto"` passes |
| ✅ `TestApplication.rest` | First simple REST endpoint unblocked by RequestScope activation |
| ✅ `RestClientSpanTest.testIntegrationWithJaxRsClient` | **First concrete proof that M7c.7 (humboldt-rest CLIENT filters) works** — the test verifies that a `kind=CLIENT` span is created around a JAX-RS Client request, with OTel attrs `url.full`/`server.address`/etc. The full chain (cassini-client + ServiceLoader\<Feature\> + HumboldtClientTracingFeature + HumboldtClientRequestFilter/ResponseFilter) is validated |

## Item delivered (HBT-1)

Two orthogonal causes of the initial blockage:

### 1. BCE Cassini @Path → @RequestScoped not applied at runtime
- **Symptom**: `cdi.select(BaggageResource)` throws `UnsatisfiedResolutionException` (the class has `@Path` without a scope → Vauban ignores it as a bean)
- **Cause**: `HumboldtDeployableContainer` registers WAR classes via `addBeanClass()` but never explicitly declares the `CassiniScopeExtension` BCE. Vauban has the full runtime mechanism (`BceProcessor.processEnhancementOnly` line 742 of `VaubanContainerBuilder`) to apply `@Enhancement`s to "unprocessed" classes, but only if the BCE is in the bean classes set
- **Fix**: added one line `builder.addBeanClass(CassiniScopeExtension.class)` in `HumboldtDeployableContainer.deploy()`. The BCE becomes discoverable and applies its `@Enhancement`, which adds synthetic `@RequestScoped` to `@Path` classes in the WAR

### 2. RequestScope never activated around an HTTP dispatch
- **Symptom** (after fix #1): `ContextNotActiveException: RequestScope is not active` when invoking a resource method on the `BaggageResource_ClientProxy` proxy
- **Cause**: `cassini-cdi-vauban` never activates `RequestContext` per incoming HTTP request (separate integration bug, to be tracked on the Cassini side)
- **Workaround in CassiniHarness**: new `RequestScopeActivatingHandler` that wraps the Cassini `Handler` and does `cdi.requestContext().activate()` before each dispatch + `deactivate()` in finally. Local to the humboldt TCK runner — the proper fix on the Cassini side must be done separately

## Non-regression validation

- `CassiniScopeExtension` invoked during the build: JUL log `@Path class ... has no CDI scope, defaulting to @RequestScoped` appears for each TCK resource
- No previously PASS test becomes FAIL
- The 9 tests that were already passing (julInfoTest, julWarnTest, runtimeInstance, spanName, spanNameWithoutQueryString, testExporter, testOpenTelemetryBean, testSpanAndTracer, tracer) are still PASS

## Cumulative session summary 2026-05-23+24

| Run | PASS | Delta vs baseline |
|---|---|---|
| baseline M7c.1+2+4 | 5 | — |
| post M7c.8 | 7 | +2 |
| post M5b | 9 | +2 |
| post M7c.9 | 10 | +1 |
| post M7c.11 | 16 | +6 |
| post M7c.5+6+7 | 16 | 0 (foundations) |
| **post HBT-1 fix** | **19** | **+3** |

**Total cumulative: 5 → 19 PASS (+280%)**, **60 → 43 FAIL (-28%)**.

## Next steps (updated)

| Step | Item | Estimated gain | Effort |
|---|---|---|---|
| 1 | **HBT-2** Activate RequestContext in cassini-cdi-vauban (instead of the workaround in CassiniHarness) | cassini non-regression | Medium — touches cassini-core |
| 2 | **M7c.12** cyrano CLIENT filters (MP Rest Client spans) | +5-12 (testIntegrationWithMpRestClient*) | Medium |
| 3 | **M4b** full SDK metric | +24 | Large |
| 4 | Cluster D autoconfigure SPI | +3 | Small |
| 5 | Investigate RestSpan/SpanDefault tests that remain FAIL despite the server fixture being OK | TBD | Small |
