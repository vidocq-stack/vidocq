# Vidocq Dev Services

Zero-config external dependencies (a PostgreSQL database, a Keycloak identity provider, …)
for `vidocq:dev`, inspired by Quarkus Dev Services. You run `mvn vidocq:dev`, and the
services you need are provisioned, wired into the application, and torn down on exit — no
`docker run`, no hand-copied JDBC URLs.

> Looking for the live-reload / JDWP debugging side of `vidocq:dev`? See **`DEBUGMODE.md`**.
> This document covers the *services* a dev session provisions.

## How it works

A **dev service** is a `DevService` SPI provider (`vidocq-runtime-devservices-spi`). Providers
are discovered by `ServiceLoader` **on the Maven plugin classpath** — fed by your project's
`<plugin><dependencies>` — and run **inside the Maven JVM**, never inside the forked
application JVM. Each provider:

1. decides whether it `appliesWhen(...)` (it opts out when you configured the dependency
   yourself),
2. provisions the service (a Testcontainers container) and returns a map of `key=value`
   coordinates,
3. those coordinates are injected into the child JVM as `-D` system properties (folded with
   `putIfAbsent`, so an explicit `-D` or `vidocq.dev.systemProperties` entry always wins),
4. and each key the child does get from a provider is marked with
   `-Dvidocq.dev.provided.<key>=<provider id>`, for example
   `vidocq.dev.provided.vidocq.pool.audit.url=postgres`. The application cannot otherwise tell a
   dev-service datasource from a hand-set one: the Mansart pool extension reads the marker to show
   `dev service postgres, localhost:54219` in the startup report and the dev console. A key an
   explicit value kept is not marked, and the marker names the provider only, never a value
   (`vidocq.dev.*` keys are exempt from the configuration key audit).

This keeps the heavy machinery (Testcontainers, the Docker client) entirely **off the runtime
module-path** and out of the AOT / native image — the application module-path is identical to
production.

Providers start **once**, before the first child fork, and survive source reloads: a
recompile/restart respawns the child but never touches the running containers. They stop once,
when the session ends (Ctrl+C).

Disable all of it with `-Dvidocq.dev.devServices=false`.

## Bundled providers

| Provider | Artifact | Publishes |
|----------|----------|-----------|
| PostgreSQL | `vidocq-runtime-devservice-postgres` | `vidocq.pool[.<name>].url` / `.username` / `.password` |
| Keycloak | `vidocq-runtime-devservice-keycloak` | OIDC issuer / auth-server coordinates |

Add the provider to the **plugin**'s `<dependencies>` (not the project's):

```xml
<plugin>
  <groupId>io.vidocq.runtime</groupId>
  <artifactId>vidocq-runtime-maven-plugin</artifactId>
  <dependencies>
    <dependency>
      <groupId>io.vidocq.runtime</groupId>
      <artifactId>vidocq-runtime-devservice-postgres</artifactId>
      <version>${vidocq.version}</version>
    </dependency>
  </dependencies>
</plugin>
```

### Opt-out semantics

A provider only applies when the dependency is **not explicitly configured**. For Postgres,
the `@Default` datasource is skipped when `vidocq.pool.url` is set **as an explicit `-D`, an
environment variable, or in the dev-goal configuration** — a baked-in default in
`vidocq.properties` does **not** count, so your production default URL never suppresses a dev
container. Point dev mode at your own database by passing `-Dvidocq.pool.url=…` (the container
is then not started, and your URL is used as-is).

## Multi-datasource

An application can use several datasources — a `@Default` plus any number of named ones:

```java
@Inject @Named("analytics") DataSource analytics;        // injected named DataSource

@Repository(dataStore = "analytics")                     // repository routed to it
interface PageViewRepository extends CrudRepository<PageView, Long> { }
```

Three pieces cooperate:

### 1. Declare the names (build-time)

Names must be visible **at compile time** so the `@Named` beans can be generated (AOT-friendly,
no runtime reflection). Declare them either way — or both, they are merged and de-duplicated:

- **Properties** — a `vidocq.pool.<name>.url` key in `vidocq.properties` (or
  `application.properties`), the Vidocq config files the build can see:

  ```properties
  # src/main/resources/vidocq.properties
  vidocq.pool.analytics.url=jdbc:postgresql://localhost:5432/analytics
  vidocq.pool.audit.url=jdbc:postgresql://localhost:5432/audit
  ```

- **Annotation** — `@VidocqDataSources` on any type, package or module, for names that come
  only from dynamic sources (env, system properties) the build cannot see:

  ```java
  @VidocqDataSources({"analytics", "audit"})
  module com.example.app { }
  ```

> Names are **single-segment** (`analytics`, not `a.b`). The `@Default` `vidocq.pool.url` has
> no name and is always present when set.

### 2. Wire the codegen (build-time)

Add the **optional** processor to `annotationProcessorPaths` — it generates one
`@Named("<name>") @Singleton` `DataSource` holder per datasource:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
        <artifactId>vidocq-runtime-mansart-pool-datasources-codegen</artifactId>
        <!-- version managed by vidocq-runtime-parent -->
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

