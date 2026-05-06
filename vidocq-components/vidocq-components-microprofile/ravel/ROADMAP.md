# Ravel — Plan d'attaque

> Implémentation MicroProfile Config 3.1 dans le style Vidocq : zéro librairie tierce
> (specs Jakarta EE / MicroProfile autorisées), JDK 25, virtual threads, JPMS strict,
> intégration CDI optionnelle via Vauban.

## Principes directeurs

| Principe | Application concrète |
|---|---|
| Zéro librairie tierce | Pas de Smallrye Config, Helidon Config, Apache Commons Config dans `ravel-core`. Seules les API specs (`microprofile-config-api`, `jakarta.inject`, `jakarta.cdi-api`, `jakarta.annotation-api`) sont compilées. |
| Specs Jakarta / MicroProfile autorisées | `ravel-cdi-vauban` peut dépendre de `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. Le cœur `ravel-core` reste limité à `microprofile-config-api`. |
| Virtual threads | Pas de `synchronized`, pas de `ThreadLocal`. Caches `ConcurrentHashMap`/`ClassValue`. Détection de cycle d'expressions via `ScopedValue`. |
| JPMS strict | `module-info.java` partout, packages `internal.*` non exportés, SPI via `provides/uses`. Pas d'`opens` non justifié. |
| TDD strict | Red → Green → Refactor. Tests écrits avant le code de prod. Citation systématique de la section spec MicroProfile Config 3.1 dans le JavaDoc des tests. |
| TCK PASS 100 % | Contrat dur sur MicroProfile Config 3.1 TCK avant tout merge structurel. |
| Performance mesurée | JMH dès M1, comparatif systématique avec Smallrye Config (référence MP), baseline ratchet. |
| AOT-friendly | Pas de génération dynamique de proxy, pas de `setAccessible(true)` à chaud sauf pour les implicit converters (constructeur `String` / `valueOf` / `parse`). Compatible GraalVM `native-image`. |

## Méthodologie : TDD + TCK comme garde-fous parallèles

Ravel est développé en **TDD strict** (Red → Green → Refactor). Aucune ligne de production n'est
écrite avant un test qui la justifie. Au-delà du cycle TDD interne :

- **Couche 1 — tests unitaires TDD** : pilotent la conception de chaque classe.
- **Couche 2 — tests d'intégration `ravel-core`** : scénarios multi-sources, cascade d'ordinals,
  expressions imbriquées, profils combinés. Indépendants du TCK et reproductibles sans Arquillian.
- **Couche 3 — TCK officiel** (`microprofile-config-tck:3.1.1`) : contrat 100 % PASS avant tout merge
  structurel. Module hors reactor (POM Model 4.0.0).
- **Couche 4 — Bench JMH** : `ravel-bench` compare lookup throughput, expression resolution overhead,
  conversion cost vs Smallrye Config sur la même JVM.

## Phases

### M0 — Bootstrap ✅

- [x] `.sdkmanrc` (`java=25-tem`, `maven=4.0.0-rc-5`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile)
- [x] `CLAUDE.md`
- [x] `ROADMAP.md` (ce fichier)
- [x] Création des 5 sous-modules avec `pom.xml` + `module-info.java` squelettes :
      `ravel-api`, `ravel-core`, `ravel-cdi-vauban`, `ravel-bench`, `ravel-tck` (hors reactor)
- [x] `LICENSE` (Apache 2.0)
- [x] `README.md`
- [x] Validation `mvn -ntp install -DskipTests` réussit (reactor + ravel-tck standalone)

**Livrable :** `mvn -ntp install -DskipTests` réussit sur le reactor (ravel-api, ravel-core,
ravel-cdi-vauban, ravel-bench) et compile aussi le projet hors-reactor `ravel-tck` (POM Model 4.0.0).
Tous les `module-info.java` peuvent `requires org.eclipse.microprofile.config` grâce à la version
**3.1.1** (publiée 2026-04-22) qui ajoute `Automatic-Module-Name: org.eclipse.microprofile.config`
dans le manifest du JAR API — la 3.1 originale (publiée 2023) n'avait ni descripteur ni nom auto.

---

### M1 — Sources built-in + lookup de base

**Scope spec :** §3 (ConfigSource), §4 (Built-in ConfigSources), §2.1 (Config lookup).

| Tâche | Notes |
|---|---|
| `RavelConfig` implémente `org.eclipse.microprofile.config.Config` | `getValue`, `getOptionalValue`, `getValues`, `getOptionalValues`, `getPropertyNames`, `getConfigSources` |
| `RavelConfigBuilder` implémente `ConfigBuilder` | `addDefaultSources`, `addDiscoveredSources`, `addDiscoveredConverters`, `withSources`, `forClassLoader` |
| `RavelConfigProviderResolver` extends `ConfigProviderResolver` | Singleton via ServiceLoader, un `Config` par `ClassLoader`, `registerConfig`/`releaseConfig` thread-safe |
| `META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver` | Point d'entrée ServiceLoader |
| `SystemPropertiesConfigSource` (ordinal 400) | Lecture `System.getProperties()` à chaque appel (mutable runtime) |
| `EnvironmentVariablesConfigSource` (ordinal 300) | Mapping spec §7.6 : `MY_VAR` ↔ `my.var` ↔ `my-var`, les trois formes essayées |
| `MicroprofilePropertiesConfigSource` (ordinal 100) | Charge tous les `META-INF/microprofile-config.properties` du `ClassLoader` (multi-fichiers possibles) |
| `ConfigSourceProvider` SPI via ServiceLoader | Permet aux extensions tierces d'ajouter des sources |
| `ConfigValue` impl avec metadata (source name, raw value, ordinal) | Section §2.1.5 spec |
| Tests : cascade d'ordinals, sources mutables, propriété absente | Coverage des 3 sources + edge cases (clé null, valeur vide) |

**Livrable :** `ConfigProvider.getConfig().getValue("key", String.class)` fonctionne avec les 3 sources canoniques. Tests unitaires verts.

---

### M2 — Converters de types

**Scope spec :** §5 (Converter), §5.1 (Built-in converters), §5.2 (Automatic converters / Implicit converters), §5.3 (Custom converters).

| Tâche | Notes |
|---|---|
| Built-in stricts (§5.1) | `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `float`/`Float`, `double`/`Double`, `String`, `Class<?>` |
| Built-in étendus | `OptionalInt`, `OptionalLong`, `OptionalDouble`, `Character`, `URI`, `URL`, `InetAddress`, `Duration`, `Period`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, énumérations |
| Convertisseurs `Boolean` (§5.1.1) | `true` / `1` / `yes` / `y` / `on` (case-insensitive) → `true`, sinon `false` |
| Conversion tableaux et `List`/`Set` (§5.4) | Séparateur virgule, escape `\,`, valeurs vides ignorées |
| `Converter<T>` SPI via ServiceLoader (`META-INF/services/org.eclipse.microprofile.config.spi.Converter`) | Priorité via `@Priority`, défaut 100 |
| Implicit converters (§5.2) | Détection par réflexion d'un seul de : `static T of(String)`, `static T valueOf(String)`, `static T parse(CharSequence)`, constructeur `(String)` |
| `ConfigBuilder.withConverter(Class<T>, int priority, Converter<T>)` | Programmatic registration |
| Tests par converter, cas limites | Virgule seule, backslash final, valeur null, type incompatible → `IllegalArgumentException` |

