# JLINK.md

Ce document decrit le smoke check M9 pour JPMS/jlink avec artefact modulaire.

## Etat actuel

- `dirac-api`, `dirac-core`, `dirac-cdi-vauban`, `dirac-rest`, `dirac-examples` et `dirac-bench` sont des modules explicites.
- Le blocker `jlink` est leve en generant un artefact local explicitement modulaire de `microprofile-metrics-api:5.1.1`.
- Le JAR source en cache Maven n'est pas modifie; le JAR modulaire est cree sous `target/modular-mp-metrics-api/`.

## Scripts

- `build-modular-mp-metrics-api.sh`
  - copie le JAR MP Metrics d'origine,
  - genere `module-info.java` via `jdeps`,
  - compile et injecte `module-info.class` dans la copie,
  - retourne le chemin de l'artefact modulaire genere.
- `run-jlink-smoke.sh`
  - installe `dirac-api` + `dirac-core`,
  - genere l'artefact MP Metrics modulaire,
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

## M9.2 historique

Le script `run-jlink-smoke-m92.sh` reste un POC historique; le flux principal M9 repose maintenant sur `run-jlink-smoke.sh` + artefact modulaire genere.
