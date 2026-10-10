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
| `mansart-validation`   | **Jakarta Validation 3.1** (Bean Validation) implementation, planned. Standalone; also consumed by `mansart-persistence` (spec §3.7). |
| `mansart-pool`         | **Virtual-thread-native** JDBC connection pool, post-Loom, zero dependencies, optional and standalone. Usable outside Mansart. |

## Philosophy (inherited from Vidocq)

- **Strict Java Modules**, no classpath.
- **Class-File API (JEP 484) + APT** to generate `@Repository` implementations and the static metamodel at compile time. No runtime reflection, no dynamic proxies.
- **Zero external dependencies** beyond Jakarta specs. Native JDBC, no Hibernate, no Spring Data, no QueryDSL.
- **Virtual Threads** for all query execution.
- **TDD + Arquillian** for the TCK harness.

## Status

✅ Jakarta Persistence P0–P12 are delivered under a scope-based engineering gate, including required
`SINGLE_TABLE` / `JOINED` inheritance, polymorphic loading, JPQL/native/stored queries, runtime/canonical metamodel,
Criteria through the shared query engine, and entity graphs.
P9 adds dialect-rendered schema generation, `SchemaManager`, the JTA/CDI bridge, the Vidocq runtime extension and
its Arquillian gate (6/6). P10 maps `orm.xml` mapping files (secure StAX + XSD, metadata-complete,
unit defaults, XML-over-annotation overrides, XML callbacks and default listeners) into the same entity model.
P10 is **delivered** under a scope-based gate (2026-10-10): case-preserving delimited identifiers,
inherited and entity-owned embedded annotation/XML association overrides are implemented. Embedded references
and collections execute through composed generated accesses and existing relationship planners; unsupported P5
mapping shapes are rejected, not silently omitted.
The untouched official PostgreSQL 17 TCK baseline reports **2096 / 2135 passed**, 35 errors and 4 official skips:
10 cache errors in the earlier P11 baseline and 25 incompatibilities between two delimited-default fixtures and
the unquoted DDL.
The runner applies by default the local fixture patch **TCK-BUG-001**, a byte-exact backport of the upstream fix
(jakartaee/persistence#1175, commit `1fea05e`) to a derived copy of the spec-tests jar: **2131 / 2135 passed**,
0 failures/errors, 4 official skips, zero regressions. This locally patched score is not an official result and no
certification is sought; official artifacts and the provider are unchanged (`TCK_FIXTURES=official` reproduces the
2096 baseline). The final full rerun and exact counters are recorded in [`mansart-persistence/TCK.md`](./mansart-persistence/TCK.md).
Runtime baseline checks use the local Vauban snapshot carrying the
upstream injection fix (pending a Vauban release). The P8 gate remains **924 passed / 925**, one official skip.
See [`mansart-persistence/TCK.md`](./mansart-persistence/TCK.md) for the measured scope and remaining blockers.
P11 adds the optional second-level cache and Bean Validation bridge. P12 adds isolated JMH comparisons against
Hibernate ORM and EclipseLink plus a modular JDK Leyden AOT cache smoke for the APT path; raw benchmark evidence
is recorded in [`mansart-persistence/BENCH.md`](./mansart-persistence/BENCH.md) (commands and raw log locations:
[`mansart-jpa-bench/README.md`](./mansart-persistence/mansart-jpa-bench/README.md)). No performance winner is claimed
without supporting measurements. A Leyden cache smoke is not a GraalVM native-image proof.
No formal Jakarta Persistence certification is sought, and the Web Profile is not a target.
`TABLE_PER_CLASS` remains explicitly refused and decision D5 remains open.
Data remains unchanged: incompatible plural fields in Data-owned canonical classes need producer reconciliation.
See [`PLAN.md`](./PLAN.md) for the overall vision and
[`ROADMAP.md`](./ROADMAP.md) for all sub-project statuses.

## Priority dialects

1. **H2** (embedded mode — target for unit tests and in-memory TCK).
2. **PostgreSQL** (reference production target).

Others (MySQL, MariaDB, Oracle, SQL Server, SQLite, DuckDB) will follow once the dialect SPI stabilises.
