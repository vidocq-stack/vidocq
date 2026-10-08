# AGENTS.md

## Repository Mission

- Knock implements **MicroProfile Health 4.0** in Java 25 with **zero third-party
  implementation libraries**: only the MP Health spec in `knock-core`, Jakarta APIs only
  on the CDI side (`README.md`, `pom.xml`, `CLAUDE.md`).
- Strict Java Modules architecture: `knock-api` re-exports the spec, `knock-core` stays standalone SE
  (depends on `jakarta.json` / champollion for JSON serialization), `knock-cdi-vauban` is
  an optional CDI adapter, `knock-cassini` is the optional Jakarta REST adapter
  (JAX-RS `/health*` resources via Cassini), `knock-tck` is in-reactor behind the `tck`
  Maven profile (runnable via the TCK script or `./mvnw -Ptck -pl knock-tck test`).
- Prefer `ROADMAP.md` to track project progress rather than updating this file,
  which is intended as a contribution guide for agents.
- If the rules in this file need updating, remember to align `CLAUDE.md` accordingly
  so Claude Code can reference it easily.

## Actual Code State to Know Before Modifying

- `M1` is delivered: `HealthCheckResponse` builder, registry, parallel aggregator (virtual
  threads), and JSON-P serialization are implemented in `knock-core` (see
  `io.vidocq.knock.internal.*` + tests `KnockHealthCheckResponseBuilderTest`,
  `KnockAggregatorTest`, `KnockJsonSerializerTest`).
- `M2` is delivered: `HealthCheckRegistrar` (auto-registration of qualified checks §4.1)
  and CDI bean `KnockCdiHealthCheckRegistry` in `knock-cdi-vauban`. A `HealthCheck` bean
  without a probe qualifier is not a procedure and is silently ignored (§4.2) — no deployment
  error (tests `HealthCheckCdiIntegrationTest` PASS against embedded Vauban).
- `M3` is delivered: JAX-RS resource `KnockHealthResource` (`@Path("/health")`, 4 endpoints)
  in `knock-cassini` (tests `KnockHealthResourceTest` 7/7 PASS, without HTTP container via a
  minimal local `TestRuntimeDelegate` — zero internal Cassini imports).
- The target flow in `knock-core`: `HealthCheckRegistry.getChecks(ProbeType)` → call `.call()`
  on each `HealthCheck` → aggregation (DOWN if ≥ 1 DOWN) → `HealthSnapshot` (status + list).
- The MP Health builder is already wired via SPI ServiceLoader:
  `KnockHealthCheckResponseProvider` + `META-INF/services` + Java Modules `provides` in
  `knock-core/src/main/java/module-info.java`.
- **Exported runtime SPI**: `io.vidocq.knock.runtime.{HealthCheckRegistries, KnockHealthService,
  HealthReport}` in `knock-core` — single entry point for adapters
  (`knock-cdi-vauban`, `knock-cassini`). Never depend on `io.vidocq.knock.internal.*`
  from a sibling module.
- `knock-cdi-vauban` discovers at the BCE phase the beans annotated `@Liveness`, `@Readiness`,
  `@Startup` and registers them in the `HealthCheckRegistry` via `HealthCheckRegistrar`.
- `knock-cassini` exposes Jakarta REST endpoints (`@Path("/health")` etc.) by delegating
  to the registry; JSON serialization is produced by `knock-core` via Jakarta JSON-P
  (champollion as runtime implementation).
- See `ROADMAP.md` for the detailed status of each milestone.

## Boundaries Not to Break

- `knock-tck` joins the reactor **only under the `tck` Maven profile** (TCK harmonisation,
  same pattern as the `vidocq-runtime-tck-*` runners): a regular `./mvnw install` neither
  builds nor downloads the TCK harness. Keep that gating. The historical ShrinkWrap Maven
  Resolver 3.3 vs Model 4.1.0 constraint (which once kept the runner out of the reactor)
  is obsolete since the Maven 3.9.16 / Model 4.0.0 migration.
