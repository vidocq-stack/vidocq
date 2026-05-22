# AGENTS.md

## Mission du dépôt

- Knock implémente **MicroProfile Health 4.0** en Java 25, avec **zéro librairie tierce
  d'implémentation** : seulement la spec MP Health dans `knock-core`, Jakarta APIs uniquement
  côté CDI (`README.md`, `pom.xml`, `CLAUDE.md`).
- Architecture JPMS stricte : `knock-api` ré-exporte la spec, `knock-core` reste standalone SE
  (dépend de `jakarta.json` / champollion pour la sérialisation JSON), `knock-cdi-vauban` est
  un adaptateur CDI optionnel, `knock-cassini` est l'adaptateur Jakarta REST optionnel
  (ressources JAX-RS `/health*` via Cassini), `knock-tck` reste hors reactor.
- Utiliser de préférence `ROADMAP.md` pour suivre l'avancement du projet plutôt que de mettre
  à jour ce fichier, qui est destiné à être un guide de contribution pour les agents.
- Si les règles de ce fichier doivent être mises à jour, penser à aligner `CLAUDE.md` de la
  même façon, pour que Claude Code puisse s'y référer facilement.

## État réel du code à connaître avant de modifier

- `M1` est livré : builder `HealthCheckResponse`, registry, agrégateur parallèle (virtual threads)
  et sérialisation JSON-P sont implémentés dans `knock-core` (voir `io.vidocq.knock.internal.*`
  + tests `KnockHealthCheckResponseBuilderTest`, `KnockAggregatorTest`, `KnockJsonSerializerTest`).
- `M2` est livré : BCE `HealthCheckCdiExtension` (validation §4.2), `HealthCheckRegistrar`
  (auto-enregistrement §4.1) et bean CDI `KnockCdiHealthCheckRegistry` dans `knock-cdi-vauban`
  (tests `HealthCheckCdiIntegrationTest` 5/5 PASS contre Vauban embedded).
- `M3` est livré : ressource JAX-RS `KnockHealthResource` (`@Path("/health")`, 4 endpoints) dans
  `knock-cassini` (tests `KnockHealthResourceTest` 7/7 PASS, sans container HTTP via un
  `TestRuntimeDelegate` minimal local — zéro import interne Cassini).
- Le flux cible dans `knock-core` : `HealthCheckRegistry.getChecks(ProbeType)` → appel `.call()`
  sur chaque `HealthCheck` → agrégation (DOWN si ≥ 1 DOWN) → `HealthSnapshot` (status + liste).
- Le builder MP Health est déjà branché via SPI ServiceLoader :
  `KnockHealthCheckResponseProvider` + `META-INF/services` + `provides` JPMS dans
  `knock-core/src/main/module-info/module-info.java`.
- **SPI runtime exportée** : `io.vidocq.knock.runtime.{HealthCheckRegistries, KnockHealthService,
  HealthReport}` dans `knock-core` — point d'entrée unique pour les adaptateurs
  (`knock-cdi-vauban`, `knock-cassini`). Ne jamais dépendre de `io.vidocq.knock.internal.*`
  depuis un module sœur.
- `knock-cdi-vauban` découvrira à la phase BCE les beans annotés `@Liveness`, `@Readiness`,
  `@Startup` et les enregistrera dans le `HealthCheckRegistry` via `HealthCheckRegistrar`.
- `knock-cassini` expose les endpoints Jakarta REST (`@Path("/health")` etc.) en délégant
  au registry ; la sérialisation JSON est produite par `knock-core` via Jakarta JSON-P
  (champollion comme implémentation runtime).
- Consulter `ROADMAP.md` pour l'état détaillé de chaque milestone.

## Frontières à ne pas casser

- Ne jamais remettre `knock-tck` dans le reactor : le parent `pom.xml` l'exclut
  volontairement à cause de ShrinkWrap Maven Resolver / Model 4.0.0 vs 4.1.0.
- `knock-core` dépend de `org.eclipse.microprofile.health` + `jakarta.json` (API JSON-P spec)
  en compile ; champollion est fourni à l'exécution. CDI et Jakarta REST restent dans leurs
  modules dédiés. `jakarta.annotation` est admis **en test scope uniquement**.
