# Run TCK MP Telemetry 2.1 — post bean proxies + AsyncInvoker (+2 PASS)

**Date** : 2026-05-24 12:28
**Command** : `./run-official-tck-telemetry-2.1.sh all`

## Raw results

```
<testng-results ignored="0" total="85" passed="32" failed="30" skipped="23">
```

## Progress

| Run | total | PASS | FAIL | SKIP |
|---|---|---|---|---|
| post b3+jaeger | 85 | 30 | 32 | 23 |
| **post bean proxies + async** | 85 | **32** | **30** | **23** |

**+2 PASS** (30 → 32), **-2 FAIL** (32 → 30).

## Tests unblocked

| Test | Fixed bug |
|---|---|
| ✅ `BaggageBeanTest.baggageBeanChange` | `HumboldtCdiEnricher.resolveValue()` captured `Baggage.current()` at enrichment time — freezing the value. Fix: dynamic proxy delegating to `Baggage.current()` on each call |
| ✅ `SpanBeanTest.spanBeanChange` | Same for Span — dynamic proxy instead of captured `Span.current()` |

## Items delivered

### 1. Dynamic proxies for Span/Baggage
- `HumboldtCdiEnricher.resolveValue()`: now returns a `Proxy.newProxyInstance(...)` that invokes `Span.current()` / `Baggage.current()` on **every** method call instead of capturing the initial value
- Same fix in `HumboldtTelemetryProducers` (in case the producer is used in normal CDI runtime, outside the enricher)
- Allows TCK tests to mutate the Context after injection and obtain the new value through the injected instance

### 2. CassiniAsyncInvoker (cassini-client)
- Full implementation of `jakarta.ws.rs.client.AsyncInvoker` (~34 methods)
- Delegates each async method to the corresponding sync method of `CassiniInvocationBuilder` via `CompletableFuture.supplyAsync(..., virtualThreadExecutor)`
- The CLIENT filter pipeline (request/response) runs entirely in the async thread — the `kind=CLIENT` spans created by humboldt-rest are properly created and ended
- Replaces the `UnsupportedOperationException` previously thrown by `CassiniInvocationBuilder.async()`

## Tests that improve but do not pass yet

| Test | State before | State after |
|---|---|---|
| `testIntegrationWithJaxRsClientAsync` | `ConditionTimeoutException: expected [3] but found [1]` (AsyncInvoker UOE → no HTTP call → no spans) | `AssertionError: expected [0000000000000000] but found [...]` (3 spans collected, but parentage assertion fails) |
| `testIntegrationWithJaxRsClientError` | same | same |

The AsyncInvoker works (the tests now reach `readSpans()` with 3 collected spans), but the assertion `clientSpan.getSpanId() == serverSpan.getParentSpanId()` fails. Likely a CLIENT/SERVER parentage issue related to the absence of `Span.makeCurrent()` around the async call, OR the humboldt-rest CLIENT span not closing correctly before serverSpan extracts the context. To investigate in a separate item.

## Cumulative session summary 2026-05-23+24

| Run | PASS | Cumulative vs baseline |
|---|---|---|
| baseline | 5 | — |
| post M7c.11 | 16 | +11 |
| post HBT-1 | 19 | +14 |
| post M7c.12 | 23 | +18 |
| post Cluster D partial | 24 | +19 |
| post sampler-bridge + spi-propagator | 26 | +21 |
| post b3+jaeger | 30 | +25 |
| **post bean proxies + async** | **32** | **+27** |

**Session cumulative: 5 → 32 PASS (+540%)**, **60 → 30 FAIL (-50%)**.

## Remaining — final categorization

| Category | Tests | Estimated effort |
|---|---|---|
| **Metrics** (full M4b SDK metric) | 23 | Very large (~2-3d) |
| **Async server + async client parentage** | 6 | Large (cassini M2h + parentage investigation) |
| **Customizer SPI** (AutoConfigurationCustomizer) | 1 | Large (~300 LOC) |

**Total remaining: 30 FAIL**. The big chunk for the 95% gate is M4b. The 6 async failures depend in part on the Cassini M2h deliverable (`@Suspended AsyncResponse` + server-side `CompletionStage`).
