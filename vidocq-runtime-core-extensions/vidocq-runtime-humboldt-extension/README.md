# vidocq-runtime-humboldt-extension

Extension Vidocq Runtime qui branche **Humboldt** (MicroProfile Telemetry 2.1 —
tracing, metrics, logs) sur le cycle de vie Vidocq.

## Ce que fait l'extension

- **`configure()`** : lit toutes les env vars/system properties OTel standard
  via `VidocqConfiguration` (priorité sys-prop > env > vidocq.properties).
- **`beforeStart()`** : appelle `HumboldtAutoConfigure.configure(env)` qui assemble
  les 3 SDKs (`SdkTracerProvider` + `SdkMeterProvider` + `SdkLoggerProvider`)
  avec les exporters OTLP HTTP-JSON et les propagators W3C, puis installe le
  résultat comme `GlobalOpenTelemetry`.
- **`onStop()`** : flush + shutdown ordonné (5s de timeout chacun).

Priorité **100** — démarre avant les extensions applicatives
(`cassini=500`, `cyrano`, `mansart`, etc.) pour que `GlobalOpenTelemetry`
soit prêt à recevoir les spans dès le premier appel.

## Activation automatique

Dès que cet artefact est sur le classpath, le ServiceLoader le découvre
(`META-INF/services/io.vidocq.runtime.spi.VidocqExtension` + `provides` JPMS).
Aucune configuration code requise.

## Configuration env vars

| Variable | Défaut | Notes |
|---|---|---|
| `OTEL_SERVICE_NAME` | `humboldt` | Nom du service (resource attribute `service.name`) |
| `OTEL_RESOURCE_ATTRIBUTES` | (vide) | Format `key=value,key=value` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Base URL collector OTel |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `${ENDPOINT}/v1/traces` | Override per-signal |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `${ENDPOINT}/v1/metrics` | |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` | `${ENDPOINT}/v1/logs` | |
| `OTEL_TRACES_EXPORTER` | `otlp` | `otlp \| none \| in-memory \| logging` |
| `OTEL_METRICS_EXPORTER` | `otlp` | `otlp \| none \| in-memory` |
| `OTEL_LOGS_EXPORTER` | `otlp` | `otlp \| none \| in-memory` |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | `always_on/off \| traceidratio \| parentbased_*` |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Ratio pour `traceidratio` |
| `OTEL_EXPORTER_OTLP_HEADERS` | (vide) | `Authorization=Bearer ...,X-Tenant=...` |
| `MP_TELEMETRY_SDK_DISABLED` | `false` | `true` → extension désactivée |

## Instrumentation automatique

L'extension active automatiquement :
- **`@WithSpan`** sur n'importe quelle méthode CDI (via `humboldt-cdi` +
  `BuildCompatibleExtension` Humboldt — l'annotation
  `io.opentelemetry.instrumentation.annotations.WithSpan` est interceptée
  sans configuration code, c'est le standard MicroProfile Telemetry 2.1).
- **Filters JAX-RS server** (via `humboldt-rest`) — `ContainerRequestFilter`
  qui extrait `traceparent` W3C, démarre un span SERVER, set
  `http.request.method`/`url.path`/`url.scheme`, et `ContainerResponseFilter`
  qui set `http.response.status_code` et `span.end()`.

## Désactivation

```bash
MP_TELEMETRY_SDK_DISABLED=true java -jar mon-app.jar
```

Utile pour les tests qui veulent fournir leur propre `OpenTelemetry`
(typiquement un `InMemorySpanExporter` pour assertions) sans interférence
de l'autoconfig.

## Voir aussi

- [humboldt/](https://forge.vidocq.dev/vidocq/humboldt) — implémentation Humboldt
- [humboldt/PLAN.md](https://forge.vidocq.dev/vidocq/humboldt/src/branch/main/PLAN.md) — architecture détaillée
- [humboldt/TCK.md](https://forge.vidocq.dev/vidocq/humboldt/src/branch/main/TCK.md) — statut TCK MP Telemetry 2.1
