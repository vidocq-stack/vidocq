# Bugs Vauban identifies

## 1. ~~Proxying de beans normal-scoped sans constructeur no-arg~~ FIXE

**Symptome :** `UnproxyableResolutionException: Normal scoped bean X has no non-private no-arg constructor`

**Contexte :** Un bean `@ApplicationScoped` avec uniquement un constructeur `@Inject` ne pouvait pas etre proxifie par Vauban. CDI 4.1 a assoupli cette contrainte.

**Status :** Fixe dans Vauban 0.1.0-SNAPSHOT. Les proxies sont generes sans constructeur no-arg.

---

## 2. ~~`Module.addReads()` manquant dans `VaubanLookup`~~ FIXE

**Symptome :** `RuntimeException: Cannot obtain Lookup for X`

**Contexte :** Vauban appelait `MethodHandles.privateLookupIn()` sans `Module.addReads()` pour les modules JPMS applicatifs.

**Status :** Fixe dans Vauban 0.1.0-SNAPSHOT.

---

## 3. ~~`sun.misc.Unsafe::staticFieldOffset` deprecie~~ FIXE

**Symptome :** `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called`

**Status :** Fixe dans Vauban 0.1.0-SNAPSHOT. Migration vers `VarHandle` / `MethodHandles.Lookup`.

---

## 4. ~~BCE `@Enhancement` ne traite pas toutes les classes correspondantes~~ FIXE

**Symptome :** `@Enhancement(types = Object.class, withAnnotations = Path.class)` ne traitait qu'une seule classe au lieu de toutes.

**Status :** Fixe dans Vauban 0.1.0-SNAPSHOT. Le `BceProcessor` invoque `@Enhancement` pour chaque classe correspondante. Verifie fonctionnel via `RestScopeExtension` qui enrichit toutes les classes `@Path`.

---

## 5. ~~VaubanProcessor APT : enrichissement des annotations trigger non fonctionnel~~ FIXE

**Symptome :** L'APT chargeait les enrichment rules depuis `vauban-apt.properties` mais ne les appliquait pas aux classes annotees `@Path`.

**Status :** Fixe dans Vauban 0.1.0-SNAPSHOT. Le `VaubanProcessor` APT execute les BCEs a la compilation, decouvre les extensions via ServiceLoader, et genere beans.list + _Factory + _ClientProxy pour les classes enrichies. Ecrit `META-INF/vauban-bce-processed` pour eviter la re-execution au runtime.

---

## 6. ~~ClientProxy : invokevirtual sur methode protected cross-package rejete par le verifier~~ FIXE

**Symptome :** `VerifyError: Bad access to protected data in invokevirtual` au demarrage d'une application utilisant des servlets `@ApplicationScoped` qui heritent de `HttpServlet`.

**Contexte :** Quand un bean normal-scoped etend une classe dont certaines methodes sont `protected` (cas typique de `HttpServlet.doGet`, `doPost`, `doHead` qui sont `protected`), le ClientProxy genere par Vauban surcharge ces methodes et tente d'appeler la super-implementation. Le bytecode emis utilise `invokevirtual` sur la super-classe (ex. `HelloServlet.doHead`) au lieu de `invokespecial` pour un super-call, ce qui est rejete par le verifier JVM (acces protected hors de la meme classe/package via virtual invocation non autorise).

**Details bytecode :**
```
Location: fr/vidocq/examples/servlet/HelloServlet_ClientProxy.doHead(...)V @14: invokevirtual
Reason: Type 'fr/vidocq/examples/servlet/HelloServlet' (current frame, stack[0])
        is not assignable to 'fr/vidocq/examples/servlet/HelloServlet_ClientProxy'
Bytecode:
  2ab4 000e b900 1601 00c0 0004 2b2c b600 1bb1
                                     ^^^^ invokevirtual (should be invokespecial for super-call)
```

**Reproduction :**
```java
@ApplicationScoped
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("hi");
    }
}
```
Lancement → `VerifyError` immediat.

