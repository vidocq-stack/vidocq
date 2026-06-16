# DevServices: multi-datasource, DataGrip connectivity, and DEV_SERVICES.md

- **Date**: 2026-06-16
- **Status**: Approved (design) — ready for implementation planning
- **Scope**: `mansart` (mansart-jakarta-data) + `vidocq` (runtime DevServices, docs)

## 1. Context & goal

`vidocq:dev` provisions a Postgres + Keycloak via Testcontainers and forks the app in
debug mode. Today it supports a **single** datasource: `PostgresDevService` publishes the
fixed keys `vidocq.pool.url/username/password`, and `MansartPoolExtension` exposes a single
`@Default` `DataSource` bean. Three gaps to close:

1. **Multiple named datasources** — an app may need a business DB and a separate
   reporting/analytics DB, each reachable from different repositories.
2. **External tooling (DataGrip)** — the mapped Testcontainers port is logged inside the
   JDBC URL but is otherwise hard to find and changes every run.
3. **No documentation** — there is no `DEV_SERVICES.md` describing the setup.

This is a *generic capability* to lay down well, not a fix for a blocked project.

## 2. Key finding (verified by reading the code)

mansart-data **already routes named datasources**. `DataStoreResolver` (mansart-data-cdi)
resolves `@Repository(dataStore="X")` to a CDI lookup
`lookup.select(DataSource.class, NamedLiteral.of("X"))` (with a `java:` JNDI fallback,
per-store caching, and clear errors). A `MultiDataStoreArquillianTest` (primary/secondary)
already exercises this. **Consumption is done.** The only missing links are *publication*:
the runtime publishes a single `@Default` DataSource, and the DevService starts a single DB.

## 3. Non-goals (YAGNI)

- No change to `DataStoreResolver` or the Vauban resolution engine.
- No heterogeneous engines in the first cut (the per-container model leaves the door open,
  but only Postgres is wired now).
- No XA / JTA multi-resource wiring (lands later with `mansart-persistence`).
- No runtime bytecode generation or dynamic CDI beans — everything is compile-time.

## 4. Architecture overview

| Component | Change |
| --- | --- |
| `DataStoreResolver`, Vauban engine, `MansartPoolHolder` (`@Default`) | **unchanged** |
| `mansart-data-processor` (APT) | **+** generate one `@Named` holder per `dataStore` |
| `mansart-data-core` | **+** `AbstractNamedDataSourceHolder`, `NamedDataSourceRegistry` |
| `MansartPoolExtension` (runtime) | **+** publish named pools into the registry |
| `PostgresDevService` (runtime) | **+** N containers, console message, coords file |
| `DEV_SERVICES.md` (vidocq root) | **new** |

## 5. Part A — Multi-datasource (compile-time, approach "B")

### 5.1 Generated holder (APT)
For each distinct `@Repository(dataStore="X")` where `X` is non-empty and not a `java:` JNDI
name, `mansart-data-processor` generates once per name:

```java
@Named("X") @Singleton
public final class _X$DataSource extends AbstractNamedDataSourceHolder {
    public _X$DataSource() { super("X"); }
}
```

Plain annotated `@Named @Singleton` class → discovered by **both** Weld (TCK) and Vauban
(runtime), no BuildCompatibleExtension required. `DataStoreResolver`'s existing
`select(DataSource.class, NamedLiteral.of("X"))` finds it unchanged. JNDI (`java:`) stores
keep the existing JNDI path and get no generated holder.

> **Limitation**: a `@Named` holder exists only for stores referenced by some
> `@Repository(dataStore="X")`. A direct `@Inject @Named("X") DataSource` outside any
> repository is out of scope — the named pool is still registered in the registry, it just
> has no CDI bean. This matches the Jakarta Data access model (DBs are reached via
> repositories) and keeps generation driven by real usage.

### 5.2 `AbstractNamedDataSourceHolder` (mansart-data-core)
Carries all ten `DataSource` methods, each delegating to
`NamedDataSourceRegistry.require(name)`. The generated subclass is the 3-line file above —
mirrors the existing `MansartPoolHolder` delegation pattern (`@Singleton`, no client proxy).

