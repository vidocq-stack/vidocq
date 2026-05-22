# Dirac — Plan d'implémentation

> Implémentation MicroProfile Metrics 5.1.1 dans le style Vidocq : zéro librairie tierce
> d'implémentation (APIs Jakarta EE / MicroProfile autorisées), Java 25, virtual threads,
> JPMS strict, CDI via Vauban, endpoint REST via Cassini, configuration via Ravel.

## Principes directeurs

| Principe | Application concrète |
|---|---|
| Zéro librairie d'implémentation | Pas de Micrometer, Dropwizard Metrics, SmallRye Metrics dans `dirac-core`. Seules les API specs compilées. |
| Séparation métriques / CDI | `dirac-core` contient les structures de données et les enregistrements ; `dirac-cdi-vauban` contient les intercepteurs CDI. |
| Thread-safety sans contention | `LongAdder` pour les compteurs ; `AtomicReference` pour les états ; `ConcurrentHashMap` pour les registres. Pas de `synchronized`. |
| JPMS strict | `module-info.java` partout, `internal.*` non exporté, SPI via `provides/uses`. Pas d'`opens` non justifié. |
| TDD strict | Red → Green → Refactor. Test avant le code. Citation §spec dans les tests. |
| TCK PASS 100 % | Contrat dur avant tout merge structurel. Score déclaré dans `TCK.md`. |
| Performance mesurée | JMH dès M4, comparatif vs Micrometer et SmallRye Metrics, résultats dans `BENCH.md`. |
| AOT-friendly | Pas de proxy dynamique. `@Gauge` résolu par `MethodHandle` au démarrage. Compatible GraalVM native-image. |
| OpenMetrics standard | Format d'export conforme Prometheus text exposition format 0.0.4 + OpenMetrics 1.0. |

## Méthodologie : TDD + TCK comme garde-fous parallèles

Dirac est développé en **TDD strict** (Red → Green → Refactor). Aucune ligne de production
n'est écrite avant un test qui la justifie. Au-delà du cycle TDD interne :

- **Couche 1 — tests unitaires TDD** : pilotent la conception de chaque type de métrique
  et du registre. Testables sans container CDI (c'est la raison d'être de `dirac-core`).
- **Couche 2 — tests d'intégration CDI** : scénarios `@Counted`, `@Timed`, `@Gauge` avec
  Vauban embedded. Vérifient la résolution des intercepteurs et des producers sans TCK.
- **Couche 3 — TCK officiel** (`microprofile-metrics-tck:5.1.1`) : contrat 100 % PASS
  avant tout merge structurel. Module hors reactor (POM Model 4.0.0).
- **Couche 4 — Bench JMH** : `dirac-bench` compare throughput, overhead d'interception,
  latence p99 vs Micrometer et SmallRye Metrics sur la même JVM.

## Architecture des modules

```
dirac-api            io.vidocq.dirac.api
  exports io.vidocq.dirac.api
  requires microprofile.metrics.api
  → SPI publique : MetricRegistryProducer, DiracContext,
                   HistogramSnapshot, TimerSnapshot

dirac-core           io.vidocq.dirac.core
  exports io.vidocq.dirac.core to io.vidocq.dirac.cdi.vauban, io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires microprofile.metrics.api
  → Implémentations : CounterImpl, GaugeImpl, HistogramImpl, TimerImpl,
                       MetricRegistryImpl, OpenMetricsFormatter, JsonMetricsFormatter,
                       BaseMetricsRegistrar

dirac-cdi-vauban     io.vidocq.dirac.cdi.vauban
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.enterprise.cdi
  requires jakarta.interceptor
  requires io.vidocq.vauban.api
  → Implémentations : CountedInterceptor, TimedInterceptor, DiracExtension (BCE),
                       MetricRegistryProducerBean, DiracAutoDiscovery

dirac-rest           io.vidocq.dirac.rest
  requires io.vidocq.dirac.api
  requires io.vidocq.dirac.core
  requires jakarta.ws.rs
  → Implémentations : MetricsResource, ContentNegotiationFilter

dirac-bench          io.vidocq.dirac.bench
  → JMH benchmarks vs Micrometer, SmallRye Metrics

dirac-tck            (hors reactor — Model 4.0.0)
  → TestNG + Arquillian + Vauban embedded + Chappe, runner TCK officiel MP Metrics 5.1.1

dirac-examples       io.vidocq.dirac.examples
  → Exemples standalone et avec vidocq-mps
```

