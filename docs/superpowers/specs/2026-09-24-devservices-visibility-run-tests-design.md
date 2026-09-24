# Dev services: visibility, `vidocq.properties`, `vidocq:run` and tests

- **Date**: 2026-09-24
- **Status**: Approved design (brainstorming 2026-09-24): ready for implementation planning
- **Issue**: Vidocq/vidocq#123, rewritten from "start a container for an unconfigured datasource" once it was clear
  dev services already exist
- **Scope**: `vidocq` runtime and Maven plugin. No brick changes. No new service: H2 and PostgreSQL are enough for
  now, and Keycloak stays as it is.

## 1. Context

`vidocq:dev` has had dev services since 0.2.0. The `DevService` SPI lives in `vidocq-runtime-devservices-spi`,
with two providers, `postgres` (multi-datasource) and `keycloak` (realm import), both on Testcontainers. It is
documented in `DEV_SERVICES.md`. Their design and code:

- **Where they run.** Providers run in the **Maven plugin's JVM**. The plugin realm discovers them with
  `ServiceLoader`, fed by the application's `<plugin><dependencies>`. `DevServiceManager` starts them once, before
  the first child JVM, in `order()` order, and rolls back the ones already started if one fails. It stops them in
  reverse order from the shutdown hook and from `finally` (`VidocqDevMojo`). Containers survive hot reloads and
  child respawns.
- **What they inject.** Their outputs reach the child JVM only as `-D` values (`putIfAbsent`), plus a marker
  `-Dvidocq.dev.provided.<key>=<id>`. `DevServicesReport` logs a "Connection information" block and writes
  `target/vidocq-dev-services.properties` (datasources only, passwords included).
- **When they skip.** `DefaultDevServiceContext` decides whether a service is needed from the dev seeds, earlier
  providers' outputs, system properties and environment variables. It **does not read `vidocq.properties`**, although
  `DEV_SERVICES.md` shows a stable-port example written as a properties file.

Four gaps:

1. **Invisible to the application.** No startup report section and no dev console panel. The Mansart pool line
   shows `dev service postgres, host:port` from the marker; Keycloak shows nowhere.
2. **`vidocq.properties` ignored** when deciding whether a service is needed or how to set it up.
3. **Only `vidocq:dev`.** `vidocq:run` never starts a dev service.
4. **No tests.** A test that boots the application in-JVM (`VidocqBootstrap.create().configure().start()`, as the
   examples do) has no way to get the dev database.

## 2. Decision: providers stay out of the application

Considered:

- **(A) Plugin and test hosts, with a state channel to the application. Chosen.**
- **(B) Providers as a runtime extension active in DEV and TEST, as Quarkus does.** Rejected: Testcontainers and
  docker-java would enter the application's module graph as automatic modules, against strict JPMS and the "zero
  dependency for what ships" rule. A child respawn would also lose the containers unless they are reused.
- **(C) A separate daemon owning containers for every client.** Rejected: the heaviest option, for sharing across
  sessions and applications that nobody asked for.

Under (A), the `DevService` SPI is unchanged for existing providers. Three **hosts** run the providers:
`vidocq:dev`, `vidocq:run` and a JUnit launcher. One **state file** carries what the application may see.

## 3. Units

| Unit | Module | Runs in | Responsibility |
|---|---|---|---|
| `DevService`, `DevServiceContext`, **`DevServiceState`** (new) | `vidocq-runtime-devservices-spi` | host JVM | Provider contract; `describe()` is added (§4.1) |
| **`DevServicesSession`** (new, extracted from `VidocqDevMojo`) | **`vidocq-runtime-devservices-host`** (new, class path, no Maven API) | host JVM | Resolve the context (§5), start/stop the providers through `DevServiceManager`, write the state file (§4.2) and the connection block |
| `DevServiceManager`, `DefaultDevServiceContext`, `DevServicesReport` | moved to `vidocq-runtime-devservices-host` | host JVM | As today; the context gains the configuration sources of §5 |
| `VidocqDevMojo`, `VidocqRunMojo` | `vidocq-runtime-maven-plugin` | plugin JVM | Delegate to `DevServicesSession`; add the extension to the child's module path and pass the state file path (§4.3) |
| **`DevServicesExtension`** (new) | **`vidocq-runtime-devservices-extension`** (new, JPMS, no Testcontainers, no plugin dependency) | application JVM | Read the state file once; write the `devservices` report section and dev console panel (§4.4) |
| **`DevServicesSessionListener`** (new) | **`vidocq-runtime-devservices-junit`** (new, test dependency) | test JVM | JUnit Platform `LauncherSessionListener` hosting a `DevServicesSession` for the whole test run (§7) |

Each unit is testable alone. The session needs a `DevServiceContext` and a list of providers, with fakes in tests.
The extension needs only a JSON file. The listener needs only the session.

