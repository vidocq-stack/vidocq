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
| `vidocq extension add <id…>` | 🔲 stub | Prints roadmap notice |
| `vidocq extension remove <id…>` | 🔲 stub | Prints roadmap notice |
| `vidocq doctor [--verbose]` | ✅ M6 | Environment & project health checks (see M6) |

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

## Milestone M3 — Extension management

- **`vidocq extension add <id…>`** — parse the project `pom.xml` (StAX, zero extra dep),  
  inject the matching `<dependency>` block, write back.
- **`vidocq extension remove <id…>`** — remove the dependency block.
- **`vidocq extension list --available`** — query the Vidocq extension registry  
  (`https://registry.vidocq.dev`; plain HTTP via `java.net.http.HttpClient`).
- Offline mode: fall back to a local cache (`~/.vidocq/registry-cache.json`).

---

## Milestone M4 — Build & packaging

| Sub-command | Description |
|---|---|
| `vidocq build` | Full `./mvnw package` wrapper with coloured output |
| `vidocq build uber-jar` | Trigger `vidocq:uber-jar` Maven goal |
| `vidocq build jlink` | Trigger `vidocq:jlink` (see `JLINK.md`) |
| `vidocq build native` | GraalVM `native-image` via `vidocq:native` goal (future) |
| `vidocq clean` | `./mvnw clean` |

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