## Phases

### M0 — Bootstrap

- [x] `.sdkmanrc` (`java=25-tem`, `maven=4.0.0-rc-5`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile)
- [x] `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md` (ces fichiers)
- [x] Création des sous-modules avec `pom.xml` + `module-info.java` squelettes :
      `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, `dirac-rest`,
      `dirac-bench`, `dirac-examples`, `dirac-tck` (hors reactor)
- [x] `LICENSE` (Apache 2.0)
- [x] `README.md`
- [x] `run-official-tck-mp-metrics-5.1.sh` (script TCK racine)
- [x] Validation `./mvnw -ntp install -DskipTests` réussit sur le reactor
- [x] Validation `mvn -f dirac-tck/pom.xml -DskipTests compile` réussit (hors reactor)

**Note M0 :** `dirac-rest/module-info.java` est intentionnellement différé à M7 (module
optionnel sans contenu à M0 — compiler args override incompatible avec JPMS sans sources).

**Livrable M0 ✅ :** Reactor compilable (7/7 BUILD SUCCESS), `module-info.java` squelettes
cohérents sur `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, TCK non-reactor compilable.

---

### M1 — MetricRegistry + Counter

**Scope spec :** §2 (MetricRegistry), §3.1 (Counter), §4.1 (@Counted).

| Tâche | Notes | État |
|---|---|---|
| `MetricID` record | Utilisation du type `org.eclipse.microprofile.metrics.MetricID` (API spec) pour `(name, tags)` immuable | ☑ |
| `Tag` record | Utilisation du type `org.eclipse.microprofile.metrics.Tag` (API spec) avec validation native | ☑ |
| `MetricRegistryImpl` | `ConcurrentHashMap<MetricID, Metric>` ; méthodes `counter()`, `gauge()`, `histogram()`, `timer()`, `register()`, `remove()`, `getMetrics()` | ☑ |
| `CounterImpl` | `LongAdder` ; `inc()`, `inc(long)`, `getCount()` | ☑ |
| `MetricRegistryProducerBean` CDI | Produit `MetricRegistry` pour les scopes `APPLICATION`, `BASE`, `VENDOR` avec qualifier `@RegistryScope` | ☑ |
| `DiracExtension` BCE — squelette | Implémente `BuildCompatibleExtension` sans logique | ☑ |
| `CountedInterceptor` | Priorité `4020` ; résout le `MetricID` en cache (BeanClass, Method) → incrémente `Counter` APPLICATION | ☑ |
| `@Counted` — champs `absolute`, `tags`, `description`, `unit`, `scope` | Lecture des attributs de l'annotation pour construire le `MetricID` et le `Metadata` | ☑ |
| Tests unitaires `CounterImpl` | `inc()`, `inc(long)`, concurrence sous 200 virtual threads | ☑ |
| Tests unitaires `MetricRegistryImpl` | Enregistrement, lookup, removal, unicité par MetricID | ☑ |
| Tests d'intégration CDI | `@Counted` sur méthode simple avec Vauban embedded ; vérification du registre APPLICATION | ☑ |

**Décisions M1 :**
- `MetricID` utilise `TreeMap<String, String>` pour les tags (ordre stable pour le format OpenMetrics).
- Le cache `(BeanClass, Method) → MetricID` est construit lors du premier appel intercepté
  (pas au démarrage BCE, car le nom de métrique peut contenir le nom de classe canonique).
- Un `Counter` enregistré deux fois avec le même `MetricID` retourne la même instance
  (sémantique "get-or-create").

**Livrable M1 ✅ :** `@Counted` fonctionnel, registre APPLICATION injectable via CDI. Tests unitaires + intégration CDI verts.

---

### M2 — Gauge

**Scope spec :** §3.2 (Gauge), §4.2 (@Gauge).

