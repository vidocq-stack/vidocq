# Ravel — MicroProfile Config 3.1 TCK

## Current Status — M5

| Item | Status |
|---|---|
| `ravel-tck` module (POM Model 4.0.0 out-of-reactor) | ✅ |
| Maven profile `tck-official` with `microprofile-config-tck:3.1.1` | ✅ |
| Surefire TestNG provider forced via plugin dependency `surefire-testng:3.5.5` | ✅ |
| Arquillian Weld embedded container 4.0.0 + Weld 6.0.2 (CDI 4.1) | ✅ |
| `arquillian-container-spi/impl-base/core-impl-base` pinned to 1.10.1 (vs transitive 1.8.0) | ✅ |
| Smoke test `RavelTckSmokeTest` (JUnit 6, outside Arquillian) | ✅ 2/2 PASS |
| Script `run-official-tck-mp-config-3.1.sh` (smoke / all / `-Dtest=...`) | ✅ |
| Arquillian bootstrap under JDK 25 | ✅ **resolved (bump to 1.10.1 + dep mgmt)** |
| Official TCK discovery (`dependenciesToScan`) | ✅ |
| Current TCK score | **349 PASS / 0 FAIL / 0 SKIP** out of 349 tests |
| 100% PASS | ✅ **achieved** — see J2 below |

## J2 — Closing remaining failures → 100% PASS

After J1 (synthetic beans), 40 failures / 160 skips remained. The fixes delivered
in this session closed the following six gaps to reach **349/349 PASS**:

1. **OptionalInt / OptionalLong / OptionalDouble** (CDIPropertyExpressionsTest,
   CdiOptionalInjectionTest): deployment validation treated these types
   as required. They are now explicitly recognised as "absent-friendly wrappers"
   in `validateDeploymentContract`.
2. **Default FQN property name** (CDIPlainInjectionTest): the BCE resolved
   the default name as `field.name()` instead of
   `<canonicalName(declaringClass)>.<member>` required by §6.1. Fixed in
   `ConfigCdiExtension.resolvePropertyName` (with `canonicalize` replacing
   `$` with `.`).
3. **Synthetic beans for array types** (ArrayConverterTest, ClassConverterTest):
   `addBean(Object.class).type(arrayLangModelType)` was silently ignored
   by Weld (WELD-001408). The runtime `Class<?>` is now used directly for
   non-parameterised types; parameterised types (`Provider<T>`,
   `Optional<T>`, `List<T>`, etc.) retain the lang-model `Type` to preserve
   their generic parameter.
4. **`@ConfigProperties` synthetic creator + prefix resolution** (ConfigPropertiesTest):
   - Refactored: a single `SyntheticBean` per `BeanType` qualified `@ConfigProperties`
     (`prefix` is `@Nonbinding`, so multiple prefixes cannot coexist on the same type).
   - The creator resolves the effective prefix from the `InjectionPoint` (annotation
     directly on the field + qualifiers for programmatic lookups via
     `CDI.current().select(BeanX.class, ConfigProperties.Literal.of("foo"))`),
     falling back to `@ConfigProperties` at class level then empty string.
5. **`@ConfigProperties` deployment validation** (ConfigPropertiesMissingPropertyInjectionTest):
   `ConfigPropertiesExclusionExtension` collects `@ConfigProperties`-annotated type-level
   classes during `ProcessAnnotatedType` and raises `addDeploymentProblem(...)` at
   `AfterDeploymentValidation` for any missing required property. Fields with
   `@ConfigProperty(defaultValue=...)`, `Optional[Int|Long|Double]`, or a Java
   initialiser (`int port = 9080;`, detected by comparing to the zero value
   after instantiation) are exempt.
6. **`ArrayConverter` for primitives + `List<T>`/`Set<T>`** (ArrayConverterTest, etc.):
   - `(T[]) Array.newInstance(int.class, n)` throws `ClassCastException [I → [Ljava.lang.Object;`.
     Refactored to a generic `Converter<T>` that returns `Object` (cast at call site)
     — supports `int[]`, `boolean[]`, `Boolean[]`, etc. uniformly.
   - `RavelConfigPropertyResolver.resolveCollection(...)` detects
     `List<T>`/`Set<T>` at the injection point, delegates to the array converter of the
     component type for splitting + conversion, and returns an
     `ArrayList`/`LinkedHashSet`.

