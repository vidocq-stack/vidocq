# Humboldt

> *Alexander von Humboldt (1769–1859) — Prussian polymath, naturalist, explorer. The man who made the founding gesture of modern observability: observe simultaneously, measure rigorously, correlate across layers. On Chimborazo in 1802 he recorded jointly pressure, temperature, magnetism, humidity, fauna, flora — and drew the first isothermal maps. OpenTelemetry, in modern Java SE.*

**Humboldt** is the MicroProfile Telemetry 2.1 implementation of the [Vidocq](https://forge.vidocq.dev/vidocq) ecosystem. Distributed tracing, metrics and logs, on top of the [OpenTelemetry](https://opentelemetry.io/) public API — **without embedding `opentelemetry-sdk` or third-party exporters**.

> 🇫🇷 French version: [README.md](README.md)

## Status

**M0 — Java Modules skeleton** (in progress). See [`PLAN.md`](PLAN.md) for the detailed roadmap M0 → M9, and [`tasks/todo.md`](tasks/todo.md) for task tracking.

## Scope

- ✅ Traces (W3C TraceContext, samplers, `@WithSpan`)
- ✅ Metrics (Counter, Histogram, UpDownCounter, async Gauge, ExponentialHistogram)
- ✅ Logs (JUL/SLF4J bridge to OTel Log records)
- ✅ Configuration `OTEL_*` / `MP_TELEMETRY_*` via [Ravel](https://forge.vidocq.dev/vidocq/ravel) (MicroProfile Config)
- ✅ **OTLP HTTP/protobuf** exporter via [Chappe](https://forge.vidocq.dev/vidocq/chappe) HTTP client
- ✅ Jakarta REST auto-instrumentation via [Cassini](https://forge.vidocq.dev/vidocq/cassini) + CDI via [Vauban](https://forge.vidocq.dev/vidocq/vauban)
- 🚧 **OTLP gRPC** exporter (post-MVP — awaits the `chappe-grpc` module, see PLAN.md §3.5)
- ❌ No JVM bytecode agent (instrumentation via APT + Class-File API at build time, never hot)
- ❌ No Netty / grpc-java / OkHttp / Guava dependency

## Differentiation vs SmallRye Telemetry

| | SmallRye | **Humboldt** |
|---|---|---|
| Runtime dependencies | ~25 jars (otel-sdk, exporter-otlp, grpc-java, netty, guava, protobuf, perfmark, …) | **6 jars** (`opentelemetry-api`, `-context`, `-semconv`, `protobuf-java`, humboldt-*, `microprofile-telemetry-api`) |
| Runtime reflection | yes (Weld + OTel reflection) | **no** — APT + Class-File API at `process-classes` |
| Virtual threads pinning | risk (`ThreadLocal` Context) | **no** — `ScopedValue<Context>` (JEP 506) |
| AOT-ready (GraalVM, Leyden) | partial | **yes** by design |
| OTLP transport v1 | gRPC or HTTP | **HTTP/protobuf** only (gRPC via future native `chappe-grpc`) |

## Getting started

```bash
# Prerequisites: sdkman with Java 25 + Maven 3.9.16
sdk env
./mvnw -ntp install -DskipTests
```

## Documentation

- 📖 [Detailed implementation plan](PLAN.md) (1190 lines — metaphor, scope, dep arbitrage, architecture, codegen, Java Modules, milestones)
- 🇬🇧 [English Antora documentation](docs/en/modules/ROOT/pages/index.adoc)
- 🇫🇷 [French Antora documentation](docs/fr/modules/ROOT/pages/index.adoc)
- 🐛 [Bugs](BUG.md)
- 📊 [Benchmarks](BENCH.md)
- 🎯 [TCK status](TCK.md)
- 🗺️ [Roadmap](ROADMAP.md)

## License

[EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later](LICENSE).
