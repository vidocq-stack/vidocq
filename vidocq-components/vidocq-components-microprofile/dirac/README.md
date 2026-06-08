# Dirac

> *Paul Dirac (1902–1984) formulated the Dirac equation, predicted antimatter, and laid the
> foundations of Fermi-Dirac statistics. Dirac the project implements **MicroProfile Metrics 5.1.1**:
> it measures the state of applications with precision, without ever altering the observable
> through observation.*

**MicroProfile Metrics 5.1.1** implementation in the Vidocq style:

- **Zero third-party libraries** — no Micrometer, Dropwizard Metrics, SmallRye Metrics.
  Only `microprofile-metrics-api` is compiled into `dirac-core`.
- **Java 25** + **strict JPMS** — every module has its `module-info.java`, minimal exports.
- **Virtual threads** — `LongAdder` for counters, `System.nanoTime()` for timers.
  No `synchronized`, no `ThreadLocal`.
- **CDI via Vauban** — `@Counted`, `@Timed` interceptors, `DiracExtension` BCE.
- **Optional REST endpoint** — `GET /metrics` (OpenMetrics / Prometheus text format) via Cassini.

## Prerequisites

```bash
sdk env          # Java 25-tem + Maven 3.9.16
```

## Build

```bash
./mvnw -ntp install -DskipTests   # full reactor build
./mvnw test                        # unit tests
```

## TCK MicroProfile Metrics 5.1.1

```bash
./run-official-tck-mp-metrics-5.1.sh          # smoke test
./run-official-tck-mp-metrics-5.1.sh all      # full suite
./run-official-tck-mp-metrics-5.1.sh -Dtest=CounterTest
```

The TCK runner first installs the required Dirac artifacts into the local M2 via
`run-official-tck-mp-metrics-5.1.sh`.

## JPMS / jlink (M9)

```bash
./run-jlink-smoke.sh
./run-jlink-smoke-ci.sh
./mvnw -ntp -N -Pjlink-smoke verify
```

The M9 smoke check is documented in
[`JLINK.md`](JLINK.md).

The M9 smoke uses the reactor artifact `dirac-mp-metrics-api`, which repackages
`microprofile-metrics-api:5.1.1` with an explicit `module-info.class`.

## Modules

| Module | Description |
|---|---|
| `dirac-mp-metrics-api` | Local repackage of MP Metrics API with an explicit `module-info.class` for JPMS/jlink |
| `dirac-api` | Controlled re-export of MP Metrics 5.1.1 + Dirac SPI |
| `dirac-core` | Pure Java 25 implementations (Counter, Gauge, Histogram, Timer, registry, formatters) |
| `dirac-cdi-vauban` | CDI interceptors + Vauban BCE (DiracExtension) |
| `dirac-rest` | JAX-RS `GET /metrics` endpoint — optional, enabled when Cassini is present |
| `dirac-bench` | JMH benchmarks vs Micrometer and SmallRye Metrics |
| `dirac-tck` | Official TestNG/Arquillian TCK runner (out-of-reactor — Model 4.0.0) |
| `dirac-examples` | Standalone usage examples and integration with vidocq-mps |

## Supported Metric Types (MP Metrics 5.1.1)

| Type | Annotation | Description |
|---|---|---|
| `Counter` | `@Counted` | Monotonic incremental counter (`LongAdder`) |
| `Gauge<T>` | `@Gauge` | Instantaneous value via `MethodHandle` |
| `Histogram` | *(annotation not exposed in API 5.1.1)* | Value distribution (percentiles p50–p999) |
| `Timer` | `@Timed` | Call duration (`System.nanoTime()` + histogram) |

> **Note**: `Meter`, `ConcurrentGauge`, and `SimpleTimer` were removed in MP Metrics 5.0
> and are not implemented.
>
> **API note**: the `microprofile-metrics-api:5.1.1` JAR used in this repository
> does not expose `org.eclipse.microprofile.metrics.annotation.Histogram`.

## Project Status

See [ROADMAP.md](ROADMAP.md) for detailed milestone progress.

## License

EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later — see [LICENSE](LICENSE).