**Explication JVMS §4.10.1.9 :** quand une methode `protected` est declaree dans une superclasse, le verifier exige que le type statique du receiver sur la pile soit assignable a la classe courante. Le delegate est cast en `HelloServlet` (la bean class), type qui n'est **pas** assignable a `_ClientProxy` (c'est l'inverse — `_ClientProxy extends HelloServlet`). Le bypass same-runtime-package ne s'applique pas quand la methode protected est declaree plus haut (ex. `HttpServlet.doHead` dans `jakarta.servlet.http`, package distinct du proxy).

**Fix livre :** `RuntimeClientProxyGenerator` emet desormais un dispatch par {@link java.lang.invoke.MethodHandle} pour les methodes protected (ou package-private) declarees dans une superclasse situee dans un package different du proxy. Chaque methode concernee obtient un champ `private static final MethodHandle $$mh_<name>` initialise dans `<clinit>` via `MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup()).findVirtual(...)`. L'override du proxy invoque `$$mh_<name>.invokeExact(delegate, args)` au lieu de `invokevirtual`. MethodHandle n'est pas soumis aux verifications statiques §4.10.1.9 : l'acces est controle au runtime par la `Lookup` privilegiee.

**Status :** FIXE dans Vauban 0.1.0-SNAPSHOT (`RuntimeClientProxyGenerator`). 4 tests unitaires ajoutes pour le cas protected cross-package. L'exemple `vidocq-servlet-example` utilise desormais `@ApplicationScoped` partout sans contournement, demarrage mesure en 48 ms avec 3 servlets + 1 filter + 1 listener, tous les endpoints (incluant BASIC auth et sessions) repondent correctement.

---

## 7. ~~Régression : BCE @RequestScoped auto sur @Path non appliquée au runtime BeanManager~~ FIXE

**Symptome :** Un bean `@Path` sans scope CDI explicite, enrichi par une BCE
`@Enhancement(types = Object.class, withAnnotations = Path.class)` qui lui
ajoute `@RequestScoped`, n'est **pas** exposé par
`BeanManager.getBeans(Object.class, @Any)` au runtime.

