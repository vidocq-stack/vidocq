# AGENTS.md

> This file is the contribution guide for AI agents (GitHub Copilot, Copilot Chat,
> Copilot Workspace). It must stay in sync with `CLAUDE.md` — any change in one
> must be reflected in the other.

## Repository Mission

- Grimm implements **MicroProfile OpenAPI 4.1** in Java 25, with **zero third-party
  implementation libraries**: only spec APIs (`microprofile-openapi-api`, `jakarta.ws.rs-api`,
  `jakarta.enterprise.cdi-api`, `jakarta.annotation-api`) plus the Vidocq modular repackage
  `io.vidocq.ravel:ravel-mp-config-api` are compiled into `grimm-core` and
  `grimm-cdi-vauban`.
- Strict Java Modules architecture: `grimm-core` is pure Java scanning + serialization logic with no
  CDI dependency; `grimm-cdi-vauban` is the CDI integration layer and JAX-RS endpoint host;
  `grimm-tck` is out-of-reactor.
- **No SmallRye OpenAPI, Swagger Core, Jackson, or Snakeyaml** in production code.
- Virtual threads (Project Loom) wherever concurrency is introduced —
  `Executors.newVirtualThreadPerTaskExecutor()`, no platform thread pools.
- Use `ROADMAP.md` to track milestone progress (M0…M10+).
- If the rules in this file need updating, align `CLAUDE.md` in the same operation — both
  files are mirrors targeted at different tools.

## Real Code State to Know Before Modifying

- Consult `ROADMAP.md` for the detailed state of each milestone.
- M1 is implemented: `OASFactoryResolver` wiring (`ServiceLoader`) and model POJOs are present
  in `grimm-core`.
- `grimm-core` will export `io.vidocq.grimm.internal` as a **qualified export** to
  `io.vidocq.grimm.cdi.vauban` only — any new internal class is invisible outside
  `cdi-vauban` without a `module-info.java` change.
- The BCE is a **CDI 4.1 Build Compatible Extension**
  (`jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`) — not the
  legacy `jakarta.enterprise.inject.spi.Extension`. Declared via `provides … with` in the
  `module-info` of `cdi-vauban`.

## Boundaries Not to Break

- Never put `grimm-tck` back in the reactor: deliberately excluded due to the ShrinkWrap Maven
  Resolver / Model 4.0.0 vs 4.1.0 incompatibility (ecosystem-wide Vidocq constraint).
- `grimm-core` must **never import** any CDI class (`jakarta.enterprise.*`, `jakarta.inject.*`),
  any Vauban class, or any Cassini/Chappe class.
- **No `synchronized` blocks** — use `ReentrantLock.tryLock()`, `Semaphore`, `AtomicReference`.
  `synchronized` pins virtual threads.
- **No `ThreadLocal`** — use `ScopedValue` (JEP 506) to propagate request context.
- **No `java.lang.reflect.Proxy`** — annotation access via direct reflection or `MethodHandle`.
- **No `setAccessible(true)`** in production — open packages in `module-info.java` when
  reflective access is needed, and document why.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) for `grimm-core` and
  `grimm-cdi-vauban` unit tests. The TCK uses **TestNG** (upstream constraint).
- Any `<scope>compile|runtime</scope>` dependency addition requires the `dependency-gatekeeper`
  agent and an explicit justification in the PR.

## Java Modules Convention — `module-info` placement

- In `grimm-core` and `grimm-cdi-vauban`, place `module-info.java` under
  `src/main/module-info/` (not `src/main/java/`). This prevents Maven Compiler Plugin from
  switching to Java Modules mode during `testCompile` (test-scope dependencies like Vauban/Ravel
  are not on the module path).
- `module-info.class` is compiled alone in the `prepare-package` phase; `maven-clean-plugin`
  removes it before incremental builds.
- Module-path JARs are assembled by `maven-dependency-plugin` in the `initialize` phase
  (copied to `target/javamodules/`). Any new module-path dependency must be referenced in
  that copy step.
- `microprofile-openapi-api:4.1` provides `module-info.class`; use
  `requires org.eclipse.microprofile.openapi` in `module-info.java`.
- MP Config API is consumed via `io.vidocq.ravel:ravel-mp-config-api`, which provides
  `module org.eclipse.microprofile.config` for Java Modules/jlink compatibility.

## Model Build Pipeline

The OpenAPI document is assembled once at CDI container startup by `grimm-cdi-vauban`:

1. **`StaticFileReader`** — reads `META-INF/openapi.yaml` / `.json` / `.yml` from the
   deployment classpath.
2. **`ModelReaderInvoker`** — instantiates the class named by `mp.openapi.model.reader` (if
   set) and calls `buildModel()`.