| Tâche | Notes | État |
|---|---|---|
| `GaugeImpl<T>` | Stocke un `MethodHandle` résolu au démarrage ; `getValue()` invoque le handle | ☑ |
| `DiracExtension` BCE — résolution `@Gauge` | Parcourt les méthodes annotées `@Gauge` ; construit et met en cache les `MethodHandle` ; valide la signature (pas de paramètre, type de retour non-void) | ☑ |
| `@Gauge` — enregistrement automatique | Le BCE enregistre chaque méthode `@Gauge` dans le registre ciblé par `scope` au démarrage du container | ☑ |
| Validation négative | `@Gauge` sur méthode avec paramètres ou retour `void` → échec de validation au démarrage | ☑ |
| Tests unitaires `GaugeImpl` | Lecture simple, mise à jour via la méthode sous-jacente | ☑ |
| Tests d'intégration CDI | `@Gauge` sur méthode retournant une valeur métier ; vérification via `MetricRegistry.getGauges()` | ☑ |

**Décisions M2 :**
- `GaugeImpl` ne stocke pas la valeur — il invoque le `MethodHandle` à chaque appel de
  `getValue()`. C'est la sémantique attendue : une gauge est une lecture instantanée.
- `MethodHandle` résolu dans le BCE avec `MethodHandles.privateLookupIn(beanClass, lookup)`
  pour accéder aux méthodes `protected` ou package-private si nécessaire.

**Livrable M2 ✅ :** `@Gauge` enregistré automatiquement au démarrage. Tests verts.

---

### M3 — Histogram

**Scope spec :** §3.3 (Histogram).

| Tâche | Notes | État |
|---|---|---|
| `HistogramSnapshot` record | `count`, `sum`, `min`, `max`, `mean`, percentiles (p50, p75, p95, p98, p99, p999) | ☑ |
| `HistogramImpl` | Reservoir d'échantillons avec decay exponentiel (ou implémentation maison) ; thread-safe via `AtomicLongArray` ; expose `HistogramSnapshot` | ☑ |
| Tests unitaires `HistogramImpl` | Distribution, percentiles, concurrence | ☑ |

**Décisions M3 :**
- Le reservoir utilise un algorithme de decay exponentiel avec fenêtre de 5 minutes
  (paramètres par défaut Prometheus : `alpha=0.015`, `size=1028`).
- Thread-safety par `AtomicLong[]` + CAS sans `synchronized`.
- Le `microprofile-metrics-api:5.1.1` actuellement consommé n'expose pas d'annotation
  `@Histogram` dans `org.eclipse.microprofile.metrics.annotation` ; M3 couvre donc
  uniquement l'implémentation `Histogram` côté core.

**Livrable M3 ✅ :** Histogram fonctionnel côté core (`HistogramImpl` + snapshot + registry + tests).

---

### M4 — Timer + Benchmarks JMH baseline

**Scope spec :** §3.4 (Timer), §4.4 (@Timed).

| Tâche | Notes | État |
|---|---|---|
| `TimerSnapshot` record | Étend `HistogramSnapshot` avec `elapsedTime` (durée totale) | ☑ |
| `TimerImpl` | `System.nanoTime()` delta → délègue à `HistogramImpl` ; expose `TimerSnapshot` | ☑ |
| `TimedInterceptor` CDI | Priorité `4021` ; démarre `nanoTime()` avant `ctx.proceed()`, enregistre le delta après | ☑ |
| `@Timed` — attributs `absolute`, `tags`, `description` | Même pattern que `@Counted` | ☑ |
| Benchmarks JMH — baseline | Overhead d'interception : méthode CDI baseline vs `@Counted` vs `@Timed` (`M4TimerBenchmarks`) | ☑ |
| Charge JMH sous 1000 virtual threads | Throughput `Counter.increment()` et `Timer.update(Duration)` avec `Executors.newVirtualThreadPerTaskExecutor()` | ☑ |
| Tests unitaires `TimerImpl` | Mesure de durée, concurrence, sous-milliseconde | ☑ |
| Tests d'intégration CDI | `@Timed` sur méthode lente ; vérification `TimerSnapshot.mean()` | ☑ |

**Décisions M4 :**
- `TimerImpl` utilise `System.nanoTime()` uniquement — jamais `currentTimeMillis()`.
- L'overhead du `TimedInterceptor` doit rester < 1 µs p99 sur JVM chauffée (objectif perf).
- Benchmarks M4 implémentés dans `dirac-bench/src/main/java/io/vidocq/dirac/bench/M4TimerBenchmarks.java`.

