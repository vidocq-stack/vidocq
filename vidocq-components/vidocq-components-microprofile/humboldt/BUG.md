# Humboldt — Bug registry

> Every reproducible bug (internal issue, regression, incorrect behavior not yet fixed) must be tracked here. See the workspace root `CLAUDE.md` for the convention.

## Entry format

```
### [HBT-N] Short title
- **Date**: YYYY-MM-DD
- **Component**: humboldt-api / humboldt-sdk-trace / …
- **Status**: OPEN / INVESTIGATING / FIXED / WONTFIX
- **Affected**: affected version(s) (e.g. 0.1.0-SNAPSHOT)
- **Symptom**: description of the observed error
- **Reproduction**: minimal steps to reproduce
- **Root-cause hypothesis**: root-cause diagnosis (when known)
- **Fix**: PR link or applied fix
```

---

### [HBT-2] cassini-cdi-vauban never activates RequestContext around an HTTP dispatch

- **Date**: 2026-05-24
- **Component**: cassini-cdi-vauban
- **Status**: FIXED (cassini-cdi-vauban commit `00:53 2026-05-24`)
- **Affected**: cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptom**: any `@RequestScoped` resource (= any `@Path` class after the Cassini BCE) throws `ContextNotActiveException: RequestScope is not active` when invoking a resource method.
- **Reproduction**: see HBT-1 — trigger BaggageTest before the workaround.
- **Cause**: `cassini-cdi-vauban` contains no call to `VaubanContainer.requestContext().activate()` around HTTP dispatches. `@RequestScoped` beans therefore can never be instantiated.
- **Applied fix**: new `VaubanRequestScopeFilter` (`@Provider @PreMatching @Priority(Integer.MIN_VALUE)`)
  which implements both `ContainerRequestFilter` (activate) and `ContainerResponseFilter` (deactivate).
  Auto-injected in production via `VaubanBeanProvider.getResourceClasses()` (singleton returned
  by `getBean()`). For test harnesses that do not use `CassiniStackBuilder.beanProvider(...)`
  (case of `humboldt-tck/CassiniHarness`), the constructor is public — register manually via
  `.provider(new VaubanRequestScopeFilter(container))`.
- **Validation** : Cassini Jakarta REST 4.0 TCK = 2535/2535 PASS (contract respected, 0 regression).
  humboldt MP Telemetry 2.1 TCK = 19/43/23 (equivalent to the previous workaround fix, but now
  activation is handled by cassini-cdi-vauban rather than by an ad-hoc humboldt-tck Handler).

---

### [HBT-1] Cassini @Path → @RequestScoped BCE not applied to beans added at runtime via Vauban addBeanClass

- **Date**: 2026-05-24
- **Component**: humboldt-tck (Vauban runtime + cassini-cdi-vauban BCE interaction)
- **Statut** : FIXED (humboldt-tck commit `00:25 2026-05-24`)
- **Affected**: humboldt-tck 0.1.0-SNAPSHOT, vauban 0.1.0-SNAPSHOT, cassini-cdi-vauban 0.1.0-SNAPSHOT
- **Symptom**: `cdi.select(BaggageResource.class)` throws `UnsatisfiedResolutionException:
  No bean found for type: ...BaggageResource`. The class has `@Path` but no
  explicit CDI scope — the BCE `CassiniScopeExtension.addDefaultScope()` expected to add
  `@RequestScoped` is NOT executed for classes registered via
  `VaubanContainerBuilder.addBeanClass()` en runtime. Consequence: TCK resources
  TCK with `@Inject Baggage/Tracer/Span/...` receive `null` → NPE → HTTP 500.
- **Reproduction** :
  1. `cd humboldt && ./run-official-tck-telemetry-2.1.sh -Dtest=BaggageTest`
  2. Observe in stderr logs: `Baggage Resource Exception: NullPointerException
     Cannot invoke "Baggage.getEntryValue" because "this.baggage" is null`
  3. `cdi.select(BaggageResource.class)` (from CassiniHarness) confirms
     `UnsatisfiedResolutionException`.
- **Root-cause hypothesis**: Vauban applies `@Enhancement` BCEs at compile time via
  APT processor. Classes added by `addBeanClass(Class)` at runtime are
  registered in the BeanManager but do NOT go through the
  enhancement phase → their annotations are not mutated → `@Path` classes without
  scope are ignored by bean discovery (Vauban requires an explicit CDI scope
  to consider a class a bean).
- **Affected tests (FAIL HTTP 500)**: BaggageTest, baggageBeanChange, and
  probably any test that POSTs/GETs a TCK resource with a CDI-typed `@Inject`
  (testIntegrationWithJaxRsClient*, testIntegrationWithMpRestClient*).
- **Applied fix**: Vauban already has the runtime mechanism to apply BCEs
  `@Enhancement` to "unprocessed" classes (`BceProcessor.processEnhancementOnly` line 742
  of `VaubanContainerBuilder`), but ONLY if the BCE is in the bean classes set.
  `addBeanClass()` does not scan the ServiceLoader `META-INF/services`. Fix: add a
   line in `HumboldtDeployableContainer.deploy()` that explicitly declares
   `CassiniScopeExtension.class` via `addBeanClass()`. The BCE becomes discoverable and
   applies its `@Enhancement`, which adds synthetic `@RequestScoped` to `@Path` classes
  of the WAR.
- **Validation**: `BaggageTest.baggage` PASS (vs FAIL before). TCK run goes from 16 → 19 PASS.
- **Follow-up**: a generic Vauban fix (automatically scanning BCEs via ServiceLoader
  in `build()` even without `scanLocal()`) would be cleaner — separate Vauban task.

---

### [HBT-3] JPMS workaround in `humboldt-rest` via manual copying of compile-scope JARs

- **Date** : 2026-05-25
- **Component**: humboldt-rest/pom.xml
- **Status**: ⚠️ OPEN — workaround active
- **Affected**: humboldt 0.1.0-SNAPSHOT
- **Symptom**: `humboldt-rest/pom.xml` uses `maven-dependency-plugin` (phase `initialize`)
  to copy compile-scope JARs (`humboldt-propagator-w3c`, `humboldt-otel-api`,
  `humboldt-otel-context`) into `target/javamodules/`, then passes
  `--module-path ${project.build.directory}/javamodules` manuellement au compilateur javac.
  This workaround is limited to the `humboldt-rest` submodule (the other Humboldt modules
  do not seem affected).
- **Reproduction** :
  ```bash
  grep -n "javamodules\|module-path" humboldt/humboldt-rest/pom.xml
  # reveals maven-dependency-plugin + compilerArgs
  ```
  Remove the config and recompile `humboldt-rest` to observe `module not found` errors.
- **Root-cause hypothesis**: the OTel modules (`opentelemetry-api`, `opentelemetry-context`)
  and the copied intermediate Humboldt modules do not have a `module-info.class` recognized by
  `maven-compiler-plugin` 4.x. Copying them into `target/javamodules/` allows javac to
  resolve them as automatic modules from the JAR filename.
- **Proposed fix**: check whether `opentelemetry-api` 1.x publishes a JPMS descriptor
  explicitly in recent versions; wrap if needed. Investigate why
  `humboldt-propagator-w3c` and `humboldt-otel-*` (internal modules) are not resolved
  natively — they should have their own `module-info.class`.

---

## BUG-20260712-01 — hardcoded implementation version constant in the published api artifact

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : Humboldt.version() (humboldt-api/Humboldt.java)
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
