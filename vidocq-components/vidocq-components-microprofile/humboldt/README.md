# Humboldt

> *Alexander von Humboldt (1769–1859) — polymathe prussien, naturaliste-explorateur. L'homme qui a posé le geste fondamental de l'observabilité moderne : observer simultanément, mesurer rigoureusement, corréler à travers les couches. Sur le Chimborazo en 1802 il enregistre conjointement pression, température, magnétisme, humidité, faune, flore — et trace les premières cartes isothermes. OpenTelemetry, en Java SE moderne.*

**Humboldt** est l'implémentation MicroProfile Telemetry 2.1 de l'écosystème [Vidocq](https://forge.vidocq.dev/vidocq). Tracing distribué, metrics et logs, sur la surface API publique [OpenTelemetry](https://opentelemetry.io/) — **sans embarquer `opentelemetry-sdk` ni les exporters tiers**.

> 🇬🇧 English version: [README_EN.md](README_EN.md)

## Statut

**M0 — Squelette JPMS** (en cours). Voir [`PLAN.md`](PLAN.md) pour la roadmap détaillée M0 → M9, et [`tasks/todo.md`](tasks/todo.md) pour le suivi des tâches.

## Périmètre

- ✅ Traces (W3C TraceContext, samplers, `@WithSpan`)
- ✅ Metrics (Counter, Histogram, UpDownCounter, Gauge async, ExponentialHistogram)
- ✅ Logs (bridge JUL/SLF4J vers OTel Log records)
- ✅ Configuration `OTEL_*` / `MP_TELEMETRY_*` via [Ravel](https://forge.vidocq.dev/vidocq/ravel) (MicroProfile Config)
- ✅ Exporter **OTLP HTTP/protobuf** via [Chappe](https://forge.vidocq.dev/vidocq/chappe) HTTP client
- ✅ Auto-instrumentation Jakarta REST via [Cassini](https://forge.vidocq.dev/vidocq/cassini) + CDI via [Vauban](https://forge.vidocq.dev/vidocq/vauban)
- 🚧 Exporter **OTLP gRPC** (post-MVP — attend le module `chappe-grpc`, cf. PLAN.md §3.5)
- ❌ Pas d'agent JVM bytecode (instrumentation via APT + Class-File API au build, jamais à chaud)
- ❌ Pas de dépendance Netty / grpc-java / OkHttp / Guava

## Différenciation vs SmallRye Telemetry

| | SmallRye | **Humboldt** |
|---|---|---|
| Dépendances runtime | ~25 jars (otel-sdk, exporter-otlp, grpc-java, netty, guava, protobuf, perfmark, …) | **6 jars** (`opentelemetry-api`, `-context`, `-semconv`, `protobuf-java`, humboldt-*, `microprofile-telemetry-api`) |
| Réflexion runtime | oui (Weld + OTel reflection) | **non** — APT + Class-File API à `process-classes` |
| Virtual threads pinning | risque (`ThreadLocal` Context) | **non** — `ScopedValue<Context>` (JEP 506) |
| AOT-ready (GraalVM, Leyden) | partiel | **oui** dès le design |
| OTLP transport v1 | gRPC ou HTTP | **HTTP/protobuf** seul (gRPC via futur `chappe-grpc` natif) |

## Démarrage

```bash
# Pré-requis : sdkman avec Java 25 + Maven 4.0.0-rc-5
sdk env
./mvnw -ntp install -DskipTests
```

## Documentation

- 📖 [Plan d'implémentation détaillé](PLAN.md) (1190 lignes — métaphore, périmètre, arbitrage deps, architecture, codegen, JPMS, jalons)
- 🇫🇷 [Documentation Antora française](docs/fr/modules/ROOT/pages/index.adoc)
- 🇬🇧 [English Antora documentation](docs/en/modules/ROOT/pages/index.adoc)
- 🐛 [Bugs](BUG.md)
- 📊 [Benchmarks](BENCH.md)
- 🎯 [Statut TCK](TCK.md)
- 🗺️ [Roadmap](ROADMAP.md)

## Licence

[Apache License 2.0](LICENSE).
