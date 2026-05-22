# AGENTS.md

> Ce fichier est le guide de contribution pour les agents IA (GitHub Copilot, Copilot Chat,
> Copilot Workspace). Il doit rester synchrone avec `CLAUDE.md` — toute modification dans
> l'un doit être reflétée dans l'autre.

## Mission du dépôt

- Dirac implémente **MicroProfile Metrics 5.1.1** en Java 25, avec **zéro librairie
  d'implémentation tierce** : seules les API specs (`microprofile-metrics-api`,
  `jakarta.enterprise.cdi-api`, `jakarta.interceptor-api`, `jakarta.annotation-api`) sont
  compilées dans `dirac-core` et `dirac-cdi-vauban`.
- Architecture JPMS stricte : `dirac-api` wrapping de la spec, `dirac-core` implémentations
  pures Java 25 sans CDI, `dirac-cdi-vauban` intercepteurs CDI + BCE Vauban,
  `dirac-rest` endpoint JAX-RS optionnel, `dirac-tck` hors reactor.
- **Pas de Micrometer, Dropwizard Metrics, SmallRye Metrics** dans le code de production.
- Virtual threads (Project Loom) pour les benchmarks concurrents et les tests de charge —
  `Executors.newVirtualThreadPerTaskExecutor()`. Jamais de pool platform.
- Utiliser `ROADMAP.md` pour suivre l'avancement des milestones (M0..M8).
- Si les règles de ce fichier doivent être mises à jour, aligner `CLAUDE.md` dans la même
  opération — les deux fichiers sont des miroirs destinés à des outils différents.

## État réel du code à connaître avant de modifier

- Consulter `ROADMAP.md` pour l'état détaillé de chaque milestone (M0..M8).
- Les milestones marqués ✅ sont terminés ; ceux marqués 🚧 sont en cours.
- **État réel actuel : M1 à M7 achevés.** Le dépôt contient un noyau fonctionnel
  pour `Counter`, `MetricRegistry`, `Gauge`, `Histogram` (core), `Timer`, les composants CDI associés,
  les formatters OpenMetrics et JSON (`OpenMetricsFormatter`, `JsonMetricsFormatter`), et l'endpoint REST
  `MetricsResource` avec `ContentNegotiationFilter`. M8 (TCK officiel) est la prochaine étape.
- Placeholders réellement présents à compléter avant de créer de nouvelles classes parallèles :
  - `dirac-api/src/main/java/io/vidocq/dirac/api/DiracException.java`
  (Note : `MetricsEndpoint.java` remplacé par `MetricsResource.java` en M7.)
- Toute description ci-dessous d'une implémentation (`CounterImpl`, `TimerImpl`, etc.) reste la
  **cible** tant que le fichier concret correspondant n'existe pas encore dans `src/main/java`.
- Modules JPMS cibles :
  - `io.vidocq.dirac.api` (`dirac-api`)
  - `io.vidocq.dirac.core` (`dirac-core`)
  - `io.vidocq.dirac.cdi.vauban` (`dirac-cdi-vauban`)
  - `io.vidocq.dirac.rest` (`dirac-rest`, optionnel)
  - `io.vidocq.dirac.bench` (`dirac-bench`)
  - `io.vidocq.dirac.examples` (`dirac-examples`)
  - `io.vidocq.dirac.tck` (`dirac-tck`, hors reactor)

## Types de métriques MicroProfile Metrics 5.1.1

MicroProfile Metrics 5.x a simplifié la spec par rapport à 4.x :

- **Supprimés en 5.0** : `Meter`, `@Metered`, `ConcurrentGauge`, `@ConcurrentGauge`,
  `SimpleTimer`, `@SimplyTimed` — **ne pas les implémenter**.
- **Présents en 5.1.1 (API de métriques)** :
  - `Counter` / `@Counted` — compteur incrémental monotone (LongAdder)
  - `Gauge<T>` / `@Gauge` — valeur instantanée exposée via une méthode annotée
  - `Histogram` — distribution des valeurs (percentiles)
  - `Timer` / `@Timed` — durée des appels (combine histogram + compteur)
- **Point d'attention API concret** : le JAR `microprofile-metrics-api:5.1.1` présent dans ce
  dépôt n'expose pas l'annotation `org.eclipse.microprofile.metrics.annotation.Histogram`.
- **Scopes** : `APPLICATION` (par défaut, injectable), `BASE` (métriques JVM), `VENDOR`
- **MetricID** : `(String name, SortedMap<String, String> tags)` — valeur immuable
- **Tag** : `(String name, String value)` — pair immuable

## Architecture cible des modules