**Livrable M4 ✅ :** `@Timed` opérationnel (core + CDI) et baseline JMH disponible dans `dirac-bench`.

---

### M5 — Métriques BASE (JVM) et VENDOR

**Scope spec :** §3.3 (Base Metrics), §3.4 (Vendor Metrics).

| Tâche | Notes | État |
|---|---|---|
| `BaseMetricsRegistrar` | Enregistre les métriques JVM obligatoires dans le registre BASE au démarrage | ☑ |
| GC metrics | `gc.time`, `gc.total` via `ManagementFactory.getGarbageCollectorMXBeans()` (exposition gauge agrégée des compteurs MXBean) | ☑ |
| Thread metrics | `thread.count` (Gauge), `thread.daemon.count` (Gauge), `thread.max.count` (Gauge) via `ThreadMXBean` | ☑ |
| Heap metrics | `memory.usedHeap` (Gauge), `memory.committedHeap` (Gauge), `memory.maxHeap` (Gauge) via `MemoryMXBean` | ☑ |
| Uptime metrics | `jvm.uptime` (Gauge, ms) via `RuntimeMXBean` | ☑ |
| Class loading metrics | `classloader.loadedClasses` (Gauge), `classloader.unloadedClasses` (Gauge) | ☑ |
| CPU metrics | `cpu.availableProcessors` (Gauge), `cpu.systemLoadAverage` (Gauge) via `OperatingSystemMXBean` | ☑ |
| Enregistrement au démarrage | `MetricRegistryProducerBean` appelle `BaseMetricsRegistrar.register(MetricRegistry base)` à l'initialisation | ☑ |
| Tests unitaires | Vérification de l'enregistrement des métriques BASE attendues (`BaseMetricsRegistrarTest`) | ☑ |
| Tests d'intégration | Registre BASE non-vide après démarrage Vauban embedded (`BaseMetricsCdiIntegrationTest`) | ☑ |

**Décisions M5 :**
- Les valeurs JVM provenant des MXBeans sont exposées en lecture instantanée via `Gauge` pour garder un état live sans scheduler.
- Le registre BASE est peuplé dans `MetricRegistryProducerBean` à l'initialisation de l'application.

**Livrable M5 ✅ :** Registre BASE peuplé au démarrage avec métriques JVM principales et couverture de tests core + CDI.

---

### M6 — Format OpenMetrics / Prometheus

**Scope spec :** §3.0 (Exposition des métriques — format Prometheus text).

| Tâche | Notes | État |
|---|---|---|
| `OpenMetricsFormatter` | Sérialise `MetricRegistry` en format Prometheus text (`text/plain;version=0.0.4`) | ☑ |
| Format `# HELP` et `# TYPE` | Générés depuis `Metadata.description()` et le type de métrique | ☑ |
| Format des lignes de métrique | `metric_name{tag1="v1",tag2="v2"} value [timestamp]` | ☑ |
| Suffixes Prometheus par type | Counter : `_total` ; Histogram : `_bucket`, `_count`, `_sum` ; Timer : `_seconds_*` (conversion nanos→secondes) | ☑ |
| Canonicalisation des noms | `.` → `_` ; caractères non alphanumériques → `_` (spec §3.1) | ☑ |
| `JsonMetricsFormatter` | Sérialise en JSON (format spec MP Metrics §3.2) — sans bibliothèque JSON tierce (Champollion) | ☑ |
| Tests unitaires `OpenMetricsFormatter` | Sortie exacte pour Counter, Gauge, Histogram, Timer avec et sans tags | ☑ |
| Tests unitaires `JsonMetricsFormatter` | Structure JSON conforme spec pour chaque type | ☑ |

**Décisions M6 :**
- `OpenMetricsFormatter` construit le texte avec `StringBuilder` — pas de dépendance à
  un moteur de template.
- Le format Timer est actuellement exposé en série `_seconds` (`quantile`, `_count`, `_sum`) avec conversion nanos→secondes.
- `JsonMetricsFormatter` : clés au format `metricName[;tagKey=tagValue]*` (tags triés). Counter/Gauge → scalaire JSON. Histogram → objet `{count, sum, p50…p999}`. Timer → objet `{count, elapsedTime, p50…p999}` (secondes). Implémenté avec `StringBuilder` — aucune dépendance.

