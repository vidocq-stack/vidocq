# DEBUGMODE.md — Étude d'un dev mode / hot reload pour Vidocq

> **Statut : étude exploratoire.** Ce document analyse la faisabilité d'un *dev mode* type
> Quarkus (recompilation + rechargement à chaud, capacités debug par extension) pour le runtime
> Vidocq. Il **ne décrit aucune implémentation déjà réalisée** : c'est une matière de décision.
> Toute affirmation de performance ici reste qualitative ; tout chiffre devra être consigné dans
> `BENCH.md` (règle workspace) avant d'être considéré acquis.

---

## 1. Objectif & cadrage

### Ce qu'on veut
Une boucle **code → résultat** la plus courte possible pendant le développement : modifier une
ressource REST, un bean CDI ou une config, et voir l'effet sans `mvn install` complet ni redémarrage
manuel. C'est exactement la valeur de `quarkus dev` : on garde le focus, on itère vite.

### Ce qu'on ne veut PAS
**Dégrader la prod.** Vidocq vend l'inverse philosophique du hot reload :

- **codegen statique** — Class-File API (JEP 484) + APT, zéro proxy dynamique, zéro réflexion à chaud ;
- **JPMS strict** — chaque module a son `module-info.java`, lancement sur **module-path**, exports
  minimaux ;
- **AOT-friendly** — compatible GraalVM native-image / Leyden CDS ;
- **Virtual Threads** partout pour l'I/O.

Le hot reload, lui, est par nature une activité de *churn* de classloaders, de re-scan et d'état
mutable — l'antithèse de l'AOT.

### Le cadrage retenu : dev mode = dérogation explicite et documentée
C'est le même choix que Quarkus, où **`quarkus dev` ≠ `quarkus build --native`** : le mode développement
relâche volontairement certaines contraintes (JPMS strict, AOT) pour gagner en vélocité, tandis que la
**prod reste module-path + codegen statique + AOT**. Aucune des relaxations décrites ici ne doit fuiter
dans le chemin de production ou native ; tout le code du dev mode doit être compilé **hors** du chemin
prod (cf. §10, impact AOT).

---

## 2. Référence : ce que fait Quarkus

Pour situer la cible, rappel du fonctionnement de Quarkus (architecture, pas API) :

- **Déclencheurs** : goal Maven `quarkus:dev` et commande CLI `quarkus dev` (la CLI délègue au plugin).
- **Classloaders hiérarchiques** : un *base-runtime ClassLoader* stable (dépendances qui ne changent
  pas) + un *deployment/hot ClassLoader* **recréé à chaque reload** pour les classes applicatives.
- **Trigger on-request** : Quarkus ne reconstruit pas en boucle ; à la **prochaine requête HTTP** après
  un changement de source, il bloque, recompile les sources modifiées (compilateur en process),
  rejoue l'**augmentation** (les *build steps* — l'équivalent de la génération de code), recrée le CL
  applicatif, puis sert. Économe : pas de rebuild si on ne sollicite pas le serveur.
- **DevServices** : les extensions auto-provisionnent leurs dépendances de dev (bases de données via
  conteneurs, brokers, etc.) sans config manuelle.
- **Dev UI** : une console `/q/dev` à laquelle chaque extension contribue des panneaux (beans, routes,
  config, santé…).
- **Continuous testing** : relance des tests impactés en arrière-plan.

Points transposables à Vidocq : la **hiérarchie de classloaders**, le **trigger on-request**, la
**ré-génération à chaque reload**, le **modèle par extension** (DevServices + Dev UI).

---

## 3. Contraintes propres à Vidocq (et ce que l'architecture actuelle permet)

Faits vérifiés dans le code (référence `fichier:ligne` en annexe §11) :

| Brique | État actuel | Conséquence pour le reload |
|---|---|---|
| **Lancement prod** | module-path JPMS strict : `java --module-path lib --module …` généré par `VidocqPackageMojo` | Recharger = recréer une **couche de classes** (process neuf, ou `ModuleLayer` enfant). |
| **Lifecycle** | `VidocqBootstrap` mono-coup : `configure() → start() → awaitShutdown() → shutdown()` ; arrêt des extensions en ordre inverse puis `container.close()` | Pas de boucle reload native : il faut un cycle **re-entrant** `shutdown()` → re-`configure()/start()`. |
| **SPI extension** | 4 phases `configure / beforeStart / onStart / onStop`, **aucun hook reload** | Ajouter des hooks dev optionnels (cf. §7). |
| **Codegen** | APT `VaubanProcessor` dans `javac` (marqueurs `META-INF/vauban-bce-processed`, index `META-INF/vauban-beans.list`) **+** `VaubanGenerator.generate(config)` rejouable **in-process** sur un dossier de `.class` + un `URLClassLoader` | Un reload doit **rejouer javac+APT** (classes projet) et/ou **`VaubanGenerator`** (dépendances). La partie `VaubanGenerator` est déjà une API in-process — atout. |
| **Serveur chappe** | `Server.start()/stop()/isRunning()`, **drain gracieux** (`shutdownGracePeriod`, 30s défaut), VT-per-connexion, `SO_REUSEADDR/REUSEPORT` | Le serveur est **redémarrable proprement** ; rebind du port OK. |
| **Précédent CLI** | `chappe-cli` (`chappe serve`, mini-YAML, fat-jar, jlink) existe déjà | Modèle pour un futur `vidocq dev` autonome. |

