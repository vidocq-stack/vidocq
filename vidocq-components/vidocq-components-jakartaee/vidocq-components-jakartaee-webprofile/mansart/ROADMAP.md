# Mansart — Roadmap

Stack Jakarta Data 1.0 + Jakarta Transactions 2.0 + virtual-thread-native JDBC pool,
zero or few external production dependencies. Independent runtime building blocks.

> Vision and structural decisions: see [`PLAN.md`](PLAN.md). Product overview: [`README.md`](README.md).

## Overview

| Sub-project | Spec | Status |
|---|---|---|
| `mansart-jakarta-data` | Jakarta Data 1.0 (repositories) | ✅ M3-M4 delivered, integrated into the Vidocq runtime |
| `mansart-pool` | — (post-Loom JDBC pool) | ✅ M2 delivered (H2), M5 PostgreSQL delivered |
| `mansart-dialect-spi` | — (shared SPI) | ✅ M1 delivered (H2 + PostgreSQL) |
| `mansart-transactions` | Minimal JTA | ✅ extension delivered in the runtime |

Persistence and the provisional Validation provider were extracted on 2026-10-11.
They are external [Noether](https://codefloe.com/Vidocq/noether) building blocks,
under `io.vidocq.noether`, not Mansart modules. Their roadmaps are
[Persistence](https://codefloe.com/Vidocq/noether/src/branch/main/noether-persistence/ROADMAP.md)
and [Validation](https://codefloe.com/Vidocq/noether/src/branch/main/noether-validation/ROADMAP.md).
Data remains frozen; a Data/JPA bridge stays deferred.

## Delivered phases

### M1 — Dialect SPI ✅
- `Dialect` + `DialectFactory` interface loaded via `ServiceLoader`
- H2 and PostgreSQL implementations (SQL generation, types, paginated select,
  upsert/merge, identity)
- SPI for `mansart-jakarta-data`; Persistence uses its own SQL AST and dialect SPI
  (decision D4), with convergence deferred.

### M2 — Mansart Pool MVP (H2) ✅
- Virtual-thread-native JDBC pool (no pinning ThreadLocal, no classic thread pool
  internally)
- Min/max configuration, idle eviction, leak detection
- Zero dependencies, independent from the rest of Mansart (usable outside Mansart
  with any `DataSource`)
- See `mansart-pool/PLAN.md` for architecture details

### M3 — Mansart Jakarta Data backend JDBC + H2 ✅
- Reference backend: **pure JDBC**, not JPA (avoids the circular dependency,
  minimal binary, demonstrates Jakarta Data without ORM)
- Static repository generation via APT (`@Repository` → `XxxRepositoryImpl`
  at compile time, no `java.lang.reflect.Proxy` proxy)
- Static `_Book`, `_Author` metamodel produced by APT
- Query methods : findBy, countBy, deleteBy, BasicRepository, PageableRepository
- Pagination, Sort, Limit, Order

### M4 — PostgreSQL dialect ✅
- Complete PostgreSQL `Dialect` adapter
- Integration tests via testcontainers (out-of-band, no test dep on Mansart core)

### M5 — Mansart Pool PostgreSQL ✅
- Pool validation under PostgreSQL load
- Metrics (active connections, waiters, eviction rate)

### Vidocq extension ✅
- `vidocq-runtime-mansart-data-extension` : integration into the MicroProfile runtime
  via Vidocq SPI (see [vidocq runtime ROADMAP](../vidocq/ROADMAP.md))
- `vidocq-runtime-mansart-pool-extension`
- `vidocq-runtime-mansart-transactions-extension`
- Example: `vidocq-runtime-mansart-h2-example`

## Ongoing / planned phases

### M6 — Jakarta Data 1.0 TCK ⏳

**Goal**: pass the official Jakarta Data 1.0 TCK on the applicable profiles.

- [ ] `mansart-jakarta-data-tck/` **outside the reactor** (standalone POM Model 4.0.0) —
      same ShrinkWrap constraint as `cassini-tck` / `champollion-tck` / `humboldt-tck`
- [ ] `run-official-tck-data-1.0.sh` script at the root
- [ ] Categorize the expected failures and official challenges in `TCK.md`
- [ ] Target: 100% PASS on the tests applicable to the JDBC backend (tests
      depending on an EntityManager will be explicitly excluded while M7 is
      suspended)

### M7 — Persistence history and extraction

Persistence P0–P12 was delivered under a scope-based engineering gate in Mansart
on 2026-10-10 and moved to Noether on 2026-10-11. The implementation, detailed
roadmap, fixture policy and historical evidence are owned by that separate repo.
This extraction does not change delivered Data, Pool or Transactions semantics.

### M8 — Performance & footprint (TBD)
- [ ] Benchmarks JMH `mansart-jakarta-data` vs Spring Data JDBC, EclipseLink, Hibernate
- [ ] Benchmarks `mansart-pool` vs HikariCP, Agroal (focus virtual threads, pinning)
- [ ] AOT GraalVM memory footprint for other building blocks (zero runtime reflection already respected on the APT side)

## Cross-cutting technical backlog

- [ ] Additional dialects: MySQL, Oracle, SQL Server, MariaDB (as needed)
- [ ] DDL migrations: Flyway integration or minimal homegrown implementation
- [ ] Observability: integration with Humboldt (JDBC spans, pool metrics)

## Out of scope (explicit — see PLAN.md)

- ❌ ORM persistence-context semantics in `mansart-data` → use the separate Persistence implementation;
      runtime enhancement and dynamic proxies remain outside its scope
- ❌ Mandatory/default L2 caching → optional cache delivered in Persistence P11
- ❌ Reactive (Mutiny, R2DBC) → Vidocq philosophy = virtual threads, not reactive

## Bugs

No `BUG.md` today. Create the file if a reproducible regression appears (follow
the pattern of the other sub-projects: short id, date, symptom, repro, status).

## Tracking conventions

- **This roadmap** : M-x milestones, medium-term vision.
- **`PLAN.md`** : architecture, structural decisions, overall vision.
- **`<sous-projet>/PLAN.md`** : specific architecture (exists for `mansart-pool/`).
- **`TCK.md`** : will be created in M6 (Jakarta Data conformance status).
- **`BENCH.md`** : reproducible JMH numbers; historical JPA evidence is preserved in [Noether](https://codefloe.com/Vidocq/noether/src/branch/main/noether-persistence/BENCH.md).
