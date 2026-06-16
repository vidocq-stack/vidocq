# Design — Schema migration extensions (SPI + Flyway + Liquibase)

## 1. Context

Vidocq ships **no** schema-migration extension. Every application wires it by hand:

- **Arago** has a `FlywayMigrator` (`@Observes @Initialized(ApplicationScoped.class)` → `Instance<DataSource>.get()` → `Flyway.configure().migrate()`). Its own Javadoc says: *"Vidocq ships no Flyway extension; this is the deliberate manual wiring."*
- The runtime **examples** use a `SchemaInitializer` that runs raw JDBC DDL at boot.

This is recurring boilerplate that belongs in a runtime extension. We add a small migration SPI plus two opt-in backend implementations (Flyway, Liquibase), and adopt it in an example.

## 2. Goal & scope (validated with the maintainer)

- **SPI (zero-dep) + Flyway + Liquibase**, both as opt-in extension modules. No built-in "plain SQL"
  zero-dep runner in this PR.
- **Multi-datasource aware** (the just-shipped feature): the `@Default` pool is migrated by default;
  a **named** datasource is migrated **only when its migration location is configured** (opt-in) — a
  named datasource without scripts must not fail the boot.
- **One backend per app.** If both Flyway and Liquibase are on the path, `vidocq.migration.engine`
  disambiguates; otherwise fail fast.
- Adopt the extension in the **`mansart-h2-example`** (drop its `SchemaInitializer`) as the E2E demo.
- **Out of scope / follow-ups**: migrating Arago's `FlywayMigrator` (separate repo, separate PR); a
  zero-dep plain-SQL runner; `validate`/`repair`/`clean` commands (migrate-only here); non-JDBC targets.

## 3. Approach (chosen: A — shared SPI + one runner)

**Key insight**: Flyway and Liquibase take **JDBC coordinates** (url / user / password) directly and
manage their own connection. So the migration extension does **not** need the runtime `DataSource` — it
reads `vidocq.pool[.<name>].url|username|password` from configuration and migrates **before** the pool
opens. This fully decouples it from `mansart-pool` and works as-is with the DevServices (which inject
those `vidocq.pool.*` keys as `-D` into the child JVM).

Rejected: B (a full `VidocqExtension` per backend — duplicates config/multi-DS/lifecycle); C (a CDI
`@Observes @Initialized` bean per backend — runs after full boot, couples to CDI, observer races).

## 4. Modules (3) — under `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/`

### 4.1 `vidocq-runtime-migration-extension` (zero-dep) — SPI + runner

Exports the SPI and provides the runner `VidocqExtension`. Deliberately **not** paired with a
`*-extension-codegen` artifact, so the `checkpom` companion convention does not fire (same as
`chappe-webserver-extension`).

```java
public interface SchemaMigrator {
    String engine();                                  // "flyway" | "liquibase"
    MigrationResult migrate(MigrationTarget target);  // throws on failure
}
public record MigrationTarget(String dataSourceName,  // "default" | "<name>"
                              String jdbcUrl, String username, String password,
                              List<String> locations) {}
public record MigrationResult(int applied, String version) {}
```

`MigrationExtension implements VidocqExtension` — `name() = "migration"`, `priority() = 150` (after the
Chappe transport at 100, **before** the Mansart pool at 200, well before mansart-data at 300):

- `configure(VidocqConfiguration)` — read `vidocq.migration.*`; build the ordered `MigrationTarget`
  list: the `@Default` (from `vidocq.pool.url`, using `vidocq.migration.locations` or the engine
  default) whenever `vidocq.pool.url` is set, plus each named datasource that **explicitly** sets
  `vidocq.migration.<name>.locations`. Resolve the backend via
  `ServiceLoader<SchemaMigrator>`: 0 providers + targets present → fail fast; 1 → use it; 2+ →
  `vidocq.migration.engine` selects by `engine()`, else fail fast.
- `beforeStart(VaubanContainerBuilder)` — for each target, `migrator.migrate(target)`; log the
  `MigrationResult`. A failure throws and aborts the boot (fail fast). Adds no bean.
- No `DataSource` dependency, no coupling to `MansartPoolHolder`.

### 4.2 `vidocq-runtime-flyway-migration-extension`

`provides SchemaMigrator` via Flyway: `Flyway.configure(cl).dataSource(url, user, password)
.locations(target.locations()).load().migrate()`. Depends on the SPI module + `flyway-core` +
`flyway-database-postgresql` (Apache 2.0).

### 4.3 `vidocq-runtime-liquibase-migration-extension`

