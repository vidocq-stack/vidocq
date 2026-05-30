# Mansart

<p align="center">
  <img src="mansart-logo.png" alt="Mansart" width="300">
</p>

Jakarta Data 1.0 + Jakarta Persistence 3.2 implementation for the Vidocq ecosystem.

The name pays homage to **Jules Hardouin-Mansart**, 17th-century architect (Versailles, Invalides,
Place Vendome) — the idea of durable, modular, carefully assembled structures matches the role of
the persistence layer in the stack.

## Sub-projects

| Sub-project | Description |
| --- | --- |
| `mansart-jakarta-data` | **Jakarta Data 1.0** implementation (repositories, pagination, query methods). Direct JDBC backend, pluggable dialects. |
| `mansart-persistence`  | **Jakarta Persistence 3.2** (JPA) implementation, reused by `mansart-jakarta-data` for entity-typed repositories when the app already provides an `EntityManager`. |
| `mansart-pool`         | **Virtual-thread-native** JDBC connection pool, post-Loom, zero dependencies, optional and standalone. Usable outside Mansart. |

## Philosophy (inherited from Vidocq)

- **Strict JPMS**, no classpath.
- **Class-File API (JEP 484) + APT** to generate `@Repository` implementations and the static metamodel at compile time. No runtime reflection, no dynamic proxies.
- **Zero external dependencies** beyond Jakarta specs. Native JDBC, no Hibernate, no Spring Data, no QueryDSL.
- **Virtual Threads** for all query execution.
- **TDD + Arquillian** for the TCK harness.

## Status

🚧 Under design — see [`PLAN.md`](./PLAN.md) for the overall vision and [`mansart-jakarta-data/PLAN.md`](./mansart-jakarta-data/PLAN.md) for the detailed plan of the first module.

## Priority dialects

1. **H2** (embedded mode — target for unit tests and in-memory TCK).
2. **PostgreSQL** (reference production target).

Others (MySQL, MariaDB, Oracle, SQL Server, SQLite, DuckDB) will follow once the dialect SPI stabilises.
