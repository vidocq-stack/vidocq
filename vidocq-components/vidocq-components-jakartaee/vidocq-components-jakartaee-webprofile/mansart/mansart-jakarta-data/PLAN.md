# mansart-jakarta-data — Plan détaillé

Implémentation Jakarta Data 1.0 (`jakarta.data:jakarta.data-api:1.0.x`) avec backend JDBC, dialectes pluggables, et génération statique des repositories.

## Périmètre Jakarta Data 1.0 ciblé

| Concept spec | Implémentation Mansart |
| --- | --- |
| `@Repository` | Détecté par APT, génère `XxxRepositoryImpl` |
| `BasicRepository<T, K>` | Méthodes par défaut implémentées dans `XxxRepositoryImpl` |
| `CrudRepository<T, K>` | Idem |
| `DataRepository` (marker) | Détecté |
| `@Find` | Génère un `SELECT … WHERE` à partir du nom des paramètres |
| `@Save`, `@Insert`, `@Update`, `@Delete` | Génère `MERGE`/`INSERT`/`UPDATE`/`DELETE` selon dialecte |
| `@Query` (JDQL) | Parser JDQL → AST commun → SQL dialecte |
| `@OrderBy`, `Sort`, `Order` | Appliqués dans la clause `ORDER BY` générée |
| `@By`, `@Param` | Liaison paramètre → colonne |
| `Limit`, `PageRequest`, `Page`, `CursoredPage` | Pagination offset (M3) puis keyset (M3.5) |
| `Slice` | Variante `PageRequest` sans count |
| Static metamodel (`_Entity`) | Généré par APT |
| Lifecycle annotations (`@Insert`, `@Update`, `@Delete`, `@Save` typés) | Une entité paramètre → SQL exact |
| `EntityExists`, `OptimisticLockingFailureException`, `NonUniqueResultException` | Mappées depuis SQLState JDBC via le dialecte |

**Hors scope v1** : NoSQL, sous-types de `DataRepository` non standard, projections complexes (DTO partiels — viendra en v1.1).

## Sous-modules

```
mansart-jakarta-data/
├── pom.xml                              ← reactor mansart-jakarta-data
├── mansart-data-api/                    ← annotations Mansart + re-export jakarta.data
├── mansart-data-dialect-spi/            ← Dialect, DialectFactory, AST
├── mansart-data-core/                   ← runtime : RepositoryRuntime, RowMapper, ConnectionScope
├── mansart-data-processor/              ← APT : métamodèle (_Book + Book_) + RepositoryImpl
├── mansart-data-cdi/                    ← BCE Vauban/CDI 4.1 (bootstrap mode B)
├── mansart-data-dialect-h2/             ← provides DialectFactory (H2)
├── mansart-data-dialect-postgresql/     ← provides DialectFactory (PostgreSQL)
├── mansart-data-tests/                  ← tests unitaires (H2 in-memory + Testcontainers PG)
└── mansart-data-tck/                    ← HORS reactor — runner TCK Jakarta Data 1.0
```

### Dépendances inter-modules

```
api ────────── jakarta.data-api 1.0.1
                + jakarta.persistence-api 3.2 (compile-only / requires static — pour les annotations JPA optionnelles)
dialect-spi ── api
core ───────── api, dialect-spi
processor ──── api, dialect-spi          (Maven scope provided ; APT actif uniquement chez l'utilisateur)
cdi ────────── core, jakarta.cdi-api 4.1, jakarta.inject-api
dialect-h2 ─── dialect-spi               (driver h2 en provided)
dialect-pg ─── dialect-spi               (driver postgresql en provided)
tests ──────── core, cdi, dialect-h2, processor (test) ; testcontainers (test, optionnel)
tck ────────── core, cdi, dialect-h2     (hors reactor)
```

## Architecture runtime

### Discovery du dialecte

```java
ServiceLoader.load(DialectFactory.class)
    .stream()
    .map(ServiceLoader.Provider::get)
    .filter(f -> f.supports(dataSource))    // sniff JDBC URL ou metadata
    .findFirst()
    .orElseThrow();
```

Le sniffer interroge `Connection.getMetaData().getDatabaseProductName()` une seule fois au bootstrap. Cache global par `DataSource`.

### Cycle de vie d'un appel de repository

```
caller thread (virtual)
   │
   ▼
XxxRepositoryImpl.findByXxx(arg)        ← code généré, statique
   │
   ▼
QueryPlan (immutable, calculé à compile-time, instancié 1×)
   │
   ▼
ConnectionScope.with(dataSource, conn -> {
   PreparedStatement ps = conn.prepareStatement(plan.sql())
   plan.bind(ps, args)
   ResultSet rs = ps.executeQuery()
   return plan.mapper().map(rs)          ← RowMapper généré aussi par APT
})
```

`ConnectionScope` utilise un `ScopedValue<Connection>` pour propager la connexion à l'intérieur d'une transaction sans `ThreadLocal`. Si une `jakarta.transaction.UserTransaction` est active, on s'enrôle dessus ; sinon auto-commit.

### Métamodèle statique

```java
// Source utilisateur
@Entity
public class Book {
    @Id Long id;
    String title;
    @ManyToOne Author author;
    LocalDate publishedOn;
}

// Généré par mansart-data-processor en target/generated-sources/
@Generated("io.vidocq.mansart.data.processor")
public final class _Book {
    public static final EntityModel<Book> $MODEL = …;
    public static final Attribute<Book, Long>      id          = …;
    public static final Attribute<Book, String>    title       = …;
    public static final Attribute<Book, Author>    author      = …;
    public static final Attribute<Book, LocalDate> publishedOn = …;
}
```

Les `Attribute` sont des `record` du SPI dialecte qui exposent `columnName()`, `javaType()`, `getter MethodHandle`, `setter MethodHandle`. **Aucune réflexion runtime** — les MethodHandles sont obtenus à `<clinit>` une fois.

### Génération des repositories

