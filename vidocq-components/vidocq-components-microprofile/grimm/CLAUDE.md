# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Jacob Grimm (1785–1863) devoted his life to cataloguing language — codifying the rules of
> phonetic transformation (Grimm's Law), gathering folk stories, and compiling the
> _Deutsches Wörterbuch_. **Grimm** the project implements **MicroProfile OpenAPI 4.1**: it
> catalogues API contracts, describes every endpoint, and turns running services into living
> documentation.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — run `sdk env`)
- The official TCK `org.eclipse.microprofile.openapi:microprofile-openapi-tck:4.1`
  must be installed in the local M2 repository (non-public artifact — see `grimm-tck/README.md`)

## Essential Commands

```bash
# Set up SDK environment
sdk env

# Full reactor build (no tests)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test
./run-official-tck-mp-openapi-4.1.sh

# TCK — full suite
./run-official-tck-mp-openapi-4.1.sh all

# TCK — targeted test
./run-official-tck-mp-openapi-4.1.sh -Dtest=ClassName
```

> `grimm-tck` is **outside the reactor** (pom.xml with standalone Model 4.0.0) to work around
> the ShrinkWrap Maven Resolver 3.3 / Model 4.1.0 incompatibility. Do not change this model.
> See the root Vidocq `CLAUDE.md` § *Critical architecture constraint: TCK runners outside the
> reactor*.

## Architecture

Grimm is a **MicroProfile OpenAPI 4.1** implementation with zero implementation dependencies:
only the MicroProfile OpenAPI API, required Jakarta specs, and the Vidocq modular repackage
`io.vidocq.ravel:ravel-mp-config-api` are compiled.

```
grimm-core         ← Scanner, OASFactory impl, model POJOs, JSON/YAML serializer, model merger
grimm-cdi-vauban   ← CDI BCE (model built at startup) + /openapi JAX-RS endpoint
grimm-tck          ← TestNG + Arquillian + official MP OpenAPI 4.1 TCK runner (out-of-reactor)
grimm-bench        ← JMH benchmarks vs SmallRye OpenAPI
grimm-examples     ← Standalone and integrated usage examples
```

**Fundamental separation:** `grimm-core` contains all pure Java scanning and serialization logic,
fully independent of CDI. `grimm-cdi-vauban` is the CDI integration layer: a BCE that discovers
annotated beans at startup, orchestrates the model build pipeline, and exposes the result via a
JAX-RS resource class.

**Model build pipeline** (executed once at container startup, result cached):
1. Read static file (`META-INF/openapi.yaml` / `.json` / `.yml`) — `StaticFileReader`
2. Invoke `OASModelReader` if configured (`mp.openapi.model.reader`) — `ModelReaderInvoker`
3. Scan JAX-RS resource classes + MP OpenAPI annotations — `AnnotationScanner`
4. Merge all three sources — `ModelMerger`
5. Apply `OASFilter` if configured (`mp.openapi.filter`) — `FilterInvoker`
6. Cache the final `OpenAPI` document — `GrimmModelCache`

**`/openapi` endpoint:** a JAX-RS resource class (`OpenApiResource`) in `grimm-cdi-vauban` that
reads from `GrimmModelCache` and serves the document as `application/json` or `application/yaml`
(content negotiation via `Accept` header; default is YAML per spec §2.2).

## Architecture Constraints — Do Not Violate

1. **Zero implementation imports in `grimm-core`** — only `microprofile-openapi-api`,
   `io.vidocq.ravel:ravel-mp-config-api`, `jakarta.ws.rs-api`, `jakarta.annotation-api`.
   No CDI, no Vauban, no Cassini, no Chappe.
2. **No `synchronized` blocks, no `ThreadLocal`** — virtual-thread-friendly.
   Use `ReentrantLock`, `ConcurrentHashMap`, `ScopedValue` where shared state is needed.
3. **No `setAccessible(true)` in production** — use `MethodHandles.privateLookupIn` for
   reflective access; document any `opens` in the `module-info`.
