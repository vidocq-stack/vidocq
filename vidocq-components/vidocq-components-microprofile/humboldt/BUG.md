# Humboldt — Registre des bugs

> Tout bug reproductible (issue interne, régression, comportement incorrect non encore corrigé) doit être tracé ici. Cf. `CLAUDE.md` racine du workspace pour la convention.

## Format d'une entrée

```
### [HBT-N] Titre court
- **Date** : YYYY-MM-DD
- **Composant** : humboldt-api / humboldt-sdk-trace / …
- **Statut** : OPEN / INVESTIGATING / FIXED / WONTFIX
- **Affecté** : version(s) concernées (ex. 0.1.0-SNAPSHOT)
- **Symptôme** : description de l'erreur observée
- **Reproduction** : étapes minimales pour reproduire
- **Hypothèse de cause** : diagnostic root cause (quand connu)
- **Correction** : lien PR ou fix appliqué
```

---

### [HBT-1] BCE Cassini @Path → @RequestScoped non appliquée aux beans ajoutés runtime via Vauban addBeanClass

- **Date** : 2026-05-24
- **Composant** : humboldt-tck (interaction Vauban runtime + cassini-cdi-vauban BCE)
- **Statut** : OPEN (bloqueur partiel pour ~3-5 tests TCK MP Telemetry 2.1)
- **Affecté** : humboldt-tck 0.1.0-SNAPSHOT, vauban 0.1.0-SNAPSHOT, cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptôme** : `cdi.select(BaggageResource.class)` throws `UnsatisfiedResolutionException:
  No bean found for type: ...BaggageResource`. La classe a `@Path` mais pas de scope
  CDI explicite — la BCE `CassiniScopeExtension.addDefaultScope()` censée ajouter
  `@RequestScoped` n'est PAS exécutée pour les classes enregistrées via
  `VaubanContainerBuilder.addBeanClass()` en runtime. Conséquence : les ressources
  TCK avec `@Inject Baggage/Tracer/Span/...` reçoivent `null` → NPE → HTTP 500.
- **Reproduction** :
  1. `cd humboldt && ./run-official-tck-telemetry-2.1.sh -Dtest=BaggageTest`
  2. Observer dans les logs stderr : `Baggage Resource Exception: NullPointerException
     Cannot invoke "Baggage.getEntryValue" because "this.baggage" is null`
  3. `cdi.select(BaggageResource.class)` (depuis CassiniHarness) confirme
     `UnsatisfiedResolutionException`.
- **Hypothèse de cause** : Vauban applique les BCE `@Enhancement` à compile-time via
  APT processeur. Les classes ajoutées par `addBeanClass(Class)` en runtime sont
  registered dans le BeanManager mais NE passent PAS par la phase
  enhancement → leurs annotations ne sont pas mutées → les classes `@Path` sans
  scope sont ignorées par la bean discovery (Vauban exige un scope CDI explicite
  pour considérer une classe comme bean).
- **Tests impactés (FAIL HTTP 500)** : BaggageTest, baggageBeanChange, et
  probablement tout test qui POST/GET sur une resource TCK avec `@Inject` de type CDI
  (testIntegrationWithJaxRsClient*, testIntegrationWithMpRestClient*).
- **Correction proposée (deux pistes)** :
  - **Vauban** : appliquer les BCE `@Enhancement` aux classes ajoutées par
    `addBeanClass()` runtime (équivalent CDI 4.1 "synthetic classes processing")
  - **HumboldtDeployableContainer** : pré-traiter les classes du WAR — détecter les
    `@Path` sans scope CDI, leur ajouter `@RequestScoped` synthétique avant
    `addBeanClass()` (workaround spécifique TCK runner)
- **Statut M7c.7** : le fix CassiniHarness (resolver `cdi.select(cls)` au lieu de
  `new cls`) est en place et fonctionne pour toute classe Vauban-discoverable. Mais
  la BCE manquante laisse les classes TCK hors discovery → fallback `new` → pas d'injection.

---
