# mansart-jakarta-data

**Jakarta Data 1.0 implementation** for the Vidocq ecosystem — JDBC, strict Java Modules, zero dependency outside Jakarta specs, static APT generation and runtime bytecode via Class-File API (JEP 484). No unbounded runtime reflection. No dynamic proxy. AOT-friendly (GraalVM, Leyden).

```
            ┌────────────────────────────────────────────┐
            │   @Repository interface (your code)        │
            └────────────┬───────────────────────────────┘
                         │
       ┌─────────────────┴──────────────────┐
       │                                    │
   compile-time                         runtime
   (mansart-data-processor)             (mansart-data-cdi + core)
       │                                    │
   <Repo>Impl.java                      hidden class via java.lang.classfile
       │                                    │
       └─────────────┬──────────────────────┘
                     ▼
              RepositoryRuntime
                     ▼
                  Dialect (H2 / PostgreSQL)
                     ▼
                JDBC DataSource
```

## Status

- **Jakarta Data 1.0 TCK: 74/74 PASS** on **H2** ✅ and **74/74 PASS** on **PostgreSQL 17** ✅ (73 EntityTests + 1 SignatureTests, Testcontainers).
- **Internal tests: 93/93 unit + 6/6 smoke Arquillian** — see `mansart-data-tests/`.
- Delivered dialects: `H2`, `PostgreSQL`.
- Bootstrap mode **standalone** (`MansartData.builder()`) or **CDI 4.1 Lite** (BCE — Vauban / Weld / OpenWebBeans).

See `PLAN.md` for complete roadmap and `BUG.md` for open/closed bugs.

## Prerequisites

- **Java 25** (Temurin)
- **Maven 3.9.16**
- (Optional) `sdkman`: `cd mansart-jakarta-data && sdk env`

> The parent `pom.xml` uses Maven Model 4.1.0 — Maven 3.x cannot read it.

## Installation

```xml
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- At least one dialect -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-dialect-h2</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- JDBC driver: provided by application (provided scope dialect-side) -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.3.232</version>
</dependency>

<!-- Optional: compile-time generation of *RepositoryImpl + metamodel -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-processor</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>

<!-- Optional: CDI 4.1 bootstrap -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-cdi</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quickstart

### 1. An entity

Standard `jakarta.persistence.*` annotations (Jakarta Persistence 3.2) — it's a spec API, not an implementation, so compatible with the zero-dep philosophy.

```java
package shop;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Column;

@Entity
public class Author {
    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    public Long   getId()                  { return id; }
    public void   setId(Long id)           { this.id = id; }
    public String getName()                { return name; }
    public void   setName(String n)        { this.name = n; }
}
```

### 2. A repository

```java
package shop;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;

import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuthorRepository extends BasicRepository<Author, Long> {

    long count();
    boolean existsById(Long id);

    // Derived query
    List<Author> findByName(String name);
    List<Author> findByNameLike(String pattern);
    Optional<Author> findOneByName(String name);
    long deleteByName(String name);
    List<Author> findAllByOrderByNameAsc();

    // Pagination + cursor
    Page<Author> findByNameLikeOrderByNameAsc(String pattern, PageRequest page);

    // JDQL
    @Query("FROM Author WHERE name LIKE :pattern AND id > :minId")
    List<Author> search(String pattern, Long minId);

    @Query("UPDATE Author SET name = :newName WHERE name = :oldName")
    long rename(String oldName, String newName);
}
```

### 3. Standalone bootstrap

```java
import io.vidocq.mansart.data.core.MansartData;
import org.h2.jdbcx.JdbcDataSource;