**Livrable :** `config.getValue("timeout", Duration.class)` fonctionne pour tous les types built-in et automatiques. Custom converter découvert via ServiceLoader.

---

### M3 — Config Profiles + Property Expressions

**Scope spec :** §7.5 (Configuration profile), §7.2 (Configuration property expression).

| Tâche | Notes |
|---|---|
| Lecture `mp.config.profile` au démarrage du `Config` | Une seule valeur ; mode "actif" stocké dans `RavelConfig` immutable |
| Préfixe `%<profile>.` sur toute source | Wrapping `ProfiledConfigSource` qui réécrit les clés et hérite ordinal + 1 |
| Activation des profils en cascade | `mp.config.profile=dev` → `%dev.app.url` masque `app.url` (ordinal +1) |
| Expression `${key}` (§7.2) | Resolver récursif sur le raw value lu depuis le `ConfigSource` |
| Expression `${key:default}` | Default value si la clé n'est pas trouvée |
| Imbrication `${${env}.url}` | Resolver récursif, profondeur testée |
| Échappement `\$` | Pas d'interpolation |
| Détection de cycle | Via `ScopedValue<Set<String>>` (pas `ThreadLocal`), `IllegalArgumentException` levée |
| Désactivation possible via `mp.config.property.expressions.enabled=false` | Section §7.2 spec |
| Tests : profil absent / présent / multiple, cycle direct, cycle indirect, échappement | Couverture exhaustive |

