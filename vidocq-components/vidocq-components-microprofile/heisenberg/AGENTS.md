# AGENTS.md

> Ce fichier est le guide de contribution pour les agents IA (GitHub Copilot, Copilot Chat,
> Copilot Workspace). Il doit rester synchrone avec `CLAUDE.md` — toute modification dans
> l'un doit être reflétée dans l'autre.

## Mission du dépôt

- Heisenberg implémente **MicroProfile Fault Tolerance 4.1** en Java 25, avec **zéro librairie
  d'implémentation tierce** : seules les API specs (`microprofile-fault-tolerance-api`,
  `jakarta.enterprise.cdi-api`, `jakarta.interceptor-api`, `microprofile-config-api`) sont
  compilées dans `heisenberg-core` et `heisenberg-cdi-vauban`.
- Architecture JPMS stricte : `heisenberg-api` wrapping de la spec, `heisenberg-core` moteurs
  purs Java 25 sans CDI, `heisenberg-cdi-vauban` intercepteur CDI + BCE Vauban,
  `heisenberg-tck` hors reactor.
- **Pas de SmallRye Fault Tolerance, Hystrix, Resilience4j** dans le code de production.
- Virtual threads (Project Loom) pour `@Asynchronous` et `@Timeout` — `Thread.ofVirtual() + join(Duration)` (Java 21+, finalisé). `StructuredTaskScope` (JEP 505) est disponible depuis Java 25 mais n'est pas utilisé actuellement pour maximiser la compatibilité avec Java 21+.
- Utiliser `ROADMAP.md` pour suivre l'avancement des milestones (M0..M9).
- Si les règles de ce fichier doivent être mises à jour, aligner `CLAUDE.md` dans la même
  opération — les deux fichiers sont des miroirs destinés à des outils différents.

## État réel du code à connaître avant de modifier

- Consulter `ROADMAP.md` pour l'état détaillé de chaque milestone (M0..M9).
- **État à date : M0–M8 terminés, M9 (TCK complet 100 %) en cours (🚧).**
  Dernier run `all` reproductible (TCK.md, 2026-05-16T11:17Z) :
  `424 run / 349 PASS / 61 fail / 14 skip` (~82 % PASS) sur
  `run-official-tck-mp-fault-tolerance-4.1.sh all` — objectif 100 % visé.
  **§9 MP Metrics (Dirac)** : run ciblé `24 run / 1 PASS / 23 fail` (infra CDI OK,
  publication des métriques FT encore manquante). **§10 OpenTelemetry (Humboldt)** :
  tests exclus du profil tant que MP Telemetry n'est pas livré.
- Tous les moteurs existent et sont câblés dans `heisenberg-core` :
  `RetryEngine`, `TimeoutEngine`, `CircuitBreakerEngine` (`CircuitBreakerState`
  CLOSED/OPEN/HALF_OPEN), `BulkheadEngine`, `BulkheadStateRegistry`,
  `CircuitBreakerStateRegistry`, `FallbackResolver`, `FallbackPolicy`,
  `PolicyComposer`, `AsynchronousEngine`, `AnnotationReader`, `ConfigResolver`,
  configs immuables (`RetryConfig`, `TimeoutConfig`, `CircuitBreakerConfig`,
  `BulkheadConfig`, `FallbackConfig`).
- Dans `heisenberg-cdi-vauban` (intercepteur + BCE) :
  `FaultToleranceInterceptor` (priorité 4010), `FaultTolerancePriority3850Interceptor`
  (variante TCK pour la priorité 3850), `FaultToleranceBinding` (marqueur),
  `HeisenbergExtension` (BCE CDI 4.1 — validations + ré-écriture `@Priority`),
  `StateRegistryBean` + `BulkheadStateRegistryBean` (`@ApplicationScoped`),
  `HeisenbergAutoDiscovery` (bridge ServiceLoader vers Ravel),
  **recorders métriques §9 / §10** (`DiracFtMetricsRecorder` + `OtelFtMetricsRecorder`,
  fan-out via `CompositeFtMetricsRecorder` et `MetricsRecorderResolver`).
- Modules JPMS effectifs : `io.vidocq.heisenberg.api`, `io.vidocq.heisenberg.core`,
  `io.vidocq.heisenberg.cdi.vauban` (note : suffixe `.vauban`, pas `.cdi` seul).
- `heisenberg-core` exporte son package interne **en export qualifié** :
  `exports io.vidocq.heisenberg.internal to io.vidocq.heisenberg.cdi.vauban;` — toute
  nouvelle classe interne reste invisible hors `cdi-vauban` sans modification du
  `module-info.java`.
- Le BCE est un **CDI 4.1 Build Compatible Extension**
  (`jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`) — pas
  l'ancien `jakarta.enterprise.inject.spi.Extension` portable. Déclaré via
  `provides … with io.vidocq.heisenberg.cdi.internal.HeisenbergExtension` dans le
  `module-info` de `cdi-vauban`.
