# AGENTS.md

Contributor guidance for agents working on this repository. See also the companion `CLAUDE.md` file.

## Project overview

Vidocq is a **modular Java SE MicroProfile 7.2 runtime** built on **Vauban** (CDI 4.1
Lite). Each MicroProfile/Jakarta spec ships as an independent **extension** discovered via
`ServiceLoader` (Quarkus-inspired). Non-negotiable constraints:

- **JDK 25** with **native JPMS**: every module has a `module-info.java`.
- **Zero runtime dependencies** in the core; functionality is added through extensions.
- **Virtual-thread native**: use `ScopedValue` (JEP 487) for `@RequestScoped` context — never `ThreadLocal` (pinning).
- **Compile-time over reflection**: Vauban generates proxies/interceptors via the JDK 25 Class-File API; extensions use APT codegen, not runtime reflection (GraalVM-friendly).
- **English only** for code, comments, Javadoc, commits, docs, **and CodeFloe issues/PRs** (title, body, comments) (see `~/.claude/CLAUDE.md`).

## Build, test & run

- Toolchain is pinned in `.sdkmanrc`: **Java 25-tem + Maven 3.9.16**. Run `sdk env install` then `sdk env`.
- Build everything: `mvn clean install` (uses Maven Model 4.0.0; internal module versions are pinned explicitly in root `dependencyManagement`).
- Dev mode (live run of an example/app): `./mvnw vidocq:dev` — reads `vidocq.mainModule` / `vidocq.mainClass` properties (see `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml`).
- Commits must be **DCO signed-off**: `git commit -s` (see `CONTRIBUTING.md`).
- **This repository's own TCK runners are in the reactor**, behind the `tck` profile of
  `vidocq-runtime-integration-tests`: `mvn -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-<x> test`.
  They certify the *assembled runtime*. Five of them gate the pull requests (`ci.tck.modules` in the
  root pom, ~45 s): `select-tck` replays only those a change reaches, and all five on any doubt
  (root pom, CI, scripts); `tck-nightly.yml` runs eight. The pull request also runs only the
  tests of the modules a change reaches (`targeted-producer-tests`, every test on any doubt);
  `main` runs the whole suite before publishing. The per-brick runners of the other repositories
  (Servlet 6.1, the official per-spec suites) are a different matter — several stay outside their
  reactor and are launched by their own `run-official-tck-*.sh`.

## Forge & tooling (CodeFloe)

The `vidocq` project (and its org) is hosted on **CodeFloe** (`https://codefloe.com`), a
**Forgejo** instance — not GitHub. Use the **`tea` CLI** (already authenticated, login
`codefloe`, set as default via `tea login list`) to interact with issues, pull requests,
CI/Actions runs, and repo/org config — do **not** use `gh` or GitHub MCP tools for this
repo. Common commands: `tea issues`, `tea pulls`, `tea issues create`, `tea pr create`.

## Architecture & reactor layout