The generated holders are ordinary `@Named @Singleton` beans, discovered by Vauban like any
other bean — no extra configuration. (This artifact is deliberately **not** named
`*-extension-codegen`, so it is *not* forced on every pool consumer; declare it only when you
use named datasources.)

### 3. Runtime

At boot, `MansartPoolExtension` opens one connection pool per `vidocq.pool.<name>.url`
(the same `vidocq.pool.*` tunables apply, prefixed `vidocq.pool.<name>.`) and registers it in
`NamedDataSourceRegistry`. The generated `@Named` holder forwards to that pool; `mansart-data`
routes `@Repository(dataStore = "<name>")` to the same `@Named` bean. The `@Default` pool is
unchanged.

### 4. Dev containers for the named datasources

The dev service **creates** the URLs, so it cannot guess the names — list them explicitly:

```properties
# a dev-only value (e.g. a dev profile, or -Dvidocq.dev.postgres.datasources=…)
vidocq.dev.postgres.datasources=analytics,audit
```

This starts **one container per datasource** (plus the `@Default`) and injects
`vidocq.pool.analytics.url=…`, `vidocq.pool.audit.url=…` as `-D` into the child — overriding any
default baked into `vidocq.properties`.

Overrides, globally via `vidocq.dev.postgres.` and per datasource via
`vidocq.dev.postgres.<name>.`:

| Key suffix | Default |
|------------|---------|
| `image` | `postgres:16-alpine` (global default applies to every datasource) |
| `db` | `vidocq` for the `@Default`, the datasource name otherwise |
| `username` / `password` | `vidocq` / `vidocq` |
| `port` | random host port — set a value to pin it (see below) |

## Connection information

When dev services start, the connection coordinates of every provisioned datasource are
**printed to the console** and **written to a file**, so any external tool — DataGrip, `psql`,
a migration runner, a script — can reach the dev databases:

```
Connection information:
  Datasource: default
    JDBC URL : jdbc:postgresql://localhost:54213/vidocq
    Host     : localhost
    Port     : 54213
    Username : vidocq
    Password : vidocq
  Datasource: analytics
    JDBC URL : jdbc:postgresql://localhost:54219/analytics
    ...
```

The same coordinates land in **`target/vidocq-dev-services.properties`** (rewritten on each
`vidocq:dev` start):

```properties
datasource.default.url=jdbc:postgresql://localhost:54213/vidocq
datasource.default.host=localhost
datasource.default.port=54213
datasource.default.username=vidocq
datasource.default.password=vidocq
datasource.analytics.url=jdbc:postgresql://localhost:54219/analytics
...
```

The block and the file are **provider-agnostic**: they render whatever `vidocq.pool[.<name>].*`
coordinates the dev services published, host and port parsed from the JDBC URL (any user info in
the URL is left out of the host).

### Stable port for external tooling

By default each container gets a **random** host port, so the coordinates change on every
restart. To keep a tool connected across restarts, pin a **fixed port** and enable container
**reuse**:

```properties
vidocq.dev.postgres.port=55432                 # @Default on a fixed port
vidocq.dev.postgres.analytics.port=55433       # named datasource on its own fixed port
vidocq.dev.reuse=true                          # keep the container between sessions
```

Container reuse also requires `testcontainers.reuse.enable=true` in your
`~/.testcontainers.properties`. A fixed port that is already taken fails the start with a clear
Docker bind error.

## Docker daemon resolution

Testcontainers must find your Docker daemon. With Docker Desktop the default socket usually
works out of the box; with **OrbStack**, **Colima**, **Podman** or a remote daemon you may need
`~/.testcontainers.properties`:

```properties
# point at a specific daemon
docker.host=unix:///Users/me/.orbstack/run/docker.sock
# …or let Testcontainers auto-detect
# docker.client.strategy=org.testcontainers.dockerclient.UnixSocketClientProviderStrategy

# disable reuse unless you opt in per-session
testcontainers.reuse.enable=false
```

If `vidocq:dev` aborts with *"Could not find a valid Docker environment"*, this file is the
first thing to check.

## Disabling

| What | How |
|------|-----|
| All dev services | `-Dvidocq.dev.devServices=false` |
| One datasource (use your own DB) | set its `vidocq.pool[.<name>].url` explicitly (`-D` / env) |
| Container reuse | `vidocq.dev.reuse=false` (default) |

## Schema migrations

Schema migration runs **at boot**, before any connection pool opens, so the schema is
always ready before your application code sees it. It is purely opt-in: add one backend
module to your project dependencies and the migration runs automatically; omit both and
nothing changes.

### How it works

`MigrationExtension` is a `VidocqExtension` with priority **150** — after the Chappe
transport (100), before the Mansart pool (200). On startup it reads the
`vidocq.pool[.<name>].url|username|password` coordinates already present in your
configuration, constructs a JDBC connection inside the chosen engine (Flyway or Liquibase),
and applies all pending migrations. No runtime `DataSource` is required — the migration
extension opens its own short-lived connection and releases it before the pool starts.

