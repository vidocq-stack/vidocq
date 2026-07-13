# AGENTS.md

## Repository mission
- Ravel implements **MicroProfile Config 3.1** in Java 25 with **zero third-party implementation libraries**: only the MP Config spec in `ravel-core`, Jakarta APIs only on the CDI side (`README.md`, `pom.xml`, `CLAUDE.md`).
- Strict Java Modules architecture: `ravel-api` re-exports the spec, `ravel-core` stays standalone SE, `ravel-cdi-vauban` is an optional adapter, `ravel-tck` stays out of reactor.
- **jlink-ready**: because the original MicroProfile Config API only has an `Automatic-Module-Name`, the `ravel-mp-config-api` module repackages it with an explicit `module-info.class` (module name kept: `org.eclipse.microprofile.config`). All other modules depend on **this repackage**, never directly on `org.eclipse.microprofile.config:microprofile-config-api` (except `ravel-tck` out of reactor).
- Prefer using `ROADMAP.md` to track project progress rather than updating this file, which is intended as a contribution guide for agents.
- When updating the rules in this file, remember to align `CLAUDE.md` accordingly so Claude Code can reference it easily.

## Actual code state to know before modifying
- `M0`, `M1` and `M2` are complete (`ROADMAP.md`): built-in sources + built-in/implicit/array converters + `@Priority` ordering.
- The active flow in `ravel-core`: `ConfigProvider.getConfig()` → `RavelConfigProviderResolver` → `RavelConfigBuilder` → `RavelConfig` → `ConfigSource` cascade (descending ordinal) → dispatch via `Converter<T>` (built-in, application, array, implicit).
- Active built-in sources: `SystemPropertiesConfigSource` (400), `EnvironmentVariablesConfigSource` (300), `MicroprofilePropertiesConfigSource` (100).
- Built-in converters (priority 1): primitives/boxes, `String`, `Class<?>`, `Optional{Int,Long,Double}`, `URI`, `URL`, `InetAddress`, `Duration`, `Period`, `LocalDate/Time/DateTime`, `OffsetTime/DateTime`, `ZonedDateTime`, `Instant`. Boolean §5.1.1 truthy: `true|1|yes|y|on` (case-insensitive).
- Arrays §5.4 resolved at lookup time via `ArraySplitter` + `ArrayConverter` (cache `ConcurrentHashMap` in `RavelConfig.derivedConverters`). Implicit converters §5.2 detected on **public** methods/constructors only (no `setAccessible`).
- `module-info.java` of `ravel-core` already provides `ConfigProviderResolver` and declares `uses` for `ConfigSource`, `ConfigSourceProvider`, `Converter`: prefer `ServiceLoader`, not custom hooks.
- Current priority: **M5 = TCK MicroProfile Config 3.1 (100% PASS) + JMH bench `ravel-bench`** vs Smallrye Config on the same JVM. M4 can be completed incrementally (hardened BCE validation, extended CDI coverage) but the focus is now on the TCK.

## Boundaries not to break
- Never put `ravel-tck` back into the reactor: the parent `pom.xml` deliberately excludes it due to ShrinkWrap Maven Resolver / Model 4.0.0 vs 4.1.0.
- `ravel-core` only depends on `org.eclipse.microprofile.config` at compile/runtime; CDI stays in `ravel-cdi-vauban`. `jakarta.annotation` is allowed **in test scope only** to validate `@Priority` reading.
- Keep `io.vidocq.ravel.internal.*` unexported; any extension must go through the SPI or MicroProfile types.
- No `synchronized`, no `ThreadLocal`; for propagated contexts (e.g., expression cycles in M3), use `ScopedValue`.
- No `setAccessible(true)` — implicit converters only target `public` methods/constructors.
- Reading `@Priority` is done by qualified name (reflection on `Annotation.annotationType().getName()`), never by `import jakarta.annotation.Priority` on the production side.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) for all tests. No downgrade to JUnit 5: the version is pinned in the parent `pom.xml` (`<junit.version>`) and in the standalone POM `ravel-tck/pom.xml`. Tests target `org.junit.jupiter.api.*`, JUnit Platform 2.x.

## Useful workflows
```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./mvnw -pl ravel-bench -am package
java -jar ravel-bench/target/benchmarks.jar
./run-official-tck-mp-config-3.1.sh
./run-official-tck-mp-config-3.1.sh all
./run-official-tck-mp-config-3.1.sh -Dtest=TestName
```
- The TCK always goes through the root script, which first installs the reactor then invokes `mvn -f ravel-tck/pom.xml -Ptck-official test` (`ravel-tck/README.md`).
- The TCK is not "for later": `ROADMAP.md` treats it as a continuous verification from `M2/M3`, even though the 100% PASS contract is locked in `M5`.

## Contribution conventions observed
- Strict TDD: Red → Green → Refactor, with citation of the targeted MicroProfile Config section in tests (`CLAUDE.md`, `ROADMAP.md`).
- Tests in the same package, named `<Class>Test`; no Mockito, use manual doubles like `ravel-core/src/test/java/io/vidocq/ravel/internal/MapConfigSource.java`.
- `ravel-core` integration tests complement unit tests before the TCK: multi-sources, ordinals, expressions, profiles (`ROADMAP.md`).
- Performance is a design constraint, not a bonus: `ravel-bench` serves to compare Ravel to Smallrye Config on the same JVM.
- **Language** — commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## What an agent should assume for upcoming tasks
- `M3` must be inserted **before** conversion: profiles via `ProfiledConfigSource` (ordinal +1 on the wrapped source) and `${key}` / `${key:default}` expressions resolved on the raw value returned by the cascade. Cycle detection = `ScopedValue<Set<String>>` + `IllegalArgumentException`.
- `M4` remains a CDI Vauban adapter (Build Compatible Extension), not a core extension. Deployment validation → `DeploymentException`, not `NoSuchElementException` at runtime.
- Before any structural change to `ravel-core` or `ravel-cdi-vauban`, reason with the final contract: **TCK MicroProfile Config 3.1 at 100% + reproducible JMH comparison vs Smallrye Config**.
- Current test suite: **145 green tests** on `ravel-core` + **20 green tests** on `ravel-cdi-vauban` (M1=80, M2=+51, M3=+14, M4=20); any regression must be explained and fixed before merge.

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
