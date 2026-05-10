# Knock - Claude Code Guidelines

> « Knock, ou le Triomphe de la Médecine » (Jules Romains, 1923) — un médecin qui
> diagnostique tout et transforme chaque villageois en patient potentiel.
> C'est exactement le rôle d'un système de health check : ausculter chaque composant
> du système, diagnostiquer son état (UP/DOWN), et agréger ces diagnostics en un tableau
> de santé consultable à tout instant.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK MicroProfile Health 4.0 est un artefact **public Maven Central** :
  `org.eclipse.microprofile.health:microprofile-health-tck:4.0`
  (contrairement aux TCK Jakarta, pas besoin de l'installer manuellement).

## Commandes essentielles

```bash
# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test seulement
./run-official-tck-mp-health-4.0.sh

# TCK — suite complète
./run-official-tck-mp-health-4.0.sh all

# TCK — test ciblé
./run-official-tck-mp-health-4.0.sh -Dtest=NomDuTest
```

> `knock-tck` est **hors reactor** (POM Model 4.0.0 standalone) pour contourner
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — même contrainte que `cassini-tck`,
> `foy-tck`, `champollion-tck` et `ravel-tck`. Ne pas changer ce modèle.

## Architecture

Knock est une implémentation MicroProfile Health 4.0, **zéro librairie tierce**
(pas de SmallRye Health, pas de Vert.x Health), uniquement des specs Jakarta EE /
MicroProfile en dépendances, virtual threads, JPMS strict.

```
knock-api          ← Re-expose la spec org.eclipse.microprofile.health
                     (HealthCheck, HealthCheckResponse, HealthCheckResponseBuilder,
                      @Liveness, @Readiness, @Startup)
knock-core         ← Implémentation : HealthCheckRegistry, agrégation UP/DOWN,
                     sérialisation JSON via champollion (Jakarta JSON-P/JSON-B)
knock-cdi-vauban   ← Intégration CDI Vauban : BCE découvrant les beans
                     @Liveness / @Readiness / @Startup, enregistrement auto dans le registry
knock-cassini      ← Adapter Cassini (Jakarta REST) : ressources JAX-RS /health,
                     /health/live, /health/ready, /health/started
knock-tck          ← Runner TCK officiel MicroProfile Health 4.0 (HORS reactor)
```

**Flux d'un health check :**
Requête HTTP `GET /health/live` → `knock-cassini` (ressource JAX-RS `@Path("/health/live")`)
→ `HealthCheckRegistry` → collecte de tous les `@Liveness HealthCheck` → appel `.call()`
sur chaque instance → agrégation (DOWN si ≥ 1 DOWN) → sérialisation JSON via champollion
→ réponse HTTP 200/503.

**Endpoints MicroProfile Health 4.0 :**
- `GET /health/live`    — liveness probes (`@Liveness`)
- `GET /health/ready`   — readiness probes (`@Readiness`)
- `GET /health/started` — startup probes (`@Startup`)
- `GET /health`         — agrégat de tous les checks

**Format de réponse (spec §3.1) :**
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "database-connection",
      "status": "UP",
      "data": { "responseTime": "12ms" }
    }
  ]
}
```
HTTP 200 si `status=UP`, HTTP 503 si `status=DOWN`.

## Contraintes d'architecture à ne pas violer

1. **`knock-core` ne dépend que de `org.eclipse.microprofile.health` + `jakarta.json`**
   (Jakarta JSON-P, fourni par champollion) — pas de CDI, pas de JAX-RS. Le registre,
   l'agrégation et la sérialisation JSON fonctionnent en standalone SE.
2. **`knock-cdi-vauban` dépend de `knock-core` + `jakarta.cdi`** mais jamais l'inverse —
   la découverte CDI est un module optionnel invisible depuis le cœur.
3. **`knock-cassini` dépend de `knock-core` + `jakarta.ws.rs`** (Jakarta REST, implémenté par
   Cassini) — l'adaptation JAX-RS est optionnelle et découplée du cœur. Les endpoints
   `/health*` sont des ressources JAX-RS standards, pas un handler Chappe brut.
4. **champollion est l'implémentation Jakarta JSON-P/JSON-B de référence** : `knock-core`
   déclare `requires jakarta.json` (API spec) ; champollion est fourni à l'exécution.
   Jamais Jackson, Gson, ou toute autre lib JSON tierce.
5. **JPMS strict** : tous les modules ont un `module-info.java`, packages `internal.*`
   non exportés, SPI exposée uniquement via `provides ... with`.
6. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly. Utiliser
   `ConcurrentHashMap` pour le registry, `ScopedValue` si un contexte de propagation devient
   nécessaire.
7. **Pas de réflexion `setAccessible(true)`** — aucune raison fonctionnelle dans un
   système de health check. Toute ouverture JPMS éventuelle doit être documentée.
8. **TCK MicroProfile Health 4.0 PASS à 100 %** est un contrat avant tout merge structurel.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java`.
- **Packages** :
  - `io.vidocq.knock.spi.*` = SPI public stable (extensions registry tierces)
  - `io.vidocq.knock.internal.*` = code interne (peut casser entre versions)
