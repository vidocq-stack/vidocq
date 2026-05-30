# Grimm — Implementation Roadmap

> MicroProfile OpenAPI 4.1 implementation in the Vidocq style: zero third-party implementation
> libraries (Jakarta EE / MicroProfile spec APIs only), Java 25, virtual threads, strict JPMS,
> CDI via Vauban, configuration via Ravel, transport via Cassini/Chappe.

## Guiding Principles

| Principle | Concrete application |
|---|---|
| Zero implementation library | No SmallRye OpenAPI, Swagger Core, Jackson, Snakeyaml in `grimm-core`. Only spec APIs compiled. |
| Scanner / CDI separation | `grimm-core` contains pure Java scanning + serialization; `grimm-cdi-vauban` holds the single BCE and the JAX-RS endpoint. |
| Virtual threads | Any parallel scanning uses `VirtualThreadPerTaskExecutor`. No `synchronized`, no `ThreadLocal`. |
| Strict JPMS | `module-info.java` everywhere, `internal.*` not exported, SPI via `provides/uses`. No unjustified `opens`. |
| Strict TDD | Red → Green → Refactor. Test before code. Cite spec section in test comments. |
| TCK 100% PASS | Hard contract before any structural merge. Score declared in `TCK.md`. |
| Measured performance | JMH from M5+, comparison vs SmallRye OpenAPI; results in `BENCH.md`. |
| AOT-friendly | No `Proxy.newProxyInstance`. Annotation reading via direct reflection + `MethodHandles`. GraalVM native-image compatible. |

## Testing Strategy: TDD + TCK as parallel safety nets

- **Layer 1 — TDD unit tests**: drive the design of each `grimm-core` component. Testable
  without a CDI container.
- **Layer 2 — CDI integration tests**: multi-component scenarios with Vauban embedded.
  Verify the build pipeline without TCK overhead.
- **Layer 3 — Official TCK** (`microprofile-openapi-tck:4.1`): hard 100% PASS contract before
  any structural merge. Out-of-reactor module (Model 4.0.0).
- **Layer 4 — JMH benchmarks**: `grimm-bench` measures scanning throughput and serialization
  latency vs SmallRye OpenAPI on the same JVM.

## Module Architecture

```
grimm-core              io.vidocq.grimm.core
  exports io.vidocq.grimm.internal
          to io.vidocq.grimm.cdi.vauban     (qualified export)
  requires org.eclipse.microprofile.openapi
  requires jakarta.ws.rs
  requires jakarta.annotation
  requires org.eclipse.microprofile.config
  → OASFactoryImpl, model POJOs (OpenAPIImpl, PathItemImpl, OperationImpl, …),
    AnnotationScanner, StaticFileReader, ModelMerger, FilterInvoker,
    ModelReaderInvoker, JsonSerializer, YamlSerializer,
    ScanConfig (record), FilterConfig (record), ServerConfig (record)

grimm-cdi-vauban        io.vidocq.grimm.cdi.vauban
  requires io.vidocq.grimm.core
  requires org.eclipse.microprofile.openapi
  requires jakarta.enterprise.cdi
  requires jakarta.ws.rs
  requires jakarta.annotation
  requires org.eclipse.microprofile.config
  requires io.vidocq.vauban.api
  → GrimmExtension (BCE CDI 4.1 BuildCompatibleExtension),
    GrimmModelCache (@ApplicationScoped),
    OpenApiResource (@Path("/openapi") @ApplicationScoped),
    GrimmConfigProducer (@ApplicationScoped),
    GrimmAutoDiscovery (ServiceLoader bridge for OASFactory)

grimm-tck               (out-of-reactor — Model 4.0.0)
  → TestNG + Arquillian + Vauban embedded + Cassini/Chappe, official TCK runner

grimm-bench             io.vidocq.grimm.bench
  → JMH benchmarks: scanning throughput, serialization latency vs SmallRye OpenAPI

grimm-examples          io.vidocq.grimm.examples
  → Standalone examples and integrated Vidocq usage
```