**Livrable :** OpenMetrics et JSON implémentés et testés (39 tests dirac-core verts). ✅

---

### M7 — Endpoint REST /metrics (dirac-rest + Cassini)

**Scope spec :** §2.3 (REST API).

| Tâche | Notes | État |
|---|---|---|
| `MetricsResource` JAX-RS | `@Path("/metrics")`, `@GET` → retourne tous les scopes | ☑ |
| `GET /metrics/{scope}` | Scope = `application`, `base`, `vendor` ; 404 si scope inconnu | ☑ |
| `GET /metrics/{scope}/{name}` | Métrique individuelle ; 404 si non trouvée | ☑ |
| Négociation de contenu | `Accept: text/plain` → OpenMetrics ; `Accept: application/json` → JSON ; défaut → text/plain | ☑ |
| `ContentNegotiationFilter` JAX-RS | Filtre `@Provider @PreMatching` — null/vide/`*/*` → `text/plain` | ☑ |
| Intégration Cassini | `MetricsResource` est un bean `@ApplicationScoped` — découvert automatiquement par Cassini via CDI | ☑ |
| Tests d'intégration REST | Couverts par le TCK officiel M8 (deploy Arquillian + Chappe) ; tests unitaires directs couvrent la logique de formatage | ☑ |

**Décisions M7 :**
- `dirac-rest` dépend de `jakarta.ws.rs-api` en `provided` — Cassini fournit l'implémentation.
- La logique de formatage est extraite dans des méthodes package-visible (`formatAll`, `formatScope`, `formatMetric`)
  testées directement sans RuntimeDelegate JAX-RS.
- `@ApplicationScoped` sur `MetricsResource` → Cassini la découvre comme bean CDI sans code supplémentaire.
- Les codes d'erreur HTTP suivent la spec : 200 OK, 404 Not Found si scope/métrique absents.
- Format `GET /metrics` JSON : objet racine avec clés `application`, `base`, `vendor`.
- `OpenMetricsFormatter` et `JsonMetricsFormatter` ont chacun une surcharge acceptant `Map<MetricID, Metric>`
  pour le filtrage par nom (`GET /metrics/{scope}/{name}`).
- `MetricsEndpoint` placeholder (M0) remplacé et supprimé.
- Les tests d'intégration HTTP (Chappe embedded) sont différés à M8 — le TCK teste cet endpoint de bout en bout.

**Livrable ✅ :** `MetricsResource` + `ContentNegotiationFilter` implémentés et testés (21 tests dirac-rest verts).

---

### M8 — TCK officiel MicroProfile Metrics 5.1.1

**Scope :** Suite complète `microprofile-metrics-tck:5.1.1`.

| Tâche | Notes | État |
|---|---|---|
| `dirac-tck/pom.xml` (Model 4.0.0) | Dépendances : TCK, Arquillian, Vauban embedded, Chappe ; **hors reactor** | ☑ |
| `DiracDeployableContainer` | `DeployableContainer` Arquillian Local démarrant Vauban + Dirac embedded + Chappe | ☑ |
| `VaubanDiracTckBootstrap` | Extraction classes de l'archive, démarrage Vauban (DiracExtension + intercepteurs + producers), activation RequestContext | ☑ |
| `DiracTestEnricher` | Injection `@Inject` sur les classes de test TCK via `BeanManager` Vauban | ☑ |
| `DiracArquillianExtension` + `arquillian.xml` | Découverte du container par Arquillian | ☑ |
| `tck-suite.xml` | Sélection des packages TCK MP Metrics 5.1.1 | ☑ |
| `run-official-tck-mp-metrics-5.1.sh` | Script racine : install reactor → invoke TCK + génération `tck-report.txt` | ☑ |
| Passage TCK smoke test | `DiracTckSmokeTest` 1/1 PASS | ☑ |
| Passage TCK complet | **127/127 PASS** sur le `tck-suite.xml` officiel | ☑ |
| `TCK.md` | Documentation des challenges et tests exclus | ☐ |
| `dirac-tck/README.md` | Procédure d'installation TCK + architecture du runner | ☐ |