```java
// Source utilisateur
@Repository
public interface BookRepository extends CrudRepository<Book, Long> {
    @Find
    List<Book> findByAuthorAndPublishedOnBetween(Author author, LocalDate from, LocalDate to);

    @Query("FROM Book WHERE title LIKE :pattern ORDER BY publishedOn DESC")
    Page<Book> search(String pattern, PageRequest pageRequest);
}

// Généré par APT
@Generated("io.vidocq.mansart.data.processor")
public final class BookRepositoryImpl implements BookRepository {
    private static final QueryPlan FIND_BY_AUTHOR_AND_PUBLISHED_ON_BETWEEN = QueryPlans.select(
        _Book.$MODEL,
        Where.and(Where.eq(_Book.author), Where.between(_Book.publishedOn)),
        OrderBy.NONE,
        Pagination.NONE);

    private static final QueryPlan SEARCH = QueryPlans.fromJdql(
        "FROM Book WHERE title LIKE :pattern ORDER BY publishedOn DESC",
        _Book.$MODEL);

    private final RepositoryRuntime runtime;

    BookRepositoryImpl(RepositoryRuntime runtime) { this.runtime = runtime; }

    @Override
    public List<Book> findByAuthorAndPublishedOnBetween(Author author, LocalDate from, LocalDate to) {
        return runtime.queryList(FIND_BY_AUTHOR_AND_PUBLISHED_ON_BETWEEN, author, from, to);
    }

    @Override
    public Page<Book> search(String pattern, PageRequest pageRequest) {
        return runtime.queryPage(SEARCH, pageRequest, pattern);
    }
    // … méthodes CrudRepository déléguées à runtime
}
```

L'instanciation est faite par un bootstrap CDI (BCE Vauban) qui fait `Class.forName(itf.getName() + "Impl")` une seule fois, ou directement à la main via `MansartData.repository(BookRepository.class, dataSource)`.

## SPI Dialect

```java
public interface Dialect {
    String name();
    boolean supports(DatabaseMetaData md);

    // Génération SQL
    SqlFragment select(EntityModel<?> model, Where where, OrderBy orderBy, Pagination pagination);
    SqlFragment insert(EntityModel<?> model, boolean returningGeneratedKey);
    SqlFragment update(EntityModel<?> model, Where where);
    SqlFragment delete(EntityModel<?> model, Where where);
    SqlFragment merge(EntityModel<?> model);   // pour @Save (upsert)

    // Pagination
    SqlFragment paginate(SqlFragment base, Pagination p);
    SqlFragment keyset(SqlFragment base, Cursor c);

    // Mapping types Java ↔ SQL
    int sqlType(Class<?> javaType);
    void bind(PreparedStatement ps, int idx, Object value, Class<?> javaType) throws SQLException;
    <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException;

    // Mapping erreurs
    DataException translate(SQLException e);
}

public interface DialectFactory {
    Dialect create();
    boolean supports(DatabaseMetaData md);
}
```

### Différences clés H2 vs PostgreSQL à isoler dans le dialecte

| Aspect | H2 | PostgreSQL |
| --- | --- | --- |
| Identifiants générés | `IDENTITY` + `Statement.RETURN_GENERATED_KEYS` | `RETURNING id` |
| Upsert (`@Save`) | `MERGE INTO … KEY(…) VALUES …` | `INSERT … ON CONFLICT (…) DO UPDATE SET …` |
| Pagination offset | `LIMIT n OFFSET m` | identique |
| Pagination keyset | comparaison tuple `WHERE (col1, col2) > (?, ?)` | identique (syntaxe row constructor) |
| Booleans | `BOOLEAN` natif | `BOOLEAN` natif |
| `UUID` | `UUID` natif | `UUID` natif (driver convertit) |
| `LocalDateTime` | `TIMESTAMP` | `TIMESTAMP WITHOUT TIME ZONE` |
| SQLState unique violation | `23505` | `23505` (commun, peut rester dans une couche partagée) |
| SQLState optimistic lock | n/a (à émuler via `version` colonne) | idem |

## Plan de travail (jalons détaillés)

### M2 — Squelette + APT métamodèle ✅ DONE (2026-05-04)

