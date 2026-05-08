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

### M1 — Sources built-in + lookup de base ✅

**Scope spec :** §3 (ConfigSource), §4 (Built-in ConfigSources), §2.1 (Config lookup).

| Tâche | Notes | État |
|---|---|---|
| `RavelConfig` implémente `org.eclipse.microprofile.config.Config` | `getValue`, `getOptionalValue`, `getValues`, `getOptionalValues`, `getPropertyNames`, `getConfigSources`, `getConfigValue`, `getConverter`, `unwrap` | ✅ |
| `RavelConfigBuilder` implémente `ConfigBuilder` | `addDefaultSources`, `addDiscoveredSources`, `addDiscoveredConverters`, `withSources`, `withConverter(s)`, `forClassLoader`, `build` | ✅ |
| `RavelConfigProviderResolver` extends `ConfigProviderResolver` | Singleton via ServiceLoader, un `Config` par `ClassLoader`, `ConcurrentHashMap` + `computeIfAbsent` atomique (testé sous 100 virtual threads concurrents) | ✅ |
| `META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver` + `provides` JPMS | Double compatibilité classpath/module-path | ✅ |
| `SystemPropertiesConfigSource` (ordinal 400) | Lecture `System.getProperty/getProperties` à chaque appel (mutable runtime, pas de cache local) | ✅ |
| `EnvironmentVariablesConfigSource` (ordinal 300) | Mapping spec §7.6 : 3 formes essayées (exact / non-alphanum→`_` / +UPPER). Helper `toEnvFormat` extrait pour testabilité | ✅ |
| `MicroprofilePropertiesConfigSource` (ordinal 100) | Une instance par URL `META-INF/microprofile-config.properties` du `ClassLoader`, snapshot figé | ✅ |
| `ConfigSourceProvider` SPI via ServiceLoader | `addDiscoveredSources` charge sources directes + providers | ✅ |
| `RavelConfigValue` record avec metadata | §2.1.5 — factory `absent(name)` pour clé manquante | ✅ |
| Tests unitaires + intégration | **80 tests verts** sur 8 classes + 1 fixture `MapConfigSource` | ✅ |

**Décisions M1 documentées** : converter `String` identity uniquement (M2 ajoutera primitives, types automatiques, implicit converters) ; pas de `synchronized`, pas de `ThreadLocal`, pas de `setAccessible` (audit clean) ; cascade ordinals stable avec tie-breaking sur l'ordre d'enregistrement.

**Livrable :** `ConfigProvider.getConfig().getValue("key", String.class)` fonctionne avec les 3 sources canoniques. Reactor + tests unitaires verts.

---

### M2 — Converters de types ✅

**Scope spec :** §5 (Converter), §5.1 (Built-in converters), §5.1.1 (Boolean), §5.2 (Automatic / Implicit converters), §5.3 (Custom converters), §5.4 (Arrays).

| Tâche | Notes | État |
|---|---|---|
| Built-in stricts (§5.1) | `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `float`/`Float`, `double`/`Double`, `short`/`Short`, `byte`/`Byte`, `char`/`Character`, `String`, `Class<?>` — tous au même converter pour boxed/primitive | ✅ |
| Built-in étendus | `OptionalInt`, `OptionalLong`, `OptionalDouble`, `URI`, `URL`, `InetAddress`, `Duration`, `Period`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetTime`, `OffsetDateTime`, `ZonedDateTime`, `Instant` | ✅ |
| Énumérations | Via `ImplicitConverter` (pattern `valueOf` traité comme cas dédié pour la sécurité de typage) | ✅ |
| Convertisseur `Boolean` §5.1.1 | `true`/`1`/`yes`/`y`/`on` (case-insensitive, après `trim()`) → `true` ; tout le reste → `false` | ✅ |
| Conversion tableaux §5.4 | `ArraySplitter` + `ArrayConverter<T>` — séparateur `,`, échappement `\,`, segments vides ignorés ; `getValue(name, T[].class)` et `getValues(name, T.class)` (via default Config) | ✅ |
| `Converter<T>` SPI via ServiceLoader | `addDiscoveredConverters` charge + lit `@jakarta.annotation.Priority` (défaut **100**) | ✅ |
| Implicit converters §5.2 | Détection ordonnée : `static of(String)` > `valueOf(String)` > `parse(CharSequence)` > ctor `(String)`. **Strictement public**, pas de `setAccessible(true)` | ✅ |
| `ConfigBuilder.withConverter(Class<T>, int priority, Converter<T>)` | Priorité explicite respectée ; un converter de priorité plus basse n'écrase pas un plus haut | ✅ |
| `ConfigBuilder.withConverters(Converter<?>...)` | Lit `@Priority` (défaut 100) via réflexion FQN — pas de dépendance compile sur `jakarta.annotation` | ✅ |
| Cache thread-safe des converters dérivés | `ConcurrentHashMap` sur `RavelConfig.derivedConverters` ; pas de `synchronized`, virtual-thread-friendly | ✅ |
| Tests par converter + cas limites | Boolean truthy/falsy, numériques avec espaces, char.length≠1, URL/URI invalides, enum unknown, array escape `\,`, segments vides, type incompatible → `IllegalArgumentException` | ✅ |

