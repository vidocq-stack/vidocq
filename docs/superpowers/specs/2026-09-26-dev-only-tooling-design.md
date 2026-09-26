# Dev tools only under `vidocq:dev`: `-dev` companion modules, never packaged

- **Date**: 2026-09-26
- **Status**: Approved design (brainstorming 2026-09-26): ready for implementation planning
- **Issue**: Vidocq/vidocq#143
- **Scope**:
  - the Maven plugin (`vidocq:dev`, `vidocq:run`, the packaging goals, `vidocq:checkpom`, `vidocq:idea`);
  - `vidocq-runtime-devconsole-spi` and the dev console extension;
  - the six extensions that have a live panel, and their new `-dev` modules;
  - the dev services extension (marking only);
  - the examples and integration tests that declare the console;
  - docs.

  No brick changes.

## 1. Context

The dev console, its panels and the dev services extension are development tools. Today only the dev services extension
is kept out of the application: `vidocq:dev` and `vidocq:run` add `vidocq-runtime-devservices-extension` to the child's
module path from the plugin's own dependencies (`DevServicesExtensionJar`), and no binary ever contains it.

Everything else ships:

- **The console.** An application declares `vidocq-runtime-devconsole-extension`, for instance the Mansart H2 example,
  three integration tests and `lc4jcdi-on-vidocq`. `vidocq:package`, `jlink`, `jpackage` and `docker` then ship it.
  It is off outside a `dev` launch, but its code is in the binary.
- **Each extension's live panel.** Six extensions implement `DevConsolePanel` in their extension class:
  `CassiniExtension`, `MansartPoolExtension`, `DiracMetricsExtension`, `KnockHealthExtension`, `MigrationExtension` and
  `McpExtension`. Each module has a `requires transitive io.vidocq.runtime.spi.devconsole`, so the panel code and the
  console SPI ship with every binary that uses the extension.
- **Planned code.** The MCP inspector, which calls tools, prompts and resources from the console, is code that must
  never reach a production binary. It is to be built on this work (§11).

`DevConsolePanel` extends `StartupReportContributor`. A panel is therefore also the contributor of its section, and
the console tells a live section from a static one by `contributor instanceof DevConsolePanel` (`PanelEntry.contributed`).

