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
./run-official-tck-mp-openapi-4.1.sh -Dtest=AnnotationScanTest
```

