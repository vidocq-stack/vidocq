# Heisenberg

> *Werner Heisenberg (1901–1976) formulated the uncertainty principle — one cannot measure
> simultaneously the position and momentum of a particle with arbitrary precision.
> The Heisenberg project encapsulates the uncertainty inherent in network calls and
> remote services, and responds to it with order, measure, and resilience.*

Implementation of **MicroProfile Fault Tolerance 4.1** in the Vidocq ecosystem.

- **Zero third-party libraries** — only Jakarta EE / MicroProfile spec APIs are compiled.
- **Java 25** — virtual threads for `@Asynchronous` and `@Timeout` (`Thread.ofVirtual() + join(Duration)`).
- **Strict JPMS** — each module has its own `module-info.java`, with minimal exports.
- **CDI via Vauban** — `heisenberg-cdi-vauban` provides the CDI interceptor and BCE.
- **Config via Ravel** — parameter overrides through MicroProfile Config.

## Implemented policies

| Annotation | Description |
|---|---|
| `@Retry` | Automatic retries with delay, jitter, retryOn/abortOn |
| `@Timeout` | Maximum execution time per attempt (virtual threads + `join(Duration)`) |
| `@CircuitBreaker` | Circuit breaker (CLOSED/OPEN/HALF_OPEN), sliding window |
| `@Bulkhead` | Isolation by semaphore (sync) or queue (async) |
| `@Fallback` | Alternative on failure (FallbackHandler or class method) |
| `@Asynchronous` | Execution on a virtual thread, CompletionStage/Future return |

## Modules

| Module | Description |
|---|---|
| `heisenberg-api` | Re-exposes the MP FT 4.1 spec + Vidocq SPI |
| `heisenberg-core` | Pure Java 25 policy engines (without CDI) |
| `heisenberg-cdi-vauban` | CDI interceptor + Vauban BCE |
| `heisenberg-bench` | JMH benchmarks vs SmallRye Fault Tolerance |
| `heisenberg-tck` | Official TCK runner (outside the reactor, TestNG/Arquillian) |
| `heisenberg-examples` | Usage examples |

## Prerequisites

```bash
sdk env   # Java 25-tem + Maven 3.9.16
```

## Build

```bash
./mvnw -ntp install -DskipTests   # full reactor
./mvnw test                        # unit tests
```

## MicroProfile Fault Tolerance 4.1 TCK

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh        # smoke test
./run-official-tck-mp-fault-tolerance-4.1.sh all    # full suite
./run-tck-no-observability.sh                       # smoke test without observability
./run-tck-no-observability.sh all                   # full suite without observability
./run-tck-no-observability.sh -Dtest=RetryTest      # targeted test without observability
```

## License

EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later — see [LICENSE](LICENSE).
