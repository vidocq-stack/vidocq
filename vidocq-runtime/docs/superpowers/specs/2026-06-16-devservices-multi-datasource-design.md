# DevServices: multi-datasource, connection info, and DEV_SERVICES.md

- **Date**: 2026-06-16
- **Status**: Approved (design v2) — ready for implementation planning
- **Scope**: `vidocq` runtime only (mansart untouched)

## 1. Context & goal

`vidocq:dev` provisions Postgres + Keycloak via Testcontainers and forks the app in debug
mode, but supports a **single** datasource: `PostgresDevService` publishes fixed keys
`vidocq.pool.url/username/password`, and `MansartPoolExtension` exposes one `@Default`
`DataSource`. Three gaps:

1. **Multiple named datasources** — an app may need a business DB + a separate analytics DB,
   each injectable as `@Inject @Named("X") DataSource` and targetable by
   `@Repository(dataStore="X")`.
2. **External tooling connectivity** — the mapped port is buried in the logged JDBC URL.
3. **No documentation** — no `DEV_SERVICES.md`.

A *generic capability laid down well*, AOT-friendly, not a fix for a blocked project.

## 2. Key finding (verified by reading the code)

mansart **already routes named datasources end-to-end and needs no change**:
- `DataStoreResolver` (mansart-data-cdi:71-110) resolves `@Repository(dataStore="X")` to
  `lookup.select(DataSource.class, NamedLiteral.of("X"))` (JNDI fallback for `java:`,
  per-store caching, clear errors). `MultiDataStoreArquillianTest` exercises it.
- `mansart-pool` only provides a `MansartDataSource` (a JDBC pool) — it has no business with
  CDI `@Named` beans.

So the work is **publication only**, and per the design discussion it belongs **entirely in
the Vidocq extension** (`vidocq-runtime-mansart-pool-extension`), not in mansart.

## 3. Non-goals (YAGNI)

- No change to mansart (`mansart-pool`, `mansart-data`, `DataStoreResolver`).
- No change to the Vauban resolution engine; no runtime-registered/dynamic CDI beans.
- No enumeration property (`vidocq.pool.datasources=…`): names are **derived** from the
  `vidocq.pool.<name>.*` keys / the annotation.
- No XA/JTA multi-resource (lands later with `mansart-persistence`).
- First cut wires Postgres only (the per-container model leaves other engines open).

## 4. Declaration model — follow Quarkus

Quarkus splits **structure** (which datasources exist) from **values** (url/credentials):
structure is **build-time fixed** (generates the beans, AOT closed-world), values are
runtime-overridable. We do the same, because MP Config is runtime-dynamic and the build only
sees **static** ConfigSources on the classpath.

- **Structure (names) → build-time**, from **two sources**, both feeding one codegen:
  1. **Properties**: `vidocq.pool.<name>.url` found in the build-visible Vidocq config files
     `vidocq.properties` / `application.properties` (the `PropertiesFileConfigSource.FILES`,
     ordinal 100) — read via the `Filer` at annotation-processing time. **Not**
     `META-INF/microprofile-config.properties`: Vidocq's own convention is `vidocq.properties`,
     and the examples (and arago) declare `vidocq.pool.*` there.
  2. **Annotation**: `@VidocqDataSources({"analytics","audit"})` — covers names declared via
     dynamic sources (env, system props, programmatic ConfigSource) that the build cannot see.
  Names from both are merged and **deduplicated**.
- **Values → runtime**: `vidocq.pool.<name>.url/username/password/...` resolved by MP Config
  (all sources, profiles, dev service overrides).
- **`@Default`**: `vidocq.pool.*` (unchanged).

Accepted limitation (same as Quarkus): a datasource whose name appears **only** in a dynamic
source at runtime gets no `@Named` bean unless also declared via the annotation. Values stay
fully dynamic.

## 5. Architecture — all in `vidocq-runtime-mansart-pool-extension`

