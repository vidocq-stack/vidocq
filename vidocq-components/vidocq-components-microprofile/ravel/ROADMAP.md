# Ravel — Roadmap

> MicroProfile Config 3.1 implementation in the Vidocq style: zero third-party libraries
> (Jakarta EE / MicroProfile specs allowed), JDK 25, virtual threads, strict Java Modules,
> optional CDI integration via Vauban.

## Guiding Principles

| Principle | Concrete application |
|---|---|
| Zero third-party libraries | No Smallrye Config, Helidon Config, Apache Commons Config in `ravel-core`. Only spec APIs (`microprofile-config-api`, `jakarta.inject`, `jakarta.cdi-api`, `jakarta.annotation-api`) are compiled. |
| Jakarta / MicroProfile specs allowed | `ravel-cdi-vauban` may depend on `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. The core `ravel-core` is limited to `microprofile-config-api`. |
| Virtual threads | No `synchronized`, no `ThreadLocal`. `ConcurrentHashMap`/`ClassValue` caches. Expression cycle detection via `ScopedValue`. |
| Strict Java Modules | `module-info.java` everywhere, `internal.*` packages not exported, SPI via `provides/uses`. No unjustified `opens`. |
| Strict TDD | Red → Green → Refactor. Tests written before production code. Systematic citation of the MicroProfile Config 3.1 spec section in test Javadoc. |
| TCK 100% PASS | Hard contract on the MicroProfile Config 3.1 TCK before any structural merge. |
| Measured performance | JMH from M1, systematic comparison with Smallrye Config (MP reference), baseline ratchet. |
| AOT-friendly | No dynamic proxy generation, no `setAccessible(true)` at runtime except for implicit converters (`String` constructor / `valueOf` / `parse`). Compatible with GraalVM `native-image`. |

## Methodology: TDD + TCK as parallel guardrails

Ravel is developed with **strict TDD** (Red → Green → Refactor). No production line is
written before a test that justifies it. Beyond the internal TDD cycle:

- **Layer 1 — TDD unit tests**: drive the design of each class.
- **Layer 2 — `ravel-core` integration tests**: multi-source scenarios, ordinal cascades,
  nested expressions, combined profiles. Independent of the TCK and reproducible without Arquillian.
- **Layer 3 — Official TCK** (`microprofile-config-tck:3.1.1`): 100% PASS contract before any
  structural merge. Out-of-reactor module (POM Model 4.0.0).
- **Layer 4 — JMH benchmarks**: `ravel-bench` compares lookup throughput, expression resolution
  overhead, conversion cost vs Smallrye Config on the same JVM.

## Phases

### M0 — Bootstrap ✅

- [x] `.sdkmanrc` (`java=25-tem`, `maven=3.9.16`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] Parent `pom.xml` (Model 4.1.0, multi-module, Jakarta + MicroProfile dependency management)
- [x] `CLAUDE.md`
- [x] `ROADMAP.md` (this file)
- [x] Created 5 sub-modules with skeleton `pom.xml` + `module-info.java`:
      `ravel-api`, `ravel-core`, `ravel-cdi-vauban`, `ravel-bench`, `ravel-tck` (out-of-reactor)
- [x] `LICENSE` (Apache 2.0)
- [x] `README.md`
- [x] `mvn -ntp install -DskipTests` succeeds (reactor + standalone ravel-tck)

**Deliverable:** `mvn -ntp install -DskipTests` succeeds on the reactor (ravel-api, ravel-core,
ravel-cdi-vauban, ravel-bench) and also compiles the out-of-reactor project `ravel-tck` (POM Model 4.0.0).
All `module-info.java` files can `requires org.eclipse.microprofile.config` thanks to version
**3.1.1** (published 2026-04-22) which adds `Automatic-Module-Name: org.eclipse.microprofile.config`
to the API JAR manifest — the original 3.1 (published 2023) had neither a descriptor nor an
automatic name.

---

### M1 — Built-in sources + basic lookup ✅

**Spec scope:** §3 (ConfigSource), §4 (Built-in ConfigSources), §2.1 (Config lookup).

| Task | Notes | Status |
|---|---|---|
| `RavelConfig` implements `org.eclipse.microprofile.config.Config` | `getValue`, `getOptionalValue`, `getValues`, `getOptionalValues`, `getPropertyNames`, `getConfigSources`, `getConfigValue`, `getConverter`, `unwrap` | ✅ |
| `RavelConfigBuilder` implements `ConfigBuilder` | `addDefaultSources`, `addDiscoveredSources`, `addDiscoveredConverters`, `withSources`, `withConverter(s)`, `forClassLoader`, `build` | ✅ |
| `RavelConfigProviderResolver` extends `ConfigProviderResolver` | Singleton via ServiceLoader, one `Config` per `ClassLoader`, `ConcurrentHashMap` + atomic `computeIfAbsent` (tested under 100 concurrent virtual threads) | ✅ |
| `META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver` + Java Modules `provides` | Dual classpath/module-path compatibility | ✅ |
| `SystemPropertiesConfigSource` (ordinal 400) | Reads `System.getProperty/getProperties` on each call (mutable runtime, no local cache) | ✅ |
| `EnvironmentVariablesConfigSource` (ordinal 300) | Spec §7.6 mapping: 3 forms tried (exact / non-alphanumeric→`_` / +UPPER). `toEnvFormat` helper extracted for testability | ✅ |
| `MicroprofilePropertiesConfigSource` (ordinal 100) | One instance per `META-INF/microprofile-config.properties` URL from `ClassLoader`, frozen snapshot | ✅ |
| `ConfigSourceProvider` SPI via ServiceLoader | `addDiscoveredSources` loads direct sources + providers | ✅ |
| `RavelConfigValue` record with metadata | §2.1.5 — `absent(name)` factory for missing keys | ✅ |
| Unit + integration tests | **80 green tests** across 8 classes + 1 `MapConfigSource` fixture | ✅ |

**M1 documented decisions**: `String` identity converter only (M2 will add primitives, automatic types,
implicit converters); no `synchronized`, no `ThreadLocal`, no `setAccessible` (clean audit); stable
ordinal cascade with tie-breaking on registration order.

**Deliverable:** `ConfigProvider.getConfig().getValue("key", String.class)` works with all 3 canonical
sources. Reactor + unit tests green.

---

### M2 — Type converters ✅

**Spec scope:** §5 (Converter), §5.1 (Built-in converters), §5.1.1 (Boolean), §5.2 (Automatic / Implicit
converters), §5.3 (Custom converters), §5.4 (Arrays).

| Task | Notes | Status |
|---|---|---|
| Strict built-ins (§5.1) | `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `float`/`Float`, `double`/`Double`, `short`/`Short`, `byte`/`Byte`, `char`/`Character`, `String`, `Class<?>` — all using the same converter for boxed/primitive | ✅ |
| Extended built-ins | `OptionalInt`, `OptionalLong`, `OptionalDouble`, `URI`, `URL`, `InetAddress`, `Duration`, `Period`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetTime`, `OffsetDateTime`, `ZonedDateTime`, `Instant` | ✅ |
| Enumerations | Via `ImplicitConverter` (`valueOf` pattern treated as a dedicated case for type safety) | ✅ |
| `Boolean` converter §5.1.1 | `true`/`1`/`yes`/`y`/`on` (case-insensitive, after `trim()`) → `true`; everything else → `false` | ✅ |
| Array conversion §5.4 | `ArraySplitter` + `ArrayConverter<T>` — `,` separator, `\,` escaping, empty segments ignored; `getValue(name, T[].class)` and `getValues(name, T.class)` (via default Config) | ✅ |
| `Converter<T>` SPI via ServiceLoader | `addDiscoveredConverters` loads + reads `@jakarta.annotation.Priority` (default **100**) | ✅ |
| Implicit converters §5.2 | Ordered detection: `static of(String)` > `valueOf(String)` > `parse(CharSequence)` > ctor `(String)`. **Strictly public**, no `setAccessible(true)` | ✅ |
| `ConfigBuilder.withConverter(Class<T>, int priority, Converter<T>)` | Explicit priority respected; a lower-priority converter does not override a higher one | ✅ |
| `ConfigBuilder.withConverters(Converter<?>...)` | Reads `@Priority` (default 100) via FQN reflection — no compile dependency on `jakarta.annotation` | ✅ |
| Thread-safe cache of derived converters | `ConcurrentHashMap` on `RavelConfig.derivedConverters`; no `synchronized`, virtual-thread-friendly | ✅ |
| Tests per converter + edge cases | Boolean truthy/falsy, numerics with spaces, char.length≠1, invalid URL/URI, unknown enum, array `\,` escaping, empty segments, incompatible type → `IllegalArgumentException` | ✅ |

**M2 documented decisions**:
- The MP §5.3 contract "default priority 100" is read **by qualified name** (`jakarta.annotation.Priority`
  or `javax.annotation.Priority`) avoiding any compile dependency on `jakarta.annotation` in
  `ravel-core` (test scope only, compliant with the "zero third-party" rule).
- Built-ins are registered at **priority 1**; any application converter (default priority 100)
  automatically overrides them.
- §5.2 patterns use **only** `public` methods/constructors — no `setAccessible(true)`, compliant
  with Java Modules / AOT-friendly constraints.
- `getOptionalValue(name, X[].class)` treats an empty string as "absent" (§2.1.4); `,,` or
  `\\` not followed by `,` are normalised by `ArraySplitter`.

**Deliverable:** `config.getValue("timeout", Duration.class)`, `config.getValue("hosts", String[].class)`,
`config.getValues("colors", Status.class)` and a custom `Converter<UUID>` annotated `@Priority(500)` work.
**131 green tests** (80 M1 + 51 M2) across 12 classes.

---

### M3 — Config Profiles + Property Expressions ✅

**Spec scope:** §7.5 (Configuration profile), §7.2 (Configuration property expression).

| Task | Notes | Status |
|---|---|---|
| Read `mp.config.profile` at `Config` build time | Value resolved before final build; profile applied via wrapped sources | ✅ |
| `%<profile>.` prefix on any source | `ProfiledConfigSource` wrapping that rewrites keys and inherits ordinal + 1 | ✅ |
| Cascaded profile activation | `mp.config.profile=dev` → `%dev.app.url` shadows `app.url` (ordinal +1) | ✅ |
| `${key}` expression (§7.2) | Recursive resolver on the raw value read from the `ConfigSource` | ✅ |
| `${key:default}` expression | Default value if the key is not found | ✅ |
| Nested `${${env}.url}` | Recursive resolver, depth tested | ✅ |
| `\$` escaping | No interpolation | ✅ |
| Cycle detection | Via `ScopedValue<Set<String>>` (not `ThreadLocal`), `IllegalArgumentException` thrown | ✅ |
| Disable via `mp.config.property.expressions.enabled=false` | Spec §7.2 section | ✅ |
| Tests: absent/present/multiple profile, direct cycle, indirect cycle, escaping | Exhaustive coverage in dedicated + integration tests | ✅ |

**M3 documented decisions**:
- Expression resolution happens on the raw value before typed conversion.
- `ConfigValue` preserves `rawValue` (original string) and exposes resolved `value`.
- The active profile is selected via the ordinal cascade at build time, then applied to all
  sources via wrapping.

**Deliverable:** `config.getValue("database.url", String.class)` switches to `%dev.database.url`
with `mp.config.profile=dev`; `${key}` / `${key:default}` / `${${env}.url}` expressions resolved;
cycles detected. **155 green tests** on `ravel-core`.

---

### M4 — CDI integration (`ravel-cdi-vauban`)

**Spec scope:** §6 (CDI integration), §6.1 (`@ConfigProperty`), §6.2 (`Config` injection), §6.3 (Optional injection).

| Task | Notes | Status |
|---|---|---|
| `ConfigCdiExtension` (Vauban Build Compatible Extension) | BCE added + validation of `@ConfigProperty` injection points (supported type, missing required property) | ✅ |
| `@Produces @ConfigProperty` producer generation per type | Single parametric producer via `InjectionPoint` (deduplication by design) | ✅ |
| `Optional<T>` support (§6.3) | If the property is absent → `Optional.empty()` | ✅ |
| `Provider<T>` / `Supplier<T>` support | Lookup on each `get()` (dynamic injection) | ✅ |
| `@Inject Config config` | Full `Config` injection via dedicated producer | ✅ |
| `@ConfigProperty(defaultValue=…)` | Default value converted with the target converter | ✅ |
| Deployment validation (§6.4) | BCE reports missing required injections (without `defaultValue`) | ✅ |
| Integration tests with Vauban container | CDI SE bootstrap smoke test + `Config` injection in a real container (`SeContainerInitializer`) | ✅ |
| No Java Modules `opens` on user beans | BCE + producers without reflection on application classes | ✅ |

**Deliverable (current increment):** `@Inject @ConfigProperty(name="app.name", defaultValue="vidocq") String name;`
+ `Optional<T>`/`Provider<T>`/`Supplier<T>` validated by module tests. **20 green tests** on
`ravel-cdi-vauban` (165 total with `ravel-core`).

---

### M5 — TCK + Bench ✅

**Scope:** official MicroProfile Config 3.1 validation + comparative benchmarks.

| Task | Notes | Status |
|---|---|---|
| `ravel-tck/pom.xml` Model 4.0.0 standalone | Same as `cassini-tck`/`foy-tck`/`champollion-tck` — out-of-reactor | ✅ |
| Arquillian runner + official `microprofile-config-tck:3.1.1` harness | Arquillian 1.10.1 (BOM + dep mgmt on `container-spi/impl-base/core-impl-base`) + Weld 6.0.2 + TestNG 7.10.2; JDK 25 `MalformedParameterizedTypeException` bug resolved | ✅ |
| Arquillian → Ravel embedded adapter | Weld SE embedded; `arquillian.xml` + `META-INF/beans.xml` (CDI 4.1) in place | ✅ |
| `run-official-tck-mp-config-3.1.sh` | Modes: smoke (default, 2/2 PASS) / all / `-Dtest=TestName`; report `target/tck-report.txt` | ✅ |
| BCE `@Validation` → `@Registration(types=Object.class)` | CDI Lite 4.1 §16.1 forbids `BeanInfo` in `@Validation`; commit `8d80958` | ✅ |
| `TCK.md` | Final status + J1/J2 journal of the 6 closed gaps (Optional*, FQN, arrays, `@ConfigProperties`, deployment validation, primitives/collections) | ✅ |
| **Contract score: 100% PASS** | **349 tests run / 349 PASS / 0 failures / 0 skipped** — achieved in J2 (commit `f5603bb`) | ✅ |
| Synthetic beans `@ConfigProperty` via `@Synthesis` BCE | One `SyntheticBean` per IP type collected at `@Registration`, qualified `@ConfigProperty`. For non-parameterised types (incl. arrays) the runtime `Class<?>` is used — Weld silently ignores lang-model `ArrayType` (WELD-001408); for parameterised types (`Provider<T>`, `Optional<T>`, `List<T>`, `Set<T>`) the lang-model `Type` is preserved | ✅ |
| `@ConfigProperties` support (MP Config 3.1 §6.4) | One `SyntheticBean` per BeanType (prefix `@Nonbinding`). Prefix resolution from IP (direct annotation + qualifiers for programmatic lookups `CDI.current().select(BeanX.class, ConfigProperties.Literal.of("foo"))`), class-level fallback. Deployment validation via `ConfigPropertiesExclusionExtension.validateConfigProperties` at `AfterDeploymentValidation` (Java initialisers detected by comparing to zero value after instantiation) | ✅ |
| Raw expressions / non-strict lookup (§7.2) | `getOptionalValue` returns `Optional.empty()` and `getConfigValue` returns a partial `ConfigValue` (raw preserved) on unresolved `${missing}` | ✅ |
| `Converter` returning `null` semantics (§5.3) | `getValue` throws `NoSuchElementException`; `getOptionalValue` returns `Optional.empty()` | ✅ |
| `ArrayConverter` primitives + `List<T>`/`Set<T>` (§5.4) | `Array.set` instead of `(T[])` cast (resolves `ClassCastException [I → [Ljava.lang.Object;` for `int[]`, `boolean[]`, etc.); `RavelConfigPropertyResolver.resolveCollection` detects `List<T>`/`Set<T>` at the injection point and delegates to the component type's array converter | ✅ |
| `ravel-bench` JMH | `LookupBenchmark` (cache hit/miss) + `ExpressionBenchmark` (literal/1/3 levels) + `ConversionBenchmark` (Integer/Long/Boolean/Duration/String[]); `@Param` Ravel/Smallrye | ✅ |
| JMH comparison vs Smallrye Config | Formal run published in `BENCH.md` (run #1, 2026-05-09) — Ravel outperforms Smallrye on conversions (`bool` −37%, `integer` −19%, `String[]` −27%), is close on simple lookups (~+8…30%), has a significant lag on resolved expressions (`oneLevel` 63×, `deep` 88× — caching gap documented) | ✅ baseline published |

**Deliverable:** MicroProfile Config 3.1 TCK **349/349 PASS** reproducible via
`./run-official-tck-mp-config-3.1.sh all` (report `ravel-tck/target/tck-report.txt`).
JMH baseline published in `BENCH.md` (reproduction command and raw results included).

---

### M6 — Vidocq ecosystem integration ✅

**Scope:** deploy Ravel in Cassini, Chappe, Vauban, and `vidocq`; replace
Smallrye Config as the default implementation. Documented in
[ADR-001](docs/adr/ADR-001-integration-ecosysteme-vidocq.md).

| Task | Status | Notes |
|---|---|---|
| Documentation [`docs/integration-cassini.md`](docs/integration-cassini.md) | ✅ | 198 lines — dependencies, Java Modules, `@Path` + `@ConfigProperty` example, profiles, `@ConfigProperties`, Ravel/Smallrye comparison |
| Documentation [`docs/integration-chappe.md`](docs/integration-chappe.md) | ✅ | 155 lines — programmatic usage without CDI (`ConfigProvider.getConfig()` for port/TLS/timeouts) |
| Documentation [`docs/integration-vauban.md`](docs/integration-vauban.md) | ✅ | 260 lines — injectable `Config` bean, auto-discoverable BCE via ServiceLoader, `@ConfigProperties` POJO |
| ADR-001 integration strategy | ✅ | "Drop-in" rationale + deployment order + risks |
| ServiceLoader BCE (`META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`) | ✅ | `ConfigCdiExtension` exposed via the standard CDI 4.1 contract |
| Portable extension ServiceLoader (`jakarta.enterprise.inject.spi.Extension`) | ✅ | `ConfigPropertiesExclusionExtension` exposed |
| `META-INF/vauban-beans.list` (strict Java Modules fallback) | ✅ | Complement to ServiceLoader for non-portable discovery paths |
| `module-info.java` `provides ... with` | ✅ | Java Modules mirror for both service files |
| `cassini-cdi-vauban` adapter: `ravel-cdi-vauban` as optional dependency | ✅ (Cassini side) | `<optional>true</optional>` — BCE auto-discovered if the JAR is on the classpath |
| `cassini-examples-vauban/ConfigDemoResource` example | ✅ (code written) | JAX-RS resource `@ApplicationScoped @Path("/config")` with 3 `@ConfigProperty` (greeting/version/Optional env) |
| `cassini-examples-vauban/ConfigDemoResourceTest` end-to-end test | ✅ | 2/2 PASS after rebuilding Cassini on corrected Vauban snapshot (VAU-BCE-001) |
| BCE Vauban test in `ravel-cdi-vauban` (`resolves_config_property_injection_through_bce_pipeline`) | ✅ | Re-enabled after fix VAU-BCE-001. Covers `@Registration` reading `BeanInfo.injectionPoints()`, `@Synthesis` synthesising a `SyntheticBean<String>` (scalar) + `SyntheticBean<Optional<String>>` (parameterised). ravel-cdi-vauban suite: 21/21 PASS, 0 skip |
| End-to-end bench Cassini + Ravel vs Cassini + Smallrye Config | ⏳ | To be triggered in a dedicated session |
| `vidocq`: integrate Ravel as MicroProfile Config 3.1 implementation | ✅ | `ravel-cdi-vauban` added to `vidocq-runtime-core`; `requires transitive io.vidocq.ravel.cdi.vauban` in module-info; `RavelConfigPropertyIntegrationTest` 1/1 PASS (String, Integer, Optional<String>) — all vidocq apps can use `@ConfigProperty` without additional dependencies |

**Deliverable:** complete documentation, discovery artifacts (ServiceLoader +
Java Modules + `vauban-beans.list`) packaged, Cassini integration operational
(`ConfigDemoResourceTest` 2/2 PASS), Ravel swap in vidocq validated
(`RavelConfigPropertyIntegrationTest` 1/1 PASS). Spec conformance via TCK
349/349 PASS (Weld); `vauban-core ↔ ravel-cdi-vauban` bridge unblocked by
`fix/vau-bce-001-bce-not-invoked` (see `vauban/BUG.md#VAU-BCE-001`).

#### M6 Residual — Vauban BCE integration bug ✅ resolved (VAU-BCE-001)

Initially diagnosed as "the `@Registration` / `@Synthesis` phases are not invoked"
(evidence: silent println instrumentation). Detailed investigation on the `vauban-core`
side (branch `fix/vau-bce-001-bce-not-invoked`):
**the phases were correctly dispatched by `BceProcessor`**; six cumulative defects
in the downstream pipeline silently degraded the result — hence the apparent
dropout of the extension:

1. `VaubanBceBeanInfo.injectionPoints()` returned `List.of()` hard-coded;
2. `VaubanAnnotationInfo` did not override `name()` (default API → `declaration()` → crash on classes outside the index, e.g. `@ConfigProperty` living in `microprofile-config-api`);
3. `VaubanClassType.declaration()` crashed on any JDK / third-party type absent from the scan;
4. `VaubanSyntheticBeanBuilder.type(Type)` was a no-op (`return this; // simplified`) — `Optional<T>`, `List<T>`, `Provider<T>` silently dropped;
5. `VaubanTypes.ofClass(String)` returned `null` for out-of-index types → downstream NPE in `types.parameterized(...)`;
6. `BceProcessor.toBeanDescriptor` lost qualifier members (`Map.of()`) — `@Tagged("scalar")` no longer matched the same IP.

Details and regression test in `vauban/BUG.md#VAU-BCE-001`. Measured effect:
- vauban-core: 269/269 PASS (266 baseline + 3 new tests targeting the 6 defects);
- ravel-cdi-vauban: 21/21 PASS, 0 skip (vs 20/0/1 before).

---

## Priority Order — Rationale

1. **M1 (sources + lookup)** first because everything else depends on it: no converter without
   a raw value, no profile without a source. The essential minimum of the spec fits in this milestone.
2. **M2 (converters)** before M3 (profiles/expressions): profiles and expressions operate on the
   *raw value* before conversion; conversion is the leaf layer. Testing M3 without M2 would force
   everything through `String`, masking conversion bugs.
3. **M4 (CDI)** after M1+M2+M3: CDI integration is an *adapter* on the complete `Config`. Until
   `Config` is spec-compliant, CDI injection cannot be validated.
4. **M5 (TCK)** is a continuous activity from M2/M3 (the first TCK tests must already pass for
   covered sections), but the "100% PASS" objective only becomes a contract at the end of M5.
5. **M6 (integration)** comes last: Cassini / Chappe / Vauban will not be polluted until
   Ravel is solid. The swap will be done behind a dedicated PR per client project.

## Known Risks

| Risk | Mitigation |
|---|---|
| Env var mapping (§7.6) with complex fallback rules (3 forms tried in order) | Exhaustive tests from M1 on the matrix (`MY_VAR` / `my.var` / `my-var`); TCK covers these cases |
| Expression cycles (`${a}` → `${b}` → `${a}`) | `ScopedValue<Set<String>>` read-thread-safe; no `ThreadLocal` that would pin virtual threads |
| Automatic (implicit) conversion with `setAccessible` required on private or package-private `valueOf`/`parse` | Only allow `public static` methods; otherwise document the open module; no global `addOpens` |
| TCK Arquillian: containerless vs embedded Vauban | Two Maven profiles in `ravel-tck`; `@Tag("cdi")` tests excluded in standalone mode |
| Profiles + expressions interaction | Combinatorics explicitly tested: `%dev.url=${base}/dev` must resolve `${base}` *after* profile selection |
| GraalVM AOT compatibility | Test `native-image` on a `ravel-examples` example from M3; implicit converters via reflection are the sensitive point |
| ServiceLoader + Java Modules in `ravel-cdi-vauban` | Verify that CDI `@Provides` does not require `opens` on user modules |

## Actioned Decisions

- ✅ **Jakarta / MicroProfile specs allowed**: `microprofile-config-api`, `jakarta.cdi-api`,
  `jakarta.inject-api`, `jakarta.annotation-api`. No Smallrye / Helidon / Apache Commons Config.
- ✅ **`ravel-core` standalone SE**: usable without CDI, without Servlet, without Vauban.
- ✅ **`ravel-cdi-vauban` separate**: optional module, not loaded if CDI is absent.
- ✅ **Strict TDD** on all production modules.
- ✅ **TCK 100% PASS** as a hard contract.
- ✅ **TCK out-of-reactor** (POM Model 4.0.0 standalone) — ShrinkWrap Maven Resolver 3.3 constraint.
- ✅ **Cycle detection via `ScopedValue`** (not `ThreadLocal`) — virtual-thread-friendly.

## Open Decisions

- [ ] Should a "watch" mode be exposed for file sources (`microprofile-config.properties` reloaded
      via `WatchService`)? → Out of MP Config 3.1 spec, defer to M6+ as an extension.
- [ ] `ConfigProperty` on a user `@Produces` method — supported by the spec? → Verify §6 before M4.
- [ ] Strategy for `Provider<T>` / `Supplier<T>`: lookup on each `get()` or configurable TTL cache?
      → Lookup on each `get()` by default (spec compliance); TTL cache as a future option.
- [ ] Expression resolver: tolerate forward references (`${b}` defined after `${a}`)? → Yes, no
      declarative ordering, lazy resolution at read time.
- [ ] Future MicroProfile Config 4.0 integration (when released) — design-out to facilitate a
      version bump without deep refactoring.