Root reactor modules (`pom.xml`, artifactId `vidocq-runtime-build`: this repository's build, never an
application's parent):

- `vidocq-runtime-bom` — the versions of every published runtime artifact; `ApplicationBomAndParentTest` fails when
  a published module is missing.
- `vidocq-runtime-parent` — the parent of an application (Vidocq/vidocq#339): imports the BOM, wires the indexer and
  the plugin, carries no setting of this repository. Both are published flattened (no parent, no profile).
- `vidocq-runtime-spi` — public extension SPI: `VidocqExtension`, `ExtensionContext`.
- `vidocq-runtime-core` — lifecycle orchestrator, `ServiceLoader` discovery, bean scan.
- `vidocq-runtime-core-codegen` — Vauban indexer APT bundle (wired globally via root `pluginManagement`).
- `vidocq-runtime-extensions/` — delivered extensions, grouped by domain: `*-essentials` (Chappe HTTP), `*-jakartaee-core` (Cassini REST), `*-jakartaee-web` (Mansart data/pool/transactions), `*-microprofile` (config, rest-client, jwt, telemetry, health, metrics, openapi), `*-jpms-repackaged` (third-party JPMS repackages).
- `vidocq-runtime-tooling` — project editing shared by the CLI and the plugin (extension catalog, pom and `module-info.java` editors, doctor checks, `vidocq.properties`), `java.xml` only.
- `vidocq-runtime-maven-plugin`, `vidocq-runtime-cli`, `vidocq-runtime-integration-tests` (Arquillian ITs), `vidocq-runtime-examples`.

## Extension model & codenames

Extensions implement `io.vidocq.runtime.spi.VidocqExtension`, registered via
`provides VidocqExtension with …` in `module-info.java` (or `META-INF/services`). Lifecycle:
`configure` → `beforeStart` (enrich `VaubanContainerBuilder`) → `onStart` (container ready) →
`onStop` (reverse priority order). `priority()` orders extensions.

Each extension is named after a **codename** mapping to its spec — keep this mapping when adding modules:

| Codename | Spec / role | Extension artifact |
|---|---|---|
| Chappe | HTTP/1.1+H2+WS+gRPC server | `vidocq-runtime-chappe-webserver-extension` |
| Cassini | JAX-RS 4.0 (transport via Chappe) | `vidocq-runtime-cassini-rest-extension` |
| Peano | Jakarta Expression Language 6.0 | `vidocq-runtime-peano-el-extension` |
| Ravel | MicroProfile Config 3.1 | `vidocq-runtime-ravel-config-extension` |
| Cyrano | MicroProfile Rest Client 4.0 | `vidocq-runtime-cyrano-rest-client-extension` |
| Cervantes | MicroProfile JWT Auth 2.2 | `vidocq-runtime-cervantes-jwt-extension` |
| Humboldt | MicroProfile Telemetry 2.2 | `vidocq-runtime-humboldt-telemetry-extension` |
| Knock | MicroProfile Health 4.0 | `vidocq-runtime-knock-health-extension` |
| Dirac | MicroProfile Metrics 5.1 | `vidocq-runtime-dirac-metrics-extension` |
| Grimm | MicroProfile OpenAPI 4.2 (+ Swagger UI) | `vidocq-runtime-grimm-openapi-extension` |
| Mansart | Jakarta Data 1.0 / Pool / JTA | `vidocq-runtime-mansart-{data,pool,transactions}-extension` |

### Codegen (APT) wiring — required pattern for apps/examples

**Applications under `vidocq-runtime-parent` ≥ 0.4.0**: nothing to write. The parent declares
`vidocq-runtime-maven-plugin` with `<extensions>true</extensions>`, and its lifecycle participant
(`CodegenPathsParticipant`, declared in `META-INF/plexus/components.xml` — a Sisu index is skipped by Maven 3.9.9 for
Java 25 classes) adds `vidocq-runtime-core-codegen` and each extension's `-codegen` bundle to the compiler's
processor path in the model (Vidocq/vidocq#339). `vidocq create` and `vidocq extension add` write no codegen path
there; `PomDependencies.codegenWiredByPlugin` is the test.

**Examples of this reactor** inherit `vidocq-runtime-build`, which does not load the plugin as an extension: the
Vauban indexer comes from the root `pluginManagement`, and for **every** `vidocq-runtime-<X>-extension` an example
depends on, add the matching `vidocq-runtime-<X>-extension-codegen` bundle to
`<annotationProcessorPaths combine.children="append">` — the `append` is mandatory so the inherited indexer entry is
preserved. Canonical example: `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml`.

## Maven plugin goals (`vidocq-runtime-maven-plugin`)

`generate` (bean index + codegen), `package` (fat-jar/ZIP distribution), `checkpom`
(wired in `validate` via `pluginManagement`), `jlink`, `jpackage`, `docker`, `dev`,
`run` (one forked run, after a forked lifecycle up to `process-classes`: compiles and indexes first),
`idea` (experimental, command line only: IntelliJ run configurations in `.run/`, never `.idea/`;
a Maven run of `vidocq:run` by default, `-Dvidocq.idea.kind=application` for the main-class kind),
`vscode` (command line only: `.vscode/` settings with the language server on Java 25 and no automatic build, tasks for
`vidocq:dev`/`run`/`test`, a debug attach to `vidocq:dev`; leaves a file it did not write alone),
`add-extension` / `remove-extension` (`-Dextensions=…`: the edits of `vidocq extension add|remove`, through the
same `vidocq-runtime-tooling` code, #314), `list-extensions` and `doctor` (the reports of `vidocq extension list`
and `vidocq doctor`), `create` (no project needed: the project of `vidocq create`).

**In this repository**, the `vidocq:` prefix does not resolve at the root (`No plugin found for prefix 'vidocq'`): the
root build declares the plugin in `pluginManagement` only, since declaring it in `<build><plugins>` would make a reactor
cycle (`vidocq-runtime-core` is upstream of the plugin), and Maven resolves a prefix from the build's plugins or the
`pluginGroups` of the settings. Either name the plugin (its version comes from `pluginManagement`),
`./mvnw io.vidocq.runtime:vidocq-runtime-maven-plugin:idea`, or add
`<pluginGroups><pluginGroup>io.vidocq.runtime</pluginGroup></pluginGroups>` to `~/.m2/settings.xml` once (an install
of the plugin maps the prefix in the local repository). An application under `vidocq-runtime-parent` declares the
plugin in its build and needs neither.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.
