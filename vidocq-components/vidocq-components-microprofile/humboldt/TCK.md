# Humboldt — MicroProfile Telemetry 2.1 TCK status

## Target

100% official TCK compliance for **MicroProfile Telemetry 2.1** across the 3 suites
(tracing + metrics + logs), with `OTEL_*` / `MP_TELEMETRY_*` configuration,
propagation W3C TraceContext + Baggage.

## TCK coordinates (M7.1 audit — 2026-05-21)

✅ **Public Maven Central** — no non-public manual install required:

| Signal | Artifact |
|---|---|
| Tracing | `org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1` |
| Metrics | `org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1` |
| Logs | `org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1` |

❌ No single `microprofile-telemetry-tck:2.1` aggregator — it is split into 3.

❌ No separate `microprofile-telemetry-api:2.1` JAR — the MP Telemetry spec
2.x delegates entirely to the public OTel API (no MP-specific classes
except for the `@WithSpan` annotation from
`io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations`).

## TCK stack

**TestNG + Arquillian + ShrinkWrap** (not JUnit). The TCK brings its own
copy of `io.opentelemetry:opentelemetry-sdk` for its internal fixtures
(`InMemorySpanExporter`, etc.) — that is OK on the out-of-reactor runner side, it does not
does not pollute the Humboldt application code in production.

## Current status

**100 % PASS (2026-05-24)** — TCK MicroProfile Telemetry 2.1 (tracing suite) fully green:

```
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

| Signal | Status | Notes |
|---|---|---|
| Tracing | ✅ **85/85 PASS** | Full official TCK suite — 100% applicable |
| Metrics | ✅ SDK M4b delivered | Long/Double Counter/Gauge/Histogram + Observable + JVM metrics instruments; no dedicated TCK tests in `microprofile-telemetry-metrics-tck:2.1` |
| Logs | ✅ SDK M5b delivered | JulHandler + log bridge; `mptelemetry.tck.log.file.path` wired; no dedicated TCK tests in `microprofile-telemetry-logs-tck:2.1` |
| Baggage | ✅ W3C propagator delivered in M3 | |
| Config | ✅ OTEL_* / MP_TELEMETRY_* vars delivered in M6c | |

## M7 roadmap

### M7a — Scaffold (✅ completed)
- pom `humboldt-tck/` Model 4.0.0 standalone outside-reactor
- arquillian.xml placeholder + tck-suite.xml TestNG
- HumboldtTckSmokeTest : valide classpath + AutoConfiguredHumboldt fonctionnel
- Script `run-official-tck-telemetry-2.1.sh` at the root

### M7b — Humboldt Arquillian adapter (✅ completed)

`HumboldtDeployableContainer` + `HumboldtCdiEnricher` + `CassiniHarness` HTTP
+ `HumboldtTelemetryProducers` CDI + bridge OTel SDK `withExtraSpanExporter`.

### M7c — Run + triage (✅ completed — 85/85 PASS)

#### Final run (2026-05-24) — `mvn -Ptck-official test`

```
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

**85/85 PASS — 100%** on the official tracing suite.

Complete progression of the 2026-05-21→24 session (5 → 85 PASS, +1600%):

| Step | Action | Δ PASS |
|---|---|---|
| M7c.4 | Bump commons-io 2.16.1 — no more `Tailer.builder` error `Tailer.builder` | infra |
| M7c.1 | `HumboldtTelemetryProducers` CDI (Tracer/Span/Baggage/Meter/Logger) | +~12 |
| M7c.2 | `CassiniHarness` HTTP + `HTTPContext` port — 0 `@ArquillianResource URL` error | +~20 |
| M4b | Variants Long/Double Counter/Gauge/Histogram + Observable + JVM metrics | +23 |
| M5b | JulHandler → log bridge ; `humboldt-tck-logs.txt` wired | +3 |
| M7c.5-6 | Cyrano + cassini-client integration in the Arquillian container | +6 |
| M7c.7 | CLIENT filters in humboldt-rest (`ClientRequestFilter`/`ClientResponseFilter`) | +6 |
| M7c.8 | `http.route` via `UriInfo.getMatchedTemplates()` | +1 |
| M7c.9 | CDI `@RequestScoped` Span/Baggage proxy | +2 |
| M7c.10-12 | B3/Jaeger propagators, SPI Propagator/Sampler, auto-instrumentation MP Rest Client | +8 |
| M7c (final) | BCE `@WithSpan` + `@SpanAttribute` on parameters, `HumboldtTckExecutor` SPI | +4 |

#### Quality gate

**100 % de tests applicables PASS** ✅

## Architectural constraint

Like `cassini-tck`, `champollion-tck`, `foy-tck`: `humboldt-tck` is
**outside reactor** (POM Model 4.0.0 standalone, without `<parent>`), to work around
the ShrinkWrap Maven Resolver 3.3 incompatibility (transitive TCK deps) which
cannot parse Model 4.1.0 POMs. See root workspace `CLAUDE.md`
for details. Do not reintegrate it into the reactor.

## Challenge format (template)

When an official TCK test is disabled because of a spec interpretation
that is non-portable, a dubious TCK environment, or an out-of-scope reason:

```
### [TCK-N] NomDuTest
- **Category**: spec interpretation / TCK environment / out-of-scope
- **Date**: YYYY-MM-DD
- **Justification**: why the test is disabled / contested
- **Upstream action**: issue opened? PR proposed? link
- **When to reactivate**: reactivation condition
```

---

_No functional challenge — 100% of the official suite passes._