### 5.3 `NamedDataSourceRegistry` (mansart-data-core)
Neutral `Map<String, DataSource>`: `register(name, ds)`, `unregister(name)`,
`require(name)` (clear error if absent — "no DataSource registered for dataStore 'X'; is
`vidocq.pool.X.url` set?"). Not coupled to the pool — any producer can register.

### 5.4 Named pools (runtime — `MansartPoolExtension`)
`configure()` additionally scans `vidocq.pool.<name>.*` (same key shape as the `@Default`,
namespaced). `beforeStart()` builds one `MansartDataSource` per named block and calls
`NamedDataSourceRegistry.register(name, ds)`. `onStop()` drains and unregisters them. The
`@Default` path (`vidocq.pool.*` → `MansartPoolHolder`) is byte-for-byte unchanged.

## 6. Part B — DevService multi-base

- Declaration: `vidocq.dev.postgres.datasources=analytics,audit` → one `PostgreSQLContainer`
  per name → publishes `vidocq.pool.<name>.url/username/password`.
- Per-datasource overrides: `vidocq.dev.postgres.<name>.image|db|username|password|port`.
- The `@Default` base keeps the current `vidocq.dev.postgres.*` keys (back-compatible; an
  app with no `datasources` list behaves exactly as today).
- `DevServiceManager` aggregation is collision-free because keys are namespaced.

## 7. Part C — DataGrip connectivity (console + file)

Both are produced in dev mode. Per datasource, a readable console block at startup:

```
╭─ DevService postgres [analytics] ───────────────
│ JDBC : jdbc:postgresql://localhost:54033/analytics
│ User : test     Password : test
╰─ DataGrip: host=localhost port=54033 db=analytics
```

Plus a machine-readable `target/vidocq-dev-services.properties` written before the first
fork (and refreshed if containers restart), e.g.:

```
postgres.default.jdbcUrl=jdbc:postgresql://localhost:54012/test
postgres.default.username=test
postgres.default.password=test
postgres.analytics.jdbcUrl=jdbc:postgresql://localhost:54033/analytics
...
```

- **Port policy**: random by default (no collisions). Opt-in fixed port via
  `vidocq.dev.postgres.<name>.port=5432` together with `vidocq.dev.reuse=true` to keep the
  container (and port) stable across runs for a persistent DataGrip connection. A fixed-port
  collision fails fast with a clear message and the override hint.

## 8. Part D — `DEV_SERVICES.md` (vidocq root)

Outline: principle (providers on the plugin classpath, AOT-safe) · Postgres + Keycloak ·
published keys · `vidocq.dev.*` overrides · **multi-datasource** (declaration +
`@Repository(dataStore)`) · **DataGrip recipe** (console, coords file, fixed-port/reuse) ·
debug 5005 · pitfalls (Docker daemon resolution → `~/.testcontainers.properties`).

## 9. PR breakdown

1. **PR1 — mansart** (do first, stabilize & test): `NamedDataSourceRegistry` +
   `AbstractNamedDataSourceHolder` in mansart-data-core; APT holder generation in
   mansart-data-processor; extend the multi-store TCK / unit tests to cover the generated
   holder discovery. Green before PR2.
2. **PR2 — vidocq runtime**: named pools in `MansartPoolExtension`; N-container
   `PostgresDevService` + console message + coords file + port policy; `DEV_SERVICES.md`.
   Depends on PR1 being published to local M2.

## 10. Testing strategy

- **mansart**: unit test the APT (generated holder source for a `dataStore`), the registry
  (register/require/error), and an Arquillian test where two generated holders resolve to
  two registered DataSources (extend `MultiDataStoreArquillianTest`).
- **runtime**: Docker-gated IT — `vidocq.dev.postgres.datasources=a,b` starts two
  containers, publishes the namespaced keys, and a child app with two
  `@Repository(dataStore=...)` reads from the right DB; assert the console block and the
  coords file content.

## 11. Decisions log

- Need = generic capability, well laid (not project-blocked).
- Isolation = one container per datasource (extensible to other engines later).
- Declaration = explicit list `vidocq.dev.postgres.datasources=...`.
- Port = random default, fixed opt-in + reuse for DataGrip stability; console **and** coords
  file in dev mode.
- Publication of named beans = **approach B** (APT compile-time holders + registry); Vauban
  and `DataStoreResolver` untouched.
- `AbstractNamedDataSourceHolder` lives in `mansart-data-core`.
- Two PRs, mansart stabilized first.
```
