# Dirac

> *Paul Dirac (1902–1984) a formulé l'équation de Dirac, prédit l'antimatière et posé les bases
> des statistiques de Fermi-Dirac. Dirac le projet implémente **MicroProfile Metrics 5.1.1** :
> il mesure avec précision l'état des applications, sans jamais altérer l'observable par
> l'observation.*

Implémentation **MicroProfile Metrics 5.1.1** dans le style Vidocq :

- **Zéro librairie tierce** — pas de Micrometer, Dropwizard Metrics, SmallRye Metrics.
  Seule `microprofile-metrics-api` est compilée dans `dirac-core`.
- **Java 25** + **JPMS strict** — chaque module a son `module-info.java`, exports minimaux.
- **Virtual threads** — `LongAdder` pour les compteurs, `System.nanoTime()` pour les timers.
  Pas de `synchronized`, pas de `ThreadLocal`.
- **CDI via Vauban** — intercepteurs `@Counted`, `@Timed`, BCE `DiracExtension`.
- **Endpoint REST optionnel** — `GET /metrics` (OpenMetrics / Prometheus text format) via Cassini.

## Prérequis

```bash
sdk env          # Java 25-tem + Maven 4.0.0-rc-5
```

## Build

```bash
./mvnw -ntp install -DskipTests   # build complet du reactor
./mvnw test                        # tests unitaires
```

## TCK MicroProfile Metrics 5.1.1

```bash
./run-official-tck-mp-metrics-5.1.sh          # smoke test
./run-official-tck-mp-metrics-5.1.sh all      # suite complète
./run-official-tck-mp-metrics-5.1.sh -Dtest=CounterTest
```

Le runner TCK installe d'abord les artefacts Dirac requis dans le M2 local via
`run-official-tck-mp-metrics-5.1.sh`.

## JPMS / jlink (M9)

```bash
./run-jlink-smoke.sh
./run-jlink-smoke-m92.sh
```

Le detail du smoke check et du blocker `jlink` actuel est documente dans
[`JLINK.md`](JLINK.md).

## Modules

| Module | Description |
|---|---|
| `dirac-api` | Re-exposition contrôlée MP Metrics 5.1.1 + SPI Dirac |
| `dirac-core` | Implémentations pures Java 25 (Counter, Gauge, Histogram, Timer, registre, formatters) |
| `dirac-cdi-vauban` | Intercepteurs CDI + BCE Vauban (DiracExtension) |
| `dirac-rest` | Endpoint JAX-RS `GET /metrics` — optionnel, activé si Cassini est présent |
| `dirac-bench` | Benchmarks JMH vs Micrometer et SmallRye Metrics |
| `dirac-tck` | Runner TCK officiel TestNG/Arquillian (hors reactor — Model 4.0.0) |
| `dirac-examples` | Exemples d'utilisation standalone et avec vidocq-mps |

## Types de métriques supportés (MP Metrics 5.1.1)

| Type | Annotation | Description |
|---|---|---|
| `Counter` | `@Counted` | Compteur incrémental monotone (`LongAdder`) |
| `Gauge<T>` | `@Gauge` | Valeur instantanée via `MethodHandle` |
| `Histogram` | *(annotation non exposée dans l'API 5.1.1 utilisée)* | Distribution des valeurs (percentiles p50–p999) |
| `Timer` | `@Timed` | Durée des appels (`System.nanoTime()` + histogram) |

> **Note** : `Meter`, `ConcurrentGauge` et `SimpleTimer` ont été supprimés en MP Metrics 5.0
> et ne sont pas implémentés.
>
> **Point d'attention API** : le JAR `microprofile-metrics-api:5.1.1` utilisé dans ce dépôt
> n'expose pas `org.eclipse.microprofile.metrics.annotation.Histogram`.

## État du projet

Voir [ROADMAP.md](ROADMAP.md) pour l'avancement détaillé des milestones.

## Licence

Apache License, Version 2.0 — voir [LICENSE](LICENSE).