| Component | Change |
| --- | --- |
| mansart (`-pool`, `-data`), Vauban engine, `MansartPoolHolder` (`@Default`) | **unchanged** |
| `…-mansart-pool-extension` (api) | **+** `@VidocqDataSources`, `NamedDataSourceRegistry`, `AbstractNamedDataSourceHolder` |
| `…-mansart-pool-extension-codegen` (new APT) | **+** generate one `@Named` holder per declared name |
| `…-mansart-pool-extension` (runtime) | **+** create named pools from MP Config, feed the registry |
| `PostgresDevService` (devservice-postgres) | **+** N containers, console message, coords file |
| `DEV_SERVICES.md` (vidocq root) | **new** |

### 5.1 Generated holder (codegen)
Per declared name `X`:
```java
@Named("X") @Singleton
public final class _X$DataSource extends AbstractNamedDataSourceHolder {
    public _X$DataSource() { super("X"); }
}
```
Plain `@Named @Singleton` → discovered by both Weld (TCK) and Vauban; `DataStoreResolver`
finds it unchanged via `select(DataSource.class, NamedLiteral.of("X"))`.

### 5.2 Shared classes (extension api module)
- `AbstractNamedDataSourceHolder` — the ten `DataSource` methods, delegating to
  `NamedDataSourceRegistry.require(name)` (mirrors `MansartPoolHolder`).
- `NamedDataSourceRegistry` — neutral `Map<String,DataSource>`: `register/unregister/require`
  with a clear error ("no DataSource registered for 'X'; is `vidocq.pool.X.url` set?").
- `@VidocqDataSources` — `String[] value()`. The app depends on this api module at compile
  (to use the annotation and to compile the generated holders that reference the registry).

### 5.3 Codegen APT (new `-codegen` module)
Runs during the **app** build. Collects names from (a) `@VidocqDataSources` and (b)
`vidocq.pool.<name>.url` parsed from the static config resource(s) via the `Filer`. Emits one
deduplicated `_X$DataSource` per name. Wires into the app's `annotationProcessorPaths` like
the other `*-extension-codegen` modules.

### 5.4 Runtime (extension)
`configure()` scans MP Config property names for `vidocq.pool.<name>.url`, builds a
`PoolConfig` per named block (same keys as `@Default`, namespaced). `beforeStart()` creates a
`MansartDataSource` per name and `NamedDataSourceRegistry.register(name, ds)`. `onStop()`
drains and unregisters. The `@Default` path is byte-for-byte unchanged.

## 6. DevService multi-base

- Declaration: `vidocq.dev.postgres.datasources=analytics,audit` → one `PostgreSQLContainer`
  per name → publishes `vidocq.pool.<name>.url/username/password`. This explicit list is
  required here (unlike the runtime pool, which derives names from existing
  `vidocq.pool.<name>.url`): in dev the DevService *creates* those URLs, so it cannot derive
  the names from them — it must be told which databases to start.
- Per-datasource overrides: `vidocq.dev.postgres.<name>.image|db|username|password|port`.
- `@Default` base keeps the current `vidocq.dev.postgres.*` keys (back-compatible).
- `DevServiceManager` aggregation stays collision-free (namespaced keys).

## 7. Connection information (console + file)

Both emitted in dev mode (wording is generic — not tool-specific). Per datasource at startup:
```
╭─ DevService postgres [analytics] ─ Connection information ─
│ JDBC : jdbc:postgresql://localhost:54033/analytics
│ User : test     Password : test
╰─ host=localhost  port=54033  db=analytics
```
Plus a machine-readable `target/vidocq-dev-services.properties`
(`postgres.<name>.jdbcUrl/username/password`) written before the first fork and refreshed on
container restart.

- **Port policy**: random by default (no collisions). Opt-in fixed port via
  `vidocq.dev.postgres.<name>.port` + `vidocq.dev.reuse=true` to keep container and port
  stable across runs (persistent external connection). Fixed-port collision fails fast with a
  clear message + override hint.

## 8. `DEV_SERVICES.md` (vidocq root)

