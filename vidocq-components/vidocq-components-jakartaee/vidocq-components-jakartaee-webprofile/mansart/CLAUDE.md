# CLAUDE.md — mansart

Sub-project-specific guide. See also the workspace root `CLAUDE.md` for the cross-cutting philosophy.

## Mission

Provide the Vidocq ecosystem with three persistence building blocks, independent at runtime:

- `mansart-jakarta-data` — Jakarta Data 1.0 (declarative repositories).
- `mansart-persistence` — Jakarta Persistence 3.2 (classic JPA).
- `mansart-pool` — virtual-thread-native JDBC pool, zero-dep, optional.

The first two share the same SQL dialect SPI and the same static metamodel. `mansart-pool` is completely decoupled: it only provides a `javax.sql.DataSource` usable by any JDBC client.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` coming in this folder).
- For TCKs: non-public official Jakarta artifacts must be installed in the local M2 (procedure documented in the TCK runner).

## Architecture Constraints to Respect

1. **No runtime reflection** on entities or `@Repository` interfaces. Everything goes through APT (`mansart-data-processor`) which generates:
   - the static metamodel (`_Book`, `_Author`, …) with typed attributes;
   - an `XxxRepositoryImpl implements XxxRepository` implementation class per annotated `@Repository` interface.
2. **No runtime bytecode generation** (no ASM, no Byte Buddy, no dynamic proxy). If a case requires dynamic behavior, use the **Class-File API** (`java.lang.classfile`) — never ASM.
3. **Zero external dependencies** beyond `jakarta.data-api`, `jakarta.persistence-api`, `jakarta.transaction-api`, `jakarta.inject-api`, `jakarta.cdi-api`. JDBC is in the JDK. Drivers (`h2`, `postgresql`) stay in `<scope>provided</scope>` (the application provides them).
4. **Strict JPMS** — each module has its `module-info.java`. The dialect SPI (`mansart-data-dialect-spi`) is `exports`; dialects (`mansart-data-dialect-h2`, `…-postgresql`) are `provides DialectFactory with …` and discovered by `ServiceLoader`.
5. **Virtual Threads** — all `PreparedStatement.execute*` calls run from a virtual thread. A JDBC connection must never be held across a `synchronized` block that wraps a blocking operation.

## Planned Modules (under `mansart-jakarta-data/`)

| Sub-module | Role |
| --- | --- |
| `mansart-data-api` | Re-exposes `jakarta.data` + Mansart-specific annotations (`@Dialect`, `@JdbcRepository`). |
| `mansart-data-core` | Runtime: query plan execution, result-set ↔ entity mapping, connection management. |
| `mansart-data-processor` | APT — static metamodel + generates `*RepositoryImpl` classes. |
| `mansart-data-dialect-spi` | SPI: `Dialect`, `DialectFactory`, neutral query AST. |
| `mansart-data-dialect-h2` | H2 dialect (tests + embedded). |
| `mansart-data-dialect-postgresql` | PostgreSQL dialect (production reference). |
| `mansart-data-tests` | Unit tests (H2 in-memory). |
| `mansart-data-tck` | Jakarta Data 1.0 TCK runner — **OUT OF REACTOR** (standalone POM Model 4.0.0, like other Vidocq TCKs). |

## Conventions

- PostgreSQL integration tests via **Testcontainers** in `<scope>test</scope>` only — never at runtime.
- Generated metamodel under `target/generated-sources/annotations/`; generated classes are prefixed with `_` (JPA static metamodel convention) and annotated `@Generated`.
- All generated queries go through the `mansart-data-dialect-spi` AST — never inline SQL in `mansart-data-core`.
- Reproducible bugs → `BUG.md` (skill `/log-bug`). Performance measurements → `BENCH.md` (skill `/log-bench`).
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## Roadmap (proposed milestones)

- **M1 — Validated plan** *(in progress)* — see `PLAN.md` and `mansart-jakarta-data/PLAN.md`.
- **M2 — APT metamodel + repositories**: generation of `_Entity` and `*RepositoryImpl` for `BasicRepository`, `CrudRepository`, `@Find`, `@Save`, `@Delete`.
- **M3 — H2 dialect + unit tests**: full CRUD, offset/keyset pagination, `@OrderBy`, `Sort`, `Limit`, `Page`.
- **M4 — PostgreSQL dialect**: dialect port + Testcontainers tests + `RETURNING` identifiers.
- **M5 — `@Query` JDQL**: JDQL parsing → AST → dialect SQL. (JPQL later via `mansart-persistence`.)
- **M6 — Jakarta Data 1.0 TCK**: out-of-reactor Arquillian harness, smoke + full suite.
- **M7 — `mansart-persistence` bridge**: minimal JPA 3.2 bootstrap, shared metamodel, typed `EntityManager` repositories.
