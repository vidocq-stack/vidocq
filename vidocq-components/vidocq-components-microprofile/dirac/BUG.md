# BUG — Dirac

Tracking reproducible bugs in `dirac` (internal issues, regressions, incorrect behaviors
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, cause hypothesis, status.

---

## DRC-001 — JPMS bypassed via manual copy of compile-scope JARs

- **Opening date**: 2026-05-25
- **Status**: ⚠️ OPEN — active workaround

### Symptom

The dirac root `pom.xml` uses `maven-dependency-plugin` (`initialize` phase) to
copy all compile-scope JARs to `target/javamodules/`, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

This workaround indicates that Maven's native JPMS resolution doesn't work for
some of dirac's compile-scope dependencies, notably `microprofile-metrics-api` and
`vauban-core`/`vauban-classloader-spi`.

### Minimal repro

```bash
grep -n "javamodules\|module-path" dirac/pom.xml
# reveals the two manually configured plugins
```

Without the workaround (removing the `maven-dependency-plugin` config), `javac` fails with:

```
error: module not found: org.eclipse.microprofile.metrics
```

### Cause hypothesis

The concerned JARs don't have their own `module-info.class` — they only expose an
`Automatic-Module-Name` in their `MANIFEST.MF`. Version 4.x of `maven-compiler-plugin`
doesn't automatically place them on the `--module-path` for projects having an
explicit `module-info.java`. The copy to `target/javamodules/` allows javac to
resolve them as automatic modules by deriving their name from the JAR file name.

### Resolution path

1. Check if upstream versions of `microprofile-metrics-api` (3.x → 4.x?) publish
   a `module-info.class`. If yes, bump the version and remove the workaround.
2. Contact / PR upstream Eclipse MicroProfile to add a modular descriptor.
3. Failing that, wrap via an internal Dirac module (`dirac-mp-metrics-api`) that provides
   the missing `module-info.class` — pattern already used for `ravel-mp-config-api`.

---

## DRC-002 — @Gauge resolution + BASE registry fail on the module path (strict JPMS)

- **Opening date**: 2026-06-02
- **Status**: ✅ FIXED 2026-06-02

### Symptom

On a strict module-path deployment (e.g. the Vidocq runtime / Arago Docker image), boot or the
first `/metrics` scrape fails. Two distinct failures:

1. `DiracException: Unable to resolve @Gauge method handle for 'public long App.activeRooms()'`
   (cause `IllegalAccessException`) — application `@Gauge` beans cannot be wired.
2. `DeploymentException: Failed to create client proxy for ... MetricRegistryProducerBean`
   → `IllegalAccessError: class io.vidocq.dirac.internal.BaseMetricsRegistrar cannot access
   ManagementFactory because module io.vidocq.dirac.core does not read module java.management`.

Neither reproduces on the class-path (unnamed module reads everything / is open), so unit tests
and the Arquillian TCK (which run class-path) stay green — the bug only bites under strict JPMS.

### Minimal repro

Deploy a Dirac-enabled app on the module path with an application `@Gauge` bean in another module.

### Cause

1. `DiracExtension.resolveMethodHandle` did `MethodHandles.privateLookupIn(beanClass, lookup())`.
   `privateLookupIn` requires the caller (Dirac) module to **read** the bean's module; the app opens
   its package for reflection but Dirac does not read the app module, so the lookup is denied.
2. `dirac-core` uses `java.lang.management.ManagementFactory` (BASE JVM metrics) without
   `requires java.management`, and `dirac-cdi-vauban` exported but did not **open** its
   `io.vidocq.dirac.cdi.internal` package to `io.vidocq.vauban.core` (vauban instantiates the BCE +
   producer/interceptor beans by deep reflection — `exports` is not enough).

### Fix

- `DiracExtension.resolveMethodHandle`: add the readability edge
  `DiracExtension.class.getModule().addReads(beanClass.getModule())` before `privateLookupIn`
  (no-op on the class-path / unnamed modules).
- `dirac-core/module-info`: `requires java.management;`.
- `dirac-cdi-vauban/module-info`: `opens io.vidocq.dirac.cdi.internal to io.vidocq.vauban.core;`
  (qualified — internal package stays unexported as API; mirrors `knock-cdi-vauban`).

Verified: dirac unit tests + **MP-Metrics 5.1 TCK 127/127 PASS**; Arago Docker `/metrics` 200 with
`arago_active_rooms` exposed.

## BUG-20260712-01 — Plain `@Inject MetricRegistry` and `@Inject @Metric Gauge` unresolvable via CDI

- **Date** : 2026-07-12
- **Statut** : FIXED (branch pr/ybl/metrics-default-registry-injection)
- **Module touché** : dirac-cdi-vauban (`MetricRegistryProducerBean`)
- **Symptôme** : two spec-mandated injection shapes failed with
  `UnsatisfiedResolutionException` when resolved through the real CDI container:
  (1) plain `@Inject MetricRegistry` (no qualifier) — the only producer carried the
  `@RegistryScope` qualifier, so `@Default` injection points had no candidate;
  (2) `@Inject @Metric(...) Gauge<T>` — no Gauge producer existed at all.
  Both were masked in dirac-tck and in the runtime TCK runner of vidocq PR #19 by a
  custom `DiracTestEnricher` that bypassed CDI and read the Dirac registry directly
  via reflection. Only the assembled-runtime runner (generic CDI enricher) exposed them.
- **Reproduction minimale** :
  ```
  cd vidocq && ./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-dirac-metrics test
  # -> 127 errors "No bean found for type MetricRegistry", then Gauge failures
  ```
- **Hypothèse de cause** : producers designed for the TCK-harness path, never exercised
  through standard CDI resolution.
- **Investigations** :
  - 2026-07-12 : `produceByScope` now also carries an explicit `@Default`
    (spec: plain injection = application scope); new lazy-forwarding
    `<T extends Number> Gauge<T>` producer resolves the gauge from the registry at
    `getValue()` time (registration order independent). Unit tests added
    (`MetricRegistryDefaultInjectionCdiIntegrationTest`), per-brick TCK still 127/127
    PASS, runtime TCK runner 127/127 PASS.
