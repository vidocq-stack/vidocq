
## BUG-20260712-01 — hardcoded implementation version constant in the published api artifact

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : Ravel.IMPLEMENTATION_VERSION (ravel-api/Ravel.java)
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports a hardcoded
  "0.1.0-SNAPSHOT" implementation version — the constant was maintained by hand and never
  updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** : read the constant from the published 0.2.0 jar.
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3 follow-up.
    Fixed: version.properties filtered by Maven next to the class, constant loaded at class
    init (same-module Java Modules resource, no opens). No longer compile-time-inlineable, which
    also protects future consumers from the javac inlining trap.

## BUG-20260713-01 — MP Config TCK gaps when running on the Vauban CDI runtime (vs Weld)

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13 — ravel-cdi-vauban + Vauban fixes, runner migrated, 349/349 on the assembled runtime)
- **Module touché** : ravel-cdi-vauban (+ possibly vauban resolution rules)
- **Symptôme** : migrating the vidocq runtime TCK runner
  (`vidocq-runtime-tck-ravel-config`) from Weld SE embedded to the assembled Vidocq
  runtime (shared `vidocq-runtime-arquillian` container) runs 375 tests with 16
  failures in 4 families:
  1. array-typed `@ConfigProperty` injection unsatisfied (`Boolean[]`, `Class[]` —
     ArrayConverterTest, ClassConverterTest): no producer covers CDI array types;
  2. `@ConfigProperties` bean resolution ambiguous (ConfigPropertiesTest) and the
     negative test `ConfigPropertiesMissingPropertyInjectionTest` does NOT fail
     deployment as the spec requires;
  3. `CDIPropertyExpressionsTest`: deployment validation reports
     "Missing required config property 'my.prop'" although the property is defined
     via a config expression in the deployment — validation-time Config lookup does
     not see the deployment's expression-resolved values;
  4. `@Inject Config`/`@ConfigProperty` enrichment ambiguous (2 beans match) on
     7 test classes — the Config producer appears registered twice when the
     ConfigCdiExtension runs through the runtime BCE path;
  plus config-profile tests (ConfigPropertyFileProfileTest, OverrideConfigProfileTest)
  and DefaultConfigSourceOrdinalTest.checkSetup (system property visibility timing).
- **Reproduction minimale** :
  ```
  # rewrite vidocq-runtime-tck-ravel-config on the knock-health model
  # (vidocq-runtime-core + vidocq-runtime-ravel-config-extension +
  #  vidocq-runtime-arquillian, drop Weld) then:
  cd vidocq && ./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-ravel-config test
  # -> 375 run / 16 failures (2026-07-13, full log kept by the campaign notes)
  ```
- **Hypothèse de cause** : ravel's CDI wiring was only ever exercised under Weld
  (weld-lite-extension-translator); the Vauban path (BCE + _VaubanComponents)
  lacks the array-type producers and duplicates the Config producer registration.
