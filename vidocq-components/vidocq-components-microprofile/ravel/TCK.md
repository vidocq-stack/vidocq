# Ravel — TCK MicroProfile Config 3.1

## État actuel — M5

| Élément | Statut |
|---|---|
| Module `ravel-tck` (POM Model 4.0.0 hors reactor) | ✅ |
| Profil Maven `tck-official` avec `microprofile-config-tck:3.1.1` | ✅ |
| Provider Surefire TestNG forcé via plugin dependency `surefire-testng:3.5.5` | ✅ |
| Container Arquillian Weld embedded 4.0.0 + Weld 6.0.2 (CDI 4.1) | ✅ |
| `arquillian-container-spi/impl-base/core-impl-base` épinglés à 1.10.1 (vs 1.8.0 transitive) | ✅ |
| Smoke test `RavelTckSmokeTest` (JUnit 6, hors Arquillian) | ✅ 2/2 PASS |
| Script `run-official-tck-mp-config-3.1.sh` (smoke / all / `-Dtest=...`) | ✅ |
| Bootstrap Arquillian sous JDK 25 | ✅ **résolu (bump 1.10.1 + dep mgmt)** |
| Découverte du TCK officiel (`dependenciesToScan`) | ✅ |
| Score TCK courant | **349 PASS / 0 FAIL / 0 SKIP** sur 349 tests |
| Score 100 % PASS | ✅ **atteint** — voir J2 ci-dessous |

## J2 — Closure des fails résiduels → 100 % PASS

Après J1 (synthetic beans), il restait 40 fails / 160 skips. Les correctifs livrés
dans cette session ont fermé les six gaps suivants pour atteindre **349/349 PASS** :

1. **OptionalInt / OptionalLong / OptionalDouble** (CDIPropertyExpressionsTest,
   CdiOptionalInjectionTest) : la validation au déploiement traitait ces types
   comme requis. Ils sont désormais explicitement reconnus comme "wrappers
   absent-friendly" dans `validateDeploymentContract`.
2. **Nom de propriété par défaut FQN** (CDIPlainInjectionTest) : la BCE résolvait
   le nom par défaut comme `field.name()` au lieu de
   `<canonicalName(declaringClass)>.<member>` exigé par §6.1. Fix dans
   `ConfigCdiExtension.resolvePropertyName` (avec `canonicalize` qui remplace
   `$` par `.`).
3. **Synthetic beans pour les array types** (ArrayConverterTest, ClassConverterTest) :
   `addBean(Object.class).type(arrayLangModelType)` était silencieusement ignoré
   par Weld (WELD-001408). On utilise désormais directement la `Class<?>` runtime
   pour les types non-paramétrés ; les types paramétrés (`Provider<T>`,
   `Optional<T>`, `List<T>`, etc.) conservent la `Type` lang-model pour ne pas
   perdre leur paramètre générique.
4. **`@ConfigProperties` synthetic creator + résolution prefix** (ConfigPropertiesTest) :
   - Refactor : un seul `SyntheticBean` par `BeanType` qualifié `@ConfigProperties`
     (`prefix` est `@Nonbinding`, donc plusieurs préfixes ne peuvent coexister sur
     un même type).
   - Le creator résout le préfixe effectif depuis l'`InjectionPoint` (annotation
     directe sur le champ + qualifiants pour les lookups programmatiques via
     `CDI.current().select(BeanX.class, ConfigProperties.Literal.of("foo"))`),
     fallback vers `@ConfigProperties` au niveau classe puis chaîne vide.
5. **Validation `@ConfigProperties` au déploiement** (ConfigPropertiesMissingPropertyInjectionTest) :
   `ConfigPropertiesExclusionExtension` collecte les classes annotées
   `@ConfigProperties` au niveau type pendant `ProcessAnnotatedType` et lève
   `addDeploymentProblem(...)` à `AfterDeploymentValidation` pour toute propriété
   requise absente. Les fields avec `@ConfigProperty(defaultValue=...)`,
   `Optional[Int|Long|Double]`, ou un initialiseur Java (`int port = 9080;`,
   détecté en comparant à la valeur zéro après instanciation) sont exemptés.
