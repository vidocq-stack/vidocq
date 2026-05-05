# BUG.md — mansart-jakarta-data

Suivi des bugs reproductibles. Convention : voir `../CLAUDE.md` (workspace root).

Statuts : `OPEN` → `INVESTIGATING` → `FIXED` (commit hash) → `CLOSED`.

---

## BUG-20260505-01 — Jakarta Data 1.0 TCK : 73 erreurs sur EntityTests (entités TCK non métamodélisées)

- **Date** : 2026-05-05
- **Statut** : INVESTIGATING — M7-4 réduit le périmètre. Reste M7-5 (entités sans annotations Mansart).
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
- **Investigation 2026-05-05 (M7-4)** :
  - M7-4 livre l'auto-discovery BCE `@Enhancement` → les 5 interfaces TCK
    (`Boxes`, `NaturalNumbers`, `PositiveIntegers`, `CustomRepository`,
    `AsciiCharacters`, `MultipleEntityRepo`) sont bien détectées et enregistrées
    comme beans synthétiques `@Singleton`. Vauban résout les beans, l'enricher
    appelle `MansartRuntimeRepoCreator.create(itf, runtime)`.
  - Validation indépendante via `RuntimeRepoArquillianTest` : un repo runtime-only
    (sans `META-INF/mansart-repositories.list`) est correctement injecté et CRUD
    round-trip OK à travers Vauban + Arquillian.
  - Reste 2 gaps qui causent les 71 NPE résiduels (Score TCK identique : 71 / 73,
    mais les causes sont maintenant côté entity-model, pas côté BCE) :
      a. **Entités sans `@Entity`/`@Id` Mansart** — TCK utilise la convention
         Jakarta Data implicite (pas d'annotation, le champ `id` est l'Id par
         convention, le type Id provient du `BasicRepository<E, K>` générique).
         `RuntimeEntityModelBuilder` crashe avec
         `Entity NaturalNumber has no @Id field`.
      b. **`CustomRepository` sans `BasicRepository<E, K>`** — interface
         Jakarta Data autonome. `resolveEntityClass` ne trouve pas l'entité ;
         il faut l'inférer depuis les paramètres/return-types des méthodes
         lifecycle ou `@Find`/`@Query`.
  - Sous-jalons proposés pour fermer ce bug :
      - **M7-5** : `RuntimeEntityModelBuilder` infère l'`@Id` implicite (champ
         `id`, type pris du paramètre générique `K` du repo). Détecte `@Entity`
         absent → traite la classe comme entité POJO si elle est référencée par
         un repo. Couvre 4 / 5 repos TCK.
      - **M7-6** : inférer le type d'entité depuis les méthodes du repo (return
         type de `findBy*`, paramètre de `@Save`, etc.). Couvre `CustomRepository`
         et `MultipleEntityRepo`.
- **Investigation 2026-05-05 (M7-5 / M7-6 livrés)** :
  - `pickImplicitIdField` détecte 3 conventions Jakarta Data 1.0 (champ nommé
    `id`, champ unique du type `K`, champ `<entitySimple>Id`).
  - `JdqlAst` accepte la clause `FROM` optionnelle, les littéraux numériques /
    chaînes (avec escape `''`) / booléens / FQN d'enum, l'opérateur `NOT` infixe
    devant `LIKE`/`IN`/`BETWEEN`. `JdqlExecutor` résout les FQN d'enum via
    `Class.forName` + `Enum.valueOf` (avec fallback `.` → `$` pour les enums
    inner-class).
  - `RuntimeRepositoryProxy.inferFromMethods` couvre les repos qui n'étendent
    pas `BasicRepository<E,K>` (ex. `CustomRepository`).
  - **Score TCK** : 71 / 73 NPE encore, mais la nature des erreurs a basculé
    (6 / 6 repos TCK construisent maintenant — y compris `Boxes` qui utilise
    `String boxIdentifier` comme id implicite). Les nouveaux blockers sont :
      - **M7-7** *(LIVRÉ 2026-05-05)* — `RepositoryRuntime.ensureTable(EntityModel)`
        émet `CREATE TABLE IF NOT EXISTS` portable (H2 + PostgreSQL) avec mapping
        Java → JDBC type. Appelé par `RuntimeRepositoryProxy.create` pour les repos
        runtime-only. Réduit `TableOrViewNotFound` de 376 → 80, débloque 4 tests
        TCK (73 → 4 PASS / 18 FAIL / 51 ERR).
      - **M7-8** — Méthodes dérivées manquantes : `countAll`, `find`,
        `IgnoreCase`, `Contains`, `True`/`False`, méthodes `default`
        (≈ 20 `UnsupportedOperationException`).
      - **M7-9** — `:name` dans `@Query` exige `-parameters` ; `maven-compiler-plugin
        4.0.0-beta-4` ne l'honore pas systématiquement (≈ 48
        `MansartDataException: @Query references :name…`).

---