**Atout différenciant** : le `VidocqBootstrap` logge déjà « *Started in X ms* ». Si le démarrage à froid
est de l'ordre de quelques dizaines de millisecondes (à mesurer, §9/M1), alors **un restart de process
complet est lui-même un hot reload acceptable** — ce qui n'est pas le cas d'un Spring/Quarkus classique
au démarrage lourd. Cette rapidité change l'équation du choix d'approche.

**Contrainte de fond** : tout reload doit (1) **rejouer la génération de code** et (2) **recréer une
couche de classes**. Les deux approches ci-dessous diffèrent sur *comment* recréer cette couche.

---

## 4. Approche A — Fast process-restart

### Principe
Le goal `vidocq:dev` **fork un JVM enfant** lancé **exactement comme la prod** (module-path). Un
*watcher* (parent) surveille `src/main/{java,resources}`. Sur changement :

```
[watcher] détecte une modif sous src/
   → recompile incrémentale : mvn process-classes
        (javac + APT VaubanProcessor + vidocq:generate / VaubanGenerator)
   → stop() gracieux du JVM enfant   (shutdown hook VidocqBootstrap + drain chappe, déjà propres)
   → relance du JVM enfant            (java --module-path … --module …)
```

Le serveur revient à l'état neuf, mais identique à la prod.

### Pour
- **Honore 100 % JPMS strict + codegen statique** : chaque run est une « prod miniature », rien n'est
  relâché côté isolation modulaire ni génération.
- **Zéro risque d'état résiduel** : pas de fuite mémoire, pas de classes fantômes, pas de piège
  `ScopedValue`.
- **Réutilise tout l'existant** : `VidocqBootstrap`, le shutdown hook, le drain chappe, la chaîne de
  build. Effort d'implémentation faible.

### Contre
- **Perd l'état applicatif** à chaque reload (sessions, caches en mémoire).
- **Latence = temps de restart** (recompile + stop + start). Vraisemblablement faible vu la rapidité de
  démarrage, **mais à mesurer** (M1) avant de conclure.

---

## 5. Approche B — In-VM live reload via `ModuleLayer` enfant

### Principe
Un **seul JVM**, **serveur chappe maintenu up**. Les classes applicatives vivent dans un **`ModuleLayer`
enfant + un loader dédié** (`Configuration.resolve` + `ModuleLayer.defineModulesWithOneLoader`), parenté
par le *boot layer* qui contient chappe/vauban/extensions (couche **stable**, jamais rechargée). Sur
reload (déclenché **on-request**, comme Quarkus) :

```
[1ʳᵉ requête après modif]
   → compile in-process     (JDK Compiler API + APT Vauban + VaubanGenerator)
   → nouveau child ModuleLayer + loader à partir des classes recompilées
   → rebuild VaubanContainer en scannant ce nouveau loader
   → swap atomique du Handler/Router côté chappe (le serveur ne redémarre pas)
   → ancien layer + loader + container partent au GC
```

### Pour
- **Boucle la plus rapide** : on ne paie ni le `stop/start` du serveur ni le coût JVM.
- **État serveur préservé** : connexions, port, threads d'I/O intacts.
- **Préserve JPMS** — avantage **net sur Quarkus** : un `ModuleLayer` enfant reste constitué de
  **modules réels** (avec leurs `module-info`), là où Quarkus recourt à un ClassLoader « plat »
  non-modulaire. Vidocq pourrait offrir un hot reload *modulaire*.

### Contre
- **Pièges d'état statique** : singletons, champs `static`, et surtout `ScopedValue`
  (`RequestContext.CURRENT` de chappe) — risque de référencer des classes de l'ancien layer.
- **Fuites de références boot→app** : si une classe du boot layer retient une instance applicative,
  l'ancien loader ne sera jamais GC (classloader leak classique).
