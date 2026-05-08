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
| Découverte du TCK officiel (`dependenciesToScan`) | ✅ — **403 tests exécutés, 25 fails, 371 skipped** |
| Score 100 % PASS | ⏳ bloqué sur la BCE Ravel (cf. plus bas) |

## Comment lancer

```bash
./run-official-tck-mp-config-3.1.sh           # smoke (2 tests JUnit, vérifie le ServiceLoader)
./run-official-tck-mp-config-3.1.sh all       # suite TCK officielle complète
./run-official-tck-mp-config-3.1.sh -Dtest=ConfigProviderTest
```

Le script :
1. installe en local `ravel-api` / `ravel-core` / `ravel-cdi-vauban` via `./mvnw install -DskipTests` ;
2. invoque `./mvnw -f ravel-tck/pom.xml -P<profile> test` (le wrapper, pas `mvn` système, pour
   garantir Maven 4.0.0-rc-5) ;
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

## Bug n°3 — Synthetic beans manquants pour `@ConfigProperty` (15 fails restants en `arquillianBeforeClass`) 🔥 actif

Symptôme :

```
WELD-001408: Unsatisfied dependencies for type OffsetDateTime[] with qualifiers @ConfigProperty
  at injection point [BackedAnnotatedField] @Inject @ConfigProperty
      private org.eclipse.microprofile.config.tck.ArrayConverterBean.myOffsetDateTimeArray
```

### Cause racine

`RavelConfigProducer.produceConfigProperty` retourne `Object` — Weld n'utilise
ce producer que pour les IP typés exactement `Object`. Pour résoudre les IP
arbitraires (`OffsetDateTime[]`, `URL[]`, `LocalDate`, custom converters, etc.)
il faut **enregistrer un `SyntheticBean<T>` par type rencontré** via le hook
`@Synthesis` du BCE — comme le font Smallrye Config et Helidon Config.

Plan d'implémentation :
1. Phase `@Enhancement` ou `@Registration` : collecter dans un état BCE le set
   des `Type` distincts utilisés par les IP `@ConfigProperty` (déclaration via
   `InjectionPointInfo.type()`), incluant `ArrayType`, `ParameterizedType`
   (Optional/Provider/Supplier/List/Set), `ClassType`.
2. Phase `@Synthesis` : pour chaque type T collecté, déclarer un
   `SyntheticBean<T>` qualifié `@ConfigProperty` (binding non-binding pour
   `name`/`defaultValue`) avec un `SyntheticBeanCreator<T>` qui appelle
   `ConfigProvider.getConfig().getValue(name, T)` ou délègue à un
   `RavelConfigPropertyResolver` ré-utilisable.
3. Migrer `RavelConfigProducer.produceConfigProperty(InjectionPoint)` vers ce
   resolver pour partager la logique entre tests existants et synthetic beans.

### Classes TCK actuellement bloquées par ce gap

- `arquillianBeforeClass` (15) : `ArrayConverterTest`, `CDIPlainInjectionTest`,
  `CDIPropertyExpressionsTest`, `CDIPropertyNameMatchingTest`,
  `CdiOptionalInjectionTest`, `ClassConverterTest`, `ConfigValueTest`,
  `ConverterTest`, `ImplicitConverterTest`, `ConvertedNullValueTest`,
  `ConfigPropertiesMissingPropertyInjectionTest`, `*ConfigProfileTest` (6).
- `arquillianBeforeTest` (3) : `ConfigProviderTest.testDynamicValueInPropertyConfigSource`,
  `CustomConfigSourceTest.testConfigSourceProvider`, `CustomConverterTest.testBoolean`.

## Bug n°4 — `@ConfigProperties` non supporté (6 fails)

`ConfigPropertiesTest.testConfigPropertiesPlainInjection` et 5 autres :
MP Config 3.1 §6 introduit l'annotation `@ConfigProperties` (préfixe sur un
POJO entier, distinct de `@ConfigProperty`). Ravel ne l'implémente pas
encore — gap connu, à traiter après le bug n°3 dans la même itération.

## Bug n°5 — `PropertyExpressions` lookup raw (6 fails)

`PropertyExpressionsTest.noExpression*` : la spec §7.2 exige que les
`getOptionalValue(...)` / `getConfigValue(...)` retournent quelque chose
(empty / raw value) quand l'expression `${missing.prop}` ne se résout pas
plutôt que de lever une exception. Ravel lève `IllegalArgumentException`.
Fix unitaire dans `RavelConfig.resolveExpression` pour distinguer les chemins
"required" (exception, du bon type `NoSuchElementException`) et "optional"
(retourner le raw / `Optional.empty()`).

## Bug n°6 — divers (3 fails)

- `DefaultConfigSourceOrdinalTest.checkSetup` — assertion sur l'ordinal
  par défaut de `META-INF/microprofile-config.properties` (100, à vérifier
  contre la valeur déclarée par notre `MicroprofilePropertiesConfigSource`).
- `NullConvertersTest.nulls` — comportement converters face à valeur null /
  empty string.

## Tests désactivés / challenges spec

Aucun pour l'instant. Cette section sera enrichie quand de vrais écarts
spec/impl émergeront après les fix M5 successifs.

## Références

- MicroProfile Config 3.1 spec : `https://microprofile.io/specifications/microprofile-config/3.1/`
- TCK artefact : `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
- Arquillian core : `https://github.com/arquillian/arquillian-core`
- Weld SE : `https://docs.jboss.org/weld/reference/latest/en-US/html/environments.html#weld-se`
- CDI Lite spec — Build Compatible Extensions : §`jakarta.enterprise.inject.build.compatible.spi`