## 4. Visibility

### 4.1 What a provider describes

`DevService` gains a default method:

```java
/** What this provider started, for the report and the console; called after start(). */
default DevServiceState describe(Map<String, String> injected) {
    return DevServiceState.minimal(id(), injected.keySet());
}
```

```java
public record DevServiceState(
        String id,                      // "postgres"
        String image,                   // "postgres:16-alpine", or null when not a container
        Map<String, String> endpoints,  // name → "host:port" or URL, e.g. "default" → "localhost:54321",
                                        // "admin" → "http://localhost:8180/admin"
        List<String> injectedKeys) {}   // the keys start() returned, sorted
```

- A third-party provider keeps compiling and gets its id and injected keys shown.
- `postgres` fills in its image and one endpoint per datasource.
- `keycloak` fills in its image, the issuer URL and the admin console URL.

### 4.2 The state file

After starting the providers, the host writes `<basedir>/target/vidocq-dev-services.json`. It rewrites the file with
`"state": "stopped"` when it stops them.

```json
{
  "host": "vidocq:dev",
  "state": "running",
  "startedAt": "2026-09-24T10:12:03Z",
  "services": [
    {
      "id": "postgres",
      "image": "postgres:16-alpine",
      "endpoints": {"default": "localhost:54321"},
      "injected": [
        {"key": "vidocq.pool.url", "value": "jdbc:postgresql://localhost:54321/app"},
        {"key": "vidocq.pool.username", "value": "app"},
        {"key": "vidocq.pool.password", "configured": true}
      ]
    }
  ]
}
```

- **Secrets never enter the file.** A key whose last segment names a secret is written as `"configured": true`,
  never with its value. The rule matches the config panel's (#116): segments ending with `password`, `passwd`,
  `pwd`, `secret`, `token`, `key`, `credential(s)`, `apikey`, `api-key` or `private-key`, in any case.
- **URL credentials are removed.** A URL value loses its `user:password@` part and has secret query parameters
  masked as `***`, before it is written.
- `target/vidocq-dev-services.properties` stays as it is, passwords included. It is the local file external SQL
  tools read, and `DEV_SERVICES.md` says it is local to the machine.
- The file is written atomically (a temporary file, then a move), so the application never reads half of it.

### 4.3 Handing it to the application

The host passes `-Dvidocq.devservices.state=<absolute path>` to the child JVM, or sets the system property under
the JUnit host. The plugin appends the `vidocq-runtime-devservices-extension` jar to the child's module path when
dev services are active. The application declares nothing. The jar is resolved as a plugin dependency, at the
plugin's version.

### 4.4 The `devservices` extension

- **Declaration.** `DevServicesExtension implements VidocqExtension, DevConsolePanel`. It is declared both as
  `provides io.vidocq.runtime.spi.VidocqExtension` in its `module-info` and in `META-INF/services`: under the plugin
  hosts it is on the child's module path, while under the JUnit host it arrives on the class path.
- **Lifecycle.** `onStart` reads and parses the
  file once into an immutable snapshot of strings, then does no more I/O. `onStop` clears the snapshot. A dev
  reload therefore rereads the file.
- **Report section `devservices`**, title `Dev services`:
  - **Summary:** `2 services: postgres (postgres:16-alpine at localhost:54321), keycloak (quay.io/keycloak/keycloak:26.0 at localhost:8180) — vidocq:dev`.
  - **DETAILED:** a row per service (image, endpoints), then a list of its injected keys. Values are shown only in a
    DEV launch; a secret always shows as `configured`, through `section.secret(key, true)`.
  - **Without a state property or file:** the summary reads `no dev service: not started by vidocq:dev, vidocq:run or
    the test launcher`. This is not an anomaly.
  - **Unreadable file:** the extension raises anomaly `VIDOCQ-DEVS-001` (`the dev services state file <path> could
    not be read: <exception class>`, hint "rerun the goal; the file is written by the Maven plugin"). The boot goes
    on.