**Livrable :** `config.getValue("%dev.database.url", String.class)` activé via `mp.config.profile=dev`. Expressions résolues, cycles détectés.

---

### M4 — Intégration CDI (`ravel-cdi-vauban`)

**Scope spec :** §6 (CDI integration), §6.1 (`@ConfigProperty`), §6.2 (`Config` injection), §6.3 (Optional injection).

| Tâche | Notes |
|---|---|
| `ConfigCdiExtension` (Build Compatible Extension Vauban) | Détecte les points d'injection `@ConfigProperty` à la compilation |
| Génération de producers `@Produces @ConfigProperty` par type | Un par couple (type, defaultValue) — déduplication |
| Support `Optional<T>` (§6.3) | Si la propriété est absente → `Optional.empty()`, pas de `NoSuchElementException` |
| Support `Provider<T>` / `Supplier<T>` | Lookup à chaque `get()` (dynamic injection) |
| `@Inject Config config` | Injection du `Config` complet via producer dédié |
| `@ConfigProperty(defaultValue=…)` | Respecté ; valeur par défaut convertie comme une valeur normale |
| Validation au déploiement (§6.4) | Propriété manquante sans `defaultValue` et type non-`Optional` → `DeploymentException` (pas `NoSuchElementException` à runtime) |
| Tests d'intégration avec Vauban embedded | Vérification BCE, scope `@Dependent` par défaut |
| Pas d'opens JPMS sur les beans utilisateurs | Le BCE génère du code, pas de réflexion runtime sur les classes utilisateur |

**Livrable :** `@Inject @ConfigProperty(name="app.name", defaultValue="vidocq") String name;` fonctionne dans Vauban. Tests d'intégration verts.

---

### M5 — TCK + Bench

**Scope :** validation officielle MicroProfile Config 3.1 + benchmarks comparatifs.

