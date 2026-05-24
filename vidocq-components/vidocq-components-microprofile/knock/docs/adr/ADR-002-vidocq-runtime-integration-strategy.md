# ADR-002 — Stratégie d'intégration de Knock dans l'écosystème Vidocq

- Statut : **Accepté** (M5, mai 2026)
- Décideur·euse·s : équipe Knock + équipe vidocq
- Lié à : `ROADMAP.md` §M5, `docs/integration-cassini.md`, `docs/integration-vidocq-runtime.md`

## Contexte

Knock est livré avec quatre artefacts (`knock-api`, `knock-core`, `knock-cdi-vauban`,
`knock-cassini`) et un TCK officiel à 100 % PASS (28/28). Il faut maintenant
décider *comment* Knock atterrit dans les applications Vidocq, sans imposer de
boilerplate à l'utilisateur ni casser le découpage modulaire (knock-core
standalone SE, adaptateurs CDI/JAX-RS optionnels).

## Options envisagées

### Option A — Extension `VidocqExtension` dédiée

Créer une `KnockExtension implements VidocqExtension` dans
`vidocq-runtime-knock-extension`, qui démarre/arrête explicitement le registry et
appelle Cassini pour mount la ressource.

- **+** Cycle de vie explicite, log de bootstrap dédié.
- **−** Duplique ce que `CassiniExtension` fait déjà (scan `@Path` beans).
- **−** Couple Knock à un détail d'API du SPI vidocq (priorité, ordre).
- **−** Va à l'encontre du design *zero-config* de Knock (aucun bean, aucun
  service, juste des annotations CDI standard).

### Option B — Wrapper Maven/JPMS uniquement (retenu)

`vidocq-runtime-knock-extension` n'est qu'un agrégat de dépendances + un
`module-info` qui `requires transitive` les modules Knock + champollion.
Aucune classe Java propre. L'intégration repose à 100 % sur les SPI **standards**
qui existent déjà :

1. CDI 4.1 BCE (`HealthCheckCdiExtension` enregistré via
   `META-INF/services/...BuildCompatibleExtension` + JPMS `provides`) — découvre
   les `@Liveness/@Readiness/@Startup` ;
2. JAX-RS scanning de `@Path` beans CDI (`CassiniExtension` interroge déjà
   `VaubanBeanProvider.getResourceClasses()`) — mount `KnockHealthResource`.

- **+** Zéro code Java à maintenir côté vidocq.
- **+** Knock reste utilisable hors vidocq avec exactement les mêmes deps.
- **+** Pas de couplage avec l'API `VidocqExtension` (priorité, hooks).
- **+** Ajouter / retirer la dépendance suffit à activer / désactiver.
- **−** Pas de log de bootstrap *Knock* dédié (les logs viennent de Cassini :
  « `1 resource class(es)` »). Mitigeable par un `INFO` dans `KnockHealthResource`
  côté `@PostConstruct` si nécessaire.

### Option C — Inclusion directe dans `vidocq-runtime-core`

Pas modulaire. Forcerait `vidocq-runtime-core` à dépendre de `jakarta.ws.rs` et de
Cassini, ce qui briserait le contrat *core peut tourner sans REST*.

## Décision

**Option B** retenue : `vidocq-runtime-knock-extension` est un module Maven/JPMS
*wrapper*, sans code Java. Il vit dans
`vidocq/vidocq-runtime-core-extensions/vidocq-runtime-knock-extension/` et publie
l'artefact `io.vidocq.runtime:vidocq-runtime-knock-extension`.

```
vidocq-runtime-knock-extension/
├── pom.xml                         (deps : knock-cdi-vauban, knock-cassini,
│                                    vidocq-runtime-cassini-rest-extension,
│                                    champollion-jsonp runtime)
└── src/main/java/module-info.java  (requires transitive)
```

## Conséquences

### Positives

- L'utilisateur d'un projet vidocq active Knock en ajoutant **une seule**
  dépendance ; les endpoints `/health*` apparaissent au démarrage suivant.
- Knock peut être versionné et publié indépendamment de vidocq. Le wrapper
  ne reflète que des coordonnées Maven, pas du code couplé.
- Le TCK Knock reste *self-contained* : le runner Arquillian
  (`KnockDeployableContainer`) reproduit exactement le même chemin d'intégration
  (Cassini + Vauban embedded + `KnockHealthResource`), donc valider M4 valide
  aussi le chemin M5.

### Négatives / à surveiller

- Si `CassiniExtension` change la façon dont elle découvre les `@Path` beans,
  Knock peut être impacté. → couvert par les TCK Cassini (continuous) et le TCK
  Knock (continuous).
- Pas de point d'extension *vidocq-runtime-côté* pour interposer un middleware avant
  les probes (auth, rate limit). → si le besoin émerge, créer un module
  optionnel `vidocq-runtime-knock-secure-extension` qui *en plus* du wrapper expose
  un `ContainerRequestFilter`. Hors scope M5.

## Ordre de déploiement

1. `mvn -pl knock-api,knock-core,knock-cdi-vauban,knock-cassini -am install -DskipTests`
   dans le repo `knock` (déjà couvert par `run-official-tck-mp-health-4.0.sh`).
2. `mvn -pl vidocq-runtime-core-extensions/vidocq-runtime-knock-extension -am install -DskipTests`
   dans le repo `vidocq`.
3. Toute application qui dépend de `vidocq-runtime-knock-extension` récupère Knock
   transitivement, sans rien d'autre.

## Risques

| Risque                                                                 | Mitigation                                                                                  |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| Conflit de version `jakarta.json-api` entre champollion et l'hôte      | `dependencyManagement` du parent vidocq fixe la version (alignée 2.1.x) ; champollion en runtime-only |
| BCE Knock pas découverte (ServiceLoader) en JPMS strict                | Double déclaration : `META-INF/services/` + `provides` JPMS dans `module-info`              |
| Cassini ne scanne pas `KnockHealthResource` si elle n'est pas en mode `annotated` | CDI 4.1 par défaut en mode `annotated` ; bean `@ApplicationScoped` explicitement annoté |
| TCK Knock régresse à cause d'une nouvelle version Cassini             | Le TCK Knock tourne via le script `./run-official-tck-mp-health-4.0.sh all` à chaque PR    |

## Références

- MicroProfile Health 4.0 §3 (endpoints), §4 (qualifiers), §6 (config)
- `ADR-001-jpms-workaround-microprofile-health.md` (workaround module-info pour testCompile)
- `knock-tck/src/test/java/io/vidocq/knock/tck/arquillian/KnockDeployableContainer.java`
  (runner Arquillian — reproduit le chemin d'intégration M5)

