# Ravel - Claude Code Guidelines

> Maurice Ravel (1875–1937) était maître de l'orchestration — il savait tisser des sources
> disparates (cordes, vents, percussions) en un tout cohérent et hiérarchisé.
> C'est exactement ce que fait un système de configuration : agréger des sources hétérogènes
> (fichiers, variables d'environnement, propriétés système) en une configuration unifiée.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK MicroProfile Config 3.1 est un artefact **public Maven Central** :
  `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
  (contrairement aux TCK Jakarta, pas besoin de l'installer manuellement).
  Note : la version d'artefact `3.1.1` est une re-release de la spec 3.1 publiée le
  2026-04-22 qui ajoute `Automatic-Module-Name` au manifest — contenu fonctionnel identique
  à la 3.1 mais utilisable en JPMS strict. La version 3.1 originale (sans descripteur
  modulaire) ne doit pas être utilisée.

## Commandes essentielles

```bash
# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# Benchmarks JMH
./mvnw -pl ravel-bench -am package
java -jar ravel-bench/target/benchmarks.jar

# TCK — smoke test seulement
./run-official-tck-mp-config-3.1.sh

# TCK — suite complète
./run-official-tck-mp-config-3.1.sh all

# TCK — test ciblé
./run-official-tck-mp-config-3.1.sh -Dtest=NomDuTest
```

> `ravel-tck` est **hors reactor** (POM Model 4.0.0 standalone) pour contourner
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — même contrainte que `cassini-tck`,
> `foy-tck`, et `champollion-tck`. Ne pas changer ce modèle.

## Architecture

Ravel est une implémentation MicroProfile Config 3.1, **zéro librairie tierce** (pas de Smallrye,
Guava, etc.), uniquement des specs Jakarta EE / MicroProfile en dépendances, virtual threads, JPMS strict.

```
ravel-api          ← Re-expose la spec org.eclipse.microprofile.config (ConfigProvider, Config,
                     ConfigSource, ConfigSourceProvider, Converter, ConfigBuilder)
ravel-core         ← Implémentation : ConfigSources (SysProps/EnvVars/Properties),
                     type converters built-in et SPI, property expressions, config profiles
ravel-cdi-vauban   ← Intégration CDI Vauban : @Inject @ConfigProperty, BCE, Optional injection
ravel-bench        ← JMH : comparatif Smallrye Config / Helidon Config, throughput lookup, overhead
ravel-tck          ← Runner TCK officiel MicroProfile Config 3.1 (HORS reactor)
```

**Flux d'une lookup :**
`ConfigProvider.getConfig()` → `RavelConfig` → cascade des `ConfigSource` par ordinal décroissant
→ résolution des expressions (`${key}`) → conversion via `Converter<T>` → valeur typée.

**Sources de configuration (ordinal spec MicroProfile) :**
- `SystemPropertiesConfigSource` — ordinal 400, `-Dkey=val`
- `EnvironmentVariablesConfigSource` — ordinal 300, mapping tirets/points/casse
- `MicroprofilePropertiesConfigSource` — ordinal 100, `META-INF/microprofile-config.properties`
- Sources tierces via `ConfigSourceProvider` SPI (ServiceLoader)

**Deux niveaux de conversion :**
- **Built-in** : `String`, primitifs Java, `OptionalInt/Long/Double`, `URL`, `URI`, `InetAddress`,
  `Duration`, `LocalDate/Time/DateTime`, énumérations, tableaux et `List`/`Set` (séparateur virgule)
- **Custom** : `Converter<T>` via ServiceLoader ou `ConfigBuilder.withConverter()`

## Contraintes d'architecture à ne pas violer

1. **`ravel-core` ne dépend que de `org.eclipse.microprofile.config`** — pas de CDI, pas de Servlet,
   pas d'autres specs Jakarta. Le cœur config doit fonctionner en standalone SE sans aucun container.
2. **`ravel-cdi-vauban` dépend de `ravel-core` + `jakarta.cdi`** mais jamais l'inverse — l'intégration
   CDI est un module optionnel qui n'est pas visible depuis le cœur.
3. **JPMS strict** : tous les modules ont un `module-info.java`, packages `internal.*` non exportés,
   SPI exposée uniquement via `provides ... with`.
4. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly. Utiliser `ScopedValue`
   pour tout contexte propagé (ex. résolution cyclique détectée dans `ExpressionResolver.STACK`).
5. **Pas de réflexion `setAccessible(true)`** sauf pour l'implicit converter (constructeur `String`,
   méthode `valueOf`/`parse`) — documenter toute ouverture JPMS nécessaire dans le `module-info`.
6. **Détection de cycle dans les property expressions** : une expression qui se référence elle-même
   (directement ou indirectement) doit lever `IllegalArgumentException`, pas boucler infiniment.
7. **TCK MicroProfile Config 3.1 PASS à 100 %** est un contrat avant tout merge structurel.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java`.
- **Packages** :
  - `io.vidocq.ravel.spi.*` = SPI public stable (extensions ConfigSource tierces)
  - `io.vidocq.ravel.internal.*` = code interne (peut casser entre versions)
- **Maven groupId** : `io.vidocq.ravel`.
- **Records** pour les objets immuables (`ConfigValue`, `ConfigEntry`) ;
  **sealed interfaces** pour les hiérarchies fermées (types d'expressions, résultats de lookup).
- **Pattern matching** exhaustif sur switch — pas de chaîne `if/else if`.

## Roadmap

Voir `ROADMAP.md` pour le plan détaillé phase par phase (M0..M5).

- **M0** — Bootstrap reactor Maven, JPMS, `.sdkmanrc`
- **M1** — `ravel-api` + `ravel-core` : 3 sources built-in, converters primitifs, `ConfigProvider`
- **M2** — Converters avancés (tableaux, collections, types temporels), implicit converters
- **M3** — Config Profiles (`%dev.`, `%prod.`, `%test.`), property expressions (`${key}`)
- **M4** — `ravel-cdi-vauban` : `@ConfigProperty`, CDI BCE, injection `Optional<T>`
- **M5** — TCK runner + script, score 100 %, `ravel-bench`

## TDD — Test-Driven Development (obligatoire)

Ravel est développé en **TDD strict**, dans cet ordre :

1. **Red** — écrire le test qui décrit le comportement attendu (citation section spec MicroProfile
   Config 3.1 ou lien vers le paragraphe concerné en commentaire JavaDoc). Le test doit échouer
   pour la bonne raison (compilation OK, assertion KO).
2. **Green** — écrire le minimum de code pour faire passer le test. Pas d'optimisation, pas
   d'abstraction qui anticipe un test futur.
3. **Refactor** — nettoyer en gardant les tests verts. Lancer la suite complète du module avant
   tout commit.

Règles concrètes :

- **Un test par classe publique**, nommé `<Classe>Test`, dans le même package (`src/test/java`).
- **Pas de Mockito** — doubles écrits à la main ou `MapConfigSource` de test inline.
- **Tests par fixture spec** : pour chaque section de la spec MicroProfile Config 3.1 référencée,
  un test nommé `<methode>_spec_section<X>_<Y>()`. Traçabilité spec ↔ test.
- **Coverage mesurée** mais pas érigée en gate ; la qualité du test prime sur le pourcentage.

## TCK — Technology Compatibility Kit

MicroProfile Config TCK — exécuté dans un module hors reactor (`ravel-tck`, POM Model 4.0.0)
pour contourner ShrinkWrap Maven Resolver 3.3 :

| TCK | Artifact | Cible |
|---|---|---|
| MicroProfile Config 3.1 | `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1` | 100 % PASS (contrat) |

Le script `run-official-tck-mp-config-3.1.sh` :

- supporte `smoke` (par défaut), `all`, et `-Dtest=NomDuTest` ciblé ;
- installe le reactor en local (`mvn install -DskipTests`) avant invocation ;
- produit un rapport `target/tck-report.txt` avec le score PASS/FAIL/SKIP.

**Discipline de release :**

- **Aucun merge structurel** sur `ravel-core`/`ravel-cdi-vauban` sans TCK PASS.
- Les éventuels challenges (tests désactivés pour interprétation spec ou bug TCK) sont documentés
  dans `TCK.md` avec citation spec, hash du test, et plan de réactivation.

## Principes IA — collaboration sur ce dépôt

- **Plan mode par défaut** sur tout changement structurel (nouveau module, nouvelle SPI,
  modification d'un `ConfigSource` built-in).
- **Élégance équilibrée** : préférer un design simple qui passe le TCK à un design parfait qui
  ne le passe pas. Documenter les arbitrages dans des ADR (`docs/adr/`).
- **Pas de paresse sur les specs** : citer la section MicroProfile Config 3.1 dans les commentaires
  de code quand l'implémentation y répond directement.
- **Zéro librairie tierce** : les specs Jakarta EE et MicroProfile sont les seules dépendances
  autorisées en scope `provided`/`compile` (CDI, Annotations, etc.). Si une lib d'implémentation
  semble nécessaire, c'est qu'on s'est trompé de découpe.
- Utiliser les agents **`jpms-guardian`**, **`virtual-threads-reviewer`**, **`dependency-gatekeeper`**
  proactivement sur toute modification de `module-info.java`, code concurrent, ou `pom.xml`.