Quarkus solves the same problem with *conditional dev dependencies*. An extension declares a dependency that is
activated in dev mode only, never in prod, and not in test unless its own condition says so
(<https://quarkus.io/guides/conditional-extension-dependencies>).

The maintainer's decisions (brainstorming):

- **Bricks do not change.** A brick (Mansart, Cassini, Knock…) never knows the console. It exposes a read-only public
  API of what it already knows, as `CassiniStack.routes()` / `statistics()` (cassini#41, #42) and Knock's last answer
  per check (knock#27) do.
- **The startup-report section stays in the runtime extension.** It is not a dev tool: it prints in every mode, in
  production logs too. Only the live part (sampled values, charts, actions) moves to a `-dev` module.
- **Discovery is automatic.** A descriptor in the runtime extension's jar names its companion. The application
  declares nothing.
- **Only `vidocq:dev` adds dev tools, to the application's JVM.**
  - `vidocq:run` is "the application as packaged, started from Maven" and gets none, except the dev services it
    already takes on an explicit opt-in (#123).
  - Tests of every kind (`mvn test`, `vidocq:test`, the continuous tests of `vidocq:dev`) get the console only through
    an explicit `test`-scope declaration.
- **A dev-only jar is never packaged, even when declared.** `vidocq:checkpom` warns about such a declaration.
- **`vidocq:idea` is part of this work.** It generates a *Dev* configuration running `vidocq:dev`.

## 2. Modules

| Family | Example | Holds | Depends on |
|---|---|---|---|
| Brick | `mansart`, `cassini` | Its read-only public API | Nothing of the console |
| Runtime extension | `vidocq-runtime-mansart-pool-extension` | The integration, the startup-report section, the descriptor | `vidocq-runtime-spi`, never `vidocq-runtime-devconsole-spi` |
| `-dev` module (new) | `vidocq-runtime-mansart-pool-extension-dev` | The live panel | Its runtime extension, `vidocq-runtime-devconsole-spi` |
| Dev-only infrastructure | `vidocq-runtime-devconsole-extension`, `vidocq-runtime-devservices-extension` | The console and its own panels (config, cdi, logs, tests, jvm); the dev services | — |
| Console SPI (not dev-only) | `vidocq-runtime-devconsole-spi` | Five API types | `vidocq-runtime-spi` |

The six new `-dev` modules sit next to their runtime extension, in the same parent: `cassini-rest`, `mansart-pool`,
`dirac-metrics`, `knock-health`, `migration`, `langchain4j-cdi-mcp`. Each `-dev` module is a JPMS module named after its
extension with a `.dev` suffix, such as `io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev`.

A runtime extension exposes what its panel reads through a qualified export only:
`exports <package> to <the -dev module>`. No other module sees it. A qualified export to a module that is absent
at run time is legal, so the runtime extension works alone.

## 3. The live panel SPI

A new type in `vidocq-runtime-devconsole-spi`, `io.vidocq.runtime.spi.devconsole.LivePanel`, is a panel that writes
no section of its own:

```java
public interface LivePanel {
    /** The id of the startup-report section this panel makes live, such as "mansart-pool". */
    String id();
    default void start(ExtensionContext context) {}   // the console calls it once per boot, before any sample
    default void stop() {}                            // once per boot, when the console stops
    default List<Chart> charts() { return List.of(); }
    void sample(PanelSample sample);
    default List<PanelAction> actions() { return List.of(); }
}
```

- A `-dev` module declares it as a service: `provides io.vidocq.runtime.spi.devconsole.LivePanel with …`.
- The console loads the `LivePanel` services (`uses` in its `module-info`) and matches each to the contributed section
  of the same `id()`. The section gives the title and the boot facts; the live panel gives samples, charts and actions,
  as a `DevConsolePanel` does today.
- `DevConsolePanel` stays, unchanged, for the console's own panels and for third-party extensions that keep the
  all-in-one form. The documentation says that such a panel ships in the binary, and recommends a `-dev` module.

Rules, each logged once per boot as a WARNING anomaly:

- **`VIDOCQ-DEVC-007`, two live panels for one id:** the first found wins, and the second is skipped. The warning names both.
- **`VIDOCQ-DEVC-008`, a live panel whose section is missing** (its contributor failed or was skipped): it is not
  shown, and the warning names it. It never invents a section.
- **`VIDOCQ-DEVC-009`, a section whose contributor is itself a `DevConsolePanel`, with a live panel for the same id:**
  the live panel wins. The warning says the extension ships its panel twice.
- **A live panel's `start` or `sample` that throws** is handled as a failing `DevConsolePanel` is today: the section
  keeps its boot facts, and the page shows the exception class.

## 4. Descriptor and marking

- **Descriptor.** A runtime extension with a companion carries `META-INF/vidocq/dev-module`, a one-line text file that
  holds the companion's `artifactId`. The companion has the extension's `groupId` and version: the plugin takes them
  from the extension artifact itself. Blank lines and lines starting with `#` are ignored; a file that holds more than
  one artifactId, or an artifactId that is not a valid Maven identifier, is unreadable (§8).
- **Marking.** Every dev-only jar carries `Vidocq-Dev-Only: true` in its `META-INF/MANIFEST.MF`, set by the module's
  POM. This covers the console, every `-dev` module and the dev services extension. The marking is
  what the packaging goals and `checkpom` read, whatever the declaring scope.
- **The console SPI is not marked.** It is an ordinary API jar of five types. An extension that keeps the all-in-one
  `DevConsolePanel` form requires it (`requires transitive io.vidocq.runtime.spi.devconsole`), so it must resolve in
  every binary that has such an extension (§9); dropping it would break that module graph under `vidocq:run` and the
  packaged launcher, and fail `jlink`. `vidocq:dev` adds it with the console, and a companion may bring it among its
  runtime dependencies although it is not marked.

## 5. `vidocq:dev`

`vidocq:dev` builds the child's module path as today, then adds the dev tools:

1. **Companions.** For each resolved runtime artifact of the project, it reads `META-INF/vidocq/dev-module` and
   resolves the companion `groupId:artifactId:version` with Maven's own resolution: the project's repositories,
   offline when Maven is. It resolves the companion's transitive runtime dependencies too, since they bring the console
   SPI.
2. **The console.** `vidocq-runtime-devconsole-extension` comes from the plugin's own dependencies, as the dev services
   extension does today (`${plugin.artifactMap}`). It is always added in `vidocq:dev`.
3. **Never twice.** A dev-only artifact that the project already resolves at runtime scope (the legacy declaration,
   §9) is kept, and the plugin's copy is not added: a module must not be on the module path twice.
4. **Log.** One INFO line lists what was added, such as `Dev tools: devconsole, mansart-pool-dev, cassini-rest-dev`.

The layer mode, hot reload and continuous testing are unchanged. The companions are on the boot module path, like the
extensions themselves, so a hot reload keeps them.

## 6. Everything else

- **`vidocq:run`, `package`, `jlink`, `jpackage`, `docker`** add no dev tool, and drop every jar marked
  `Vidocq-Dev-Only` from what they launch or ship, even when the project declares it. Each dropped jar logs a
  WARNING once per goal run: `<artifactId> is dev-only: not packaged; remove the dependency, vidocq:dev brings it`.
  A dropped jar never fails the build.
  One exception is kept from #123: `vidocq:run -Dvidocq.dev.devServices=true` still adds the dev services
  extension, from the plugin's own dependencies, as today. It is an explicit opt-in for one run, never packaged, and
  it brings no console.
- **`vidocq:checkpom`** warns, and never fails, about a dependency on a dev-only artifact in any scope but `test`. It
  reads the marking from the resolved jar.
- **Tests.** `mvn test`, `vidocq:test` and the continuous tests of `vidocq:dev` get no dev tool from the plugin. A test
  that needs the console declares it in `<scope>test</scope>`, together with the `-dev` modules it wants. `test` scope
  is never packaged, and `checkpom` accepts it. The dev services JUnit host (#123) is already a `test`-scope dependency
  and is unchanged.

## 7. `vidocq:idea`

For each application, the Maven kind (the default) now generates three shared run configurations in `.run/`:

| Configuration | File | Runs |
|---|---|---|
| `<Name>` | `<Name>.run.xml` (the existing file) | Maven, `vidocq:dev`: console, reload, continuous testing |
| `<Name> (packaged)` | `<Name> (packaged).run.xml` | Maven, `vidocq:run`, as the file did before |
| `<Name> (debug)` | `<Name> (debug).run.xml` | Remote JVM Debug to the dev debug agent: `vidocq.dev.debugHost` and `vidocq.dev.debugPort` when the project sets them, else `127.0.0.1:5005` |

- The existing file keeps its name, which is the configuration the developer already uses, and now runs `vidocq:dev`.
  The ownership and drift rules of `vidocq:idea` apply to all three files as they apply to one today.
- The Application kind (`-Dvidocq.idea.kind=application`, which launches the main class) is unchanged. It has no
  console, and the documentation says so.

## 8. Error handling

| Situation | Behaviour |
|---|---|
| A companion cannot be resolved (offline, never downloaded, missing) | WARNING that names it and says how to get it (`mvn -U` once online); the application starts; its section shows boot facts only |
| A descriptor is unreadable | WARNING that names the jar; that extension gets no companion |
| The console artifact is missing from the plugin's dependencies | The goal fails: it is a broken plugin installation, as a missing dev services extension is today |
| The same dev-only module both declared and brought | The declared one is kept; nothing is added twice (§5.3) |
| A dev-only jar declared by the project, in a packaging goal | Dropped with a WARNING (§6); never fails the build |
| A `LivePanel` without a matching section, a duplicate id, a double panel | The rules and codes of §3 |

## 9. Migration

- **Existing applications keep working.** An application that declares the console at compile scope still sees it
  under `vidocq:dev`, where the declared copy is kept. Its binaries no longer contain it (§6), and `checkpom` tells it
  to remove the declaration.
- **Examples and ITs in this repository.**
  - The Mansart H2 example moves the console to `test` scope, for its `DevConsoleSnapshotTest`, together with the
    `mansart-pool` `-dev` module.
  - The three integration tests that declare it (`it-continuous-testing` fixture, `it-langchain4j-cdi-mcp`,
    `it-devservices`) use `test` scope where a test reads the console, and drop it where `vidocq:dev` is what they run.
- **`lc4jcdi-on-vidocq`** (VidocqTools) needs the same cleanup in a pull request of its own once this ships.
- **Third-party panels** in the all-in-one `DevConsolePanel` form keep working, and ship in the binary until they move
  to a `-dev` module.

## 10. Testing

- **Unit, plugin:**
  - the descriptor reader: one artifactId, comments, blanks, several lines, an invalid identifier;
  - companion resolution with a fake resolver: resolved, missing (warning, no failure), never twice;
  - the `Vidocq-Dev-Only` filter in the shared module-path code, for `vidocq:run` and each packaging goal;
  - the `checkpom` warning in every scope but `test`;
  - `vidocq:idea`: the three files, the debug host and port, the unchanged Application kind.
- **Unit, console:** `LivePanel` matching (matched, duplicate, orphan, double panel), start, stop and failure handling.
- **Unit, extensions:** each panel's tests move with it to its `-dev` module. Each runtime extension gets a test that
  its section is still written with no `-dev` module present.
- **Integration:**
  - **`vidocq:dev`:** the continuous-testing fixture no longer declares the console, and the `tests` panel must still
    appear. This proves the console is added automatically.
  - **Companions:** a `vidocq:dev` IT on an application with the Mansart pool shows the `mansart-pool` panel live,
    with the companion brought by the descriptor.
  - **Packaging:** a project that declares the console at compile scope produces a `vidocq:package` distribution
    without it, and the WARNING is logged.
  - **JPMS:** a module-path boot of a runtime extension alone, with no companion, starts cleanly. This covers the
    qualified export to an absent module.

## 11. Documentation, and what follows

- **Documentation**, English only, each new section tagged `[.tag-new]#NEW#`:
  - `dev-console.adoc`, *Getting it*: nothing to declare, `vidocq:dev` brings the console; tests declare it at `test`
    scope; binaries never contain it;
  - `dev-console-panels.adoc`: the `-dev` module, `LivePanel`, the descriptor, the qualified export, and a
    walkthrough on one extension;
  - the plugin page: `vidocq:dev` dev tools, the exclusion in the packaging goals, the `checkpom` warning, the three
    `vidocq:idea` configurations;
  - `migration.adoc`: the dependency to remove;
  - `whats-new.adoc`: one bullet.
- **Follow-up, the MCP inspector.** It is built on this work, directly in
  `vidocq-runtime-langchain4j-cdi-mcp-extension-dev`, as its own spec. Decisions already taken:
  - list, call and read tools, prompts and resources to test them quickly; elicitation and sampling answer with a clear
    refusal in v1;
  - calls go through the application's real `/mcp`, from the console's server;
  - arguments come from a form generated for flat schemas, with a raw JSON editor as fallback;
  - approach A: `PanelAction` gains a JSON argument with a schema, a structured result and action groups, generic for
    every panel, through an amendment of ADR 0001.