---

## Phases

### M0 — Bootstrap

- [x] `.sdkmanrc` (`java=25-tem`, `maven=3.9.16`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] Parent `pom.xml` (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile)
- [x] `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md` (these files)
- [x] Sub-module scaffolding with `pom.xml` + `module-info.java` skeletons:
      `grimm-core`, `grimm-cdi-vauban`, `grimm-bench`, `grimm-examples`, `grimm-tck` (out-of-reactor)
- [x] `LICENSE` (Apache 2.0)
- [x] `README.md`
- [x] `run-official-tck-mp-openapi-4.1.sh` (root TCK script)
- [x] Validate `./mvnw -ntp install -DskipTests` passes on the reactor
- [x] Validate `mvn -f grimm-tck/pom.xml -DskipTests compile` passes (out-of-reactor)

**Deliverable:** Compilable reactor, coherent `module-info.java` skeletons, out-of-reactor TCK
compilable.

---

### M1 — Core Model: OASFactory + POJOs

**Spec scope:** §3.1 (MicroProfile OpenAPI Model), §3.2 (OASFactory).

| Task | Notes | Status |
|---|---|---|
| `OASFactoryImpl` | Implements `OASFactory` SPI; registered via `ServiceLoader` (`META-INF/services` + JPMS `provides`) | ☑ |
| `OpenAPIImpl` record/POJO | Implements `org.eclipse.microprofile.openapi.models.OpenAPI` | ☑ |
| `InfoImpl` | Implements `Info` | ☑ |
| `PathItemImpl` | Implements `PathItem` | ☑ |
| `OperationImpl` | Implements `Operation` | ☑ |
| `ParameterImpl` | Implements `Parameter` | ☑ |
| `RequestBodyImpl` | Implements `RequestBody` | ☑ |
| `APIResponseImpl` / `APIResponsesImpl` | Implements `APIResponse` / `APIResponses` | ☑ |
| `SchemaImpl` | Implements `Schema` (OpenAPI 3.1 — supports JSON Schema dialect) | ☑ |
| `TagImpl`, `ServerImpl`, `ContactImpl`, `LicenseImpl` | Metadata model objects | ☑ |
| `SecuritySchemeImpl`, `SecurityRequirementImpl` | Security model objects | ☑ |
| `CallbackImpl`, `LinkImpl`, `HeaderImpl` | Advanced model objects | ☑ |
| `ComponentsImpl` | `components` object for shared definitions | ☑ |
| `MediaTypeImpl`, `EncodingImpl` | Request/response media types | ☑ |
| `ExternalDocumentationImpl`, `AbstractExtensible` | Cross-cutting model support | ☑ |
| Unit tests — builder API | Round-trip: build via `OASFactory` → verify all fields | ☑ |

**Decisions M1:**
- All model POJOs are mutable plain Java objects (MP OpenAPI API imposes a builder-style fluent
  API; immutable records cannot satisfy it). Use `null` for absent optional fields.
- `OASFactory` is discovered by the API itself via `ServiceLoader` — declare
  `provides org.eclipse.microprofile.openapi.spi.OASFactoryResolver with
  io.vidocq.grimm.internal.factory.GrimmOASFactoryResolver` in `module-info.java`.

**Deliverable:** All MP OpenAPI model interfaces implemented; `OASFactory` wired via
ServiceLoader.

---

### M2 — JSON and YAML Serialization

**Spec scope:** §2.2 (OpenAPI document format), §2.3 (`format` parameter).

| Task | Notes | Status |
|---|---|---|
| `JsonSerializer` | Serializes `OpenAPI` model to JSON string; zero external JSON library — hand-written using `java.io` / `StringBuilder` or `javax.json` (Jakarta JSON-P via Champollion) | ☑ |
| `YamlSerializer` | Serializes `OpenAPI` model to YAML string; zero external YAML library — hand-written indented serializer | ☑ |
| `JsonDeserializer` | Parses JSON `OpenAPI` document (for static file reading); JSON-P streaming | ☑ |
| `YamlDeserializer` | Parses YAML `OpenAPI` document (for static file reading) | ☑ |
| Round-trip unit tests | Serialize → deserialize → verify equality for all model objects | ☑ |
| `format` query parameter | `?format=json` vs `?format=yaml` (implémenté dans `OpenApiResource`, validé sur tests HTTP + TCK) | ☑ |