**Décisions M8 :**
- Le container Arquillian démarre Vauban (CDI), enregistre les beans du test, expose
  l'endpoint `/metrics` via Chappe (port dynamique).
- La propriété `mp.metrics.appName` est exposée dans le script pour les tests de scoping.
- `RequestContext` Vauban activé au moment du déploiement de chaque archive TCK.
- Contrat strict `aroundInvoke` requis par le TCK : sur métrique supprimée du registre,
  `CountedInterceptor`/`TimedInterceptor` lèvent `IllegalStateException`
  (test TCK `removeCounterFromRegistry` / `removeTimerFromRegistry` exigent ce comportement).
  Les tests unitaires et d'intégration CDI Dirac pré-enregistrent donc les métriques
  comme le fait `@AroundConstruct` en production.

**Livrable M8 ✅ :** TCK officiel MicroProfile Metrics 5.1.1 passé à 127/127 (0 failures, 0 errors, 0 skipped). Rapport reproductible via `./run-official-tck-mp-metrics-5.1.sh all` (cf. `dirac-tck/target/tck-report.txt`). Reste à produire `TCK.md` et `dirac-tck/README.md`.

---

## Risques connus

| Risque | Impact | Mitigation |
|---|---|---|
| Artefact TCK non-public | Blocage si l'artefact n'est pas dans le M2 local | Documentation dans `dirac-tck/README.md` ; script d'installation |
| Reservoir HDR vs implémentation maison | Précision des percentiles, license HDR | Implémenter d'abord un reservoir EWMA maison ; HDR si les benchmarks montrent un écart |
| Endpoint REST TCK | Le TCK teste l'endpoint HTTP — Chappe + Cassini doivent être opérationnels | Tester l'endpoint dès M7 avant le TCK |
| `@Gauge` et types génériques | `MethodHandle` sur méthode générique peut nécessiter un cast | Tester avec `Gauge<Long>`, `Gauge<Integer>`, `Gauge<Double>` dès M2 |
| Scoping des métriques | `APPLICATION` scope doit être réinitialisé entre les déploiements Arquillian | Nettoyer le registre dans `undeploy()` du container Arquillian |
| Format OpenMetrics strict | Le TCK vérifie le format exact (espacements, suffixes) | Implémenter des tests de format caractère par caractère |
| Concurrence registre | Enregistrement concurrent du même `MetricID` | `computeIfAbsent` dans `MetricRegistryImpl` — valider avec 500 virtual threads |

## Décisions actées

- [x] Séparation `dirac-core` (implémentations pures) / `dirac-cdi-vauban` (intercepteurs CDI)
- [x] `LongAdder` pour `Counter` (haute concurrence sans contention)
- [x] `MethodHandle` pour `@Gauge` (résolution au démarrage, pas de réflexion runtime)
- [x] `System.nanoTime()` pour `Timer` (jamais `currentTimeMillis()`)
- [x] Reservoir EWMA maison pour `Histogram` (évite HDR Histogram comme dépendance tierce)
- [x] Format OpenMetrics en priorité sur JSON (text/plain est le format par défaut)
- [x] `dirac-rest` est optionnel (module séparé, pas de dépendance depuis `dirac-core`)
- [x] `dirac-tck` hors reactor (contrainte commune à tout l'écosystème Vidocq — ShrinkWrap)

## Décisions ouvertes

- **Reservoir HDR Histogram** : utiliser l'algorithme EWMA maison ou HdrHistogram (dépendance) ?
  Décision à prendre après les premiers benchmarks M4.
- **Timestamps OpenMetrics** : les inclure par défaut ou les rendre optionnels via MP Config ?
  La spec MP Metrics 5.1 ne les impose pas.
- **`mp.metrics.appName`** : préfixe optionnel pour le scope APPLICATION — implémenter dès M1
  ou différer au TCK ?
- **Intégration `vidocq-mps`** : définir l'extension MPS Dirac après que TCK soit vert.
- **GraalVM native-image** : `@Gauge` utilise `MethodHandle` au démarrage — compatible AOT
  uniquement si le `MethodHandle` est résolu à compile-time (via `classfile-codegen`).
  Évaluer si une phase de génération statique est nécessaire.