If migration fails, the boot **aborts immediately** (fail-fast): no partially-migrated
database can be reached by a running application.

### Add a backend module

Add **one** of the two backend modules to your project's `<dependencies>`:

```xml
<!-- Flyway (Apache 2.0) -->
<dependency>
  <groupId>io.vidocq.runtime.extensions.essentials</groupId>
  <artifactId>vidocq-runtime-flyway-migration-extension</artifactId>
</dependency>

<!-- — OR — Liquibase (Apache 2.0) -->
<dependency>
  <groupId>io.vidocq.runtime.extensions.essentials</groupId>
  <artifactId>vidocq-runtime-liquibase-migration-extension</artifactId>
</dependency>
```

If both are present, set `vidocq.migration.engine=flyway` or `=liquibase` to
disambiguate; the extension throws at boot if it finds two backends and no engine selector.

### Script locations

| Engine | Default location | File naming |
|--------|------------------|-------------|
| Flyway | `classpath:db/migration` | `V1__description.sql`, `V2__…` |
| Liquibase | `classpath:db/changelog/db.changelog-master.xml` | XML / YAML / JSON changelog |

Place your scripts under `src/main/resources/` so Maven packages them into the JAR:

```
src/main/resources/
  db/migration/
    V1__create_products.sql
    V2__add_index.sql
```

### JPMS — required `opens` in `module-info.java`

Migration scripts are **resources inside a named module** and are subject to JPMS
encapsulation. The extension cannot open your module for you. You must add an `opens`
directive for every package that contains migration resources:

```java
module com.example.app {
    // Flyway reads scripts from this package at runtime
    opens db.migration;

    // Liquibase changelog (adjust to match your package structure)
    // opens db.changelog;
}
```

Without this directive, in a launch that leaves the application module in the JVM's boot layer
(`-Dvidocq.dev.layer=false` or `vidocq.package.layer=false` for an application without a `@VidocqMain`
trampoline; a trampoline re-layers the application in `Vidocq.run()`), Flyway/Liquibase cannot discover
the scripts and the migration appears to apply zero changes, silently leaving your schema empty. Flyway
also needs it to instantiate Java migrations, in every launch.

A second cause, fixed by Vidocq/vidocq#96 (0.4.0): `vidocq:dev`, `vidocq:run` and the launcher
`vidocq:package` writes by default boot the application in a module layer of its own, whose class loader
lists no directory. Before the fix the backends found no script there **even with** `opens db.migration`;
they now list `classpath:` locations from the application's modules. Either way, a datasource that ends its
migration with no migration found and none in its schema history is reported at boot as the warning
`VIDOCQ-MIG-001` (see `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`, "Schema migration").

### Configuration keys

All keys live under the `vidocq.migration.` prefix. JDBC coordinates are **reused** from
the matching `vidocq.pool[.<name>].*` keys — you do not repeat them.

| Key | Default | Description |
|-----|---------|-------------|
| `vidocq.migration.enabled` | `true` | Set to `false` to skip all migrations at boot |
| `vidocq.migration.engine` | _(auto)_ | `flyway` or `liquibase` — required only when both backends are on the classpath |
| `vidocq.migration.locations` | engine default | Override the `@Default` datasource script location(s), comma-separated. Read from every source (`vidocq.properties`, `-D`, `VIDOCQ_MIGRATION_LOCATIONS`); before Vidocq/vidocq#96 only the environment variable worked, the other two stopped the boot with a `StringIndexOutOfBoundsException` |
| `vidocq.migration.failOnMissingLocations` | `false` | `true`: a Flyway location that holds no script stops the boot (`Unable to resolve location …`) instead of migrating nothing. Liquibase always stops on a missing changelog |
| `vidocq.migration.<name>.locations` | — | Opt-in migration of a **named** datasource; the named datasource is **not** migrated unless this key is set |

### Default vs named datasources

- The **`@Default`** datasource (`vidocq.pool.url`) is migrated automatically whenever
  `vidocq.pool.url` is set and `vidocq.migration.enabled` is `true`.
- **Named** datasources (`vidocq.pool.<name>.url`) are **opt-in**: set
  `vidocq.migration.<name>.locations` to enrol a named datasource in migration. This
  avoids accidentally migrating read-replica or analytics datasources that you manage
  separately.

### Real example — `vidocq-runtime-mansart-h2-example`

The bundled example (`vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`) uses
Flyway to create the `products` table at boot:

```
src/main/resources/db/migration/V1__products.sql
```

It declares `opens db.migration;` in its `module-info.java` and sets
`vidocq.pool.url=jdbc:h2:mem:mansart` in its configuration. No further migration
configuration is needed — the extension picks up the `@Default` pool coordinates and
applies `V1__products.sql` before the Mansart pool opens.
