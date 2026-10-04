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

### BUG-20261004-04 — Merged schemas lose most properties of the higher-priority source

- **id**: BUG-20261004-04
- **date**: 2026-10-04
- **symptom**: when two sources give a schema at the same position (a media type schema, a
  property of it, its `items`), `ModelMerger.mergeSchema` copies only `format`, `type`, `title`,
  `default`, `enum`, `required`, `items`, `properties`, `description`, `additionalProperties`,
  `$schema`, `$ref` and the extensions of the higher-priority one. Its `minimum`, `maxLength`,
  `pattern`, `readOnly`, `examples`, `not`, `contentMediaType` and every other property are lost.
- **minimal repro**: a static file whose response schema is `{type: string}` and an annotation
  model whose same response schema has `minimum: 1, maxLength: 5`; the merged schema has neither.
- **hypothesis (confirmed)**: the merge was a hand-written list of properties, older than the
  4.0 model. A same-named `components.schemas` entry is not concerned: the higher source replaces
  it whole.
- **status**: FIXED — 2026-10-04, commit 77ee08e. `mergeSchema` walks `Schema#getAll()`, so it
  follows the property table of the model (`SchemaImpl`); `items` and `properties` still merge
  recursively, and an empty list or map does not override the lower source. Covered by
  `ModelMergerTest`; official TCK still 367/367. Follow-up 2e13a1c (review): only an empty
  `type`, `enum`, `required` or `properties` leaves the lower value in place; any other empty
  value (`default: []`, `const: {}`, an extension `x-tags: []`) is copied, as before 77ee08e.

### BUG-20261004-05 — Static file: refs of parameters, request bodies, responses and callbacks lost; parameter fields and the `null` type dropped

- **id**: BUG-20261004-05
- **date**: 2026-10-04
- **symptom**: `OpenApiModelMapper` (static file and `mp.openapi.schema.*` reader) drops the `$ref`
  of a Parameter, a RequestBody and an APIResponse, and reads the `$ref` of a Callback as a callback
  expression. A parameter loses `style`, `explode`, `allowReserved`, `deprecated`,
  `allowEmptyValue`, `example`, `examples` and `content`. A schema `type: "null"` (or the `null` of
  `type: [string, "null"]`) is dropped, and so is a type name that is not a JSON Schema type.
- **minimal repro**: `paths./p.post: {parameters: [{$ref: Param.yaml}], requestBody: {$ref:
  Body.yaml}, responses: {200: {$ref: Response.yaml}}, callbacks: {cb: {$ref: Callback.yaml}}}`:
  the served document has none of the four refs.
- **hypothesis (confirmed)**: the mapper had no case for these keys (only `x-` keys fall through
  to the extensions), and `toSchemaType` knew six of the seven JSON Schema types.
- **status**: FIXED — 2026-10-04, commit 8d4a6fc. The four refs are kept as written, like the
  other static refs; every Parameter field of the 4.2 model is mapped; `null` is
  `SchemaType.NULL` and an unknown type is kept as written. Covered by `OpenApiModelMapperTest`;
  official TCK still 367/367.

### BUG-20261004-06 — `constValue` is not read as JSON; `@SchemaProperty` ignores `constValue` and `externalDocs`

- **id**: BUG-20261004-06
- **date**: 2026-10-04
- **symptom**: the 4.2 Javadoc of `@Schema.constValue` / `@SchemaProperty.constValue` says the
  value "is parsed as JSON if the schema type is anything other than STRING". `SchemaGenerator`
  reads `@Schema(constValue)` with a scalar-only parser: an object or an array stays a string, and
  a STRING schema has `"5"` turned into the number 5. `@SchemaProperty` maps neither `constValue`
  nor `externalDocs`. The parser of extension values (`parseValue = true`) also split an object at
  a comma nested in `[...]`: `{"a": [1, 2]}` gave `{a=[1, ...}` broken in two.
- **minimal repro**: `@Schema(constValue = "{\"a\": 1}") Map<String, Object> m;` gives the string
  `{"a": 1}`; `@Schema(constValue = "5") String s;` gives `5L`.
- **status**: FIXED — 2026-10-04, commit 7f4eacc. `constValue` goes through the parser of
  extension values (`AnnotationModelMappings.parseJsonValue`) unless the schema type is STRING;
  that parser tracks `[...]` nesting; `@SchemaProperty` maps `constValue` and `externalDocs`.
  Covered by `SchemaGeneratorTest` and `AnnotationModelMappingsTest`; official TCK still 367/367.
  The hand-rolled parser itself was then replaced by grimm's JSON reader (BUG-20261004-09).

### BUG-20261004-07 — `@SchemaProperty` maps a few attributes only; `@Schema.examples` is not mapped