- **Investigations** :
  - 2026-07-13 : campaign "TCK runners on the assembled runtime" — 7 of the 8
    runtime TCK runners migrated to the shared Vidocq container (28+127+168+463+
    344+206+85 all green); this runner intentionally left on Weld until the four
    families above are fixed. The Weld run still certifies ravel-core and the
    portable ConfigCdiExtension (349/349).
  - 2026-07-13 (fix) : all four families resolved — **349/349 on the assembled
    Vidocq runtime** (zero skip), Weld per-brick run still 349/349. Root causes
    split between ravel and Vauban:
    1. arrays — Vauban recorded synthetic bean types given as runtime array
       classes as flat ClassType (`[Ljava.lang.Boolean;`), unmatchable against
       ArrayType injection points (vauban VAU-BCE-004);
    2. `@ConfigProperties` — the Weld-only portable exclusion extension never
       runs on CDI Lite: ConfigCdiExtension now vetoes type-level
       `@ConfigProperties` classes itself (`@Enhancement` adds `@Vetoed`) and
       re-validates required fields in `@Validation` (covers the broken-deployment
       TCK tests); programmatic lookups needed Vauban to stop dropping
       `select(type, qualifiers...)` qualifiers and to expose a synthetic
       InjectionPoint to synthetic bean creators;
    3. expressions/profile/ordinal — the Vauban build-time composite discovery
       loader did not expose `getResources` of the deployment loader, so the
       MicroprofilePropertiesConfigSource (and ServiceLoader ConfigSources) were
       invisible during BCE validation; fixed in Vauban, and the Arquillian
       container's system-property surfacing hack (which clobbered
       `config_ordinal` and broke `%dev.` profiles) was removed from vidocq;
    4. duplicate Config bean — ConfigCdiExtension now skips its fallback
       `@Default Config` synthetic bean when a Config-typed bean (the producer
       from the jar's APT bean index) is already registered; Vauban also
       validates observer-method non-event parameters as real injection points
       (MissingValueOnObserverMethodInjectionTest).

## BUG-20260827-01 — separator-only values (`","`, `",,"`) are not treated as absent for array/list lookups

- **Date** : 2026-08-27
- **Statut** : FIXED (branch pr/ybl/config-empty-values — ArrayConverter returns null for zero elements; ravel-tck 378/378 on 2026-08-27)
- **Module touché** : ravel-core `RavelConfig.getValue` / `getOptionalValue` / `getValues` / `getOptionalValues` (array and collection conversion path)
- **Symptôme** : MP Config TCK 3.1.1 `emptyvalue.EmptyValuesTestProgrammaticLookup` — 4 failures
  out of 29 (`testCommaStringGetValueArray`, `testDoubleCommaStringGetValueArray` expect
  `NoSuchElementException`; `testCommaStringGetOptionalValue`,
  `testDoubleCommaStringGetOptionalValues` expect an empty `Optional`). A property whose raw
  value is only separators (`my.prop=,` or `my.prop=,,`) yields zero elements once split; the
  spec (§ Empty values / converters for arrays) requires such a value to be considered
  **missing** for array/list/set lookups, exactly like `""`. Ravel currently returns a present,
  empty array / list.
- **Reproduction minimale** :
  ```
  cd vidocq && ./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-ravel-config test \
    -Dtest=EmptyValuesTestProgrammaticLookup -Dsurefire.failIfNoSpecifiedTests=false
  # 29 run, 4 failures. Same suite passes for "" (empty) and "foo," / ",bar" (trailing/leading separator).
  ```
- **Hypothèse de cause** : the array/collection branch of the lookup only maps `""` to
  "absent" (before splitting); after splitting on unescaped commas an all-empty element list
  must also be mapped to "absent" (throw `NoSuchElementException` in `getValue(s)`, return
  `Optional.empty()` in `getOptionalValue(s)`).
- **Investigations** :
  - 2026-08-27 : discovered during the MP 7.1 conformance audit. The suite had **never run**:
    both `ravel-tck/pom.xml` and the vidocq runner restrict surefire to `**/*Test.class`,
    `**/*Tests.class`, `**/*IT.class`, which silently drops
    `EmptyValuesTestProgrammaticLookup` (28 tests) and `TestCustomConfigProfile` (1 test, passes).
    The documented 349/349 is therefore 349 out of 378 — WildFly 38 runs 378 on the same TCK.
    Fix both include lists (add `**/EmptyValuesTestProgrammaticLookup.class` and
    `**/TestCustomConfigProfile.class`, or switch to `**/*.class` with TestNG's own
    `@Test` filtering) together with the code fix so the regression stays visible.

## BUG-20261001-01 — `@Inject @ConfigProperty` does not compile, then does not start, under the Vauban processor (ravel#21)

- **Date** : 2026-10-01
- **Statut** : FIXED (branch `fix/config-values-checked-at-container-start`, with vauban `fix/extension-build-time-signal` and vidocq `fix/ravel-config-codegen-bundle`)
- **Module touché** : ravel-cdi-vauban (`ConfigCdiExtension`)
- **Symptôme** : reported by Sébastien Blanc on 0.3.0 (Vidocq/ravel#21). A plain
  `@Inject @ConfigProperty(name = "shop.name") String` fails the compilation with
  "Unsatisfied dependency"; the only way out was `-Avauban.validation=false`. Peeled layer by layer:
  1. 0.3.0 only: Vauban did not see `@ConfigProperty` as a qualifier at compile time — fixed on main
     by vauban#70 (BUG-20260914-13 there).
  2. Ravel's extension never ran in the compiler: no artifact put it on the processor path
     (fixed in vidocq: `vidocq-runtime-ravel-config-extension-codegen`).
  3. Once it ran there, it checked the *values* against the build machine:
     `Missing required config property 'shop.name'`, although the value lives in the deployment
     (`vidocq.properties`, the environment).
  4. It also synthesised the fallback `@Default Config` bean, because the compilation does not see
     `RavelConfigProducer`; frozen into the application, that bean made every `@Inject Config`
     ambiguous at start.
- **Reproduction minimale** : an `@ApplicationScoped` bean with the field above, compiled with
  `ravel-cdi-vauban` on the processor path (vidocq `vidocq-runtime-it-ravel-config`).
- **Correction** : the extension asks Vauban whether it runs at build time
  (`ExtensionPhase.isBuildTime()`); there it registers the injection points and their beans but
  leaves the value checks (`@ConfigProperty` and `@ConfigProperties`) and the fallback `Config`
  bean to the container start, which runs it again. Pinned by `ConfigCdiExtensionTest`
  (`*_at_build_time`); `VaubanContainerIntegrationTest` still proves a missing key fails the start.