**Décisions M2 documentées** :
- Le contrat MP §5.3 « priorité par défaut 100 » est lu **par nom qualifié** (`jakarta.annotation.Priority` ou `javax.annotation.Priority`) en évitant toute dépendance compile sur `jakarta.annotation` dans `ravel-core` (test scope uniquement, conforme à la règle « zéro tierce »).
- Les built-in sont enregistrés à **priorité 1** ; tout converter applicatif (priorité 100 par défaut) les écrase automatiquement.
- Les patterns `§5.2` n'utilisent **que** des méthodes/constructeurs `public` — aucun `setAccessible(true)`, conformément aux contraintes JPMS / AOT-friendly.
- `getOptionalValue(name, X[].class)` traite la chaîne vide comme « absent » (§2.1.4), `,,` ou `\\` non suivi de `,` sont normalisés selon `ArraySplitter`.

**Livrable :** `config.getValue("timeout", Duration.class)`, `config.getValue("hosts", String[].class)`, `config.getValues("colors", Status.class)` et un `Converter<UUID>` custom annoté `@Priority(500)` fonctionnent. **131 tests verts** (80 M1 + 51 M2) sur 12 classes.

---

### M3 — Config Profiles + Property Expressions ✅

**Scope spec :** §7.5 (Configuration profile), §7.2 (Configuration property expression).

| Tâche | Notes | État |
|---|---|---|
| Lecture `mp.config.profile` au démarrage du `Config` | Valeur résolue avant build final ; profil appliqué via sources enveloppées | ✅ |
| Préfixe `%<profile>.` sur toute source | Wrapping `ProfiledConfigSource` qui réécrit les clés et hérite ordinal + 1 | ✅ |
| Activation des profils en cascade | `mp.config.profile=dev` → `%dev.app.url` masque `app.url` (ordinal +1) | ✅ |
| Expression `${key}` (§7.2) | Resolver récursif sur le raw value lu depuis le `ConfigSource` | ✅ |
| Expression `${key:default}` | Default value si la clé n'est pas trouvée | ✅ |
| Imbrication `${${env}.url}` | Resolver récursif, profondeur testée | ✅ |
| Échappement `\$` | Pas d'interpolation | ✅ |
| Détection de cycle | Via `ScopedValue<Set<String>>` (pas `ThreadLocal`), `IllegalArgumentException` levée | ✅ |
| Désactivation possible via `mp.config.property.expressions.enabled=false` | Section §7.2 spec | ✅ |
| Tests : profil absent / présent / multiple, cycle direct, cycle indirect, échappement | Couverture exhaustive sur tests dédiés + intégration | ✅ |

