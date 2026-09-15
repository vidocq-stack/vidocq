# CLAUDE.md

Contributor guidance for agents working on this repository. See also the companion `AGENTS.md` file.

## Project overview

Vidocq is a **modular Java SE MicroProfile 7.1 runtime** built on **Vauban** (CDI 4.1
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
  They certify the *assembled runtime*. Five of them gate every pull request (`ci.tck.modules` in the
  root pom, ~45 s); `tck-nightly.yml` runs eight. The per-brick runners of the other repositories
  (Servlet 6.1, the official per-spec suites) are a different matter — several stay outside their
  reactor and are launched by their own `run-official-tck-*.sh`.

## Forge & tooling (CodeFloe)

The `vidocq` project (and its org) is hosted on **CodeFloe** (`https://codefloe.com`), a
**Forgejo** instance — not GitHub. Use the **`tea` CLI** (already authenticated, login
`codefloe`, set as default via `tea login list`) to interact with issues, pull requests,
CI/Actions runs, and repo/org config — do **not** use `gh` or GitHub MCP tools for this
repo. Common commands: `tea issues`, `tea pulls`, `tea issues create`, `tea pr create`.

## Architecture & reactor layout

Root reactor modules (`pom.xml`):

- `vidocq-runtime-spi` — public extension SPI: `VidocqExtension`, `ExtensionContext`.
- `vidocq-runtime-core` — lifecycle orchestrator, `ServiceLoader` discovery, bean scan.
- `vidocq-runtime-core-codegen` — Vauban indexer APT bundle (wired globally via root `pluginManagement`).
- `vidocq-runtime-extensions/` — delivered extensions, grouped by domain: `*-essentials` (Chappe HTTP), `*-jakartaee-core` (Cassini REST), `*-jakartaee-web` (Mansart data/pool/transactions), `*-microprofile` (config, rest-client, jwt, telemetry, health, metrics, openapi), `*-jpms-repackaged` (third-party JPMS repackages).
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
| Ravel | MicroProfile Config 3.1 | `vidocq-runtime-ravel-config-extension` |
| Cyrano | MicroProfile Rest Client 4.0 | `vidocq-runtime-cyrano-rest-client-extension` |
| Cervantes | MicroProfile JWT Auth 2.1 | `vidocq-runtime-cervantes-jwt-extension` |
| Humboldt | MicroProfile Telemetry 2.1 | `vidocq-runtime-humboldt-telemetry-extension` |
| Knock | MicroProfile Health 4.0 | `vidocq-runtime-knock-health-extension` |
| Dirac | MicroProfile Metrics 5.1 | `vidocq-runtime-dirac-metrics-extension` |
| Grimm | MicroProfile OpenAPI 4.x (+ Swagger UI) | `vidocq-runtime-grimm-openapi-extension` |
| Mansart | Jakarta Data 1.0 / Pool / JTA | `vidocq-runtime-mansart-{data,pool,transactions}-extension` |

### Codegen (APT) wiring — required pattern for apps/examples

The Vauban indexer (`vidocq-runtime-core-codegen`) is inherited from the root
`pluginManagement`. For **every** `vidocq-runtime-<X>-extension` an app depends on, add the
matching `vidocq-runtime-<X>-extension-codegen` POM bundle to
`<annotationProcessorPaths combine.children="append">` — the `append` is mandatory so the
inherited indexer entry is preserved. Canonical example:
`vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml`.

## Maven plugin goals (`vidocq-runtime-maven-plugin`)

`generate` (bean index + codegen), `package` (fat-jar/ZIP distribution), `checkpom`
(wired in `validate` via `pluginManagement`), `jlink`, `jpackage`, `docker`, `dev`.

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
