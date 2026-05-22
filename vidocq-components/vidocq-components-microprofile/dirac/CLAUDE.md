# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Paul Dirac (1902–1984) a formulé l'équation de Dirac, prédit l'antimatière et posé les bases
> des statistiques de Fermi-Dirac. Dirac le projet implémente **MicroProfile Metrics 5.1.1** :
> il mesure avec précision l'état des applications — compteurs, jauges, histogrammes, minuteries —
> et les expose dans un format standard (OpenMetrics / Prometheus), sans jamais altérer
> l'observable par l'observation.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le runner TCK hors reactor consomme les artefacts Dirac installés localement par le script
  `run-official-tck-mp-metrics-5.1.sh`

## Commandes essentielles

```bash
# Environnement SDK
sdk env

# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test
./run-official-tck-mp-metrics-5.1.sh

# TCK — suite complète
./run-official-tck-mp-metrics-5.1.sh all

# TCK — test ciblé
./run-official-tck-mp-metrics-5.1.sh -Dtest=NomDuTest

# Installation locale des modules requis par le runner TCK hors reactor
./mvnw -ntp -pl dirac-api,dirac-core,dirac-cdi-vauban -am install -DskipTests

# Rapport synthétique généré par le script TCK
cat dirac-tck/target/tck-report.txt
```

> `dirac-tck` est **hors reactor** (pom.xml en Model 4.0.0 standalone) pour contourner
> une incompatibilité ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0. Ne pas changer ce modèle.
> Voir `CLAUDE.md` racine Vidocq § *Contrainte d'architecture critique : TCK runners hors reactor*.

## Architecture

Dirac est une implémentation **MicroProfile Metrics 5.1.1** à zéro dépendance
d'implémentation : seules les APIs Jakarta EE et MicroProfile spécifiées sont utilisées.

```
dirac-api            ← Wrapping de la spec MP Metrics 5.1.1 + SPI Vidocq (MetricRegistryProducer,
                       DiracContext) ; types publics stables : MetricID, Tag, Snapshot
dirac-core           ← Implémentations pures Java 25 sans CDI (CounterImpl, GaugeImpl,
                       HistogramImpl, TimerImpl, MetricRegistryImpl, OpenMetricsFormatter,
                       JsonMetricsFormatter)
dirac-cdi-vauban     ← Intercepteurs CDI + BCE Vauban (DiracExtension) pour @Counted,
                       @Timed, @Gauge ; producers @ApplicationScoped pour MetricRegistry
dirac-rest           ← Endpoint JAX-RS GET /metrics (OpenMetrics / Prometheus text format)
                       via Cassini ; optionnel — activé si cassini est sur le classpath
dirac-bench          ← Benchmarks JMH vs Micrometer / SmallRye Metrics
dirac-tck            ← Runner TestNG+Arquillian TCK officiel (hors reactor — Model 4.0.0)
dirac-examples       ← Exemples d'utilisation standalone et avec vidocq-mps
```

**État réel actuel :** M1, M2, M3 (core), M4, M5, M6 et M7 sont achevés ; M8 (TCK officiel) est la prochaine étape. Les classes de production déjà présentes incluent
`io.vidocq.dirac.api.DiracException`, `io.vidocq.dirac.internal.CounterImpl`,
`io.vidocq.dirac.internal.MetricRegistryImpl`, `io.vidocq.dirac.internal.GaugeImpl`,
`io.vidocq.dirac.internal.HistogramImpl`, `io.vidocq.dirac.internal.TimerImpl`,
`io.vidocq.dirac.internal.BaseMetricsRegistrar`,
`io.vidocq.dirac.internal.OpenMetricsFormatter`,
`io.vidocq.dirac.internal.JsonMetricsFormatter`,
`io.vidocq.dirac.api.TimerSnapshot`,
`io.vidocq.dirac.cdi.internal.DiracExtension` (validation/résolution `@Gauge`),
`io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean`,
`io.vidocq.dirac.cdi.internal.CountedInterceptor`,
`io.vidocq.dirac.cdi.internal.GaugeRegistrationBean` et
`io.vidocq.dirac.cdi.internal.TimedInterceptor`,
`io.vidocq.dirac.rest.MetricsEndpoint` (placeholder), ainsi que le benchmark
`io.vidocq.dirac.bench.M4TimerBenchmarks`. La majorité des éléments décrits
ci-dessus reste la cible M8 (TCK complet).

