# grimm-tck

Out-of-reactor runner for the official MicroProfile OpenAPI 4.1 TCK.

## Why this module is out of reactor

`grimm-tck/pom.xml` stays on Maven model `4.0.0` to avoid the ShrinkWrap Maven Resolver incompatibility with model `4.1.0`.

## Local prerequisites

1. Install the reactor artifacts in your local repository.
2. Install the non-public TCK artifact `org.eclipse.microprofile.openapi:microprofile-openapi-tck:4.1` in local M2.

## Commands

From the repository root:

```bash
./run-official-tck-mp-openapi-4.1.sh
./run-official-tck-mp-openapi-4.1.sh all
./run-official-tck-mp-openapi-4.1.sh matrix PetStoreAppTest
./run-official-tck-mp-openapi-4.1.sh -Dtest=AnnotationScanTest
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