**Contexte :** `CassiniScopeBCE` (clone de l'ancien `VidocqRestScopeBCE` du
bug #5) scanne les classes `@Path` et leur ajoute `@RequestScoped` si aucun
scope n'est présent. Au build-time :

- `_ClientProxy` est bien généré pour la classe enrichie
- le FQN apparaît dans `META-INF/vauban-beans.list`

Au runtime cependant, Cassini's `ResourceScanner.discover` n'observe **que**
les beans avec scope CDI *explicite* dans le source. L'exemple
`vidocq-rest-example` :

- `HelloCDIRequestScopedResource` (scope explicite) → OK, 4 endpoints visibles
- `HelloCDIApplicationScopedResource` (scope explicite) → OK
- `HelloSimpleJaxRSResource` (scope via BCE) → **invisible au runtime**
- `io.vidocq.mpserver.examples.extlib.ExternalResource` (scope via BCE sur
  librairie externe) → **invisible au runtime**

**Indice :** le marker `META-INF/vauban-bce-processed` (documenté dans le
bug #5 comme "écrit par le VaubanProcessor APT") n'existe **dans aucun**
module du reactor (0 match sur `find`). Laisse supposer que la chaîne APT
→ BCE documentée dans #5 n'est plus exécutée, ou que la v0.1.0-SNAPSHOT
actuelle a régressé sur ce point.

**Workaround :** annoter explicitement les classes `@Path` avec
`@RequestScoped` (ou autre scope CDI normal). L'extension Cassini est
totalement fonctionnelle sous cette contrainte.

**Status :** diagnostiqué côté Vauban — fix en cours (TDD).

### Diagnostic (2026-04-24)

Investigation chirurgicale via deux passes Explore sur le reactor Vauban.

**Hypothèses initiales :**
- H1 : APT plus déclenché chez le consommateur — **écartée** (ServiceLoader
  `META-INF/services/javax.annotation.processing.Processor` OK)
- H2 : APT ne découvre plus les BCEs via ServiceLoader au compile-time —
  **écartée** (`discoverBceClasses()` fonctionne)
- H3 : APT applique les modifs en mémoire mais celles-ci ne persistent pas
  jusqu'au runtime — **CONFIRMÉE**

**Cause racine (H3 précisée) :**

`VaubanProcessor` (APT) ajoute les annotations synthétiques (`@RequestScoped`)
uniquement dans un modèle **en mémoire** (`VaubanClassConfig.getAddedAnnotations()`)
utilisé pour :
- générer `_ClientProxy` / `_Factory` (nouvelles classes)
- décider de promouvoir la classe dans `META-INF/vauban-beans.list`

Mais **ne ré-écrit jamais** le `.class` source sur disque. Les appels à
`ClassFile.of().build()` concernent uniquement les classes générées (proxies,
factories), jamais `ClassFile.of().transform()` sur le bytecode compilé par
javac.

Côté runtime, `VaubanContainerBuilder.java:484-488` contient un short-circuit :

```java
boolean allSourcesProcessed = unprocessedArchiveClasses.isEmpty()
                              && !bceProcessedSources.isEmpty();
if (allSourcesProcessed) {
    bceClasses = List.of(); // ← SKIP full BCE
}
```

L'invariant implicite de ce skip est : "quand une classe est pre-processée
par l'APT, son bytecode `.class` contient déjà les annotations synthétiques".
Cet invariant est **violé**. `BeanDiscovery.computeScope()` relit
`classInfo.annotations()` sur le bytecode **original** de javac → pas de
`@RequestScoped` → scope par défaut `@Dependent` → classe invisible via
`getBeans(Object.class, @Any)` sur les scopes normaux.

Le format actuel de `vauban-beans.list` est une simple liste de FQN, sans
métadonnée de scope associée (`VaubanProcessor.java:249-256`).

### Solution retenue

**Pas de réécriture de `.class` post-compilation** (antipattern Lombok —
l'APT doit produire de nouveaux artefacts, pas modifier ce que javac a écrit).

**Architecture hybride** :

1. **Build-time (APT)** : quand une BCE applique des modifications
   observables au runtime (annotations ajoutées, types ajoutés, qualifiers)
   à une classe, `VaubanProcessor` écrit une entrée dans un nouveau fichier
   `META-INF/vauban-bce-runtime.list` :

   ```
   # <BCE-FQN>;<target-class-FQN>
   fr.vidocq.cassini.scope.CassiniScopeBCE;io.vidocq.mpserver.examples.rest.HelloSimpleJaxRSResource
   fr.vidocq.cassini.scope.CassiniScopeBCE;io.vidocq.mpserver.examples.extlib.ExternalResource
   ```

   Une classe est inscrite ssi son `VaubanClassConfig` contient au moins
   une modification observable au runtime (annotation ajoutée, type ajouté,
   qualifier). Les BCEs en lecture seule ne produisent rien → pas d'overhead.

2. **Runtime (`VaubanContainerBuilder`)** : remplacer le short-circuit
   `bceClasses = List.of()` par :
   - Lecture de `META-INF/vauban-bce-runtime.list` sur tout le classpath
   - Pour chaque ligne `(bceFQN, classFQN)` : résoudre la BCE via
     ServiceLoader et rejouer **uniquement** sa phase `@Enhancement` sur
     `classFQN`
   - Reconstruire l'index avec les annotations ajoutées
   - Pour les JARs non pre-processés (sans marker `vauban-bce-processed`
     dans leur origine) : BCE complète comme aujourd'hui (fallback)

### Bénéfices

- Pas de réécriture `.class` → respect conventions APT / JSR-269
- Build-time reste source de vérité pour **quoi** enrichir, runtime applique
  effectivement
- Mix JAR pre-processé / non-processé naturel
- Performance runtime : O(n) sur n = enrichissements réels listés, pas
  O(classes × BCEs)
- `vauban-bce-runtime.list` sert de trace auditable des modifications BCE
- Évolutif : ajout futur d'autres types d'enrichissements via extension du
  format

### Plan TDD

1. APT produit `vauban-bce-runtime.list` avec entrées `<BCE>;<target>`
2. Runtime lit le fichier et rejoue la BCE → scope correct appliqué
3. Runtime voit la classe enrichie via `getBeans(Object.class, @Any)`
   (symptôme exact du bug #7)
4. Classpath mixte (JAR pre-processé + JAR brut `@Path`) → les deux classes
   découvertes avec leur scope
5. BCE lecture seule → pas d'entrée dans `vauban-bce-runtime.list`

### Fichiers impactés (livré)

- `vauban-processor/src/main/java/fr/vidocq/vauban/processor/VaubanProcessor.java` :
  écrit `META-INF/vauban-bce-runtime.list` (format `<bce-fqn>;<target-fqn>`,
  trié, filtré par `VaubanClassConfig.isModified()`)
- `vauban-core/src/main/java/fr/vidocq/vauban/core/extensions/VaubanClassConfig.java` :
  champ `sourceBce` + getter/setter pour tracer la BCE qui a créé la config
- `vauban-core/src/main/java/fr/vidocq/vauban/core/extensions/BceProcessor.java` :
  - nouvelle méthode `replayEnhancementForTargets(List<Map.Entry<bce, target>>, VaubanIndex)`
    qui invoque `@Enhancement` directement sur les couples ciblés sans matching
  - **fix bug latent** : `applyClassConfigs` filtre désormais les annotations
    de scope (ex. `@RequestScoped`) hors de la liste `bean.qualifiers()` —
    elles n'évincent plus `@Default`. C'est ce fix qui débloque le cas
    vidocq-rest-example (JAR sans marker `vauban-bce-processed`)
- `vauban-core/src/main/java/fr/vidocq/vauban/core/container/VaubanContainerBuilder.java` :
  - `loadRuntimeReplayList(ClassLoader)` lit `META-INF/vauban-bce-runtime.list`
    sur tout le classpath et résout les couples (bce, target) via `Class.forName`
  - architecture cohabitation : `combinedEnhMods` fusionne BCE full sur les
    JARs bruts + replay ciblé sur les JARs pre-processés
  - les BCEs disponibles pour le full scan incluent celles de la runtime-list
    (cas où la BCE est dans un JAR pré-processé mais doit aussi enrichir un
    JAR brut)
  - le bloc `applyEnhancements` est sorti du `if (!bceClasses.isEmpty())`
    pour traiter aussi les modifications du replay quand `allSourcesProcessed=true`

### Tests livrés

- `vauban-processor/.../BceRuntimeListCompileTimeTest.java` : 3 tests
  - écriture `<bce>;<target>` quand BCE ajoute annotation
  - tolérance des commentaires `#`
  - BCE read-only n'écrit rien
- `vauban-core/.../BceRuntimeReplayTest.java` : 3 tests
  - replay applique `@RequestScoped` au bean
  - `getBeans(Object.class, @Any)` voit le bean enrichi (symptôme exact bug #7)
  - classpath mixte (JAR pre-processé + JAR brut)

### Vérification end-to-end

`vidocq-rest-example` (utilise `vidocq-maven-plugin:generate`, donc tombe dans
le cas "JAR brut" — fallback BCE full au runtime) :
- `/hello-simple-jaxrs` (BCE `@RequestScoped` local) → HTTP 200 ✓
- `/external` (BCE cross-JAR sur lib externe) → HTTP 200 ✓
- 4/4 endpoints opérationnels

**Status :** FIXE dans Vauban 0.1.0-SNAPSHOT.

---

## 5. ~~`BeanManager.getInjectionTargetFactory(AnnotatedType)` — Not yet implemented~~ FIXÉ

**Symptome initial :** `java.lang.IllegalStateException: Not yet implemented`
levé depuis `io.vidocq.vauban.core.container.VaubanBeanManager.getInjectionTargetFactory`.

**Contexte :** Cassini instancie les classes `@Path` sans scope CDI explicite
(§3.1.1 JAX-RS : ressources par défaut per-request) via l'API standard
`bm.getInjectionTargetFactory(bm.createAnnotatedType(type))`.

**Status :** fixé côté Vauban. Cassini utilise désormais
`InjectionTargetFactory.createInjectionTarget(null)` + `produce/inject/postConstruct`
dans `Invoker.instantiateWithInjection`.