3. **`AnnotationScanner`** — scans JAX-RS resource classes + MP OpenAPI annotations
   (respecting `mp.openapi.scan.*` config keys).
4. **`ModelMerger`** — merges the three `ModelSource` values with spec order/priority:
   annotations > `OASModelReader` > static file (MP OpenAPI §4.4).
5. **`FilterInvoker`** — instantiates the class named by `mp.openapi.filter` (if set) and
   applies each filter method.
6. **`GrimmModelCache`** — holds the immutable final `OpenAPI` document for the lifetime of
   the application.

## `/openapi` Endpoint

- Served by `OpenApiResource` in `grimm-cdi-vauban` — a plain JAX-RS `@Path("/openapi")`
  `@GET` resource.
- Content negotiation via `Accept` header: `application/yaml` (default per spec §2.2),
  `application/json`.
- Reads from `GrimmModelCache`; no recomputation per request.
- `format` query parameter (`?format=json` / `?format=yaml`) overrides `Accept` (spec §2.3).

## Useful Workflows

```bash
# Initialise SDK environment
sdk env

# Full reactor build
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test (installs reactor then runs TCK)
./run-official-tck-mp-openapi-4.1.sh

# TCK — full suite
./run-official-tck-mp-openapi-4.1.sh all

# TCK — targeted test (e.g. AnnotationScanTest)
./run-official-tck-mp-openapi-4.1.sh -Dtest=AnnotationScanTest

# JMH benchmarks
./mvnw -pl grimm-bench -Pbench package
java -jar grimm-bench/target/benchmarks.jar
```

- The TCK always goes through the root script, which first installs the reactor then invokes
  `mvn -f grimm-tck/pom.xml -Ptck-official test`.
- The TCK requires the non-public artifact in the local M2 — see `grimm-tck/README.md` for
  the install procedure.

## Contribution Conventions

- **Strict TDD**: Red → Green → Refactor. No production line before a failing test justifies it.
  Cite the MicroProfile OpenAPI 4.1 spec section in test comments (e.g. `// §3.5.1`).
- Unit tests in the same package as the tested class, named `<Class>Test`.
- No Mockito — manual test doubles (`FakeOASModelReader`, `FakeOASFilter`,
  `FakeConfigSource`, etc.).
- Scanner logic in `grimm-core` is testable without a CDI container — that is the whole point
  of the `grimm-core` / `grimm-cdi-vauban` separation.
- JMH benchmarks in `grimm-bench` — comparison vs SmallRye OpenAPI on the same JVM.
  Results recorded in `BENCH.md` at the project root.
- Reproducible bugs tracked in `BUG.md` with: id, date, symptom, minimal repro, hypothesis,
  status.
- For non-trivial changes (scanner phase, `ModelMerger`, BCE, `module-info.java`), enter plan
  mode and record architectural decisions in `ROADMAP.md` (section "Actioned Decisions").
- Use `virtual-threads-reviewer` for concurrent code changes, `java-modules-guardian` after
  `module-info.java`/package changes, and `tck-runner` to diagnose TCK failures.
- **Language**: Commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## What an Agent Should Assume for Upcoming Tasks

- `grimm-core` will contain: `OASFactoryImpl`, model POJOs (implementing all MP OpenAPI
  model interfaces), `AnnotationScanner`, `StaticFileReader`, `ModelMerger`, `FilterInvoker`,
  `ModelReaderInvoker`, JSON and YAML serializers, `ScanConfig` record, `FilterConfig` record.
- `grimm-cdi-vauban` will contain: `GrimmExtension` (BCE CDI 4.1), `GrimmModelCache`
  (`@ApplicationScoped`), `OpenApiResource` (JAX-RS resource), `GrimmConfigProducer`.
- The Java module name for `grimm-core` is `io.vidocq.grimm.core`; for `grimm-cdi-vauban`
  it is `io.vidocq.grimm.cdi.vauban`.
- Configuration keys follow the MP OpenAPI spec §4.1:
  `mp.openapi.model.reader`, `mp.openapi.filter`, `mp.openapi.scan.disable`,
  `mp.openapi.scan.packages`, `mp.openapi.scan.classes`,
  `mp.openapi.scan.exclude.packages`, `mp.openapi.scan.exclude.classes`,
  `mp.openapi.servers`, `mp.openapi.schema.<FQCN>`.
- The annotation scanning order must follow spec §3.7 (class-level annotations override
  method-level for Info, Tags, Servers; method-level `@Operation` takes precedence over
  class-level `@Tag`).
- Before any structural change to `GrimmExtension` or `AnnotationScanner`, reason against the
  contract: **MicroProfile OpenAPI 4.1 TCK at 100% PASS**.

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
