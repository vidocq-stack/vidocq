# ADR-001 — Java Modules Fork for `knock-mp-health-api` (explicit jlink module)

**Date:** 2026-05-10  
**Status:** Accepted — build workaround part superseded (2026-10-08): `module-info.java` is back in
`src/main/java`, `target/javamodules/` and the `prepare-package` recompilation are gone, and tests run on the
module path (Vidocq/vidocq-parent#13, #15). The `knock-mp-health-api` fork itself stands.  
**Deciders:** Vidocq Team

---

## Context

`microprofile-health-api:4.0.1` (Eclipse MicroProfile) is shipped without `module-info.class`.
This JAR is therefore an **automatic module**, which blocks `jlink` (which rejects automatic
modules). Furthermore, the upstream `package-info.java` files use
`@org.osgi.annotation.versioning.Version`, introducing an OSGi dependency that is also
non-modular.

Knock needs an **explicit** Java module for `microprofile.health.api` so that:

- `requires microprofile.health.api` compiles cleanly;
- `jlink` can produce a minimal image without automatic modules;
- `knock-core` remains usable in pure SE (no mandatory CDI dependency).

---

## Identified Problems

1. **`jlink` rejects automatic modules** → upstream `microprofile-health-api` blocks
   the creation of a dedicated image.
2. **Non-modular OSGi dependency** via `@org.osgi.annotation.versioning.Version` in the
   `package-info.java` files → second source of automatic module.
3. **`knock-core`** remains subject to the `module-info` workaround to avoid Java Modules detection
   during `testCompile` (see "Three-phase Maven build" section below).

---

## Chosen Solution

### Fork MicroProfile Health API

Create a minimal fork **within the repo** under the Maven module
`io.vidocq.knock:knock-mp-health-api`:

- Sources copied from `microprofile-health-api:4.0.1` (sources JAR).
- Added a `module-info.java` with the name **`microprofile.health.api`**.
- Added `uses org.eclipse.microprofile.health.spi.HealthCheckResponseProvider`.
- CDI dependencies declared as `requires static` to preserve pure SE usage.
- Removed `@org.osgi.annotation.versioning.Version` annotations from
  `package-info.java` files to avoid a non-modular OSGi dependency.
- Included upstream `META-INF/LICENSE` and `META-INF/NOTICE`.

### `module-info.java` of `knock-core` outside `src/main/java`

`knock-core` places its `module-info.java` in a separate source root: `src/main/module-info/`.
The solution proceeds in three phases, configured in `knock-core/pom.xml`:

1. **`default-compile`** (`src/main/java`) does not compile `module-info.java` → no
   `module-info.class` in `target/classes` → Maven does not detect Java Modules for `testCompile`.
2. **`maven-clean-plugin`** (`generate-test-sources`) removes any stale `module-info.class`
   from `target/classes` (incremental builds: avoids Java Modules detection on a residual class
   from the previous cycle).
3. **`maven-compiler-plugin`** (`prepare-package`) recompiles only
   `src/main/module-info/module-info.java` into `target/classes` → the final JAR correctly
   embeds `module-info.class`.

`maven-surefire` receives `<useModulePath>false</useModulePath>`: tests in `knock-core`
run on the **classpath**. This is intentional — unit tests validate business logic;
Java Modules wiring is validated by the TCK smoke test (`knock-tck`).

### Double SPI Registration

`KnockHealthCheckResponseProvider` is declared **twice**:

| Mechanism | File | Consumed by |
|---|---|---|
| `provides ... with` | `knock-core/src/main/module-info/module-info.java` | `ServiceLoader` Java Modules (explicit modules) |
| `META-INF/services/` | `knock-core/src/main/resources/META-INF/services/…HealthCheckResponseProvider` | `ServiceLoader` via ClassLoader (`HealthCheckResponse.named()` method) |

The `META-INF/services/` registration is mandatory because `HealthCheckResponse.named()`
cannot use the Java Modules `ServiceLoader.load(…)` mechanism: it invokes
`ServiceLoader.load(HealthCheckResponseProvider.class, Thread.currentThread().getContextClassLoader())`
which scans service files on the classpath — including `META-INF/services/` from
named modules on the module-path.

---

## jlink Compatibility

**The chosen solution is compatible with `jlink`.**

The `io.vidocq.knock:knock-mp-health-api` fork is an **explicit module**
(`microprofile.health.api`). The jlink image therefore contains **no automatic modules**.

Example command:

```bash
jlink \
  --module-path "${JAVA_HOME}/jmods:${project.build.directory}/javamodules" \
  --add-modules io.vidocq.knock.api \
  --output knock-runtime
```

---

## Revision Conditions

This workaround must be re-evaluated if:

1. Upstream `microprofile-health-api` publishes a JAR with `module-info.class`
   → remove the fork, revert to the official artifact, keep `requires microprofile.health.api`.
2. OSGi annotations are no longer needed upstream
   → possibility of restoring the original `package-info.java` files.
3. Maven Compiler Plugin improves Java Modules detection for `testCompile`
   → re-evaluate the necessity of the `module-info` workaround in `knock-core`.

---

## Rejected Alternatives

| Alternative | Reason for rejection |
|---|---|
| Patch the `microprofile-health-api` JAR (add `Automatic-Module-Name` via `jar --update`) | Complex in a multi-module build; risk of desynchronization at each version bump |
| Create a wrapper JAR module for `microprofile.health.api` | Over-engineering; additional artifact to maintain and align |
| Exclude `microprofile-health-api` from module-path, everything on classpath | Abandons strict Java Modules — contrary to Vidocq principles |
| Wait for a MicroProfile release providing `module-info.class` | Blocking; no date announced on the MicroProfile roadmap |
