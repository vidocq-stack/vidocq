# Mansart — Roadmap

Stack Jakarta Data 1.0 + Jakarta Persistence 3.2 + virtual-thread-native JDBC pool,
zero or few external production dependencies. Independent runtime building blocks.

> Vision and structural decisions: see [`PLAN.md`](PLAN.md). Product overview: [`README.md`](README.md).

## Overview

| Sub-project | Spec | Status |
|---|---|---|
| `mansart-jakarta-data` | Jakarta Data 1.0 (repositories) | ✅ M3-M4 delivered, integrated into the Vidocq runtime |
| `mansart-pool` | — (post-Loom JDBC pool) | ✅ M2 delivered (H2), M5 PostgreSQL delivered |
| `mansart-dialect-spi` | — (shared SPI) | ✅ M1 delivered (H2 + PostgreSQL) |
| `mansart-persistence` (`mansart-jpa`) | Jakarta Persistence 3.2 (JPA) | ✅ **P0–P12 delivered, scope-based; no formal certification sought** (TCK evidence and bounded comparative performance/AOT smoke in sub-project docs), see [`mansart-persistence/ROADMAP.md`](mansart-persistence/ROADMAP.md) |
| `mansart-transactions` | Minimal JTA | ✅ extension delivered in the runtime |
| `mansart-validation` | Jakarta Validation 3.1 (Bean Validation) | ✅ V0–V2 stopgap usable by Persistence P11; not a complete Validation implementation, see [`mansart-validation/ROADMAP.md`](mansart-validation/ROADMAP.md) |

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

### M7 — Mansart Persistence (JPA 3.2) ✅ Scope-based delivery

**Status**: restarted on 2026-10-07 as `mansart-persistence/` (Maven parent `mansart-jpa`);
P0–P12 delivered on 2026-10-10 within the documented scope.
The detailed plan — milestones P0 (TCK instrument) to P12 (comparative performance and AOT smoke) — lives in
[`mansart-persistence/ROADMAP.md`](mansart-persistence/ROADMAP.md); the summary below is kept
for the overview.

Scope:
- [x] `EntityManager`, `EntityManagerFactory`, JPQL, native query results and stored procedures (P7)
- [x] Lifecycle (`@PrePersist`, `@PostLoad`, etc.)
- [x] `@OneToMany`/`@ManyToOne`/`@ManyToMany` relations (loaded eagerly; `LAZY` remains a hint)
- [x] Required `SINGLE_TABLE` / `JOINED` inheritance, polymorphic loading and JPQL `TYPE` (P6);
      optional `TABLE_PER_CLASS` explicitly refused, decision D5 open
- [x] Runtime/canonical metamodel, Criteria API through the shared JPQL AST, entity graphs (P8);
      frozen Data-owned incompatible canonical plural fields remain a producer-reconciliation boundary
- [x] Optional L2 cache (homegrown, opt-in — no external cache library) and optional Bean Validation integration (P11)
- [x] Jakarta Persistence 3.2 TCK runner outside the reactor (2096 / 2135 untouched baseline; 2131 / 2135 with local fixture patch TCK-BUG-001, zero failures/errors and four official skips)
- [x] Bounded P12 evidence (TCK, comparative JMH, and Leyden AOT cache smoke); no formal certification or Web Profile target

Remaining boundaries: unsupported relationship mapping shapes, optional `TABLE_PER_CLASS`,
runtime cache/validation coverage beyond the existing six integration scenarios, and deferred
Data integration. See the detailed roadmap; a green local patched TCK is not exhaustive spec coverage.

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
- **`BENCH.md`** : reproducible JMH numbers; JPA results are in `mansart-persistence/BENCH.md`.
