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

## 6. ClientProxy : override de methode protected avec invokevirtual au lieu de invokespecial

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

**Contournement :** utiliser un scope pseudo (`@jakarta.inject.Singleton` ou `@Dependent`) qui ne genere pas de ClientProxy. L'exemple `vidocq-servlet-example` utilise `@Singleton` partout pour cette raison.

**Fix attendu :** dans le generateur de ClientProxy, emettre `invokespecial` pour les super-calls de methodes overridees (meme semantique que `super.methodName(args)` en source Java), independamment de la visibilite de la methode.

**Status :** OUVERT. Decouvert lors de l'integration de `vidocq-servlet-chappe-extension` (jalon M2a+). Affecte tous les beans normal-scoped qui heritent de classes avec methodes protected.