JdbcDataSource ds = new JdbcDataSource();
ds.setURL("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
ds.setUser("sa");

MansartData md = MansartData.builder()
        .dataSource(ds)             // required
        // .dialect(new H2Dialect()) // optional, otherwise auto-detection
        .build();

AuthorRepository repo = md.repository(AuthorRepository.class);

Author a = new Author(); a.setName("Victor Hugo");
repo.save(a);                                  // SQL INSERT + GENERATED KEY → a.id
repo.findById(a.getId()).ifPresent(System.out::println);
List<Author> hits = repo.findByNameLike("Vic%");
```

`md.repository(itf)` first looks for the `<itf>Impl` class generated by APT. If APT hasn't run (e.g. interfaces compiled in a third-party jar like the TCK), use `md.runtimeRepository(itf)` which goes through **runtime bytecode generation via Class-File API**.

### 4. CDI 4.1 bootstrap

Add `mansart-data-cdi` to classpath. The module provides:

- `MansartDataExtension` (`BuildCompatibleExtension`) — discovers all `@jakarta.data.repository.Repository` interfaces in the deployment, registers a synthetic `@Singleton` bean typed on the interface.
- `MansartRuntimeProducer` — produces a `@Singleton` `RepositoryRuntime` from a `DataSource` injected by the application.

```java
@ApplicationScoped
public class DataSourceProducer {
    @Produces @Singleton
    public DataSource ds() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:demo");
        ds.setUser("sa");
        return ds;
    }
}

@ApplicationScoped
public class AuthorService {
    @Inject AuthorRepository authors;