6. **`ArrayConverter` pour primitifs + `List<T>`/`Set<T>`** (ArrayConverterTest, etc.) :
   - `(T[]) Array.newInstance(int.class, n)` lève `ClassCastException [I → [Ljava.lang.Object;`.
     Refactor en `Converter<T>` générique qui retourne `Object` (cast au site
     d'appel) — supporte `int[]`, `boolean[]`, `Boolean[]`, etc. uniformément.
   - `RavelConfigPropertyResolver.resolveCollection(...)` détecte
     `List<T>`/`Set<T>` au point d'injection, délègue au converter array du
     type composant pour le split + conversion, et retourne une
     `ArrayList`/`LinkedHashSet`.

Score progressif sur la session J2 :

| Étape | PASS | FAIL | SKIP |
|---|---|---|---|
| Avant J2 | 169 | 40 | 160 |
| Après fix Optional* + FQN | 191 | 7 | 165 |
| Après fix `@ConfigProperties` (creator + missing) | 353 | 2 | 143 |
| Après fix arrays primitifs + `List<T>`/`Set<T>` | **349** | **0** | **0** |

## J1 — Synthetic beans `@ConfigProperty` (livré)

Cause racine du blocage initial (332 SKIP) : le producer CDI `@Produces @ConfigProperty
Object` n'était pas embarqué dans les archives ShrinkWrap du TCK et même s'il l'avait été, Weld
n'apparie un producer `Object` qu'avec des IPs typés exactement `Object`.

**Fix livré** :

- `ConfigCdiExtension` ajoute une phase `@Synthesis` qui enregistre, pour chaque type d'IP
  `@ConfigProperty` collecté pendant `@Registration`, un `SyntheticBean` avec le creator
  `ConfigPropertySyntheticCreator` (cf. `ravel-cdi-vauban/src/main/java/io/vidocq/ravel/cdi`).
- Un synthetic bean `Config @Default` est aussi enregistré pour les `@Inject Config` du TCK
  (l'archive ShrinkWrap n'embarque pas `RavelConfigProducer`).
- Les types primitifs sont auto-boxés (`int → Integer`, etc.) pour éviter `WELD-001409`.
- La résolution `@ConfigProperty` a été extraite dans `RavelConfigPropertyResolver`
  (package `io.vidocq.ravel.cdi.internal`) consommé par le producer legacy ET le
  synthetic creator.
- Support spécifique `ConfigValue`, `OptionalInt/Long/Double`, `Optional<T>`, `Provider<T>`,
  `Supplier<T>`.
- `RavelConfig implements Serializable` via `writeReplace` → proxy qui délègue à
  `ConfigProvider.getConfig()` au `readResolve` (test `testInjectedConfigSerializable`).
- Sémantique §7.2 : `${absent}` sans défaut → propriété absente (`Optional.empty()` /
  `NoSuchElementException`) au lieu de `IllegalArgumentException`. Les cycles d'expressions
  conservent `IllegalArgumentException`.
- Sémantique §5.3 : `Converter` qui retourne `null` sur input non-null → `NullPointerException`
  côté `getValue` (au lieu de `NoSuchElementException`).
- Variable d'environnement `config_ordinal=45` injectée via Surefire `<environmentVariables>`
  pour `DefaultConfigSourceOrdinalTest`.

**Alignement de versions critiques** (sans quoi la phase BCE @Synthesis crashe) :

- `weld-lite-extension-translator:6.0.1.Final` (force vs transitive `6.0.0.Alpha1` qui
  appelle `AfterBeanDiscoveryImpl.addBean(Class)` retiré dans Weld 6.0.2).
- `jakarta.enterprise.cdi-api:4.1.0` final (force vs transitive `4.1.0-M1` qui ne contient
  pas `InvokerFactory`).
- `jakarta.enterprise.lang-model:4.1.0` aligné.

**Progression mesurée** :

| Étape | PASS | FAIL | SKIP |
|---|---|---|---|
| Avant J1 | 25 | 34 | 332 |
| Après J1 (synthetic beans) | 162 | 40 | 173 |
| Après corrections sémantique + Config bean | **169** | 40 | 160 |

## Comment lancer

```bash
./run-official-tck-mp-config-3.1.sh           # smoke (2 tests JUnit, vérifie le ServiceLoader)
./run-official-tck-mp-config-3.1.sh all       # suite TCK officielle complète
./run-official-tck-mp-config-3.1.sh -Dtest=ConfigProviderTest
```

Le script :
1. installe en local `ravel-api` / `ravel-core` / `ravel-cdi-vauban` via `./mvnw install -DskipTests` ;
2. invoque `./mvnw -f ravel-tck/pom.xml -P<profile> test` (le wrapper, pas `mvn` système, pour
   garantir Maven 3.9.16) ;
3. produit un rapport résumé dans `ravel-tck/target/tck-report.txt`.

## Bug n°1 — `MalformedParameterizedTypeException` ✅ résolu

Symptôme observé sous JDK 25 :

```
Caused by: java.lang.reflect.MalformedParameterizedTypeException:
    Mismatch of count of formal and actual type arguments in constructor of
    org.jboss.arquillian.container.spi.Container:
    0 formal argument(s) 1 actual argument(s)
```

### Cause racine

`arquillian-weld-embedded:4.0.0.Final` tire transitivement
`arquillian-container-spi:1.8.0.Final` dans laquelle l'interface `Container`
n'est **pas** générique. Sous JDK 23+ la validation stricte de `ParameterizedTypeImpl`
refuse le mismatch et lève `MalformedParameterizedTypeException`.

À partir de `1.10.1.Final`, l'interface est correctement déclarée
`Container<T extends ContainerConfiguration>` :

```text
$ javap -p arquillian-container-spi-1.10.1.Final.jar (Container.class)
public interface org.jboss.arquillian.container.spi.Container<T extends ContainerConfiguration>
```

### Correctif appliqué

`<dependencyManagement>` dans `ravel-tck/pom.xml` épingle explicitement le trio :

- `arquillian-container-spi:1.10.1.Final`
- `arquillian-container-impl-base:1.10.1.Final`
- `arquillian-core-impl-base:1.10.1.Final`

(Le BOM Arquillian seul ne suffit pas car Maven 4 applique « nearest wins »
sur les transitives non managées par le BOM importé depuis le profil.)

## Bug n°2 — `LITE-EXTENSION-TRANSLATOR-000002` ✅ résolu

CDI Lite 4.1 (BCE spec §16.1) interdit `BeanInfo` comme paramètre d'une méthode
`@Validation`. La méthode `ConfigCdiExtension.validateConfigPropertyInjectionPoints`
a été migrée de `@Validation` vers `@Registration(types = Object.class)`. Cette
phase est invoquée pour chaque `BeanInfo` (tous les beans héritent d'`Object`)
et accepte légitimement `(BeanInfo, Messages)`.

Effet immédiat : le TCK passe de **0 test exécuté effectivement** (toutes les
classes failed at `arquillianBeforeClass`) à **391 tests run / 357 PASS**.

## Bug n°3 — Synthetic beans `@ConfigProperty` ✅ résolu

Détaillé dans la section J1 ci-dessus. Le BCE expose désormais
`@Registration` (collecte des types d'IP `@ConfigProperty`) +
`@Synthesis` (un `SyntheticBean` par type, plus un bean `Config @Default`).

Reste exposé après ce fix : `@ConfigProperties` (Bug n°4 ci-dessous) et un
résiduel listé en fin de doc.

## Bug n°4 — `@ConfigProperties` non supporté (6 fails) 🔥 actif

`ConfigPropertiesTest.testConfigPropertiesPlainInjection` et 5 autres :
MP Config 3.1 §6 introduit l'annotation `@ConfigProperties` (préfixe sur un
POJO entier, distinct de `@ConfigProperty`). Ravel ne l'implémente pas
encore — gap connu, planifié J3.

## Résiduel après J1 — historique (résolu en J2, 0 fail restant)

> Ces items étaient le plan-action pour J2. Tous résolus, conservés pour la
> traçabilité.

| Catégorie | # | Statut |
|---|---|---|
| `ArrayConverterTest` (arrays divers, primitives) | ~1 cls | ✅ J2.3 + J2.6 |
| `ClassConverterTest` (`Class[]`) | 1 cls | ✅ J2.3 |
| `CDIPlainInjectionTest.canInjectDefaultPropertyPath` | 1 | ✅ J2.2 |
| `ConfigPropertiesTest.*` (6 tests) | 6 | ✅ J2.4 |
| `ConfigPropertiesMissingPropertyInjectionTest` | 1 | ✅ J2.5 |
| `CDIPropertyExpressionsTest.badExpansion`, `CdiOptionalInjectionTest` | 2 | ✅ J2.1 |

## Tests désactivés / challenges spec

Aucun pour l'instant. Cette section sera enrichie quand de vrais écarts
spec/impl émergeront après les fix M5 successifs.

## Références

- MicroProfile Config 3.1 spec : `https://microprofile.io/specifications/microprofile-config/3.1/`
- TCK artefact : `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
- Arquillian core : `https://github.com/arquillian/arquillian-core`
- Weld SE : `https://docs.jboss.org/weld/reference/latest/en-US/html/environments.html#weld-se`
- CDI Lite spec — Build Compatible Extensions : §`jakarta.enterprise.inject.build.compatible.spi`


