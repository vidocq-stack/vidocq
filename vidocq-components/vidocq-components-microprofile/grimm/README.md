# Grimm

Grimm is a MicroProfile OpenAPI 4.1 implementation for the Vidocq ecosystem.

## Current status

Core implementation and CDI integration are in place, with official MicroProfile OpenAPI 4.1 TCK currently green (349/349).

## Prerequisites

- Java 25
- Maven 3.9.16

## Quick start

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./run-official-tck-mp-openapi-4.1.sh all
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
./run-official-tck-mp-openapi-4.1.sh
./run-official-tck-mp-openapi-4.1.sh all
./run-official-tck-mp-openapi-4.1.sh matrix PetStoreAppTest
./run-official-tck-mp-openapi-4.1.sh -Dtest=AnnotationScanTest
```

