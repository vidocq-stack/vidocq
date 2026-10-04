# Humboldt :: TCK Runner

**MicroProfile Telemetry 2.2** conformance harness — runs the official
Eclipse TCK against the Humboldt implementation.

> **In-reactor** Maven module, behind the `tck` profile of the Humboldt root
> POM (same pattern as the `vidocq-runtime-tck-*` runners): a plain
> `./mvnw install` neither downloads nor runs any TCK. Activate it with
> `./mvnw -Ptck,<tck-smoke|tck-official> -pl humboldt-tck test`, or through
> `run-official-tck-telemetry-2.2.sh` (recommended entry point).

## M7 status — SCAFFOLD (2026-05-21)

✅ **Completed**:
- TCK coordinates confirmed public on Maven Central (MicroProfile Telemetry 2.1 at the time):
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1`
  - No single `microprofile-telemetry-tck:2.1` aggregator — it is split.
  - Since 2026-10-04 the runner uses the `2.2-RC3` version of the same three artifacts
    (MicroProfile Telemetry 2.2) — see [`../TCK.md`](../TCK.md).
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
./run-official-tck-telemetry-2.2.sh         # smoke (HumboldtTckSmokeTest only)
./run-official-tck-telemetry-2.2.sh all     # full TCK suite (M7c+)
./run-official-tck-telemetry-2.2.sh -Dtest=BasicAppTest  # targeted test
```

The script performs:
1. `./mvnw clean install -Dmaven.test.skip=true` on the Humboldt reactor
   (installs the SNAPSHOT artifacts that the runner consumes)
2. `./mvnw -Ptck,<profile> -pl humboldt-tck clean test` (`tck-official` with
   `all`, `tck-smoke` otherwise)

## Structure

```
humboldt-tck/
├── pom.xml                                      # in-reactor module (profile `tck`)
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