Point d'attention API concret : le JAR `microprofile-metrics-api:5.1.1` utilisé ici n'expose
pas l'annotation `org.eclipse.microprofile.metrics.annotation.Histogram` ; la partie CDI de M3
dépendante de cette annotation est donc bloquée tant que ce point n'est pas tranché.

Un test d'intégration CDI `@Counted` avec Vauban embedded est présent dans
`dirac-cdi-vauban/src/test/java/io/vidocq/dirac/cdi/internal/CountedInterceptorCdiIntegrationTest.java`.

Les packages effectivement présents côté production sont `io.vidocq.dirac.api`,
`io.vidocq.dirac.internal`, `io.vidocq.dirac.cdi.internal` et `io.vidocq.dirac.rest`.

**Séparation fondamentale :** `dirac-core` contient les structures de données et les
enregistrements de métriques (logique pure, thread-safe via `LongAdder`, `AtomicReference`,
nanoTime). `dirac-cdi-vauban` porte les intercepteurs CDI qui délèguent au core.
Ainsi, le registre et les métriques sont testables unitairement sans container CDI.

**Registre de métriques :** `MetricRegistryImpl` maintient un
`ConcurrentHashMap<MetricID, Metric>` par scope (`APPLICATION`, `BASE`, `VENDOR`).
`MetricID` est une valeur immuable `(name, Set<Tag>)`. Le registre APPLICATION est
injectable via CDI ; BASE et VENDOR sont peuplés au démarrage par `DiracExtension`.

**Flux d'une mesure interceptée (ex: `@Counted`) :**
CDI intercepte l'appel via `CountedInterceptor.around()` →
résout le `MetricID` (cache par `(BeanClass, Method)`) →
incrémente le `Counter` dans le `MetricRegistryImpl` APPLICATION →
délègue à `ctx.proceed()`.

**Format d'export :** OpenMetrics / Prometheus text format (`text/plain;version=0.0.4`) via
`OpenMetricsFormatter` dans `dirac-core`. Endpoint REST optionnel dans `dirac-rest`.

## Contraintes d'architecture à ne pas violer

1. **Zéro import de librairie d'implémentation dans `dirac-core`** — uniquement les API
   specs : `microprofile-metrics-api`. Pas de Micrometer, Dropwizard Metrics, SmallRye Metrics.
2. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly obligatoire.
   Utiliser `LongAdder`, `AtomicLong`, `AtomicReference`, `ConcurrentHashMap`.
3. **Pas de `setAccessible(true)` en production** — utiliser `MethodHandles.privateLookupIn`
   si un accès interne est nécessaire ; documenter tout `opens` dans le `module-info`.
4. **Pas de `java.lang.reflect.Proxy`** pour la résolution des `@Gauge` — résolution par
   `MethodHandle` dès le démarrage du container (BCE `DiracExtension`).
5. **`dirac-tck/pom.xml` reste en Model 4.0.0** — ne pas passer en 4.1.0 tant que
   ShrinkWrap n'est pas mis à jour (contrainte commune à tout l'écosystème Vidocq).
6. **TCK 100 % PASS est un contrat** — toute modification structurelle de `dirac-core`
   ou `dirac-cdi-vauban` doit préserver ce score avant merge.
7. **Thread-safety sans contention** — les métriques sont des hot paths ; préférer
   `LongAdder` à `AtomicLong` pour les compteurs sous forte concurrence.

## Conventions

- **Java modules explicites** : `dirac-api` a déjà son `module-info.java` sous `src/main/java/` ;
  `dirac-core` et `dirac-cdi-vauban` gardent le leur sous `src/main/module-info/` ;
  `dirac-rest`, `dirac-bench` et `dirac-examples` n'ont pas encore de `module-info.java` dans
  l'état courant et neutralisent les `compilerArgs` hérités du parent dans leur `pom.xml`.
- **Packages** :
  - `io.vidocq.dirac.api.*` — SPI publique stable (MetricRegistryProducer, DiracContext, Snapshot)
  - `io.vidocq.dirac.internal.*` — code interne, non exporté (implémentations, formatter)
  - `io.vidocq.dirac.cdi.*` — intégration CDI (intercepteurs, BCE, producers)
  - `io.vidocq.dirac.rest.*` — endpoint JAX-RS (optionnel)
