# Humboldt :: TCK Runner

**MicroProfile Telemetry 2.1** conformance harness — runs the official
Eclipse TCK against the Humboldt implementation.

> **STANDALONE** Maven project (Model Version 4.0.0, without `<parent>`),
> intentionally OUTSIDE the Humboldt reactor. ShrinkWrap constraint
> documented in the `pom.xml` and the workspace CLAUDE.md.

## M7 status — SCAFFOLD (2026-05-21)

✅ **Completed**:
- TCK coordinates confirmed public on Maven Central:
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1`
  - No single `microprofile-telemetry-tck:2.1` aggregator — it is split.
- TCK stack: **TestNG + Arquillian + ShrinkWrap** (not JUnit).
- The runner's Model 4.0.0 `pom.xml` resolves all its dependencies.
- `HumboldtTckSmokeTest` validates that Humboldt runtime + TCK classes + OTel
  instrumentation annotation are on the classpath.

🚧 **Coming in M7b**:
- **Humboldt Arquillian adapter** = compose Vauban (CDI Lite, PLAN §15.1
  validation) + Cassini (JAX-RS via Chappe) + Humboldt runtime to
  run the TCK tests' ShrinkWrap `@Deployment`s.
- **`@WithSpan` aliasing**: the TCK uses
  `io.opentelemetry.instrumentation.annotations.WithSpan` — our Humboldt
  interceptor must also intercept this official annotation (in addition to
  our `io.vidocq.humboldt.cdi.WithSpan`).
- **`InMemorySpanExporterProvider` SPI**: the TCK imports its own version
  via `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.SdkTracerProviderConfigurer`
  (to be verified) — `humboldt-runtime` will need to honor this contract.

🚧 **Coming in M7c**:
- First run of the TCK suite (`-Ptck-official`)
- Identification of tests that pass vs those that fail
- Documentation of **TCK challenges** (disabled tests with justification) in `humboldt/TCK.md`
- Quality gate: applicable TCK score ≥ 95% (~target goal)

## Usage

```bash
# From humboldt/ (above this folder)
./run-official-tck-telemetry-2.1.sh         # smoke (HumboldtTckSmokeTest only)
./run-official-tck-telemetry-2.1.sh all     # full TCK suite (M7c+)
./run-official-tck-telemetry-2.1.sh -Dtest=BasicAppTest  # targeted test
```

The script performs:
1. `mvn install -DskipTests` on the parent Humboldt reactor (to install
   the local M2 snapshots 0.1.0 that the standalone runner will consume)
2. `cd humboldt-tck && mvn ...` (according to the activated profile)

## Structure

```
humboldt-tck/
├── pom.xml                                      # Model 4.0.0 standalone
├── README.md                                    # this file
├── src/
│   ├── main/java/io/vidocq/humboldt/tck/        # possible M7b extensions
│   └── test/
│       ├── java/io/vidocq/humboldt/tck/
│       │   └── HumboldtTckSmokeTest.java        # autonomous smoke, without container
│       └── resources/
│           ├── arquillian.xml                   # Arquillian config (M7b)
│           └── tck-suite.xml                    # TestNG suite aggregating the 3 TCKs
└── target/
```

## Why out-of-reactor?

See the `pom.xml` header and `humboldt/CLAUDE.md` (section "Critical
architecture constraint: out-of-reactor TCK runners"). Summary: ShrinkWrap Maven Resolver 3.3
(mandatory transitive dependency of the Arquillian TCK) relies on maven-resolver
1.9 / maven-model 3.9, which cannot parse the reactor's Model 4.1.0 POMs.
Its `ClasspathWorkspaceReader` scans the current reactor and crashes on any
Humboldt POM (implicit version via parent).

The same pattern is documented in cassini-tck, foy-tck, champollion-tck.