- `knock-core` depends on `org.eclipse.microprofile.health` + `jakarta.json` (JSON-P spec API)
  at compile scope; champollion is provided at runtime. CDI and Jakarta REST stay in their
  dedicated modules. `jakarta.annotation` is allowed **in test scope only**.
- Every module keeps its `module-info.java` in `src/main/java` and its tests run on the module path.
  A test that registers bean classes directly with the container gets its `--add-reads`/`--add-opens`
  to `io.vidocq.vauban.core` from surefire, test-only (the old `src/main/module-info/` workaround was
  removed on 2026-10-08, Vidocq/vidocq-parent#15).
- `knock-cassini` depends on `knock-core` + `jakarta.ws.rs` (Jakarta REST spec); Cassini is
  the provided runtime implementation. Do not import internal Cassini classes from
  `knock-cassini` — limit to the standard JAX-RS API.
- Keep `io.vidocq.knock.internal.*` unexported; all extensions must go through the SPI.
- No `synchronized`, no `ThreadLocal` — virtual-thread-friendly.
- No `setAccessible(true)` — no functional justification in a health system.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) for all tests.
- JSON serialization uses **Jakarta JSON-P only** (`jakarta.json.Json`);
  champollion is its implementation. Never Jackson, Gson, standalone Yasson, or StringBuilder
  when Jakarta JSON-P is available.
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## Useful Workflows

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./run-official-tck-mp-health-4.0.sh
./run-official-tck-mp-health-4.0.sh all
./run-official-tck-mp-health-4.0.sh -Dtest=TestName
```

- The TCK always goes through the root script, which first installs the reactor then invokes
  `./mvnw -Ptck,tck-official -pl knock-tck test` (knock-tck is in-reactor behind the `tck` profile).
- The TCK script explicitly installs `knock-api,knock-core,knock-cdi-vauban,knock-cassini`
  via `./mvnw -pl ... -am install -DskipTests` before executing `knock-tck`.
- The TCK is not "for later": it serves as continuous verification from M2/M3 onward.

## Observed Contribution Conventions

- Strict TDD: Red → Green → Refactor, with citation of the targeted MicroProfile Health
  section in test JavaDoc comments (`CLAUDE.md`, `ROADMAP.md`).
- Tests in the same package, named `<Class>Test`; no Mockito — manual test doubles.
- JSON serialization tested by JSON string (or champollion JsonObject) comparison,
  never by reflection on internal fields.
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

- `M1` is already in place in `knock-core`:
  `KnockHealthCheckResponseBuilder`, `KnockHealthCheckRegistry`, `KnockAggregator`,
  `HealthSnapshot`, `KnockJsonSerializer`, and SPI provider
  `KnockHealthCheckResponseProvider`.
- `M2` = CDI Vauban integration (`HealthCheckRegistrar` discovers `@Liveness/@Readiness/@Startup`
  and auto-registers them in the registry). A `HealthCheck` bean without a probe qualifier is
  not a procedure and is silently ignored (§4.2) — never a deployment error.
- `M3` = Jakarta REST endpoints via Cassini (`knock-cassini`): JAX-RS resources
  `/health`, `/health/live`, `/health/ready`, `/health/started`. HTTP 200 if UP, 503 if DOWN.
  JAX-RS `Response` built with `jakarta.ws.rs.core.Response`; JSON body produced
  by `knock-core` via Jakarta JSON-P / champollion. **Delivered**: `KnockHealthResource` +
  `KnockHealthService` (SPI runtime facade), 7/7 tests PASS.
- `M4` = MicroProfile Health 4.0 TCK at 100% PASS — hard contract.
- `M5` = Vidocq ecosystem integration (cassini, vidocq). Knock becomes the default
  health probe for every vidocq deployment.
- Before any structural modification to `knock-core` or `knock-cdi-vauban`, reason
  against the final contract: **MicroProfile Health 4.0 TCK at 100% PASS**.

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

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
