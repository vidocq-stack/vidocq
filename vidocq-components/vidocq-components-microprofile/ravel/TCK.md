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

## Bug n°2 — `LITE-EXTENSION-TRANSLATOR-000002` 🔥 actif

Une fois Arquillian débloqué, le TCK officiel détecte 403 tests et la majorité des
classes échoue au `arquillianBeforeClass` avec :

```
Caused by: org.jboss.weld.exceptions.DeploymentException:
    LITE-EXTENSION-TRANSLATOR-000017: There was a problem executing
    Build Compatible Extension method
    public void io.vidocq.ravel.cdi.ConfigCdiExtension
        .validateConfigPropertyInjectionPoints(BeanInfo, Messages)
    during phase @Validation.
Caused by: IllegalArgumentException: LITE-EXTENSION-TRANSLATOR-000002:
    @Validation methods cant declare a parameter of type {1}
```

### Cause racine (côté Ravel)

CDI Lite 4.1 — spec §`Validation` — interdit `BeanInfo` comme paramètre des méthodes
`@Validation`. Les paramètres autorisés sont `Messages` et `Types` (et certains
contextes via `@Enhancement` / `@Registration` en amont).

`ConfigCdiExtension` doit être réécrite pour :
1. Collecter les injection points via `@Registration(types = …)` ou
   `@Enhancement` qui peut recevoir `BeanInfo`.
2. Reporter les erreurs durant `@Validation(MessagesT, Types)`.

Plan ouvert dans la prochaine itération M5 : réécriture de la BCE pour
respecter les contraintes de la signature CDI Lite, puis relance du TCK.

### Échantillon des classes TCK affectées

`ClassConverterTest`, `ConfigPropertiesTest`, `ConfigProviderTest`,
`ConfigValueTest`, `ConverterTest`, `CustomConfigSourceTest`,
`CustomConverterTest`, `ImplicitConverterTest`, `PropertyExpressionsTest`,
`WarPropertiesLocationTest`, `*ConfigProfileTest`, etc.

## Tests désactivés / challenges spec

Aucun pour l'instant — toutes les défaillances actuelles sont causées par
la BCE Ravel. Cette section sera enrichie quand le code Ravel passera la
validation et que de vrais écarts spec/impl émergeront.

## Références

- MicroProfile Config 3.1 spec : `https://microprofile.io/specifications/microprofile-config/3.1/`
- TCK artefact : `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
- Arquillian core : `https://github.com/arquillian/arquillian-core`
- Weld SE : `https://docs.jboss.org/weld/reference/latest/en-US/html/environments.html#weld-se`
- CDI Lite spec — Build Compatible Extensions : §`jakarta.enterprise.inject.build.compatible.spi`


