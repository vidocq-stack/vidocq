# Knock

> *« Knock, ou le Triomphe de la Médecine »* — Jules Romains, 1923.

Implémentation **MicroProfile Health 4.0** dans le style Vidocq :
zéro librairie tierce, JDK 25, virtual threads, JPMS strict,
intégration CDI via Vauban, endpoints Jakarta REST via Cassini,
sérialisation JSON via Champollion.

## Modules

| Module | Rôle |
|---|---|
| `knock-api` | Re-expose la spec `org.eclipse.microprofile.health` + SPI publique |
| `knock-core` | Implémentation standalone : registry, agrégation, sérialisation JSON (Jakarta JSON-P) |
| `knock-cdi-vauban` | BCE Vauban découvrant les beans `@Liveness`/`@Readiness`/`@Startup` |
| `knock-cassini` | Endpoints Jakarta REST `/health*` via Cassini |
| `knock-tck` | Runner TCK officiel MicroProfile Health 4.0 (hors reactor) |

## Prérequis

```bash
sdk env   # java=25-tem, maven=3.9.16
```

## Commandes

```bash
# Build complet (sans tests)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# Smoke test TCK
./run-official-tck-mp-health-4.0.sh

# Suite TCK complète
./run-official-tck-mp-health-4.0.sh all
```

## Endpoints

| Endpoint | Probe | Code HTTP |
|---|---|---|
| `GET /health` | Tous les checks | 200 UP / 503 DOWN |
| `GET /health/live` | `@Liveness` | 200 UP / 503 DOWN |
| `GET /health/ready` | `@Readiness` | 200 UP / 503 DOWN |
| `GET /health/started` | `@Startup` | 200 UP / 503 DOWN |

## Exemple

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

## Contraintes

- **Zéro librairie tierce** : specs Jakarta EE / MicroProfile uniquement
- **JPMS strict** : `module-info.java` sur tous les modules
- **Virtual threads** : pas de `synchronized`, pas de `ThreadLocal`
- **TCK 100 % PASS** : contrat avant tout merge structurel

## Roadmap

Voir [`ROADMAP.md`](ROADMAP.md) pour le plan détaillé (M0 → M5).

## Licence

Apache License 2.0 — voir [`LICENSE`](LICENSE).
