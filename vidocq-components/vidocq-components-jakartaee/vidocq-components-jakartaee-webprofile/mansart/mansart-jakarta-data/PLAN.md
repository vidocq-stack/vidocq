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

### M6 — TCK Jakarta Data 1.0

- [x] **M6** structure : `mansart-data-tck` hors reactor (POM Model 4.0.0 standalone), script `run-official-tck-data-1.0.sh`, README, smoke harness.
- [x] **M6.1** : BCE Mansart (`mansart-data-cdi/MansartDataExtension`) + `MansartRuntimeProducer` + `MansartRepoCreator` ; `mansart-data-processor` génère `META-INF/mansart-repositories.list` ; smoke 3/3 PASS via `VaubanContainer.builder().addBeanClass(...)`.
- [x] **M6.2** : connecteur Vauban Arquillian porté (`io.vidocq.vauban.tck.*`) + `MansartArquillianSmokeTest` : `@Deployment` ShrinkWrap, `@Inject` AuthorRepository/DataSource/RepositoryRuntime, 2/2 PASS.
- [x] **M6.3** : `jakarta.data:jakarta.data-tck:1.0.1` résolu depuis Maven Central (artifactId avec un `.`, pas un `-`) ; profil `-Ptck-run` complet (JUnit 5, Arquillian Junit 5, jakarta.servlet-api, ant) ; surefire `dependenciesToScan` ; 73 EntityTests **discovered et tentés** sur la suite officielle.
- [x] **M6.4** : analyse honnête — 73 erreurs EntityTests, gap d'architecture documenté dans `BUG.md` (BUG-20260505-01). Le harness Arquillian + `TCKLoadableExtension` + `TCKArchiveProcessor` fonctionne (entités TCK déployées). Le bloqueur est l'absence de **génération runtime** des `*RepositoryImpl` et des métamodèles `_<Entity>` pour les entités TCK pré-compilées.
- [ ] **M7** : runtime impl generation (Class-File API) — voir bloc M7 ci-dessous. Cible TCK pass déplacée à M7.
- [ ] **M6.5** : variante PostgreSQL via Testcontainers (après M7 pour avoir des tests qui passent à transcrire).

### M7 — Runtime impl generation (Class-File API) — débloque le TCK pass

- [ ] BCE `@Discovery`/`@Enhancement` : scanner les classes scannées pour `@jakarta.data.repository.Repository`, identifier l'entité (via `BasicRepository<T,K>` superinterface).
- [ ] Construire `EntityModel` à runtime via `MethodHandles.privateLookupIn` sur la classe d'entité (lecture des annotations JPA/Mansart) — équivalent runtime de `EntityScanner`.
- [ ] Générer le bytecode du `*RepositoryImpl` via `java.lang.classfile` (équivalent runtime de `RepositoryWriter`) — supporter au minimum les méthodes de `BasicRepository`/`CrudRepository` + dérivation par nom de méthode + `@Query` JDQL.
- [ ] `defineClass` dans un `MethodHandles.Lookup` du package de l'entité (ou d'un module Mansart-créé).
- [ ] Préférence à la génération compile-time si `META-INF/mansart-repositories.list` liste l'interface ; sinon fallback runtime.
- [ ] Re-run TCK : cible 80%+ PASS sur le dialecte H2.

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
