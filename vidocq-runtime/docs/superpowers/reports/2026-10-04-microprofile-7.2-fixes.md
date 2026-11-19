# MicroProfile 7.2 upgrade — fixes record

## Purpose and status

This is the traceability record the maintainer asked for ("keep a trace of all the fixes") during
the Vidocq MicroProfile 7.2 upgrade of 2026-10-04. It lists every fix made in the upgrade
branches: fix rounds of task reviews, final-review fix waves, Phase F follow-ups and pre-existing
defects. Plain feature commits are not listed as fixes. It is built from the controller ledger
(`.superpowers/sdd/2026-08-27-microprofile-7.2-upgrade/progress.md`), the plan
(`docs/superpowers/plans/2026-08-27-microprofile-7.2-upgrade.md`), the git history and each
repository's `BUG.md`.

- Date: 2026-10-04.
- Branches, all `pr/ybl/mp-7.2` cut from `origin/main` on 2026-10-04 (ranges are base..head):

| Repository | Range | Notes |
|---|---|---|
| cervantes | `68e4a10..e4a5ca8` | phases A and F (FA1-FA3), second final review |
| grimm | `f6f37db..548f83f` | phases B and F (FB1-FB6), second final review |
| vidocq | `43db665c..242a09ea` | phases D and F (FD1); also holds the controller's `docs(plan)` commits |
| humboldt | `db2cab7..5028008` | phases C and F (FC1-FC4, FC2), second final review |
| vauban | `7384ac1..0fd814c` (branch `pr/ybl/inherited-interceptor-method`) | Phase F only (FV1, FV2 rounds 1-5, second final review) |

- Status: nothing is pushed yet. Pushing and opening PRs waits for the maintainer's go.
  Phase E (switching to the final artifacts once they are on Maven Central) is still open.
  Phase F and its second final review are finished and closed for all five repositories, and the last
  verification chain is green (see "Final verification").
- Honesty note: the TCKs were run on release-candidate artifacts (JWT 2.2, OpenAPI 4.2-RC5,
  Telemetry TCK 2.2-RC3) that the maintainer decided are byte-identical to the finals still under
  ballot. This record makes no "certified" or "MicroProfile 7.2 compatible" claim. Task E1
  re-runs the TCKs on the finals.

Columns of the fixes tables: `Why / how it was found` is one of task review, final review,
controller TCK check, pre-existing defect, maintainer request, or a plan correction.

---

## cervantes (MicroProfile JWT Authentication 2.2)

### What phases A to D delivered

Phase A moved cervantes to MicroProfile JWT 2.2: PEM public keys are parsed with automatic
RSA/EC family detection (A1, `83ad7b8`), RS256 and ES256 are both accepted when
`mp.jwt.verify.publickey.algorithm` is unset (A2, `1d2a021`), and the official JWT 2.2 TCK runs
in the cervantes TCK module (A3, `4104200`). Phase F then made invalid configuration fail at
container start, completed the English translation and the Javadoc repair, and added the same
check for the decryption algorithm.

### Fixes

| # | Fix | Why / how it was found | Commits | Tests | BUG.md id |
|---|---|---|---|---|---|
| C1 | Docs and logo said the algorithm "defaults to RS256", tied 208/208 to "MicroProfile 7.2", still said "JWT 2.1" in production Javadoc/pom descriptions, and the PNG logo still showed 2.1 | Task review A3 (Important plus 3 minors); controller ruling widened the round to the wording and logo items | `1280f28` | docs only; re-review 4/4 addressed | — |
| C2 | An unrecognised `mp.jwt.verify.publickey.algorithm` was silently ignored (every family accepted); now rejected at startup | Final review (Important) | `b00156c` | `JwtAuthConfigProducerTest` | — |
| C3 | 0.3 `JwtConfig` constructor removed by the 7th record component; the 6-argument constructor is kept | Final review (minor, API compatibility) | `8fce055` | `JwtConfigCompatibilityTest` | — |
| C4 | Same-family algorithm test missing and `KeyResolver` imported by FQN | Final review (minor) | `67bd520` | `DefaultJwtValidatorTest` | — |
| C5 | `concepts.adoc` said the exact algorithm is checked (code checks the family); `internals.adoc` pipeline lacked the family check | Final review (minor) | `ad6b28b` | docs only | — |
| C6 | Unused `tck-suite.xml` with a stale comment and stale "non-public TCK artifact" lines | Final review (minor) | `637888e` | docs and build metadata; TCK 208/0/0 from clean | — |
| C7 | Only the EC failure was kept as the cause of a PEM key-load error (the RSA one was lost); armour stripping was duplicated | Deferred minor from task review A1, taken up in FA1 | `14df45b` | `PemKeysTest` | — |
| C8 | Invalid MicroProfile JWT configuration now fails container start (Vauban swallows `Startup` observer exceptions, so the check is a BCE `@Validation`) | Final review parked item ("fails at first injection"), turned into Task FA1 by the maintainer's request to fix review follow-ups | `826e927` | `FailFastStartupTest`, `JwtAuthConfigProducerTest`, `CdiTestSupport`; TCK 208/0/0 | — |
| C9 | The new check ignored Vauban's build-time contract (BCE phases run at build time), swallowed real `Config` failures behind a broad catch, and dropped the cause | Task review FA1 (Important plus minors) | `0e479dd`, `4f4c5ab` | `FailFastStartupTest` (asserts `DeploymentException`); TCK 208/0/0 | — |
| C10 | 47 files with French text, 17 Javadoc blocks damaged by `ZZPH0ZZ` placeholders, stale CLAUDE/README/ROADMAP/Antora facts, garbled Javadoc in `JwtAuthConfigProducer` | Pre-existing defects found by reviews A2 and A3; Task FA2 | `aa98e3a` | docs only | — |
| C11 | Leftover French comment, fail-fast NOTE incomplete (decrypt key, container start only), clumsy machine-translated Javadoc | Task review FA2 (minors, maintainer wants all points fixed) | `43ad332` | docs only, accepted without re-review | — |
| C12 | An unrecognised `mp.jwt.decrypt.key.algorithm` made every JWE fail per token; now fails at container start (RSA-OAEP and RSA-OAEP-256 exact) | New finding reported by the FA2 implementer, ruling on Task FA3 | `c8b4b6c` | `FailFastStartupTest`, `JwtAuthConfigProducerTest`; TCK 208/0/0 | — |
| C13 | FQN `List` import, missing sync comment on the algorithm switch, NOTE wording; supported JWE algorithms kept in one place | Task review FA3 (minors) | `7a21f0b` | build green, accepted without re-review | — |
| C14 | Second final review: `usage.adoc` JWE comment contradicted FA3 (unset = both accepted, set = exact); `reference.adoc` over-claimed (an http(s) verify key location stays lazy through `LazyHttpKeyResolver`); migration note for the 7th `JwtConfig` component (record deconstruction patterns); the FA3 defect had no BUG.md entry | Second final review (Important 1, minors 3 and 4) | `b193485` | docs only | CERV-004 (logged; FIXED by `c8b4b6c`) |
| C15 | Machine-translation leftovers in `CervantesClaimExtension`, `JweDecryptor`, `Jwe`, `KeyResolvers`, `ClaimValueImpl`, `CervantesClaimInjectionTest`; `@Deprecated` missing on two `Jwe` factories; producer Javadoc claimed `@ApplicationScoped` (it is `@Dependent`) | Second final review (minor 5 and declined items taken up by the fix wave ruling) | `dc8d050` | build; TCK 208/0/0 | — |
| C16 | TCK script now uses `./mvnw` and cleans the reactor and the TCK module; comment on where it cleans reworded by the controller | Second final review (declined item taken up, same as grimm/humboldt) | `3ebaf5d`, `e4a5ca8` | TCK 208/0/0 (controller read `final2-cervantes-tck.log`, 19:13) | — |

### TCK result (from the ledger)

JWT 2.2 TCK: 208 run, 0 failures, 0 errors, 0 skipped. First run in A3 (`4104200`); controller
verification from a clean TCK module on 2026-10-04 (14:29-14:34) gave 208/0/0/0; the same figure
held after the final fix wave, FA1 and FA3 (`208/0/0`). The assembled Vidocq runtime runner
(D1, final verification) also gives JWT 208. The `RsaAndEcSignatureAlgorithmTest` is deployed.
JWT 2.1 baseline on main was 206.

After the second final wave: TCK 208/0/0 (19:13, controller). The Phase F verification chain
(19:07) re-ran cervantes TCK 208 and checked the fail-fast check end to end in the example
(`vidocq:run` with `MP_JWT_VERIFY_PUBLICKEY_ALGORITHM=HS999` gives a `DeploymentException` at start listing the
supported values; a good configuration starts in 135 ms).

### Second final review (Phase F)

Verdict: With fixes. One Important (JWE comment in `usage.adoc`), minors on translation leftovers,
`reference.adoc`, and a missing BUG.md entry; the runtime fail-fast check was already verified by the
controller. Fix wave `b193485`, `dc8d050`, `3ebaf5d` (rows C14-C16); re-review APPROVED, all in-scope items addressed, one
comment moved by the controller (`e4a5ca8`). CERVANTES FINAL REVIEW CLOSED, head `e4a5ca8`.

### BUG.md

The only entry added for this work is CERV-004 (2026-10-04, FIXED `c8b4b6c`), logged in `b193485`. `cervantes/BUG.md` has no `BUG-20261004-*` ids.

---

## grimm (MicroProfile OpenAPI 4.2-RC5)

### What phases A to D delivered

