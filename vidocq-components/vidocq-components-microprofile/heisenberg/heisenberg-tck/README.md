# Heisenberg TCK — MicroProfile Fault Tolerance 4.1

Runner officiel **hors reactor** (POM Model 4.0.0) pour la suite TCK
`org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`.

## Lancement

Depuis la racine du dépôt :

```bash
# Smoke test (JUnit, sans Arquillian — vérifie le classpath)
./run-official-tck-mp-fault-tolerance-4.1.sh

# Suite TCK complète (TestNG + Arquillian + container Heisenberg embedded)
./run-official-tck-mp-fault-tolerance-4.1.sh all

# Test ciblé
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=FallbackTest
```

Le script :

1. Installe `heisenberg-api`, `heisenberg-core`, `heisenberg-cdi-vauban` dans le
   M2 local (`./mvnw install -DskipTests`).
2. Invoque `mvn -f heisenberg-tck/pom.xml -Ptck-official test [args...]`.
3. Produit `heisenberg-tck/target/tck-report.txt` (résumé PASS/FAIL).

## Installation de l'artefact TCK

Le TCK est disponible sur Maven Central :

```bash
mvn dependency:get \
    -Dartifact=org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1
```

Le script `run-official-tck-mp-fault-tolerance-4.1.sh` télécharge l'artefact à
la demande via le profil Maven `tck-official` du POM.

## Architecture du runner

- `HeisenbergDeployableContainer` — container Arquillian embedded local
  (protocole `Local`, mode in-VM, pas de container distant).
- `VaubanTckBootstrap` — par déploiement TCK : extrait les propriétés MP Config
  de l'archive ShrinkWrap, démarre un container Vauban avec
  `FaultToleranceInterceptor`, `HeisenbergExtension`, `StateRegistryBean`,
  `BulkheadStateRegistryBean`, et toutes les classes de l'archive ; active le
  `RequestContext` pour la durée du test.
- `HeisenbergTestEnricher` — injection `@Inject` (et `@Inject Instance<T>`)
  des champs des classes de test TCK via le `BeanManager` Vauban.
- `HeisenbergArquillianExtension` — enregistre les services via
  `META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension`.
- `arquillian.xml` — qualifier `heisenberg` (sélectionné par
  `arquillian.launch=heisenberg`).
- `tck-suite.xml` — sélection des packages TCK
  (`org.eclipse.microprofile.fault.tolerance.tck.*`).

## Multiplicateur de timeout

Pour les environnements CI lents, augmenter dans le profil `tck-official`
du POM ou en ligne de commande :

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh all \
    -Dorg.eclipse.microprofile.fault.tolerance.tck.timeout.multiplier=2.0
```

## État actuel

Voir [`TCK.md`](../TCK.md) à la racine du projet pour le score TCK courant et
la liste des tests exclus avec justification.

## Pourquoi hors reactor

`heisenberg-tck/pom.xml` reste en Model 4.0.0 et **hors du reactor** parce que
ShrinkWrap Maven Resolver 3.3 (dépendance transitive Arquillian) utilise
`maven-resolver` 1.9 / `maven-model` 3.9 qui ne savent pas parser les POMs
Model 4.1.0 du reactor. Ne pas changer ce modèle tant que ShrinkWrap n'est pas
mis à jour (contrainte commune à tout l'écosystème Vidocq — knock-tck,
cassini-tck, cyrano-tck, heisenberg-tck).

