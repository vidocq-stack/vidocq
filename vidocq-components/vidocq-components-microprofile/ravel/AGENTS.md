# AGENTS.md

## Mission du dépôt
- Ravel implémente **MicroProfile Config 3.1** en Java 25, avec **zéro librairie tierce d'implémentation** : seulement la spec MP Config dans `ravel-core`, Jakarta APIs uniquement côté CDI (`README.md`, `pom.xml`, `CLAUDE.md`).
- Architecture JPMS stricte : `ravel-api` ré-exporte la spec, `ravel-core` reste standalone SE, `ravel-cdi-vauban` est un adaptateur optionnel, `ravel-tck` reste hors reactor.
- Utiliser de préférence roadmap.md pour suivre l'avancement du projet plutot que de mettre à jour ce fichier, qui est destiné à être un guide de contribution pour les agents.
- Si les règles de fichier doivent être mises à jour, penser à aligner claude.md de la même façon, pour que calude code puisse s'y référer facilement.

## État réel du code à connaître avant de modifier
- `M0`, `M1` et `M2` sont terminés (`ROADMAP.md`) : sources built-in + converters built-in/implicit/array + priorité `@Priority`.
- Le flux en place dans `ravel-core` : `ConfigProvider.getConfig()` -> `RavelConfigProviderResolver` -> `RavelConfigBuilder` -> `RavelConfig` -> cascade de `ConfigSource` (ordinal décroissant) -> dispatch via `Converter<T>` (built-in, applicatif, array, implicit).
- Sources built-in actives : `SystemPropertiesConfigSource` (400), `EnvironmentVariablesConfigSource` (300), `MicroprofilePropertiesConfigSource` (100).
- Converters built-in (priorité 1) : primitifs/boxes, `String`, `Class<?>`, `Optional{Int,Long,Double}`, `URI`, `URL`, `InetAddress`, `Duration`, `Period`, `LocalDate/Time/DateTime`, `OffsetTime/DateTime`, `ZonedDateTime`, `Instant`. Boolean §5.1.1 truthy : `true|1|yes|y|on` (case-insensitive).
- Arrays §5.4 résolus à la lookup via `ArraySplitter` + `ArrayConverter` (cache `ConcurrentHashMap` dans `RavelConfig.derivedConverters`). Implicit converters §5.2 détectés sur méthodes/ctor **publics** uniquement (pas de `setAccessible`).
- `module-info.java` de `ravel-core` fournit déjà `ConfigProviderResolver` et déclare `uses` pour `ConfigSource`, `ConfigSourceProvider`, `Converter` : privilégier `ServiceLoader`, pas des hooks maison.
- Priorité actuelle : **M3 = profils + property expressions** ; opérer sur le raw value avant conversion, détection de cycle via `ScopedValue` (jamais `ThreadLocal`).

## Frontières à ne pas casser
- Ne jamais remettre `ravel-tck` dans le reactor : le parent `pom.xml` l'exclut volontairement à cause de ShrinkWrap Maven Resolver / Model 4.0.0 vs 4.1.0.
- `ravel-core` ne dépend que de `org.eclipse.microprofile.config` en compile/runtime ; CDI reste dans `ravel-cdi-vauban`. `jakarta.annotation` est admis **en test scope uniquement** pour valider la lecture de `@Priority`.
- Garder `io.vidocq.ravel.internal.*` non exporté ; toute extension doit passer par la SPI ou les types MicroProfile.
- Pas de `synchronized`, pas de `ThreadLocal`; pour les contextes propagés (ex. cycles d'expressions en M3), utiliser `ScopedValue`.
- Pas de `setAccessible(true)` — les implicit converters ne ciblent que des méthodes/ctor `public`.
- La lecture de `@Priority` est faite par nom qualifié (réflexion sur `Annotation.annotationType().getName()`), jamais par `import jakarta.annotation.Priority` côté production.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) pour tous les tests. Pas de retour à JUnit 5 : la version est pinnée dans `pom.xml` parent (`<junit.version>`) et dans le POM standalone `ravel-tck/pom.xml`. Les tests ciblent `org.junit.jupiter.api.*`, JUnit Platform 2.x.

## Workflows utiles
```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./mvnw -pl ravel-bench -am package
java -jar ravel-bench/target/benchmarks.jar
./run-official-tck-mp-config-3.1.sh
./run-official-tck-mp-config-3.1.sh all
./run-official-tck-mp-config-3.1.sh -Dtest=NomDuTest
```
- Le TCK passe toujours par le script racine, qui installe d'abord le reactor puis invoque `mvn -f ravel-tck/pom.xml -Ptck-official test` (`ravel-tck/README.md`).
- Le TCK n'est pas “pour plus tard” : `ROADMAP.md` le traite comme une vérification continue dès `M2/M3`, même si le contrat 100 % PASS est verrouillé en `M5`.

## Conventions de contribution observées
- TDD strict : Red -> Green -> Refactor, avec citation de la section MicroProfile Config visée dans les tests (`CLAUDE.md`, `ROADMAP.md`).
- Tests dans le même package, nommés `<Classe>Test`; pas de Mockito, utiliser des doubles manuels comme `ravel-core/src/test/java/io/vidocq/ravel/internal/MapConfigSource.java`.
- Les tests d'intégration `ravel-core` complètent les tests unitaires avant le TCK : multi-sources, ordinals, expressions, profils (`ROADMAP.md`).
- La perf est une contrainte de conception, pas un bonus : `ravel-bench` sert à comparer Ravel à Smallrye Config sur la même JVM.

## Ce qu'un agent doit supposer pour les prochaines tâches
- `M3` doit s'insérer **avant** la conversion : profils via `ProfiledConfigSource` (ordinal +1 sur la source enveloppée) et expressions `${key}` / `${key:default}` résolues sur la raw value retournée par la cascade. Détection de cycle = `ScopedValue<Set<String>>` + `IllegalArgumentException`.
- `M4` reste un adaptateur CDI Vauban (Build Compatible Extension), pas une extension du core. Validation au déploiement → `DeploymentException`, pas `NoSuchElementException` à runtime.
- Avant toute modification structurelle de `ravel-core` ou `ravel-cdi-vauban`, raisonner avec le contrat final : **TCK MicroProfile Config 3.1 à 100 % + comparaison JMH reproductible vs Smallrye Config**.
- Suite de tests actuelle : **131 tests verts** sur `ravel-core` ; toute régression doit être expliquée et corrigée avant merge.

