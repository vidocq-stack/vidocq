# Vidocq CLI — Roadmap

Standalone command-line interface for the Vidocq runtime.
Inspired by the [Quarkus CLI](https://quarkus.io/guides/cli-tooling) command vocabulary:
`create`, `dev`, `start`, `extension list/add/remove`, `info`, `version`.

Zero external dependencies — hand-rolled arg parser, pure Java 25, JPMS-native.

---

## Milestone M1 — MVP (current, 0.2.0-SNAPSHOT)

| Command | Status | Notes |
|---|---|---|
| `vidocq version` | ✅ | Prints CLI version string |
| `vidocq info` | ✅ | JVM info + ServiceLoader extension scan |
| `vidocq help [command]` | ✅ | Top-level + per-command help |
| `vidocq start [--port] [--config] [--debug]` | ✅ | Delegates to `VidocqBootstrap` |
| `vidocq dev [--port] [--profile] [--debug]` | ✅ M2 | Boots + watches sources; in-process reload on change (see M2) |
| `vidocq create --name … [-g] [--package] [-x …]` | ✅ | Scaffolds pom.xml + App + module-info |
| `vidocq extension list [--installed\|--available\|--all]` | ✅ | ServiceLoader scan |
| `vidocq extension add <id…>` | ✅ M3 | Injects `<dependency>` into pom.xml (idempotent) |
| `vidocq extension remove <id…>` | ✅ M3 | Removes the matching `<dependency>` from pom.xml |
| `vidocq doctor [--verbose]` | ✅ M6 | Environment & project health checks (see M6) |
| `vidocq build [type] [-o] [--skip-tests] [--dry-run]` | ✅ M4 | Wraps Maven `package`/`jlink`/`jpackage`/`docker` (see M4) |
| `vidocq clean [-o] [--dry-run]` | ✅ M4 | Wraps `mvn clean` |

---

## Milestone M2 — Dev mode & live reload ✅ delivered

- **`vidocq dev`** — boots the runtime in-process, then watches the project's source
  roots (`src/main/java`, `src/main/resources`, and `target/classes` when present) via
  `java.nio.file.WatchService`, one **virtual thread per watched tree** with recursive
  auto-registration of new directories. Changes are **debounced** (250 ms) and trigger an
  **in-process reload**: the current `VidocqBootstrap` is shut down and a fresh one is
  configured + started in the same JVM. This re-applies configuration and resources and
  re-runs extension discovery.
  > **Scope (honest):** the reload re-applies config/resources and re-runs the boot
  > lifecycle in the *same* classloader — it does **not** hot-swap changed `.class` bytes.
  > Recompile-and-reclassload (à la the `vidocq:dev` Maven mojo, which forks a child JVM)
  > is a future enhancement tracked separately.
- **`--profile <name>` / `-P`** — activates a named config profile (default `dev`).
  Layers `vidocq-<profile>.properties` on top of `vidocq.properties` and publishes the
  merged values as system properties (highest config ordinal), so explicit `-D…`
  overrides still win. Reports how many config files were layered.
- **`--debug`** — prints a JDWP connection hint (`address=*:5005`, `suspend=n`) so a
  debugger can attach to the dev JVM.
- ANSI progress **spinner** during boot (virtual-thread driven; auto-disabled when there
  is no console or `NO_COLOR` is set).

**Components** (all pure/injectable for unit testing): `dev/Profiles` (config layering),
`dev/DebugOptions` (JDWP string), `dev/Reloads` (extension-change filter),
`dev/Debouncer` (clock-injectable), `dev/SourceWatcher` (`WatchService` + vthreads),
`dev/BootSpinner` (ANSI spinner). Supervisor logic lives in `CommandRunner.runDev`.

---

## Milestone M3 — Extension management ✅ delivered

- **`vidocq extension add <id…>`** — resolves each id to a Maven coordinate and injects a
  project-level `<dependency>` into `./pom.xml`. Editing is **text-based** (the rest of the
  file's formatting and comments are preserved); existing dependencies are detected with
  **StAX** (`PomDependencies`, `java.xml`) so adds are **idempotent**. If the POM has no
  `<dependencies>` element, one is created before `</project>`.
- **`vidocq extension remove <id…>`** — removes the matching project-level `<dependency>`
  block, leaving any `<dependencyManagement>` entry untouched.
- **Id resolution** (`KnownExtensions.resolve`): a known short id (e.g. `knock-health`)
  maps to its real, category-specific coordinate
  (`io.vidocq.runtime.extensions.microprofile:vidocq-runtime-knock-health-extension`); an
  explicit `groupId:artifactId` is used verbatim; anything else falls back to the
  convention `io.vidocq.runtime:vidocq-runtime-<id>-extension` (with a warning).
- **`vidocq extension list --available`** — lists extensions from the registry with graceful
  degradation: a remote fetch (`HttpClient`, virtual thread, short timeout) →
  on-disk cache (`~/.vidocq/registry-cache.json`) → built-in offline catalog. The source is
  reported so the user knows whether the data is live, cached, or offline.

**Components** (pure/injectable where possible): `ext/ExtensionCoordinate` (GA value type +
dependency XML), `ext/RegistryEntry`, `ext/KnownExtensions` (catalog + resolver),
`ext/PomDependencies` (StAX reader), `ext/PomEditor` (pure text add/remove returning
`Result(pom, changed)`), `ext/MiniJson` (tiny zero-dep JSON reader),
`ext/ExtensionRegistry` (remote→cache→catalog strategy with an injectable `Fetcher`),
`ext/HttpRegistryFetcher` (real `HttpClient` fetch). Wired in `CommandRunner`.

> **Scope (honest):** the registry endpoint (`registry.vidocq.dev`) is not yet live, so in
> practice `list --available` serves the built-in catalog; the remote + cache paths are
> implemented and unit-tested with an injected fetcher and a temp cache directory.

---

## Milestone M4 — Build & packaging ✅ delivered

Thin, coloured wrappers around the real `vidocq-runtime-maven-plugin` goals
(prefix `vidocq`). The CLI resolves the project `mvnw` wrapper (walking the cwd
and its ancestors) and falls back to `mvn` on the `PATH`; stdio is inherited so
Maven's own output streams straight to the terminal.

| Sub-command | Maven invocation | Description |
|---|---|---|
| `vidocq build` | `mvn package` | Standalone distribution ZIP |
| `vidocq build jlink` | `mvn package vidocq:jlink` | Self-contained jlink runtime image (see `JLINK.md`) |
| `vidocq build jpackage` | `mvn package vidocq:jpackage` | Native installer (.dmg/.deb/.msi) or app-image |
| `vidocq build docker` | `mvn package vidocq:docker` | Dockerfile around the jlink image |
| `vidocq clean` | `mvn clean` | Remove build output |

Shared options for `build`/`clean`: `--offline, -o` (Maven `-o`),
`--skip-tests` (build only, `-DskipTests`), `--dry-run` (print the resolved
command without running Maven), and `-- <args…>` to pass any extra arguments
straight through to Maven.

> The original roadmap listed speculative `uber-jar`/`native` goals; those goals
> do not exist in the Maven plugin. Distribution packaging is the `package`
> goal, native installers are `jpackage`, and GraalVM `native-image` remains a
> future addition (no `vidocq:native` goal yet).

**Design (testability-first):** `build/BuildType` (token → real goals),
`build/MavenInvocation` (pure command-line assembly), `build/MavenLauncher`
(executable resolution + `ProcessBuilder`). Covered by `BuildTypeTest`,
`MavenInvocationTest`, `MavenLauncherTest`, `CliParserBuildTest`.

---

## Milestone M5 — Config & shell integration

- **`vidocq config get/set/list`** — read/write `vidocq.properties` in the current project.
- **`vidocq completion bash`** / **`vidocq completion zsh`** — emit shell completion scripts  
  sourced by `~/.bashrc` / `~/.zshrc`.
- **Plugin SPI** — `VidocqCliPlugin` service interface so third-party modules can register  
  extra top-level commands without forking the CLI (discovered via ServiceLoader).

---

## Milestone M6 — Diagnostics (`vidocq doctor`) ✅ delivered

`vidocq doctor` inspects the local environment and current project, prints a ✔/⚠/✘
report, and exits non-zero when a blocking issue is found — so it doubles as a CI
pre-flight gate (`vidocq doctor && vidocq build`).

| Check | OK | WARN | FAIL |
|---|---|---|---|
| **Java version** | running JVM ≥ minimum (25) | — | below minimum |
| **JAVA_HOME** | set & is a directory | unset / not a directory | — |
| **Maven wrapper** | `mvnw`/`mvnw.cmd` in cwd or an ancestor | none found | — |
| **Vidocq project** | `pom.xml` references `io.vidocq.runtime` | no pom / not a Vidocq pom | — |
| **Extensions** | ≥ 1 provider on the classpath | none found | — |

- **`--verbose`, `-v`** — append a per-status summary footer and the minimum Java version.
- **Exit code** — `1` if any check FAILs, otherwise `0`; warnings never fail the command.
- **Testability** — checks live in a pure `doctor.Diagnostics` engine driven by an injectable
  `DoctorContext` record. `CommandRunner` gathers the (impure) facts — JVM version, env,
  filesystem probes, ServiceLoader scan — then hands them to the pure engine and renders the
  `Diagnostic` list. The engine is fully unit-tested without touching the filesystem.

### Future checks (backlog)
- Network reachability of the extension registry (depends on M3).
- `vidocq.properties` validity / unknown-key warnings (depends on M5 config model).
- Disk space & write permissions for the build output directory.

---

## Design constraints (all milestones)

- **Zero external runtime dependencies** — no Picocli, no JLine, no Jackson.  
  Own arg parser; ANSI detection via `NO_COLOR` + `System.console()`.
- **JPMS** — `module io.vidocq.runtime.cli`; minimal exports; `uses VidocqExtension`.
- **Virtual threads** for any blocking operation (file watching, HTTP registry calls).
- **Testability** — `Command` is a pure sealed data type; `CommandRunner` receives it;  
  unit tests can instantiate commands without touching the filesystem or network.
