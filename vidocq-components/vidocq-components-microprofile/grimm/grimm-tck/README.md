# grimm-tck

Out-of-reactor runner for the official MicroProfile OpenAPI 4.2 TCK.

## Why this module is out of reactor

The runner is a standalone POM (model `4.0.0`, no `<parent>`, not a module of the root POM). This keeps the released runtime decoupled from the official TCK: a normal build never resolves or runs any TCK artifact. Run it with `./mvnw -f grimm-tck/pom.xml -Ptck-official clean test`, or through `run-official-tck-mp-openapi-4.2.sh`.

## Local prerequisites

1. Install the reactor artifacts in your local repository.
2. Nothing to install for the TCK itself: `org.eclipse.microprofile.openapi:microprofile-openapi-tck:4.2-RC5` (like the 4.1 TCK)
   is published on Maven Central and resolved by Maven during the run.

## Commands

From the repository root:

```bash
./run-official-tck-mp-openapi-4.2.sh
./run-official-tck-mp-openapi-4.2.sh all
./run-official-tck-mp-openapi-4.2.sh matrix PetStoreAppTest
./run-official-tck-mp-openapi-4.2.sh -Dtest=AnnotationScanTest
```

## Runtime matrix knobs

The custom Arquillian container can be tuned through JVM properties (`-Dgrimm.tck.*`) to
stabilize startup diagnostics:

- `grimm.tck.host` (default `127.0.0.1`)
- `grimm.tck.port` (default `0`, ephemeral)
- `grimm.tck.waitForReadiness` (default `true`)
- `grimm.tck.readinessTimeoutMillis` (default `10000`)
- `grimm.tck.readinessPath` (default `/openapi`)
- `grimm.tck.systemProperties` (semicolon-separated `key=value` pairs propagated before bootstrap)

