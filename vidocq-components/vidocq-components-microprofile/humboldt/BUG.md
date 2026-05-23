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

### [HBT-2] cassini-cdi-vauban n'active jamais le RequestContext autour d'un dispatch HTTP

- **Date** : 2026-05-24
- **Composant** : cassini-cdi-vauban
- **Statut** : FIXED (cassini-cdi-vauban commit `00:53 2026-05-24`)
- **Affecté** : cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptôme** : toute resource `@RequestScoped` (= toute classe `@Path` après BCE Cassini) throws `ContextNotActiveException: RequestScope is not active` lors de l'invocation d'une méthode resource.
- **Reproduction** : cf. HBT-1 — déclencher BaggageTest avant le workaround.
- **Cause** : `cassini-cdi-vauban` ne contient aucun appel à `VaubanContainer.requestContext().activate()` autour des dispatches HTTP. Les beans `@RequestScoped` ne peuvent donc jamais être instanciés.
- **Correction appliquée** : nouveau `VaubanRequestScopeFilter` (`@Provider @PreMatching @Priority(Integer.MIN_VALUE)`)
  qui implémente à la fois `ContainerRequestFilter` (activate) et `ContainerResponseFilter` (deactivate).
  Auto-injecté côté production via `VaubanBeanProvider.getResourceClasses()` (singleton retourné
  par `getBean()`). Pour les harness de test qui n'utilisent pas `CassiniStackBuilder.beanProvider(...)`
  (cas de `humboldt-tck/CassiniHarness`), le constructor est public — enregistrer manuellement via
  `.provider(new VaubanRequestScopeFilter(container))`.
- **Validation** : TCK Cassini Jakarta REST 4.0 = 2535/2535 PASS (contrat respecté, 0 régression).
  TCK humboldt MP Telemetry 2.1 = 19/43/23 (équivalent au fix workaround précédent, mais maintenant
  l'activation est portée par cassini-cdi-vauban et non plus par un Handler ad-hoc humboldt-tck).

---

### [HBT-1] BCE Cassini @Path → @RequestScoped non appliquée aux beans ajoutés runtime via Vauban addBeanClass

- **Date** : 2026-05-24
- **Composant** : humboldt-tck (interaction Vauban runtime + cassini-cdi-vauban BCE)
- **Statut** : FIXED (humboldt-tck commit `00:25 2026-05-24`)
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
- **Correction appliquée** : Vauban a déjà le mécanisme runtime pour appliquer les BCE
  `@Enhancement` aux classes "unprocessed" (`BceProcessor.processEnhancementOnly` ligne 742
  de `VaubanContainerBuilder`), mais SEULEMENT si la BCE est dans le bean classes set.
  `addBeanClass()` ne scanne pas le ServiceLoader META-INF/services. Fix : ajout d'une
  ligne dans `HumboldtDeployableContainer.deploy()` qui déclare explicitement
  `CassiniScopeExtension.class` via `addBeanClass()`. La BCE devient discoverable et
  applique son `@Enhancement` qui ajoute `@RequestScoped` synthétique aux classes `@Path`
  du WAR.
- **Validation** : `BaggageTest.baggage` PASS (vs FAIL avant). Run TCK passe de 16 → 19 PASS.
- **Suivi** : un fix générique côté Vauban (scanner automatiquement les BCE via ServiceLoader
  dans `build()` même si pas de `scanLocal()`) serait plus propre — chantier Vauban séparé.

---
