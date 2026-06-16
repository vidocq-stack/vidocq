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
   `putIfAbsent`, so an explicit `-D` or `vidocq.dev.systemProperties` entry always wins).

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
coordinates the dev services published, host and port parsed from the JDBC URL.

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
