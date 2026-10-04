# Bug Tracker

Track reproducible bugs with:
- id
- date
- symptom
- minimal repro
- hypothesis
- status

## Entries

### BUG-20260715-01 — grimm-tck references stale vidocq-runtime groupIds

- **id**: BUG-20260715-01
- **date**: 2026-07-15
- **symptom**: `./run-official-tck-mp-openapi-4.1.sh all` fails before running any test:
  `Could not resolve dependencies for io.vidocq.grimm:grimm-tck` —
  `Could not find artifact io.vidocq.runtime:vidocq-runtime-cassini-rest-extension:jar:0.3.0-SNAPSHOT`.
- **minimal repro**: build vidocq runtime `main` (`mvn install`), then run the grimm TCK script.
- **hypothesis (confirmed)**: the vidocq runtime module rework of 2026-06-08
  ("first rework of modules") moved `vidocq-runtime-cassini-rest-extension` to groupId
  `io.vidocq.runtime.extensions.jakartaee.core` and `vidocq-runtime-chappe-webserver-extension`
  to `io.vidocq.runtime.extensions.essentials`. `grimm-tck` (out-of-reactor) still declared the
  old `io.vidocq.runtime` groupId, which is no longer installed locally; resolution then falls
  back to the invalid snapshot on central-snapshots (see vidocq/BUG.md) and fails.
  `vidocq-runtime-core` did not move.
- **status**: FIXED — grimm-tck/pom.xml updated to the new groupIds;
  full MP OpenAPI 4.1 TCK green again (349 tests, 0 failures, 0 errors, 0 skipped).


## BUG-20261001-01 — Grimm ships automatic modules: `/openapi` lost in a modular application (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/named-modules`, with vauban `fix/inject-constructor-over-no-arg`, cassini `fix/no-split-into-automatic-module` and the Vidocq extension's `requires io.vidocq.grimm.cdi.vauban`)
- **Symptom** (reported by Sébastien Blanc on 0.4.0-SNAPSHOT): a Vidocq application whose build runs `cassini-maven-plugin:generate` with `scope=dependencies` does not serve `/openapi`. Grimm is "not active in the container", the Cassini report misses `OpenApiResource`, and nothing says why. Depending on the launch shape, the layer fails instead: `LayerInstantiationException: Package io.vidocq.grimm.cdi in both module grimm.cdi.vauban and module <app>`.
- **Minimal repro**: an application module with Cassini REST + the Grimm extension, and the Cassini plugin execution above. `/openapi` answers 404, and 200 without that execution.
- **Cause**:
  1. The `module-info.java` of grimm-core, grimm-processor and grimm-cdi-vauban, under `src/main/module-info/`, were never compiled into the jars, so Grimm shipped automatic modules (`grimm.cdi.vauban`, ...).
  2. cassini-maven-plugin took the jar without module-info for a class-path jar and wrote `io/vidocq/grimm/cdi/OpenApiResource$$CassiniAdapter.class` into the application module, which split the package.
  3. The application layer's loader then owned `io.vidocq.grimm.cdi`, so `GrimmModelCache` and the other Grimm beans could not be loaded, and Vauban's scan skipped them without a word.
- **Fix**: the three module descriptors are compiled at `prepare-package` and ship in the jars, so Grimm is made of named modules (`io.vidocq.grimm.*`), never automatic ones. grimm-cdi-vauban:
  - `provides` its generated `_VaubanComponents`;
  - `requires transitive` the MicroProfile OpenAPI and JAX-RS APIs its own API exposes, with jakarta.ws.rs-api moved to compile scope to match.

  The Cassini plugin then repackages grimm-cdi-vauban instead of splitting its package. Being a named module also exposed a Vauban defect, fixed in Vauban: with an `@Inject` constructor next to a no-arg one, the generated provider only knew the no-arg one. Verified on the reporter's shape, packaged distribution: `/openapi` 200, and the document lists the application's routes.

### BUG-20261004-01 — `Schema.setAll` keeps standard properties and `getAll` returns only unknown ones

- **id**: BUG-20261004-01
- **date**: 2026-10-04
- **symptom**: the MP OpenAPI 4.2 `Schema` Javadoc says `setAll` is "equivalent to clearing all
  properties, including extensions, and then setting each property with `set`", and `getAll` is
  "equivalent to calling `get` for each property set to a non-null value". In `SchemaImpl`,
  `setAll` clears only the store of unknown properties (the extensions) and leaves every standard
  property in place; `getAll` returns that store only, never the standard properties.
