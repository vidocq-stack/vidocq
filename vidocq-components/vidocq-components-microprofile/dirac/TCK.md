# Dirac — MicroProfile Metrics 5.1.1 TCK Status

## Target

100% conformance to official **MicroProfile Metrics 5.1.1** TCK — counters, timers,
gauges, histograms, `/metrics` endpoint OpenMetrics + JSON, CDI integration.

## TCK Coordinates

✅ **Public Maven Central** — no manual install required:

| Artifact |
|---|
| `org.eclipse.microprofile.metrics:microprofile-metrics-api-tck:5.1.1` |

## Execution

From the Dirac folder (`vidocq-components/vidocq-components-microprofile/dirac`):

```bash
./run-official-tck-mp-metrics-5.1.sh           # smoke test (DiracTckSmokeTest, outside Arquillian)
./run-official-tck-mp-metrics-5.1.sh all       # full suite (JUnit 4 + Arquillian)
./run-official-tck-mp-metrics-5.1.sh -Dtest=TestName
```

Report generated in `dirac-tck/target/tck-report.txt`.

The script first installs `dirac-api`, `dirac-core` and `dirac-cdi-vauban`, then runs
`-Ptck,<profile> -pl dirac-tck test`. Both Maven calls read and write `.tck-repository/` in this folder
(`TCK_LOCAL_REPOSITORY` moves it), above `~/.m2/repository`, which they only read: the install of a
branch never replaces the published Dirac snapshot the other builds of the machine resolve
(BUG-20261123-57). Or run the reactor directly:

```bash
./mvnw -Ptck -pl dirac-tck test                  # smoke
./mvnw -Ptck,tck-official -pl dirac-tck test     # full suite
```

A pull request runs no TCK. The `TCK nightly` workflow of the monorepo runs the full suite
(`ci.tck.command` of `dirac/pom.xml`: `mvn -B -ntp -P tck,tck-official -pl dirac-tck test`,
in this folder) once a day, on demand on a branch (component tag `DIRAC`) and for a release.
A plain `-Ptck` runs the smoke test only: the `smoke` profile of `dirac-tck` is active by default.

## Runner Architecture

| Component | Role |
|---|---|
| `DiracDeployableContainer` | Arquillian *embedded* container, `Local` protocol — the tests run in the Arquillian JVM |
| `DiracArquillianExtension` | SPI `LoadableExtension` — registers the container and the test enricher |
| `VaubanDiracTckBootstrap` | Starts a Vauban CDI container per deployment (Dirac beans + archive classes), with the archive's `microprofile-config.properties` values |
| `DiracTestEnricher` | Injects the `@Inject` fields and the test method parameters of each TCK test from that container |
| Vauban CDI | Resolves `@Inject MetricRegistry`, `@Counted`/`@Timed` interceptors, BCE `DiracExtension` |

**In-reactor, behind the `tck` profile**: `dirac-tck` has `dirac-parent` as its parent and
joins the reactor only under the `tck` Maven profile, so a plain build neither downloads nor
runs the TCK. The MP Metrics api-tck builds its deployments with `addClass`/`addPackage` and
does not use the ShrinkWrap Maven resolver.

## Current Score

The current result is the last `tck / DIRAC` job of the `TCK nightly` workflow. Last
result recorded here:

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

`dirac-tck` stays behind the `tck` profile of `dirac/pom.xml`: the release reactor never
builds it.
