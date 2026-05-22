# JLINK.md

Ce document decrit le smoke check M9 pour JPMS/jlink via l'artefact `dirac-mp-metrics-api`.

## Etat actuel

- `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, `dirac-rest`, `dirac-examples` et `dirac-bench` sont des modules explicites.
- Le blocker `jlink` est leve par l'artefact reactor `dirac-mp-metrics-api`, qui repackage `microprofile-metrics-api` avec un `module-info.class` explicite.
- Le JAR source en cache Maven n'est pas modifie.

## Scripts

- `run-jlink-smoke.sh`
  - installe `dirac-mp-metrics-api` + `dirac-api` + `dirac-core`,
  - valide les modules,
  - construit l'image `target/dirac-image-smoke`.
- `run-jlink-smoke-ci.sh`
  - wrapper CI bloquant (propage les erreurs).

## Execution locale

```bash
./run-jlink-smoke.sh
./run-jlink-smoke-ci.sh
```

## Profil Maven

```bash
./mvnw -ntp -N -Pjlink-smoke verify
```

- Le profil appelle `run-jlink-smoke-ci.sh` en phase `verify`.
- Le profil est bloquant: tout echec `jlink` fait echouer le build.

## Resultat attendu

- `jlink` termine sans erreur,
- image disponible dans `target/dirac-image-smoke`,
- module `microprofile.metrics.api` visible dans `--list-modules`.
