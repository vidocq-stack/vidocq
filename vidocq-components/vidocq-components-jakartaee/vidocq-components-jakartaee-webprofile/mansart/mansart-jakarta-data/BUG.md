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
      - **M7-8** *(2026-05-05)* — Comparators ajoutés : `True`/`False`,
        `Contains`/`StartsWith`/`EndsWith` (LIKE wildcard auto), `Empty`/`NotEmpty`,
        suffixe `IgnoreCase` (sans casing SQL pour l'instant), infixe
        `<Attr>Not<Comparator>`, méthodes interface `default`. Top-level
        `countAll`/`deleteAll`/`removeAll`. `findFirst<N>By...` (limite numérique
        inline appliquée par slicing post-query). `IgnoreCase` mid-name accepté.
        `<Attr>NotNull` détecté comme IS_NOT_NULL. Dispatcher `@Find` qui mappe
        chaque paramètre au nom d'attribut correspondant. **Score TCK** : 73 →
        2 PASS / 26 FAIL / 45 ERR (`UnsupportedOperationException` à zéro).
        Légère régression de tests passants (4 → 2) — les nouveaux comparators
        et IgnoreCase mid-name peuvent produire des prédicats sémantiquement
        incorrects pour certains cas (ex. IgnoreCase sur colonne non-String).
      - **M7-11** *(LIVRÉ 2026-05-05)* — `H2Dialect.extract` / `PostgresqlDialect.extract`
        gèrent `javaType.isEnum()` via `getString` + `Enum.valueOf` ; H2 ne sait
        pas convertir VARCHAR vers une classe enum via `getObject(idx, enumClass)`.
        Réduit `JdbcSQLFeatureNotSupportedException` (30 → 0). **Score TCK** :
        73 → 21 PASS / 14 FAIL / 38 ERR.
      - **M7-13** *(LIVRÉ 2026-05-05)* — Routes `Page<E>` / `CursoredPage<E>` dans
        le dispatcher `@Query` (JdqlExecutor) ET `@Find` (findAnnotationDispatcher) :
        détecte un `PageRequest` dans les args, route vers `queryPage` / `queryCursored`.
        Tableaux `E[]` reconstitués dans le bon component-type. `@Find` honore
        désormais aussi `Sort/Order/Limit/PageRequest` (via `applyControlOrder` +
        `controlLimitRange` partagés). **Score TCK** : 73 → 54 PASS / 7 FAIL / 12 ERR.
      - **M7-12** *(LIVRÉ 2026-05-05)* — Dispatcher dérivé honore
        `jakarta.data.Limit` (avec offset via `startAt`),
        `jakarta.data.Sort<E>`, `jakarta.data.Sort[]` (varargs),
        `jakarta.data.Order<E>` et `jakarta.data.page.PageRequest`. Pour les
        retours `Page<E>`, route vers `queryPage`; pour `CursoredPage<E>`, vers
        `queryCursored`. **Score TCK** : 73 → 51 PASS / 8 FAIL / 14 ERR.
      - **M7-10** *(LIVRÉ 2026-05-05)* — `VaubanTestEnricher.invokeBeforeEachMethods`
        appelle manuellement les méthodes annotées `@org.junit.jupiter.api.BeforeEach`
        après l'enrichissement (parent → enfant). Bonus : `lifecycleDispatcher`
        détecte les méthodes `@Save`/`@Insert`/`@Update`/`@Delete` à signature
        batch (`List<E>`/`Iterable<E>`/`E[]`) et itère au lieu de tenter de
        sauver le carrier comme une seule entité (sinon `ReadOnlyRepository.saveAll`
        envoie une `ArrayList` à `readId`). **Score TCK : 73 → 14 PASS / 6 FAIL /
        53 ERR** (le populator s'exécute, les tables sont peuplées, plus de
        308 `TableOrViewNotFound` côté DB).
      - **M7-10 historique** *(découvert 2026-05-05, à fixer)* — Le harness Arquillian Junit5
        en mode standalone (`StandaloneExtension extends ArquillianExtension`)
        ne déclenche **PAS** les méthodes `@BeforeEach` du test. Conséquence :
        `EntityTests.setup()` qui appelle `NaturalNumbersPopulator.populate(numbers)`
        et `AsciiCharactersPopulator.populate(characters)` ne s'exécute jamais →
        toutes les tables read-only restent vides → ~50 tests qui interrogent
        ces données échouent avec `expected: <[…]> but was: <[]>`.
        Vérifié indirectement : sur 73 tests le pattern `mansart-saveAll` ne
        se déclenche que sur l'entité `Box` (les tests qui créent eux-mêmes
        leurs données). Les tests TestNG (`@BeforeMethod` dans
        `MansartArquillianSmokeTest`) fonctionnent correctement, donc le bug
        est strictement côté Arquillian Junit5. Stratégie envisagée :
        - patcher `VaubanTestEnricher` pour invoquer manuellement les méthodes
          `@BeforeEach` après l'enrichissement, OU
        - écrire un wrapper `InvocationInterceptor` qui appelle
          `invocation.proceed()` proprement et laisse JUnit faire son cycle.
      - **M7-9** *(2026-05-05)* — `nameToIndexFor` lit `@jakarta.data.repository.Param`
        sur les paramètres en plus de `Parameter.getName()` ; le binding `:name`
        fonctionne donc même si `-parameters` n'est pas honoré (cas du jar TCK
        précompilé). Bonus : `H2Dialect.bind` / `PostgresqlDialect.bind` coercent
        les enums en `name()` String pour éviter l'erreur H2 « JAVA_OBJECT to
        CHARACTER VARYING ». **Score TCK** : 73 → 2 PASS / 29 FAIL / 42 ERR
        (errors 45 → 42, plusieurs tests passent maintenant en mode "wrong
        result" au lieu de crash SQL).

---