- **id**: BUG-20261004-07
- **date**: 2026-10-04
- **symptom**: in `SchemaGenerator.applyAnnotationOverrides`, a `@SchemaProperty` maps only
  `implementation`, `ref`, `type`, `title`, `description`, `format`, `example`, `comment`,
  `extensions`, `hidden` (and, since BUG-20261004-06, `constValue` and `externalDocs`). Its
  `minimum`/`maximum` and their exclusive forms, `multipleOf`, `minLength`/`maxLength`, `pattern`,
  `minItems`/`maxItems`, `uniqueItems`, `minProperties`/`maxProperties`, `requiredProperties`,
  `nullable`, `readOnly`, `writeOnly`, `deprecated`, `enumeration`, `defaultValue`, `examples`,
  `not`/`oneOf`/`anyOf`/`allOf`, `additionalProperties`, the discriminator and the 2020-12
  keywords (`if`/`then`/`else`, `dependentSchemas`, `contains`, `prefixItems`, …) are ignored.
  `@Schema.examples()` (4.0, which deprecates `example`) is not mapped either.
- **minimal repro** (from code review): `@Schema(properties = @SchemaProperty(name = "age",
  minimum = "0"))` on a class with an `int age` field: the `age` schema has no `minimum`.
- **hypothesis**: the `@SchemaProperty` mapping was written as a subset of the `@Schema` one; the
  two annotation types share no interface, so the `@Schema` code cannot be reused as it stands.
- **status**: FIXED — 2026-10-04, commit c75ff4e. `@Schema` and `@SchemaProperty` are read into
  `SchemaAttributes` and applied by one `applyAttributes` method (no second copy); it also maps
  `examples()`, composition, the discriminator and the 2020-12 keywords, for both annotations (the
  `@Schema` branch did not map those either). A property declared by both the field's `@Schema` and
  the class's `@Schema(properties = ...)`: the latter is applied last, attribute by attribute.
  Covered by `SchemaPropertyMappingTest`; official TCK still 367/367.

### BUG-20261004-08 — The `OASModelReader` model overrides the static file (spec order reversed)

- **id**: BUG-20261004-08
- **date**: 2026-10-04
- **symptom**: the "Processing rules" of the MP OpenAPI 4.2 spec order the sources as
  `OASModelReader` (the starting model), then the static file, "where conflicting elements from
  the static file will override the values from the original model", then the annotations,
  "further overriding any conflicting elements". `ModelMerger` merged static file, reader,
  annotations, so on a conflict the reader won over the static file. The docs (concepts,
  getting-started, internals), AGENTS.md, CLAUDE.md and ROADMAP.md stated the same reversed order.
- **minimal repro**: an `OASModelReader` returning `info.title: Reader` and a static file with
  `info.title: Static`; `/openapi` served `Reader` (spec: `Static`).
- **hypothesis (confirmed)**: the order was taken from the reading order of the pipeline (static
  file read first) rather than from the processing rules; the official TCK has no app that gives
  the reader and the static file a conflicting element, so it did not catch it.
- **status**: FIXED — 2026-10-04, commit 3224795. The merger merges reader, static file,
  annotations; `ModelBuilder` invokes the reader before reading the static file; the two unit
  tests that asserted the reverse priority are inverted; docs updated. Covered by
  `ModelMergerTest`; official TCK still 367/367.

### BUG-20261004-09 — Annotation JSON values (`parseValue`, `constValue`) read by a lossy hand-rolled parser

- **id**: BUG-20261004-09
- **date**: 2026-10-04
- **symptom**: extension values with `parseValue = true` (and, since BUG-20261004-06,
  `constValue`) went through a hand-rolled splitter: an escaped quote broke an object
  (`{"a": "x\"y", "b": 1}`), `null` gave the string `"null"`, `1e3` stayed a string, a top-level
  `"abc"` kept its quotes, a leading space made the whole value a raw string, and `1.0` was a
  `Double` at the top level but a `Long` inside an array.
- **minimal repro**: `@Extension(name = "x-e", value = "{\"a\": \"x\\\"y\", \"b\": 1}", parseValue = true)`
  gives `{a="x\"y", "b": 1}` (one broken entry) instead of `{a=x"y, b=1}`.
- **status**: FIXED — 2026-10-04, commit 85bc4f4. `AnnotationModelMappings.parseJsonValue`
  delegates to `JsonDeserializer.parseRaw`, the reader of static files, so grimm has one JSON
  reader; a value that is not JSON stays the string as written. A JSON `null` extension adds
  nothing (the model keeps no null extension). Covered by `AnnotationModelMappingsTest`; official
  TCK still 367/367.
- **behaviour change**: lenient non-JSON values (`{a: 1}`, single quotes, `TRUE`, `1f`, `1d`, `1L`)
  now stay raw strings, and an integral `1.0` inside an array is now a `Double` (it was a `Long`).
  Grimm has no release notes or whats-new page yet, so this entry is the only record.

### BUG-20261004-10 — `summary` next to a `$ref` is lost (MP OpenAPI model limitation)

- **id**: BUG-20261004-10
- **date**: 2026-10-04
- **symptom**: OpenAPI 3.1 (§4.8.23) lets a Reference Object carry `summary` and `description`
  next to `$ref`. A static file with `parameters: [{$ref: '#/components/parameters/P', summary: s}]`
  serves the parameter without `summary`. The same holds for a request body, a response, a header,
  a link and a security scheme. On a callback, `summary` and `description` are both lost: every key
  other than `$ref` and `x-*` is read as a callback expression.