**Décisions M3 documentées** :
- La résolution des expressions se fait sur la valeur brute avant conversion typée.
- `ConfigValue` conserve `rawValue` (chaîne d'origine) et expose `value` résolue.
- Le profil actif est sélectionné via la cascade d'ordinals au moment du build, puis appliqué à toutes les sources via wrapping.

**Livrable :** `config.getValue("database.url", String.class)` bascule vers `%dev.database.url` avec `mp.config.profile=dev` ; expressions `${key}` / `${key:default}` / `${${env}.url}` résolues ; cycles détectés. **155 tests verts** sur `ravel-core`.

---

### M4 — Intégration CDI (`ravel-cdi-vauban`)

**Scope spec :** §6 (CDI integration), §6.1 (`@ConfigProperty`), §6.2 (`Config` injection), §6.3 (Optional injection).

| Tâche | Notes | État |
|---|---|---|
| `ConfigCdiExtension` (Build Compatible Extension Vauban) | BCE ajoutée + validation des points `@ConfigProperty` (type supporté, propriété requise manquante) | ✅ |
| Génération de producers `@Produces @ConfigProperty` par type | Producer unique paramétrique via `InjectionPoint` (déduplication par design) | ✅ |
| Support `Optional<T>` (§6.3) | Si la propriété est absente → `Optional.empty()` | ✅ |
| Support `Provider<T>` / `Supplier<T>` | Lookup à chaque `get()` (dynamic injection) | ✅ |
| `@Inject Config config` | Injection du `Config` complet via producer dédié | ✅ |
| `@ConfigProperty(defaultValue=…)` | Valeur par défaut convertie avec le converter cible | ✅ |
| Validation au déploiement (§6.4) | BCE signale les injections requises manquantes (sans `defaultValue`) | ✅ |
| Tests d'intégration avec container Vauban | Smoke test de bootstrap CDI SE Vauban + injection `Config` dans un conteneur réel (`SeContainerInitializer`) | ✅ |
| Pas d'opens JPMS sur les beans utilisateurs | BCE + producers sans réflexion sur classes applicatives | ✅ |

**Livrable (incrément actuel) :** `@Inject @ConfigProperty(name="app.name", defaultValue="vidocq") String name;` + `Optional<T>`/`Provider<T>`/`Supplier<T>` validés par tests de module. **20 tests verts** sur `ravel-cdi-vauban` (165 au total avec `ravel-core`).

---

### M5 — TCK + Bench 🟡 en cours

**Scope :** validation officielle MicroProfile Config 3.1 + benchmarks comparatifs.

| Tâche | Notes | État |
|---|---|---|
| `ravel-tck/pom.xml` Model 4.0.0 standalone | Idem `cassini-tck`/`foy-tck`/`champollion-tck` — hors reactor | ✅ |
| Runner Arquillian + harness officiel `microprofile-config-tck:3.1.1` | Arquillian 1.10.1 (BOM + dep mgmt sur `container-spi/impl-base/core-impl-base`) + Weld 6.0.2 + TestNG 7.10.2 ; bug `MalformedParameterizedTypeException` JDK 25 résolu | ✅ scaffolding |
| Adapter Arquillian → Ravel embedded | Weld SE embedded ; `arquillian.xml` + `META-INF/beans.xml` (CDI 4.1) en place | ✅ |
| `run-official-tck-mp-config-3.1.sh` | Modes : smoke (défaut, 2/2 PASS) / all / `-Dtest=NomTest` ; rapport `target/tck-report.txt` | ✅ |
| BCE `@Validation` → `@Registration(types=Object.class)` | CDI Lite 4.1 §16.1 interdit `BeanInfo` en `@Validation` ; commit `8d80958` | ✅ |
| `TCK.md` | Documente bugs n°1 (Arquillian, résolu), n°2 (BCE Validation, résolu), n°3 synthetic beans (actif), n°4 `@ConfigProperties`, n°5 expressions raw, n°6 ordinal | ✅ |
| Score contrat : 100 % PASS | **391 tests run / 357 PASS / 34 fails / 332 skipped** ; gap principal = synthetic beans `@ConfigProperty` pour types arbitraires (cf. `TCK.md` bug n°3) | ⏳ |
| Synthetic beans `@ConfigProperty` via `@Synthesis` BCE | Enregistrer `SyntheticBean<T>` qualifié `@ConfigProperty` pour chaque type d'IP collecté ; débloque ~15 `arquillianBeforeClass` + tests Optional/Provider/Supplier/arrays/converters | ⏳ |
| Support `@ConfigProperties` (MP Config 3.1 §6) | POJO préfixé via annotation distincte de `@ConfigProperty` ; ~6 tests TCK | ⏳ |
| Expressions raw / lookup non-strict (§7.2) | `getOptionalValue` / `getConfigValue` ne doivent pas lever sur `${missing}` non résolu | ⏳ |
| `ravel-bench` JMH | `LookupBenchmark` (cache hit/miss) + `ExpressionBenchmark` (literal/1/3 niveaux) + `ConversionBenchmark` (Integer/Long/Boolean/Duration/String[]) ; `@Param` Ravel/Smallrye | ✅ |
| Comparatif JMH vs Smallrye Config | Premier smoke run : `LookupBenchmark.hit_String` Ravel ~22 ns/op, Smallrye ~15 ns/op (1 fork, 1 iter) | ✅ premier baseline |

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