```
dirac-api            io.vidocq.dirac.api
  exports io.vidocq.dirac.api
  requires microprofile.metrics.api
  → SPI : MetricRegistryProducer, DiracContext, HistogramSnapshot, TimerSnapshot

dirac-core           io.vidocq.dirac.core
  exports io.vidocq.dirac.core (pour cdi-vauban uniquement — export qualifié)
  requires io.vidocq.dirac.api
  requires microprofile.metrics.api
  → Implémentations : CounterImpl (LongAdder), GaugeImpl (MethodHandle),
                       HistogramImpl (reservoir HDR), TimerImpl (nanoTime + HistogramImpl),
                       MetricRegistryImpl (ConcurrentHashMap<MetricID, Metric>),
                       OpenMetricsFormatter (format Prometheus text),
                       BaseMetricsRegistrar (métriques JVM : GC, threads, heap, uptime)

dirac-cdi-vauban     io.vidocq.dirac.cdi.vauban
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.enterprise.cdi
  requires jakarta.interceptor
  requires io.vidocq.vauban.api
  → Implémentations : CountedInterceptor (@Interceptor @Counted),
                       TimedInterceptor (@Interceptor @Timed),
                       DiracExtension (BCE CDI 4.1, résout les @Gauge au démarrage),
                       MetricRegistryProducerBean (@ApplicationScoped, 3 scopes),
                       DiracAutoDiscovery (ServiceLoader)

dirac-rest           io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.ws.rs
  → Implémentations : MetricsResource (GET /metrics, GET /metrics/{scope},
                       GET /metrics/{scope}/{name}), ContentNegotiationFilter

dirac-bench          io.vidocq.dirac.bench
  → JMH benchmarks vs Micrometer, SmallRye Metrics

dirac-tck            (hors reactor — Model 4.0.0)
  → TestNG + Arquillian + Vauban embedded + Chappe, runner TCK officiel MP Metrics 5.1.1

dirac-examples       io.vidocq.dirac.examples
  → Exemples standalone et avec vidocq-mps
```

- **État concret à date :** `dirac-api` expose `DiracException`; `dirac-core` contient déjà
  `CounterImpl`, `MetricRegistryImpl`, `GaugeImpl`, `HistogramImpl`, `TimerImpl` et `BaseMetricsRegistrar` ;
  `dirac-cdi-vauban` contient `DiracExtension` (validation/résolution `@Gauge`), `MetricRegistryProducerBean`,
  `CountedInterceptor`, `GaugeRegistrationBean` et `TimedInterceptor` ; `dirac-rest` contient encore le placeholder
  `MetricsEndpoint`.
- Les packages effectivement présents côté production sont `io.vidocq.dirac.api`,
  `io.vidocq.dirac.internal`, `io.vidocq.dirac.cdi.internal` et `io.vidocq.dirac.rest`.

## Frontières à ne pas casser

- Ne jamais remettre `dirac-tck` dans le reactor : exclu volontairement à cause de
  ShrinkWrap Maven Resolver / incompatibilité Model 4.0.0 vs 4.1.0 (contrainte commune
  à tout l'écosystème Vidocq).
- `dirac-core` ne doit importer **aucune** classe CDI (`jakarta.enterprise.*`,
  `jakarta.inject.*`) — uniquement `microprofile-metrics-api`.
- **Pas de `synchronized`** — utiliser `LongAdder`, `AtomicLong`, `AtomicReference`,
  `ConcurrentHashMap`. Les `synchronized` pinent les virtual threads.
- **Pas de `ThreadLocal`** — utiliser `ScopedValue` (JEP 506) pour propager le contexte
  si nécessaire dans les intercepteurs.
- **Pas de `java.lang.reflect.Proxy`** — résolution des `@Gauge` via
  `MethodHandles.lookup().findVirtual(...)` au démarrage (BCE `DiracExtension`).
- **Pas de `setAccessible(true)`** en production — ouvrir les packages dans le
  `module-info.java` et documenter pourquoi.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) pour les tests `dirac-core` et
  `dirac-cdi-vauban`. Le TCK utilise **TestNG** (contrainte upstream).
- Tout ajout de dépendance `<scope>compile|runtime</scope>` exige un passage par l'agent
  `dependency-gatekeeper` et une justification explicite dans la PR.
- **`dirac-rest` est optionnel** — sa présence ne doit jamais être requise par `dirac-core`
  ou `dirac-cdi-vauban` (dépendance inversée ou SPI).

## Convention JPMS — workaround `module-info` + `target/javamodules/`

- Dans `dirac-core` et `dirac-cdi-vauban`, le `module-info.java` vit sous
  `src/main/module-info/` (et **non** `src/main/java/`). C'est intentionnel : empêche
  Maven Compiler Plugin de basculer en mode JPMS lors de `testCompile`. Le `module-info.class`
  est compilé seul en phase `prepare-package`. Même contrainte que dans Heisenberg.
- `dirac-api` garde actuellement son `module-info.java` sous `src/main/java/`.
- `dirac-rest`, `dirac-bench` et `dirac-examples` n'ont pas encore de `module-info.java` dans
  l'état courant ; leurs `pom.xml` neutralisent les `compilerArgs` hérités du parent via
  `combine.self="override"` pour éviter un `--module-path` invalide.