- **minimal repro**: the static file above; or programmatically, there is no `setSummary` on
  `Parameter`, `RequestBody`, `APIResponse`, `Header`, `Link`, `SecurityScheme` or `Callback` in
  the MP OpenAPI 4.2-RC5 API (`javap`), and `Callback` is a map of expressions to path items.
- **hypothesis (confirmed)**: an API limitation, not a grimm defect. `description` survives
  wherever the object has its own `description` field. `Schema` keeps `summary` as an unknown
  keyword. `PathItem` and `Example` have a `summary` field.
- **status**: WON'T FIX in grimm — the model has nowhere to put the value. A side store outside
  the API would be invisible to `OASFilter` and `OASModelReader` code, and would make Grimm's
  model differ from the spec model. The fix belongs upstream: a `summary` (and, for `Callback`,
  `description`) on the Reference-capable model interfaces. Documented in `concepts.adoc`.

### BUG-20261004-11 — Every `@Schema` / `@SchemaProperty` writes `minItems`, `maxItems` and `maxProperties`

- **id**: BUG-20261004-11
- **date**: 2026-10-04
- **symptom**: every annotated schema carried `minItems: 2147483647`, `maxItems: -2147483648` and
  `maxProperties: 0`, and so did the document served at `/openapi`. A validator or client generator
  read "at most zero properties" for every annotated object. An empty `@Schema()` also counted as
  content in `JaxRsResourceScanner.hasAnyContent`.
- **minimal repro**: `@Schema(description = "d") class C {}`, then `getMinItems()` on the generated
  schema returns `2147483647`.
- **hypothesis (confirmed)**: the guards in `SchemaGenerator.applyAttributes` and `hasAnyContent`
  assumed the wrong annotation defaults. The real ones (MP OpenAPI 4.2-RC5 sources): `maxProperties`
  0, `minItems` `Integer.MAX_VALUE`, `maxItems` `Integer.MIN_VALUE`. The shared `applyAttributes`
  (BUG-20261004-07) spread the defect to every `@SchemaProperty`. Not visible to the TCK, which only
  asserts values that are set.
- **status**: FIXED — 2026-10-04, commit 91159d3. The guards compare with the real defaults; an
  explicit 0 still maps. Covered by `SchemaPropertyMappingTest` (class, field and `@SchemaProperty`
  without those attributes; explicit values); official TCK still 367/367.

### BUG-20261004-12 — `examples()` of `@Schema` / `@SchemaProperty` is not parsed as JSON

- **id**: BUG-20261004-12
- **date**: 2026-10-04
- **symptom**: `@Schema(type = INTEGER, examples = "1")` gave `examples: ["1"]` (a string on an integer
  schema); an object example was an escaped JSON string.
- **minimal repro**: `@SchemaProperty(name = "n", type = INTEGER, examples = {"1", "2"})`.
- **hypothesis (confirmed)**: the 4.2 Javadoc of `examples()` says the value is a literal string when
  the schema type is STRING and parsed as JSON otherwise, the same clause as `constValue`. The
  mapping copied the raw strings. The deprecated `example()` has no such clause and stays raw.
- **status**: FIXED — 2026-10-04, commit 91159d3. Each entry goes through `constValueOf`. Covered by
  `SchemaPropertyMappingTest` (STRING literal, INTEGER, OBJECT); official TCK still 367/367.

### BUG-20261004-13 — A `@Schema` that sets only some attributes is ignored on parameters, bodies, headers and content

- **id**: BUG-20261004-13
- **date**: 2026-10-04
- **symptom**: `@QueryParam("n") @Schema(minimum = "0") int n`, or a request body `@Schema(examples = "x")`,
  gave the inferred schema without the attribute. Same for `oneOf`/`anyOf`/`allOf`/`not`, `readOnly`,
  `writeOnly`, `nullable`, `deprecated`, `pattern`, `maximum`, `multipleOf`, `uniqueItems`, `defaultValue`,
  `constValue`, and others.
- **minimal repro**: `void get(@QueryParam("n") @Schema(minimum = "0") int n)`; the parameter schema has no `minimum`.
- **hypothesis (confirmed)**: `JaxRsResourceScanner.hasAnyContent` (five call sites: parameter, request body,
  response header, content, header) tested a hand-picked subset of attributes and chose `generate(type)`
  over `generate(type, annotation)` when none was set. Pre-existing.
- **status**: FIXED — 2026-10-04, commit 5e1795c. `SchemaGenerator.hasContent` uses
  `SchemaAttributes.hasContent()`, which compares every shared attribute with its real default (one list,
  the one the mapping uses), plus implementation, ref, required and properties. An empty `@Schema()` is
  unchanged. Covered by `JaxRsResourceScannerTest` (parameter and request body; minimum, examples, oneOf,
  readOnly; empty schema) and `SchemaPropertyMappingTest`.