**Decisions M2:**
- JSON serialization delegates to Champollion (Jakarta JSON-P 2.1) — already in the Vidocq
  ecosystem, zero additional dependency. Add `requires champollion.jsonp` (or the spec module
  `jakarta.json`) in `module-info.java`.
- YAML serialization is hand-written: OpenAPI YAML is regular indented YAML with a small
  vocabulary; no need for a full YAML library.
- Deserialization (for static files) also uses Champollion JSON-P streaming for JSON; YAML
  is converted to a JSON-like map structure by the hand-written YAML parser.

**Deliverable:** Full round-trip JSON ↔ model and YAML ↔ model for all objects in the spec.

---

### M3 — Static File Reader

**Spec scope:** §4.2 (Static OpenAPI files).

| Task | Notes | Status |
|---|---|---|
| `StaticFileReader` | Reads `META-INF/openapi.yaml`, `openapi.yml`, `openapi.json` from the deployment classpath (in that priority order per spec) | ☑ |
| Format detection | Detect file format from extension; delegate to `YamlDeserializer` or `JsonDeserializer` | ☑ |
| Classpath scanning | Use `ClassLoader.getResourceAsStream` — JPMS-safe, no file-system assumptions | ☑ |
| Absent file | Return `Optional.empty()` cleanly (not an error) | ☑ |
| Unit tests | Files present / absent / malformed | ☑ |

**Deliverable:** Static file reading works independently; tested without CDI.

---

### M4 — Annotation Scanner: Document-level Annotations

**Spec scope:** §3.3 (Annotations), §3.4 (`@OpenAPIDefinition`), §3.5 (`@Tag`, `@Server`).

| Task | Notes | Status |
|---|---|---|
| `AnnotationScanner` — skeleton | Accepts a list of classes; produces a partial `OpenAPI` model | ☑ |
| `ScanConfig` record | `disableScan`, `includePackages`, `includeClasses`, `excludePackages`, `excludeClasses` | ☑ |
| `@OpenAPIDefinition` processing | Maps `info`, `tags`, `servers`, `security`, `externalDocs` to the model | ☑ |
| `@Tag` / `@Tags` processing | Class-level and method-level; deduplication by name | ☑ |
| `@Server` / `@Servers` processing | Class-level and method-level; merged per spec §3.5.2 | ☑ |
| `@ExternalDocumentation` processing | Top-level and operation-level | ☑ |
| Unit tests | Classes bearing each annotation; verify model output | ☑ |

**Deliverable:** Document-level annotations scanned and merged into the model correctly.

---

### M5 — Annotation Scanner: Operation Annotations

**Spec scope:** §3.6 (`@Operation`), §3.7 (`@Parameter`), §3.8 (`@RequestBody`),
§3.9 (`@APIResponse`).

| Task | Notes | Status |
|---|---|---|
| JAX-RS resource class discovery | Identify `@Path`-annotated classes; derive HTTP method + path from `@GET`/`@POST`/… + `@Path` | ☑ |
| `@Operation` processing | Maps `operationId`, `summary`, `description`, `tags`, `deprecated`, `hidden` | ☑ |
| `@Parameter` / `@Parameters` | `in` (path/query/header/cookie), `name`, `required`, `schema`, `description` | ☑ |
| JAX-RS parameter inference | Infer path/query/header parameters from `@PathParam`, `@QueryParam`, `@HeaderParam` when no explicit `@Parameter` | ☑ |
| `@RequestBody` processing | `content`, `description`, `required`; infer from JAX-RS `@Consumes` + entity body parameter | ☑ |
| `@APIResponse` / `@APIResponses` | Status codes, `content`, `description`, `headers` | ☑ |
| Response inference | Infer 200 response with return type schema when no explicit `@APIResponse` | ☑ |
| `@Callback` / `@Callbacks` | Maps callbacks on operations | ☑ |
| Unit tests | JAX-RS resources with various annotation combinations; verify generated `PathItem` / `Operation` | ☑ |
| First JMH benchmark baseline | Scanning throughput on un set de 50 ressources vs SmallRye OpenAPI (reporté en backlog M11, non bloquant pour M10) | ↻ |