- Le flux effectif dans `heisenberg-cdi-vauban` :
  `@Retry @Timeout @CircuitBreaker @Bulkhead @Fallback` sur une méthode CDI →
  `FaultToleranceInterceptor.around(InvocationContext)` →
  `AnnotationReader.read(ctx)` → `PolicyComposer.invoke(...)` →
  exécution de la chaîne : `FallbackPolicy` → `CircuitBreakerEngine` → `BulkheadEngine`
  → `TimeoutEngine` → `RetryEngine` → `ctx.proceed()`.
- `StateRegistryBean` / `BulkheadStateRegistryBean` (`@ApplicationScoped`) stockent
  les états `CircuitBreakerState` et `BulkheadSemaphore` via
  `ConcurrentHashMap<StateKey, ...>` où
  `StateKey = (beanClass.getName() + "#" + method.getName() + descriptor)`.
- La SPI exportée est `io.vidocq.heisenberg.api.*` :
  `FaultToleranceException`, `FtMetricsRecorder` (avec `NOOP` et enums
  `RetryResult` / `CBCallResult` / `CBState`).

## Frontières à ne pas casser

- Ne jamais remettre `heisenberg-tck` dans le reactor : exclu volontairement à cause de
  ShrinkWrap Maven Resolver / incompatibilité Model 4.0.0 vs 4.1.0 (contrainte commune
  à tout l'écosystème Vidocq).
- `heisenberg-core` ne doit importer **aucune** classe CDI (`jakarta.enterprise.*`,
  `jakarta.inject.*`) — uniquement `microprofile-fault-tolerance-api`,
  `jakarta.interceptor-api` (pour `InvocationContext`) et `microprofile-config-api`.
- **Pas de `synchronized`** — utiliser `ReentrantLock.tryLock(timeout)`, `Semaphore`,
  `AtomicReference` pour l'état `CircuitBreaker`. Les `synchronized` pinent les virtual threads.
- **Pas de `ThreadLocal`** — utiliser `ScopedValue` (JEP 506) pour propager le contexte
  d'exécution à travers les appels virtuels.
- **Pas de `java.lang.reflect.Proxy`** — toute résolution de `fallbackMethod` passe par
  `MethodHandles.lookup().findVirtual(...)`.
- **Pas de `setAccessible(true)`** en production — ouvrir les packages nécessaires dans le
  `module-info.java` et documenter pourquoi.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) pour les tests `heisenberg-core` et
  `heisenberg-cdi-vauban`. Le TCK utilise **TestNG** (contrainte upstream).
- Tout ajout de dépendance `<scope>compile|runtime</scope>` exige un passage par l'agent
  `dependency-gatekeeper` et une justification explicite dans la PR.

## Convention JPMS — workaround `module-info` + `target/javamodules/`

- Dans `heisenberg-core` et `heisenberg-cdi-vauban`, le `module-info.java` vit sous
  `src/main/module-info/` (et **non** `src/main/java/`). C'est intentionnel : empêche
  Maven Compiler Plugin de basculer en mode JPMS lors de `testCompile` (les dépendances
  test-scope comme Vauban/Ravel ne sont pas sur le module-path). Le `module-info.class`
  est compilé seul en phase `prepare-package`, et `maven-clean-plugin` le supprime avant
  les builds incrémentaux. `heisenberg-api` garde son `module-info.java` sous
  `src/main/java/` (pas de test-scope CDI à isoler).
- Les tests s'exécutent en classpath (`useModulePath=false`) ; le câblage JPMS est validé
  par le smoke TCK uniquement.
- Le module-path de compilation est construit via `maven-dependency-plugin` en phase
  `initialize`, qui copie les JARs requis dans `target/javamodules/`. Tout ajout de
  dépendance à mettre sur le module-path doit être référencé dans cette copie.
