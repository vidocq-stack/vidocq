# mansart-transactions-tck

Runner pour le **TCK officiel Jakarta Transactions 2.0** contre l'implémentation Mansart.

> **HORS reactor** — pom.xml en `modelVersion 4.0.0` standalone (pas de `<parent>`). Cf. `CLAUDE.md` racine
> du workspace : tant qu'upstream ShrinkWrap Maven Resolver ne supporte pas Maven Model 4.1.0, ce
> module reste détaché et n'est jamais buildé via `mvn -pl …` depuis le parent reactor.

## Prérequis

1. **Java 25 + Maven 4.0.0-rc-5** — pinés via `../.sdkmanrc` (`sdk env`).
2. **Artefact TCK** : `jakarta.transaction:jakarta.transaction-tck:2.0.1`.
   S'il n'est pas sur Maven Central, l'installer manuellement :
   ```bash
   mvn install:install-file \
        -Dfile=jakarta.transaction-tck-2.0.1.jar \
        -DgroupId=jakarta.transaction \
        -DartifactId=jakarta.transaction-tck \
        -Dversion=2.0.1 \
        -Dpackaging=jar
   ```

## Lancement

```bash
./run-official-tck-transactions-2.0.sh                 # smoke
./run-official-tck-transactions-2.0.sh all             # suite complète
./run-official-tck-transactions-2.0.sh -Dtest=…        # cible
```

## État

- M1 (begin/commit/rollback) : pas commencé.
- TCK harness (Arquillian standalone ou suite JUnit) : à câbler après M1.