**Deliverable:** Fully annotated JAX-RS resources produce correct `paths` in the model.

---

### M6 — Schema Generation

**Spec scope:** §3.10 (`@Schema`), §3.11 (Schema generation from Java types), OpenAPI 3.1
JSON Schema dialect.

| Task | Notes | Status |
|---|---|---|
| `SchemaGenerator` | Maps Java types → `Schema` objects: primitives, `String`, `Date`/`Instant`, `UUID`, `BigDecimal` | ☑ |
| Collection types | `List<T>`, `Set<T>`, `T[]` → `type: array, items: schemaOf(T)` | ☑ |
| Map types | `Map<String,V>` → `type: object, additionalProperties: schemaOf(V)` | ☑ |
| POJO introspection | Public getters + fields → `type: object, properties: …`; respect `@JsonbProperty` if present | ☑ |
| Enum types | `type: string, enum: [values]` | ☑ |
| `@Schema` annotation override | Explicit `@Schema` on field/class overrides inferred schema | ☑ |
| `@Schema(hidden=true)` | Exclude field/class from generated schema | ☑ |
| `@Schema(ref=…)` | `$ref` resolution to `#/components/schemas/…` | ☑ |
| Recursive / circular types | Detect cycles; emit `$ref` to `components/schemas` | ☑ |
| `SchemaRegistry` | Shared registry for named schemas → `components/schemas`; avoids duplication | ☑ |
| `mp.openapi.schema.<FQCN>` | Config-driven schema override for a fully-qualified class name (livré via `ConfigApplier` en M8) | ☑ |
| Unit tests | All Java-to-schema mappings; circular reference detection; `@Schema` override | ☑ |

**Deliverable:** Schema generation from Java types; `components/schemas` populated correctly.

---

### M7 — OASModelReader and OASFilter

**Spec scope:** §4.1 (`OASModelReader`), §4.3 (`OASFilter`).

| Task | Notes | Status |
|---|---|---|
| `ModelReaderInvoker` | Loads class named by `mp.openapi.model.reader` via `Class.forName` + instantiation; calls `buildModel()` | ☑ |
| `FilterInvoker` | Loads class named by `mp.openapi.filter`; calls each `filterXxx` method in the order defined by spec §4.3 | ☑ |
| Filter method order | `filterOpenAPI` is called last (§4.3); implement the full sequence: `filterPathItem`, `filterOperation`, `filterParameter`, `filterRequestBody`, `filterAPIResponse`, `filterSchema`, `filterHeader`, `filterTag`, `filterServer`, `filterLink`, `filterCallback`, `filterOpenAPI` | ☑ |
| `null` return from filter | A filter method returning `null` removes the element from the model (spec §4.3.1) | ☑ |
| `ModelMerger` — three-source merge | Priority: annotations > `OASModelReader` > static file (spec §4.4) | ☑ |
| `ModelSource` sealed interface | `StaticFileSource`, `AnnotationSource`, `ReaderSource` — used by `ModelMerger` | ☑ |
| Unit tests `FilterInvoker` | Filter that removes operations; filter that renames tags | ☑ |
| Unit tests `ModelReaderInvoker` | Reader that adds a server; reader that sets `info` | ☑ |
| Unit tests `ModelMerger` | All three sources present; priority conflicts; absent sources | ☑ |

**Deliverable:** Full programmatic model extension and post-processing pipeline working.

---

### M8 — Configuration Support

**Spec scope:** §4.1 (MP Config integration for scan properties).