    public Author create(String name) {
        Author a = new Author(); a.setName(name);
        return authors.save(a);
    }
}
```

The BCE is standard CDI 4.1 Lite — compatible with Vauban (Vidocq), Weld embedded, OpenWebBeans.

## Entity annotations

Mansart uses standard **`jakarta.persistence.*`** annotations (Jakarta Persistence 3.2). It's a **spec API**, not an implementation — Vidocq's "zero external dep" commitment applies to implementations, and the Jakarta spec is the official standard to align with.

| Annotation used | Role |
| --- | --- |
| `@jakarta.persistence.Entity` | Marks a persistent class. |
| `@jakarta.persistence.Table(name, schema)` | Overrides plural snake_case convention. |
| `@jakarta.persistence.Id` | Identifier. |
| `@jakarta.persistence.GeneratedValue(strategy)` | `AUTO`, `IDENTITY`, `SEQUENCE`. |
| `@jakarta.persistence.Column(name, nullable, unique, length)` | Overrides column name. |
| `@jakarta.persistence.Version` | Optimistic locking. |
| `@jakarta.persistence.Enumerated(STRING\|ORDINAL)` | Enum persistence. |
| `@jakarta.persistence.Transient` | Excluded from mapping. |
| `@jakarta.persistence.ManyToOne(fetch)` | Owning many-to-one relationship. |
| `@jakarta.persistence.OneToOne(fetch)` | Owning one-to-one relationship. |
| `@jakarta.persistence.JoinColumn(name)` | FK column name (default `<attr>_id`). |
| `@jakarta.persistence.Embedded` / `@Embeddable` | Inline mapping. |
| `@jakarta.data.repository.Repository(dataStore = "name")` | Multi-DataSource selection: Mansart resolves the value as a CDI `@Named` on `DataSource` (`java:` prefix → JNDI). No proprietary annotation. |

Secondary benefit: total interop with Hibernate, EclipseLink, Spring Data, and the future `mansart-persistence` (JPA 3.2). Standard JPA static metamodel (`Author_.id`, `Author_.name`) generated in parallel with the rich Mansart metamodel (`_Author.$MODEL`).

Default conventions (without `@Table` / `@Column`):
- Table = plural snake_case (`Book` → `books`, `OrderLine` → `order_lines`).
- Column = snake_case (`publishedOn` → `published_on`).
- FK = `<attr>_id` (`@ManyToOne Author author` → `author_id`).

## Repository — supported features

### Inherited methods
- `BasicRepository<T,K>`: `save`, `saveAll`, `findById`, `findAll`, `delete`, `deleteById`, `deleteAll`.
- `CrudRepository<T,K>`: same + `insert`, `update`.
- `DataRepository<T,K>`: marker.

### Lifecycle annotations
- `@Insert`, `@Update`, `@Delete`, `@Save` typed (parameter = entity, collection or varargs; return `void`/`T`/`Iterable<T>`/`int`/`long`/`boolean`).

### `@Find`
- Parameter name → entity attribute binding.

### Derived queries (by method name)
- Prefixes: `findBy`, `findOneBy`, `existsBy`, `countBy`, `deleteBy`, `findAllBy`.
- Operators: `Equals`, `Between`, `In`, `LessThan`, `LessThanEqual`, `GreaterThan`, `GreaterThanEqual`, `Like`, `Contains`, `StartsWith`, `EndsWith`, `Null`, `NotNull`, `Empty`, `NotEmpty`, `True`, `False`, `IgnoreCase`, `Not`.
- Sort: `OrderBy<Attr>Asc`/`Desc`, multi-attributes (`OrderByNameAscIdAsc`).

### `@Query` JDQL
- `FROM <Entity>` (optional when context is clear).
- `SELECT *`, scalar projection (e.g.: `SELECT name FROM …`), aggregates (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), `GROUP BY`, `HAVING`.
- `WHERE`: operators `=`, `<>`, `<`, `<=`, `>`, `>=`, `LIKE`, `IN (…)`, `BETWEEN`, `IS [NOT] NULL`, `IS [NOT] EMPTY`, `AND`/`OR`/`NOT`, `UPPER`/`LOWER`.
- Arithmetic: `+ - * /` in `SET` and `WHERE`.
- Literals: `42`, `42L`, `0.5`, `0.5d`, `'string'`, `TRUE`, `FALSE`, `com.foo.MyEnum.VAL`.
- Parameters: named (`:name`) or positional (`?1`).
- `UPDATE … SET … WHERE …`, `DELETE FROM … WHERE …` — returns `long` (affected rows) or `boolean` (rows > 0).
- `ORDER BY` (combinable with runtime `Sort`/`Order`).

### Pagination
- `Page<T>`, `CursoredPage<T>`, `PageRequest` (offset + multi-attribute cursor), `Limit`, `Slice` (variant without count).
- Runtime-passed `Sort` / `Order` — merged with static `ORDER BY`.

### Exceptions
- `EmptyResultException` (method returns `T` but 0 rows).
- `NonUniqueResultException` (method returns `T`/`Optional<T>` but > 1 rows).
- `OptimisticLockingFailureException` (on `@Version`).
- `EntityExists` (SQLState 23505).

## Dialects

```java
public interface DialectFactory {
    Dialect create();
    boolean supports(DatabaseMetaData md);
}
```

Discovery via `ServiceLoader` (declared `provides` in dialect's `module-info.java`). Add a custom dialect by publishing a `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory` (and/or Java Modules `provides`).

| Dialect | Status | Specifics |
| --- | --- | --- |
| `mansart-data-dialect-h2` | ✅ | `MERGE INTO … KEY(…)` for upsert, `BIGINT GENERATED BY DEFAULT AS IDENTITY` for `@GeneratedValue`. |
| `mansart-data-dialect-postgresql` | ✅ | `INSERT … ON CONFLICT (…) DO UPDATE` for upsert, `RETURNING id` for GENERATED KEY. |

## Java Modules

All modules are native Java Modules. Example application-side:

```java
module shop {
    requires io.vidocq.mansart.data.api;
    requires io.vidocq.mansart.data.core;
    requires jakarta.data;
    // no requires on dialects — they are loaded by ServiceLoader
}
```

`mansart-data-core` declares `uses io.vidocq.mansart.data.dialect.DialectFactory`; each dialect declares `provides`.

## Build, tests, TCK

```bash
# Complete build (except official TCK)
./mvnw -ntp install -DskipTests

# Unit tests + smoke
./mvnw test

