# Ravel TCK Runner

Harness Arquillian pour la suite officielle **MicroProfile Config 3.1 TCK**
(`org.eclipse.microprofile.config:microprofile-config-tck:3.1`).

## ⚠ Hors reactor

Ce module est volontairement **hors du reactor Ravel** et utilise un POM
`Model 4.0.0` standalone — voir le commentaire en tête de `pom.xml`.

Ne pas réintégrer dans `<subprojects>` de `ravel/pom.xml` tant que
ShrinkWrap Maven Resolver ne supporte pas Maven Model 4.1.0.

## Lancement

Toujours via le script à la racine de Ravel :

```bash
cd ravel
./run-official-tck-mp-config-3.1.sh         # smoke test
./run-official-tck-mp-config-3.1.sh all     # suite complète
./run-official-tck-mp-config-3.1.sh -Dtest=NomDuTest
```

Le script installe d'abord le reactor (`mvn install -DskipTests`) puis
invoque `mvn -f ravel-tck/pom.xml -Ptck-official test`.

## Statut

Phase M0 : squelette ; le harness Arquillian sera implémenté en M5
(cf. `ROADMAP.md`).