| Tâche | Notes |
|---|---|
| `ravel-tck/pom.xml` Model 4.0.0 standalone | Idem `cassini-tck`/`foy-tck`/`champollion-tck` — hors reactor |
| Runner Arquillian + harness officiel `microprofile-config-tck:3.1.1` | Artefact public Maven Central (pas d'install local nécessaire) |
| Adapter Arquillian → Ravel embedded | Soit standalone (sans CDI), soit via Vauban embedded selon le test |
| `run-official-tck-mp-config-3.1.sh` | Modes : smoke (défaut) / all / `-Dtest=NomTest` |
| `TCK.md` | Documente les éventuels challenges (tests désactivés avec citation spec + plan de réactivation) |
| Score contrat : 100 % PASS | Régression bloquante sur `ravel-core`/`ravel-cdi-vauban` |
| `ravel-bench` JMH | Lookup throughput (cache hit / cache miss), expression resolution overhead, conversion cost |
| Comparatif JMH vs Smallrye Config | Même JVM, mêmes paramètres ; cible : ≥ Smallrye sur lookup simple |

**Livrable :** scripts shell + rapport TCK reproductible. Bench publié dans `BENCH.md`.

---

### M6 — Intégration écosystème Vidocq

| Tâche | Notes |
|---|---|
| Adapter `cassini-ravel` (côté Cassini) : `@ConfigProperty` injectable dans les ressources REST | Nécessite Vauban + `ravel-cdi-vauban` |
| Adapter `chappe-ravel` (optionnel) : configuration du serveur HTTP via `Config` | Port, TLS keystore path, etc. |
| Adapter `vauban-ravel` : `Config` enregistré comme bean CDI dans Vauban core | Pour usage interne de Vauban (lecture des extensions activées via config) |
| Documentation : `docs/integration-cassini.md`, `docs/integration-chappe.md`, `docs/integration-vauban.md` | Exemples concrets |
| Bench end-to-end : Cassini + Ravel vs Cassini + Smallrye Config | Throughput requêtes REST avec injection `@ConfigProperty` |

**Livrable :** Vidocq-MPS livre une release avec Ravel comme implémentation MP Config par défaut, sans dépendance Smallrye.

---

## Ordre de priorité — pourquoi celui-ci ?

1. **M1 (sources + lookup)** d'abord parce que tout le reste s'y appuie : pas de converter sans
   valeur brute, pas de profil sans source. Le minimum vital de la spec tient en ce milestone.
2. **M2 (converters)** avant M3 (profils/expressions) : les profils et expressions opèrent sur le
   *raw value* avant conversion ; la conversion est la couche feuille. Tester M3 sans M2 forcerait
   tout à passer par `String`, ce qui masquerait des bugs de conversion.
3. **M4 (CDI)** après M1+M2+M3 : l'intégration CDI est un *adapter* sur le `Config` complet. Tant
   que `Config` n'est pas conforme à la spec, l'injection CDI ne peut pas être validée.
4. **M5 (TCK)** est une activité continue dès M2/M3 (les premiers tests TCK doivent déjà passer
   sur les sections couvertes), mais l'objectif "100 % PASS" ne devient un contrat qu'à la fin
   du milestone M5.
5. **M6 (intégration)** vient en dernier : on ne polluera pas Cassini / Chappe / Vauban avant
   que Ravel soit solide. Le swap se fera derrière une PR dédiée par projet client.

## Risques connus

| Risque | Mitigation |
|---|---|
| Mapping env vars (§7.6) avec règles de fallback complexes (3 formes essayées dans l'ordre) | Tests exhaustifs dès M1 sur la matrice (`MY_VAR` / `my.var` / `my-var`) ; le TCK couvre ces cas |
| Cycles d'expressions (`${a}` → `${b}` → `${a}`) | `ScopedValue<Set<String>>` lit-thread-safe ; pas de `ThreadLocal` qui pinerait les virtual threads |
| Conversion automatique (implicit) avec `setAccessible` requis sur `valueOf`/`parse` privés ou packagés | N'autoriser que les méthodes `public static` ; sinon documenter le module ouvert ; pas d'`addOpens` global |
| TCK Arquillian : containerless vs embedded Vauban | Deux profiles Maven dans `ravel-tck` ; tests `@Tag("cdi")` exclus en mode standalone |
| Interaction profils + expressions | Combinatoire testée explicitement : `%dev.url=${base}/dev` doit résoudre `${base}` *après* sélection du profil |
| GraalVM AOT compatibility | Tester `native-image` sur un exemple `ravel-examples` dès M3 ; les implicit converters via réflexion sont le point sensible |
| ServiceLoader + JPMS dans `ravel-cdi-vauban` | Vérifier que les `@Provides` CDI ne nécessitent pas d'`opens` sur les modules utilisateur |

## Décisions actées

- ✅ **Specs Jakarta / MicroProfile autorisées** : `microprofile-config-api`, `jakarta.cdi-api`,
  `jakarta.inject-api`, `jakarta.annotation-api`. Pas de Smallrye / Helidon / Apache Commons Config.
- ✅ **`ravel-core` standalone SE** : utilisable sans CDI, sans Servlet, sans Vauban.
- ✅ **`ravel-cdi-vauban` séparé** : module optionnel, n'est pas chargé si CDI absent.
- ✅ **TDD strict** sur tous les modules de production.
- ✅ **TCK PASS 100 %** comme contrat dur.
- ✅ **TCK hors reactor** (POM Model 4.0.0 standalone) — contrainte ShrinkWrap Maven Resolver 3.3.
- ✅ **Détection de cycle via `ScopedValue`** (pas `ThreadLocal`) — virtual-thread-friendly.

## Décisions ouvertes

- [ ] Faut-il exposer un mode "watch" pour les sources fichiers (`microprofile-config.properties`
      rechargé sur `WatchService`) ? → Hors spec MP Config 3.1, à différer en M6+ comme extension.
- [ ] `ConfigProperty` sur méthode `@Produces` utilisateur — supporté par la spec ? → Vérifier §6
      avant M4.
- [ ] Stratégie pour les `Provider<T>` / `Supplier<T>` : lookup à chaque `get()` ou cache TTL
      configurable ? → Lookup à chaque `get()` par défaut (conformité spec) ; cache TTL en option
      futur.
- [ ] Expression resolver : tolérer les références en avant (`${b}` défini après `${a}`) ?
      → Oui, pas d'ordre déclaratif, résolution lazy à la lecture.
- [ ] Intégration future MicroProfile Config 4.0 (quand released) — design-out pour faciliter
      le bump de version sans refactoring profond.
