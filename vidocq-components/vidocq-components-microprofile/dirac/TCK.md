# Dirac — MicroProfile Metrics 5.1.1 TCK Status

## Target

100% conformance to official **MicroProfile Metrics 5.1.1** TCK — counters, timers,
gauges, histograms, `/metrics` endpoint OpenMetrics + JSON, CDI integration.

## TCK Coordinates

✅ **Public Maven Central** — no manual install required:

| Artifact |
|---|
| `org.eclipse.microprofile.metrics:microprofile-metrics-tck:5.1.1` |

## Execution

```bash
./run-official-tck-mp-metrics-5.1.sh           # smoke test (DiracTckSmokeTest, outside Arquillian)
./run-official-tck-mp-metrics-5.1.sh all       # full suite (TestNG + Arquillian)
./run-official-tck-mp-metrics-5.1.sh -Dtest=TestName
```

Report generated in `dirac-tck/target/tck-report.txt`.

## Runner Architecture

| Component | Role |
|---|---|
| `DiracDeployableContainer` | Arquillian *embedded* container — boots Vauban CDI + Cassini JAX-RS on Chappe |
| `DiracArquillianExtension` | SPI `LoadableExtension` — registers the container |
| Vauban CDI | Resolves `@Inject MetricRegistry`, `@Counted`/`@Timed` interceptors, BCE `DiracExtension` |
| Cassini + Chappe | Exposes `/metrics` (OpenMetrics + JSON) on random port; `@ArquillianResource URL` wired |

**Out-of-reactor constraint**: `dirac-tck` (standalone POM Model 4.0.0, no `<parent>`)
to work around ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — same constraint as
`cassini-tck`, `champollion-tck`, `foy-tck`, `humboldt-tck`. Do not reintegrate into reactor.

## Current Score

```
Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
```

**127/127 PASS — 100%** (report from 2026-05-24, commit `48f125b`).

```text
# Tests passed: 127/127
RESULT: PASS
```

Summary of M8 work that led to 100%:

- Resolution of `@Counted`/`@Timed` interceptors on inheritance, stereotypes, and proxy bridge methods
- "removed metric" behavior during intercepted invocations (expected by TCK)
- Bootstrap of Arquillian deployments with `microprofile-config.properties` values scoped per archive
- `DiracExtension` BCE: auto-discovery of `@Gauge`, `@Counted`, `@Timed`, `@RegistryType`

## Known Challenges

None — 100% of applicable tests pass, no disabled tests.

## Architecture Constraint

`dirac-tck/pom.xml` remains in **Model 4.0.0** standalone (without `<parent>`).
See `CLAUDE.md` at workspace root for justification (ShrinkWrap Maven Resolver 3.3).
