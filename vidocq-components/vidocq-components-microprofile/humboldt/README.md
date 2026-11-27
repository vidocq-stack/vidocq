# Humboldt

> *Alexander von Humboldt (1769–1859) — Prussian polymath, naturalist-explorer. The man who performed the foundational act of modern observability: observe simultaneously, measure rigorously, correlate across layers. On Chimborazo in 1802 he records pressure, temperature, magnetism, humidity, fauna, flora together — and draws the first isotherm maps. OpenTelemetry, in modern Java SE.*

**Humboldt** is the MicroProfile Telemetry 2.2 implementation (OpenTelemetry 1.67) in the [Vidocq](https://codefloe.com/Vidocq) ecosystem. Distributed tracing, metrics, and logs, on the public [OpenTelemetry](https://opentelemetry.io/) API surface — **without embedding `opentelemetry-sdk` or third-party exporters**.

> 🇬🇧 English version: [README_EN.md](README_EN.md)

## Status

**MicroProfile Telemetry 2.2 implemented (OpenTelemetry 1.67). Official TCK 2.2-RC3 (byte-identical to the 2.2 final under ballot): 85/85 PASS (2026-10-04).** The 2.1 TCK passed 85/85 on 2026-06-24. See [`TCK.md`](TCK.md).

See [`PLAN.md`](PLAN.md) for the detailed M0 → M9 roadmap, and [`tasks/todo.md`](tasks/todo.md) for task tracking.

## Scope

- ✅ Traces (W3C TraceContext, samplers, `@WithSpan`)
- ✅ Metrics (Counter, Histogram, UpDownCounter, Gauge async, ExponentialHistogram)
- ✅ Logs (bridge JUL/SLF4J to OTel Log records)
- ✅ Configuration `OTEL_*` / `MP_TELEMETRY_*` via [Ravel](https://codefloe.com/Vidocq/vidocq/src/branch/main/vidocq-components/vidocq-components-microprofile/ravel) (MicroProfile Config)
- ✅ Exporter **OTLP/HTTP**, `http/protobuf` or `http/json` (`otel.exporter.otlp.protocol`, default `http/protobuf`), via the JDK `java.net.http.HttpClient` (on virtual threads); both encoders hand-written, no runtime dependency
- 🚧 Transport on the [Chappe](https://codefloe.com/Vidocq/vidocq/src/branch/main/vidocq-components/vidocq-components-transport/chappe) HTTP client, and OTLP over gRPC (needs gRPC on Chappe): ROADMAP.md
- ✅ Auto-instrumentation Jakarta REST via [Cassini](https://codefloe.com/Vidocq/vidocq/src/branch/main/vidocq-components/vidocq-components-jakartaee/vidocq-components-jakartaee-core/cassini) + CDI via [Vauban](https://codefloe.com/Vidocq/vidocq/src/branch/main/vidocq-components/vidocq-components-jakartaee/vidocq-components-jakartaee-core/vauban)
- 🚧 Exporter **OTLP gRPC** (post-MVP — awaits module `chappe-grpc`, see PLAN.md §3.5)
- ❌ No JVM bytecode agent (instrumentation via APT + Class-File API at build time, never at runtime)
- ❌ No Netty / grpc-java / OkHttp / Guava dependency

## What Humboldt guarantees

- **Runtime dependencies**: humboldt-* jars only; the OpenTelemetry API and context ship repackaged as named modules (`humboldt-otel-api`, `humboldt-otel-context`, `humboldt-otel-instrumentation-annotations`).
- **No runtime reflection**: APT + Class-File API at `process-classes`.
- **No virtual-thread pinning from the context**: the `ContextStorage` (`HumboldtContextStorage`) keeps the context in a `ThreadLocal`, which no longer pins a virtual thread since JDK 21 (JEP 444); a move to `ScopedValue` (JEP 506) is postponed to M8.
- **AOT-ready** (GraalVM, Leyden) from the design.
- **OTLP transport v1**: HTTP only, protobuf or JSON (gRPC via a future native `chappe-grpc`; `grpc` fails the start until then).

## Startup

```bash
# Prerequisite: sdkman with Java 25 + Maven 3.10.0
sdk env
./mvnw -ntp install -DskipTests
```

## Documentation

- 📖 [Detailed implementation plan](PLAN.md) (1190 lines — metaphor, scope, dependency trade-offs, architecture, codegen, Java Modules, milestones)
- 🇬🇧 [English Antora documentation](docs/en/modules/ROOT/pages/index.adoc)
- 🐛 [Bugs](BUG.md)
- 📊 [Benchmarks](BENCH.md)
- 🎯 [TCK Status](TCK.md)
- 🗺️ [Roadmap](ROADMAP.md)

## License

[EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later](LICENSE).