Progressive score during J2:

| Step | PASS | FAIL | SKIP |
|---|---|---|---|
| Before J2 | 169 | 40 | 160 |
| After Optional* + FQN fix | 191 | 7 | 165 |
| After `@ConfigProperties` (creator + missing) fix | 353 | 2 | 143 |
| After primitive array + `List<T>`/`Set<T>` fix | **349** | **0** | **0** |

## J1 — Synthetic beans `@ConfigProperty` (delivered)

Root cause of the initial blockage (332 SKIP): the CDI producer `@Produces @ConfigProperty Object`
was not bundled in the TCK ShrinkWrap archives and even if it had been, Weld only matches an
`Object` producer with injection points typed exactly `Object`.

**Fix delivered**:

- `ConfigCdiExtension` adds a `@Synthesis` phase that registers, for each `@ConfigProperty` IP type
  collected during `@Registration`, a `SyntheticBean` with the creator
  `ConfigPropertySyntheticCreator` (see `ravel-cdi-vauban/src/main/java/io/vidocq/ravel/cdi`).
- A synthetic `Config @Default` bean is also registered for `@Inject Config` in the TCK
  (the ShrinkWrap archive does not bundle `RavelConfigProducer`).
- Primitive types are auto-boxed (`int → Integer`, etc.) to avoid `WELD-001409`.
- `@ConfigProperty` resolution was extracted into `RavelConfigPropertyResolver`
  (package `io.vidocq.ravel.cdi.internal`) consumed by both the legacy producer and the
  synthetic creator.
- Specific support for `ConfigValue`, `OptionalInt/Long/Double`, `Optional<T>`, `Provider<T>`,
  `Supplier<T>`.
- `RavelConfig implements Serializable` via `writeReplace` → proxy that delegates to
  `ConfigProvider.getConfig()` at `readResolve` (test `testInjectedConfigSerializable`).
- §7.2 semantics: `${absent}` without a default → absent property (`Optional.empty()` /
  `NoSuchElementException`) instead of `IllegalArgumentException`. Expression cycles
  still throw `IllegalArgumentException`.
- §5.3 semantics: `Converter` that returns `null` on non-null input → `NullPointerException`
  from `getValue` (instead of `NoSuchElementException`).
- Environment variable `config_ordinal=45` injected via Surefire `<environmentVariables>`
  for `DefaultConfigSourceOrdinalTest`.

**Critical version alignment** (required to prevent BCE @Synthesis from crashing):

- `weld-lite-extension-translator:6.0.1.Final` (forced vs transitive `6.0.0.Alpha1` which
  calls `AfterBeanDiscoveryImpl.addBean(Class)` removed in Weld 6.0.2).
- `jakarta.enterprise.cdi-api:4.1.0` final (forced vs transitive `4.1.0-M1` which does not
  contain `InvokerFactory`).
- `jakarta.enterprise.lang-model:4.1.0` aligned.

**Measured progress**:

| Step | PASS | FAIL | SKIP |
|---|---|---|---|
| Before J1 | 25 | 34 | 332 |
| After J1 (synthetic beans) | 162 | 40 | 173 |
| After semantics + Config bean fixes | **169** | 40 | 160 |

## How to Run

```bash
./run-official-tck-mp-config-3.1.sh           # smoke (2 JUnit tests, verifies ServiceLoader)
./run-official-tck-mp-config-3.1.sh all       # full official TCK suite
./run-official-tck-mp-config-3.1.sh -Dtest=ConfigProviderTest
```

The script:
1. Installs `ravel-api` / `ravel-core` / `ravel-cdi-vauban` locally via `./mvnw install -DskipTests`;
2. Invokes `./mvnw -f ravel-tck/pom.xml -P<profile> test` (the wrapper, not system `mvn`, to
   guarantee Maven 3.9.16);
3. Produces a summary report in `ravel-tck/target/tck-report.txt`.

## Bug #1 — `MalformedParameterizedTypeException` ✅ resolved

Symptom observed under JDK 25:

