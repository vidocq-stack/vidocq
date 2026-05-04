# BUG.md — mansart-jakarta-data

Suivi des bugs reproductibles. Convention : voir `../CLAUDE.md` (workspace root).

Statuts : `OPEN` → `INVESTIGATING` → `FIXED` (commit hash) → `CLOSED`.

---

## BUG-20260505-01 — Jakarta Data 1.0 TCK : 73 erreurs sur EntityTests (entités TCK non métamodélisées)

- **Date** : 2026-05-05
- **Statut** : OPEN — bloqué sur M7 (génération runtime Class-File API)
- **Module touché** : `mansart-data-cdi`, `mansart-data-processor` (gap d'architecture)
- **Symptôme** : `mvn -Ptck-run test` → 73 / 73 EntityTests en erreur. La trace typique :
  ```
  EntityTests.testNot:1681 NullPointer
    Cannot invoke "ee.jakarta.tck.data.framework.read.only.NaturalNumbers.findByXxx(...)"
    because "this.naturalNumbers" is null
  ```
- **Reproduction minimale** :
  ```bash
  cd mansart-jakarta-data && mvn install -DskipTests
  cd mansart-data-tck && mvn -Ptck-run test
  ```
- **Hypothèse de cause (confirmée)** :
  - Le harness Arquillian fonctionne : `TCKLoadableExtension` (du jar TCK) enregistre
    `TCKArchiveProcessor` + `TCKFrameworkAppender` qui ajoutent les paquets TCK
    (`framework/read/only`, etc.) à l'archive ShrinkWrap déployée.
  - Notre `VaubanDeployableContainer` scanne ces classes et les passe à Vauban.
  - **Le BCE Mansart cherche `META-INF/mansart-repositories.list`** — généré par
    `mansart-data-processor` à la **compilation de l'utilisateur**. Le jar TCK est
    pré-compilé : aucune liste, aucun `*RepositoryImpl`, aucun `_<Entity>` métamodèle.
  - Conséquence : `@Inject NaturalNumbers naturalNumbers` reste null car aucun
    bean ne résout l'interface.
- **Investigations** :
  - 2026-05-05 : confirmé via `javap` sur `TCKLoadableExtension` que la pipeline
    Arquillian est correcte. Le gap est strictement "génération à compile-time
    only". Pour passer le TCK il faut soit :
      1. **Génération runtime via Class-File API** : à `@Discovery` du BCE, scanner
         le classpath/deployment pour `@jakarta.data.repository.Repository`,
         construire le métamodèle (`EntityModel`) en lisant les annotations
         `@Entity`/`@Id`/etc. par réflexion (la philosophie Vidocq impose
         `MethodHandles.privateLookupIn` plutôt que `setAccessible`), puis
         générer le bytecode des `*RepositoryImpl` via `java.lang.classfile`
         et les `defineClass` dans un `MethodHandles.Lookup` de chaque package
         d'entité. Étiqueté **M7 — Runtime impl generation**.
      2. Distribuer un APT companion pour le jar TCK qui génère métamodèles +
         impls ; non viable (le jar est immuable côté Mansart).
  - **Décision** : la voie 1 est la bonne. Programmée pour M7, après livraison
    des 3 sous-projets (`mansart-jakarta-data`, `mansart-pool`,
    `mansart-persistence`) en mode "compile-time only".

---
