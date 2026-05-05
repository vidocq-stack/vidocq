# mansart-jakarta-data

**Implémentation Jakarta Data 1.0** pour l'écosystème Vidocq — JDBC, JPMS strict, zéro dépendance hors specs Jakarta, génération statique APT et bytecode runtime via Class-File API (JEP 484). Pas de réflexion runtime non bornée. Pas de proxy dynamique. AOT-friendly (GraalVM, Leyden).

```
            ┌────────────────────────────────────────────┐
            │   @Repository interface (votre code)       │
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

## État

- **Jakarta Data 1.0 TCK : 74/74 PASS** sur **H2** ✅ et **74/74 PASS** sur **PostgreSQL 17** ✅ (73 EntityTests + 1 SignatureTests, Testcontainers).
- **Tests internes : 93/93 unit + 6/6 smoke Arquillian** — voir `mansart-data-tests/`.
- Dialectes livrés : `H2`, `PostgreSQL`.
- Mode bootstrap **standalone** (`MansartData.builder()`) ou **CDI 4.1 Lite** (BCE — Vauban / Weld / OpenWebBeans).

Voir `PLAN.md` pour la roadmap complète et `BUG.md` pour les bugs ouverts/clos.

## Prérequis

- **Java 25** (Temurin)
- **Maven 4.0.0-rc-5**
- (Optionnel) `sdkman` : `cd mansart-jakarta-data && sdk env`

> Le `pom.xml` parent utilise Maven Model 4.1.0 — Maven 3.x ne sait pas le lire.

## Installation

```xml
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Au moins un dialecte -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-dialect-h2</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Driver JDBC : à fournir par l'application (scope provided côté dialecte) -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.3.232</version>
</dependency>

<!-- Optionnel : génération compile-time des *RepositoryImpl + métamodèle -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-processor</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>

<!-- Optionnel : bootstrap CDI 4.1 -->
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-cdi</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quickstart

### 1. Une entité

Annotations Mansart (zéro-dep) **ou** annotations JPA (`jakarta.persistence`) — Mansart reconnaît les deux. Si les deux sont présentes sur la même classe, l'APT échoue avec un message clair.

```java
package shop;

import io.vidocq.mansart.data.Entity;
import io.vidocq.mansart.data.Id;
import io.vidocq.mansart.data.GeneratedValue;
import io.vidocq.mansart.data.Column;

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

### 2. Un repository

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

### 3. Bootstrap standalone

```java
import io.vidocq.mansart.data.core.MansartData;
import org.h2.jdbcx.JdbcDataSource;

