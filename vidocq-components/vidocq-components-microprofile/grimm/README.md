# Grimm

Grimm is a MicroProfile OpenAPI 4.2 implementation for the Vidocq ecosystem.

## Current status

Core implementation and CDI integration are in place, with the MicroProfile OpenAPI 4.2 TCK run green at 367/367 on 2026-10-04 against TCK 4.2-RC5 (byte-identical to the 4.2 final under ballot): the 364 tests of the official TCK plus 3 Grimm harness tests. It will be re-run on the 4.2 final.

## Prerequisites

- Java 25
- Maven 3.9.16

## Quick start

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./run-official-tck-mp-openapi-4.2.sh all
```

## Repository layout

- `grimm-core`: scanner and serialization core (pure Java)
- `grimm-cdi-vauban`: CDI integration and `/openapi` endpoint
- `grimm-bench`: JMH benchmarks
- `grimm-examples`: standalone and integrated examples
- `grimm-tck`: official MicroProfile OpenAPI TCK runner (out of reactor)

## TCK

Run the root script to install the reactor and launch the TCK harness:

```bash
./run-official-tck-mp-openapi-4.2.sh
./run-official-tck-mp-openapi-4.2.sh all
./run-official-tck-mp-openapi-4.2.sh matrix PetStoreAppTest
./run-official-tck-mp-openapi-4.2.sh -Dtest=AnnotationScanTest
```

