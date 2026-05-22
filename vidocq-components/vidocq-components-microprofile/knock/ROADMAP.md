# Knock — Plan d'attaque

> Implémentation MicroProfile Health 4.0 dans le style Vidocq : zéro librairie tierce
> (specs Jakarta EE / MicroProfile autorisées), JDK 25, virtual threads, JPMS strict,
> intégration CDI optionnelle via Vauban, transport HTTP optionnel via Chappe.

## Principes directeurs

| Principe | Application concrète |
|---|---|
| Zéro librairie tierce | Pas de SmallRye Health, Vert.x Health, Jackson, Gson dans `knock-core`. Seules les API specs sont compilées : `microprofile-health-api` + `jakarta.json` (JSON-P spec, implémentée par champollion). |
| Specs Jakarta / MicroProfile autorisées | `knock-cdi-vauban` peut dépendre de `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. `knock-cassini` peut dépendre de `jakarta.ws.rs` (Jakarta REST spec, implémentée par Cassini). Le cœur `knock-core` se limite à `microprofile-health-api` + `jakarta.json`. |
| Virtual threads | Pas de `synchronized`, pas de `ThreadLocal`. Le registry utilise des structures concurrentes (`ConcurrentHashMap`, `CopyOnWriteArrayList`). Les appels `HealthCheck.call()` peuvent être parallélisés sur un `VirtualThreadPerTaskExecutor`. |
| JPMS strict | `module-info.java` partout, packages `internal.*` non exportés, SPI via `provides/uses`. Pas d'`opens` non justifié. |
| TDD strict | Red → Green → Refactor. Tests écrits avant le code de prod. Citation systématique de la section spec MicroProfile Health 4.0 dans le JavaDoc des tests. |
| TCK PASS 100 % | Contrat dur sur MicroProfile Health 4.0 TCK avant tout merge structurel. |
| AOT-friendly | Pas de génération dynamique de proxy, pas de `setAccessible(true)`. Compatible GraalVM `native-image`. |

## Méthodologie : TDD + TCK comme garde-fous parallèles

Knock est développé en **TDD strict** (Red → Green → Refactor). Aucune ligne de production
n'est écrite avant un test qui la justifie. Au-delà du cycle TDD interne :

- **Couche 1 — tests unitaires TDD** : pilotent la conception de chaque classe.
- **Couche 2 — tests d'intégration `knock-core`** : scénarios multi-checks, agrégation
  UP/DOWN, exécution concurrente. Indépendants du TCK et reproductibles sans Arquillian.
- **Couche 3 — TCK officiel** (`microprofile-health-tck:4.0`) : contrat 100 % PASS avant
  tout merge structurel. Module hors reactor (POM Model 4.0.0).

## Rappel spec MicroProfile Health 4.0 — points clés

### Annotations de qualification (§2)

| Annotation | Endpoint | Rôle |
|---|---|---|
| `@Liveness` | `/health/live` | Le process est-il vivant ? (sinon redémarrer) |
| `@Readiness` | `/health/ready` | Le process est-il prêt à recevoir du trafic ? |
| `@Startup` | `/health/started` | L'initialisation est-elle terminée ? |
| _(aucune)_ | `/health` | Agrégat de tous les checks |

### Contrat `HealthCheck` (§3)

```java
@FunctionalInterface
public interface HealthCheck {
    HealthCheckResponse call();
}
```

### Format de réponse JSON (§3.1)

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "my-check",
      "status": "UP",
      "data": { "key": "value" }
    }
  ]
}
```

- HTTP **200** si `status = UP` (ou liste vide).
- HTTP **503** si `status = DOWN` (au moins un check DOWN).
- `data` est omis si absent (ne pas sérialiser `"data": null`).

### Règle d'agrégation (§3.2)

