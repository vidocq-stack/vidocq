# TCK Jakarta Transactions 2.0 — état Mansart

## Vue d'ensemble

| Couche | État | Vérif |
|---|---|---|
| Auto-récupération du zip Eclipse | ✅ | `./install-tck.sh --verify` |
| Installation M2 (`jakarta.transaction:jakarta.transaction-tck:2.0.1`) | ✅ | `ls $HOME/.m2/repository/jakarta/transaction/jakarta.transaction-tck/2.0.1/` |
| Adaptateur Java (`MansartTckProvider`, `MansartUserTransaction`) | ✅ | `mansart-transactions-tck/src/main/java/` |
| Smoke wiring 5 tests JUnit | ✅ 5/5 | `./run-official-tck-transactions-2.0.sh smoke` |
| Suite TCK officielle (tsharness `tsant`) | ⏳ M6b — manuelle | Procédure dans `mansart-transactions-tck/README.md` |
| Sigtest Jakarta Transactions API | ⏳ M6b | Inclus dans tsharness |

## Pourquoi pas d'auto-runner pour la suite complète ?

Le TCK Jakarta Transactions 2.0 distribué par Eclipse est un **harness Sun tsharness** historique
(format hérité de l'ère Sun Java EE TCK), avec :

- un `bin/build.xml` Apache Ant,
- un `bin/ts.jte` qui décrit ~80 propriétés (JAVA_HOME, classpath SUT, hostnames, ports, …),
- des sources de tests sous `src/com/sun/ts/tests/jta/ee/` qui sont compilées sur place par
  `ant build` puis exécutées par `ant runclient`.

Aucun jar Surefire-scannable n'existe. Tenter `dependenciesToScan` sur `jtatck.jar` ne trouve
que des classes de support (`com.sun.ts.lib.deliverable.tck.*`) — pas les tests.

Le travail M6b consiste à :

1. produire un `ts.jte` minimal templatisé qui pointe vers `MansartTckProvider` + jars Mansart ;
2. wrapper l'invocation `ant build` + `ant runclient` dans le runner pour qu'elle soit reproductible ;
3. parser le rapport tsharness HTML et le résumer dans `target/tck-report-transactions.txt`.

Pas un travail bloquant pour M2..M5 — le smoke wiring valide déjà que notre TM est appelable
exactement comme le ferait le TCK.

## Suivi upstream

- [Jakarta Transactions TCK GitHub](https://github.com/jakartaee/transactions-tck) — un format
  Maven n'est pas annoncé à la roadmap publique.
- Comparable au TCK Servlet 6.1 (cf. `foy/`) qui a fait sa migration Maven récemment.

## Procédure manuelle (résumé — voir README du module pour le détail)

```bash
./install-tck.sh                          # télécharge + dépaquette
cd .tck-cache/transactions-tck/bin
$EDITOR ts.jte                             # JAVA_HOME, jta.classes, provider Mansart
ant build && ant runclient
xdg-open ../dist/index.html                # rapport HTML
```
