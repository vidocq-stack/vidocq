# Ravel - Claude Code Guidelines

> Maurice Ravel (1875–1937) was a master of orchestration — he knew how to weave disparate
> sources (strings, winds, percussion) into a coherent and hierarchical whole.
> This is exactly what a configuration system does: aggregate heterogeneous sources
> (files, environment variables, system properties) into a unified configuration.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) — the version is pinned in the
  parent `pom.xml` via `<junit.version>` and in `ravel-tck/pom.xml`.
  No downgrade to JUnit 5: all new tests target `org.junit.jupiter.api.*` /
  JUnit Platform 2.x. The target JVM (Java 25) easily covers the minimum
  required by JUnit 6 (Java 17+).
- The MicroProfile Config 3.1 TCK is a **public Maven Central** artifact:
  `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
  (unlike Jakarta TCKs, no manual installation needed).
  Note: artifact version `3.1.1` is a re-release of spec 3.1 published on
  2026-04-22 that adds `Automatic-Module-Name` to the manifest — functionally identical
  to 3.1 but usable with strict Java Modules. The original 3.1 version (without modular descriptor)
  must not be used.

## Essential commands

```bash
# Reactor build (without TCK)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# JMH benchmarks
./mvnw -pl ravel-bench -am package
java -jar ravel-bench/target/benchmarks.jar

# TCK — smoke test only
./run-official-tck-mp-config-3.1.sh

# TCK — full suite
./run-official-tck-mp-config-3.1.sh all

# TCK — targeted test
./run-official-tck-mp-config-3.1.sh -Dtest=TestName
```

> `ravel-tck` is **in-reactor, gated behind the `tck` Maven profile** (TCK
> harmonisation, same pattern as the vidocq-runtime-tck-* runners): a plain
> `mvn install` neither downloads nor runs anything TCK-related. Historical
> constraint, now obsolete — it used to be out-of-reactor to work around
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — same constraint as `cassini-tck`,
> `foy-tck`, and `champollion-tck`. Do not change this model.

## Architecture

Ravel is a MicroProfile Config 3.1 implementation with **zero third-party libraries** (no Smallrye,
Guava, etc.), only Jakarta EE / MicroProfile specs as dependencies, virtual threads, strict Java Modules.

```
ravel-api          ← Re-exports the org.eclipse.microprofile.config spec (ConfigProvider, Config,
                     ConfigSource, ConfigSourceProvider, Converter, ConfigBuilder)
ravel-core         ← Implementation: ConfigSources (SysProps/EnvVars/Properties),
                     built-in and SPI type converters, property expressions, config profiles
