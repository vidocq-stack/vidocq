
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
    init (same-module JPMS resource, no opens). No longer compile-time-inlineable, which
    also protects future consumers from the javac inlining trap.

## BUG-20260713-01 — MP Config TCK gaps when running on the Vauban CDI runtime (vs Weld)

- **Date** : 2026-07-13
- **Statut** : OPEN
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