- **Maven groupId** : `io.vidocq.knock`.
- **Records** pour les objets immuables (`HealthCheckResult`, `HealthSnapshot`) ;
  **sealed interfaces** pour les hiérarchies fermées (status, types de probe).
- **Pattern matching** exhaustif sur switch — pas de chaîne `if/else if`.
- **JUnit 6** uniquement pour les tests (BOM `org.junit:junit-bom` 6.x).

## TDD — Test-Driven Development (obligatoire)

Knock est développé en **TDD strict**, dans cet ordre :

1. **Red** — écrire le test qui décrit le comportement attendu (citation section spec
   MicroProfile Health 4.0 en commentaire JavaDoc). Le test doit échouer pour la bonne
   raison (compilation OK, assertion KO).
2. **Green** — écrire le minimum de code pour faire passer le test.
3. **Refactor** — nettoyer en gardant les tests verts. Lancer la suite complète du
   module avant tout commit.

Règles concrètes :

- **Un test par classe publique**, nommé `<Classe>Test`, dans le même package (`src/test/java`).
- **Pas de Mockito** — doubles écrits à la main ou implémentations `HealthCheck` inline.
- **Tests par fixture spec** : pour chaque section de la spec MicroProfile Health 4.0
  référencée, un test nommé `<methode>_spec_section<X>_<Y>()`.

## TCK — Technology Compatibility Kit

MicroProfile Health TCK — exécuté dans un module hors reactor (`knock-tck`, POM Model 4.0.0)
pour contourner ShrinkWrap Maven Resolver 3.3 :

| TCK | Artifact | Cible |
|---|---|---|
| MicroProfile Health 4.0 | `org.eclipse.microprofile.health:microprofile-health-tck:4.0` | 100 % PASS (contrat) |

Le script `run-official-tck-mp-health-4.0.sh` :

- supporte `smoke` (par défaut), `all`, et `-Dtest=NomDuTest` ciblé ;
- installe le reactor en local (`mvn install -DskipTests`) avant invocation ;
- produit un rapport `target/tck-report.txt` avec le score PASS/FAIL/SKIP.

**Discipline de release :**

- **Aucun merge structurel** sur `knock-core`/`knock-cdi-vauban` sans TCK PASS.
- Les éventuels challenges (tests désactivés pour interprétation spec ou bug TCK) sont
  documentés dans `TCK.md` avec citation spec, hash du test, et plan de réactivation.

## Principes IA — collaboration sur ce dépôt

- **Plan mode par défaut** sur tout changement structurel (nouveau module, nouvelle SPI,
  modification du registry ou des endpoints HTTP).
- **Élégance équilibrée** : préférer un design simple qui passe le TCK à un design parfait
  qui ne le passe pas. Documenter les arbitrages dans des ADR (`docs/adr/`).
- **Pas de paresse sur les specs** : citer la section MicroProfile Health 4.0 dans les
  commentaires de code quand l'implémentation y répond directement.
- **Zéro librairie tierce** : les specs Jakarta EE et MicroProfile sont les seules
  dépendances autorisées en scope `provided`/`compile`. Si une lib d'implémentation
  semble nécessaire, c'est qu'on s'est trompé de découpe.
- Utiliser les agents **`jpms-guardian`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`** proactivement sur toute modification de `module-info.java`,
  code concurrent, ou `pom.xml`.
- Si les règles de ce fichier doivent être mises à jour, penser à aligner `AGENTS.md` de la
    même façon, pour que Copilot Code puisse s'y référer facilement.
