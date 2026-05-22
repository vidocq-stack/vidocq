# JLINK.md

Ce document lance M9 avec un smoke check reproductible JPMS/jlink.

## Etat actuel

- `dirac-api`, `dirac-core`, `dirac-cdi-vauban` sont des modules explicites.
- `microprofile-metrics-api:5.1.1` est un module automatique (`microprofile.metrics.api`).
- Consequence: `jlink` echoue tant que ce module reste automatique.

## Smoke check M9

Script fourni: `run-jlink-smoke.sh`

Il execute:
1. build/installation locale minimale (`dirac-api`, `dirac-core`),
2. validation JPMS (`java --validate-modules`),
3. tentative de creation d'image `jlink`.

## Execution

```bash
./run-jlink-smoke.sh
```

## Resultats attendus

- JPMS validation: OK
- jlink: echec connu avec message du type:
  `automatic module cannot be used with jlink: microprofile.metrics.api`

Cet echec est le blocker principal M9.

## Prochaine etape pour M9

Lever le blocker du module automatique MicroProfile Metrics via:
- soit un artefact explicitement modulaire compatible,
- soit un bridge modulaire interne controle.

## M9.2 prototype (Option A locale)

Script fourni: `run-jlink-smoke-m92.sh`

Ce script realise un POC local, sans changer les dependances de production:
1. copie `microprofile-metrics-api-5.1.1.jar` dans `target/m92-jlink/`,
2. genere un `module-info.java` avec `jdeps`,
3. compile ce descripteur et l'injecte dans le jar copie,
4. construit une image `jlink` avec `dirac-api` + `dirac-core`.

Execution:

```bash
./run-jlink-smoke-m92.sh
```

Resultat attendu:
- image `target/m92-jlink/image` creee,
- modules visibles: `io.vidocq.dirac.api`, `io.vidocq.dirac.core`, `microprofile.metrics.api`.

Limite:
- c'est un prototype local M9.2 (pas encore un pipeline de release).