- **Régénération d'index** : `vauban-beans.list` et les factories doivent être régénérés et rechargés
  proprement dans le nouveau loader.
- **Complexité nettement supérieure** ; surface de bugs subtils.

---

## 6. Comparatif A vs B

| Critère | A — Process-restart | B — In-VM ModuleLayer |
|---|---|---|
| Honore JPMS strict | ✅ total (run = prod) | ✅ partiel (child layers = modules réels) |
| Honore codegen statique | ✅ full pipeline rejoué | ✅ rejoué in-process |
| Latence de reload | restart complet (à mesurer) | la plus faible |
| Préservation d'état serveur | ❌ perdu | ✅ conservé |
| Risque / complexité | **faible** | **élevé** (état statique, leaks) |
| Compat AOT (prod intacte) | ✅ trivial | ✅ si bien isolé du chemin prod |
| Effort d'implémentation | faible | élevé |

**Conclusion : choix renvoyé au jalon de décision M2.** Hypothèse de travail : si le restart mesuré en
M1 est **sub-100 ms**, l'approche **A est probablement suffisante seule**, et B devient un raffinement
optionnel (`--in-vm`) plutôt qu'une nécessité. La rapidité de démarrage de Vidocq est précisément ce qui
peut rendre l'approche simple compétitive.

---

## 7. Modes debug **par extension**

Modèle inspiré de Quarkus (DevServices + Dev UI), **gated par un profil** `vidocq.profile=dev`. En
prod, les hooks dev sont absents/no-op et doivent être éliminés du chemin (DCE / compilation séparée,
cf. §10).

### 7.1 SPI dev optionnelle
Une extension dev déclare ses besoins via une SPI dédiée — esquisse conceptuelle (signatures
**illustratives**, non figées) :

- `VidocqDevExtension` (parallèle à `VidocqExtension`, ou enrichissement de `ExtensionContext`) :
  - **chemins surveillés** au-delà de `src/main/java` (ex. cassini surveille les classes de ressources,
    foy un `web.xml`, champollion une config JSON-B) ;
  - **codegen à rejouer** au reload (quel générateur, sur quelle entrée) ;
  - **granularité de reload** demandée : `CONFIG_ONLY` / `BEAN_GRAPH` / `FULL_RESTART`. La boucle choisit
    alors le reload **le moins cher suffisant** (recharger juste la config coûte bien moins qu'un rebuild
    complet du graphe de beans).

### 7.2 Dev Console
Un endpoint **dev-only** monté sous un préfixe `Router` chappe, p.ex. `/_vidocq/dev` (jamais monté hors
profil dev). Chaque extension contribue un panneau :

| Extension | Panneau Dev Console |
|---|---|
| vauban | liste des beans découverts, scopes, intercepteurs |
| cassini | routes REST (méthode, path, ressource, producteurs media-type) |
| foy | servlets, filtres, mappings |
| champollion | config JSON-B active, adapters enregistrés |
| humboldt | dernières traces / spans |
| mansart | datasource active, état du pool |

### 7.3 DevServices
Un hook `devServices()` qui **provisionne les dépendances de dev** au démarrage du dev mode et les
libère au stop :

- **mansart** → démarre une base H2 (ou conteneur) éphémère (cf. exemple existant
  `vidocq-mps-mansart-h2-example`) ;
- **cyrano** → démarre un *upstream stub* pour le MicroProfile Rest Client.

### 7.4 Découverte
Toujours via `ServiceLoader` (cohérent avec `ExtensionLoader`). Les hooks dev sont simplement **absents
ou no-op** quand `vidocq.profile != dev`.

---

## 8. Impacts sur le code existant (esquisse — hors périmètre de cette étude)

Pour mémoire, ce qu'une future implémentation toucherait (aucun de ces changements n'est réalisé ici) :

- **`vidocq-runtime-core`** : rendre `VidocqBootstrap` **re-entrant** (cycle shutdown→restart en process)
  ; lecture du profil dev.
