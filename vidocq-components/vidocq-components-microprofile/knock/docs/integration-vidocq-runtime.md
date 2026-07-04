# Knock ↔ Vidocq integration

> Knock is the default health check system for Vidocq. This page describes
> how to enable it, how it interacts with the other extensions, and how
> to extend it in an MPS application.

## Activation: a single dependency

The `vidocq-runtime-knock-health-extension` aggregate groups the `knock-cdi-vauban` +
`knock-cassini` + Jakarta JSON-P implementation (`champollion-jsonp`) modules.
Adding this dependency is enough to expose `/health*`:

```xml
<dependency>
    <groupId>io.vidocq.runtime.extensions.microprofile</groupId>
    <artifactId>vidocq-runtime-knock-health-extension</artifactId>
</dependency>
```

(the version is managed by the `vidocq-runtime-parent` BOM.)

This aggregate transitively depends on:

- `vidocq-runtime-cassini-rest-extension` — the extension that scans `@Path` beans
  via `BeanProvider` and builds the `CassiniStack`;
- `knock-cassini` — the `KnockHealthResource` resource (`@ApplicationScoped`,
  `@Path("/health")`);
- `knock-cdi-vauban` — the BCE that auto-discovers `@Liveness/@Readiness/@Startup`;
- `knock-core` — registry, aggregator, JSON-P serialization;
- `champollion-jsonp` — runtime implementation of `jakarta.json`.

## Startup lifecycle

```
VidocqBootstrap
 ├─ ChappeEngineExtension   (priority 100) — HTTP engine
 ├─ HealthCheckCdiExtension (Knock BCE)    — discover @Liveness/@Readiness/@Startup
 ├─ CassiniExtension        (priority 500) — scans @Path beans including KnockHealthResource
 │     └─ mount(/) on Chappe
 └─ ChappeServerBootstrap   — starts the HTTP server
```

When `CassiniExtension.onStart()` queries
`VaubanBeanProvider.getResourceClasses()`, Vauban has already:

1. Instantiated the qualified `HealthCheck` beans (the user bean
   `@Liveness DatabaseCheck` and the `KnockHealthResource` resource);
2. Validated deployment via `HealthCheckCdiExtension` (§4.2 MP Health 4.0 spec):
   a bean that implements `HealthCheck` *without* an MP qualifier triggers a validation
   error (deployment rejected);
3. Exposed `KnockCdiHealthCheckRegistry` (`@ApplicationScoped`) injectable into
   `KnockHealthResource`.

`KnockHealthResource` is therefore available immediately after the Chappe
server starts, without a dedicated Vidocq Runtime extension — `CassiniExtension`
mounts it automatically like any other `@Path` bean.

## Exposed endpoints

With `vidocq.rest.context-path=/api`:

| Verb | URL                       | Probes        |
|-------|---------------------------|---------------|
| GET   | `/api/health`             | all           |
| GET   | `/api/health/live`        | `@Liveness`   |
| GET   | `/api/health/ready`       | `@Readiness`  |
| GET   | `/api/health/started`     | `@Startup`    |

Without `vidocq.rest.context-path` (default `/`), the paths are `/health`,
`/health/live`, etc.

## Response format (spec §3.1)

```json
{
  "status": "UP",
  "checks": [
    { "name": "database",          "status": "UP",   "data": { "responseTime": "12ms" } },
    { "name": "external-api-quota", "status": "DOWN", "data": { "remaining": 0 } }
  ]
}
```

HTTP 200 if `status=UP`, HTTP 503 if `status=DOWN`. If no check is
registered for a given probe, the status is `UP` with `checks: []` (see
`NoProcedureSuccessfulTest` from the TCK).

## Writing an application check

```java
package com.example.shop.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class StockServiceReadinessCheck implements HealthCheck {

    @Inject StockServiceClient client;

    @Override
    public HealthCheckResponse call() {
        boolean ok = client.ping();
        return HealthCheckResponse.named("stock-service")
                .status(ok)
                .build();
    }
}
```

`@Inject` in a `HealthCheck` is supported (see `CDIProducedProceduresTest` from the
TCK). Execution happens on a virtual thread, so blocking on I/O is safe.

## MP Config configuration (optional)

Knock is *zero-config*. No `mp.health.*` property is required to
function. Spec §6 defines `mp.health.disable-default-procedures` (disable
default checks) — Knock does **not** register any default check, so
this property has no effect (the TCK validates this behavior via `ConfigTest`).

## Deactivation

Removing the `vidocq-runtime-knock-health-extension` dependency is enough; Knock exposes no
dedicated `VidocqExtension` service, its integration is purely passive (CDI BCE
+ JAX-RS resource scanned by Cassini).

## Verification

```bash
# Starting the example
cd vidocq-runtime-examples/vidocq-runtime-cassini-rest-example
mvn -ntp -DskipTests package
java -p target/modules -m my.app/com.example.Main &

# Checks
curl -i http://localhost:8080/health/live
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8080/health
```

## TCK reference

`./run-official-tck-mp-health-4.0.sh all` (from the `knock` repo) runs the
official `microprofile-health-tck:4.0` against the full Knock stack:
**28/28 PASS**. The Arquillian runner (`KnockDeployableContainer`) reproduces the
same integration path as `vidocq-runtime-knock-health-extension`: Cassini + Vauban
embedded + `KnockHealthResource` mounted on a local Chappe endpoint.
