# Grimm

Grimm is a MicroProfile OpenAPI 4.1 implementation for the Vidocq ecosystem.

## Current status

This repository is at bootstrap milestone M0. It currently contains project scaffolding and contribution guidance.

## Prerequisites

- Java 25
- Maven 4.0.0-rc-5

## Quick start

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
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
./run-official-tck-mp-openapi-4.1.sh -Dtest=AnnotationScanTest
```