- **`vidocq-runtime-spi`** : profil dev dans `VidocqConfig` ; SPI dev (`VidocqDevExtension` / extension
  d'`ExtensionContext`).
- **`vidocq-runtime-maven-plugin`** : nouveau **`VidocqDevMojo`** (`vidocq:dev`) — watcher + recompile
  incrémentale + pilotage du JVM enfant (A) ou de la boucle in-VM (B).
- **`vidocq-runtime-chappe-extension`** : **swap de `Handler`/Router** atomique (nécessaire surtout pour
  B ; pour A, le serveur repart de zéro).
- **CLI** : `vidocq dev` standalone calqué sur `chappe-cli` (post-MVP, le Mojo restant le délégué).

---

## 9. Roadmap jalonnée (proposition)

> Proposition de séquencement — à arbitrer avec `ROADMAP.md`. **M1 livré 2026-05-28**, le
> reste reste à arbitrer.

- **M0 — Fondations.** Profil dev `vidocq.profile=dev` + flag de log. `VidocqBootstrap` re-entrant :
  PoC `shutdown()` → re-`configure()/start()` en process, sans fuite. *Reporté — pas requis pour A.*
- **M1 — Mojo `vidocq:dev` (Approche A) — ✅ implémenté.** Fork JVM enfant module-path + watcher
  NIO `src/` + recompile incrémentale via `mvnw process-classes` + restart gracieux. Bloquant, Ctrl+C
  propre. Vit dans `vidocq-runtime-maven-plugin` ; aucune modif du runtime. Mesures dans `BENCH.md`.
- **M2 — Décision A vs B.** Mesures M1 : reload p50 ≈ 1.8 s sur cassini-rest-example (dominé par le
  démarrage Maven ~1.6 s ; boot Vidocq lui-même = 110 ms). Décision préliminaire : **A suffit** tant
  qu'on ne descend pas sous la barre psychologique des 2 s. Une optimisation `mvnd` (Maven Daemon)
  ramènerait probablement le reload sous 500 ms, ce qui rendrait B clairement non rentable. *À
  revérifier après essai mvnd.*
- **M3 — SPI dev + Dev Console.** `VidocqDevExtension` + granularité de reload ; Dev Console
  `/_vidocq/dev` minimale (premier panneau : beans vauban).
- **M4 — DevServices.** Première cible : mansart H2 (réutilise `vidocq-mps-mansart-h2-example`).
- **M5 — (conditionnel à M2) PoC Approche B.** In-VM `ModuleLayer` enfant derrière un flag `--in-vm`.
- **M6 — CLI + continuous testing.** `vidocq dev` standalone (modèle `chappe-cli`) ; relance des tests
  impactés.

---

## 10. Risques & questions ouvertes

- **État statique / `ScopedValue` (approche B)** : `RequestContext.CURRENT` et tout champ `static`
  peuvent retenir des classes de l'ancien layer → classloader leak. À auditer avant tout PoC B.
- **Coût APT/javac à chaque reload** : la partie projet passe par `javac` (APT) ; mesurer ce coût
  (M1) et envisager une compilation incrémentale fine (uniquement les sources modifiées).
- **TCK runners hors-reactor** : `cassini-tck`, `foy-tck`, etc. sont volontairement détachés (Model
  4.0.0 standalone, incompat ShrinkWrap/Model 4.1). Le dev mode **ne doit pas** interférer avec ces
  runners ni présumer un reactor unifié.
- **Impact AOT (critique)** : tout le code du dev mode (watcher, Mojo, SPI dev, Dev Console) doit être
  **hors du chemin prod/native** — compilé dans des modules/scopes séparés, gardé par le profil dev, et
  vérifié comme éliminé à la compilation native. Aucune dépendance dev ne doit alourdir l'image AOT.
- **Granularité de reload** : bien distinguer config-only / bean-graph / full-restart pour ne pas payer
  un rebuild complet quand une simple relecture de config suffit.

---

## 11. Annexe

### Pointeurs fichiers (faits cités)
- `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqPackageMojo.java:105` —
  lancement prod module-path (`java --module-path lib --module …`).
- `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqBootstrap.java:63,90,147` — lifecycle
  `configure / start / shutdown`.
- `vidocq-runtime-spi/src/main/java/io/vidocq/runtime/spi/VidocqExtension.java:41-59` — 4 phases SPI.
- `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqGenerateMojo.java:82-105` —
  `VaubanGenerator.generate(config)` in-process.
- `chappe/chappe-api/src/main/java/io/vidocq/chappe/api/Server.java:26-39,86` — `start/stop/isRunning`,
  `shutdownGracePeriod`.
- `chappe-cli` — précédent de CLI standalone (`chappe serve`).

### Glossaire
- **Augmentation** (Quarkus) : phase de génération/transformation au build qui produit le code de
  câblage — l'équivalent du couple APT `VaubanProcessor` + `VaubanGenerator` côté Vidocq.
- **Child ModuleLayer** : couche de modules JPMS enfant d'une couche parente, créée à l'exécution via
  `ModuleLayer.defineModulesWithOneLoader` ; jetable (GC du layer + loader quand plus référencée).
- **DevServices** : provisioning automatique, en mode dev, des dépendances externes (BD, brokers…) par
  les extensions.
