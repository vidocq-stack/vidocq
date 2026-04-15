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
