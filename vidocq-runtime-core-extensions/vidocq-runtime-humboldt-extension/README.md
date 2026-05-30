# vidocq-runtime-humboldt-extension

Vidocq Runtime extension that wires **Humboldt** (MicroProfile Telemetry 2.1 —
tracing, metrics, logs) into the Vidocq lifecycle.

## What the extension does

- **`configure()`**: reads all standard OTel env vars/system properties
  via `VidocqConfiguration` (priority: sys-prop > env > vidocq.properties).
- **`beforeStart()`**: calls `HumboldtAutoConfigure.configure(env)` which assembles
  the 3 SDKs (`SdkTracerProvider` + `SdkMeterProvider` + `SdkLoggerProvider`)
  with OTLP HTTP-JSON exporters and W3C propagators, then installs the
  result as `GlobalOpenTelemetry`.
- **`onStop()`**: ordered flush + shutdown (5 s timeout each).

Priority **100** — starts before application extensions
(`cassini=500`, `cyrano`, `mansart`, etc.) so that `GlobalOpenTelemetry`
is ready to receive spans from the very first call.

## Automatic activation

As soon as this artifact is on the classpath, the ServiceLoader discovers it
(`META-INF/services/io.vidocq.runtime.spi.VidocqExtension` + `provides` JPMS).
No code configuration required.

## Environment variable configuration

| Variable | Default | Notes |
|---|---|---|
| `OTEL_SERVICE_NAME` | `humboldt` | Service name (resource attribute `service.name`) |
| `OTEL_RESOURCE_ATTRIBUTES` | (empty) | Format `key=value,key=value` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTel collector base URL |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `${ENDPOINT}/v1/traces` | Per-signal override |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `${ENDPOINT}/v1/metrics` | |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` | `${ENDPOINT}/v1/logs` | |
| `OTEL_TRACES_EXPORTER` | `otlp` | `otlp \| none \| in-memory \| logging` |
| `OTEL_METRICS_EXPORTER` | `otlp` | `otlp \| none \| in-memory` |
| `OTEL_LOGS_EXPORTER` | `otlp` | `otlp \| none \| in-memory` |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | `always_on/off \| traceidratio \| parentbased_*` |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Ratio for `traceidratio` |
| `OTEL_EXPORTER_OTLP_HEADERS` | (empty) | `Authorization=Bearer ...,X-Tenant=...` |
| `MP_TELEMETRY_SDK_DISABLED` | `false` | `true` → extension disabled |

## Automatic instrumentation

The extension automatically activates:
- **`@WithSpan`** on any CDI method (via `humboldt-cdi` +
  `BuildCompatibleExtension` Humboldt — the
  `io.opentelemetry.instrumentation.annotations.WithSpan` annotation is intercepted
  without any code configuration; this is the MicroProfile Telemetry 2.1 standard).
- **JAX-RS server filters** (via `humboldt-rest`) — a `ContainerRequestFilter`
  that extracts `traceparent` W3C, starts a SERVER span, sets
  `http.request.method`/`url.path`/`url.scheme`, and a `ContainerResponseFilter`
  that sets `http.response.status_code` and calls `span.end()`.

## Disabling

```bash
MP_TELEMETRY_SDK_DISABLED=true java -jar my-app.jar
```

Useful for tests that want to provide their own `OpenTelemetry`
(typically an `InMemorySpanExporter` for assertions) without interference
from the auto-configuration.

## See also

- [humboldt/](https://forge.vidocq.dev/vidocq/humboldt) — Humboldt implementation
- [humboldt/PLAN.md](https://forge.vidocq.dev/vidocq/humboldt/src/branch/main/PLAN.md) — detailed architecture
- [humboldt/TCK.md](https://forge.vidocq.dev/vidocq/humboldt/src/branch/main/TCK.md) — MP Telemetry 2.1 TCK status