- **Maven groupId** : `io.vidocq.dirac`
- **Records** : privilégier les records immuables pour `MetricID`, `Tag`, `HistogramSnapshot`,
  `TimerSnapshot`, `GaugeConfig`, `TimerConfig`, `HistogramConfig`
- **Sealed interfaces** : `Metric` reste l'interface racine ; `Counter`, `Gauge<T>`,
  `Histogram`, `Timer` sont les sous-types scellés de l'implémentation interne
- **Pattern matching** : utiliser `switch` sur types scellés dans `OpenMetricsFormatter`
- **LongAdder / DoubleAdder** pour les compteurs et accumulateurs haute fréquence
- **`System.nanoTime()`** pour les minuteries — jamais `System.currentTimeMillis()`
- **Virtual threads** pour les tests de concurrence et les benchmarks JMH

## Méthodologie TDD

- **Red → Green → Refactor** — aucune ligne de production sans test préalable.
- Citer la section spec MicroProfile Metrics 5.1.1 dans le Javadoc/commentaire des tests.
- Tests unitaires dans le même package que la classe testée, nommés `<Classe>Test`.
- Pas de Mockito — doubles manuels (`FakeMetricRegistry`, `FakeInvocationContext`, etc.).
- Tests d'intégration CDI via Vauban embedded (sans Arquillian) dans `dirac-cdi-vauban`.
- Benchmarks JMH dans `dirac-bench` — comparatif vs Micrometer et SmallRye Metrics dès M4.
- Compléter en priorité les placeholders déjà présents (`DiracExtension`, `MetricsEndpoint`)
  au lieu de créer des points d'entrée parallèles.

## Plan mode default

- Entrer en plan mode pour toute tâche non-triviale (ajout d'un type de métrique,
  refactoring du `MetricRegistryImpl`, modification du format OpenMetrics).
- Documenter les décisions d'architecture dans `ROADMAP.md` (section « Décisions actées »).
- Utiliser l'agent `virtual-threads-reviewer` pour toute modification de code concurrent.
- Utiliser l'agent `jpms-guardian` après tout ajout de package ou modification de `module-info.java`.

## Agents disponibles

- `classfile-codegen` — si la résolution de `@Gauge` nécessite de la génération de bytecode
- `virtual-threads-reviewer` — pour `TimerImpl` (nanoTime + LongAdder), accès concurrent
  au `MetricRegistryImpl`, tout code concurrent ou critique en performance
- `jpms-guardian` — après modification de `module-info.java` ou ajout de package
- `dependency-gatekeeper` — avant tout ajout de dépendance au `pom.xml`
- `tck-runner` — pour diagnostiquer les échecs TCK MicroProfile Metrics 5.1.1

## TCK MicroProfile Metrics 5.1.1

- Framework : **TestNG** (pas JUnit — contrainte du TCK officiel)
- Container Arquillian : Vauban embedded + Chappe (transport HTTP pour l'endpoint /metrics)
- Artefact TCK : `org.eclipse.microprofile.metrics:microprofile-metrics-tck:5.1.1`
- Configuration présente aujourd'hui : `dirac-tck/src/test/resources/arquillian.xml`
- Test effectivement présent aujourd'hui : `dirac-tck/src/test/java/io/vidocq/dirac/tck/DiracTckSmokeTest.java`
- Score cible : **100 % PASS** (tous les tests de la suite)
- `TCK.md` n'existe pas encore dans ce dépôt

## Dépendances spec autorisées

```
org.eclipse.microprofile.metrics:microprofile-metrics-api:5.1.1
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1              (provided)
jakarta.interceptor:jakarta.interceptor-api:2.2                 (provided)
jakarta.annotation:jakarta.annotation-api:3.0                   (provided)
jakarta.ws.rs:jakarta.ws.rs-api:4.0                             (provided, dirac-rest uniquement)
org.junit:junit-bom:6.0.3                                       (test, BOM)
```

Toute nouvelle dépendance `<scope>compile</scope>` ou `<scope>runtime</scope>` doit passer
le `dependency-gatekeeper` et être explicitement justifiée dans la PR.
