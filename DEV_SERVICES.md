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
   yourself, or when the application does not use what it provides) and, when it does not start,
   may say why through `skipReason(...)` — one line, no secret — which the host logs as
   `DevService '<id>' not started: <reason>`, keeps in the state file and shows in the startup
   report,
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

Besides `property(key)` — the explicit sources, then the `vidocq.dev.*` keys of the application's
files — the `DevServiceContext` a provider gets has two `default` methods, which every host of this
repository implements (an older one answers "unknown": empty, `false`):

- `applicationProperty(key)` — any key of the application's own files (`vidocq.properties`,
  `application.properties`, the external configuration directory), to learn what the application
  is configured for, such as the database a URL names. Never a reason to switch a service off in
  place of `property(key)`.
- `onApplicationClasspath(className)` — whether the application's class path, as its launch will
  see it (runtime dependencies for `vidocq:dev` and `vidocq:run`, test dependencies for
  `vidocq:test` and a JUnit run), holds that class, found as a `.class` entry, never loaded.

Providers start **once**, before the first child fork, and survive source reloads: a
recompile/restart respawns the child but never touches the running containers. They stop once,
when the session ends (Ctrl+C).

Disable all of it with `-Dvidocq.dev.devServices=false`, or with `vidocq.dev.devServices=false`
in `vidocq.properties` (see [Switching dev services on and off](#switching-dev-services-on-and-off)).

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
environment variable, or in the dev-goal configuration**. Point dev mode at your own database by
passing `-Dvidocq.pool.url=…` (the container is then not started, and your URL is used as-is).

A provider also stays off for an application that does not use what it provides. The Postgres
provider reads the URL your `vidocq.properties` (or `application.properties`, or the external
configuration directory) gives, for the `@Default` datasource and then for each name of
`vidocq.dev.postgres.datasources`, in this order:

1. an explicit URL (`-D`, environment variable, goal configuration): no container;
2. a file URL starting with `jdbc:` but not `jdbc:postgresql:` (whatever the case), such as
   `jdbc:h2:mem:app`: no container, and the log says
   `DevService 'postgres' not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`;
3. a file URL `jdbc:postgresql:…`, or a wrapper driver's in front of it such as
   `jdbc:otel:postgresql:…` or `jdbc:p6spy:postgresql:…` (not Testcontainers' `jdbc:tc:`): a
   container, whose URL replaces the file's under the dev host — the file's URL is the production
   one, and never switches the dev container off;
4. no URL anywhere, or a value that is not a readable `jdbc:` URL (such as `${db.url}`,
   `jdbc:${db.kind}://…` or `jdbc:` alone): a container only
   when `org.postgresql.Driver` is on the application's class path (runtime dependencies for
   `vidocq:dev`/`vidocq:run`, test dependencies for `vidocq:test` and a JUnit run); otherwise
   `not started: no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`.

A reason names the key and the URL's scheme only, never its host, user or password; with several
datasources and none started, the reasons are joined with `; `. It is kept in the state file
(`"skipped"`) and shown as a `postgres` row, `not started: …`, in the startup report and the dev
console's *Dev services* panel; with nothing started, the summary reads `no dev service started`.

The file is read from `target/classes`, or from `src/main/resources` under `vidocq:dev` on a tree
not built yet; an edit since the last build is read as built. When some datasource starts and
another does not, the log says why for the other (`Postgres dev service: datasource 'audit' not
started: …`). `vidocq:test` resolves the test dependencies at start, dev services on or off.

**Upgrading:** an application that relied on the container with no URL at all and the PostgreSQL
driver in *test* scope only no longer gets one under `vidocq:dev` and `vidocq:run` (they look at
the runtime class path). Write its production URL in `vidocq.properties`
(`vidocq.pool.url=jdbc:postgresql://…`): rule 3 gives the container back.

## Where configuration comes from

A provider's keys fall into two kinds, and only the second one is read from your project's own files:

| Kind | Examples | Sources |
|------|----------|---------|
| **Opt-out keys** — decide whether a service starts at all | `vidocq.pool[.<name>].url`, `mp.jwt.verify.issuer` | An explicit `-D`, an environment variable, or the dev-goal configuration. **Never** `vidocq.properties` or `application.properties`. |
| **Tuning keys** — say how to start it, once a service is already going to start | everything under `vidocq.dev.` — `vidocq.dev.postgres.port`, `vidocq.dev.postgres.datasources`, `vidocq.dev.keycloak.realm`, `vidocq.dev.reuse`, `vidocq.dev.devServices` | A `-D`, an environment variable, the dev-goal configuration, **and now also `vidocq.properties`/`application.properties`** (read from `target/classes`, i.e. after resource filtering, the same way the runtime itself reads them) |

This split exists so that a baked-in default never silently switches a dev container off: a
`vidocq.pool.url=jdbc:postgresql://prod-host/app` your production `vidocq.properties` already
carries must never suppress the Postgres dev service just because the file happens to be on the
build's classpath. `vidocq.pool.url` (and any other opt-out key) therefore switches a provider off
only from an explicit `-D`, an environment variable or the dev-goal configuration, exactly as
before. A provider may still **read** the file to learn what the application is configured for:
the Postgres provider reads the file's URL for its database kind only (see
[Opt-out semantics](#opt-out-semantics)) — an H2 or MySQL URL there means the application is not on
PostgreSQL, while a PostgreSQL one still gets its container. The `vidocq.dev.*` tuning keys are read
from `vidocq.properties` as before, which is what makes the stable-port example below, and the
file-based `vidocq:run` opt-in further down, work.

Resolution order for a `vidocq.dev.*` key, first match wins: the goal's own `-D`/configuration,
then `vidocq.dev.systemProperties`, then an earlier provider's own output, then the host JVM's
system properties, then its environment variables, then `vidocq.properties`/`application.properties`.
A value that is a `${…}` expression (Ravel/MicroProfile Config is not emulated here) is passed to
the provider exactly as written; a provider that cannot parse it — a non-numeric port, say — names
the offending key and its literal value in the error, instead of a bare `NumberFormatException`.

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
**reuse** — these are tuning keys (see [Where configuration comes from](#where-configuration-comes-from)
above), so this now works written straight into `src/main/resources/vidocq.properties`, no `-D`
needed on every `vidocq:dev`/`vidocq:run` invocation:

```properties
# src/main/resources/vidocq.properties
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
| All dev services under `vidocq:dev` | `-Dvidocq.dev.devServices=false`, or `vidocq.dev.devServices=false` in `vidocq.properties` |
| One datasource (use your own DB) | set its `vidocq.pool[.<name>].url` explicitly (`-D` / env) |
| Container reuse | `vidocq.dev.reuse=false` (default) |

Under `vidocq:run`, dev services are off by default in the first place — see below.

### Switching dev services on and off

Every host — `vidocq:dev`, `vidocq:run` and the JUnit listener — reads `vidocq.dev.devServices`
the same way, first match wins:

1. **the explicit value**: a `-Dvidocq.dev.devServices=…`, the goal's `<devServices>`
   configuration, or (under the JUnit host) the system property;
2. **then the application's files**: `vidocq.dev.devServices` in `vidocq.properties` or
   `application.properties` (it is a tuning key, see
   [Where configuration comes from](#where-configuration-comes-from));
3. **then the host's default**: on for `vidocq:dev` and tests, off for `vidocq:run`.

The value is `true` or `false`, trimmed, in any case; anything else fails the goal (or the test
run) with a message naming the key and the value. So a `vidocq.properties` that says `true`
opts every `vidocq:run` into dev services, and `-Dvidocq.dev.devServices=false` still turns them
off for one invocation — and the other way round.

## vidocq:run

`vidocq:run` launches the application the way the production launcher does — starting a
container unasked would break that promise — so, unlike `vidocq:dev`, it starts **no** dev
service by default. Opt in with the same key dev services already use, either as a `-D` or,
since it is a tuning key (see [Where configuration comes from](#where-configuration-comes-from)),
written into `vidocq.properties`:

```bash
mvn vidocq:run -Dvidocq.dev.devServices=true
```

```properties
# src/main/resources/vidocq.properties
vidocq.dev.devServices=true
```

Both goals share the same machinery from here on: the same providers, the same "Connection
information" console block and `target/vidocq-dev-services.properties` file, the same state file
for the application to read (see [The state file](#the-state-file)), and the same
`vidocq-runtime-devservices-extension` added to the child's module path. Ctrl+C — or the process
exiting on its own — stops the containers exactly once, whichever of the shutdown hook or the
goal's own cleanup gets there first.

**Turning it back off.** A `vidocq.properties` that says `vidocq.dev.devServices=true` is
overridden for one invocation by `mvn vidocq:run -Dvidocq.dev.devServices=false`: the explicit
value comes first (see [Switching dev services on and off](#switching-dev-services-on-and-off)).

## In tests

A test that only wants a database uses the JUnit host: add the listener and the provider it
needs, both `test` scope:

```xml
<dependency>
  <groupId>io.vidocq.runtime</groupId>
  <artifactId>vidocq-runtime-devservices-junit</artifactId>
  <version>${vidocq.version}</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.vidocq.runtime</groupId>
  <artifactId>vidocq-runtime-devservice-postgres</artifactId>
  <version>${vidocq.version}</version>
  <scope>test</scope>
</dependency>
```

`vidocq-runtime-devservices-junit` registers a JUnit Platform `LauncherSessionListener` (no
annotation needed in your tests) that starts the providers found on the test class path **once**,
before any test class runs, and stops them once the whole test run is over — one set of
containers for the whole suite. It injects each provider's coordinates as system properties
(`vidocq.pool.url`, `.username`, `.password`, …) before the first test runs, so any
`VidocqBootstrap.create().configure().start()` in any test sees them, and any test that only
needs the database can query it with plain `java.sql`, without booting Vidocq at all.

Opt out with the same key as everywhere else, `vidocq.dev.devServices=false` — a `-D` on the
Surefire command line, or in your project's own files, since it is a tuning key; the explicit
value wins over the files, as for the goals above.

**A hard requirement, not a suggestion.** The listener, and any `LauncherSessionListener` your own
project adds the same way, must ship as its **own separate jar** — a `test`-scope dependency your
`module-info.java` does not `requires` — and never be compiled straight into your module's own
`src/test/java`. Surefire's default module-path `--patch-module` folds your project's *entire*
`target/test-classes` tree into your named module, `module-info` or not; a listener that landed
there would be invisible to `ServiceLoader`, which only honours an explicit `provides … with …` in
a named module, never a `META-INF/services` file. Kept as a separate jar, `vidocq-runtime-devservices-junit`
lands on the class path instead, in the unnamed module, exactly where `ServiceLoader` finds its
`META-INF/services` entry — proven with a throwaway spike, both ways, before this was shipped.
This is also why no `<useModulePath>false</useModulePath>` is needed in your `pom.xml`: the
default module-path Surefire configuration already does the right thing.

If Docker (or Podman) is not reachable, the listener fails the whole test run fast, naming the
container runtime it looked for and the key that switches dev services off — a test that needed
the database would have failed anyway, so failing at the very start says why immediately instead
of timing out on the first query.

## The state file

Whichever host started them — `vidocq:dev`, `vidocq:run`, or the JUnit listener above — writes
`<basedir>/target/vidocq-dev-services.json` once the providers are up, and rewrites it with
`"state": "stopped"` once they stop. **A custom `<project.build.directory>` does not move it**:
the path is always `<basedir>/target/vidocq-dev-services.json`, `<basedir>` meaning the project's
own `pom.xml` directory, never `${project.build.directory}`.

This file is how `vidocq-runtime-devservices-extension` (on the child's module path under the
Maven-plugin hosts, on the class path under the JUnit host — an application declares nothing)
shows the `devservices` section of the startup report and, in a `dev` launch, the dev console
panel of the same name: what started, its image and endpoints, and which keys it injected. A
password, or any key whose last segment names a secret, is **never** written to this file — it
shows as `configured` instead. A JDBC URL's `user:password@` and any secret query parameter are
stripped the same way. Without the file (or without the `vidocq.devservices.state` system property
that points to it), the section simply reads "no dev service" — the extension never scans for a
stray file on its own.

**One project, one state file.** The path is fixed by the project, not by the host, so every host
of the same project shares it — and `target/vidocq-dev-services.properties` too. Running `mvn test`
while `vidocq:dev` runs in another terminal starts the test run's own containers and overwrites
both files with the test host's; when the test run ends, it marks the state file `stopped` with
host `test`, although `vidocq:dev`'s containers still run. The running `vidocq:dev` application
keeps what it read at boot, but a reload reads the file again and then shows the test run's. Restart `vidocq:dev`, or
avoid running both at once, when the files must describe it.

**`target/vidocq-dev-services.properties` is a different file, on purpose.** It is the one
described in [Connection information](#connection-information) above: local to the machine,
never sent anywhere, and it **does** keep every password in clear text — that is what lets an
external tool (`psql`, DataGrip, a migration runner) connect to the dev database without you
copying a JDBC URL by hand. Only the JSON state file, the one the application itself reads, masks
secrets.

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

## Continuous testing (#122)

`vidocq:dev` (with `vidocq.dev.continuousTesting`, on when `src/test/java` exists) and `vidocq:test` run the
application's tests on every change. The tests reuse the dev containers: `vidocq:dev` hands every run its
session's keys, `vidocq:test` opens its own session (host `vidocq:test`), and every run gets
`-Dvidocq.dev.devServices=false`, so the JUnit host starts nothing. The tests share the dev database. See
`docs/en/modules/ROOT/pages/continuous-testing.adoc`.
