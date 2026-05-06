# mansart-transactions-tck

Adaptateur + harness pour le **TCK officiel Jakarta Transactions 2.0** contre l'implémentation Mansart.

> **HORS reactor** — pom.xml en `modelVersion 4.0.0` standalone (pas de `<parent>`). Cf. `CLAUDE.md`
> racine du workspace : tant qu'upstream ShrinkWrap Maven Resolver ne supporte pas Maven Model 4.1.0,
> ce module reste détaché et n'est jamais buildé via `mvn -pl …` depuis le parent reactor.

## Modes d'exécution

| Mode | Commande | Ce que ça fait |
|---|---|---|
| **smoke** *(défaut)* | `./run-official-tck-transactions-2.0.sh` | 5 tests JUnit locaux qui vérifient le wiring Mansart (`MansartTckProvider`, `MansartUserTransaction`). N'a pas besoin du TCK officiel — runnable hors-ligne. |
| **tsharness / all** | `./run-official-tck-transactions-2.0.sh tsharness` | Auto-télécharge le TCK depuis Eclipse si absent, le déballe sous `../.tck-cache/transactions-tck/`, puis imprime la procédure d'invocation tsant officielle (cf. ci-dessous). |
| ciblé | `./run-official-tck-transactions-2.0.sh -Dtest=Foo` | Pass-through Surefire pour debug d'un smoke spécifique. |

Sortie : `target/tck-transactions-output.log` + `target/tck-report-transactions.txt`.

## Auto-récupération du TCK

Le runner appelle `../install-tck.sh` qui :

1. télécharge `https://download.eclipse.org/jakartaee/transactions/2.0/jakarta-transactions-tck-2.0.1.zip` ;
2. dépaquette dans `mansart-transactions/.tck-cache/transactions-tck/` ;
3. installe `lib/jtatck.jar` dans le M2 sous `jakarta.transaction:jakarta.transaction-tck:2.0.1` ;
4. installe les jars annexes du harness (`tsharness.jar`, `sigtest.jar`, `javatest.jar`) sous `io.vidocq.mansart:mansart-tck-*`.

Idempotent (`--force` pour ré-installer, `--verify` pour vérifier seulement).

### Override

```bash
TCK_VER=2.0.1           ./run-official-tck-transactions-2.0.sh tsharness
TCK_URL=file:///…/x.zip ../install-tck.sh --force
```

## Architecture du harness

| Fichier | Rôle |
|---|---|
| `src/main/java/.../MansartTckProvider` | Singleton invoqué par le harness pour récupérer le `TransactionManager`. |
| `src/main/java/.../MansartUserTransaction` | Façade `UserTransaction` au-dessus du `TransactionManager` Mansart. |
| `src/test/java/.../MansartTckSmokeTest` | 5 tests JUnit qui vérifient le wiring (lookup → begin → commit/rollback). |

## Pourquoi le TCK officiel n'est pas Surefire-scannable

Le **TCK Jakarta Transactions 2.0** est un harness **Sun tsharness** historique :

```
.tck-cache/transactions-tck/
  bin/{tsant, ts.jte, build.xml, …}    ← scripts Ant + descripteur d'exécution
  src/com/sun/ts/tests/jta/ee/…        ← sources des tests
  lib/jtatck.jar                        ← classes de support (PAS les tests)
  classes/                              ← compilés par tsant build
  dist/                                 ← rapports HTML après tsant runclient
```

Aucun runner JUnit ne peut le piloter directement. La suite officielle s'invoque via :

```bash
cd .tck-cache/transactions-tck/bin
# 1. éditer ts.jte (JAVA_HOME, jta.classes, provider Mansart)
ant build
ant runclient
# rapport : ../dist/
```

La doc officielle est dépaquetée sous `.tck-cache/transactions-tck/docs/html-usersguide/`.

> Suivi upstream : un wrapper Maven/Surefire pour le TCK Transactions 2.0 n'existe pas
> côté Eclipse. Tant qu'il n'arrive pas, le mode `tsharness` reste un guide manuel.

## État

- ✅ Auto-récupération du zip depuis Eclipse Foundation.
- ✅ Adaptateur Mansart (`MansartTckProvider` + `MansartUserTransaction`).
- ✅ Smoke wiring 5/5 vert (lancé par défaut, sans dépendance externe).
- ⏳ Suite tsharness complète : configuration `ts.jte` à finaliser (M6b — voir `../PLAN.md`).
