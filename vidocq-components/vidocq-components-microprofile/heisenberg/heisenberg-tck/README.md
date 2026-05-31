# Heisenberg TCK — MicroProfile Fault Tolerance 4.1

Official **outside-the-reactor** runner (POM Model 4.0.0) for the TCK suite
`org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`.

## Running

From the repository root:

```bash
# Smoke test (JUnit, without Arquillian — checks the classpath)
./run-official-tck-mp-fault-tolerance-4.1.sh

# Full TCK suite (TestNG + Arquillian + embedded Heisenberg container)
./run-official-tck-mp-fault-tolerance-4.1.sh all

# Targeted test
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=FallbackTest
```

The script:

1. Installs `heisenberg-api`, `heisenberg-core`, `heisenberg-cdi-vauban` into the
   local M2 (`./mvnw install -DskipTests`).
2. Invokes `mvn -f heisenberg-tck/pom.xml -Ptck-official test [args...]`.
3. Produces `heisenberg-tck/target/tck-report.txt` (PASS/FAIL summary).

## Installing the TCK artifact

The TCK is available on Maven Central:

```bash
mvn dependency:get \
    -Dartifact=org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1
```

The `run-official-tck-mp-fault-tolerance-4.1.sh` script downloads the artifact
on demand through the POM `tck-official` Maven profile.

## Runner architecture

- `HeisenbergDeployableContainer` — local embedded Arquillian container
  (protocol `Local`, in-VM mode, no remote container).
- `VaubanTckBootstrap` — for each TCK deployment: extracts MP Config properties
  from the ShrinkWrap archive, starts a Vauban container with
  `FaultToleranceInterceptor`, `HeisenbergExtension`, `StateRegistryBean`,
  `BulkheadStateRegistryBean`, and all archive classes ; activates the
  `RequestContext` for the duration of the test.
- `HeisenbergTestEnricher` — injects `@Inject` (and `@Inject Instance<T>`)
  into fields of TCK test classes through the Vauban `BeanManager`.
- `HeisenbergArquillianExtension` — registers services through
  `META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension`.
- `arquillian.xml` — qualifier `heisenberg` (selected by
  `arquillian.launch=heisenberg`).
- `tck-suite.xml` — selection of TCK packages
  (`org.eclipse.microprofile.fault.tolerance.tck.*`).

## Timeout multiplier

For slow CI environments, increase it in the `tck-official` profile
of the POM or on the command line:

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh all \
    -Dorg.eclipse.microprofile.fault.tolerance.tck.timeout.multiplier=2.0
```

## Current status

See [`TCK.md`](../TCK.md) at the project root for the current TCK score and
for the list of excluded tests with justification.

## Why it stays outside the reactor

`heisenberg-tck/pom.xml` stays on Model 4.0.0 and **outside the reactor** because
ShrinkWrap Maven Resolver 3.3 (a transitive Arquillian dependency) uses
`maven-resolver` 1.9 / `maven-model` 3.9, which cannot parse the reactor
Model 4.1.0 POMs. Do not change this model until ShrinkWrap is updated
(common constraint across the whole Vidocq ecosystem — knock-tck,
cassini-tck, cyrano-tck, heisenberg-tck).
