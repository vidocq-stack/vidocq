# BUG — Dirac

Tracking reproducible bugs in `dirac` (internal issues, regressions, incorrect behaviors
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, cause hypothesis, status.

---

## DRC-001 — Java Modules bypassed via manual copy of compile-scope JARs

- **Opening date**: 2026-05-25
- **Status**: ✅ FIXED 2026-10-07 — workaround removed (Vidocq/vidocq-parent#13)

### Symptom

The dirac root `pom.xml` uses `maven-dependency-plugin` (`initialize` phase) to
copy all compile-scope JARs to `target/javamodules/`, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

This workaround indicates that Maven's native Java Modules resolution doesn't work for
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

### Resolution (2026-10-07)
The failure no longer reproduces on main: with the `target/javamodules` copy and the `--module-path` arguments
removed, `clean verify` passes with the same tests (105) and every produced jar (10) keeps the same module
descriptor. Most likely the failure dated from the Maven 4 RC / compiler-plugin 4.0.0-beta era (it does not come
back with compiler plugin 3.13 either). Workaround removed; the shared execution in vidocq-parent goes next
(Vidocq/vidocq-parent#13).

## DRC-002 — @Gauge resolution + BASE registry fail on the module path (strict Java Modules)

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
and the Arquillian TCK (which run class-path) stay green — the bug only bites under strict Java Modules.

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

## DRC-003 — Interceptors and `/metrics` not discovered by Weld SE (dirac#23)

- **Opening date**: 2026-10-09
- **Status**: ✅ FIXED 2026-10-09

### Symptom

Under a CDI container other than Vauban that does not scan implicit bean archives (Weld SE by
default), `@Counted` and `@Timed` methods are never intercepted, `@Inject MetricRegistry` is
unsatisfied, and the `GET /metrics` resource is not a bean: metrics silently stay empty.

### Minimal repro

`jar tf dirac-cdi-vauban-*.jar | grep beans.xml` and the same for `dirac-rest`: no
`META-INF/beans.xml`, so both jars are only implicit bean archives.

### Cause

Every test and the TCK run on Vauban, which is told about Dirac's beans through its build-time
index, so the missing `beans.xml` never showed.

### Fix

`dirac-cdi-vauban` and `dirac-rest` ship `META-INF/beans.xml` (`bean-discovery-mode="annotated"`).
`BeanArchiveTest` in each module pins it; both fail on `main` (`missing
target/classes/META-INF/beans.xml`). The Weld SE and Open Liberty integration tests are in
`dirac-it-other-containers` (DRC-004, DRC-005, DRC-006).

## DRC-004 — Dirac does not deploy under Weld or Open Liberty (dirac#23)

- **Opening date**: 2026-10-10
- **Status**: ✅ FIXED 2026-10-10

### Symptom

Weld skips `MetricRegistryProducerBean` and `GaugeRegistrationBean` with an INFO message,
`WELD-000119: ... Type io.vidocq.vauban.api.ProxyLink not found`, then fails the deployment:
`WELD-001408: Unsatisfied dependencies for type MetricRegistryProducerBean`.

### Minimal repro

`dirac-it-weld` on `main`.

### Cause

The Vauban build weaves a `protected <init>(io.vidocq.vauban.api.ProxyLink)` entry constructor into every
normal-scoped bean, and `vauban-api` was `provided` (`requires static`). Same cause as Knock's
BUG-20261010-01, Heisenberg's BUG-006, Cervantes' CERV-008 and Humboldt's BUG-20261010-01.

### Fix

`vauban-api` is a runtime dependency of `dirac-cdi-vauban` and `dirac-rest` (plain `requires`), its Jakarta
CDI dependencies excluded. `dirac-cdi-vauban-module-it` names the CDI API on its processor path, and
`dirac-bench` and `dirac-examples` declare it, as they got it through `vauban-api` before.

## DRC-005 — Two containers in one JVM register each other's metrics (dirac#23)

- **Opening date**: 2026-10-10
- **Status**: ✅ FIXED 2026-10-10

### Symptom

Two containers sharing Dirac's classes (two applications on one class loader, or two Weld containers in
one JVM) that start together: the first one registers the second one's gauges instead of its own.

### Minimal repro

`dirac-it-weld`, `TwoContainersOneJvmTest`: the first container starts the second from an
`@Initialized(ApplicationScoped.class)` observer, before its own gauges are registered. On `main`:
`expected: <[first.gauge]> but was: <[second.gauge]>`.

### Cause

`DiracExtension` kept the discovered gauges, timers and counters in `static` sets, cleared at each
container's `@Discovery` phase, and `GaugeRegistrationBean` registered whatever they held at application
start. `GaugeRegistrationBean` also looked gauge beans up through `CDI.current()`, ambiguous with several
containers.

### Fix

The extension keeps, per instance, the names of the classes that carry metrics, and registers a synthetic
`DiscoveredMetrics` bean with them as a parameter; `DiscoveredMetricsCreator` resolves the metrics in the
container. `GaugeRegistrationBean` injects `Instance<DiscoveredMetrics>` and looks gauge beans up through
its own `Instance<Object>`. Unit tests use `DiscoveredMetrics` directly; the container tests add
`DiracExtension`, as the TCK bootstrap does, instead of filling the static sets by hand.

## DRC-006 — An application with Dirac does not deploy on Open Liberty (dirac#23)

- **Opening date**: 2026-10-10
- **Status**: ✅ FIXED 2026-10-10

### Symptom

`CWWKZ0002E ... DeploymentException: Exception List with 11 exceptions`, each one logged as
`Unable to load metric candidate class 'com.ibm.tx.jta.cdi.AbstractTransactionContext'` (and other
Liberty classes) by `LiteExtensionTranslator`.

### Minimal repro

`dirac-it-openliberty` before the fix.

### Cause

`collectMetricAnnotations` (`@Enhancement(types = Object.class, withSubtypes = true)`) is also called for
the server's own bean classes, which the application's class loader cannot load, and reported each
`ClassNotFoundException` through `Messages.error`, which fails the deployment.

### Fix

A class that cannot be loaded (`ClassNotFoundException` or `LinkageError`) is skipped, with a `DEBUG` log:
it cannot carry a metric of the application.