- **Dev console panel `devservices`: boot facts only.** The application cannot reach Docker, so it cannot probe a
  container. The Keycloak admin URL is shown as text: the link SPI only builds links to the application's own
  listeners, by design (#113).
- **Mansart pool line.** It keeps reading the `vidocq.dev.provided.*` marker, unchanged. The `devservices` section
  is now the full picture, including Keycloak.

## 5. Configuration sources for the providers

`DefaultDevServiceContext` deliberately does **not** read `vidocq.properties`, and its Javadoc says why: a baked-in
default such as `vidocq.pool.url=jdbc:postgresql://localhost:5432/app` must not switch a dev service off. Only an
explicit override may, whether a `-D`, an environment variable or the dev goal's configuration. That rule stays.

The keys a provider reads fall into two kinds, and only the second gains a source:

- **Opt-out keys** decide whether a service starts: `vidocq.pool[.<name>].url` for PostgreSQL,
  `mp.jwt.verify.issuer` for Keycloak. They keep today's sources and still **ignore the application's files**.
- **Tuning keys**, everything under `vidocq.dev.`, say how to start it: e.g. `vidocq.dev.postgres.image`,
  `vidocq.dev.postgres.port`, `vidocq.dev.keycloak.realm`, `vidocq.dev.reuse`, `vidocq.dev.devServices`. They
  are also read from the application's files.

`DefaultDevServiceContext.property(key)` resolves, first match wins:

1. **Dev seeds (unchanged):** `-D` values given to the goal, then `vidocq.dev.systemProperties`, then the outputs of
   the providers started before.
2. The host JVM's system properties, then environment variables (unchanged).
3. **New, for keys starting with `vidocq.dev.` only:** the application's files, read with the runtime's own sources
   in the runtime's order:
   - `ExternalFileConfigSource`, when configured;
   - then `PropertiesFileConfigSource` over a class loader on `target/classes`, which reads `vidocq.properties` then
     `application.properties`.

   `target/classes` is read, not `src/main/resources`, because it is what the application sees after resource
   filtering. Under the JUnit host it is `target/classes` of `basedir`, not `target/test-classes`.

Effects:

- `vidocq.dev.postgres.port=55432` in `vidocq.properties` pins the port. The stable-port example of
  `DEV_SERVICES.md` becomes true.
- `vidocq.pool.url` in `vidocq.properties` still does **not** switch the PostgreSQL service off. `DEV_SERVICES.md`
  says so explicitly, and states the two kinds of keys.
- `vidocq.dev.devServices=true` in `vidocq.properties` is the file-based opt-in for `vidocq:run` (§6).
- The Javadoc of `DevServiceContext` (SPI), which wrongly says `vidocq.properties` is read, is corrected to state
  this rule.
- **Limit (documented):** Ravel (MicroProfile Config) is not emulated. Its profiles and `${…}` expressions are not
  evaluated. A `vidocq.dev.*` value that is an expression is passed as it is written, and the provider reports it
  when it cannot parse it.

## 6. `vidocq:run`

- **Off by default.** `vidocq:run` launches the application as the production launcher will; starting containers
  unasked would break that promise. It is switched on with the existing key `vidocq.dev.devServices=true`, as `-D`
  or in `vidocq.properties` (§5). In `vidocq:dev` the default stays on.
- **Same code.** Both goals use `DevServicesSession`: context, start, state file, connection block, extension on the
  module path, stop.
- **Stop.** The session stops when the child JVM exits or on Ctrl-C, through `VidocqRunMojo.await`'s `finally` and its
  shutdown hook.

## 7. Tests

- **Opting in.** An application adds `io.vidocq.runtime:vidocq-runtime-devservices-junit` with `test` scope, next to
  the providers it wants (e.g. `vidocq-runtime-devservice-postgres`, also `test` scope). The listener brings
  `vidocq-runtime-devservices-extension` transitively.
- **Discovery.** `DevServicesSessionListener implements LauncherSessionListener`, registered in
  `META-INF/services/org.junit.platform.launcher.LauncherSessionListener`. No annotation in the tests.
- **`launcherSessionOpened`:**
  - skip everything when `vidocq.dev.devServices=false` (§5 sources);
  - otherwise open a `DevServicesSession` with `basedir` = the `basedir` system property Surefire sets, else
    `user.dir`, and host name `test`;
  - start the providers found on the test class path;
  - set each injected key as a system property, unless already set;
  - write the state file and set `vidocq.devservices.state`.
- **`launcherSessionClosed`:** stop the session.
- **Scope.** One set of containers for the whole test run. Every `VidocqBootstrap.create().configure().start()` in any
  test sees them.
- **No container runtime.** When Docker or Podman is missing, the session fails fast with a message that names the
  runtime it looked for and the key that switches dev services off. A test needing the database would fail anyway;
  failing early says why.
- **Launch mode.** Unchanged: `LaunchModeResolver` already reads a JUnit/Surefire stack as TEST.

### 7.1 Risk: JPMS under Surefire, and the spike that settles it

A modular application's tests run on the module path under Surefire, which patches the test classes into the main
module. Test dependencies that the `module-info` does not require go on the class path. The patched module reads
`ALL-UNNAMED`.

So the listener, Testcontainers and the providers should work from the unnamed module, and the JUnit Platform
(`junit.platform.launcher`, which `uses LauncherSessionListener`) should find the listener's
`META-INF/services` entry through its class loader.

**Verified on 2026-09-24**, with a throwaway spike, redone once to match the real design after a first pass gave
a misleading result:

- **Round 1 (listener in the app's own test sources — not representative):** a modular `io.vidocq.spike.app`
  module with the `LauncherSessionListener` compiled alongside `SpikeTest` in its own `src/test/java`, in an
  unmentioned-by-`module-info` `spike.listener` package. This **failed on the module path**: Surefire's default
  `--patch-module` folds the *entire* `target/test-classes` tree — including packages `module-info` never
  mentions — into the application's named module. The listener class landed inside that named module, not the
  unnamed one, so the JPMS rule for named modules applied (`ServiceLoader` only honors an explicit
  `provides … with …` module directive there, never a `META-INF/services` file), the entry was silently ignored,
  `launcherSessionOpened` never ran, and the test failed immediately (`SQLException: The url cannot be null`)
  with no Testcontainers/Docker activity at all. This setup doesn't match the design, though: the real
  `vidocq-runtime-devservices-junit` listener never lives in the application's own test sources.
- **Round 2 (listener in its own jar, added as a test dependency — matches the design):** the
  `LauncherSessionListener` moved to a separate, plain (no `module-info`) throwaway jar
  (`io.vidocq.spike:spike-listener`), `mvn install`ed locally and added to the modular app's `pom.xml` as a
  `test`-scope dependency, exactly as an application adds `vidocq-runtime-devservices-junit`. The app's test
  sources then contained only `SpikeTest`. Run unchanged (default Surefire, module path): **this passes.**
  Because `module-info` doesn't require `spike-listener`, Surefire's `--patch-module` only folds the app's own
  `target/test-classes` (just `SpikeTest.class`) into the named module; the listener jar lands on the classpath,
  in the unnamed module, where `ServiceLoader` finds its `META-INF/services` entry as expected. No
  `<useModulePath>false</useModulePath>` was needed.

**The design stands, with one constraint carried over from round 1's finding:** `vidocq-runtime-devservices-junit`
(and any `LauncherSessionListener` an application wires this way) must be its own artifact/jar, never compiled
into the application's own test sources — Surefire's module-path patching would fold it into the named module and
silently break `ServiceLoader` discovery. Task 7's docs step must say so.

## 8. Error handling

| Situation | Behaviour |
|---|---|
| A provider fails to start | As today: roll back the providers already started, fail the goal or the test session, and name the provider |
| No container runtime | Fail fast with the runtime looked for and the key that switches dev services off |
| State file missing | Section says no dev service; no anomaly |
| State file unreadable or corrupt | Anomaly `VIDOCQ-DEVS-001`; boot continues |
| Host killed without stopping | The file says `running` until the next host run overwrites it. The section prints `startedAt`, so an old file shows its age. Testcontainers' Ryuk removes the containers, unless reuse is on |

## 9. Testing

- **Unit, `devservices-host`:**
  - the context's source order, one test per level, plus `-D` winning over the file;
  - the session with fake providers: start order, rollback on failure, stop order, idempotent stop;
  - the state file's JSON;
  - secret masking, sharing the test cases of #116's `ConfigValuesTest`;
  - the atomic write.
- **Unit, `devservices-extension`:**
  - parsing: valid, missing, corrupt (`VIDOCQ-DEVS-001`), outside DEV (no values);
  - the section and panel, with `RecordingSection` / `RecordingSample` doubles.
- **Unit, plugin:** the mojos delegate to the session; `vidocq:run` starts nothing by default.
- **Integration, `vidocq-runtime-it-devservices`** (new; needs Docker; behind a Maven profile, like the runner jobs
  that already need Docker):
  - the JUnit host: a test queries the PostgreSQL started by the listener, and the report has a `devservices` section
    with its image;
  - `vidocq:run -Dvidocq.dev.devServices=true`: the section shows the service, and the password appears nowhere in
    the report or the snapshot;
  - `vidocq:dev`: the dev console snapshot has the `devservices` panel.
  - Check before merging that the organisation's runners have a Docker daemon for this profile.

## 10. Documentation

- `DEV_SERVICES.md`:
  - the new configuration sources (§5), with the stable-port example made true;
  - `vidocq:run` (§6);
  - tests (§7);
  - the state file (§4.2) and the fact that the `.properties` file keeps passwords.
- `docs/en/modules/ROOT/pages/`:
  - a **Dev services** page (the docs have none today: the topic only appears in `whats-new.adoc` and one
    extensions paragraph), covering usage, keys, the section and panel, and tests;
  - a panels table row in `dev-console.adoc`;
  - a `whats-new.adoc` bullet.

  All with `[.tag-new]#NEW#`.

## 11. Out of scope

- New services (MySQL/MariaDB, Redis, Kafka, …).
- Sharing containers across applications, as Quarkus does with labels.
- Live container state in the console, which would need Docker access from the application.
- Continuous testing (#122), designed separately.