- **minimal repro** (from code review, `SchemaImpl` ~430-445):
  `Schema s = OASFactory.createSchema().title("t"); s.setAll(Map.of("x-a", "v"));` →
  `s.getTitle()` is still `"t"` (expected `null`).
  `OASFactory.createSchema().title("t").getAll()` → `{}` (expected `{title=t}`).
- **hypothesis**: `getAll`/`setAll` predate the 4.2 extension semantics and were written against
  the store of unknown properties only. A fix must clear (and list) the typed fields too, and
  `OpenApiValueMapper` must then stop serializing `getAll()` with `putIfAbsent`, or the standard
  properties would be written under their Java names. Not covered by the 4.2-RC5 TCK (367/367).
- **status**: FIXED — 2026-10-04, commit e137051. `get`/`set` resolve the standard properties by
  JSON name through an explicit table (no reflective accessor lookup, so `extensions`, `all`, `ref`
  or `class` are plain unknown properties); `getAll` lists every non-null property (`$ref` and the
  extensions included) and `setAll` clears all of them first. `OpenApiValueMapper` no longer writes
  `getAll()`, a view of the getters it already writes. Covered by `SchemaImplTest` and
  `OpenApiValueMapperTest`; official TCK still 367/367.

### BUG-20261004-02 — `@Schema` extensions: scalar-only value parsing, and no extensions on `externalDocs`

- **id**: BUG-20261004-02
- **date**: 2026-10-04
- **symptom**: `SchemaGenerator` has its own extension-value parser (`parseExtensionValue` /
  `parseScalar`, ~635-655) that only knows booleans and numbers, while the scanners use
  `AnnotationModelMappings.parseExtensionValue`, which also parses `{…}` objects and `[…]` arrays.
  So `@Schema(extensions = @Extension(name = "x-obj", value = "{\"a\": 1}", parseValue = true))`
  yields the string `{"a": 1}` instead of an object. In the same method (~619-626), `@Schema`
  `externalDocs` is mapped without its `extensions` (`@ExternalDocumentation.extensions()`, since
  MP OpenAPI 3.1).
- **minimal repro** (from code review): annotate a model class with the `@Schema` above and with
  `externalDocs = @ExternalDocumentation(url = "https://example.com",
  extensions = @Extension(name = "x-e", value = "v"))`; the generated schema has
  `x-obj: '{"a": 1}'` (a string) and an `externalDocs` without `x-e`.
- **hypothesis**: duplicated logic — `SchemaGenerator` should reuse
  `AnnotationModelMappings.parseExtensionValue` and the shared external-documentation mapping
  instead of its own copies. Not covered by the 4.2-RC5 TCK (367/367).
- **status**: FIXED — 2026-10-04, commit 8b75298. `SchemaGenerator` uses
  `AnnotationModelMappings.applyExtensions` (`@Schema` and `@SchemaProperty`) and
  `toModelExternalDocs`; its own extension parser is gone. Covered by `SchemaGeneratorTest`;
  official TCK still 367/367.

### BUG-20261004-03 — APT-generated model ignores Bean Validation on scalar parameters

- **id**: BUG-20261004-03
- **date**: 2026-10-04
- **symptom**: `GrimmModelProcessor` (~216) builds a path/query/header parameter schema from the
  type alone, while the runtime scanner applies Bean Validation to it (`JaxRsResourceScanner`
  ~590 and ~594, `BeanValidationMapper.apply`). The processor does not reject a parameter carrying
  `jakarta.validation` annotations (only MicroProfile OpenAPI annotations hand a class to the runtime
  scan), so for the same resource the compile-time `$$GrimmModel` fragment and the scanned model
  differ, and the served document (built from the fragment first) lacks the constraints.
- **minimal repro** (from code review): a resource without MicroProfile OpenAPI annotations,
  `@GET @Path("{id}") @Produces("text/plain") String get(@PathParam("id") @Min(1) long id)`;
  the fragment gives the `id` schema `type: integer, format: int64` with no `minimum`, the runtime
  scan gives `minimum: 1`.
- **hypothesis**: the M1 subset of the processor predates Bean Validation support in the scanner.
  Either apply the same mapping in the processor (from the annotation mirrors) or hand any class
  with Bean Validation on a parameter to the runtime scan (`SkipGeneration`). The processor's
  oracle tests, which compare against the runtime scan, do not cover this case.
- **status**: FIXED — 2026-10-04, commit a6cd5b6. The processor skips a class whose operation
  parameter carries a `jakarta.validation.constraints.*` or `javax.validation.constraints.*`
  annotation (matched by name), so the runtime scan documents it, as for the other constructs
  outside the M1 subset. Covered by `GrimmModelProcessorOracleTest`; official TCK still 367/367.