Principle (providers on the plugin classpath, AOT-safe) · Postgres + Keycloak · published
keys · `vidocq.dev.*` overrides · **multi-datasource** (`@VidocqDataSources` /
`vidocq.properties` + `vidocq.pool.<name>.*` + `@Repository(dataStore)` +
`@Inject @Named`) · **connection info** (console + coords file, fixed-port/reuse) · debug
5005 · pitfalls (Docker daemon resolution → `~/.testcontainers.properties`).

## 9. PR breakdown

**Single PR on `vidocq`** (mansart untouched, so no mansart PR): extension api
(`@VidocqDataSources`, registry, base holder) + new `-codegen` APT + named-pool runtime +
N-container `PostgresDevService` + console/coords/port + `DEV_SERVICES.md`. The plan may
stage it internally (api+codegen+registry first, then devservice, then docs).

## 10. Testing strategy

- **Codegen**: unit-test holder generation from `@VidocqDataSources` and from a sample
  `vidocq.properties` (via an in-process `JavaCompiler`); dedup across both sources.
- **Runtime**: a unit test on `MansartPoolExtension` proving two `vidocq.pool.<name>.url`
  names are discovered, build distinct pools (isolation: a table created in one is invisible
  from the other) and are registered/unregistered in `NamedDataSourceRegistry`. The full
  `@Inject @Named` **and** `@Repository(dataStore)` CDI E2E lives in the example module, where
  the codegen-generated holders, Vauban discovery and mansart-data routing converge.
- **DevService**: pure unit tests for the provisioning *plan* (which datasources, key prefixes,
  per-name/global overrides) and for `DevServicesReport` (grouping, host/port parsing, console
  block, coords file); a Docker-gated IT starts two containers and asserts distinct reachable
  pools. `vidocq.dev.postgres.datasources=a,b` → two containers, namespaced keys.

## 11. Decisions log

- Need = generic capability, AOT-friendly.
- Isolation = one container per datasource.
- Names = **derived** (`vidocq.pool.<name>.*`) + `@VidocqDataSources`; no enumeration property.
- Declaration follows Quarkus: structure build-time (two sources: static properties +
  annotation), values runtime via MP Config.
- Publication = compile-time codegen holders backed by `NamedDataSourceRegistry`; mansart
  untouched; **all mechanism lives in the Vidocq extension**. (Intended Vauban-untouched too —
  one small Vauban CDI fix proved necessary, see §12.)
- Connection info = console block ("Connection information", not tool-specific) **and**
  `target/vidocq-dev-services.properties`; port random default, fixed+reuse opt-in.
- One PR on vidocq **plus** one small Vauban fix (separate repo/branch, see §12).

## 12. Implementation notes (findings while building)

Two non-obvious things surfaced only when the example exercised the whole chain under Vauban
(not Weld), and are worth recording:

1. **`@Named`-only beans pollute `@Default`.** CDI 4.1 §2.5.2 assumes `@Default` for a bean whose
   only qualifier is `@Named`, so a generated `@Named("X")` `DataSource` holder would also answer
   an unqualified `@Inject DataSource` — ambiguous with the `@Default` pool. Fix: the codegen marks
   every holder with a no-value `@ManagedDataSource` qualifier (in the mansart-pool API) so it stays
   out of the `@Default` candidate set, while `@Inject @Named("X")` and `@Repository(dataStore)`
   still select it.

2. **Vauban added `@Default` to `@Named` *injection points* too** (`QualifierResolver`), which is
   the bean-side rule, not the injection-point rule (§5.2.2): a `@Named("X")` injection point is
   already qualified. With the marker in place this made the holder unsatisfiable. Fixed in
   vauban-core (treat any qualifier, including `@Named`, as explicit at an injection point) on
   branch `fix/named-injection-point-default-qualifier`. **The example build depends on that fix.**

3. **Generated holders must `implements javax.sql.DataSource` directly.** The Vauban compile-time
   indexer reads a bean's types from its own source element and does not walk the interfaces of an
   *external* abstract superclass, so `extends AbstractNamedDataSourceHolder` alone left the holder
   with no `DataSource` bean type. The codegen now emits the interface directly (impls stay
   inherited).