# Official Jakarta Data 1.0 TCK (outside reactor — dedicated script)
cd mansart-data-tck
./run-official-tck-data-1.0.sh           # 73/73 EntityTests on H2 in-memory
./run-official-tck-data-1.0.sh --pg      # 73/73 EntityTests on PostgreSQL 17 (Docker required)
./run-official-tck-data-1.0.sh --sig     # 1/1 SignatureTests on H2
./run-official-tck-data-1.0.sh --full    # 74/74 EntityTests + Signature on H2
PG=1 ./run-official-tck-data-1.0.sh --full   # 74/74 on PostgreSQL
```

The TCK runner is in standalone `modelVersion 4.0.0`, **deliberately detached** from parent reactor (Maven Model 4.1.0 incompatible with ShrinkWrap Maven Resolver 3.3 used by TCK). See `mansart-data-tck/README.md`.

## Modules

| Module | Description |
| --- | --- |
| [`mansart-data-dialects/mansart-data-dialect-spi`](mansart-data-dialects/mansart-data-dialect-spi/) | SPI: `Dialect`, `DialectFactory`, `EntityModel`, `Attribute` (sealed), `Where`, `OrderBy`, `Pagination`, `SqlFragment`. |
| [`mansart-data-dialects/mansart-data-dialect-h2`](mansart-data-dialects/mansart-data-dialect-h2/) | H2 dialect (`provides DialectFactory`). |
| [`mansart-data-dialects/mansart-data-dialect-postgresql`](mansart-data-dialects/mansart-data-dialect-postgresql/) | PostgreSQL dialect (`provides DialectFactory`). |
| [`mansart-data-core`](mansart-data-core/) | Runtime: `MansartData`, `RepositoryRuntime`, JDQL parser/executor, runtime `EntityModel`, **hidden classes via Class-File API**. |
| [`mansart-data-processor`](mansart-data-processor/) | APT (`SourceVersion.RELEASE_25`) — generates `_<Entity>` (rich Mansart metamodel) + `<Entity>_` (JPA static metamodel) + `<Repo>Impl`. |
| [`mansart-data-cdi`](mansart-data-cdi/) | CDI 4.1 Lite bootstrap — `BuildCompatibleExtension` discovering `@Repository` and registering `@Singleton` synthetic beans. |
| [`mansart-data-tests`](mansart-data-tests/) | Internal tests: CRUD, pagination, JDQL, virtual threads, lifecycle. |
| [`mansart-data-tck`](mansart-data-tck/) | Jakarta Data 1.0 TCK runner — **outside reactor**. |

## Philosophy

- **No runtime reflection** on entities beyond the initial `MethodHandle` obtained by `MethodHandles.privateLookupIn` (one `Lookup` per entity, cached).
- **No dynamic proxy** (`java.lang.reflect.Proxy`): replaced in M7-25 by hidden classes generated via **Class-File API** (`java.lang.classfile`, JEP 484). AOT-compatible (GraalVM, Leyden CDS).
- **No third-party bytecode generation** (no ASM, Byte Buddy, cglib).
- **Zero external dependency** except `jakarta.data-api`, `jakarta.persistence-api` (compile-only), `jakarta.cdi-api` / `jakarta.inject-api` (CDI module), `jakarta.transaction-api` (future transactions).
- **Virtual Threads** — all `PreparedStatement.execute*` execution happens from a virtual thread; no `synchronized` around blocking operations.
- **Strict Java Modules** — minimalist `module-info.java` on all modules, no classpath.

## Links

- [Jakarta Data 1.0 Spec](https://jakarta.ee/specifications/data/1.0/)
- [`PLAN.md`](PLAN.md) — detailed roadmap of milestones M2 → M7-25 and beyond.
- [`BUG.md`](BUG.md) — open and resolved bugs.
- [`BENCH.md`](BENCH.md) — performance measurements.
- [`mansart-data-tck/README.md`](mansart-data-tck/README.md) — official TCK execution.
- Vidocq workspace: see `../../CLAUDE.md` for cross-cutting philosophy.

## License

See `LICENSE` file at the Vidocq workspace root.