Phase B moved grimm to the MicroProfile OpenAPI 4.2-RC5 API (B1, `ae241b8`), treated unknown
Schema properties as extensions (B2, `92ff7b0`), mapped `@Header` example/examples (B3,
`da9718c`), mapped method-level `@ExternalDocumentation` (B4, `3ef1509`), derived
multipleOf/pattern from `@Digits` (B5, `a2588ea`) and ran the official 4.2-RC5 TCK (B6,
`0c06d07`). Phase F then fixed fidelity gaps of the model, mapper, merger and annotation
scanners, and logged and fixed the defects found by the reviews.

### Fixes

| # | Fix | Why / how it was found | Commits | Tests | BUG.md id |
|---|---|---|---|---|---|
| G1 | `HeaderImpl` accessors lacked `@SuppressWarnings("removal")` (two build warnings, report claimed otherwise) | Task review B3 | `b8418f6` | build log; re-review addressed | — |
| G2 | Annotation-to-model mapping duplicated statement for statement in both scanners (also moved the duplicated `toModelExample`/`applyHeaderExamples` and the ~150-line extension-value parser) | Task review B4 (Important) | `583f22d` | existing tests plus helper tests; diff -w check of both old parsers | — |
| G3 | `@Digits(integer = 0)` produced `\d{1,0}`, an invalid regex in user documents | Deferred minor of the B5 review, carried into B6 by ruling | `926258d` | `BeanValidationMapperTest` | — |
| G4 | Static-file schema keywords (numeric, schema-valued, discriminator, xml, externalDocs) landed in the extension store (B2 regression); also fixes allOf/anyOf/oneOf/prefixItems/dependentSchemas/patternProperties raw maps and boolean subschemas | Final review (Important) | `1a22c91` | `OpenApiModelMapperTest`; probe base vs head: 11 keywords no longer in the extension store; controller TCK 367/0/0 | — |
| G5 | Unused imports and a stray blank line left in the model | Final review (minor) | `55e907a` | build | — |
| G6 | `index.adoc` still said 349/349; "+18" wording, ROADMAP M10 349, "non-public TCK" claims, API 4.2 vs 4.2-RC5 in tables | Final review (minor) | `ad0870b` | docs only | — |
| G7 | TCK script did not clean the harness (stale classes risk) | Deferred minor of task review B6 and final review | `4c63d52` | script; later TCK runs compile from scratch | — |
| G8 | Three pre-existing defects found in review logged in `BUG.md` (not fixed at that point) | Final review | `76fad07` | docs only | BUG-20261004-01, -02, -03 (opened) |
| G9 | Needless `@SuppressWarnings` on the static header test | Final fix wave polish | `cfd0a0e` | build | — |
| G10 | Static-file discriminator `x-` keys, verbatim `$ref`, YAML 1.2 numbers, typed config schemas, header style/explode/content were lost or altered | Phase F follow-up of the final review, Task FB1 | `41eb767` | `ConfigApplierTest`, `OpenApiModelMapperTest`; TCK 367/0/0 | — |
| G11 | `ModelMerger` re-expanded verbatim static refs on a name collision (`target.setRef(source.getRef())`); `.inf`/`.nan` documented as strings | Task review FB1 (Important) | `71713e4` | `ModelMergerTest`, `ConfigApplierTest`, `OpenApiModelMapperTest` | — |
| G12 | Config `ref` alias not accepted at every level of a schema override | Task review FB1 (round 1b) | `7e2f8ed` | `ConfigApplierTest`; TCK 367/0/0 | — |
| G13 | `Schema.getAll`/`setAll` did not cover every property as the 4.2 Javadoc says | Pre-existing defect, final review; fixed in Task FB2 | `e137051` | `SchemaImplTest`, `OpenApiValueMapperTest`; TCK 367/0/0 | BUG-20261004-01 (FIXED, `e137051`) |
| G14 | `@Schema` extension values parsed scalar-only; `externalDocs` extensions not mapped | Pre-existing defect, final review; Task FB2 | `8b75298` | `SchemaGeneratorTest` | BUG-20261004-02 (FIXED, `8b75298`) |
| G15 | APT-generated model ignored Bean Validation on scalar parameters | Pre-existing defect, final review; Task FB2 | `a6cd5b6` | `GrimmModelProcessorOracleTest` | BUG-20261004-03 (FIXED, `a6cd5b6`) |
| G16 | Schema property table edge cases (explicit table replaced reflective get/set) | Carried from the FB2 review into Task FB4 | `bf39b78` | `SchemaImplTest` | — |
| G17 | `ModelMerger.mergeSchema` copied only some properties (minimum, pattern, maxLength lost) | Pre-existing defect carried from FB2 review | `77ee08e` (tests `16b866f`) | `ModelMergerTest` | BUG-20261004-04 (FIXED, `77ee08e`) |
| G18 | Static file: refs of parameters, request bodies, responses and callbacks lost; parameter fields and the `null` type dropped; no callback mapping | Pre-existing defect, carried from FB1 into FB4 | `8d4a6fc` | `OpenApiModelMapperTest` | BUG-20261004-05 (FIXED, `8d4a6fc`) |
| G19 | Config ref alias not expanded inside `$defs` and `definitions` | Carried from the FB1 re-review into FB4 | `82bc4d6` | `ConfigApplierTest` | — |
| G20 | `constValue` not read as JSON; `@SchemaProperty` ignored `constValue` and `externalDocs` | Pre-existing defect, Task FB4 | `7f4eacc` | `AnnotationModelMappingsTest`, `SchemaGeneratorTest` | BUG-20261004-06 (FIXED, `7f4eacc`) |
| G21 | `ModelMerger` guard `isEmptyCollection` dropped explicit empty default/const/extensions of the higher-priority source | Task review FB4 (Important) | `2e13a1c` | `ModelMergerTest` | — |
| G22 | `OASModelReader` model overrode the static file (spec order is reader, static file, annotations, filter) | Task review FB4, controller checked the 4.2-RC5 "Processing rules" | `3224795` | `ModelMergerTest` (2 old tests inverted) | BUG-20261004-08 (FIXED, `3224795`) |
| G23 | Annotation JSON values read by a lossy hand-rolled parser (escaped quotes, null as "null", 1e3 as string, top-level quotes kept) | Task review FB4 (minor), replaced by grimm's JSON reader | `85bc4f4` | `AnnotationModelMappingsTest` | BUG-20261004-09 (FIXED, `85bc4f4`) |
| G24 | A `summary` next to a `$ref` is lost; documented as a model limitation | Task review FB4 (minor) | `8860793` | docs only | BUG-20261004-10 (WON'T FIX) |
| G25 | FB4 polish: one annotation-name check for both safety valves, round-trip test of every property-table entry, long lines and `BigDecimal` import | Task review FB4 (polish) | `2485559`, `6bbde05`, `d24b912` | `ModelMergerTest`, schema round-trip test | — |
| G26 | `@SchemaProperty` mapped a few attributes only; `@Schema.examples` unmapped (one `applyAttributes` now serves both) | Pre-existing defect logged in FB4; Task FB5 by maintainer request | `c75ff4e` | `SchemaPropertyMappingTest`; TCK 367/0/0 | BUG-20261004-07 (FIXED, `c75ff4e`) |
| G27 | Discriminator mappings and pattern properties without a schema produced bogus entries; no recursion test | Task review FB5 (minors) | `36b5901` | `SchemaPropertyMappingTest`; core 259 tests | — |
| G28 | Dead imports and pass-throughs, untidy test setup | Task FB3 cleanup | `a95e687` | build; core 260 tests | — |
| G29 | grimm-tck runner now launched through `./mvnw` in the TCK script | Deferred minor (stale tooling), Task FB3 | `618aaa1` | TCK 367/0/0 from clean (controller read log) | — |
| G30 | How model sources combine was undocumented (and the BUG-09 behaviour change) | Ruling of FB4 and carried item of its re-review | `e5c9efc`, `baf5fe3` | docs only | BUG-20261004-09 (note) |
| G31 | Stale ShrinkWrap, Model 4.1.0 and `tck-suite` statements in grimm docs | Deferred minor of task review B6 | `f68d4e5` | docs only | — |
| G32 | `mvn -f` wording, TCK command and source-combination rules incomplete | Task review FB3 (minors) | `5858b40` | docs only, accepted without re-review | — |
| G33 | `BUG.md` bookkeeping: log and close entries | Maintainer request | `b2a1222`, `25bca7e`, `587ed61`, `8d9bb72` | docs only | BUG-20261004-01 to -09 |
| G34 | Every `@Schema`/`@SchemaProperty` wrote `minItems` 2147483647, `maxItems` -2147483648 and `maxProperties` 0 (wrong annotation-default sentinels, invisible to the TCK); `examples()` mapped as raw strings instead of JSON unless type STRING; plus code minors (pass-throughs, dead `lower()`, stale Javadoc and comments) | Second final review (Important 1 and 2, probe-confirmed; pre-existing at base, spread by FB5) | `91159d3` | `SchemaPropertyMappingTest` (RED 4/14); core 264, processor 10, cdi 14; TCK 367/0/0 | BUG-20261004-11, -12 (FIXED, `91159d3`) |
| G35 | BUG.md entries for the sentinel and examples fixes; migration note for `summary` beside a `$ref` | Second final review (Important and optional minor) | `18cfb88` | docs only | BUG-20261004-11, -12 |
| G36 | A `@Schema` that sets only some attributes (for example `minimum` alone) was silently ignored on parameters, bodies, headers and content; `SchemaAttributes.hasContent()` now compares all 51 attributes with their real 4.2 defaults and the scanner hand list is deleted | Pre-existing defect found by the second final re-review, micro-task FB6 | `5e1795c` | RED 6 failures (parameter minimum/examples/oneOf/readOnly, body minimum/examples); empty `@Schema` unchanged; core 273; TCK 367/0/0 | BUG-20261004-13 (FIXED, `5e1795c`) |
| G37 | BUG-20261004-13 logged and closed | Maintainer request | `bd4a148` | docs only | BUG-20261004-13 |
| G38 | No test for the header, content and encoding-header call sites of `hasContent` | FB6 re-review (minor) | `548f83f` | 9 tests (response header, content, encoding header x minimum-only, examples-only, empty `@Schema`); core 282 | — |

### TCK result (from the ledger)

OpenAPI 4.2-RC5 TCK in `grimm-tck`: 367 run, 0 failures, 0 errors, 0 skipped. Composition recorded
in the ledger (B6): 349 + 2 ExternalDocumentation + 6 SchemaExtension + 8 BeanValidation
`@Digits` + 2 AirlinesApp header examples (JSON and YAML per method). Re-verified by the controller
from clean builds on 2026-10-04 and after the final fix wave, FB1, FB2, FB4, FB5 and FB3 (all
367/0/0). The assembled Vidocq runtime runner gives 364 (D1 and the final verification); the
ledger does not break 364 into "official" and "local" tests.

After the second final review: TCK 367/0/0 (controller, 19:15 after `91159d3`, 19:19 after FB6), and
367/0/0 in the Phase F verification chain; the assembled-runtime OpenAPI runner stays at 364.

### Second final review (Phase F)

Verdict: With fixes (2 Important, 7 minors). Fix wave `91159d3`, `18cfb88` (rows G34-G35): re-review addressed
all 9, every numeric default of `@Schema`/`@SchemaProperty` re-read from the RC5 sources. The re-review found one
pre-existing minor (`JaxRsResourceScanner.hasAnyContent`), fixed as micro-task FB6 (`5e1795c`, `bd4a148`, rows
G36-G37), then three call-site tests (`548f83f`, G38). GRIMM FINAL REVIEW CLOSED, head `548f83f`.

### BUG.md

All 13 entries are dated 2026-10-04.

| Id | Status | Fix commit |
|---|---|---|
| BUG-20261004-01 | FIXED | `e137051` |
| BUG-20261004-02 | FIXED | `8b75298` |
| BUG-20261004-03 | FIXED | `a6cd5b6` |
| BUG-20261004-04 | FIXED | `77ee08e` |
| BUG-20261004-05 | FIXED | `8d4a6fc` |
| BUG-20261004-06 | FIXED | `7f4eacc` |
| BUG-20261004-07 | FIXED | `c75ff4e` |
| BUG-20261004-08 | FIXED | `3224795` |
| BUG-20261004-09 | FIXED | `85bc4f4` |
| BUG-20261004-10 | WON'T FIX | — (API limitation) |
| BUG-20261004-11 | FIXED | `91159d3` |
| BUG-20261004-12 | FIXED | `91159d3` |
| BUG-20261004-13 | FIXED | `5e1795c` |

---

## humboldt (MicroProfile Telemetry 2.2)

Branch `pr/ybl/mp-7.2`, range `db2cab7..f4aae54`.

### What phases A to D delivered

Phase C adopted OpenTelemetry 1.66.0 and the Telemetry 2.2-RC3 TCK (C1, `c53ac9c`; the new
`opentelemetry-common` artifact is shaded into `humboldt-otel-context`), emitted
`code.function.name` on `@WithSpan` spans (C2, `c60f837`), supported `@WithSpan(inheritContext =
false)` (C3, `7191140`) and ran the official 2.2-RC3 TCK (C4, `8fd128b`). Phase F then fixed
the data the OTel 1.66 defaults silently lost (exceptions, event names, structured values, double
metric points), made the OTLP exporters work on the module path, and corrected stale docs.

### Fixes

| # | Fix | Why / how it was found | Commits | Tests | BUG.md id |
|---|---|---|---|---|---|
| H1 | `<scope>test</scope>` on the SDK artifacts broke the `humboldt-tck` compile (the 85/85 run had reused stale classes); logo PNG/SVG and CLAUDE.md still said 2.1 | Task review C4 (Critical plus Important); the scope item was the controller's own carry-over | `a970db5` | clean `humboldt-tck` build, TCK 85/0/0/0 compiled from scratch | — |
| H2 | `SdkLogRecordBuilder` lacked `setException`/`setEventName` (OTel 1.66 defaults dropped them); spans and logs now carry `exception.*` and event names | Final review (Important) | `6890c01` | `SdkLoggerProviderTest`, `OtlpJsonLogEncoderTest`, `LoggingLogRecordExporterTest` | — |
| H3 | `OtlpJsonCommon.writeAnyValue` had no `AttributeType.VALUE` case (complex values exported as `{}`) | Final review (Important) | `25b5f97` | `OtlpJsonEncoderTest`, `OtlpJsonLogEncoderTest` | — |
| H4 | `@WithSpan(inheritContext = false)` kept the caller's baggage; the detached span now runs in `Context.root()` | Final review (minor, behaviour of C3) | `54d75ff` | `WithSpanInterceptorTest` | — |
| H5 | ComponentLoader module-path limit logged (not yet fixed at this point) | Final review (minor) | `7b96858` | docs only | BUG-20261004-01 (opened) |
| H6 | Stale docs and comments (usage.adoc, Javadoc, README_EN, humboldt-tck README, module-info comment reason, TCK.md heading, French on touched lines) | Final review (minors) | `4c4938e` | docs only | — |
| H7 | TCK script did not clean the TCK module (stale classes risk) | Final review (minor), consequence of the C4 finding | `50a4e37` | script; later runs compile from scratch | — |
| H8 | Span `exception.type` used `getName()`; now the canonical class name, with a `getName()` fallback on spans and logs | Residual parked after the final wave, Task FC1 | `693bc57` | `SdkTracerProviderTest`, `SdkLoggerProviderTest` | — |
| H9 | Non-finite doubles written as bare `NaN`/`Infinity` (invalid JSON); now strings | Residual parked after the final wave, FC1 | `08dce17` | `OtlpJsonEncoderTest`, `OtlpJsonMetricEncoderTest` | — |
| H10 | `setBody(Value)` flattened a structured body; `LogRecordData` keeps `bodyValue` (old constructors and `String body()` kept) | Residual parked after the final wave, FC1 | `e42d0c0` | `OtlpJsonLogEncoderTest`, `LoggingLogRecordExporterTest`, `SdkLoggerProviderTest` | — |
| H11 | JUL bridge ignored `getThrown()`; now passed to `setException` | Residual parked after the final wave, FC1 | `e9118b7` | `HumboldtJulHandlerTest` | — |
| H12 | OTel `ComponentLoader` service lookups failed on the module path through the interop `MapConfigProperties` | Pre-existing defect logged in the final wave (H5), fixed in FC1 | `19479e2` | `MapConfigPropertiesModuleLayerTest` (real named-module layer), `OtelSpiAutoConfigurationTest` | BUG-20261004-01 (FIXED, `19479e2`) |
| H13 | Five copies of the OTel bridges in `humboldt-tck` replaced by the interop ones (SpanDataMapper duplicated, deferred in task review C3) | Deferred minor C3, FC1 | `f5e8bd7` | TCK 85/0/0 | — |
| H14 | OTLP/JSON metric encoder dropped double data points and had no synchronous gauge case | New defect found by the FC1 review (code reading) | `5b03d5b` | `OtlpJsonMetricEncoderTest`, `OtlpHttpMetricExporterE2ETest` | BUG-20261004-02 (FIXED, `5b03d5b`) |
| H15 | Structured body rendered once and an empty string treated as no body (later reversed by H19); TCK discovers SPI providers through interop | Minors of the FC1 review, FC3 | `5bbeff3`, `b964de6` | `OtlpJsonLogEncoderTest`, `SdkLoggerProviderTest`; TCK 85/0/0 | — |
| H16 | `humboldt-otel-api` did not export `io.opentelemetry.api.internal` (`IllegalAccessError` in OTLP exporters on the module path); first fix a qualified export | FC1 exporter check, Task FC4 | `cd3b6ed` | `OtlpExporterModuleLayerTest` | BUG-20261004-03 (closed too early, see H20) |
| H17 | Exporter `CompressorUtil` loaded compressors through the default `ServiceLoaderComponentLoader`; humboldt now owns a `ServiceLoaderComponentLoader` that adds `uses` before loading | FC1 exporter check, FC4 | `5380bc3` | `ServiceLoaderComponentLoaderTest`, `OtlpExporterModuleLayerTest` | BUG-20261004-04 (FIXED, `5380bc3`) |
| H18 | A data point that does not match its metric kind is rejected instead of encoded wrongly | FC3 review minor, FC4 | `915db9c` | `OtlpJsonMetricEncoderTest` | — |
| H19 | An explicitly set empty string log body was dropped; now kept and exported as in OTel 1.66 | Ruling on FC4 (reference behaviour is OTel 1.66), reverses H15 | `a418be6` | `SdkLoggerProviderTest`, `OtlpJsonLogEncoderTest` | — |
| H20 | BUG-03 was closed early: exporters also reach `io.opentelemetry.context.internal.shaded`, `api.trace.propagation.internal` and `api.impl`; the test never called `export()`. Now every internal package is exported (qualified) to the OTel modules that use it, with a runtime `Module.addExports` fallback for child layers | Task review FC4 (Important, reviewer probe) | `12ed18f`, `87808e5` | `OtlpExporterModuleLayerTest` (end-to-end span, metric, log export to a closed port in the same and a child layer; 20 stable 1.66.0 jars scanned) | BUG-20261004-03 (FIXED, `12ed18f`, `87808e5`) |
| H21 | `OtlpHttpMetricExporter.export` threw on an unencodable batch; now a failed `CompletableResultCode`, logged once | Task review FC4 (minor) | `3098dc9` | `OtlpHttpMetricExporterE2ETest` | — |
| H22 | Long test lines; README wording "opened with --add-exports" and layer limits undocumented | Task review FC4 (minors) | `a53743e`, `a8f9732` | docs only | BUG-20261004-03 (text corrected) |
| H23 | A provider was dropped when its layer exports could not be extended (one `IllegalAccessError` lost every provider); the interop keeps it | FC4 re-review minor (a), Task FC2 | `e7518db` | `OtlpExporterModuleLayerTest` (child-layer repro) | — |
| H24 | Docs gave only `io.opentelemetry.api/<pkg>` `--add-exports` for incubating artifacts; context module also named; 2.2 spec citations by section title; French text translated; TCK script step 1 cleans | FC4 re-review minor (b) plus leftover French from the final wave, FC2 | `0abcc33`, `4abe25a` | `HumboldtAutoConfigureTest` and TCK bridge tests; TCK 85/0/0 | — |
| H25 | Child-layer limitation undocumented (interop in a child layer of the API module keeps exports unextended; the exporter may later fail with `IllegalAccessError`); `extendExports` Javadoc blamed the class path; test now asserts the FINE message | Task review FC2 (Important plus minors) | `f4aae54` | `OtlpExporterModuleLayerTest` 17/17; docs: README, `reference.adoc`, BUG-03 | BUG-20261004-03 (limitation documented) |
| H26 | BUG.md bookkeeping: log, close and correct entries | Maintainer request | `a540b22`, `e74bcfa`, `19873d8` | docs only | BUG-20261004-01 to -04 |
| H27 | Three stale "Telemetry 2.1" citations (`humboldt-cdi` module-info, `CollectingAutoConfigurationCustomizer` "§3.2", `SpanDataMapper`) | Second final review (minor) | `189fa7c` | docs and comments only | — |
| H28 | Plain `clean install` built the `humboldt-otel-api` and `-context` sources jars without humboldt's own sources; `maven-source-plugin` is now bound unconditionally in the repackaging poms | Second final review (minor); the FC4 parked item | `7e50f10` | controller checked the plain-install sources jars in `~/.m2` (they hold `ContextLayerExports`/`ApiLayerExports`) | — |
| H29 | `InteropComponentLoader` was redundant after the humboldt-owned loader; removed, the default `forClassLoader` is returned | Second final review (minor) | `4a17051` | module-layer test 17/17 | — |
| H30 | `OtlpJsonMetricEncoder` switch over `InstrumentType` had no throwing default; unknown type now fails the batch (`ofFailure`, logged once) | Second final review (minor, unreachable with the closed enum; same failure path as mismatched points) | `5028008` | exporter tests; TCK 85/0/0 (controller, 19:20) | — |

### TCK result (from the ledger)

MicroProfile Telemetry 2.2-RC3 TCK in `humboldt-tck`: 85 run, 0 failures, 0 errors, 0 skipped.
The first run (C1) had exactly one expected failure (`RestClientSpanTest.spanChild`), fixed by C2
(85/85). After the C4 review the figure was re-taken from a clean build (`a970db5`). Controller
verification from scratch on 2026-10-04 gave 85/0/0/0, again after the final fix wave (14:29-14:34
and 15:09-15:20), and after FC1, FC3, FC4 and FC2 (85/0/0 each; FC2 `f4aae54` is docs, Javadoc
and test only, so the TCK was not re-run for it). The assembled Vidocq runtime runner gives
Telemetry 85. Unit tests: the final wave reported 121 (sum of module totals); FC1 150, FC3 162,
FC4 175 then 185, FC2 186.

After the second final wave: TCK 85/0/0 (controller read `final2-humboldt-tck.log`, 19:20); the Phase F
chain (19:07) also gave 85/0/0.

### Second final review (Phase F)

Verdict: Ready to merge YES, with minors only (stale citations, sources jars, redundant loader,
switch default); the C1 and C2 deferred items were verified fixed. Fix wave `189fa7c`, `7e50f10`, `4a17051`, `5028008`
(rows H27-H30); re-review: 1-4 addressed, no new findings. HUMBOLDT FINAL REVIEW CLOSED, head `5028008`.
Trailer order (global ruling) skipped.

### BUG.md

| Id | Status | Fix commit |
|---|---|---|
| BUG-20261004-01 | FIXED | `19479e2` |
| BUG-20261004-02 | FIXED | `5b03d5b` |
| BUG-20261004-03 | FIXED, with the child-layer limitation documented | `12ed18f`, `87808e5` |
| BUG-20261004-04 | FIXED | `5380bc3` |

---

## vauban (CDI 4.1 Lite container)

Branch `pr/ybl/inherited-interceptor-method`, range `7384ac1..6e58c9c`. History note: FV2 round 5
rewrote the history, so the round-4 commits `90cfde0..24718f0` no longer exist on the branch; this record cites
the rewritten SHAs `afc8b0b..00c22c8`.

### What phases A to D delivered

Nothing: vauban is not part of phases A to D. The branch started from the follow-up candidate noted in C2 (the
humboldt review): `VaubanInvocationContext.getMethod()` might resolve the original method on the direct
superclass only. Task FV1 confirmed the bug and found it wider; Task FV2 fixed the defects FV1 found (default and
non-public inherited methods, run-time and processor generators); the second final review added documentation,
message and test fixes and one regression fix.

### Fixes

| # | Fix | Why / how it was found | Commits | Tests | BUG.md id |
|---|---|---|---|---|---|
| X1 | `getMethod()` leaked the `$$super$` bridge for every method not declared on the bean class (parent, grandparent, half-way override, interface default), also dropping that method's interceptor bindings; runtime superclass walk plus interface default fallback, in both generators | Task FV1 (verify-only task confirmed the bug and found it wider) | `2d85326` | `InheritedInterceptedMethodTest` 6/6, `InheritedMethodModulePathTest` 8/8; CDI Lite 774/774, AtInject green | BUG-20261004-01 (FIXED) |
| X2 | `run-tck.sh` lacked `-am` (TCKs ran against installed jars, not the working tree) | Task FV1 finding | `472dae8` | script | — |
| X3 | Client proxies skipped interface default methods; run-time subclass did not intercept inherited protected/package-private methods; one `BusinessMethods` helper now serves `getMethod`, `getInterceptorBindings`, the chain and the "is intercepted" check | New OPEN bugs of FV1, Task FV2 (maintainer: fix every defect found) | `1165eb8` | `DefaultMethodInterceptionTest`, `InheritedInterceptedMethodTest` | BUG-20261004-02, -03 (FIXED, `1165eb8`) |
| X4 | An interceptor bound to a default method never ran while `getInterceptorBindings()` listed it; default-method bindings apply unless overridden | FV1 review minor 1, FV2 | `1165eb8`, `7202b86`, `2f56400` | `DefaultMethodInterceptionTest`, `NonBusinessMethodBindingTest` | BUG-20261004-04 (FIXED) |
| X5 | Module path: a bean bound only through an inherited method failed to deploy | Found while implementing FV2 | `1165eb8`, `2f56400` | `InheritedBindingModulePathTest`, `NonBusinessMethodBindingModulePathTest` | BUG-20261004-05 (FIXED) |
| X6 | Processor sources did not compile when the bean binds a type variable of an inherited method; first one override per member signature (duplicate methods), then the generic bean extended raw (regression vs main) | Found in FV2; Task review FV2 (Important); round 4 re-review (regression vs main, `label(Object)` restored) | `1165eb8`, `982bfa1`, `ae477c2` | `GenericInheritanceModulePathTest`, `InterceptedShapeFromElementsTest`, `ClientProxyInheritanceCrossCheckTest` | BUG-20261004-06 (FIXED) |
| X7 | Private superclass declarations shadowed business-method resolution | Task review FV2 (minor) | `7202b86` | `DefaultMethodInterceptionTest` | BUG-20261004-04 (follow-up) |
| X8 | "Is intercepted" counted bindings on non-business methods (private, static, cross-package package-private), giving "Could not define interceptor subclass" on the module path | Task review FV2 (minor, pre-existing) | `2f56400` | `NonBusinessMethodBindingTest` | BUG-20261004-05 (follow-up) |
| X9 | An unproxyable intercepted bean (final class or method) threw `DefinitionException`; now a `DeploymentException` stating why it is intercepted | Task review FV2 (minor) | `6c1952b` | `UnproxyableInterceptedBeanTest` | BUG-20261004-07 (FIXED) |
| X10 | No behaviour test for a protected method of a superclass in another package | Task review FV2 (minor) | `9fe86da` | `InheritedInterceptedMethodTest` (`ForeignProtectedBase`) | BUG-20261004-03 |
| X11 | `run-tck.sh` called PATH `mvn` (3.9.9) and a stale `sdk use`; TCKs now through `./mvnw`; `pr.yml` kept as the synced template, the CI case explained in `run-tck.sh` | Task review FV2 (minor) and controller ruling | `25d968c`, `67a662b` | script | — |
| X12 | A default shadowed by a private superclass method hit `IllegalAccessError` at call time (the `$$super$` bridge's `super.m()` resolves to the private method); now reached through its interface in both generators (run-time `invokespecial` on the listed interface, APT `MethodHandles.lookup().findSpecial`, fallback: method left out) | New defect from FV2 round 1 | `1de9e00` | `DefaultMethodInterceptionTest`, `ShadowedDefaultModulePathTest`, `InaccessibleDefaultOwnerTest` | BUG-20261004-08 (FIXED) |
| X13 | Regression of `1de9e00`: a client proxy `TaggedScoped extends PlainTagBase implements Tagged` reported "tag(String) is already defined"; shadow only by a declaration the bean does not inherit, plus a duplicate guard | FV2 round 1-2 re-review (Important regression) | `016858e` | `ShadowedDefaultModulePathTest`, `ClientProxyInheritanceCrossCheckTest` | BUG-20261004-08 |
| X14 | A shadowed default from a generic interface rendered a raw `implements Labeled`; now rendered with the bean's type arguments | FV2 re-review (minor) | `3caac57` | `ShadowedDefaultModulePathTest`, `InterceptedSourceRendererTest` | BUG-20261004-08 |
| X15 | A source-rendered producer proxy picked a package-private owner interface; the owner is chosen from the rendering package or not forwarded | FV2 re-review (concern 3) | `1e3a925` | `ClientProxyInheritanceCrossCheckTest` | BUG-20261004-08 |
| X16 | Run-time client proxy forwarded a package-private method of another package (duplicate method, `ClassFormatError`), and a shadowed default through a non-inherited method | FV2 round 3 re-review (Important A); round 4 | `54e5c9c` | `CrossPackageShadowedDefaultTest`, `ShadowedDefaultModulePathTest` | BUG-20261004-08 |
| X17 | A shadowed default with an unnameable type argument broke the source build; now left out with a message | FV2 round 3 re-review (minor), round 4 (C) | `abe4c2c` | `ShadowedDefaultModulePathTest`, `InterceptedShapeFromElementsTest` | BUG-20261004-08 |
| X18 | An interface was listed from a module that does not read it | FV2 round 3 re-review (minor), round 4 (D) | `d3fa351` | `DefaultOwnerReadabilityTest` | BUG-20261004-08 |
| X19 | Cross-check fixtures for every reviewed shape (p3-p10, producer shapes) with duplicate detection on both paths | FV2 round 4 (requested by the ruling) | `afc8b0b` | `ClientProxyInheritanceCrossCheckTest`, `InterceptedShapeFromElementsTest`; generated sources vs 7384ac1: 47 identical, 4 `_VaubanComponents` differ by entry order only | — |
| X20 | Regression vs main (probe n3c): normal-scoped bean whose inherited non-shadowed default names a type the bean's package cannot name produced a client proxy that did not compile; guard plus a Messager WARNING, producer proxies included | FV2 round 4 re-review (Important 2) | `d6023f5` | `InaccessibleMemberTypeModulePathTest`, `ClientProxyInheritanceCrossCheckTest` | BUG-20261004-09 (pre-existing part OPEN) |
| X21 | `BusinessMethods` was public in an exported package; now `core.codegen.BeanMembers`, exported to the processor only | FV2 round 4 re-review (minor) | `ff80960` | build; core/processor/module-it 538/122/47 | — |
| X22 | `ShadowedDefaults` public in the exported `core.interceptor`; moved to `core.codegen` (describeMethodBindings internal) | Second final review (minor 6) | `cce376b` | build | — |
| X23 | Inaccurate warning texts when a default is left out (three texts; producer warning said "bean") | Second final review (minor 3), FV2 round 5 re-review | `48df15c` | `OmittedDefaultDiagnosticTest` (RED first), `InterceptedShapeFromElementsTest` | — |
| X24 | Module-it test could not tell a forwarded default from one running on the proxy | Second final review (minor 5) | `210b594` | `InaccessibleMemberTypeModulePathTest` | — |
| X25 | No user-facing note of the behaviour changes (default-method bindings intercepted, final bean/method bound via a default now `DeploymentException`, final-class error type change, inherited non-public methods intercepted at run time, wider `getInterceptorBindings()`, new WARNINGs, mixed-version archives); refreshed `internals.adoc` | Second final review (Important 1) | `3f05c11` | docs only; probes n8, n9 | — |
| X26 | Regression vs main (probe n11a): normal-scoped bean whose inherited default names a private nested type of its own package failed with "Hidden has private access in Outer" (same-package shortcut treated a private nested type as nameable); a private nested type is never named from a generated class | Controller adjudication of the second final-fix wave (load-bearing, before merge) | `ee46fc4` | RED in processor x2 and a module-it compile error; `InaccessibleMemberTypeModulePathTest`; n11a/n11d/n11e proxies byte-identical to 7384ac1 | BUG-20261004-09 (private-nested shapes FIXED) |
| X27 | `internals.adoc:99` `$$...$$` passthrough swallowed text (pre-existing rendering bug) | Found by the fix wave (concern 3) | `6e58c9c` | 9/9 `$$` rendered | — |
| X28 | BUG.md bookkeeping: cite fix commits, correct -08 and -09 (the "hand the bean to the run-time generator" direction was untrue: the run-time subclass also throws `IllegalAccessError` for n3b), record two pre-existing generator gaps and the n11 outcome | Maintainer request; FV2 round 5 re-review; second final review (Important 2) | `e3ed4f3`, `b9e804b`, `39e31d2`, `00c22c8`, `d3fe360`, `110d2d7` | docs only | BUG-20261004-01 to -11 |
| X29 | Two processor warning texts were untrue in narrow branches: "the bytecode generators leave it out too" (false for a private nested carrier interface — the run-time subclass intercepts, probe n14) and "a call behaves as on a plain instance" (false when the bean implements the carrying interface directly, probe n13o) | Re-review of the second final-fix wave (minors A, B) | `8710532` | `InterceptedShapeFromElementsTest#omittedDefaultReportsCompareTheGenerators` failing first on the old texts; `PrivateCarrierBean` fixture pins the n14 divergence | BUG-20261004-08, -09 |
| X30 | `whats-new.adoc` / `internals.adoc` over-claimed that the processor leaves out any default it cannot write (for the intercepted subclass, only under a shadow); BUG-09 now records the subclass divergence and the protected-nested-type over-restriction (byte-identical to main); stale "public" for `BusinessMethods#isBusinessMethodOf` | Same re-review (minors C, D, E) | `f0e912c`, `0fd814c` | docs only, pages rendered | BUG-20261004-09 |

### TCK result (from the ledger)

CDI Lite TCK 774/774 and AtInject TCK 1/1 at the head `6e58c9c` (controller read the CDI Lite
`testng-results` at 19:55, after HEAD at 19:53), with 955 unit tests at the head, normal and reverse order; again
774/774 at the final head `0fd814c` (read at 20:14, after HEAD at 20:11). Earlier
gates: 28 modules green at FV1; 904, 917, 923, 929, 947, 949 unit tests after FV2 rounds 1 to 5, and 952 after the
first final fix wave, each with CDI Lite 774/774 and AtInject 1/1. Controller chain (19:07, head `00c22c8`): vauban 949 unit
tests (2 skips) installed, and heisenberg 154, dirac 105, cyrano 152 unit tests rebuilt from origin/main against it.
The interceptor-heavy bricks were rebuilt against the new processor by ruling so the runners exercise its
generated code. Generated sources versus `7384ac1`: no change for unaffected beans.

### Second final review (Phase F)

Verdict: With fixes (doc/text/test, no behaviour change expected). Important: no user-facing note of the behaviour
changes; BUG-09 "Cause/Fix direction" untrue. Fix wave `cce376b`, `48df15c`, `210b594`, `d3fe360`, `3f05c11` (rows X22-X25, X28). The fix agent then found a regression versus main (n11a), fixed by `ee46fc4`, `110d2d7`, `6e58c9c` (X26-X28);
a scoped re-review of the whole final wave (`00c22c8..6e58c9c`) said ready to merge: every finding addressed,
n11a/n11d generated sources byte-identical to `7384ac1`, no lost forwarding versus main, ordinary beans unchanged. Its
remaining text inaccuracies (two warning texts, two doc sentences, one stale `BUG.md` line, two divergences to record
under BUG-09) were fixed by a text-only round: `8710532` (warning texts, test failing first on the old texts, n14
divergence pinned by a fixture), `f0e912c` (BUG-09), `0fd814c` (docs). The controller read the production diff
(comments and message strings only) and CDI Lite 774/774 at `0fd814c`; probe generated sources are identical to
`6e58c9c` except the pre-existing `_VaubanComponents` entry order. VAUBAN FINAL REVIEW CLOSED, head `0fd814c`.

### BUG.md

| Id | Status | Fix commit |
|---|---|---|
| BUG-20261004-01 | FIXED | `2d85326` (resolution `1165eb8`, `7202b86`) |
| BUG-20261004-02 | FIXED | `1165eb8` |
| BUG-20261004-03 | FIXED | `1165eb8` (test `9fe86da`) |
| BUG-20261004-04 | FIXED | `1165eb8` (`7202b86`, `2f56400`) |
| BUG-20261004-05 | FIXED | `1165eb8` (`2f56400`) |
| BUG-20261004-06 | FIXED | `1165eb8` (`982bfa1`, `ae477c2`) |
| BUG-20261004-07 | FIXED | `6c1952b` |
| BUG-20261004-08 | FIXED | `1de9e00` (`016858e`, `3caac57`, `1e3a925`, `54e5c9c`, `abe4c2c`, `d3fa351`) |
| BUG-20261004-09 | OPEN, pre-existing on main `7384ac1` (probes n3a, n3b, n3d; n11b, n11c); the branch regressions n11a, n11d and n11e are FIXED | `ee46fc4` |
| BUG-20261004-10 | OPEN, pre-existing: the Maven plugin pre-generates the intercepted subclass only for a bean with a class-level binding (from the code, not reproduced) | — |
| BUG-20261004-11 | OPEN, pre-existing: the index-based client proxy forwards only the methods the bean class declares (from the code, not reproduced) | — |

Open items: BUG-09 shapes n11b and n11c and the n3 family (n3a, n3b, n3d); the two generator gaps
(BUG-10 and BUG-11); the documented divergence between the bytecode (run-time) generator and the source
generator for the private-nested-type shapes.

---

## vidocq (runtime)

### What phases A to D delivered

Phase D aligned the runtime's OpenTelemetry SDK on 1.66.0 and the humboldt-cassini IT dependencies
(`543be6b0`), added the MicroProfile 7.2 runners for JWT 2.2, OpenAPI 4.2-RC5 and Telemetry
2.2-RC3 (`65e89480`), then updated the documentation: spec versions, TCK status, Core Profile 11
endorsement (`05d21cfc`). Phase F added the Phase F plan and the follow-up documentation work.

### Fixes

| # | Fix | Why / how it was found | Commits | Tests | BUG.md id |
|---|---|---|---|---|---|
| V1 | ROADMAP JWT/OpenAPI rows still "TODO", Telemetry "TCK in progress", README OpenAPI/JWT "Planned"; `migration.adoc` and `TCK.md` claimed "certified" on the assembled runtime (1837) | Task review D2 (Important) plus controller ruling extending the honesty rule to untouched hunks | `2ff32048` | docs only; re-review round 1 | — |
| V2 | "All eight extensions pass their official MicroProfile 7.2 TCKs": Metrics 5.1 is not a 7.2 spec (`migration`, `tck`, `whats-new`, `TCK.md`) | Re-review of D2 | `57bcff08` | docs only; re-review round 2 addressed | — |
| V3 | `tck.adoc` JWT exclusions text, README/ROADMAP Metrics under "7.2", README table missing Telemetry/RC/FT, CERTIFICATION caveat, whats-new wording | Final review (2 Important plus minors) | `3227357d` | docs only | — |
| V4 | Stale TCK comments and counts (344 vs 346, tck-nightly 1085 to 1105, stale `arquillian.xml` and `tck-suite.xml` comments, example props, French on touched pom lines) | Final review (minors) | `1f6e6b24` | docs and comments; scoped re-review 11/11 addressed | — |
| V5 | OpenTelemetry versions defined once in the root pom (extension, IT and runner), stale counts and documentation refreshed | Phase F Task FD1, follow-up of the parked "single shared OTel version property" | `eaf3132d` | `it-humboldt-cassini` 4/4; same resolved versions | — |
| V6 | French and stale (Jersey/Jetty, Model 4.1.0, dead URL) text in `vidocq-runtime-extensions/pom.xml`; out-of-reactor claims in CERTIFICATION, DEBUGMODE, HOWTO-CLAUDE; ROADMAP imprecision | Task review FD1 | `19440dbd` | docs only; re-review 3/3 addressed | — |
| V7 | `tck.adoc` claimed out-of-reactor runners, "never `-pl`" and nonexistent runner directories; Servlet "~90 %" and `vidocq-servlet-chappe-tck-runner` (today Foy `foy-tck` 921/1714, `api.*` 95.6 %); `TCK.md` dead `vidocq-runtime-rest-cassini-tck-runner` links | Second final review (Important 1-3); layout re-verified on origin/main poms | `2de6134d` | docs only; `tck.adoc` renders (6 sections, 2 tables) | — |
| V8 | `CERTIFICATION.md` 202-209 prose unclear; `HOWTO-CLAUDE.md` scope needed a date | Second final review (minors, plus the FD1 carried item) | `e3c5125e` | docs only | — |
| V9 | French comments in the `it-humboldt-cassini` pom | Second final review (Important 5) | `4cd21b57` | `xmllint` ok | — |
| V10 | French field labels in `BUG.md` | Second final review (minor) | `488b6fa2` | docs only | — |
| V11 | French comments in 10 other poms (11 poms in total with V9) | Second final review (minor) | `993382b5` | 16 poms `xmllint`-clean | — |
| V12 | French comment in the `cervantes-jwt-extension` pom (~133) and `champollion-protobuf-tck` described as a TCK | Residuals of the second final re-review, fixed directly by the controller | `242a09ea` | `xmllint` ok; `tck.adoc` renders | — |

Plan updates made by the controller (docs only, not fixes of product code):

| Commit | Update |
|---|---|
| `0475398d` | Reactivate the plan against the release candidates |
| `faaa31ac` | Record what phases A to D actually produced (plan drift) |
| `b9c5433e` | Add Phase F |
| `bf18fe78` | Plan correction: grimm-tck stays out of the grimm reactor |
| `dc4e879d` | Add Task FV2 (two interception defects in Vauban) |
| `dbff362c` | Add Task FB4 (grimm fidelity points found by the reviews) |
| `5081894d` | Add Task FC3 (humboldt defects found by the FC1 review) |
| `286b2b41` | Add Task FB5 (full `@SchemaProperty` and `@Schema.examples` mapping) |
| `e78c8826` | Add Task FC4 (OTLP exporters on the module path) |
| `34e64108` | Add Task FA3 and record the end of Phase F (stale STATUS) |

### TCK result (from the ledger)

Assembled runtime, D1 (controller read all 8 logs, 0 failures): JWT 208, OpenAPI 364, Telemetry 85,
Health 28, Metrics 127, RC 235 (9 skipped), FT 463, Config 378, total 1888. The IT
`humboldt-cassini` passes 4/4. The final verification (15:09-15:20) re-ran JWT 208, OpenAPI 364
and Telemetry 85 with 0 failures, and `clean install` with unit and IT tests succeeded. The
runner re-run against the Phase F bricks and vauban was done by the controller at 19:07: vauban 949 unit tests
(2 skips) installed, heisenberg 154, dirac 105 and cyrano 152 unit tests (origin/main rebuilt against the new
Vauban), cervantes TCK 208, grimm 367, humboldt 85, vidocq `clean install` green, and the eight runners
Config 378, OpenAPI 364, Metrics 127, JWT 208, Health 28, RC 235 (9 skipped = baseline), FT 463, Telemetry 85 =
1888, the same as Phase D.

### Second final review (Phase F)

Verdict: With fixes (docs only). Important: out-of-reactor claims and dead runner links in `tck.adoc` and
`TCK.md`, Servlet figures, the plan lacking FA3 (fixed by the controller, `34e64108`), French in a pom.
Fix wave `2de6134d`, `e3c5125e`, `4cd21b57`, `488b6fa2`, `993382b5` (rows V7-V11), then residuals `242a09ea` (V12). Re-review:
Important 1-5 and minors 6-9 addressed. VIDOCQ FINAL REVIEW CLOSED, head `242a09ea`. The Foy README figure
(920 vs 921) is another repository and goes to the follow-up list.

### BUG.md

No entry dated 2026-10-04 in `vidocq/BUG.md` (latest is BUG-20261001-01).

---

## Rulings

One line each: decision, then the reason. Ledger lines starting with `- Ruling:`, grouped by
repository. The first group applies to the whole upgrade.

### Whole upgrade (process)

1. Lanes A (cervantes), B (grimm), C (humboldt) run in parallel, tasks sequential inside a lane — separate repos and worktrees, ephemeral ports.
2. Build against the RCs on Central (JWT 2.2, OpenAPI 4.2-RC5, Telemetry TCK 2.2-RC3) — maintainer decision, RC = final byte-wise; re-run on finals is Task E1.
3. Tasks never push nor open PRs; the controller pushes after the final review and with the maintainer's go — pushes are outward-facing.
4. Provenance trailer is `Co-Authored-By: Claude Opus 5.5` (old plan trailer and Claude-Session line dropped) — matches the session's attribution.
5. Briefs are extracted with a custom awk — task ids A1..D2 are not numeric, so the stock `task-brief` script does not match.
6. Use `./mvnw` (Maven 3.9.16) — `mvn` on PATH is 3.9.9.
7. Final whole-branch review is one reviewer per repository on the most capable model — four independent branches, four PRs.
8. While the merge-order check uses `~/.m2`, fix agents must not `install` or run brick TCK scripts — they install; the controller re-runs the grimm TCK afterwards.
9. Phase F lanes run in parallel across repos, sequential inside a repo — same reasoning as phases A to C.
10. Vauban never installs during the lanes; the controller installs it at the end and re-runs bricks and runners against it — avoids a late vauban regression surprise.
11. Also rebuild the interceptor-heavy bricks (Fault Tolerance, Metrics, Rest Client) against the new Vauban processor in the end-of-Phase-F chain — the runners then exercise its generated code, not only the run-time side (about 30 minutes more).
12. Start the verification chain in parallel with the round-5 re-review — saves about an hour; the chain is re-run if the re-review forces a code change.
13. Second final review: one fix wave per repository covering every finding except the cosmetic trailer-order item (humboldt: minors 1-4 with TDD for 4; vidocq: Important 1, 2, 3, 5 and minors 6-9) — the maintainer asked to fix all points.

### cervantes

1. A3 fix round 1 also covers the tck.adoc "MicroProfile 7.2" claim, the production "JWT 2.1" wording and the logo PNG/SVG — public claims must not anticipate the platform release.
2. One fix wave for cervantes covering the Important and minors 2-7 — all small, doc accuracy and API compatibility.
3. FA1 fix round 1 covers the Important and minors 2-5; commit split parked — rewriting signed history is not worth it.
4. New Task FA3: an unrecognised `mp.jwt.decrypt.key.algorithm` fails at container start — consistency with FA1.
5. Second final wave: Important 1 and minors 3, 4, 5, 7, plus the `@Deprecated` annotations, the `@ApplicationScoped` Javadoc and the TCK script (`./mvnw` and `clean`); skip minor 6 (documented behaviour) and the Antora release-version (correct per the versioning scheme); the `repo.vidocq.dev` host goes to the maintainer follow-up list — outward infrastructure, not this branch.

### grimm

1. B4 fix round 1 extracts the external-docs mapping and B3's duplicated helpers into one shared helper — plan mandated copies, but maintainability wins; behaviour identical.
2. The B5 minor `@Digits(integer = 0)` regex fix is carried into B6 as a small TDD fix — it emitted a broken pattern into user documents.
3. Grimm fix wave covers both Importants, doc minors, script `clean`, and BUG.md entries for three pre-existing defects (logged, not fixed) — changing `setAll`/`getAll` semantics was a behaviour change outside the upgrade (later fixed in FB2 on the maintainer's request).
4. FB1 fix round 1: merger copies refs verbatim, config `ref` alias keeps its short-name expansion, `.inf`/`.nan` stay strings — compatibility and no JSON representation.
5. Plan correction: grimm-tck stays out of the grimm reactor (root pom has no tck module or profile) — decoupling reason; plan and FB3 brief fixed.
6. `set("type", list-of-wrong-type)` is kept as written instead of throwing — 4.2 `Schema.set` Javadoc allows other-dialect values, consistent with FB2.
7. Named component schemas from two sources keep "higher priority replaces whole" (documented in FB3) — pre-existing, deterministic.
8. BUG-20261004-07 becomes Task FB5, run before FB3 — maintainer asked to fix every defect found.
9. FB4 fix round 1 covers the Important, the spec processing order, the JSON parser, the model-limitation docs and polish — spec-mandated precedence change.
10. Second final wave fixes all 9 findings (Important with TDD and BUG.md entries) — the maintainer asked for every point.
11. `hasAnyContent` becomes micro-task FB6, run by resuming the final-fix implementer: decide from the shared `SchemaAttributes` (every attribute against its real default), BUG.md entry, invalid-JSON examples test — a pre-existing, user-visible silent loss of annotations.
12. FB6 re-review minor: add the three call-site tests (header, content, encoding header) by the same implementer; test-only, the controller reads the diff, no TCK re-run.

### humboldt

1. Revert the three SDK artifacts to compile scope; every humboldt TCK figure comes from a clean `humboldt-tck` build — the scope carry-item was the controller's mistake.
2. Humboldt fix wave: setException, setEventName and VALUE encoding with tests, detached `@WithSpan` in `Context.root()`, ComponentLoader limit in BUG.md, docs, script `clean` — tests cover what the TCK does not.
3. New Task FC3 for the OTLP/JSON metric encoder defect — new defect found.
4. New Task FC4 for BUG-03/04: qualified export of `io.opentelemetry.api.internal`, a humboldt-owned `ServiceLoaderComponentLoader`, empty-string body kept — reference behaviour is OTel 1.66.
5. FC4 fix round 1: survey all non-exported packages with jdeps, end-to-end module-path export test, reopen BUG-03 honestly, runtime `Module.addExports` fallback — the first fix was incomplete.
6. FC4 minors (a) per-provider guard and (b) docs for incubating artifacts go into FC2 — cheaper than a second FC4 round.
7. FC2 child-layer limitation: document it (README, `reference.adoc`, BUG-03) and do not widen the layer package export — an unqualified export would let any module ask for internal exports, and the Vidocq runtime never builds that layout.

### vauban

1. Add Task FV2 (BUG-02, BUG-03 and the `run-tck.sh` `-am` fix) — maintainer asked to fix every defect found.
2. FV2 fix round 1 covers all review findings except the pre-existing weaver "Java agent loaded dynamically" warning — log noise, out of scope.
3. FV2 fix round 2: BUG-08 fixed by reaching the shadowed default through its interface in both generators, `pr.yml` edit dropped (synced template) — avoids drift.
4. FV2 fix round 5 (last) resumes the round-4 implementer instead of a fresh one — it addressed 4 of 4 and the new findings came from its hunk split and from round 1; scope: rewrite `39e31d2..HEAD` so every commit compiles (re-signed), the n3c guard, `BeanMembers` not public API, the unnameable-member-type entry in BUG.md.
5. FV2 breaker reached at round 5/5, adjudicated: no load-bearing code finding remains (doc, message and test precision only; the defect is pre-existing on main), so the rest is carried into the final review's single fix wave, which does not change behaviour.
6. Vauban final fix wave covers findings 1-6 and 8, and records 7 under BUG-09 (no processor behaviour change) — same pre-existing family.
7. Keep the `Co-Authored-By` trailers on the eight rewritten commits — they truthfully declare AI assistance; rewriting signed commits for the model name is not worth it (same spirit as the trailer rulings).
8. The n11a regression (private nested type) is load-bearing, fix before merge with TDD by resuming the final-fix implementer, and escape the `$$` pairs in `internals.adoc` — a regression versus origin/main.

### vidocq

1. D2 limited to the vidocq repository; other docs repos go to Task E2 after merge — public docs follow merged reality.
2. D2 fix round 1 also covers `migration.adoc` and `TCK.md` "certified on the assembled runtime" — the honesty rule is binding on touched pages.
3. Vidocq fix wave covers Important 1-2 and all doc minors — park the shared OTel version property (later done in FD1).
4. The merge-order Important is not a code fix: verify what brick-PR `build-impacted` will see and report the order — bricks first, vidocq last.
5. Example UI copy and the petstore README stay in French for now — user-visible strings possibly asserted by UI tests, a separate concern from the MP 7.2 PR; they go to the maintainer follow-up list.
6. The two residuals of the second final re-review (French pom comment, `champollion-protobuf-tck` described as a TCK) were fixed directly by the controller in `242a09ea` — trivial, reviewer-listed.

### Rulings recorded inside task lines

| Repository | Decision — reason |
|---|---|
| cervantes | Park the compat-constructor Javadoc "RS256 and ES256 families" — cosmetic. |
| cervantes | Park "@Dependent producer fails at first injection" — consistent with existing config errors (FA1 later made validation fail at start). |
| cervantes | Park `BuildPhase` catching `LinkageError` — no realistic cost. |
| cervantes | FA2 fix round accepted without re-review; FA3 polish `7a21f0b` accepted without re-review — comment/Javadoc/docs-only fixes, build verified. |
| grimm | FB3 docs polish `5858b40` accepted without re-review — docs-only. |
| grimm | Park the five residual points after the final wave (see Parked). |
| grimm | Keep `Co-Authored-By: Claude Sonnet 5.5` on FB5 — both attributions are honest provenance; no history rewrite. |
| humboldt | Accept the `getName()` fallback on logs and the `LogRecordData` component change (FC1) — keeps `exception.type` for anonymous classes; old constructors kept. |
| humboldt | Park residuals after the final wave (fixed later in FC1) and the plain-install sources-jar quirk (see Parked). |
| humboldt | Keep `Co-Authored-By: Claude Sonnet 5.5` on FC2 — actual model, honest provenance, same as FB5. |
| humboldt | FC2 fix round 1 `f4aae54` accepted without re-review — controller read the whole diff, reviewer-listed items only. |
| vauban | FV2 fix round 4 goes to a fresh, more capable implementer — skill rule for rounds 4-5. |
| vauban | Keep the Opus trailers on Fable-written commits (finding 9) — truthful AI-assistance declaration. |
| cervantes | Second final fix wave: re-review approved; the TCK script comment moved by the controller (`e4a5ca8`). |
| humboldt | Trailer order skipped in the second final wave (global ruling). |
| vidocq | Park the single shared OTel version property, absolute paths in the plan, and three residual minors (see Parked). |

---

## Parked / not fixed / follow-ups

Every finding the ledger marks parked or deferred. "Fixed later" means the ledger shows a later fix.

### cervantes

| Finding | Reason / outcome |
|---|---|
| Compat-constructor Javadoc says "RS256 and ES256 families" (loose wording) | Parked, cosmetic |
| `@Dependent` producer fails at first injection, not at JVM boot | Parked in the final review; fixed later by FA1 (`826e927`) |
| `BuildPhase` catches `LinkageError` (could read an initialiser error as "not build time") | Parked, no realistic cost |
| FA1 commit split (minor 6) | Parked, rewriting signed history is not worth it |
| Long ROADMAP lines | Parked, cosmetic |
| Possibly dead `repo.vidocq.dev` snapshots repository in the cervantes-tck pom | Declined in the second final review; maintainer follow-up (outward infrastructure) |
| Decrypt settings ignored when no verify key is set | Documented behaviour, minor 6 skipped |
| Antora release-version in cervantes | Skipped, correct per the versioning scheme |
| A1 minors: `PemKeysTest` lacks an invalid-base64 case; `privateKeyFromPem` duplicates armour stripping | Deferred in the A1 review; no later fix recorded (`14df45b` shares the PEM decoding) |
| A2 minors: no RED run captured, no producer-level EC PEM with RS256 test | Deferred; no later fix recorded |
| Trailer order (`Co-Authored-By` before `Signed-off-by`) | Deferred, cosmetic (hook appends the sign-off) |

### grimm

| Finding | Reason / outcome |
|---|---|
| `$ref` short-name expansion now also reaches allOf/anyOf/oneOf/not/if/then/else/contains/prefixItems/dependentSchemas/patternProperties | Parked after the final wave, same pre-existing rule widened; reported as a follow-up |
| `x-` keys inside a static `discriminator` dropped silently (4.2 Discriminator is not Extensible) | Parked, needs an "upstream limitation" BUG entry or a side map; the FB1 task mapped other discriminator keys |
| Numbers written in plain notation (`1e-5` becomes `0.000010`) | Parked, follow-up |
| `YamlDeserializer` does not read `1e3`, `.5`, `+1` as numbers | Parked, pre-existing |
| ROADMAP.md:334 and :379 stale lines | Parked, follow-up (FB3 fixed other ROADMAP wording only) |
| `summary` next to a `$ref` (BUG-20261004-10) | WON'T FIX, MP OpenAPI model has no field |
| `Callback` non-`$ref` siblings and `Reference` `summary` dropped | Documented as a model limitation in FB4 |
| B2 minors: dead `extensions` field comment; reflective `set("extensions", map)` dispatch | Deferred; no later fix recorded |
| B5 minors: `toPlainString` comment duplicated in both serializers; `jakarta.validation-api` version hard-coded in grimm-core pom | Deferred; no later fix recorded |
| B6 minor: TCK.md and `tck.adoc` say "every method runs once per format" (untrue for 6 plain tests) | Deferred; no later fix recorded |
| `AnnotationScannerTest.java:339` `allowEmptyValue` warning in test code | Deferred, pre-existing |

### vidocq

| Finding | Reason / outcome |
|---|---|
| Single shared OTel version property across extension, IT and runner | Parked in the final review (parent-level refactor); done later in FD1 (`eaf3132d`) |
| Plan still carries `/Users/...` worktree paths | Parked, other plans in `docs/superpowers` carry absolute paths too |
| Root `pom.xml:62` `ci.tck.modules` comment still says "1085" (should be 1105) | Parked, no second fix wave |
| README tree comment (~132) omits Fault Tolerance | Parked |
| French comment in `it-cervantes-jwt` pom (~586), untouched lines | Parked |
| CERTIFICATION.md ~202-205 awkward prose | Deferred to the vidocq final review |
| README Servlet TCK paragraph mentions Model 4.1.0 and an out-of-reactor runner | Deferred, pre-existing |
| foy README says 920 and 95.5, `TCK.md` says 921 and 95.6 | Outside this repository, follow-up for the maintainer (Foy README 920 vs 921) |
| Example UI strings, petstore README, `generate_banners.py` and the CLI roadmap still carry French copy (also proper names, accent test data, vendored swagger-ui) | Maintainer follow-up: user-visible strings possibly asserted by UI tests, separate concern from this PR; a later small sweep |
| French field labels and comments in poms | Fixed in the second final wave (`488b6fa2`, `993382b5`, `4cd21b57`, `242a09ea`) |
| Phase E: switch to the final artifacts once they are on Maven Central, the MicroProfile 7.2 BOM, and the outside docs (vidocq-docs, vidocq-workspace, knock, workspace CLAUDE.md, Task E2) | Still open; the TCKs were run on release candidates (Task E1 re-runs them on the finals) |

### humboldt

| Finding | Reason / outcome |
|---|---|
| Plain `clean install` (no snapshot/release profile) builds the `humboldt-otel-api` and `-context` sources jars without humboldt's own sources | Parked in FC4 (build-local quirk, published jars correct); fixed later by `7e50f10` after the second final review |
| Child layer limitation: interop in a child layer of the API module keeps the exports unextended, so an exporter may later fail with `IllegalAccessError` | Deliberately not fixed in FC2: documented in README, `reference.adoc` and BUG-20261004-03, with the workaround; widening the export is refused (see Rulings) |
| Span `exception.type` used `getName()`; non-finite doubles; `setBody(Value)`; JUL `getThrown()`; `humboldt-tck/README.md` "outside the reactor"; leftover French comments | Parked after the final wave; fixed later in FC1 (`693bc57`, `08dce17`, `e42d0c0`, `e9118b7`) and FC2 (`4abe25a`) |
| C1 minors: comment on the unqualified `io.opentelemetry.common` export; gatekeeper note on explicit test scope in `humboldt-tck` | Deferred; scope handled by FC1/H13 (TCK deps back to test scope), the module-info comment reason was a final-review minor fixed in `4c4938e` |
| C2 minor: `CODE_FUNCTION_NAME` Javadoc says "OTel semconv" while avoiding the semconv artifact | Deferred; the second final review states the C1/C2 deferred items were verified fixed |
| C3 minor: `SpanDataMapper` duplicated in interop and the TCK bridge | Deferred; fixed in FC1 (`f5e8bd7`) |

### vauban

| Finding | Reason / outcome |
|---|---|
| Weaver "Java agent loaded dynamically" warning | Out of scope, log noise |
| BUG-20261004-09 open shapes: `n3a`, `n3b`, `n3d` (pre-existing unnameable member type, breaks the source build on main too), `n11b`, `n11c` | OPEN; needs a design decision. The "hand the bean to the run-time generator" idea is untrue: the run-time subclass also throws `IllegalAccessError` for `n3b` |
| BUG-20261004-10: the Maven plugin pre-generates the intercepted subclass only for a bean with a class-level binding | OPEN generator gap, pre-existing on main, from the code, not reproduced |
| BUG-20261004-11: the index-based client proxy forwards only the methods the bean class declares | OPEN generator gap, pre-existing on main, from the code, not reproduced |
| Documented divergence between the bytecode (run-time) generator and the source generator for private-nested-type shapes | Documented in BUG-09, not fixed |
| A superclass non-inherited method with the same name and parameters but another return type claims the key, so the default is not forwarded | Known limitation under BUG-08, not a regression, both paths agree |
| Type-annotation arguments are not checked for nameability; intermediate commits of rounds 1-3 not built individually | Concerns of round 4, no fix recorded (round 5 rewrote the history so every commit builds) |
| `ShadowedDefaults` / `HiddenArgument#toString` commit placement nits | `ShadowedDefaults` fixed by `cce376b`; placement nit not recorded as fixed |
| `internals.adoc:370-371` samples kept | Still current per probe n9; mixed-version generic case not new per probe n8 |
| Eight rewritten commits carry the Opus trailer although written by another model | Kept by ruling |

---

## Final verification

Run by the controller on 2026-10-04 (19:56–20:15), everything from a clean state, against the branch heads
vauban `6e58c9c` (the head `0fd814c` differs only by comments, message strings and docs), cervantes `e4a5ca8`,
grimm `548f83f`, humboldt `5028008`, vidocq `242a09ea`:

| Step | Result |
|---|---|
| Vauban `clean install` (installed in the local repository) | green, 955 unit tests at the final head (2 pre-existing skips); CDI Lite 774/774, AtInject 1/1 |
| heisenberg, dirac, cyrano rebuilt from `origin/main` against that Vauban (so the runners exercise the new processor's generated code) | green |
| cervantes `clean install` + official MP JWT 2.2 TCK | green, 208/208 |
| grimm `clean install` + official MP OpenAPI 4.2 TCK (RC5) | green, 367/367 (364 official + 3 local) |
| humboldt `clean install` + official MP Telemetry 2.2 TCK (RC3) | green, 85/85 |
| vidocq `clean install` (unit tests and integration tests) | green |
| Runtime TCK runners on the assembled runtime | Config 378, OpenAPI 364, Metrics 127, JWT 208, Health 28, Rest Client 235 (9 skipped, as before), Fault Tolerance 463, Telemetry 85 — 1888, the same total as after Phase D |

The Metrics runner first failed one deployment with `BindException: Address already in use` while a Vauban build
ran at the same time (the known port flake); run again alone, it gave 127/0/0.

End-to-end fail-fast check (cervantes example in the assembled runtime, `vidocq:run`): with
`mp.jwt.verify.publickey.algorithm=HS999` the start fails with a `DeploymentException` naming the property, the bad
value and the supported values; with the shipped configuration it starts in 135 ms.

---

## Process lessons

- TCK scripts without `clean` ran on stale classes. The C4 review found that `humboldt-tck` is only in the reactor under `-Ptck`, so `clean install` never rebuilt it and the 85/85 figure reused stale classes; a compile-scope break was hidden. Rule since then: every TCK figure comes from a clean build of the TCK module. The grimm script gained `clean` (`4c63d52`); the humboldt script and `run-official-tck-mp-openapi-4.2.sh` were flagged for the same fix. The cervantes figures were re-taken from a clean TCK module.
- grimm-tck stays outside the grimm reactor (the grimm root pom has no tck module or profile, kept for release-versus-TCK decoupling). The FB3 brief wrongly said it was in-reactor; the plan and the brief were corrected (`bf18fe78`) before dispatch.
- Fix rounds introduced regressions that later reviews caught: C4's `<scope>test</scope>` carry-item broke the clean compile; the B2 static-file keyword regression reached the final review; FB1's first fix still re-expanded refs in the merger; FB4's merger guard dropped explicit empty values; FV2 rounds 1-2 added client-proxy duplicates and a generic-interface regression versus main (`1de9e00`, caught in round 3), and round 3 left two more Important breakages; BUG-06 (vauban) was closed as FIXED while incomplete; BUG-03 (humboldt) was closed before every exporter-reached package was exported.
- Implementer reports are not evidence. B3's report claimed the build warnings were gone; A2 and B2 captured no RED run or mirrored the brief instead of the TCK, so the controller fetched the TCK sources for review; reviewer and controller re-read logs and re-ran TCKs themselves.
- Public claims must not anticipate the platform release. D2 and A3 reviews removed "MicroProfile 7.2" and "certified" wording; Metrics 5.1 is not a 7.2 spec, so "all eight extensions pass their 7.2 TCKs" was false.
- Rewriting history is a last resort but it worked: FV2 round 5 rewrote `39e31d2..HEAD` because the round-4 hunk split left intermediate commits that did not compile (bisect broken on main), and checked every commit from archives. Cite SHAs from after the rewrite.
- Closing a bug early is a recurring risk: BUG-06 (vauban) and BUG-03 (humboldt) each needed a later round, caught by re-reviews that probed the original case.
- Pre-existing defects found by a late review (grimm `hasAnyContent`, annotation default sentinels) are invisible to the TCK; they were found only by probes against the real annotation defaults.
- Merge order: the bricks go first (any order), vidocq last. Main's grimm-openapi gate stays red between the grimm SNAPSHOT and the vidocq merge. A full `clean install` of vidocq `origin/main` against the new bricks passed.
- The shared `~/.m2` couples lanes: fix agents must not `install` or run TCK scripts during a merge-order check, and vauban installs only at the end.
- Rounds 4 and 5 of a fix loop go to a fresh implementer on a more capable model (applied to FV2 round 4).
- The workspace PATH `mvn` is 3.9.9; the wrappers pin 3.9.16. `run-tck.sh` in vauban still called the PATH `mvn` until FV2.
- Docs-only reviewer-listed minors were accepted without re-review in three places (FA2, FA3 polish, FB3 polish); no slip is recorded.