```
Caused by: java.lang.reflect.MalformedParameterizedTypeException:
    Mismatch of count of formal and actual type arguments in constructor of
    org.jboss.arquillian.container.spi.Container:
    0 formal argument(s) 1 actual argument(s)
```

### Root Cause

`arquillian-weld-embedded:4.0.0.Final` transitively pulls
`arquillian-container-spi:1.8.0.Final` in which the `Container` interface
is **not** generic. Under JDK 23+ the strict validation of `ParameterizedTypeImpl`
rejects the mismatch and throws `MalformedParameterizedTypeException`.

Starting from `1.10.1.Final`, the interface is correctly declared
`Container<T extends ContainerConfiguration>`:

```text
$ javap -p arquillian-container-spi-1.10.1.Final.jar (Container.class)
public interface org.jboss.arquillian.container.spi.Container<T extends ContainerConfiguration>
```

### Applied Fix

`<dependencyManagement>` in `ravel-tck/pom.xml` explicitly pins the trio:

- `arquillian-container-spi:1.10.1.Final`
- `arquillian-container-impl-base:1.10.1.Final`
- `arquillian-core-impl-base:1.10.1.Final`

(The Arquillian BOM alone is not sufficient because Maven 4 applies "nearest wins"
to transitives not managed by the imported BOM from the profile.)

## Bug #2 — `LITE-EXTENSION-TRANSLATOR-000002` ✅ resolved

CDI Lite 4.1 (BCE spec §16.1) forbids `BeanInfo` as a parameter of a `@Validation` method.
`ConfigCdiExtension.validateConfigPropertyInjectionPoints` was migrated from `@Validation`
to `@Registration(types = Object.class)`. This phase is invoked for each `BeanInfo` (all beans
extend `Object`) and legitimately accepts `(BeanInfo, Messages)`.

Immediate effect: TCK went from **0 tests actually executed** (all classes failed at
`arquillianBeforeClass`) to **391 tests run / 357 PASS**.

## Bug #3 — Synthetic beans `@ConfigProperty` ✅ resolved

Detailed in the J1 section above. The BCE now exposes
`@Registration` (collecting `@ConfigProperty` IP types) +
`@Synthesis` (one `SyntheticBean` per type, plus one `Config @Default` bean).

Still exposed after this fix: `@ConfigProperties` (Bug #4 below) and a
residual listed at the end of the doc.

## Bug #4 — `@ConfigProperties` not supported (6 failures) 🔥 active

`ConfigPropertiesTest.testConfigPropertiesPlainInjection` and 5 others:
MP Config 3.1 §6 introduces the `@ConfigProperties` annotation (prefix on an
entire POJO, distinct from `@ConfigProperty`). Ravel does not implement this
yet — known gap, planned for J3.

## Residual After J1 — history (resolved in J2, 0 failures remaining)

> These items were the action plan for J2. All resolved; retained for traceability.

| Category | # | Status |
|---|---|---|
| `ArrayConverterTest` (various arrays, primitives) | ~1 cls | ✅ J2.3 + J2.6 |
| `ClassConverterTest` (`Class[]`) | 1 cls | ✅ J2.3 |
| `CDIPlainInjectionTest.canInjectDefaultPropertyPath` | 1 | ✅ J2.2 |
| `ConfigPropertiesTest.*` (6 tests) | 6 | ✅ J2.4 |
| `ConfigPropertiesMissingPropertyInjectionTest` | 1 | ✅ J2.5 |
| `CDIPropertyExpressionsTest.badExpansion`, `CdiOptionalInjectionTest` | 2 | ✅ J2.1 |

## Disabled Tests / Spec Challenges

None at this time. This section will be enriched when genuine spec/impl
divergences emerge after successive M5 fixes.

## References

- MicroProfile Config 3.1 spec: `https://microprofile.io/specifications/microprofile-config/3.1/`
- TCK artifact: `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1`
- Arquillian core: `https://github.com/arquillian/arquillian-core`
- Weld SE: `https://docs.jboss.org/weld/reference/latest/en-US/html/environments.html#weld-se`
- CDI Lite spec — Build Compatible Extensions: §`jakarta.enterprise.inject.build.compatible.spi`