- Conserver le workaround JPMS de `knock-core` : `module-info.java` reste dans
  `knock-core/src/main/module-info/` (pas dans `src/main/java`) avec recompilation dédiée en
  `prepare-package` + nettoyage de `module-info.class` avant `testCompile`. Même workaround
  appliqué à `knock-cdi-vauban` et `knock-cassini` (dépendances test-scope hors module-path).
- `knock-cassini` dépend de `knock-core` + `jakarta.ws.rs` (Jakarta REST spec) ; Cassini est
  l'implémentation fournie à l'exécution. Ne pas importer de classes internes Cassini depuis
  `knock-cassini` — se limiter à l'API JAX-RS standard.
- Garder `io.vidocq.knock.internal.*` non exporté ; toute extension doit passer par la SPI.
- Pas de `synchronized`, pas de `ThreadLocal` — virtual-thread-friendly.
- Pas de `setAccessible(true)` — aucune justification fonctionnelle dans un système de health.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) pour tous les tests.
- La sérialisation JSON se fait **uniquement via Jakarta JSON-P** (`jakarta.json.Json`) ;
  champollion en est l'implémentation. Jamais Jackson, Gson, Yasson standalone, ni StringBuilder
  si Jakarta JSON-P est disponible.

## Workflows utiles

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./run-official-tck-mp-health-4.0.sh
./run-official-tck-mp-health-4.0.sh all
./run-official-tck-mp-health-4.0.sh -Dtest=NomDuTest
```

- Le TCK passe toujours par le script racine, qui installe d'abord le reactor puis invoque
  `mvn -f knock-tck/pom.xml -Ptck-official test`.
- Le script TCK installe explicitement `knock-api,knock-core,knock-cdi-vauban,knock-cassini`
  via `./mvnw -pl ... -am install -DskipTests` avant d'exécuter `knock-tck`.
- Le TCK n'est pas « pour plus tard » : il sert de vérification continue dès M2/M3.

## Conventions de contribution observées

- TDD strict : Red → Green → Refactor, avec citation de la section MicroProfile Health
  visée dans les tests (`CLAUDE.md`, `ROADMAP.md`).
- Tests dans le même package, nommés `<Classe>Test` ; pas de Mockito — doubles manuels.
- Sérialisation JSON testée par comparaison de chaînes JSON (ou JsonObject champollion),
  jamais par réflexion sur les champs internes.

## Ce qu'un agent doit supposer pour les prochaines tâches

- `M1` est déjà en place dans `knock-core` :
  `KnockHealthCheckResponseBuilder`, `KnockHealthCheckRegistry`, `KnockAggregator`,
  `HealthSnapshot`, `KnockJsonSerializer`, et provider SPI
  `KnockHealthCheckResponseProvider`.
- `M2` = intégration CDI Vauban (BCE discover `@Liveness/@Readiness/@Startup` et auto-enregistre
  dans le registry). Validation au déploiement si un bean `HealthCheck` n'est pas qualifié.
- `M3` = endpoints Jakarta REST via Cassini (`knock-cassini`) : ressources JAX-RS
  `/health`, `/health/live`, `/health/ready`, `/health/started`. HTTP 200 si UP, 503 si DOWN.
  Réponse `Response` JAX-RS construite avec `jakarta.ws.rs.core.Response` ; corps JSON produit
  par `knock-core` via Jakarta JSON-P / champollion. **Livré** : `KnockHealthResource` +
  `KnockHealthService` (façade SPI runtime), 7/7 tests PASS.
- `M4` = TCK MicroProfile Health 4.0 à 100 % PASS — contrat dur.
- `M5` = intégration écosystème Vidocq (cassini, vidocq). Knock devient la sonde
  de santé par défaut de tout déploiement vidocq.
- Avant toute modification structurelle de `knock-core` ou `knock-cdi-vauban`, raisonner
  avec le contrat final : **TCK MicroProfile Health 4.0 à 100 % PASS**.
