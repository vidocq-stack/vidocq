# Ravel — TCK MicroProfile Config 3.1

## État actuel — M5

| Élément | Statut |
|---|---|
| Module `ravel-tck` (POM Model 4.0.0 hors reactor) | ✅ |
| Profil Maven `tck-official` avec `microprofile-config-tck:3.1.1` | ✅ |
| Provider Surefire TestNG forcé via plugin dependency `surefire-testng:3.5.5` | ✅ |
| Containerle Arquillian Weld embedded 4.0.0 + Weld 6.0.2 (CDI 4.1) | ✅ (configuré) |
| Smoke test `RavelTckSmokeTest` (JUnit 6, hors Arquillian) | ✅ 2/2 PASS |
| Script `run-official-tck-mp-config-3.1.sh` (smoke / all / `-Dtest=...`) | ✅ |
| Découverte du TCK officiel (`dependenciesToScan`) | ✅ — **450 tests** détectés |
| Bootstrap Arquillian sous JDK 25 | ❌ **bloqué upstream** |
| Score 100 % PASS | ⏳ en attente du fix Arquillian |

## Comment lancer

```bash
./run-official-tck-mp-config-3.1.sh           # smoke (2 tests JUnit, vérifie le ServiceLoader)
./run-official-tck-mp-config-3.1.sh all       # suite TCK officielle complète (TestNG + Arquillian + Weld)
./run-official-tck-mp-config-3.1.sh -Dtest=ConfigProviderTest
```

Le script :
1. installe en local `ravel-api` / `ravel-core` / `ravel-cdi-vauban` via `./mvnw install -DskipTests` ;
2. invoque `./mvnw -f ravel-tck/pom.xml -P<profile> test` (le wrapper, pas `mvn` système, pour
   garantir Maven 4.0.0-rc-5) ;
3. produit un rapport résumé dans `ravel-tck/target/tck-report.txt`.

## Bug bloquant — `MalformedParameterizedTypeException`

À l'exécution du profil `tck-official`, **les 450 tests TCK sont correctement détectés** mais
chaque classe échoue dans `Arquillian.arquillianBeforeClass` avec :

```
java.lang.RuntimeException: Could not create new instance of class
    org.jboss.arquillian.test.impl.EventTestRunnerAdaptor
Caused by: org.jboss.arquillian.container.impl.ContainerCreationException:
    Could not create Container weld-embedded
Caused by: java.lang.reflect.MalformedParameterizedTypeException:
    Mismatch of count of formal and actual type arguments in constructor of
    org.jboss.arquillian.container.spi.Container:
    0 formal argument(s) 1 actual argument(s)
   at java.base/sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
       .validateConstructorArguments(ParameterizedTypeImpl.java:56)
```

### Analyse

- La classe `org.jboss.arquillian.container.spi.Container` est **non générique**,
  mais une signature `Generic<Container<X>>` existe dans `arquillian-container-impl-base`.
- Sous JDK 23+ (et donc JDK 25), `ParameterizedTypeImpl.validateConstructorArguments`
  est devenu strict : il refuse d'instancier un `ParameterizedType` quand la classe brute
  ne déclare pas de type variable. Avant JDK 23 la validation était permissive.
- Le bug est présent dans la **dernière** version Arquillian publique (`1.10.0.Final`,
  cf. `https://search.maven.org/`).

### Pistes de résolution

| Piste | Effort | Risque | Notes |
|---|---|---|---|
| Attendre un fix upstream Arquillian (1.10.1+ ou 1.11) | 0 | dépend du calendrier amont | piste préférentielle |
| Forker `arquillian-container-spi` et corriger la signature | élevé | maintenance perpétuelle | dernier recours |
| Skip Arquillian et exécuter le TCK via un harness maison (parser des `@Test`, intercepter `@Deployment` ShrinkWrap, exécuter dans une JVM avec Weld bootstrap manuel) | très élevé | risque de divergence vs spec officielle | ❌ exclu — sortirait du contrat « TCK officiel » |
| Lancer sur JDK 21 LTS (la stricteur n'est pas appliquée) | faible | violerait la contrainte « Java 25 » | ❌ exclu (CLAUDE.md) |
| Patcher dynamiquement la classe via java agent au boot | moyen | invasif | possible workaround temporaire |

### Suivi

- Issue Arquillian de référence : à vérifier sur `https://issues.redhat.com/projects/ARQ`
  (chercher "MalformedParameterizedTypeException JDK 23+").
- Lorsque la version corrigée sortira, bumper `<arquillian.version>` dans
  [`ravel-tck/pom.xml`](./ravel-tck/pom.xml) et relancer
  `./run-official-tck-mp-config-3.1.sh all`.

## Tests désactivés / challenges spec

Aucun pour l'instant : aucune assertion TCK n'a pu s'exécuter en raison du bug bootstrap.
Cette section sera enrichie dès que le harness sera fonctionnel, en respectant la discipline
Vidocq (citation de la section spec, hash du test, plan de réactivation).

## Références

- MicroProfile Config 3.1 spec : `https://microprofile.io/specifications/microprofile-config/3.1/`
- TCK artefact : `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
  (Maven Central, automatic module name, publié 2026-04-22)
- Arquillian core : `https://github.com/arquillian/arquillian-core`
- Weld SE : `https://docs.jboss.org/weld/reference/latest/en-US/html/environments.html#weld-se`

