# Humboldt

> *Alexander von Humboldt (1769–1859) — Prussian polymath, naturalist-explorer. The man who performed the foundational act of modern observability: observe simultaneously, measure rigorously, correlate across layers. On Chimborazo in 1802 he records pressure, temperature, magnetism, humidity, fauna, flora together — and draws the first isotherm maps. OpenTelemetry, in modern Java SE.*

**Humboldt** is the MicroProfile Telemetry 2.1 implementation in the [Vidocq](https://forge.vidocq.dev/vidocq) ecosystem. Distributed tracing, metrics, and logs, on the public [OpenTelemetry](https://opentelemetry.io/) API surface — **without embedding `opentelemetry-sdk` or third-party exporters**.

> 🇬🇧 English version: [README_EN.md](README_EN.md)

## Status

**M0 — JPMS skeleton** (in progress). See [`PLAN.md`](PLAN.md) for the detailed M0 → M9 roadmap, and [`tasks/todo.md`](tasks/todo.md) for task tracking.

## Scope

- ✅ Traces (W3C TraceContext, samplers, `@WithSpan`)
- ✅ Metrics (Counter, Histogram, UpDownCounter, Gauge async, ExponentialHistogram)
- ✅ Logs (bridge JUL/SLF4J to OTel Log records)
- ✅ Configuration `OTEL_*` / `MP_TELEMETRY_*` via [Ravel](https://forge.vidocq.dev/vidocq/ravel) (MicroProfile Config)
- ✅ Exporter **OTLP HTTP/protobuf** via [Chappe](https://forge.vidocq.dev/vidocq/chappe) HTTP client
- ✅ Auto-instrumentation Jakarta REST via [Cassini](https://forge.vidocq.dev/vidocq/cassini) + CDI via [Vauban](https://forge.vidocq.dev/vidocq/vauban)
- 🚧 Exporter **OTLP gRPC** (post-MVP — awaits module `chappe-grpc`, see PLAN.md §3.5)
- ❌ No JVM bytecode agent (instrumentation via APT + Class-File API at build time, never at runtime)
- ❌ No Netty / grpc-java / OkHttp / Guava dependency

## Differentiation vs SmallRye Telemetry

| | SmallRye | **Humboldt** |
|---|---|---|
| Runtime dependencies | ~25 jars (otel-sdk, exporter-otlp, grpc-java, netty, guava, protobuf, perfmark, …) | **6 jars** (`opentelemetry-api`, `-context`, `-semconv`, `protobuf-java`, humboldt-*, `microprofile-telemetry-api`) |
| Runtime reflection | yes (Weld + OTel reflection) | **no** — APT + Class-File API at `process-classes` |
| Virtual threads pinning | risk (`ThreadLocal` Context) | **no** — `ScopedValue<Context>` (JEP 506) |
| AOT-ready (GraalVM, Leyden) | partial | **yes** from the design |
| OTLP transport v1 | gRPC or HTTP | **HTTP/protobuf** only (gRPC via future native `chappe-grpc`) |

## Startup

```bash
# Prerequisite: sdkman with Java 25 + Maven 3.9.16
sdk env
./mvnw -ntp install -DskipTests
```

## Documentation

- 📖 [Detailed implementation plan](PLAN.md) (1190 lines — metaphor, scope, dependency trade-offs, architecture, codegen, JPMS, milestones)
- 🇫🇷 [French Antora documentation](docs/fr/modules/ROOT/pages/index.adoc)
- 🇬🇧 [English Antora documentation](docs/en/modules/ROOT/pages/index.adoc)
- 🐛 [Bugs](BUG.md)
- 📊 [Benchmarks](BENCH.md)
- 🎯 [TCK Status](TCK.md)
- 🗺️ [Roadmap](ROADMAP.md)

## License

[EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later](LICENSE).
