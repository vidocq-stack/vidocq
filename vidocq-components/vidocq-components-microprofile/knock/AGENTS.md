# AGENTS.md

## Repository Mission

- Knock implements **MicroProfile Health 4.0** in Java 25 with **zero third-party
  implementation libraries**: only the MP Health spec in `knock-core`, Jakarta APIs only
  on the CDI side (`README.md`, `pom.xml`, `CLAUDE.md`).
- Strict JPMS architecture: `knock-api` re-exports the spec, `knock-core` stays standalone SE
  (depends on `jakarta.json` / champollion for JSON serialization), `knock-cdi-vauban` is
  an optional CDI adapter, `knock-cassini` is the optional Jakarta REST adapter
  (JAX-RS `/health*` resources via Cassini), `knock-tck` is now part of the main reactor
  (standalone-capable Model 4.0.0 POM, also runnable on its own via the TCK script).
- Prefer `ROADMAP.md` to track project progress rather than updating this file,
  which is intended as a contribution guide for agents.
- If the rules in this file need updating, remember to align `CLAUDE.md` accordingly
  so Claude Code can reference it easily.

## Actual Code State to Know Before Modifying

- `M1` is delivered: `HealthCheckResponse` builder, registry, parallel aggregator (virtual
  threads), and JSON-P serialization are implemented in `knock-core` (see
  `io.vidocq.knock.internal.*` + tests `KnockHealthCheckResponseBuilderTest`,
  `KnockAggregatorTest`, `KnockJsonSerializerTest`).
- `M2` is delivered: BCE `HealthCheckCdiExtension` (validation §4.2), `HealthCheckRegistrar`
  (auto-registration §4.1), and CDI bean `KnockCdiHealthCheckRegistry` in `knock-cdi-vauban`
  (tests `HealthCheckCdiIntegrationTest` 5/5 PASS against embedded Vauban).
- `M3` is delivered: JAX-RS resource `KnockHealthResource` (`@Path("/health")`, 4 endpoints)
  in `knock-cassini` (tests `KnockHealthResourceTest` 7/7 PASS, without HTTP container via a
  minimal local `TestRuntimeDelegate` — zero internal Cassini imports).
- The target flow in `knock-core`: `HealthCheckRegistry.getChecks(ProbeType)` → call `.call()`
  on each `HealthCheck` → aggregation (DOWN if ≥ 1 DOWN) → `HealthSnapshot` (status + list).
- The MP Health builder is already wired via SPI ServiceLoader:
  `KnockHealthCheckResponseProvider` + `META-INF/services` + JPMS `provides` in
  `knock-core/src/main/module-info/module-info.java`.
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

- `knock-tck` is included in the main reactor (parent `pom.xml` lists it as a `<module>`).
  Keep its POM at Model 4.0.0 and standalone-capable: ShrinkWrap Maven Resolver 3.3
  (Arquillian transitive dependency) uses maven-resolver 1.9 / maven-model 3.9 and cannot
  parse Model 4.1.0 reactor POMs, so `knock-tck` must never rely on Model 4.1.0 features
  (e.g. implicit parent versions) and must stay buildable in isolation via the TCK script.
- `knock-core` depends on `org.eclipse.microprofile.health` + `jakarta.json` (JSON-P spec API)
  at compile scope; champollion is provided at runtime. CDI and Jakarta REST stay in their
  dedicated modules. `jakarta.annotation` is allowed **in test scope only**.
- Preserve the JPMS workaround in `knock-core`: `module-info.java` stays in
  `knock-core/src/main/module-info/` (not in `src/main/java`) with dedicated recompilation in
  `prepare-package` + cleanup of `module-info.class` before `testCompile`. Same workaround
  applied to `knock-cdi-vauban` and `knock-cassini` (test-scope dependencies outside module-path).
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
  `mvn -f knock-tck/pom.xml -Ptck-official test`.
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
- `M2` = CDI Vauban integration (BCE discovers `@Liveness/@Readiness/@Startup` and auto-registers
  in the registry). Deployment validation if a `HealthCheck` bean is not qualified.
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

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

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