ravel-cdi-vauban   ← CDI Vauban integration: @Inject @ConfigProperty, BCE, Optional injection
ravel-bench        ← JMH: comparison Smallrye Config / Helidon Config, lookup throughput, overhead
ravel-tck          ← Official MicroProfile Config 3.1 TCK runner (in reactor, `tck` profile only)
```

**Lookup flow:**
`ConfigProvider.getConfig()` → `RavelConfig` → `ConfigSource` cascade by descending ordinal
→ expression resolution (`${key}`) → conversion via `Converter<T>` → typed value.

**Configuration sources (MicroProfile spec ordinal):**
- `SystemPropertiesConfigSource` — ordinal 400, `-Dkey=val`
- `EnvironmentVariablesConfigSource` — ordinal 300, dash/dot/case mapping
- `MicroprofilePropertiesConfigSource` — ordinal 100, `META-INF/microprofile-config.properties`
- Third-party sources via `ConfigSourceProvider` SPI (ServiceLoader)

**Two levels of conversion:**
- **Built-in**: `String`, Java primitives, `OptionalInt/Long/Double`, `URL`, `URI`, `InetAddress`,
  `Duration`, `LocalDate/Time/DateTime`, enumerations, arrays and `List`/`Set` (comma separator)
- **Custom**: `Converter<T>` via ServiceLoader or `ConfigBuilder.withConverter()`

## Architecture constraints not to violate

1. **`ravel-core` only depends on `org.eclipse.microprofile.config`** — no CDI, no Servlet,
   no other Jakarta specs. The config core must work standalone SE without any container.
2. **`ravel-cdi-vauban` depends on `ravel-core` + `jakarta.cdi`** but never the reverse — the
   CDI integration is an optional module not visible from the core.
3. **Strict Java Modules**: all modules have a `module-info.java`, `internal.*` packages unexported,
   SPI exposed only via `provides ... with`.
4. **No `synchronized`, no `ThreadLocal`** — virtual-thread-friendly. Use `ScopedValue`
   for any propagated context (e.g., cyclic resolution detected in `ExpressionResolver.STACK`).
5. **No `setAccessible(true)` reflection** except for the implicit converter (String constructor,
   `valueOf`/`parse` method) — document any required Java Modules opening in `module-info`.
6. **Cycle detection in property expressions**: an expression that references itself
   (directly or indirectly) must throw `IllegalArgumentException`, not loop indefinitely.
7. **TCK MicroProfile Config 3.1 100% PASS** is a hard contract before any structural merge.
8. **jlink-ready**: no Ravel dependency (except `ravel-tck`) directly references
   `org.eclipse.microprofile.config:microprofile-config-api`. The `ravel-mp-config-api` repackage
   is the sole modular source of the spec — it provides an explicit `module-info.class`
   (name `org.eclipse.microprofile.config`) that jlink can include in a runtime image,
   unlike the original jar which only has an `Automatic-Module-Name`.

## Conventions

- **Explicit Java modules**: all modules have a `module-info.java`.
- **Packages**:
  - `io.vidocq.ravel.spi.*` = stable public SPI (third-party ConfigSource extensions)
  - `io.vidocq.ravel.internal.*` = internal code (may break between versions)
- **Maven groupId**: `io.vidocq.ravel`.
- **Records** for immutable objects (`ConfigValue`, `ConfigEntry`);
  **sealed interfaces** for closed hierarchies (expression types, lookup results).
- **Exhaustive pattern matching** on switch — no `if/else if` chains.
- **JUnit 6** only for tests (BOM `org.junit:junit-bom` 6.x). Do not
  reintroduce JUnit 5; do not mix Vintage. Bump the
  `<junit.version>` property in the parent `pom.xml` for any update.
- **Language** — commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## Roadmap

See `ROADMAP.md` for the detailed phase-by-phase plan (M0..M5).

- **M0** — Bootstrap Maven reactor, Java Modules, `.sdkmanrc`
- **M1** — `ravel-api` + `ravel-core`: 3 built-in sources, primitive converters, `ConfigProvider`
- **M2** — Advanced converters (arrays, collections, temporal types), implicit converters
- **M3** — Config Profiles (`%dev.`, `%prod.`, `%test.`), property expressions (`${key}`)
- **M4** — `ravel-cdi-vauban`: `@ConfigProperty`, CDI BCE, `Optional<T>` injection
- **M5** — TCK runner + script, 100% score, `ravel-bench`

## TDD — Test-Driven Development (mandatory)

Ravel is developed using **strict TDD**, in this order:

1. **Red** — write the test that describes the expected behaviour (cite the MicroProfile Config 3.1
   spec section or link to the relevant paragraph in a JavaDoc comment). The test must fail
   for the right reason (compilation OK, assertion KO).
2. **Green** — write the minimum code to make the test pass. No optimization, no
   abstraction that anticipates a future test.
3. **Refactor** — clean up while keeping tests green. Run the full module suite before
   any commit.

Concrete rules:

- **One test per public class**, named `<Class>Test`, in the same package (`src/test/java`).
- **No Mockito** — hand-written doubles or inline `MapConfigSource` test stubs.
- **Spec fixture tests**: for each referenced MicroProfile Config 3.1 spec section,
  a test named `<method>_spec_section<X>_<Y>()`. Spec ↔ test traceability.
- **Coverage measured** but not enforced as a gate; test quality takes priority over percentage.

## TCK — Technology Compatibility Kit

MicroProfile Config TCK — run in the `ravel-tck` module, in the reactor but listed only
under the `tck` Maven profile of the root `pom.xml`, so a plain build never pulls the TCK:

| TCK | Artifact | Target |
|---|---|---|
| MicroProfile Config 3.1 | `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1` | 100% PASS (contract) |

The `run-official-tck-mp-config-3.1.sh` script:

- supports `smoke` (default: `RavelTckSmokeTest`, the `smoke` profile of `ravel-tck`, outside
  Arquillian), `all` (the official suite, `tck-official` profile), and targeted `-Dtest=TestName`;
- installs `ravel-api`, `ravel-core` and `ravel-cdi-vauban` locally (`mvn install -DskipTests`)
  before invocation;
- produces a `ravel-tck/target/tck-report.txt` report with the Maven test counts and PASS/FAIL,
  and exits with Maven's status.

**Release discipline:**

- **No structural merge** on `ravel-core`/`ravel-cdi-vauban` without TCK PASS.
- Any challenges (tests disabled for spec interpretation or TCK bug) are documented
  in `TCK.md` with spec citation, test hash, and reactivation plan.

## AI principles — collaboration on this repository

- **Plan mode by default** for any structural change (new module, new SPI,
  modification of a built-in `ConfigSource`).
- **Balanced elegance**: prefer a simple design that passes the TCK over a perfect design that
  does not. Document trade-offs in ADRs (`docs/adr/`).
- **No laziness on specs**: cite the MicroProfile Config 3.1 section in code comments when
  the implementation directly addresses it.
- **Zero third-party libraries**: Jakarta EE and MicroProfile specs are the only dependencies
  allowed in `provided`/`compile` scope (CDI, Annotations, etc.). If an implementation library
  seems necessary, the modular decomposition is wrong.
- Use agents **`java-modules-guardian`**, **`virtual-threads-reviewer`**, **`dependency-gatekeeper`**
  proactively on any modification to `module-info.java`, concurrent code, or `pom.xml`.

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