| Task | Notes | Status |
|---|---|---|
| `GrimmConfig` | Reads all `mp.openapi.*` keys via `ConfigProvider.getConfig()` (Ravel); immutable snapshot at startup | ☑ |
| `FilterConfig` | Configuration for filter and model reader class names | ☑ |
| `mp.openapi.scan.disable` | Skip annotation scanning entirely when `true` | ☑ |
| `mp.openapi.scan.packages` | Comma-separated list of packages to include | ☑ |
| `mp.openapi.scan.classes` | Comma-separated list of classes to include | ☑ |
| `mp.openapi.scan.exclude.packages` | Packages to exclude (takes priority over include) | ☑ |
| `mp.openapi.scan.exclude.classes` | Classes to exclude | ☑ |
| `mp.openapi.filter` | FQCN of `OASFilter` implementation | ☑ |
| `mp.openapi.model.reader` | FQCN of `OASModelReader` implementation | ☑ |
| `mp.openapi.servers` | Comma-separated list of server URLs to set on the model | ☑ |
| `mp.openapi.schema.<FQCN>` | JSON string defining the schema for a class (already in M6) | ☑ |
| `mp.openapi.extensions.scan.disable` | Disable `@Extension` annotation scanning | ☑ |
| Integration tests | `META-INF/microprofile-config.properties` + Ravel; all config keys exercised | ☑ |

**Deliverable:** All spec-defined configuration keys honoured; tested via Ravel config provider.

---

### M9 — CDI BCE + `/openapi` Endpoint

**Spec scope:** §2.1 (OpenAPI document endpoint), §2.2 (format), §2.3 (`format` parameter).

| Task | Notes | Status |
|---|---|---|
| `GrimmExtension` BCE | `BuildCompatibleExtension`; `@Enhancement` on JAX-RS resource beans; `@Synthesis` to register `GrimmModelCache` | ☑ |
| `GrimmModelCache` `@ApplicationScoped` | Holds the final `OpenAPI` document built by the pipeline; thread-safe lazy init | ☑ |
| `GrimmConfigProducer` `@ApplicationScoped` | Reads `GrimmConfig` from Ravel at startup; CDI-producible for injection | ☑ |
| `OpenApiResource` `@Path("/openapi")` | `@GET` method; reads from `GrimmModelCache`; content negotiation JSON/YAML; `format` query param | ☑ |
| Cassini/Chappe integration | `OpenApiResource` discovered by Cassini scanner as a standard JAX-RS resource — no special adapter needed | ☑ |
| `GrimmAutoDiscovery` | ServiceLoader bridge registering the `OASFactoryResolver` for the CDI environment | ☑ |
| CDI integration tests | Vauban embedded; inject `GrimmModelCache`; verify model content | ☑ |
| HTTP endpoint test | Start Cassini + Chappe; GET `/openapi`; verify JSON and YAML responses | ☑ |

**Deliverable:** Full end-to-end pipeline from annotated JAX-RS resources to `/openapi` HTTP
endpoint, integrated with Vauban (CDI) and Cassini (JAX-RS).

---

### M10 — Official TCK MicroProfile OpenAPI 4.1

**Scope:** Full `microprofile-openapi-tck:4.1` suite.

| Task | Notes | Status |
|---|---|---|
| `grimm-tck/pom.xml` (Model 4.0.0) | Dépendances TCK/Arquillian + Vauban/Cassini/Chappe, **out-of-reactor** maintenu | ☑ |
| `GrimmDeployableContainer` | Container Arquillian custom démarrant Vidocq+Grimm et gérant deploy/undeploy ShrinkWrap | ☑ |
| Bootstrap du déploiement TCK | Chargement classes/config + bridge CDI via `TckDeploymentContext`/`TckGrimmSupportProducer` | ☑ |
| Intégration Arquillian | `GrimmArquillianExtension` + `arquillian.xml` (`qualifier=grimm`, default) | ☑ |
| Découverte des tests officiels | Profil `tck-official` via `dependenciesToScan` (pas de `tck-suite.xml` dédié) | ☑ |
| `run-official-tck-mp-openapi-4.1.sh` | Script root: `smoke` / `all` / `matrix` / ciblé `-Dtest=...` | ☑ |
| Smoke TCK green | `GrimmTckSmokeTest` PASS | ☑ |
| Progressive TCK pass | Itérations réalisées et consignées dans `TCK.md` | ☑ |
| TCK 100% PASS | Suite officielle complète verte (349 tests) | ☑ |
| `TCK.md` | Score courant + exclusions documentées (aucune exclusion active) | ☑ |
| `grimm-tck/README.md` | Procédure d'installation locale + architecture runner documentées | ☑ |