- [x] `pom.xml` parent `mansart-jakarta-data` + 9 sous-modules (Maven Model 4.1.0, `.mvn/` marker, compiler `4.0.0-beta-4`).
- [x] `module-info.java` pour chacun (JPMS strict, exports minimaux, `provides DialectFactory` pour H2/PG, `uses` côté SPI, `provides Processor` côté APT).
- [x] `mansart-data-api` : 13 annotations Mansart (Entity, Table, Id, GeneratedValue, GenerationType, Column, Version, Transient, Enumerated, EnumType, FetchType, ManyToOne, OneToOne, JoinColumn, Embeddable, Embedded, MansartDataSource).
- [x] `mansart-data-dialect-spi` : `Dialect`, `DialectFactory`, `EntityModel`, `Attribute` (sealed) + 7 sous-types records (`IdAttribute`, `TextAttribute`, `NumericAttribute`, `TemporalAttribute`, `ReferenceAttribute`, `EnumAttribute`, `VersionAttribute`), `Where` (sealed AST), `OrderBy`, `Pagination` (sealed: None/Offset/Keyset), `SqlFragment` + `BindSite`, `SqlNames` (snake_case + pluralisation).
- [x] `mansart-data-processor` : `MansartProcessor` (`SourceVersion.RELEASE_25`, `@SupportedAnnotationTypes` Mansart + JPA), `EntityScanner` (détecte les deux dialectes d'annotations, conflit = erreur compile, dérive `EntityKind`/colonnes/FK), `MansartMetamodelWriter` (génère `_Entity` avec `MethodHandles.privateLookupIn` au `<clinit>`), `JpaMetamodelWriter` (génère `Entity_` standard si `jakarta.persistence-api` détecté).
- [x] Test : `Book` (FK vers `Author`, `@Version`) compilée → `_Book` produite avec `tableName="books"`, colonne FK `author_id`, `published_on` snake_case, attributs typés (`IdAttribute<Book, Long>`, `TextAttribute<Book>`, `ReferenceAttribute<Book, Author>`, `TemporalAttribute<Book, LocalDate>`, `VersionAttribute<Book, Integer>`). MethodHandles vérifiés en lecture/écriture.

**Résultats** : `mvn clean install` BUILD SUCCESS sur 9 modules. `mvn test` 7/7 PASS sur `MetamodelGenerationTest`.

**Décisions techniques implicites** :
- Maven `compiler-plugin 4.0.0-beta-4` (alignée avec le reste du workspace ; `3.13.0`/`3.14.0` ne lisent pas les class files Java 25 — major 69).
- Marker `.mvn/` à la racine `mansart-jakarta-data/` (requis par Maven 4 pour identifier le reactor root).
- `jakarta.transaction` retiré de `mansart-data-core/module-info.java` pour M2 (sa déclaration `requires static jakarta.cdi` cassait la compilation) — sera réintégré en M3 quand le runtime utilisera `UserTransaction`.

### M3a — H2 dialect + BasicRepository CRUD ✅ DONE (2026-05-04)

- [x] `mansart-data-dialect-h2` : `H2Dialect` complet (select/insert/update/delete/merge, `MERGE INTO ... KEY(...)` pour upsert, `bind`/`extract` pour les types courants — String, Long, BigDecimal, LocalDate, LocalDateTime, OffsetDateTime, Instant, UUID, enum).
- [x] `mansart-data-core` : `MansartData` (bootstrap standalone), `RepositoryRuntime` (save/findById/findAll/deleteById/delete/count/existsById), `ConnectionScope` (`ScopedValue<Connection>`, propagation via `where(...).call(...)` — pas de `ThreadLocal`), `RowMapper` (utilise `MethodHandle` du métamodèle), `DialectResolver` (ServiceLoader sur `DatabaseMetaData`).
- [x] `mansart-data-processor` : `RepositoryWriter` détecte les interfaces `@jakarta.data.repository.Repository`, remonte la chaîne d'interfaces pour trouver `BasicRepository<T,K>`/`CrudRepository<T,K>`/`DataRepository<T,K>`, génère `*RepositoryImpl` avec `Types.asMemberOf` pour résoudre `T`/`K`, signatures émises avec leurs propres type-vars `<S extends T>` quand utiles.
- [x] `mansart-data-tests` : `AuthorRepository extends BasicRepository<Author, Long>` + `count()` + `existsById(Long)` ; 8 tests d'intégration H2 in-memory (save → generated id, findById, findAll → Stream, count, existsById, deleteById, delete(entity), upsert via MERGE).

**Résultats** : `mvn test` → **15/15 PASS** (8 `CrudIntegrationTest` + 7 `MetamodelGenerationTest`).

**Pièges contournés en M3a** :
- `findAll()` retourne `Stream<T>` dans `BasicRepository` (pas `List<T>`) — body généré convertit avec `.stream()`.
- `<S extends T> S save(S e)` exige d'émettre les type-vars de méthode dans la signature ET d'unchecked-cast en sortie (le runtime est typé sur `T`, pas `S`).
- `count()`/`existsById()` ne sont pas dans `BasicRepository` 1.0 — l'utilisateur les déclare sur son interface, le processeur les reconnaît par nom.
- H2 2.x rejette `BIGINT IDENTITY` brut — il faut `BIGINT GENERATED BY DEFAULT AS IDENTITY` (SQL standard) ou `AUTO_INCREMENT`.
- `mansart-data-core` doit déclarer `uses io.vidocq.mansart.data.dialect.DialectFactory` dans son `module-info.java` (sinon `ServiceLoader.load` ne voit pas les providers JPMS).
- Fichiers `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory` ajoutés dans les dialectes pour fallback classpath (les tests Surefire sans `module-info` voient les providers via cette voie).

### M3b — Pagination keyset, lifecycle, derived queries (à venir)

- [ ] `CursoredPage` + `PageRequest.afterCursor()`.
- [ ] `@Insert`, `@Update`, `@Delete`, `@Save` typés (un paramètre = entité).
- [ ] Optimistic locking via colonne `@Version`.
- [ ] Dérivation par nom de méthode (`findByX`, `findByXAndY`, `findByXBetween`, `findByXIn`, `countByX`, `existsByX`, `deleteByX`).
- [ ] Tests de concurrence avec virtual threads (1000 lectures parallèles, vérifier 0 pinning via `-Djdk.tracePinnedThreads=full`).
- [ ] `BENCH.md` initial : bench JMH d'un `findById` répété (cible : < 5µs hors I/O JDBC).

### M3.5 — Pagination keyset + lifecycle (semaine 5)

- [ ] `CursoredPage` + `PageRequest.afterCursor()` côté core et dialecte H2.
- [ ] `@Insert`, `@Update`, `@Delete`, `@Save` typés (un paramètre = entité).
- [ ] Optimistic locking via colonne `@Version`.
- [ ] Tests de concurrence avec virtual threads (1000 lectures parallèles, vérifier 0 pinning).

### M4 — Dialecte PostgreSQL (semaine 6-7)

- [ ] `mansart-data-dialect-postgresql` : portage du dialecte H2 + spécificités (`RETURNING`, `ON CONFLICT`).
- [ ] Tests Testcontainers (`postgres:17-alpine`).
- [ ] Vérification que la suite `mansart-data-tests` passe à 100% sur les deux dialectes (paramétrer la suite par dialecte).

### M5 — `@Query` JDQL (semaine 8-9)

- [ ] Parser JDQL (sous-ensemble de JPQL défini par Jakarta Data 1.0). Implémentation à la main (pas d'ANTLR), descente récursive.
- [ ] Lowering JDQL → AST `mansart-data-dialect-spi` → SQL dialecte.
- [ ] Cache des plans par signature de méthode (`Map<Method, QueryPlan>` initialisé au `<clinit>` de `XxxRepositoryImpl`).

### M6 — TCK Jakarta Data 1.0 ✅ DONE (2026-05-05)

- [x] **M6** structure : `mansart-data-tck` hors reactor (POM Model 4.0.0 standalone), script `run-official-tck-data-1.0.sh`, README, smoke harness.
- [x] **M6.1** : BCE Mansart (`mansart-data-cdi/MansartDataExtension`) + `MansartRuntimeProducer` + `MansartRepoCreator` ; `mansart-data-processor` génère `META-INF/mansart-repositories.list` ; smoke 3/3 PASS via `VaubanContainer.builder().addBeanClass(...)`.
- [x] **M6.2** : connecteur Vauban Arquillian porté (`io.vidocq.vauban.tck.*`) + `MansartArquillianSmokeTest` : `@Deployment` ShrinkWrap, `@Inject` AuthorRepository/DataSource/RepositoryRuntime, 2/2 PASS.
- [x] **M6.3** : `jakarta.data:jakarta.data-tck:1.0.1` résolu depuis Maven Central (artifactId avec un `.`, pas un `-`) ; profil `-Ptck-run` complet (JUnit 5, Arquillian Junit 5, jakarta.servlet-api, ant) ; surefire `dependenciesToScan` ; 73 EntityTests **discovered et tentés** sur la suite officielle.
- [x] **M6.4** : analyse honnête — 73 erreurs EntityTests, gap d'architecture documenté dans `BUG.md` (BUG-20260505-01). Le harness Arquillian + `TCKLoadableExtension` + `TCKArchiveProcessor` fonctionne (entités TCK déployées). Le bloqueur identifié = absence de **génération runtime** des `*RepositoryImpl` et des métamodèles `_<Entity>` pour les entités TCK pré-compilées.
- [x] **M6.5** ✅ DONE (2026-05-05) — variante PostgreSQL via Testcontainers (`postgres:17-alpine`). Profile Maven `tck-pg` cumulable avec `tck-run`. `MansartTckArchiveAppender` switch H2 ↔ PG via system property `mansart.tck.dialect`. Script `run-official-tck-data-1.0.sh --pg`. **Résultat : 73/73 EntityTests PASS sur PG** dès la première run réussie — aucun fix dialect-spécifique nécessaire.

### M7 — Runtime impl generation ✅ DONE (2026-05-05) — TCK 73/73 PASS

Architecture livrée :

```
@Repository interface (TCK ou user code, sans APT)
   → BCE (MansartDataExtension) — découvre l'interface
   → @Synthesis — enregistre un bean synthétique @Singleton
   → MansartRuntimeRepoCreator.create() — appelé par Vauban à l'injection
   → RuntimeRepositoryProxy.create() — bâtit Dispatcher[] + EntityModel[]
   → RuntimeRepositoryClassGenerator.generate() — Class-File API (JEP 484)
   → hidden class <Repo>$$MansartImpl/0xNNNN
   → MansartCallback.dispatch(idx, args)
   → Dispatcher (jdql / find / lifecycle / inherited / derived) → RepositoryRuntime
   → Dialect (H2 / PostgreSQL) → JDBC → DataSource
```

Sous-jalons livrés :

- [x] **M7-1** : RuntimeEntityModelBuilder — construit `EntityModel<T>` à partir des annotations Mansart **et** JPA (`@Entity`/`@Table`/`@Id`/`@GeneratedValue`/`@Column`/`@Version`/`@ManyToOne`/`@JoinColumn`/`@Enumerated`/`@Embedded`) sur la classe d'entité TCK pré-compilée. `MethodHandles.privateLookupIn` (pas de `setAccessible`).
- [x] **M7-2** : QueryMethodParser — parser dérivation par nom de méthode (`findBy*`, `existsBy*`, `countBy*`, `deleteBy*`, opérateurs `Equals`, `Between`, `In`, `LessThan`, `GreaterThan`, `GreaterThanEqual`, `LessThanEqual`, `Like`, `IgnoreCase`, `Not`, `True`, `False`, `Null`, `NotNull`, `Empty`, `NotEmpty`, `Contains`, `StartsWith`, `EndsWith`, `OrderBy<Attr>Asc/Desc`).
- [x] **M7-3** : RuntimeRepositoryProxy première version — construction de `Dispatcher[]` typé par méthode (jdql / find / lifecycle / inherited / derived).
- [x] **M7-4** : BCE `@Discovery`/`@Enhancement`/`@Synthesis` — découvre toutes les interfaces `@Repository` du déploiement TCK + bean synthétique `@Singleton` typé sur l'interface.
- [x] **M7-5 → M7-7** : EmptyResultException / NonUniqueResultException, `Optional<T>`, `Stream<T>`, `Page<T>`, `CursoredPage<T>`, `PageRequest`, `Limit`, `Sort`, `Order` au runtime.
- [x] **M7-8** : Implicit ID — résolution conventionnelle (champ `id`, ou unique champ de type `K`, ou `<entity>Id`/Identifier/Key) quand l'entité n'a pas de `@Id` JPA explicite.
- [x] **M7-9** : Lifecycle annotations runtime (`@Insert`, `@Update`, `@Delete`, `@Save`) — entité unique, collection, varargs ; retour `void` / `T` / `Iterable<T>` / `int` / `long` / `boolean`.
- [x] **M7-10 → M7-15** : JDQL parser — literals (numériques avec suffixes `l/L/f/F/d/D`, strings, booléens, enum FQN), arithmétique (`+ - * /`), `FROM` optionnel, infix `NOT`, `IN (…)`, `BETWEEN`, `LIKE`, `IS [NOT] NULL`, `IS [NOT] EMPTY`, `UPPER`/`LOWER`, position parameters (`?1`).
- [x] **M7-16 → M7-20** : JDQL exécuteur — `SELECT` projeté, agrégats (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), `GROUP BY`, `HAVING`, `ORDER BY`, `UPDATE` set, `DELETE`, mapping retours (Long → boolean si la méthode l'attend).
- [x] **M7-21** : runtime `Sort` args appliqués (réparation `appendRuntimeSorts`).
- [x] **M7-22** : suffixes Java numériques (`0.0d`, `0.0f`, `42L`).
- [x] **M7-23** : multi-entités — par-méthode `EntityModel` (param/return/JDQL `FROM`/`UPDATE`).
- [x] **M7-24** : arithmétique `SET` (AST `Expr/ExprBin`), wrap `Long → Boolean` selon type retour.
- [x] **M7-25** : remplacement de `java.lang.reflect.Proxy` par classe hidden générée via **Class-File API** (`java.lang.classfile`, JEP 484). `MansartCallback` (dispatch holder) + `RuntimeRepositoryClassGenerator` (≈220 lignes, zéro-dep). Loaded via `MethodHandles.Lookup#defineHiddenClass(bytes, true)`. **Aucun proxy dynamique ; AOT-friendly (GraalVM/Leyden compatible)**.

**Résultats finaux** :
- **73/73 TCK Jakarta Data 1.0 EntityTests** ✅ (100%)
- **79/79 unit tests** ✅
- **6/6 smoke tests** Arquillian (incl. `RuntimeRepoArquillianTest`) ✅

### M7-26 — TCK SignatureTests ✅ DONE (2026-05-05)

- [x] Profile Maven `tck-sig` (cumulable avec `tck-run` et `tck-pg`).
- [x] `jimage.dir` redirigé vers `target/jimage-cache` (writable).
- [x] `argLine` ajoute `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED --add-opens java.base/jdk.internal.vm.annotation=ALL-UNNAMED` (sigtest fait `setAccessible` sur `@Stable`, refusé Java 25 par défaut).
- [x] System property `java.specification.version=21` (TCK 1.0.1 ne ship que `_17` et `_21` ; sur Java 25 le lookup `jakarta.data.sig_25` est `null` → NPE). Jakarta Data 1.0 figé sous Java 21, comparaison reste correcte.
- [x] Script `run-official-tck-data-1.0.sh --sig` et `--full` (cumul EntityTests + SignatureTests, switch `PG=1` pour PostgreSQL).

**Résultat** : **74/74 PASS** (73 EntityTests + 1 SignatureTests) sur H2 ✅ ET sur PostgreSQL ✅.

### M7-27 — JDQL `IgnoreCase` via SQL `LOWER()` ✅ DONE (2026-05-05)

- [x] Ajout du wrapper `Where.IgnoreCase(Where inner)` au SPI dialect (record sealed).
- [x] `H2Dialect` et `PostgresqlDialect` rendent `LOWER(col) <op> LOWER(?)` pour `Eq`, `NotEq`, `Lt`, `Lte`, `Gt`, `Gte`, `Like`, `Between`, `In` et `Not(...)` imbriqué (negation transparente).
- [x] `WhereBinder.bind` étendu avec une branche `Where.IgnoreCase` qui descend dans `inner` (le bind reste sur les valeurs originales — c'est SQL qui case-fold).
- [x] `RuntimeRepositoryProxy.buildWhere` remplace l'ancien hack `String.toLowerCase(ROOT)` côté arg par un wrap propre `Where.IgnoreCase(...)`. L'ordre est : comparator → IgnoreCase → Not → AND/OR.
- [x] Test interne `IgnoreCaseTest` (5 tests) : seed mixed-case (`Victor Hugo` / `VICTOR HUGO` / `victor hugo`) que l'ancien hack ne pouvait PAS matcher (lowercase côté Java seulement). Couvre Eq, Like, Not, Count, Delete sur `IgnoreCase`.
- [x] Régression : 84/84 tests internes ✅, 73/73 EntityTests TCK H2 ✅, 73/73 EntityTests TCK PG ✅, 1/1 SignatureTests ✅. Le TCK exerce `findByHexadecimalIgnoreCaseBetweenAndHexadecimalNotIn` (IgnoreCase sur `Between`) et `findByHexadecimalIgnoreCase` (sur `Eq`).

**Bénéfices vs ancien comportement** :
- Database collation/locale-aware (ICU sur PG, Unicode sur H2) au lieu de `String.toLowerCase(ROOT)`.
- Index fonctionnels `CREATE INDEX ON … (LOWER(col))` exploitables.
- Correct sur datasets mixed-case (l'ancien hack n'aurait jamais matché `"Victor Hugo"` avec arg `"victor hugo"`).

### M7-28 — Compile-time RepositoryWriter : parité avec runtime ✅ DONE (2026-05-05)

Le compile-time `RepositoryWriter` ne supportait correctement que `EQ`/`NOT_EQ`/`LT`/`LTE`/`GT`/`GTE`/`LIKE`/`BETWEEN`/`IN`/`IS_NULL`/`IS_NOT_NULL`. Pour les comparators ajoutés au runtime (M7-8 et M7-27), il y avait des bugs latents :

- `CONTAINS`/`STARTS_WITH`/`ENDS_WITH` → mappés sur `Where.Like` mais SANS materialisation des `%` autour de l'arg → l'arg était passé brut au PreparedStatement → résultats vides systématiques.
- `TRUE`/`FALSE` → mappés sur `Where.Eq` mais aucun arg `Boolean.TRUE`/`Boolean.FALSE` n'était ajouté à `Object[]` → erreur runtime de comptage de bind.
- `EMPTY`/`NOT_EMPTY` → corrects (pas de bind), mais le mixed-In path n'avait pas la branche.
- `IgnoreCase` flag → ignoré complètement.
- `Not` (`negated`) flag → ignoré complètement.

Implémentation :
- [x] `predicateExpr` : ajoute le wrapping `IgnoreCase(...)` puis `Not(...)` autour du predicate inner.
- [x] Nouveau `renderArgsForPredicates(List<Predicate>)` qui materialise :
  - `CONTAINS` → `"%" + p<i> + "%"`
  - `STARTS_WITH` → `p<i> + "%"`
  - `ENDS_WITH` → `"%" + p<i>`
  - `TRUE` → `Boolean.TRUE`, `FALSE` → `Boolean.FALSE`
  - `IS_NULL`/`IS_NOT_NULL`/`EMPTY`/`NOT_EMPTY` → no arg
  - `BETWEEN` → 2 refs, default → 1 ref
- [x] Nouveau helper `wrapIgnoreCaseAndNot(inner, p, pkg)` partagé par predicateExpr et mixedInBody.
- [x] mixedInBody (cas In(Collection)) propage la même logique pour tous les cas.
- [x] Fixture entité étendue dans `AuthorRepository` (compile-time) : `findByNameContains`, `findByNameStartsWith`, `findByNameEndsWith`, `findByNameIgnoreCase`, `findByNameLikeIgnoreCase`, `findByNameNotIgnoreCase`, `countByNameIgnoreCase`, `deleteByNameIgnoreCase`, `findByNameContainsIgnoreCase`.
- [x] Nouveau test `CompiletimeComparatorsTest` (9 tests) — passe par `MansartData.repository(...)` (compile-time path) avec dataset mixed-case (Victor Hugo / VICTOR HUGO / victor hugo / Émile Zola / Honoré de Balzac).

**Régression** :
- 93/93 tests internes ✅ (84 + 9 nouveaux CompiletimeComparators)
- 74/74 TCK H2 ✅ (73 EntityTests + 1 SignatureTests)
- 74/74 TCK PG ✅

Le compile-time path est désormais en **parité fonctionnelle complète** avec le runtime path pour tous les comparators de Jakarta Data 1.0 standalone.

### M7-29 — Suppression de `mansart-data-api` ✅ DONE (2026-05-05)

`mansart-data-api` exposait 17 annotations (`@Entity`, `@Id`, `@Column`, `@Table`, `@GeneratedValue`, `@Version`, `@Transient`, `@ManyToOne`, `@OneToOne`, `@JoinColumn`, `@Embedded`, `@Embeddable`, `@Enumerated`, `@EnumType`, `@FetchType`, `@GenerationType`, `@MansartDataSource`) — **16 mirrors strict de `jakarta.persistence.*`** + 1 extension propre (`@MansartDataSource`).

**Pourquoi c'était redondant** : `jakarta.persistence-api:3.2.0` est une **API spec** sous l'ombrelle Eclipse Foundation, exactement au même titre que `jakarta.data-api:1.0.1` qu'on tire déjà. La philosophie « zéro-dep externe » de Vidocq porte sur les **implémentations** (Hibernate, EclipseLink, …), pas sur les spec API. Donc miroirer JPA était de la sur-modularisation sans bénéfice réel.

Changements :
- [x] Module `mansart-data-api` **entièrement supprimé** (subproject + 17 annotations + module-info + pom + README + dependencyManagement parent).
- [x] `@MansartDataSource` (la seule annotation propre) déplacée dans `mansart-data-core` (package `io.vidocq.mansart.data.core`).
- [x] `EnumType` (énumération de stockage `STRING`/`ORDINAL`) remplacée par `EnumStorage` local au dialect-spi (garde le SPI dialecte pur JDK).
- [x] `EntityScanner` (compile-time) : retirée la branche `MANSART` du `AnnotationDialect`, supprimée la conflict rule « both Mansart and JPA », ne lit plus que `jakarta.persistence.*`.
- [x] `RuntimeEntityModelBuilder` (runtime) : supprimées toutes les recherches d'annotations Mansart (Id, Version, ManyToOne, OneToOne, GeneratedValue, Transient, Table, Column, JoinColumn).
- [x] `MansartProcessor` : `@SupportedAnnotationTypes` ne déclare plus `io.vidocq.mansart.data.Entity` ; init utilise directement la présence de `jakarta.persistence-api`.
- [x] `MansartMetamodelWriter` : génération `EnumAttribute` utilise `EnumStorage.ORDINAL` au lieu de `io.vidocq.mansart.data.EnumType.ORDINAL`.
- [x] Entités tests (`Author`, `Book`, `Article`) + entité Arquillian (`Widget`) migrées vers `jakarta.persistence.*`.
- [x] `mansart-data-core/pom.xml` : ajout dep directe `jakarta.data-api` (apportée auparavant transitivement via `mansart-data-api`).
- [x] `mansart-data-dialect-spi/pom.xml` + `module-info.java` : `requires transitive jakarta.data` (les exceptions Jakarta + types Page sont sur le SPI).
- [x] `module-info.java` × 3 (dialect-spi, core, processor) nettoyés des `requires io.vidocq.mansart.data.api`.
- [x] `mansart-data-tck/pom.xml` : remplace `mansart-data-api` par `jakarta.persistence-api` (les Widget entities du smoke utilisent JPA).
- [x] Smoke harness Arquillian : `addPackages("io.vidocq.mansart.data.api")` retiré (le package n'existe plus).

**Bénéfices** :
- Suppression d'un module entier (17 classes + module-info + pom + README).
- Plus de duplication conceptuelle entre Mansart et JPA.
- Plus de règle « si les deux jeux d'annotations sont posés → erreur compile » à maintenir.
- Interop totale avec Hibernate, EclipseLink, Spring Data, et le futur `mansart-persistence` (JPA 3.2 standard).
- Static metamodel JPA standard (`Author_.id`, `Author_.name`) utilisable en parallèle du Mansart riche (`_Author.$MODEL`).
- Cohérence : `jakarta.data-api` + `jakarta.persistence-api` côté API, Mansart ne ré-expose que ses extensions propres (`@MansartDataSource`).

**Régression** :
- 93/93 tests internes ✅
- 6/6 smoke Arquillian ✅
- 73/73 + 1/1 = **74/74 TCK H2** ✅
- 73/73 + 1/1 = **74/74 TCK PG** ✅

**Décision verrouillée n°3 révisée** : `~~hybride Mansart/JPA~~` → **JPA-only** (M7-29). Mansart ne ré-expose que `@MansartDataSource`.

### Reste à faire (post-M7-29)
- [ ] **TCK PersistenceTests / NoSQLTests** — autres sub-suites (NoSQL hors scope v1 ; PersistenceTests dépend de `mansart-persistence`).
- [ ] **JDQL feature gaps** : subqueries, joins explicites, agrégations multi-attributs.
- [ ] **mansart-persistence** — Jakarta Persistence 3.2 (JPA classique), encore placeholder.
- [ ] **mansart-pool** — pool JDBC virtual-thread-native, sous-roadmap MP1-MP4 (peer indépendant).
- [ ] **vidocq orchestrator** — SPI extensions style Quarkus, packaging, bootstrap.

## Décisions verrouillées

| # | Décision | Impact |
| --- | --- | --- |
| 1 | **Deux bootstraps** : standalone (`MansartData.builder()`) **et** CDI (BCE Vauban). | Voir §"Bootstrap" ci-dessous. |
| 2 | **Deux métamodèles** générés en parallèle : `_Book` (Mansart riche) **et** `Book_` (JPA static metamodel standard). | Voir §"Métamodèle" ci-dessous. |
| 3 | **Détection entité hybride** : APT scanne `@jakarta.persistence.Entity` **et** `@io.vidocq.mansart.data.Entity`. | Voir §"Annotations Mansart vs JPA" ci-dessous. |
| 4 | **`jakarta.data-api` pinné en `1.0.1`**. | Property `jakarta.data.version` dans le POM parent. |

## Bootstrap (décision 1 — détaillé)

### Mode A — Standalone

```java
MansartData md = MansartData.builder()
    .dataSource(ds)                        // requis
    .dialect(MansartData.AUTO)             // ou .dialect(new H2Dialect())
    .transactionManager(MansartData.AUTO)  // détecte UserTransaction si JTA présent, sinon auto-commit
    .build();

BookRepository books = md.repository(BookRepository.class);
List<Book> recent = books.findByPublishedOnAfter(LocalDate.now().minusYears(1));
md.close();   // pas obligatoire — pas de pool interne, juste un ServiceLoader cache à dropper
```

- `md.repository(itf)` fait `Class.forName(itf.getName() + "Impl")` (chargé une fois, mis en cache dans `MansartData`).
- L'`Impl` généré a un constructeur public `(RepositoryRuntime)` — `MansartData` injecte le runtime partagé.
- Aucune réflexion sur `itf` elle-même : tout a été résolu à `compile`.

### Mode B — CDI Vauban (BCE)

`mansart-data-processor` génère, en plus des `*RepositoryImpl`, un fichier `META-INF/mansart-repositories.list` (une ligne par interface `@Repository` détectée — format : `fqn.OfInterface=fqn.OfImpl`).

Au démarrage CDI, une `BuildCompatibleExtension` (BCE 4.1, fournie par `mansart-data-cdi`) :
1. Lit toutes les ressources `META-INF/mansart-repositories.list` via le ClassLoader.
2. Pour chaque entrée, déclare un bean synthétique `@ApplicationScoped` typé sur l'interface, dont la méthode `create` instancie `Impl` avec le `RepositoryRuntime` (lui-même bean produit à partir d'un `DataSource` injecté).
3. La détection du `DataSource` : par défaut `@Inject DataSource`, surchargeable via `@MansartDataSource("name")` côté repository.

→ **Sous-module ajouté** : `mansart-data-cdi` (dépend de `mansart-data-core` + `jakarta.cdi-api`). Ne nécessite Vauban que pour les tests — la BCE est standard CDI 4.1, donc compatible aussi avec Weld/OpenWebBeans pour le TCK.

```
mansart-jakarta-data/
├── …
├── mansart-data-cdi/          ← AJOUTÉ — bootstrap CDI (BCE + producteurs)
└── mansart-data-tck/
```

## Métamodèle (décision 2 — détaillé)

`mansart-data-processor` génère **systématiquement** `_Book`. Il génère **aussi** `Book_` (JPA standard) **si** `jakarta.persistence-api` est sur le compile-classpath du module utilisateur (détection : `processingEnv.getElementUtils().getTypeElement("jakarta.persistence.metamodel.SingularAttribute") != null`).

### Format `_Book` (Mansart, riche)

```java
@Generated("io.vidocq.mansart.data.processor")
public final class _Book {
    public static final EntityModel<Book> $MODEL = EntityModel.of(
        Book.class, "books",                                  // table
        List.of(/* attributes */),
        /* idAttribute */ /* versionAttribute */);

    public static final IdAttribute<Book, Long>             id          = …;
    public static final TextAttribute<Book>                  title       = …;
    public static final ReferenceAttribute<Book, Author>     author      = …;
    public static final TemporalAttribute<Book, LocalDate>   publishedOn = …;
    public static final VersionAttribute<Book, Integer>      version     = …;

    private _Book() {}
}
```

Sous-types d'`Attribute` (`IdAttribute`, `TextAttribute`, `NumericAttribute`, `TemporalAttribute`, `ReferenceAttribute`, `EnumAttribute`, `VersionAttribute`) → permettent à la SPI dialecte de générer du SQL spécialisé sans `instanceof` en chaîne.

Chaque attribut expose : `columnName()`, `javaType()`, `nullable()`, `unique()`, `MethodHandle getter()`, `MethodHandle setter()`. MethodHandles obtenus via `MethodHandles.privateLookupIn(Book.class, lookup)` au `<clinit>` — **un seul lookup**, jamais de réflexion à l'usage.

### Format `Book_` (JPA standard, interop)

```java
@StaticMetamodel(Book.class)
public abstract class Book_ {
    public static volatile SingularAttribute<Book, Long>      id;
    public static volatile SingularAttribute<Book, String>    title;
    public static volatile SingularAttribute<Book, Author>    author;
    public static volatile SingularAttribute<Book, LocalDate> publishedOn;
    public static volatile SingularAttribute<Book, Integer>   version;
    public static final String ID = "id";
    public static final String TITLE = "title";
    // …
}
```

Initialisé à null (la spec JPA prévoit que le provider JPA setter ces champs au démarrage). Quand `mansart-persistence` arrive (M7), il sera responsable du peuplement. En attendant, `Book_` reste utilisable pour les **noms de colonnes string** (les constantes `ID`, `TITLE`, …) — utiles pour `@Query`.

## Annotations Mansart vs JPA (décision 3 — détaillé)

`mansart-data-api` définit un **set minimal** d'annotations zéro-dep, miroir des annotations JPA correspondantes (mêmes noms simples, package `io.vidocq.mansart.data`) :

| Mansart (zéro-dep) | Équivalent JPA reconnu |
| --- | --- |
| `@io.vidocq.mansart.data.Entity` | `@jakarta.persistence.Entity` |
| `@Id` | `@Id` |
| `@GeneratedValue(strategy)` | idem |
| `@Column(name, nullable, unique, length)` | idem |
| `@Table(name, schema)` | idem |
| `@Version` | idem |
| `@Enumerated(STRING|ORDINAL)` | idem |
| `@Embedded` / `@Embeddable` | idem |
| `@ManyToOne(fetch)` / `@OneToOne(fetch)` | idem (relations propriétaires uniquement en v1) |
| `@JoinColumn(name)` | idem |
| `@Transient` | idem |

**Règle APT** :
1. Si `@jakarta.persistence.Entity` est présent sur la classe → on lit les annotations JPA.
2. Sinon, si `@io.vidocq.mansart.data.Entity` est présent → on lit les annotations Mansart.
3. Si les deux sont présents → erreur de compilation claire (« choisir un seul jeu d'annotations »).

**Hors scope v1** : `@OneToMany`/`@ManyToMany` (collections), `@MappedSuperclass`, héritage (`@Inheritance`), `@Convert`/`AttributeConverter`. Reportés en v1.1 ou délégués à `mansart-persistence` (M7).

## Conventions de nommage SQL (à valider)

Par défaut, sans `@Column`/`@Table` :
- Nom de table = pluriel snake_case du nom de classe (`Book` → `books`, `OrderLine` → `order_lines`). Pluralisation simple : ajout d'`s`, ou `es` après `s/x/z/ch/sh`. Documenté, surchargeable.
- Nom de colonne = snake_case du nom d'attribut (`publishedOn` → `published_on`).
- Clé étrangère = `<attr>_id` (`author` (`@ManyToOne Author`) → colonne `author_id`).

**Question ouverte n°5** : la pluralisation automatique est sympa mais peut surprendre — alternative : nom de classe en snake_case **sans** pluralisation (`book`, `order_line`). À trancher avant M2.

## Mapping erreurs SQLState → Jakarta Data exceptions

| SQLState | Famille | Exception Jakarta Data |
| --- | --- | --- |
| `23505` | unique violation | `EntityExists` |
| `23503` | FK violation | `MappingException` (à raffiner) |
| `23502` | NOT NULL violation | `MappingException` |
| `40001` | serialization failure (PG) | `OptimisticLockingFailureException` |
| `0` rows affected sur `UPDATE … WHERE version = ?` | optimistic lock | `OptimisticLockingFailureException` |
| autre | — | `DataException` (wrapper générique avec `getCause()`) |

Implémenté dans une couche partagée `mansart-data-core/SqlStateMapper` ; le dialecte peut surcharger via `Dialect.translate(SQLException)`.

## Test infrastructure (M3)

`mansart-data-tests` fournit :
- `MansartDataTest` (annotation JUnit 6 meta) — démarre un H2 in-memory unique par classe de test, exécute le DDL généré à partir du métamodèle (`SchemaGenerator.fromEntities(_Book.$MODEL, _Author.$MODEL)`), drop à la fin.
- `@PostgresContainer` — variante Testcontainers, activée via profil Maven `-Ppg-it`.
- Pas de fixtures partagées entre classes de test (parallélisable par défaut via virtual threads).

## Validation à compile-time (par APT)

Erreurs claires émises par `mansart-data-processor` quand :
- `@Find List<Book> findByXyz(...)` mais `xyz` n'est pas un attribut de `Book` → `error: Book has no attribute 'xyz'`.
- Type retour incompatible (`@Find Book findAllByXxx(...)` retourne un seul mais le nom suggère plusieurs).
- `@Query` JDQL syntaxiquement invalide (M5).
- Repository référence une entité sans `@Entity`.
- Attribut sans `@Id` sur une entité.
- Plusieurs `@Id` sans `@IdClass` (composite key reportée en v1.1).

**Objectif** : zéro classe d'exception runtime due à un mauvais usage typable au compile-time.

## Décisions encore ouvertes (avant M2)

— *Aucune. Tout est verrouillé.*

## Décisions historiques (archivées)

1. ~~Bootstrap CDI ou standalone ?~~ → **les deux** (verrouillé — voir §"Bootstrap").
2. ~~Format métamodèle : Mansart-only ou compatible JPA ?~~ → **les deux** (`_Book` + `Book_`, verrouillé — voir §"Métamodèle").
3. ~~Détection entité : annotation JPA ou Mansart ?~~ → **hybride** (verrouillé — voir §"Annotations Mansart vs JPA").
4. ~~Version `jakarta.data-api` ?~~ → **`1.0.1`** (verrouillé — property `jakarta.data.version=1.0.1` dans le POM parent).
5. ~~Pluralisation auto des noms de table ?~~ → **OUI, pluriel snake_case** (`Book` → `books`, `OrderLine` → `order_lines`). Surchargeable via `@Table(name=…)`.
6. ~~Module CDI séparé ou intégré au core ?~~ → **module séparé** `mansart-data-cdi` (verrouillé — 9 modules dans le reactor `mansart-jakarta-data`).
7. ~~Pool de connexions intégré ?~~ → **OUI, sous-module Mansart dédié** mais **PAS** sous `mansart-jakarta-data`. C'est un sous-projet peer (`mansart-pool`) au workspace `mansart/`, optionnel et indépendant. `mansart-jakarta-data` continue à n'accepter qu'un `DataSource` standard — qu'il soit Hikari, Agroal, ou Mansart Pool est invisible côté repository. Voir [`../mansart-pool/PLAN.md`](../mansart-pool/PLAN.md).

2. **Format du métamodèle : Mansart-only ou compatible JPA ?** Proposition : générer **deux** classes — `_Book` (Mansart, attributs richement typés) et `Book_` (JPA static metamodel standard, `SingularAttribute<Book, String> title`) pour interop avec du code existant et avec `mansart-persistence`.

3. **Détection de l'entité : `@jakarta.persistence.Entity` ou annotation Mansart propre ?** Proposition : accepter les deux. APT scanne `@Entity` (JPA, déjà standard) ET `@io.vidocq.mansart.data.Entity` (zéro-dep si l'utilisateur ne veut pas le JAR JPA en compile-only).

4. **Numéro de version `jakarta.data-api`** à pinner — actuellement `1.0.0` final ou `1.0.1` ? À vérifier au démarrage M2.

→ **Demander confirmation à l'utilisateur** sur ces 4 points avant d'attaquer M2.
