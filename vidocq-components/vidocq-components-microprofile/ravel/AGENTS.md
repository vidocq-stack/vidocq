# AGENTS.md

## Objectif du dépôt
- Ravel implémente **MicroProfile Config 3.1** en Java 25, avec **zéro librairie tierce d'implémentation** (`README.md`, `CLAUDE.md`).
- Architecture modulaire JPMS stricte : API, core standalone, intégration CDI optionnelle, bench, et runner TCK séparé.

## Carte des modules (à lire avant de coder)
- `ravel-api/` : façade JPMS de la spec (`ravel-api/src/main/java/module-info.java`).
- `ravel-core/` : moteur config standalone SE, sans CDI (`ravel-core/src/main/java/module-info.java`).
- `ravel-cdi-vauban/` : intégration CDI optionnelle, dépend de `ravel-core` (`ravel-cdi-vauban/src/main/java/module-info.java`).
- `ravel-bench/` : benchmarks JMH (voir commandes dans `CLAUDE.md`).
- `ravel-tck/` : runner TCK officiel, **hors reactor** (`ravel-tck/README.md`, `ravel-tck/pom.xml`).

## Contraintes structurelles non négociables
- Ne pas ajouter `ravel-tck` dans le reactor (`pom.xml` `<subprojects>` exclut volontairement ce module).
- Conserver la séparation : `ravel-core` ne dépend pas de CDI ; CDI reste dans `ravel-cdi-vauban`.
- Respecter JPMS : `module-info.java` explicite, packages internes non exportés (`io.vidocq.ravel.internal`).
- Respecter les règles runtime : pas de `synchronized`, pas de `ThreadLocal`, préférer `ScopedValue` (`CLAUDE.md`).

## Flux fonctionnel attendu (quand le core évolue)
- Lookup cible : `ConfigProvider.getConfig()` -> `RavelConfig` -> `ConfigSource` (ordinals) -> expressions `${...}` -> `Converter<T>`.
- Ordinals documentés : System props 400, env vars 300, `microprofile-config.properties` 100 (`CLAUDE.md`).
- Extension via SPI/ServiceLoader, pas via hooks ad hoc.

## Workflows développeur utiles
- Préparer l'environnement (SDKMAN) :
```bash
sdk env
```
- Build reactor (sans TCK) :
```bash
./mvnw -ntp install -DskipTests
```
- Tests unitaires :
```bash
./mvnw test
```
- Bench JMH :
```bash
./mvnw -pl ravel-bench -am package
java -jar ravel-bench/target/benchmarks.jar
```
- TCK officiel (toujours via script racine) :
```bash
./run-official-tck-mp-config-3.1.sh
./run-official-tck-mp-config-3.1.sh all
./run-official-tck-mp-config-3.1.sh -Dtest=NomDuTest
```

## Convention de contribution observée
- TDD strict Red -> Green -> Refactor avec traçabilité spec dans les tests (`CLAUDE.md`).
- Nommage des tests : `<Classe>Test` ; pas de Mockito (doubles manuels / map source de test).
- En phase de bootstrap actuelle, plusieurs modules sont encore squelettiques (voir `module-info.java` et `ravel-tck/README.md` statut M0).

## Dépendances et intégrations externes
- API cible : `org.eclipse.microprofile.config:microprofile-config-api:3.1.1` (et TCK `microprofile-config-tck:3.1.1`).
- `ravel-tck` active le profil `tck-official` et désactive le module path Surefire (`ravel-tck/pom.xml`).
- Dépôts/artifacts internes Vidocq configurés au parent (`pom.xml`) ; éviter d'introduire de nouvelles sources non justifiées.

