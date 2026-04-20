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

## 7. Régression : BCE @RequestScoped auto sur @Path non appliquée au runtime BeanManager

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
- `fr.vidocq.vidocq.examples.extlib.ExternalResource` (scope via BCE sur
  librairie externe) → **invisible au runtime**

**Indice :** le marker `META-INF/vauban-bce-processed` (documenté dans le
bug #5 comme "écrit par le VaubanProcessor APT") n'existe **dans aucun**
module du reactor (0 match sur `find`). Laisse supposer que la chaîne APT
→ BCE documentée dans #5 n'est plus exécutée, ou que la v0.1.0-SNAPSHOT
actuelle a régressé sur ce point.

**Workaround :** annoter explicitement les classes `@Path` avec
`@RequestScoped` (ou autre scope CDI normal). L'extension Cassini est
totalement fonctionnelle sous cette contrainte.

**Status :** non confirmé côté Vauban. À investiguer :
1. Le `VaubanProcessor` APT est-il toujours invoqué à la compilation du
   consommateur ?
2. Reçoit-il la liste des BCEs via ServiceLoader sur le classpath au moment
   de la compilation ?
3. Écrit-il effectivement les annotations ajoutées par `clazz.addAnnotation()`
   dans le bytecode généré (vs. seulement le proxy) ?