- `microprofile-fault-tolerance-api:4.1` n'a ni `Automatic-Module-Name` ni
  `module-info.class` : son nom JPMS est `microprofile.fault.tolerance.api` (dérivé du
  nom d'artefact). C'est ce nom qui doit apparaître dans les `requires`, pas
  `org.eclipse.microprofile.faulttolerance`.
- `heisenberg-cdi-vauban` déclare les APIs Jakarta (`jakarta.cdi`, `jakarta.inject`,
  `jakarta.annotation`, `jakarta.interceptor`) en `requires static` — fournies par
  le container à l'exécution.

## Workflows utiles

```bash
# Initialiser l'environnement SDK
sdk env

# Build reactor complet
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test (installe le reactor puis lance le TCK)
./run-official-tck-mp-fault-tolerance-4.1.sh

# TCK — suite complète
./run-official-tck-mp-fault-tolerance-4.1.sh all

# TCK — test ciblé (ex : RetryTest)
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest

# Benchmarks JMH
./mvnw -pl heisenberg-bench -Pbench package
java -jar heisenberg-bench/target/benchmarks.jar
```

- Le TCK passe toujours par le script racine qui installe d'abord le reactor, puis invoque
  `mvn -f heisenberg-tck/pom.xml -Ptck-official test`.
- Le TCK nécessite que l'artefact non-public soit dans le M2 local — voir
  `heisenberg-tck/README.md` pour la procédure d'installation.

## Conventions de contribution observées

- **TDD strict** : Red → Green → Refactor. Aucune ligne de production sans test préalable.
  Citer la section spec MicroProfile FT 4.1 dans les commentaires de test (ex : `// §2.5.3`).
- Tests unitaires dans le même package que la classe testée, nommés `<Classe>Test`.
- Pas de Mockito — doubles manuels (`FakeInvocationContext`, `FakeConfigSource`, etc.).
- La logique de chaque moteur (`RetryEngine`, `CircuitBreakerEngine`, etc.) est testée
  unitairement sans container CDI — c'est le but de la séparation `heisenberg-core` /
  `heisenberg-cdi-vauban`.
- Benchmarks JMH dans `heisenberg-bench` — comparatif vs SmallRye Fault Tolerance sur la
  même JVM. Résultats consignés dans `BENCH.md` à la racine du projet.
- Bugs reproductibles tracés dans `BUG.md` avec : id, date, symptôme, repro, hypothèse, statut.

## Ce qu'un agent doit supposer pour les prochaines tâches

- `heisenberg-core` est la brique fondatrice : `RetryEngine`, `TimeoutEngine`,
  `CircuitBreakerEngine` (avec `CircuitBreakerState` : CLOSED/OPEN/HALF_OPEN),
  `BulkheadEngine`, `FallbackResolver`, `PolicyComposer`, `AsynchronousEngine`,
  `ConfigResolver`. **Tous existent et sont testés** (108 tests unitaires verts).
  Aucun n'importe de classe CDI.
- `heisenberg-cdi-vauban` est le point d'entrée CDI : `FaultToleranceInterceptor`
  (priorité de base 4010, configurable via `mp.fault.tolerance.interceptor.priority`),
  `FaultTolerancePriority3850Interceptor` (variante TCK),
  `HeisenbergExtension` (BCE CDI 4.1 `BuildCompatibleExtension` sous
  `io.vidocq.heisenberg.cdi.internal`, qui valide les annotations au démarrage du container
  et ré-écrit `@Priority` selon la config),
  `StateRegistryBean` + `BulkheadStateRegistryBean` (`@ApplicationScoped`),
  `DiracFtMetricsRecorder` (§9 MP Metrics) et `OtelFtMetricsRecorder` (§10 OpenTelemetry)
  qui coexistent via fan-out (`CompositeFtMetricsRecorder`, `MetricsRecorderResolver`).
- La configuration externe suit la précédence MP FT 4.1 §9 :
  `<className>/<methodName>/<AnnotationName>/<parameter>` >
  `<className>/<AnnotationName>/<parameter>` >
  `<AnnotationName>/<parameter>`.
  Résolution via `ConfigProvider.getConfig()` (Ravel dans l'écosystème Vidocq).
- L'ordre de composition des politiques (§2.5) de l'extérieur vers l'intérieur :
  `@Fallback` → `@CircuitBreaker` → `@Bulkhead` → `@Timeout` → `@Retry` → méthode.
  Respecter scrupuleusement cet ordre dans `PolicyComposer`.
- `@Asynchronous` change le type de retour : `CompletionStage<T>` ou `Future<T>`.
  L'exécution se fait via `Executors.newVirtualThreadPerTaskExecutor()` — pas de pool platform.
- **Métriques §9 (MP Metrics) et §10 (OpenTelemetry)** : les deux APIs sont publiables
  simultanément, le `Composite` fan-out chaque appel sur tous les recorders présents.
  Pour ajouter une nouvelle métrique côté §10 : éditer `OtelFtMetricsRecorder` (noms et
  attributs définis par le TCK `TelemetryMetricDefinition`, unités `seconds` pour les
  durées, bucket boundaries explicites définies dans `histogram(name, "seconds")`).
- **Test enricher Arquillian** (`heisenberg-tck/src/test/java/.../VaubanTckBootstrap.java`) :
  la liste des beans enregistrés au container est **explicite**. Tout nouveau bean
  `@ApplicationScoped` côté Heisenberg destiné à être visible dans le TCK doit y être
  ajouté (sinon Vauban ne le découvre pas en mode TCK).
- Avant toute modification structurelle du `PolicyComposer` ou des `*StateRegistryBean`,
  raisonner avec le contrat final : **TCK MicroProfile Fault Tolerance 4.1 à 100 % PASS**
  (objectif visé ; à date : ~82 % PASS = 349/424, jalon M9 en cours — voir `TCK.md`).
