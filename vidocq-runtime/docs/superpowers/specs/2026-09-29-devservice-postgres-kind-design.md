# Dev services: no PostgreSQL container for an application that is not on PostgreSQL

Date: 2026-09-29. Found while moving `lc4jcdi-on-vidocq/mcp-tasks-server` from H2 to PostgreSQL
(VidocqTools/LC4JCDI-on-vidocq#9).

## 1. Goal

Today `PostgresDevService` starts a container for the `@Default` datasource whenever `vidocq.pool.url` is not given
as a `-D`, an environment variable or the goal's configuration. The URL written in the application's
`vidocq.properties` is never read, so an application whose file says `jdbc:h2:mem:…` still gets a PostgreSQL
container, whose URL then replaces the H2 one. With the provider on the plugin's class path, every H2 or MySQL
application breaks.

The PostgreSQL dev service starts only for an application that is on PostgreSQL, and says why when it does not.

- **In scope:**
  - two `default` methods on `DevServiceContext`: the application's own configuration value, and whether a class is on
    the application's class path;
  - the four hosts (`vidocq:dev`, `vidocq:run`, `vidocq:test`, the JUnit listener) giving them;
  - the new rule in `PostgresDevService.plan`, for `@Default` and each named datasource;
  - a provider's reason for not starting, kept in the state file and shown in the startup report and the *Dev
    services* panel;
  - the documentation of the rule.
- **Out of scope:**
  - activating the provider without the plugin dependency (Quarkus-style auto-activation): a later piece;
  - Keycloak's rule (`mp.jwt.verify.issuer`) and any other provider;
  - `%dev.`/`%prod.` keys.

## 2. The rule

For the `@Default` datasource (`vidocq.pool.url`) and for each name of `vidocq.dev.postgres.datasources`
(`vidocq.pool.<name>.url`), in this order:

1. **The URL is given explicitly** (`-D`, environment variable, goal configuration: `ctx.property(key)` as today):
   no container. Unchanged.
2. **The application's file gives a URL that is not PostgreSQL** (`ctx.applicationProperty(key)` starts with `jdbc:`
   and not with `jdbc:postgresql:`, case-insensitive): no container. Reason:
   `vidocq.pool.url is jdbc:h2, not PostgreSQL` — the scheme only, up to the second `:`, never the rest of the URL,
   which may hold a host, a user or a password.
3. **The application's file gives a PostgreSQL URL** (`jdbc:postgresql:…`): a container, whose URL replaces it under
   the dev host, as today. The file's URL is the production one; it never switches the dev service off.
4. **No URL anywhere:** a container only when `org.postgresql.Driver` is on the application's class path; otherwise
   none. Reason: `no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`.

A value that is present but does not start with `jdbc:` (a placeholder, an expression) is treated as rule 4.

`appliesWhen` stays `!plan(ctx).isEmpty()`. When the plan is empty, the provider gives the reasons of its datasources,
joined with `; `, through `DevService.skipReason(ctx)` (§4).

## 3. The SPI: `DevServiceContext`

```java
/**
 * A value of the application's own configuration files (vidocq.properties, application.properties, the external
 * configuration directory), whatever its key — for a provider to learn what the application is configured for, such
 * as the kind of database a URL names. Never a reason to switch a service off in place of property(key), whose
 * explicit sources alone do that.
 */
default Optional<String> applicationProperty(String key) { return Optional.empty(); }

/**
 * Whether the application's class path, as its launch will see it (runtime dependencies for vidocq:dev and
 * vidocq:run, test dependencies for a test run), holds this class, found as a .class entry, never loaded.
 */
default boolean onApplicationClasspath(String className) { return false; }
```

Both are `default` so that the existing implementations (test fakes included) still compile. A `default` of
"unknown" means rule 4 never starts a container through an old host: every host of this repository implements both.

**`DefaultDevServiceContext`** gains a constructor taking the unfiltered application values
(`Function<String, Optional<String>> applicationValues`) and the class path check (`Predicate<String>`); the
existing constructors delegate with "none".

**`ApplicationFiles`** gains `static Function<String, Optional<String>> allOf(Path classesDir)`: the same files in
the same order as `of`, without the `vidocq.dev.` filter. `of` is unchanged.

**The class path check** (`ApplicationClasspath` in the host module): built from a list of paths (jars and
directories); `contains(className)` looks for `<name with / >.class` in each directory, and as a jar entry in each
jar, reading each jar's entry names once, lazily, and remembering them.

## 4. Reasons: `DevService.skipReason`