- Statut global = **DOWN** dès qu'au moins un check individuel est DOWN.
- Liste de checks vide → statut global = **UP**.
- Les exceptions levées par `call()` doivent être capturées et converties en check DOWN
  (nom du check = nom de l'exception ou de la classe HealthCheck).

---

## Phases

### M0 — Bootstrap ✅

- ✅ `.sdkmanrc` (`java=25-tem`, `maven=4.0.0-rc-5`)
- ✅ `.gitignore`, `.mvn/maven.config`
- ✅ `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile Health)
- ✅ `CLAUDE.md`
- ✅ `AGENTS.md`
- ✅ `ROADMAP.md` (ce fichier)
- ✅ Création des 4 sous-modules avec `pom.xml` + `module-info.java` squelettes :
      `knock-api`, `knock-core`, `knock-cdi-vauban`, `knock-cassini` + `knock-tck` (hors reactor)
- ✅ `run-official-tck-mp-health-4.0.sh`
- ✅ Validation `mvn -ntp install -DskipTests` réussit (reactor + knock-tck standalone)
- ✅ Smoke test `KnockTckSmokeTest` : 3/3 PASS

**Note JPMS :** `microprofile-health-api:4.0.1` n'a ni `Automatic-Module-Name` ni `module-info.class`.
Contournement complet documenté dans `docs/adr/ADR-001-jpms-workaround-microprofile-health.md` — résumé :
`maven-dependency-plugin:copy-dependencies` copie tous les artefacts compile dans `target/javamodules/` ;
`--module-path target/javamodules` est injecté en premier `compilerArg` — javac dérive le nom
`microprofile.health.api` depuis le nom de fichier. `knock-core` requiert en plus un source root séparé
(`src/main/module-info/`) et un clean/recompile en `prepare-package` pour éviter la détection JPMS
lors de `testCompile`. Ce même répertoire `target/javamodules/` sert de `--module-path` pour jlink —
**la compatibilité jlink est garantie** (voir ADR-001, section « Compatibilité jlink »).

**Livrable :** `mvn -ntp install -DskipTests` réussit sur le reactor (knock-api, knock-core,
knock-cdi-vauban, knock-cassini) et compile aussi le projet hors-reactor `knock-tck`
(POM Model 4.0.0). Tous les `module-info.java` sont en place avec les `requires` minimaux.
Smoke test 3/3 PASS.

---

### M1 — Core : registry + agrégation + sérialisation JSON ✅

**Scope spec :** §2 (HealthCheck interface), §3 (réponse), §3.1 (format JSON), §3.2 (agrégation).

| Tâche | Notes | État |
|---|---|---|
| `KnockHealthCheckResponse` record (implémente `HealthCheckResponse`) | `name`, `Status`, `Optional<Map<String,Object>> data` | ✅ |
| `KnockHealthCheckResponseBuilder` (implémente `HealthCheckResponseBuilder`) | Builder fluent : `up()`, `down()`, `withData(key, val)`, `build()` | ✅ |
| `HealthCheckRegistry` interface (SPI interne) | `register(ProbeType, HealthCheck)`, `unregister(String name)`, `getChecks(ProbeType)` | ✅ |
| `KnockHealthCheckRegistry` (implémentation ConcurrentHashMap thread-safe) | Classé par `ProbeType` (LIVENESS, READINESS, STARTUP, ALL) | ✅ |
| `KnockAggregator` | Appelle tous les checks du type demandé, agrège (DOWN si ≥ 1 DOWN), capture les exceptions → DOWN | ✅ |
| `KnockJsonSerializer` | Sérialise `HealthSnapshot` en JSON spec §3.1 via Jakarta JSON-P (`jakarta.json.Json.createObjectBuilder()`) — champollion en est l'implémentation runtime | ✅ |
| Exécution parallèle via `VirtualThreadPerTaskExecutor` | Tous les `.call()` lancés en parallèle, résultats collectés via `Future<HealthCheckResponse>` | ✅ |
| Tests unitaires `KnockHealthCheckResponseBuilderTest` | UP/DOWN, data key-val, serialization absente si data vide — 13/13 PASS | ✅ |
| Tests unitaires `KnockAggregatorTest` | all UP → UP ; un DOWN → DOWN ; exception → DOWN ; liste vide → UP — 8/8 PASS | ✅ |
| Tests unitaires `KnockJsonSerializerTest` | status UP/DOWN, checks présents/absents, data omis si null — 8/8 PASS | ✅ |

**Décisions M1 :**
- `ProbeType` enum : `LIVENESS`, `READINESS`, `STARTUP`, `ALL`.
- `HealthSnapshot` record : `ProbeType type`, `HealthCheckResponse.Status status`, `List<HealthCheckResponse> checks`.
- Sérialisation JSON **obligatoirement** via Jakarta JSON-P (`jakarta.json.Json.createObjectBuilder()`) — champollion est la seule implémentation autorisée ; aucun fallback `StringBuilder`.

**Livrable :** `HealthCheckResponse.named("db").up().withData("latency","12ms").build()` fonctionne ;
agrégation correcte ; sérialisation JSON conforme §3.1.

---

### M2 — Intégration CDI Vauban (`knock-cdi-vauban`) ✅

**Scope spec :** §4 (CDI integration), §4.1 (discovery CDI beans), §4.2 (enregistrement automatique).

| Tâche | Notes | État |
|---|---|---|
| BCE `HealthCheckCdiExtension` (Build Compatible Extension Vauban) | `@Registration(types = HealthCheck.class)` — valide la présence de `@Liveness`/`@Readiness`/`@Startup` | ✅ |
| Enregistrement dans le `HealthCheckRegistry` au démarrage CDI | `HealthCheckRegistrar` `@ApplicationScoped` + `@Observes @Initialized(ApplicationScoped.class)` | ✅ |
| Validation au déploiement | Bean `HealthCheck` sans qualifier de probe → `messages.error()` → `DeploymentException` (spec §4.2) | ✅ |
| `@Inject HealthCheckRegistry` | `KnockCdiHealthCheckRegistry @ApplicationScoped` — bean CDI direct implémentant l'interface | ✅ |
| Tests d'intégration avec container Vauban | 5/5 PASS — bootstrap Vauban SE, `@Liveness`/`@Readiness`/`@Startup` auto-enregistrés, DOWN agrégé, déploiement rejeté si pas de qualifier | ✅ |

**Décisions M2 :**
- `@Produces` évité pour le registry : Vauban retourne le proxy du producer au lieu du bean produit
  lors de `select(HealthCheckRegistry.class)`. Solution : bean CDI direct `KnockCdiHealthCheckRegistry`
  implémentant `HealthCheckRegistry` — le proxy Vauban implémente alors l'interface et le cast est propre.
- BCE en validation seule (`@Registration`) — l'enregistrement est délégué à `HealthCheckRegistrar`
  via injection CDI standard (plus lisible que `@Synthesis`).
- `module-info.java` dans `src/main/module-info/` : même workaround que `knock-core` (vauban-core
  test-scope, absent de `target/javamodules/`).

**Livrable :** un bean `@Liveness HealthCheck` découvert automatiquement, enregistré, et
interrogeable via le registry. 5/5 tests d'intégration PASS.

---

### M3 — Endpoints Jakarta REST (`knock-cassini`) ✅

**Scope spec :** §3 (endpoints), §3.1 (format JSON), codes HTTP 200/503.

| Tâche | Notes | État |
|---|---|---|
| `KnockHealthResource` ressource JAX-RS `@ApplicationScoped @Path("/health")` | 4 méthodes `@GET` : `/`, `/live`, `/ready`, `/started` ; délègue au registry via `KnockHealthService` | ✅ |
| Mapping `ProbeType` ↔ chemin JAX-RS | `ALL` → `/health`, `LIVENESS` → `/health/live`, `READINESS` → `/health/ready`, `STARTUP` → `/health/started` | ✅ |
| Code HTTP 200 / 503 via `jakarta.ws.rs.core.Response` | `Response.status(report.httpStatus()).type(APPLICATION_JSON_TYPE).entity(json).build()` | ✅ |
| Content-Type `application/json` | `@Produces(MediaType.APPLICATION_JSON)` sur la ressource | ✅ |
| `@Inject HealthCheckRegistry` dans la ressource | Injection champ + ctor public pour tests directs | ✅ |
| Tests TDD `KnockHealthResourceTest` | 7/7 PASS (200 vide, 503 down, isolation par probe, exception → DOWN, content-type) | ✅ |
| SPI runtime exportée `io.vidocq.knock.runtime.{HealthCheckRegistries, KnockHealthService, HealthReport}` | Frontière JPMS : `internal.*` reste non exporté ; les adaptateurs passent par cette SPI | ✅ |

**Décisions M3 :**
- Façade `KnockHealthService(registry).report(probeType)` retournant un `HealthReport(httpStatus, json)` — point d'entrée unique pour les transports HTTP. Encapsule `KnockAggregator` + `KnockJsonSerializer`.
- Frontière respectée : aucun import de classe interne Cassini ; `Response.type(MediaType.APPLICATION_JSON_TYPE)` (instance, pas string) pour éviter le passage par `RuntimeDelegate.HeaderDelegate.fromString` aux tests.
- Tests sans container HTTP : un `TestRuntimeDelegate` minimal de test (zéro lib tierce, zéro classe interne Cassini) installé via `RuntimeDelegate.setInstance(...)`.
- Refactor M2 collatéral : `KnockCdiHealthCheckRegistry` ne dépend plus de `io.vidocq.knock.internal.KnockHealthCheckRegistry` — elle passe par la factory exportée `HealthCheckRegistries.newRegistry()`.

**Livrable :** `GET /health/live` retourne 200 `{ "status": "UP", "checks": [...] }` quand
tous les checks liveness passent ; 503 sinon. Endpoints unitairement validés (7/7 PASS).
Intégration end-to-end avec Cassini embedded reportée à M5.

---

### M4 — TCK MicroProfile Health 4.0 ✅

**Scope :** validation officielle MicroProfile Health 4.0 + script reproductible.

| Tâche | Notes | État |
|---|---|---|
| `knock-tck/pom.xml` Model 4.0.0 standalone | Idem `cassini-tck`/`foy-tck`/`ravel-tck` — hors reactor | ✅ |
| Runner Arquillian + harness officiel `microprofile-health-tck:4.0.1` | Container Arquillian custom `KnockDeployableContainer` (zéro lib container tierce — pas de Weld embedded, pas d'Undertow) | ✅ |
| Adapter Arquillian → Knock embedded | Réutilise `CassiniTestHarness` (cassini-tck) pour Jakarta REST + chappe-http pour le serveur HTTP ; mini-CDI maison (~30 LOC) pour l'injection des `@Inject HealthCheck` (pas de Weld) | ✅ |
| `run-official-tck-mp-health-4.0.sh` | Modes : smoke / all / `-Dtest=NomTest` ; rapport `target/tck-report.txt` | ✅ |
| **Score contrat : 100 % PASS** | **28 tests run / 28 PASS / 0 fails / 0 skipped** | ✅ |

**Décisions M4 :**
- **Pas de Weld embedded** : la stack Arquillian/Weld embedded crashe sous JDK 25 et viole
  l'engagement « zéro lib tierce d'implémentation ». À la place, un `KnockDeployableContainer`
  custom (~250 LOC, test-scope only) reçoit chaque `WebArchive` Arquillian, scanne les classes
  `HealthCheck` qualifiées, instancie un registry, attache un `KnockHealthResource` singleton
  à une `Application` JAX-RS, et démarre `CassiniTestHarness` sur un port libre. L'URI est
  exposée au TCK via `ProtocolMetaData(HTTPContext)`.
- **Mini-CDI maison** : pour les tests TCK qui font `@Inject` dans une `HealthCheck`
  (`DelegateHealthSuccessfulTest`), un mini-injecteur récursif instancie le type du champ via
  `getDeclaredConstructor().newInstance()` et l'écrit via `MethodHandles.privateLookupIn`
  (zéro `setAccessible(true)`). Pas de qualifiers, pas de scopes — strictement le minimum
  pour faire passer le TCK sans embarquer Weld.
- **Producers CDI** : `@Produces @Liveness/@Readiness/@Startup HealthCheck producer()` sont
  invoqués via `MethodHandles.privateLookupIn` (méthodes package-private du TCK officiel).
- **`microprofile-config.properties`** : lu depuis `/META-INF/microprofile-config.properties`
  du WAR et reposté en system property pour `mp.health.default.{readiness,startup}.empty.response`.
  Cleanup systématique entre déploiements pour éviter les fuites entre tests.
- **Aucun `TCK.md`** créé : aucun challenge à documenter (pas de test désactivé,
  pas d'interprétation spec divergente).

**Livrable :** TCK MicroProfile Health 4.0 **28/28 PASS** reproductible via
`./run-official-tck-mp-health-4.0.sh all` (rapport `knock-tck/target/tck-report.txt`).

---

### M5 — Intégration écosystème Vidocq ✅

**Scope :** déployer Knock dans Cassini, et `vidocq` ; faire de Knock le système de
health check par défaut de tout déploiement vidocq.

| Tâche | Notes | État |
|---|---|---|
| Documentation `docs/integration-cassini.md` | Dépendances, JPMS, exemple ressource JAX-RS avec health check dédié | ✅ |
| Documentation `docs/integration-vidocq.md` | Configuration health check dans vidocq, accès par défaut `/health` | ✅ |
| ADR-002 stratégie d'intégration | Rationale, ordre de déploiement, risques (cf. `docs/adr/ADR-002-vidocq-runtime-integration-strategy.md`) | ✅ |
| ServiceLoader BCE (`META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`) | `HealthCheckCdiExtension` exposée via le contrat CDI 4.1 standard | ✅ |
| `module-info.java` `provides ... with` | Doublure JPMS pour les fichiers de services (cf. `knock-cdi-vauban` et `knock-core`) | ✅ |
| `vidocq` : intégrer Knock comme système de health check | Module wrapper `vidocq-runtime-knock-extension` (Maven/JPMS, sans code Java) ajouté dans `vidocq-runtime-core-extensions/`. Active Knock via une seule dépendance, intégration zero-config (BCE + scanning JAX-RS Cassini). | ✅ |

**Décisions M5 :**

- **Wrapper Maven/JPMS plutôt que `VidocqExtension` dédiée** (ADR-002) : aucun code Java
  côté vidocq. Knock s'auto-déploie via deux SPI standards — BCE de `knock-cdi-vauban`
  pour découvrir `@Liveness/@Readiness/@Startup`, et scanning `@Path` de
  `CassiniExtension` pour mount `KnockHealthResource`. Bénéfice : Knock reste utilisable
  hors vidocq avec exactement les mêmes deps.
- **`vidocq-runtime-knock-extension` `requires transitive`** les 4 modules Knock + dépend de
  `vidocq-runtime-cassini-rest-extension`. champollion-jsonp est runtime-only.
- **Même workaround JPMS que `knock-core`** appliqué au wrapper (module-info hors
  `src/main/java/`, recompilation en `prepare-package`, `--module-path target/javamodules`)
  pour contourner `microprofile-health-api:4.0.1` sans Automatic-Module-Name.

**Livrable :** documentation complète (intégration Cassini + intégration vidocq + ADR-002),
module wrapper `vidocq-runtime-knock-extension` installé et compilable dans le reactor vidocq,
`/health*` disponible dans tout déploiement vidocq via une seule dépendance.

---

## Ordre de priorité — pourquoi celui-ci ?

1. **M1 (core)** d'abord : le registry, l'agrégateur, et la sérialisation JSON sont le
   minimum vital. Rien d'autre ne peut être testé sans eux.
2. **M2 (CDI)** avant M3 (Jakarta REST) : les endpoints n'ont de sens qu'avec des checks
   découverts et enregistrés. Valider la découverte CDI avant de brancher le transport.
3. **M3 (Jakarta REST via Cassini)** : `knock-cassini` dépend de `knock-cdi-vauban` pour
   l'injection du registry ; peut être développé en parallèle de M2 une fois le registry
   stabilisé. L'API JAX-RS standard suffit — aucune classe interne Cassini.
4. **M4 (TCK)** : contrat de conformité. Activité continue dès M1/M2 sur les sections
   couvertes ; 100 % PASS verrouillé avant M5.
5. **M5 (intégration)** en dernier : on ne pollue pas les autres projets Vidocq avant que
   Knock soit solide et TCK-validé.

## Risques connus

| Risque | Mitigation |
|---|---|
| Incompatibilité TCK Arquillian vs JDK 25 (comme `MalformedParameterizedTypeException` sur Ravel) | Identifier et patcher dès M4 ; documenter dans `TCK.md` |
| Exécution parallèle des checks et timeout | Définir un timeout par check (configurable via `HealthCheckRegistry`) ; virtual threads permettent d'attendre sans bloquer |
| Disponibilité de champollion en runtime pour JSON-P | Valider dès M1 que `champollion` est bien sur le module-path comme implémentation Jakarta JSON-P ; c'est une dépendance runtime obligatoire de `knock-core` |
| Interaction entre checks de types différents sur `/health` | Tester agrégation ALL avec mix LIVENESS/READINESS/STARTUP dont certains DOWN |
| JPMS et discovery ServiceLoader dans `knock-cdi-vauban` | Vérifier que les `provides` CDI ne nécessitent pas d'`opens` sur les modules utilisateur |
| Version du TCK MicroProfile Health 4.0 disponible sur Maven Central | Vérifier la disponibilité de `org.eclipse.microprofile.health:microprofile-health-tck:4.0` et l'Automatic-Module-Name du JAR API |

## Décisions actées

- ✅ **Specs Jakarta / MicroProfile autorisées** : `microprofile-health-api`, `jakarta.cdi-api`,
  `jakarta.inject-api`, `jakarta.annotation-api`. Pas de SmallRye / Vert.x / Quarkus Health.
- ✅ **`knock-core` standalone SE** : utilisable sans CDI, sans Jakarta REST, sans container.
  Dépend de `jakarta.json` (JSON-P spec) avec champollion comme implémentation runtime.
- ✅ **`knock-cdi-vauban` séparé** : module optionnel, non chargé si CDI absent.
- ✅ **`knock-cassini` séparé** : module optionnel, expose les endpoints via Jakarta REST
  (API spec `jakarta.ws.rs`) avec Cassini comme implémentation. Ne jamais importer de classes
  internes Cassini — l'API JAX-RS standard suffit.
- ✅ **TDD strict** sur tous les modules de production.
- ✅ **TCK PASS 100 %** comme contrat dur.
- ✅ **TCK hors reactor** (POM Model 4.0.0 standalone) — contrainte ShrinkWrap Maven Resolver 3.3.
- ✅ **Exécution parallèle des checks** via `VirtualThreadPerTaskExecutor` — virtual-thread-friendly.

## Décisions ouvertes

- [ ] Faut-il un mécanisme de cache TTL pour les réponses health (éviter d'ausculter la DB
      à chaque requête Jakarta REST) ? → Hors spec HP 4.0, à différer en extension post-M5.
- [ ] Support d'un health check programmatique (sans CDI, via `ServiceLoader`) pour les
      déploiements SE purs ? → Possible via `META-INF/services/HealthCheck` ; à évaluer en M2.
- [ ] Stratégie de timeout par check : configurable via `@ConfigProperty` (Ravel) ?
      → Cohérence avec l'écosystème Vidocq ; à confirmer en M3.
- [ ] MicroProfile Health 4.1 ou 5.0 (quand released) — design-out pour faciliter le bump
      de version sans refactoring profond.
