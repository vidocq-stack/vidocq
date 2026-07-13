# Knock ↔ Cassini Integration

> How to expose MicroProfile Health 4.0 endpoints from a Cassini application
> (Jakarta REST 4.0) via the `knock-cassini` module.

## Overview

`knock-cassini` provides a single JAX-RS resource, `KnockHealthResource`, annotated
`@ApplicationScoped` + `@Path("/health")` (with sub-paths `/live`, `/ready`,
`/started`). No internal Cassini classes are imported — only the standard
`jakarta.ws.rs` API.

```
┌────────────────────────────────────────────────┐
│  Cassini (Jakarta REST 4.0)                    │
│   └─ scans @Path CDI beans                     │
│      └─ KnockHealthResource (knock-cassini)    │
│         └─ @Inject HealthCheckRegistry         │
│            └─ KnockCdiHealthCheckRegistry      │
│               (knock-cdi-vauban)               │
│               └─ delegates to KnockHealthService │
│                  (knock-core, JSON-P/champollion) │
└────────────────────────────────────────────────┘
```

## Maven dependencies

```xml
<!-- compile: JAX-RS resource, BCE, registry -->
<dependency>
    <groupId>io.vidocq.knock</groupId>
    <artifactId>knock-cassini</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>io.vidocq.knock</groupId>
    <artifactId>knock-cdi-vauban</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- runtime: Jakarta JSON-P implementation used by knock-core -->
<dependency>
    <groupId>io.vidocq.champollion</groupId>
    <artifactId>champollion-jsonp</artifactId>
    <version>0.2.0</version>
    <scope>runtime</scope>
</dependency>
```

`knock-cassini` transitively pulls `knock-core` and `knock-api` (which re-exports the
MP Health 4.0 spec). No dependency on Cassini *runtime*: Knock depends
only on `jakarta.ws.rs` (API) and works with any conformant JAX-RS 4.0 implementation.

## Java Modules

```java
module my.app {
    requires io.vidocq.knock.cassini;     // pulls knock-core transitively
    requires io.vidocq.knock.cdi.vauban;  // BCE for @Liveness/@Readiness/@Startup discovery
    requires io.vidocq.cassini.api;       // bootstrap CassiniStack (host side)
    requires jakarta.cdi;
    requires jakarta.ws.rs;
}
```

Knock's CDI integration exposes `HealthCheckRegistrar` and `KnockCdiHealthCheckRegistry`
to Vauban through the generated `VaubanComponentProvider`
(`provides io.vidocq.vauban.api.VaubanComponentProvider` in the `module-info` of
`knock-cdi-vauban`). No Build Compatible Extension is required: registration happens on the
`@Initialized(ApplicationScoped.class)` event.

## Discovery / deployment

On Cassini bootstrapped via `CassiniStack.builder().beanProvider(vaubanBeanProvider)`:

1. Vauban scans the classpath and instantiates `KnockHealthResource` as
   `@ApplicationScoped` (CDI 4.1 `annotated` mode — no `beans.xml` needed).
2. Cassini queries `BeanProvider.getResourceClasses()` and finds `KnockHealthResource`.
3. On the first `GET /health/live`, Cassini resolves the instance via Vauban, triggers
   `@Inject HealthCheckRegistry`, and invokes the JAX-RS method.
4. `KnockHealthService` (SPI runtime facade from `knock-core`) calls all registered
   `HealthCheck` instances in parallel (virtual threads), aggregates statuses per
   MP Health 4.0 spec §3 (DOWN if ≥ 1 DOWN), serialises to JSON-P via
   champollion, and the resource builds the `Response` with HTTP 200/503.

## Endpoints

| Verb | Path               | Probes                | HTTP UP | HTTP DOWN |
|------|--------------------|-----------------------|---------|-----------|
| GET  | `/health`          | all (`ProbeType.ALL`) | 200     | 503       |
| GET  | `/health/live`     | `@Liveness`           | 200     | 503       |
| GET  | `/health/ready`    | `@Readiness`          | 200     | 503       |
| GET  | `/health/started`  | `@Startup`            | 200     | 503       |

## Example: declaring an application health check

```java
package com.example.app.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class DatabaseLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        // ... ping JDBC, etc.
        return HealthCheckResponse.named("database")
                .status(true)
                .withData("responseTime", "12ms")
                .build();
    }
}
```

No manual registration: `HealthCheckRegistrar` discovers all beans
qualified `@Liveness` / `@Readiness` / `@Startup` and registers them in the registry
when the application context is initialised. A `HealthCheck` bean without a probe
qualifier is not a procedure and is silently ignored (spec §4.2).

## Configuration

Knock is *zero-config*: no MP Config properties are required. If the application
wants to isolate endpoints behind a prefix, it is up to the JAX-RS host (Cassini or other)
to decide via its `ApplicationPath` or the Chappe mount (`vidocq.rest.context-path`
on the `vidocq` side).

## Verification

A smoke test of the resource (without an HTTP container) is provided in
`KnockHealthResourceTest` (7/7 PASS) — it uses a minimal local `TestRuntimeDelegate`
to bypass all internal Cassini imports. For an end-to-end check
against real Cassini, see the `vidocq-runtime-knock-health-extension`
(`docs/integration-vidocq-runtime.md`).

## MicroProfile Health 4.0 TCK

`./run-official-tck-mp-health-4.0.sh all` runs the official TCK
`microprofile-health-tck:4.0` against the full Knock + Cassini stack:
**28/28 PASS** (see `knock-tck/target/tck-report.txt`).

