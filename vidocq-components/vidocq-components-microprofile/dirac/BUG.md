# BUG — Dirac

Tracking reproducible bugs in `dirac` (internal issues, regressions, incorrect behaviors
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, cause hypothesis, status.

---

## BUG-20261128-04 — `GET /metrics/APPLICATION` answers 404 in a Turkish locale

- **Opening date**: 2026-11-28
- **Status**: FIXED (Vidocq/vidocq#832, Error Prone cleanup of Dirac)

### Symptom

The `/metrics/{scope}` endpoints accept the scope in any case. With a Turkish default locale (`tr-TR`),
`/metrics/APPLICATION` and `/metrics/APPLICATION/{name}` answered 404 instead of the application registry.

### Minimal repro

`MetricsResourceTest.formatScopeIgnoresTheCaseOfTheScopeInATurkishLocale`: default locale `tr-TR`,
`formatScope("APPLICATION", null)` returned `null`.

### Cause

`MetricsResource.registryForScope` lower-cased the scope with `String.toLowerCase()`, in the default locale: in
Turkish, `I` becomes the dotless `ı`, and `applıcatıon` matches no scope. Found by Error Prone's
`StringCaseLocaleUsage`.

### Fix

`toLowerCase(Locale.ROOT)`. The same change in `DistributionConfig.parseTimerBucketToNanos` fixes no visible bug
(no unit suffix holds an `i`).

---

## BUG-20261123-57 — The TCK and jlink smoke scripts install the branch into the shared `~/.m2`

- **Opening date**: 2026-11-23
- **Status**: FIXED in `8fc6307a8`

### Symptom

`run-official-tck-mp-metrics-5.1.sh` ran `./mvnw -ntp -pl dirac-api,dirac-core,dirac-cdi-vauban -am install
-DskipTests`, then the TCK, on the default local repository, `~/.m2/repository`, which every build and session of the
machine shares; `run-jlink-smoke.sh` and `run-jlink-smoke-m92.sh` ran `clean install -DskipTests` there too. A run
from a branch replaced the published Dirac snapshot: the other builds then resolved the branch's jars until the next
snapshot download. Same bug in the TCK scripts of Foy, Peano, Ibarra, Bodoni, Cervantes, Cyrano, Grimm, Heisenberg,
Humboldt, Knock and Ravel.

### Minimal repro

`OfficialTckScriptTest` (`dirac-tck`, default `-Ptck` run): the TCK script, copied next to a `mvnw` that logs its
arguments, made two Maven calls without `-Dmaven.repo.local`. The jlink smoke scripts: reading them.

### Cause

The scripts predate the workspace rule "never install into `~/.m2` from a branch build".

### Fix

Every Maven call of the three scripts takes `-Dmaven.repo.local=<Dirac folder>/.tck-repository
-Dmaven.repo.local.tail=$HOME/.m2/repository` (`TCK_LOCAL_REPOSITORY` moves the first): they install and resolve there
first, and only read `~/.m2`. `run-jlink-smoke-m92.sh` takes `microprofile-metrics-api-5.1.1.jar` from that
repository, else from `~/.m2/repository`. `.tck-repository/` is git-ignored. Docs: `tck.adoc`, `TCK.md`, `JLINK.md`,
`README.md`, `AGENTS.md` (the `MAVEN_ARGS` workaround of `TCK.md` and `JLINK.md` is gone).

---

## DRC-019 — A bean is scanned for a metric an interface names, and every superclass is read twice

- **Opening date**: 2026-11-18
- **Status**: ✅ FIXED 2026-11-18

### Symptom

On a Vauban whose language model gives a class the methods it inherits (Vauban BUG-20261117-42), `namesAMetric`
says a bean names a metric when an interface it implements carries `@Counted` or `@Timed` on a method: `methods()`
lists the interface's methods too. The bean class is loaded and scanned by reflection for nothing, the metric of an
interface method being no metric of the bean. And the superclass walk of DRC-011 reads each superclass's methods
again, as `methods()` already holds them.

### Minimal repro

`BootScanPrefilterTest.onlyTheClassesNamingAMetricAreScanned`: `ContractService implements CountedContract`, whose
`call()` carries `@Counted`. Red on 58c4295 with Vauban's lane L2 at 9152f813 (`stack-l2d-dirac-red.log`): `a metric
an interface method names is not the bean's: nothing to scan (DRC-019) ==> expected: <false> but was: <true>`.

### Cause

DRC-011 walked the superclasses and read `methods()` of each, a workaround for a Vauban that left the inherited
methods out of `methods()`. Vauban now follows the language model: `methods()` has the methods of the superclasses
and of the superinterfaces, and `annotations()` the `@Inherited` annotations of the superclasses.

### Fix

`namesAMetric` reads the class's `annotations()`, its constructors, and its `methods()` but those an interface
declares, once; a superclass the container does not describe still sends the class to the reflective scan. The same
test checks that a metric on a method inherited from a superclass, not overridden, is still found and registered
(`MethodInheritingService`). Verification: the full build on the lane's Vauban, green (`stack-l2d-dirac-green.log`).

## DRC-018 — The gauge of a class with a bounded type variable that also declares a producer cannot be read

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/bean-class-lookup`, with DRC-015. Test: `GaugeBeanLookupCdiIntegrationTest.theGaugeOfABoundedGenericBeanThatDeclaresAProducerIsRead` (the value is read, the producer never runs).
- **Reported by**: the tests of DRC-015
- **Minimal reproduction**: `@Dependent class Level<T extends Number>` with a `@Gauge` method and a `@Produces` method. Each read of the gauge failed: `DiracException: Unable to read gauge value`, caused by `AmbiguousResolutionException: Ambiguous dependency: 2 beans match` (before the first draft of DRC-015: `UnsatisfiedResolutionException`).
- **Cause**: a producer is a bean of the class that declares it (`Bean.getBeanClass()`), so two beans of that class have `@Default`. Only their bean types tell them apart: the bean of the class has a `ParameterizedType` whose raw type is the class, a producer has none. `GaugeRegistrationBean` now keeps only the beans that have one, before `resolve` and `getReference`. Reading those types names `java.lang.reflect` types, which `ArchitectureRules.NO_REFLECTION` (the legacy form of the `vidocq-archrules` rule) allows only in a documented fallback: `GaugeRegistrationBean` is one now, with its reason, and `internals.adoc` says why. It reads no member.

## DRC-017 — The gauge of a bean that has only another qualifier than `@Default` cannot be read

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/gauge-registration`, with DRC-016. Found while fixing DRC-015 and left as it was: that fix kept the qualifiers of the lookup. Tests: `GaugeBeanLookupCdiIntegrationTest.theGaugeOfABeanWithAnotherQualifierIsRead` on Vauban, `WeldPortabilityTest.theGaugeOfABeanWithAnotherQualifierIsRead` on Weld, both red before the fix with the cause below.
- **Reported by**: the tests of DRC-015
- **Minimal reproduction**: an `@ApplicationScoped` class with a qualifier of its own and a `@Gauge` method. The container starts. Each read of the gauge fails: `DiracException: Unable to read gauge value`, caused by `UnsatisfiedResolutionException: No bean found for type: …TaggedLevel with qualifiers: [@Any, @Default]`.
- **Cause**: `GaugeRegistrationBean` looks the bean up by its class and names no qualifier, so the lookup asks for `@Default`.
- **Fix**: the gauge is read on the bean of its class, whatever its qualifiers: among the container's beans with `@Any`, the one whose bean class is that class and that has a type of it (the raw class, or a parameterized type of it). Those with `@Default` are taken first, the others when there is none, so a producer of that class with `@Default` and another qualifier on the class change nothing. `GaugeBeanLookupCdiIntegrationTest.theGaugeOfAQualifiedBoundedGenericBeanIsReadWithoutRunningItsProducer` changes with it: the `@Tagged` bounded generic bean whose only `@Default` bean of its class is a producer was unsatisfied, and is read now, the producer still never runs.

## DRC-016 — A `@Dependent` bean with a `@Gauge` gets a new instance at every read of the gauge, and on Weld each one is kept until the container stops

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/gauge-registration`, with DRC-017. Found while fixing DRC-015, not fixed with it. Tests: `GaugeBeanLookupCdiIntegrationTest.aDependentBeanIsCreatedOnceForItsGaugeAndDestroyedWithTheContainer` on Vauban (red before the fix: 3 instances created, none destroyed) and `DependentGaugeBeanLifecycleTest` on Weld (red before the fix: 3 instances created, the 3 destroyed only when the container closed).
- **Reported by**: the tests of DRC-015
- **Minimal reproduction**: a `@Dependent` class with a `@Gauge` method and a counter in its constructor. Read the gauge three times: three instances are created (`expected: <1> but was: <3>`, Vauban `0.4.0-SNAPSHOT` of 455067f5).
- **What happens to those instances** (measured 2026-11-18 with a throwaway probe, not committed: 50 reads, each instance held by a `WeakReference`, ten `System.gc()`):
  - **Weld**: a memory leak. 50 instances created, 50 still reachable while the container is open, 0 destroyed; the 50 `@PreDestroy` run when the container closes. Each instance is a dependent object of the `Instance<Object>` injected into `GaugeRegistrationBean`, an application-scoped bean: memory grows by one instance per read (one per gauge per scrape) until shutdown.
  - **Vauban** (455067f5): no leak, but no destruction either. 50 created, 0 still reachable, 0 `@PreDestroy` while open and 0 after the close. `beans.select(beanClass)` makes a new `InstanceImpl` at each read; the instance and its `CreationalContext` go into that child's `dependentInstances` map (`InstanceImpl.get`), and the parent creational context of an `Instance` injected into a constructor is `null` (`VaubanContainer.instanceParameter`). Dirac drops the child, so the garbage collector takes the instance, and its `@PreDestroy` never runs.
- **Cause**: `GaugeRegistrationBean` calls `Instance.get()` at each read. For a normal-scoped bean that gives the same client proxy each time. For a `@Dependent` bean each call creates an instance. A class with a bounded type variable is not concerned: since DRC-015 its instance is created once and released when the container stops.
- **Fix**: every gauge class is read on one reference of its bean, obtained at the first read and kept, as DRC-015 did for a class with a bounded type variable: the client proxy of a bean of a normal scope, and, for a `@Dependent` bean, one instance that `GaugeRegistrationBean` creates and releases when the container destroys it. A class with no bean of its own, an abstract superclass the extension scanned on its own say, is read on the bean of that type with `@Default`, once too. When no bean is found, each read fails, unsatisfied, as before.

## DRC-015 — The gauge of a bean whose class has a bounded type variable cannot be read

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/bean-class-lookup`. `GaugeRegistrationBean` decides once per gauge class how its target is obtained, at the first read of one of its gauges, and keeps the decision, a failed one too. Every class is looked up by type, as before. A class with type variables that this lookup does not find is read on its own bean: among the container's beans that have `@Default` (`BeanContainer`, the standard API), the one whose bean class is that class and that has a parameterized type of it. A producer the class declares and a class whose bean types `@Typed` restricts are left out before `getReference`: a read never runs a producer, and such a gauge stays unsatisfied, as before. Dirac creates that instance once and releases it when the container stops. When the resolution or the creation fails, its creational context is released, and each read fails with that cause without trying again. A read once the container stopped creates no instance. Tests: `GaugeBeanLookupCdiIntegrationTest` on Vauban, `WeldPortabilityTest.theGaugeOfABoundedGenericBeanIsRead` and `BoundedGaugeBeanLifecycleTest` on Weld.
- **Reported by**: the audit of the lookups by raw class made for Vidocq/vauban#335 (Vidocq/vidocq#652). The ticket expected a start that fails once #335 is merged. The tests show something else: the start succeeds and each read of the gauge fails, already on the Vauban of `main` (455067f5) and on Weld.
- **Minimal reproduction**: `@Dependent class Level<T extends Number>` with a `@Gauge` method. Read the gauge: `DiracException: Unable to read gauge value`, caused by `UnsatisfiedResolutionException: No bean found for type: …Level with qualifiers: [@Any, @Default]` (Weld: `WELD-001334: Unsatisfied dependencies for type Level with qualifiers @Default`).
- **Cause**: the target was `beans.select(beanClass).get()`. The raw class of a bean whose class has a bounded type variable is not a type that bean can be looked up by (CDI 4.1 §5.2.4).
- **Not covered**: DRC-017 and DRC-016, open. DRC-018 (such a class that also declares a producer) is fixed with it.

## DRC-010 — Every bean class loaded and scanned by reflection at start-up for metric annotations

- **Date**: 2026-11-02
- **Status**: FIXED on `perf/boot-prefilter`. `DiracExtension.collectMetricAnnotations` first reads the class declaration the container gives the enhancement (`ClassInfo`: Vauban builds it from build-time metadata). It looks for a metric annotation on the class, its superclasses, its methods and constructors, directly or on an application annotation (a stereotype). Only a class that may declare a metric is loaded and scanned; the companion and reflective scan of those classes is unchanged. When the declaration cannot settle it (an undescribed superclass, an unreadable annotation), the class is scanned as before (`BootScanPrefilterTest`). Audit application boot: dirac reflection from 5,435 to 458 traced calls. MP Metrics TCK 127/127.
- **Reported by**: the boot reflection trace of 2026-11-02
- **Minimal reproduction**: start any application with the metrics extension: `DiracExtension.findTimedOnElement`/`findCountedOnElement` and `DiscoveredMetrics.scan`/`scanGaugeMethods` read the annotations of every method of every bean class (`getDeclaredMethods`, `getAnnotation`, `getAnnotations`).
- **Cause**: `@Enhancement(types = Object.class, withSubtypes = true)` visits every type, and each one was loaded with `Class.forName` and scanned by reflection, although very few declare a metric.

## DRC-009 — Metrics re-resolved by reflection on every call and every bean construction; companions loaded once per class at boot

- **Date**: 2026-11-02
- **Status**: FIXED on `perf/metric-ids-at-build`: the metric of an intercepted method, of a constructor, and the list a bean class pre-registers are resolved once per class (`ClassValue`, so a reloaded class is released), shared by every interceptor instance; `CompanionRegistry` indexes the companions of a module layer or class loader once instead of loading and instantiating all of them for each discovered class (`InterceptorResolutionCacheTest`, `CompanionIndexTest`). MP Metrics TCK 127/127.
- **Reported by**: the reflection audit of 2026-11-02 (wave 3)
- **Minimal reproduction**: a `@RequestScoped` resource with `@Counted`/`@Timed`: each request created the interceptors and their instance caches anew, so `findCounted`/`findTimed` (`getAnnotation`, `getDeclaredMethod`), the constructor annotations and the `getDeclaredMethods` pre-registration scan ran on every request; at boot, `DiracExtension`'s enhancement of every type made `CompanionRegistry` iterate (and instantiate) every provided `MetricsCompanion` per class.
- **Cause**: the caches lived in the interceptor instance, created per bean instance; the companion lookup searched the `ServiceLoader` linearly for each class.

## DRC-008 — Two `DiscoveredMetrics` beans make metric registration fail at start-up

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/merge-discovered-metrics`: `GaugeRegistrationBean` registers `DiscoveredMetrics.merge(...)` of every `DiscoveredMetrics` bean, each metric once (`DiscoveredMetricsMergeTest`; MP Metrics TCK 127/127; vidocq FT TCK 463/463 on the assembled runtime)
- **Reported by**: vidocq BUG-20261030-27, found while measuring the build noise of the tutorial application
- **Minimal reproduction**: `vidocq create -x cassini-rest -x heisenberg-fault-tolerance -x dirac-metrics`, a bean with class-level `@Retry` and `@Counted` injected into a resource: the application stops at boot with _AmbiguousResolutionException: 2 beans match: [DiscoveredMetrics, DiscoveredMetrics]_ from `GaugeRegistrationBean.registerMetrics`.
- **Cause**: since the Metrics codegen bundle carries `dirac-cdi-vauban` (vidocq#426), `DiracExtension` runs at build time, in vauban-processor, and again when the container starts. Each run registers a synthetic `DiscoveredMetrics` bean whose parameter lists the classes it saw. Vauban keeps one bean when two synthetic beans are identical; here the lists differ, so both stay, and `Instance.get()` is ambiguous. Same pattern as Grimm's `ScannedTypes` (grimm#44).

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

---

## DRC-007 — The same metric discovered twice is kept twice by `DiscoveredMetrics`

- **Opening date**: 2026-10-28
- **Status**: ✅ FIXED 2026-10-28

### Symptom

Scanning the same bean class twice leaves two `PreRegisteredMetric` entries for one timer
(and two for one counter) in the `DiscoveredMetrics` sets, so the metric is pre-registered
twice. Found while fixing SonarQube `java:S6218` (record with an array component).

### Minimal repro

```java
var discovered = new DiscoveredMetrics();
discovered.scanTimedMethods(TimedService.class);
discovered.scanTimedMethods(TimedService.class);
discovered.timers().size(); // 2, expected 1
```

### Cause

`DiracExtension.PreRegisteredMetric` and `DiracExtension.ResolvedGauge` are records with a
`Tag[]` component: the generated `equals`/`hashCode` compare arrays by identity, so two
equal metrics never compare equal in the `ConcurrentHashMap` key sets.

### Fix

Both records override `equals`, `hashCode` and `toString` to compare and print the tags by
content. Regression tests: `DiracExtensionTest.ingestingTheSameClassTwiceKeepsOneMetricEach`,
`preRegisteredMetricComparesTagContents`, `resolvedGaugeComparesTagContents`.

---

## DRC-011 — A metric on a method inherited from a superclass outside Vauban's index is never registered

- **Opening date**: 2026-11-17
- **Status**: ✅ FIXED 2026-11-17

### Symptom

A bean inherits, without overriding it, a method annotated `@Counted` or `@Timed` from a superclass that is not in
Vauban's index: a class of a dependency jar, or one not added to the deployment. The metric is never registered. The
interceptor still runs, since CDI passes the binding of a method not overridden on to the subclass (CDI 4.1, §4.2), so
every call to the method throws `IllegalStateException: Counter metric was removed from registry`. Both paths lose it:
the reflective scan, and the `$$DiracMetrics` companion that `dirac-processor` generates for a bean with metrics of its
own, the path most applications take. Live on Vauban main (455067f5) and vauban#335 (131fb3e6). Vauban
BUG-20261117-31 (b7af3df3), which describes such a superclass from its class file, keeps it with another cause.

### Minimal repro

- Scan: `InheritedMethodMetricCdiIntegrationTest.aMetricOnAMethodInheritedFromASuperclassOutOfTheDeploymentIsRegistered`.
  An `@ApplicationScoped` bean extends `UnindexedBase`, whose `ping()` carries `@Counted`, and only the bean is added
  to the container. Before the fix, on the three Vaubans: `the counter inherited.unindexed is registered when the bean
  is deployed ==> expected: not <null>`.
- Companion: `DiracMetricsProcessorTest.aBeanCompiledWithTheProcessorRegistersTheMetricOfAMethodInheritedFromAnotherJar`.
  A top-level bean, compiled with the processor and with a `@Counted` method of its own, extends a class of another jar
  whose `ping()` carries `@Counted`; only the bean is deployed. Before the fix, on the three Vaubans, the bean's own
  counter is registered but not the inherited one: `the inherited counter is registered when the bean is deployed ==>
  expected: not <null>`.

### Cause

- The reflective scan of `DiscoveredMetrics` read the declared methods of the bean class only. An inherited metric was
  registered only when the extension visited the superclass on its own, which it does for a class of the index alone.
  On Vauban main the superclass has no declaration, so `namesAMetric` sent the bean class to the scan, which found
  nothing.
- The processor read the members the bean class declares (`type.getEnclosedElements()`) only, and `ingest` takes the
  companion instead of the scan.
- `namesAMetric` read the class annotations of the superclasses, but the methods of the bean class only, through
  `ClassInfo.methods()`. The language model says `methods()` includes those of the superclasses; Vauban's leaves them
  out (Vauban BUG-20261117-42). Once Vauban describes the superclass (BUG-20261117-31), `namesAMetric` walks into it,
  finds no class annotation and returns `false`: the bean class is not even scanned.

A superclass in the index loses nothing: the extension visits it, abstract or not, and scans it on its own (the same
test with `IndexedBase` passes on the three Vaubans before the fix).

### Fix

- `namesAMetric` reads the methods of every class it walks into, up to and excluding `java.lang.Object`, from that
  class's own declaration, and still defers to the scan when a superclass has no declaration.
- The scan and the processor also collect the metrics of the methods the bean class inherits from its superclasses and
  does not override (neither private nor static), named after the class that declares them, as `CountedInterceptor`
  and `TimedInterceptor` name them and as the TCK's `InheritedTimedMethodBeanTest` expects. The processor reads a
  superclass of another jar from its class file and drops the overridden methods with `Elements.overrides`; the scan
  compares names and parameter types, and sees an override through a type argument by its bridge method. Both leave
  out a method of package access from another package than the bean's: it is not inherited (JLS 8.4.8), no subclass
  intercepts it, and its counter would never move.
- A superclass that cannot be linked at run time (one of its methods names a type of an optional dependency or of a
  `requires static` module) ends the inherited walk. The bean keeps its own metrics and those found below; the
  `NoClassDefFoundError` used to leave `ingest`, and the extension skipped the whole bean. The creator of the
  synthetic bean reports it at `WARNING`; the extension's own pass, at build time or at start, reports it at `DEBUG`,
  and keeps the class for the creator even when the bean has no metric of its own, since the metrics it would
  inherit cannot be read. One walk serves `@Timed` and `@Counted`. So each run of the extension gives one `WARNING`:
  one in a deployment frozen at build or one that runs the extension at start only, two when it runs at build and at
  start, as each run registers its own synthetic bean (BUG-20261030-27 of vidocq).
- None of it depends on Vauban BUG-20261117-42.

Regression tests: `InheritedMethodMetricCdiIntegrationTest` (a superclass in the deployment, and one outside it) and
`DiracMetricsProcessorTest.aBeanCompiledWithTheProcessorRegistersTheMetricOfAMethodInheritedFromAnotherJar`, run on
Vauban main, vauban#335 and BUG-31; `DiracExtensionTest.scanFindsTheMetricsOfInheritedMethodsNamedAfterTheirDeclaringClass`,
`DiracExtensionTest.aSuperclassThatCannotBeLinkedKeepsTheBeanOwnMetrics`,
`theCreatorReportsASuperclassThatCannotBeLinkedAtWarning` and
`aPackagePrivateMethodOfASuperclassInAnotherPackageRegistersNoMetric`;
`DiracMetricsProcessorTest.companionListsTheMetricsOfInheritedMethodsNamedAfterTheirDeclaringClass` and
`companionListsNoMetricForAPackagePrivateMethodOfASuperclassInAnotherPackage`.

---

## DRC-012 — An interface in the deployment registers counters that never move

- **Opening date**: 2026-11-17
- **Status**: FIXED on `fix/interceptor-metrics`

### Symptom

An interface of the deployment whose methods carry `@Counted` or `@Timed` is scanned like a bean: its metrics are
registered and show in `/metrics`, but a bean that implements it and overrides the method is not intercepted for it, so
they never move. An interface is not a bean (CDI 4.1, §3.1.1), and the bindings of an interface method are not
inherited (§4.2 covers superclasses).

The other side of it: a default method that the bean does not override, annotated on its interface, is intercepted
through the interface's annotation by Weld SE and by Vauban main alike. When the interface is outside the deployment,
Dirac registers nothing for it, and every call throws `IllegalStateException: Counter metric was removed from
registry`, on both containers. When the interface is in the deployment, the scan of the interface registers the
counter, and the call counts.

### Minimal repro

- `InheritedMethodMetricCdiIntegrationTest.anInterfaceOfTheDeploymentRegistersNoMetric` (`@Disabled("DRC-012")`), on
  Vauban main and BUG-31: `an interface is not a bean: no counter for its method ==> expected: <null> but was:
  <io.vidocq.dirac.internal.CounterImpl@...>`.
- The default method: a probe on Weld SE (`dirac-it-weld`) and on Vauban main, not committed, nothing pre-registered,
  the interface outside the deployment: `threw java.lang.IllegalStateException: Counter metric was removed from
  registry: MetricID{name='iface.default.inherited', tags=[]}` on both.

### Cause hypothesis

`collectMetricAnnotations` visits every type of the deployment, interfaces included, and `DiscoveredMetrics.scan`
reads the interface's declared methods as it reads a bean class's. Nothing registers, for the bean, the metrics of the
default methods it inherits from its interfaces.

### Fix

- 2026-11-19: since DRC-019 (`namesAMetric` leaves out the methods an interface declares), the interface was no longer
  scanned, so `anInterfaceOfTheDeploymentRegistersNoMetric` passed already. The other side got worse: a default method
  counted through its interface threw when the interface was in the deployment too, on Vauban and on Weld
  (`dirac-it-weld` discovers its test classes), and a `@Gauge` on a default method was not registered at all.
- `DiscoveredMetrics.scanMethods` and `DiracExtension.namesAMetric` skip an interface: it is no bean.
- The scan collects, for the bean, the metrics of the default methods it inherits and does not override, `@Gauge`
  ones included, named after the interface that declares them, as the interceptors name them.
  `Class.getMethods()` gives the most specific method of each signature: one that the class, a superclass or a more
  specific interface overrides passes nothing on. `namesAMetric` counts a default method's annotation as the class's.
- The processor lists them in the companion, with the same rule (`Elements.overrides` against the class chain and the
  other interface methods).

Tests, red before the fix: `InterfaceDefaultMethodMetricCdiIntegrationTest` (the interface in the deployment and
outside it: `IllegalStateException: Counter metric was removed from registry`; a default method the bean overrides),
`DiracExtensionTest.scanFindsTheMetricsOfInheritedDefaultMethodsNamedAfterTheirInterface`,
`BootScanPrefilterTest.onlyTheClassesNamingAMetricAreScanned` (a bean whose only metric is on an inherited default
method), `DiracMetricsProcessorTest.companionListsTheMetricsOfInheritedDefaultMethodsNamedAfterTheirInterface` and
`WeldPortabilityTest.aDefaultMethodCountedThroughItsInterfaceIsCounted` (red against the jars of
`fix/gauge-registration`). `InheritedMethodMetricCdiIntegrationTest.anInterfaceOfTheDeploymentRegistersNoMetric` is
enabled, green since DRC-019.

---

## DRC-013 — A `@Gauge` on a method inherited from a superclass outside the index is never registered

- **Opening date**: 2026-11-17
- **Status**: FIXED on `fix/gauge-registration`

### Symptom

A bean inherits a `@Gauge` method from a superclass that is not in Vauban's index: the gauge is never registered. No
call throws: a gauge is not intercepted, so CDI 4.1, §4.2, which passes interceptor bindings on, does not apply here.

### Minimal repro

`InheritedMethodMetricCdiIntegrationTest.aGaugeOnAMethodInheritedFromASuperclassOutOfTheDeploymentIsRegistered`
(`@Disabled("DRC-013")`), on Vauban main and BUG-31: `the gauge inherited.gauge is registered ==> expected: not
<null>`.

### Cause hypothesis

`DiscoveredMetrics.scanGaugeMethods` and the processor's gauge loop read the declared methods of the bean class only;
the DRC-011 fix does not touch them. A fix has a second point to settle: `DiracExtension.resolveMetricName` names a
gauge after `method.getDeclaringClass()`, so two beans that extend the same base of a jar would register the same gauge
name twice, one per bean. That overlaps the gauge bean lookup that `fix/bean-class-lookup` changes in
`GaugeRegistrationBean`.

### Fix

- The scan's walk of the superclasses, the one of DRC-011, collects the `@Gauge` methods the bean inherits and does
  not override, neither private nor static, nor package-private from another package (JLS 8.4.8). The processor lists
  them in the companion, and leaves a bean that inherits a `protected` gauge method of another package to the scan:
  the companion, in the bean's package, cannot call it.
- Each one keeps the name its declaring class gives it (`DiracExtension.resolveMetricName`, unchanged), and is read on
  the bean: its record names the bean class.
- The second point: when a bean and its subclass both carry one gauge, it is read on the class that declares the
  method, not on the subclass, whichever was scanned first; MicroProfile Metrics 5.1's `InheritedGaugeMethodBeanTest`
  expects that of a parent bean and its child. Two unrelated beans that carry one gauge id, two beans that extend the
  same base of a jar say, give one gauge: one id has one value. It is read on the bean whose class name comes first,
  and a `WARNING` names both, once per container. Two beans that each declared a gauge of one absolute name were kept
  at random before; they follow the same rule.
- The read itself goes through the bean lookup of DRC-016 and DRC-017: a superclass the extension scans on its own and
  that has no bean of its own, an abstract base say, is read on the bean of that type, as before.

Tests, red before the fix: `InheritedMethodMetricCdiIntegrationTest.aGaugeOnAMethodInheritedFromASuperclassOutOfTheDeploymentIsRegistered`
(enabled), `DiracExtensionTest.scanFindsTheGaugesOfInheritedMethodsNamedAfterTheirDeclaringClass` and
`aPackagePrivateGaugeOfASuperclassInAnotherPackageIsNotInherited`,
`DiscoveredMetricsMergeTest.aGaugeIsReadOnTheBeanThatDeclaresItNotOnTheSubclassThatInheritsIt`,
`aGaugeTwoUnrelatedBeansCarryIsReadOnTheFirstByClassNameAndWarned` and `aGaugeTwoUnrelatedBeansCarryIsWarnedOnceForTwoRuns`,
`DiracMetricsProcessorTest.companionListsTheGaugesOfInheritedMethodsNamedAfterTheirDeclaringClass` and
`companionCallsTheGaugesInheritedFromAnotherPackageThatItCanCall`. Also
`InheritedMethodMetricCdiIntegrationTest.aGaugeOfABeanThatASubclassInheritsIsReadOnTheBeanThatDeclaresIt`, the
parent and child case end to end. The official TCK: 127 tests, 0 failures.

---

## DRC-014 — A method counted through an application interceptor binding throws on every call

- **Opening date**: 2026-11-17
- **Status**: FIXED on `fix/interceptor-metrics`

### Symptom

A method that carries `@Counted` only through an application interceptor binding (an `@InterceptorBinding` annotation
annotated `@Counted`): the scan registers one name, `CountedInterceptor` looks up another, and every call throws
`IllegalStateException: Counter metric was removed from registry`.

- The bean's own method, with a relative name: the scan registers `<bean class>.relative`, as the "Annotated Naming
  Convention" of Metrics 5.1 says for a method; the interceptor looks up `<package>.relative.ping`, the class-level
  rule. Older than DRC-011.
- An inherited method, with the default name: the scan registers `<declaring class>.ping`, the interceptor looks up
  `<bean class>.ping`. Before DRC-011 this call threw too, as the inherited metric was not registered at all.

### Minimal repro

`InheritedMethodMetricCdiIntegrationTest.aMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt` and
`anInheritedMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt` (`@Disabled("DRC-014")`), on Vauban main
and BUG-31: `IllegalStateException: Counter metric was removed from registry:
MetricID{name='io.vidocq.dirac.cdi.internal.relative.ping', tags=[]}`, and the same with the bean class's name.

### Cause hypothesis

`CountedInterceptor.resolveMetricName` (~282, ~294) chooses between the method-level and the class-level rules, and
between the declaring class and the bean class, with `method.isAnnotationPresent(Counted.class)`, which is false for
an annotation an application binding carries; the scan finds it through `findCountedOnElement` and applies the
method-level rule. `TimedInterceptor` (~328, ~340) has the same shape (not tested).

### Fix

- `CountedInterceptor` and `TimedInterceptor` find the annotation as the scan does, with
  `DiracExtension.findCountedOnElement` and `findTimedOnElement` (their private copies are gone): on the method, the
  constructor and the class, for the intercepted call and the pre-registration alike.
- They name the metric with the scan's rule, `DiracExtension.resolveSimpleMetricName`: the annotation counts as the
  method's own when an interceptor binding of the method carries it.
- The same cause hid two more cases, now covered: a constructor counted through an application binding, and a class
  counted through one, had their counter registered by the scan, and it never moved (the interceptor saw no
  annotation).

Tests, red before the fix: `InheritedMethodMetricCdiIntegrationTest.aMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt`
and `anInheritedMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt` (enabled),
`ApplicationBindingMetricCdiIntegrationTest` (the `@Timed` method and inherited method, the constructor, the class),
`WeldPortabilityTest.aMethodCountedThroughAnApplicationBindingIsCounted` (red against the jars of
`fix/gauge-registration`). The official TCK: 127 tests, 0 failures.
