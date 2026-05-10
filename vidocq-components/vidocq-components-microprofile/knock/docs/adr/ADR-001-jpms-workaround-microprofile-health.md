# ADR-001 — Contournement JPMS pour `microprofile-health-api` (module automatique sans `Automatic-Module-Name`)

**Date :** 2026-05-10
**Statut :** Accepté
**Décideurs :** Équipe Vidocq

---

## Contexte

`microprofile-health-api:4.0.1` (Eclipse MicroProfile) est livré sans `module-info.class`
et sans `Automatic-Module-Name` dans son `MANIFEST.MF`. Ce JAR est donc, au sens JPMS :

- **Hors module-path par défaut** — Maven Compiler Plugin (4.0.0-beta-4) place les JARs sans
  `module-info` sur le **classpath** (unnamed module) lorsqu'il auto-détecte le mode JPMS
  (présence de `module-info.class` dans `target/classes`).
- **Inutilisable via `requires`** dans un `module-info.java` si Maven le met sur le classpath.

Knock a besoin que `microprofile-health-api` soit un module JPMS nommé à la compilation
(et dans les images jlink) pour que les `module-info.java` de `knock-api` et `knock-core`
puissent écrire `requires microprofile.health.api`.

---

## Problème en trois facettes

### Facette 1 — Compilation principale (phase `compile`)

Maven Compiler Plugin 4.0.0-beta-4 en mode JPMS construit le module-path en retenant uniquement
les JARs qui ont un `module-info.class` ou un `Automatic-Module-Name`. `microprofile-health-api`
n'ayant ni l'un ni l'autre, il est absent du module-path généré, et `requires microprofile.health.api`
échoue à la compilation avec : `module not found: microprofile.health.api`.

### Facette 2 — Compilation des tests de `knock-core` (phase `testCompile`)

Maven Compiler Plugin auto-détecte le mode JPMS si `module-info.class` est présent dans
`target/classes`. En mode JPMS pour `testCompile`, il reconstruit un module-path pour les
dépendances de test — mais `microprofile-health-api` y est également absent (même problème,
aggravé par le fait que les dépendances transitives ne sont pas toutes disponibles dans
`target/javamodules` au moment de `testCompile` pour les dépendances `test`-scoped).

Il en résulte une erreur à la compilation des tests : `module not found: microprofile.health.api`.

### Facette 3 — Déclaration `uses` impossible dans `knock-core/module-info.java`

La directive `uses org.eclipse.microprofile.health.spi.HealthCheckResponseProvider` ne peut
être déclarée que depuis le module qui "possède" le `ServiceLoader` appelant. Or
`HealthCheckResponse.named()` est implémenté **dans** le module automatique
`microprofile.health.api` qui n'a pas de `module-info.java`. Il est donc impossible d'écrire
`uses ... HealthCheckResponseProvider` depuis `knock-core/module-info.java`.

Le ServiceLoader invoqué dans `HealthCheckResponse.named()` utilise le
`Thread.currentThread().getContextClassLoader()` — approche ClassLoader hors JPMS qui
scanne `META-INF/services/`.

---

## Solution retenue

### Facette 1 — Forcer le JAR sur le module-path via `target/javamodules/`

Dans le POM parent, `maven-dependency-plugin:copy-dependencies` est exécuté en phase
`initialize` : il copie tous les JARs de scope `compile` dans `target/javamodules/`. Le
`maven-compiler-plugin` reçoit ensuite `--module-path ${project.build.directory}/javamodules`
comme premier argument `compilerArg`.

javac dérive alors le nom du module depuis le nom du fichier :

```
microprofile-health-api-4.0.1.jar
  → strip version suffix    → microprofile-health-api
  → replace [-_]+ by '.'   → microprofile.health.api
```

Ce nom correspond exactement aux `requires microprofile.health.api` dans les `module-info.java`.

### Facette 2 — `module-info.java` de `knock-core` hors de `src/main/java`

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

### Facette 3 — Double registration SPI

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

### Principe

jlink accepte deux types de modules dans son `--module-path` :

- **Modules explicites** — JAR avec `module-info.class` (ex. : `knock-api`, `knock-core`)
- **Modules automatiques** — JAR sans `module-info.class`, nommé via `Automatic-Module-Name`
  (MANIFEST.MF) **ou** dérivé du nom de fichier (même mécanisme que javac)

`microprofile-health-api-4.0.1.jar` est résolu par jlink comme module automatique
`microprofile.health.api` dès lors qu'il est présent dans le répertoire passé à
`--module-path`. C'est exactement ce que fournit `target/javamodules/`.

### Stabilité du nom de module entre versions

Le nom est dérivé de la partie **nom d'artefact** Maven (sans version), ce qui garantit sa
stabilité :

```
microprofile-health-api-4.0.1.jar → microprofile.health.api
microprofile-health-api-4.0.2.jar → microprofile.health.api
microprofile-health-api-5.0.jar   → microprofile.health.api
```

Le nom varie uniquement si l'artifact ID Eclipse MicroProfile change — ce qui n'a jamais
eu lieu entre les versions majeures de la spec Health.

### Commande jlink de référence

```bash
jlink \
  --module-path "${JAVA_HOME}/jmods:${project.build.directory}/javamodules" \
  --add-modules io.vidocq.knock.api \
  --output knock-runtime
```

`microprofile-health-api-4.0.1.jar` dans `target/javamodules/` est résolu comme
`microprofile.health.api`. Aucun flag supplémentaire n'est requis.

### Avertissement JDK

Le JDK émet ce warning lors de la création d'une image avec des modules automatiques sans
`Automatic-Module-Name` :

```
WARNING: Using automatic module microprofile.health.api from: microprofile-health-api-4.0.1.jar
```

Ce warning est **bénin** dans notre cas : le nom est stable (voir ci-dessus) et l'artefact
est officiel (Eclipse MicroProfile). Il disparaîtra si une future release de
`microprofile-health-api` ajoute `Automatic-Module-Name: microprofile.health.api` à son
`MANIFEST.MF`.

---

## Conditions de révision

Ce contournement doit être réévalué si :

1. `microprofile-health-api` publie un JAR avec `Automatic-Module-Name: microprofile.health.api`
   → les workarounds Maven (facettes 1 et 2) peuvent être simplifiés ou supprimés.
2. `microprofile-health-api` publie un JAR avec `module-info.class`
   → supprimer l'ensemble du workaround ; mettre à jour les `requires` si le module name change.
3. Maven Compiler Plugin corrige la détection JPMS pour les modules automatiques sans
   `Automatic-Module-Name` → réévaluer la nécessité du workaround facette 2.

---

## Alternatives écartées

| Alternative | Raison du rejet |
|---|---|
| Patcher le JAR `microprofile-health-api` (ajouter `Automatic-Module-Name` via `jar --update`) | Complexe dans un build multi-module ; risque de désynchronisation à chaque bump de version |
| Créer un module wrapper JAR pour `microprofile.health.api` | Sur-ingénierie ; artefact supplémentaire à maintenir et aligner |
| Exclure `microprofile-health-api` du module-path, tout sur le classpath | Abandonne JPMS strict — contraire aux principes Vidocq |
| Attendre un release MicroProfile apportant `module-info.class` | Bloquant ; pas de date annoncée sur la roadmap MicroProfile |