JdbcDataSource ds = new JdbcDataSource();
ds.setURL("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
ds.setUser("sa");

MansartData md = MansartData.builder()
        .dataSource(ds)             // requis
        // .dialect(new H2Dialect()) // optionnel, sinon auto-détection
        .build();

AuthorRepository repo = md.repository(AuthorRepository.class);

Author a = new Author(); a.setName("Victor Hugo");
repo.save(a);                                  // SQL INSERT + GENERATED KEY → a.id
repo.findById(a.getId()).ifPresent(System.out::println);
List<Author> hits = repo.findByNameLike("Vic%");
```

`md.repository(itf)` cherche d'abord la classe `<itf>Impl` générée par l'APT. Si l'APT n'a pas tourné (ex. interfaces compilées dans un jar tiers comme le TCK), utilisez `md.runtimeRepository(itf)` qui passe par la **génération bytecode runtime via Class-File API**.

### 4. Bootstrap CDI 4.1

Ajouter `mansart-data-cdi` au classpath. Le module fournit :

- `MansartDataExtension` (`BuildCompatibleExtension`) — découvre toutes les interfaces `@jakarta.data.repository.Repository` du déploiement, enregistre un bean synthétique `@Singleton` typé sur l'interface.
- `MansartRuntimeProducer` — produit un `RepositoryRuntime` `@Singleton` à partir d'un `DataSource` injecté par l'application.

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

Le BCE est standard CDI 4.1 Lite — compatible Vauban (Vidocq), Weld embedded, OpenWebBeans.

## Annotations entité

`mansart-data-api` expose un set minimal zéro-dep, miroir des annotations JPA :

| Annotation Mansart | Équivalent JPA |
| --- | --- |
| `@Entity` | `@jakarta.persistence.Entity` |
| `@Table(name, schema)` | idem |
| `@Id` | idem |
| `@GeneratedValue(strategy)` | idem (`AUTO`, `IDENTITY`, `SEQUENCE`) |
| `@Column(name, nullable, unique, length)` | idem |
| `@Version` | idem (optimistic locking) |
| `@Enumerated(STRING\|ORDINAL)` | idem |
| `@Transient` | idem |
| `@ManyToOne(fetch)` | idem |
| `@OneToOne(fetch)` | idem |
| `@JoinColumn(name)` | idem |
| `@Embedded` / `@Embeddable` | idem |
| `@MansartDataSource("name")` | (extension Mansart — sélection de DataSource) |

Conventions par défaut (sans `@Table` / `@Column`) :
- Table = pluriel snake_case (`Book` → `books`, `OrderLine` → `order_lines`).
- Colonne = snake_case (`publishedOn` → `published_on`).
- FK = `<attr>_id` (`@ManyToOne Author author` → `author_id`).

## Repository — fonctionnalités supportées

### Méthodes héritées
- `BasicRepository<T,K>` : `save`, `saveAll`, `findById`, `findAll`, `delete`, `deleteById`, `deleteAll`.
- `CrudRepository<T,K>` : idem + `insert`, `update`.
- `DataRepository<T,K>` : marker.

### Annotations lifecycle
- `@Insert`, `@Update`, `@Delete`, `@Save` typés (paramètre = entité, collection ou varargs ; retour `void`/`T`/`Iterable<T>`/`int`/`long`/`boolean`).

### `@Find`
- Liaison nom de paramètre → attribut entité.

### Derived queries (par nom de méthode)
- Préfixes : `findBy`, `findOneBy`, `existsBy`, `countBy`, `deleteBy`, `findAllBy`.
- Opérateurs : `Equals`, `Between`, `In`, `LessThan`, `LessThanEqual`, `GreaterThan`, `GreaterThanEqual`, `Like`, `Contains`, `StartsWith`, `EndsWith`, `Null`, `NotNull`, `Empty`, `NotEmpty`, `True`, `False`, `IgnoreCase`, `Not`.
- Tri : `OrderBy<Attr>Asc`/`Desc`, multi-attributs (`OrderByNameAscIdAsc`).

### `@Query` JDQL
- `FROM <Entity>` (optionnel quand contexte clair).
- `SELECT *`, projection scalaire (ex: `SELECT name FROM …`), agrégats (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), `GROUP BY`, `HAVING`.
- `WHERE` : opérateurs `=`, `<>`, `<`, `<=`, `>`, `>=`, `LIKE`, `IN (…)`, `BETWEEN`, `IS [NOT] NULL`, `IS [NOT] EMPTY`, `AND`/`OR`/`NOT`, `UPPER`/`LOWER`.
- Arithmétique : `+ - * /` dans `SET` et `WHERE`.
- Litéraux : `42`, `42L`, `0.5`, `0.5d`, `'string'`, `TRUE`, `FALSE`, `com.foo.MyEnum.VAL`.
- Paramètres : nommés (`:name`) ou positionnels (`?1`).
- `UPDATE … SET … WHERE …`, `DELETE FROM … WHERE …` — retour `long` (rows affectés) ou `boolean` (rows > 0).
- `ORDER BY` (combinable avec `Sort`/`Order` runtime).

### Pagination
- `Page<T>`, `CursoredPage<T>`, `PageRequest` (offset + cursor multi-attributs), `Limit`, `Slice` (variante sans count).
- `Sort` / `Order` passés au runtime — fusionnés avec `ORDER BY` statique.

### Exceptions
- `EmptyResultException` (méthode renvoie `T` mais 0 ligne).
- `NonUniqueResultException` (méthode renvoie `T`/`Optional<T>` mais > 1 ligne).
- `OptimisticLockingFailureException` (sur `@Version`).
- `EntityExists` (SQLState 23505).

## Dialectes

```java
public interface DialectFactory {
    Dialect create();
    boolean supports(DatabaseMetaData md);
}
```

Découverte par `ServiceLoader` (déclaré `provides` dans le `module-info.java` du dialecte). Ajoutez un dialecte custom en publiant un `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory` (et/ou `provides` JPMS).

| Dialecte | État | Spécificités |
| --- | --- | --- |
| `mansart-data-dialect-h2` | ✅ | `MERGE INTO … KEY(…)` pour upsert, `BIGINT GENERATED BY DEFAULT AS IDENTITY` pour `@GeneratedValue`. |
| `mansart-data-dialect-postgresql` | ✅ | `INSERT … ON CONFLICT (…) DO UPDATE` pour upsert, `RETURNING id` pour les GENERATED KEY. |

## JPMS

Tous les modules sont JPMS-natifs. Exemple côté application :

```java
module shop {
    requires io.vidocq.mansart.data.api;
    requires io.vidocq.mansart.data.core;
    requires jakarta.data;
    // pas de requires sur les dialectes — ils sont chargés par ServiceLoader
}
```

`mansart-data-core` déclare `uses io.vidocq.mansart.data.dialect.DialectFactory` ; chaque dialecte déclare `provides`.

## Build, tests, TCK

```bash
# Build complet (sauf TCK officiel)
./mvnw -ntp install -DskipTests

# Tests unitaires + smoke
./mvnw test

# TCK officiel Jakarta Data 1.0 (hors reactor — script dédié)
cd mansart-data-tck
./run-official-tck-data-1.0.sh           # 73/73 EntityTests sur H2 in-memory
./run-official-tck-data-1.0.sh --pg      # 73/73 EntityTests sur PostgreSQL 17 (Docker requis)
./run-official-tck-data-1.0.sh --sig     # 1/1 SignatureTests sur H2
./run-official-tck-data-1.0.sh --full    # 74/74 EntityTests + Signature sur H2
PG=1 ./run-official-tck-data-1.0.sh --full   # 74/74 sur PostgreSQL
```

Le runner TCK est en `modelVersion 4.0.0` standalone, **volontairement détaché** du reactor parent (Maven Model 4.1.0 incompatible avec ShrinkWrap Maven Resolver 3.3 utilisé par le TCK). Voir `mansart-data-tck/README.md`.

## Modules

| Module | Description |
| --- | --- |
| [`mansart-data-api`](mansart-data-api/) | Annotations Mansart zéro-dep (miroir de JPA) + extension `@MansartDataSource`. |
| [`mansart-data-dialects/mansart-data-dialect-spi`](mansart-data-dialects/mansart-data-dialect-spi/) | SPI : `Dialect`, `DialectFactory`, `EntityModel`, `Attribute` (sealed), `Where`, `OrderBy`, `Pagination`, `SqlFragment`. |
| [`mansart-data-dialects/mansart-data-dialect-h2`](mansart-data-dialects/mansart-data-dialect-h2/) | Dialecte H2 (`provides DialectFactory`). |
| [`mansart-data-dialects/mansart-data-dialect-postgresql`](mansart-data-dialects/mansart-data-dialect-postgresql/) | Dialecte PostgreSQL (`provides DialectFactory`). |
| [`mansart-data-core`](mansart-data-core/) | Runtime : `MansartData`, `RepositoryRuntime`, JDQL parser/exécuteur, runtime `EntityModel`, **classes hidden via Class-File API**. |
| [`mansart-data-processor`](mansart-data-processor/) | APT (`SourceVersion.RELEASE_25`) — génère `_<Entity>` (métamodèle Mansart riche) + `<Entity>_` (JPA static metamodel) + `<Repo>Impl`. |
| [`mansart-data-cdi`](mansart-data-cdi/) | Bootstrap CDI 4.1 Lite — `BuildCompatibleExtension` qui découvre les `@Repository` et enregistre des beans synthétiques `@Singleton`. |
| [`mansart-data-tests`](mansart-data-tests/) | Tests internes : CRUD, pagination, JDQL, virtual threads, lifecycle. |
| [`mansart-data-tck`](mansart-data-tck/) | Runner TCK Jakarta Data 1.0 — **hors reactor**. |

## Philosophie

- **Aucune réflexion runtime** sur les entités au-delà du `MethodHandle` initial obtenu par `MethodHandles.privateLookupIn` (un seul `Lookup` par entité, mis en cache).
- **Aucun proxy dynamique** (`java.lang.reflect.Proxy`) : remplacé en M7-25 par des classes hidden générées via **Class-File API** (`java.lang.classfile`, JEP 484). Compatible AOT (GraalVM, Leyden CDS).
- **Aucune génération de bytecode tierce** (pas d'ASM, Byte Buddy, cglib).
- **Zéro dépendance externe** hors `jakarta.data-api`, `jakarta.persistence-api` (compile-only), `jakarta.cdi-api` / `jakarta.inject-api` (module CDI), `jakarta.transaction-api` (transactions futures).
- **Virtual Threads** — toute exécution `PreparedStatement.execute*` se fait depuis un virtual thread ; pas de `synchronized` autour d'opérations bloquantes.
- **JPMS strict** — `module-info.java` minimaliste sur tous les modules, pas de classpath.

## Liens

- [Spec Jakarta Data 1.0](https://jakarta.ee/specifications/data/1.0/)
- [`PLAN.md`](PLAN.md) — roadmap détaillée des jalons M2 → M7-25 et au-delà.
- [`BUG.md`](BUG.md) — bugs ouverts et résolus.
- [`BENCH.md`](BENCH.md) — mesures de performance.
- [`mansart-data-tck/README.md`](mansart-data-tck/README.md) — exécution TCK officiel.
- Workspace Vidocq : voir `../../CLAUDE.md` pour la philosophie transverse.

## Licence

Voir le fichier `LICENSE` à la racine du workspace Vidocq.
