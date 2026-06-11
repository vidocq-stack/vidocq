# Knock

> *"Knock, or the Triumph of Medicine"* — Jules Romains, 1923.

**MicroProfile Health 4.0** implementation in the Vidocq style:
zero third-party libraries, JDK 25, virtual threads, strict JPMS,
CDI integration via Vauban, Jakarta REST endpoints via Cassini,
JSON serialisation via Champollion.

## Modules

| Module | Role |
|---|---|
| `knock-api` | Re-exports the `org.eclipse.microprofile.health` spec + public SPI |
| `knock-core` | Standalone implementation: registry, aggregation, JSON serialisation (Jakarta JSON-P) |
| `knock-cdi-vauban` | Vauban BCE discovering `@Liveness`/`@Readiness`/`@Startup` beans |
| `knock-cassini` | Jakarta REST `/health*` endpoints via Cassini |
| `knock-tck` | Official MicroProfile Health 4.0 TCK runner (in the reactor, standalone-capable POM Model 4.0.0) |

## Prerequisites

```bash
sdk env   # java=25-tem, maven=3.9.16
```

## Commands

```bash
# Full build (skip tests)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK smoke test
./run-official-tck-mp-health-4.0.sh

# Full TCK suite
./run-official-tck-mp-health-4.0.sh all
```

## Endpoints

| Endpoint | Probe | HTTP Status |
|---|---|---|
| `GET /health` | All checks | 200 UP / 503 DOWN |
| `GET /health/live` | `@Liveness` | 200 UP / 503 DOWN |
| `GET /health/ready` | `@Readiness` | 200 UP / 503 DOWN |
| `GET /health/started` | `@Startup` | 200 UP / 503 DOWN |

## Example

```java
@Liveness
@ApplicationScoped
public class DatabaseCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("database")
                .up()
                .withData("responseTime", "8ms")
                .build();
    }
}
```

## Constraints

- **Zero third-party libraries**: Jakarta EE / MicroProfile specs only
- **Strict JPMS**: `module-info.java` on all modules
- **Virtual threads**: no `synchronized`, no `ThreadLocal`
- **TCK 100% PASS**: required contract before any structural merge

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for the detailed plan (M0 → M5).

## License

EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later — see [`LICENSE`](LICENSE).