```java
/** Why this provider does not start, when appliesWhen is false: one line, no secret; null when it has none to give. */
default String skipReason(DevServiceContext ctx) { return null; }
```

- **`DevServiceManager`** asks it when `appliesWhen` is false: it logs `DevService '<id>' not started: <reason>`
  (instead of `skipped (already configured)`, kept when the reason is `null`) and records `(id, reason)`.
- **`StateFile`** writes them as `"skipped": [{"id": "postgres", "reason": "…"}]`; **`StateReader`** reads them into
  `DevServicesSnapshot.skipped()` (absent in an older file: empty).
- **`DevServicesSection`** shows one row per skipped provider, `postgres — not started: <reason>`, at every
  verbosity; with no service started and at least one skipped, the summary is `no dev service started` instead of
  `no dev service: not started by vidocq:dev, vidocq:run or the test launcher` (which stays when there is no state
  file).
- The *Dev services* panel shows what the section shows (it renders the same snapshot).

## 5. The hosts

| Host | `applicationProperty` | `onApplicationClasspath` |
|---|---|---|
| `vidocq:dev` (`VidocqDevMojo`) | `ApplicationFiles.allOf(classesDir)` | the jars of the child's module path (`buildModulePath()`, already computed before the session opens) plus `classesDir` |
| `vidocq:run` (`VidocqRunMojo`) | `ApplicationFiles.allOf(classes)` | the module path it builds (dev-only jars dropped) plus `classes` |
| `vidocq:test` (`VidocqTestMojo`) | `ApplicationFiles.allOf(classesDir)` | the test class path: the mojo gains `requiresDependencyResolution = TEST` and the `MavenProject`, and uses `getTestClasspathElements()` |
| JUnit (`DevServicesSessionListener`) | `ApplicationFiles.allOf(basedir/target/classes)` | the test JVM's own class path: the thread context class loader's `getResource("org/postgresql/Driver.class")` |

A test run deliberately sees the test class path: an application with PostgreSQL at runtime and H2 for its tests,
whose file URL is PostgreSQL, still gets its container under rule 3.

## 6. Documentation

- `dev-services.adoc`, `[#configuration-sources]`: the opt-out keys stay explicit-only for switching a service off;
  a provider may read the application's file to learn what it is configured for; the PostgreSQL rule (§2) as a table,
  with a `[.tag-new]#NEW#` badge; the *not started* rows of the report.
- `DEV_SERVICES.md`: "Opt-out semantics" and "Where configuration comes from" rewritten to match; the SPI's two
  methods and `skipReason`.
- `whats-new.adoc`: one entry.

## 7. Testing

- **`PostgresDevServiceTest`** (`plan` with a fake context): each rule, for `@Default` and a named datasource: an
  explicit URL; a file URL `jdbc:h2:mem:x`, `jdbc:mysql://h/db`, `JDBC:H2:…` (case); a file URL
  `jdbc:postgresql://prod:5432/db`; no URL with and without the driver; a value `${db.url}`; the reasons, and that a
  reason never holds anything past the scheme (a URL with `user:secret@` in it).
- **`ApplicationFilesTest`**: `allOf` returns a non-`vidocq.dev.` key; `of` still does not.
- **`ApplicationClasspathTest`**: a class in a directory, in a jar, absent; a missing path ignored.
- **`DevServiceManagerTest`**: a provider whose `appliesWhen` is false with a reason is recorded and logged; with
  `null`, the old log line.
- **State file**: written and read back with `skipped`; an older file without it reads as empty.
- **`DevServicesSectionTest`**: the *not started* row and the summary.
- **Hosts:** a unit test per mojo where the existing tests allow it; otherwise the checks below.
- **Real checks:**
  - the mansart-h2 example with `vidocq-runtime-devservice-postgres` added to its plugin under `vidocq:dev`: no
    container, the report says `postgres — not started: vidocq.pool.url is jdbc:h2, not PostgreSQL` (then the
    dependency is removed again);
  - mcp-tasks-server (LC4JCDI-on-vidocq#9) under `vidocq:dev`: a container as before;
  - `vidocq-runtime-it-devservices` (`-Pit`) stays green.

## 8. Decisions taken with the user

- The rule reads the URL's kind from the application's file and the PostgreSQL driver from its class path ("URL +
  driver").
- A PostgreSQL URL in the file never switches the container off: it is the production one.
- The example to use PostgreSQL is `lc4jcdi-on-vidocq/mcp-tasks-server` (done in LC4JCDI-on-vidocq#9).
