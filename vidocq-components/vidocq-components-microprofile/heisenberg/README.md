# Heisenberg

> *Werner Heisenberg (1901–1976) formula le principe d'incertitude — on ne peut mesurer
> simultanément la position et la quantité de mouvement d'une particule avec une précision
> arbitraire. Heisenberg le projet encapsule l'incertitude inhérente aux appels réseau et
> aux services distants, et y répond avec ordre, mesure et résilience.*

Implémentation **MicroProfile Fault Tolerance 4.1** dans l'écosystème Vidocq.

- **Zéro librairie tierce** — seules les API specs Jakarta EE / MicroProfile sont compilées.
- **Java 25** — virtual threads pour `@Asynchronous` et `@Timeout` (`Thread.ofVirtual() + join(Duration)`).
- **JPMS strict** — chaque module a son `module-info.java`, exports minimaux.
- **CDI via Vauban** — `heisenberg-cdi-vauban` fournit l'intercepteur CDI et la BCE.
- **Config via Ravel** — surcharge des paramètres via MicroProfile Config.

## Politiques implémentées

| Annotation | Description |
|---|---|
| `@Retry` | Ré-essais automatiques avec délai, jitter, retryOn/abortOn |
| `@Timeout` | Délai maximal d'exécution par tentative (virtual threads + `join(Duration)`) |
| `@CircuitBreaker` | Disjoncteur (CLOSED/OPEN/HALF_OPEN), fenêtre glissante |
| `@Bulkhead` | Isolation par sémaphore (sync) ou file (async) |
| `@Fallback` | Alternative en cas d'échec (FallbackHandler ou méthode de la classe) |
| `@Asynchronous` | Exécution sur virtual thread, retour CompletionStage/Future |

## Modules

| Module | Description |
|---|---|
| `heisenberg-api` | Re-exposition de la spec MP FT 4.1 + SPI Vidocq |
| `heisenberg-core` | Moteurs de politiques purs Java 25 (sans CDI) |
| `heisenberg-cdi-vauban` | Intercepteur CDI + BCE Vauban |
| `heisenberg-bench` | Benchmarks JMH vs SmallRye Fault Tolerance |
| `heisenberg-tck` | Runner TCK officiel (hors reactor, TestNG/Arquillian) |
| `heisenberg-examples` | Exemples d'utilisation |

## Prérequis

```bash
sdk env   # Java 25-tem + Maven 4.0.0-rc-5
```

## Build

```bash
./mvnw -ntp install -DskipTests   # reactor complet
./mvnw test                        # tests unitaires
```

## TCK MicroProfile Fault Tolerance 4.1

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh        # smoke test
./run-official-tck-mp-fault-tolerance-4.1.sh all    # suite complète
./run-tck-no-observability.sh                       # smoke test sans observabilité
./run-tck-no-observability.sh all                   # suite complète sans observabilité
./run-tck-no-observability.sh -Dtest=RetryTest      # test ciblé sans observabilité
```

## Licence

Apache License, Version 2.0 — voir [LICENSE](LICENSE).