4. **No `java.lang.reflect.Proxy`** — annotation reading via direct reflection or `MethodHandle`;
   no dynamic proxies.
5. **`grimm-tck/pom.xml` stays on Model 4.0.0** — do not upgrade to 4.1.0 while ShrinkWrap
   is not updated (ecosystem-wide Vidocq constraint).
6. **TCK 100% PASS is a contract** — any structural change to `grimm-core` or
   `grimm-cdi-vauban` must preserve this score before merge.

## Conventions

- **Explicit Java modules**: every sub-module has a `module-info.java`.
- **Packages**:
  - `io.vidocq.grimm.api.*` — public stable SPI (`GrimmConfig`, `GrimmModelCache` interface,
    `ScanConfig`)
  - `io.vidocq.grimm.internal.*` — internal code, not exported (scanner, serializer, merger,
    reader)
  - `io.vidocq.grimm.cdi.*` — CDI integration (BCE, endpoint, config producer)
- **Maven groupId**: `io.vidocq.grimm`
- **Records**: immutable records for configuration (`ScanConfig`, `FilterConfig`, `ServerConfig`)
- **Sealed interfaces**: `ModelSource` sealed interface (`StaticFileSource`, `AnnotationSource`,
  `ReaderSource`) used by `ModelMerger`
- **Pattern matching**: use `switch` on sealed types in `ModelMerger` and `FilterInvoker`
- **Virtual threads**: if any parallel scanning is introduced, use
  `Executors.newVirtualThreadPerTaskExecutor()`
- **Language**: Commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## TDD Methodology

- **Red → Green → Refactor** — no production code without a prior test.
- Cite the MicroProfile OpenAPI 4.1 spec section in test Javadoc/comments (e.g. `// §2.3.1`).
- Unit tests in the same package as the tested class, named `<Class>Test`.
- No Mockito — manual test doubles (`FakeOASModelReader`, `FakeOASFilter`,
  `FakeConfigSource`, etc.).
- CDI integration tests via Vauban embedded (without Arquillian) in `grimm-cdi-vauban`.
- JMH benchmarks in `grimm-bench` — comparison vs SmallRye OpenAPI from M4+.

## Plan Mode Default

- Enter plan mode for any non-trivial task (adding a scanner phase, modifying `ModelMerger`,
  changing the CDI BCE, touching `module-info.java`).
- Document architecture decisions in `ROADMAP.md` (section "Actioned Decisions").
- Use the `virtual-threads-reviewer` agent for any concurrent code modification.
- Use the `jpms-guardian` agent after any package addition or `module-info.java` change.

## Available Agents

- `classfile-codegen` — if schema introspection requires compile-time annotation processing
- `virtual-threads-reviewer` — for any concurrent scanning or model caching code
- `jpms-guardian` — after `module-info.java` modification or new package introduction
- `dependency-gatekeeper` — before any `pom.xml` dependency addition
- `tck-runner` — to diagnose MicroProfile OpenAPI 4.1 TCK failures

## MicroProfile OpenAPI 4.1 TCK

- Framework: **TestNG** (not JUnit — upstream TCK constraint)
- Arquillian container: Vauban embedded + Cassini/Chappe (HTTP transport for `/openapi`)
- TCK artifact: `org.eclipse.microprofile.openapi:microprofile-openapi-tck:4.1`
- Suite file: `grimm-tck/src/test/resources/tck-suite.xml`
- Target score: **100% PASS**
- Excluded tests (if any) documented in `TCK.md` with justification

## Allowed Spec Dependencies

```
org.eclipse.microprofile.openapi:microprofile-openapi-api:4.1
jakarta.ws.rs:jakarta.ws.rs-api:4.0                            (provided)
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1              (provided)
jakarta.annotation:jakarta.annotation-api:3.0                   (provided)
io.vidocq.ravel:ravel-mp-config-api:0.1.0-SNAPSHOT              (provided)
org.junit:junit-bom:6.0.3                                       (test, BOM)
```

Any new `<scope>compile</scope>` or `<scope>runtime</scope>` dependency must pass
`dependency-gatekeeper` and be explicitly justified in the PR.