`provides SchemaMigrator` via Liquibase (`target.locations().get(0)` = changelog path). Depends on the
SPI module + `liquibase-core` (Apache 2.0). **A Liquibase + JPMS module-path POC is the very first
implementation step** — Liquibase scans the classpath and uses ServiceLoader heavily and is the
finicky one on the module path.

## 5. Configuration (prefix `vidocq.migration.`)

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | master switch |
| `engine` | — | `flyway` \| `liquibase`; required only when both jars are present |
| `locations` | Flyway `classpath:db/migration` / Liquibase `db/changelog/db.changelog-master.xml` | `@Default` scripts |
| `<name>.locations` | — | **opt-in** migration of named datasource `<name>` |

JDBC coordinates are **reused** from `vidocq.pool[.<name>].url|username|password` — nothing to redeclare.

## 6. JPMS

- `migration-extension/module-info`: `requires transitive io.vidocq.runtime.spi; requires java.sql;
  exports io.vidocq.runtime.extensions.essentials.migration; provides
  io.vidocq.runtime.spi.VidocqExtension with …MigrationExtension; uses …migration.SchemaMigrator`.
- Backend modules: `requires io.vidocq.runtime.extensions.essentials.migration` + the backend's
  **automatic** module (`flyway.core`, `flyway.database.postgresql`, `liquibase.core`); `provides
  SchemaMigrator with …`. Impls live in distinct sub-packages (`…migration.flyway` / `…migration.liquibase`)
  — no split package.
- **The application** must `opens db.migration;` (or its changelog package): migration scripts are
  resources in the app's named module and are encapsulated; the extension cannot open them on the app's
  behalf. Documented in `DEV_SERVICES.md`/the example. (Arago already does `opens db.migration;`.)

## 7. Error handling

- **Fail fast at boot**: a migration error throws from `beforeStart` and aborts startup with a clear
  message; the app never serves against a half-migrated schema.
- **Never** auto-`clean`/drop. `vidocq.migration.enabled=false` skips entirely.
- **Absent scripts**: Flyway tolerates an empty/absent location (0 migrations applied); Liquibase
  requires its changelog to exist. So an app that adds the Liquibase backend but provides no changelog
  fails fast at boot (a backend with no scripts is a misconfiguration) — provide a changelog or set
  `vidocq.migration.enabled=false`.
- Log migration **counts** and target version only — never SQL payloads.

## 8. Licenses

`flyway-core` + `flyway-database-postgresql` = **Apache 2.0** (community); `liquibase-core` = **Apache
2.0**. Both are permissive and combinable with Vidocq's EPL-2.0 / EUPL-1.2 / GPL-2.0+ licensing. They
are external (non-Jakarta/MP) deps — a deliberate deviation from the zero-dep rule, **mitigated** by the
Quarkus model: the SPI stays zero-dep in the runtime, and Flyway/Liquibase are pulled only by the opt-in
backend module an app actually uses. (Note: Flyway 10+ gates some databases — Oracle, DB2 — behind paid
editions; Postgres/H2/MySQL/MariaDB stay Apache 2.0.)

## 9. Testing strategy

- **migration-extension**: unit-test the runner's config → `MigrationTarget` list (default + named
  opt-in) and the backend selection (0/1/2 providers) with a fake `SchemaMigrator` — no database.
- **flyway / liquibase**: migrate a sample script on **H2 in-memory** (fast, no Docker) and a
  **Docker-gated Postgres IT**; for Liquibase, a **module-path smoke** (the POC) proving it runs as a
  JPMS automatic module.
- **example**: `mansart-h2-example` adopts the extension (drops `SchemaInitializer`), proving the E2E
  path (config → runner → backend → migrated H2) in a real Vidocq app.

## 10. PR breakdown

**One PR on `vidocq`**: SPI + runner + Flyway + Liquibase + example adoption. Internal staging:
1. SPI + runner (fake-migrator unit tests). 2. **Liquibase POC** (module-path) — validate the riskiest
backend early. 3. Flyway impl (H2 + IT). 4. Liquibase impl (H2 + IT). 5. `mansart-h2-example` adoption.
Arago's `FlywayMigrator` migration is a separate follow-up PR (different repo).

## 11. Decisions log

- Backends = Flyway + Liquibase, opt-in modules; no zero-dep plain-SQL runner.
- Multi-datasource = `@Default` by default, named datasources opt-in (per `<name>.locations`).
- Approach A: shared `SchemaMigrator` ServiceLoader SPI + one runner `VidocqExtension`, migrating from
  JDBC config coordinates at priority ~150 (before the pool), decoupled from mansart-pool.
- One backend per app; `vidocq.migration.engine` disambiguates when both are present.
- Fail fast at boot; never auto-clean.
- Example adopts it in this PR; Arago migration deferred.