**Decisions M10:**
- Arquillian container is minimal: start Vauban (CDI) + Grimm BCE + Cassini/Chappe HTTP server;
  register JAX-RS resources from the test archive; run tests via the enricher.
- The `/openapi` endpoint must be reachable by the TCK over HTTP — Cassini + Chappe provide
  this; port configured via Arquillian `arquillian.xml`.
- `mp.openapi.scan.packages` will be set to the TCK test classes package to scope the scanner.

**Deliverable:** Measurable, reproducible TCK score; target = 100% PASS.

---

## Known Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OpenAPI 3.1 JSON Schema dialect | `SchemaImpl` must support `$vocabulary`, `$schema`, `if/then/else`, `unevaluatedProperties` (new in 3.1) | Track breaking changes vs 3.0 in `TCK.md`; add tests per dialect feature |
| YAML serialization correctness | Hand-written YAML may miss edge cases (multi-line strings, special chars) | Extensive round-trip tests; validate against OpenAPI Parser in `grimm-tck` |
| Circular schema detection | StackOverflow risk in deep object graphs | `SchemaRegistry` with a `Set<Class<?>>` visited guard in `SchemaGenerator` |
| TCK non-public artifact | Blocked if not installed in M2 local | Document in `grimm-tck/README.md`; CI install script |
| `OASFactory` ServiceLoader in JPMS | `provides` declaration in `module-info.java` must match the `GrimmOASFactoryResolver` class exactly | Validate with `java --list-modules` smoke test in M0 |
| Cassini resource scanning | `GrimmExtension` must discover JAX-RS resources before Cassini scans them | Hook BCE in the `@Enhancement` phase; ensure ordering via CDI priorities |
| MP Config key `mp.openapi.schema.<FQCN>` | FQCN contains dots — may collide with Config key namespace | Use `Config.getPropertyNames()` + prefix filter; test with Ravel |

## Actioned Decisions

- [x] `grimm-core` (pure scanner + serializer) / `grimm-cdi-vauban` (CDI BCE + endpoint)
      separation — same pattern as Heisenberg core/cdi-vauban
- [x] JSON serialization via Champollion (Jakarta JSON-P 2.1) — no new dependency
- [x] YAML serialization hand-written — no Snakeyaml, no third-party parser
- [x] Model build pipeline order: static file → OASModelReader → annotation scanner → merge →
      OASFilter (spec §4.4)
- [x] `grimm-tck/pom.xml` on Model 4.0.0 — ShrinkWrap constraint (ecosystem-wide)
- [x] `/openapi` endpoint as a plain JAX-RS resource discovered by Cassini — no special adapter
- [x] M1 completed: all MP OpenAPI model POJOs + `OASFactoryResolver` ServiceLoader wiring
- [x] JPMS alignment: use `requires org.eclipse.microprofile.openapi` (real module name in MP OpenAPI 4.1)
- [x] MP Config API is consumed via `io.vidocq.ravel:ravel-mp-config-api` (repackaged modular API)
- [x] M2 completed: JSON/YAML serialization with round-trip tests
- [x] M3 completed: StaticFileReader with classpath scanning
- [x] M4 completed: AnnotationScanner with document-level annotations + ScanConfig filtering
- [x] M5 core completed: JAX-RS resource scanning — `@Operation`, `@Parameter`/JAX-RS param
      inference, `@RequestBody` (+ `@Consumes` inference), `@APIResponse`/`@APIResponses`
      (+ default 200 inference), `@Callback`/`@Callbacks`. JMH baseline benchmark deferred.