- `microprofile-metrics-api:5.1.1` : vérifier la présence ou non d'`Automatic-Module-Name`
  dans le MANIFEST.MF avant de déclarer le `requires`. Si absent, le nom JPMS est dérivé
  de l'artefact (`microprofile.metrics.api`).
- Les tests s'exécutent en classpath (`useModulePath=false`) ; le câblage JPMS est validé
  par le smoke TCK.

## Workflows utiles

```bash
# Initialiser l'environnement SDK
sdk env

# Build reactor complet
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test
./run-official-tck-mp-metrics-5.1.sh

# TCK — suite complète
./run-official-tck-mp-metrics-5.1.sh all

# TCK — test ciblé (ex : CounterTest)
./run-official-tck-mp-metrics-5.1.sh -Dtest=CounterTest

# Installation locale des seuls modules requis par le runner TCK hors reactor
./mvnw -ntp -pl dirac-api,dirac-core,dirac-cdi-vauban -am install -DskipTests

# Rapport synthétique généré par le script TCK
cat dirac-tck/target/tck-report.txt

# Benchmarks JMH
./mvnw -ntp -pl dirac-bench package
java -jar dirac-bench/target/benchmarks.jar
```

- Le TCK passe toujours par le script racine qui installe d'abord le reactor, puis invoque
  `mvn -f dirac-tck/pom.xml -P<profile> test`.
- Le script TCK écrit un résumé dans `dirac-tck/target/tck-report.txt`.
- La configuration Arquillian réellement présente aujourd'hui est `dirac-tck/src/test/resources/arquillian.xml`.

## Conventions de contribution observées

- **TDD strict** : Red → Green → Refactor. Aucune ligne de production sans test préalable.
  Citer la section spec MicroProfile Metrics 5.1.1 dans les commentaires de test.
- Tests unitaires dans le même package que la classe testée, nommés `<Classe>Test`.
- Pas de Mockito — doubles manuels (`FakeMetricRegistry`, `FakeInvocationContext`, etc.).
- La logique de chaque type de métrique (`CounterImpl`, `TimerImpl`, etc.) est testée
  unitairement sans container CDI — c'est le but de la séparation `dirac-core` /
  `dirac-cdi-vauban`.
- Les placeholders M0 portent déjà le point d'extension attendu dans leur Javadoc ; compléter
  d'abord `DiracExtension` ou `MetricsEndpoint` avant d'introduire un doublon fonctionnel ailleurs.
- `BENCH.md`, `BUG.md`, `TCK.md` et `dirac-tck/README.md` ne sont pas encore présents dans ce
  dépôt ; ne pas les utiliser comme références ou cibles de modification tant qu'ils ne sont
  pas créés.

## Ce qu'un agent doit supposer pour les prochaines tâches

- À date, ne pas supposer que `MetricsResource` ou `ContentNegotiationFilter` existent déjà :
  la plupart des points ci-dessous décrivent encore la cible M5+.
- `dirac-core` est la brique fondatrice :
  - `CounterImpl` : incrémente via `LongAdder.increment()` / `add(long)`, lit via `sum()`
  - `GaugeImpl<T>` : stocke un `MethodHandle` résolu au démarrage, lit via `invoke()`
  - `HistogramImpl` : reservoir d'échantillons (HDR Histogram ou implémentation maison),
    expose `HistogramSnapshot` (count, sum, min, max, percentiles)
  - `TimerImpl` : `System.nanoTime()` delta → délègue à `HistogramImpl`
  - `MetricRegistryImpl` : `ConcurrentHashMap<MetricID, Metric>` par scope, thread-safe
  - `OpenMetricsFormatter` : sérialise en format Prometheus text (`# HELP`, `# TYPE`,
    lignes `metric{tags} value timestamp`)
  - `BaseMetricsRegistrar` : enregistre les métriques JVM obligatoires (spec §3.3) :
    GC (`gc.time`, `gc.total`), threads, heap, uptime, class loading
- `dirac-cdi-vauban` est le point d'entrée CDI :
  - `CountedInterceptor` (priorité `4020`, `@Counted`)
  - `TimedInterceptor` (priorité `4021`, `@Timed`)
  - `DiracExtension` (BCE CDI 4.1 `BuildCompatibleExtension`) : résout les `@Gauge` au
    démarrage, construit les `MethodHandle` mis en cache, valide les signatures
  - `MetricRegistryProducerBean` : produit les trois registres (`APPLICATION`, `BASE`,
    `VENDOR`) comme beans `@ApplicationScoped` avec qualifier `@RegistryScope`
- `dirac-rest` ne doit pas être requis par `dirac-core` :
  - `MetricsResource` : `@Path("/metrics")`, `@GET`, négociation de contenu
    (`text/plain` OpenMetrics et `application/json` selon spec §3.0)
  - Délègue à `OpenMetricsFormatter` (core) ou `JsonMetricsFormatter` (core)
- Avant toute modification structurelle du `MetricRegistryImpl` ou des intercepteurs,
  raisonner avec le contrat final : **TCK MicroProfile Metrics 5.1.1 à 100 % PASS**.
