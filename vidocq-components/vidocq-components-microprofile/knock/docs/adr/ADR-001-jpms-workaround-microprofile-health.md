# ADR-001 — Fork JPMS pour `knock-mp-health-api` (module explicite jlink)

**Date :** 2026-05-10
**Statut :** Accepté
**Décideurs :** Équipe Vidocq

---

## Contexte

`microprofile-health-api:4.0.1` (Eclipse MicroProfile) est livré sans `module-info.class`.
Ce JAR est donc un **module automatique**, ce qui bloque `jlink` (qui refuse les modules
automatiques). En plus, les `package-info.java` upstream utilisent
`@org.osgi.annotation.versioning.Version`, ce qui introduit une dépendance OSGi
elle aussi non modulaire.

Knock a besoin d'un module JPMS **explicite** pour `microprofile.health.api` afin que :

- `requires microprofile.health.api` compile proprement ;
- `jlink` puisse produire une image minimale sans modules automatiques ;
- `knock-core` reste utilisable en SE pur (pas de dépendance CDI obligatoire).

---

## Problèmes identifiés

1. **`jlink` refuse les modules automatiques** → `microprofile-health-api` upstream bloque
   la création d'une image dédiée.
2. **Dépendance OSGi non modulaire** via `@org.osgi.annotation.versioning.Version` dans les
   `package-info.java` → seconde source de module automatique.
3. **`knock-core`** reste soumis au workaround `module-info` pour éviter la détection JPMS
   en `testCompile` (voir section « Build Maven en trois temps » ci-dessous).

---

## Solution retenue

### Fork MicroProfile Health API

Créer un fork minimal **dans le repo** sous le module Maven
`io.vidocq.knock:knock-mp-health-api` :

- Sources copiées depuis `microprofile-health-api:4.0.1` (JAR *sources*).
- Ajout d'un `module-info.java` avec le nom **`microprofile.health.api`**.
- Ajout de `uses org.eclipse.microprofile.health.spi.HealthCheckResponseProvider`.
- Dépendances CDI déclarées en `requires static` pour conserver un usage SE pur.
- Suppression des annotations `@org.osgi.annotation.versioning.Version` dans les
  `package-info.java` pour éviter une dépendance OSGi non modulaire.
- Inclusion de `META-INF/LICENSE` et `META-INF/NOTICE` upstream.

### `module-info.java` de `knock-core` hors de `src/main/java`

`knock-core` place son `module-info.java` dans un source root séparé : `src/main/module-info/`.
La solution procède en trois temps, configurée dans `knock-core/pom.xml` :

1. **`default-compile`** (`src/main/java`) ne compile pas `module-info.java` → aucun
   `module-info.class` dans `target/classes` → Maven ne détecte pas JPMS pour `testCompile`.
2. **`maven-clean-plugin`** (`generate-test-sources`) supprime l'éventuel `module-info.class`
   stale de `target/classes` (builds incrémentiaux : évite la détection JPMS sur une classe
   résiduelle du cycle précédent).
3. **`maven-compiler-plugin`** (`prepare-package`) recompile uniquement
   `src/main/module-info/module-info.java` dans `target/classes` → le JAR final embarque
   correctement `module-info.class`.

`maven-surefire` reçoit `<useModulePath>false</useModulePath>` : les tests de `knock-core`
s'exécutent sur le **classpath**. Ce choix est intentionnel — les tests unitaires valident
la logique métier ; le câblage JPMS est validé par le smoke test TCK (`knock-tck`).

### Double registration SPI

`KnockHealthCheckResponseProvider` est déclaré **deux fois** :

| Mécanisme | Fichier | Consommé par |
|---|---|---|
| `provides ... with` | `knock-core/src/main/module-info/module-info.java` | `ServiceLoader` JPMS (modules explicites) |
| `META-INF/services/` | `knock-core/src/main/resources/META-INF/services/…HealthCheckResponseProvider` | `ServiceLoader` via ClassLoader (méthode de `HealthCheckResponse.named()`) |

La registration `META-INF/services/` est obligatoire parce que `HealthCheckResponse.named()`
ne peut pas utiliser le mécanisme JPMS `ServiceLoader.load(…)` : il invoque
`ServiceLoader.load(HealthCheckResponseProvider.class, Thread.currentThread().getContextClassLoader())`
qui scanne les fichiers de services sur le classpath — y compris les `META-INF/services/`
des modules nommés sur le module-path.

---

## Compatibilité jlink

**La solution retenue est compatible avec `jlink`.**

Le fork `io.vidocq.knock:knock-mp-health-api` est un **module explicite**
(`microprofile.health.api`). L'image jlink ne contient donc **aucun module automatique**.

Exemple de commande :

```bash
jlink \
  --module-path "${JAVA_HOME}/jmods:${project.build.directory}/javamodules" \
  --add-modules io.vidocq.knock.api \
  --output knock-runtime
```

---

## Conditions de révision

Ce contournement doit être réévalué si :

1. `microprofile-health-api` upstream publie un JAR avec `module-info.class`
   → supprimer le fork, revenir à l'artefact officiel, garder `requires microprofile.health.api`.
2. Les annotations OSGi cessent d'être nécessaires upstream
   → possibilité de restaurer les `package-info.java` originaux.
3. Maven Compiler Plugin améliore la détection JPMS pour `testCompile`
   → réévaluer la nécessité du workaround `module-info` dans `knock-core`.

---

## Alternatives écartées

| Alternative | Raison du rejet |
|---|---|
| Patcher le JAR `microprofile-health-api` (ajouter `Automatic-Module-Name` via `jar --update`) | Complexe dans un build multi-module ; risque de désynchronisation à chaque bump de version |
| Créer un module wrapper JAR pour `microprofile.health.api` | Sur-ingénierie ; artefact supplémentaire à maintenir et aligner |
| Exclure `microprofile-health-api` du module-path, tout sur le classpath | Abandonne JPMS strict — contraire aux principes Vidocq |
| Attendre un release MicroProfile apportant `module-info.class` | Bloquant ; pas de date annoncée sur la roadmap MicroProfile |