- [x] M6 core completed: `SchemaGenerator` + `SchemaRegistry` (scalars, collections, maps,
      enums, POJOs, recursive refs, `@Schema` overrides/hidden/ref/implementation). Wired into
      `JaxRsResourceScanner`: parameters, request body, and response media types now carry
      generated schemas, and `components/schemas` is populated. `mp.openapi.schema.<FQCN>`
      config override deferred to M8.
- [x] M7 completed: full `OASFilter` traversal (spec §4.3 — paths, operations, parameters,
      request bodies, responses, schemas, headers, tags, servers, links, callbacks, security
      schemes; `filterOpenAPI` last) with `null`-return removal (§4.3.1); `ModelMerger`
      priority fix (annotation/reader paths now overwrite static); comprehensive unit tests
      (FilterInvoker null-removal × 6 + filterOpenAPI-last ordering, ModelMerger path conflicts,
      union, tag override, absent sources).
- [x] M8 completed: `GrimmConfig` immutable snapshot reading every spec §4.1 key
      (scan.* / filter / model.reader / servers / schema.<FQCN> / extensions.scan.disable);
      `ConfigApplier` applies `mp.openapi.servers` to the model and registers JSON schema
      overrides in `SchemaRegistry`; exposed `JsonDeserializer.parseRaw`; integration tests
      use a manual MP-Config double (no impl dependency).
- [x] M9 completed: CDI runtime layer includes `GrimmExtension` (BCE class discovery),
      `GrimmConfigProducer`, `GrimmModelCache`, `GrimmAutoDiscovery`, and a CDI-wired
      `OpenApiResource` bound to `/openapi`; verified with embedded Vauban injection test
      (`GrimmVaubanIntegrationTest`) and real HTTP transport test through Cassini+Chappe
      (`OpenApiHttpChappeTest`) covering YAML default and JSON `format` override.
- [x] M10 completed: harness TCK stable (`grimm-tck` out-of-reactor, container Arquillian
      custom, smoke + runs ciblés + full run) avec score officiel **349/349 PASS**.
- [x] M10 infra hardening: custom Arquillian container supports a runtime matrix
      (`default-readiness` / `extended-readiness` / `no-readiness-probe`) with
      configurable `grimm.tck.*` overrides and `/openapi` readiness probing to
      reduce transient HTTP reachability failures during diagnostics.
- [x] M7 core completed: ModelMerger with sealed interface + ModelReaderInvoker + FilterInvoker
- [x] M11 started: `grimm-bench` now runs JMH baseline `OpenApiPipelineBenchmark`
      (model build throughput + cached JSON/YAML rendering throughput), first local results
      published in `BENCH.md`.

## Backlog Post-M10 (M11+)

- [x] Ajouter un premier benchmark JMH de reference (scanner throughput) vs SmallRye OpenAPI,
      puis publier les resultats dans `BENCH.md`.
- [ ] Ajouter un profil de publication benchmark (iterations/forks longs) et historiser les sorties brutes.
- [ ] Ajouter des benchmarks de comparaison SmallRye OpenAPI sur la meme JVM et les memes parametres JMH.

## Open Decisions

- **Parallel scanning**: should `AnnotationScanner` scan multiple classes concurrently with
  virtual threads? Likely beneficial for large deployments, but adds complexity. Defer to M5
  benchmarks.
- **`components/schemas` naming**: use simple class name or FQCN for generated schema names?
  The spec does not mandate a naming scheme. Use simple name with a collision-resolution suffix.
- **`@JsonbTransient` / `@JsonbProperty` awareness**: should `SchemaGenerator` respect
  Jakarta JSON-B annotations when introspecting POJOs? Logical but not required by spec; decide
  at M6.
- **Webhook support** (new in OpenAPI 3.1): `@Webhook` annotation in MP OpenAPI 4.x (confirm
  if included in 4.1 or deferred to 4.2).
