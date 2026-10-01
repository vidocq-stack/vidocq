# Bug Tracker

Track reproducible bugs with:
- id
- date
- symptom
- minimal repro
- hypothesis
- status

## Entries

### BUG-20260715-01 — grimm-tck references stale vidocq-runtime groupIds

- **id**: BUG-20260715-01
- **date**: 2026-07-15
- **symptom**: `./run-official-tck-mp-openapi-4.1.sh all` fails before running any test:
  `Could not resolve dependencies for io.vidocq.grimm:grimm-tck` —
  `Could not find artifact io.vidocq.runtime:vidocq-runtime-cassini-rest-extension:jar:0.3.0-SNAPSHOT`.
- **minimal repro**: build vidocq runtime `main` (`mvn install`), then run the grimm TCK script.
- **hypothesis (confirmed)**: the vidocq runtime module rework of 2026-06-08
  ("first rework of modules") moved `vidocq-runtime-cassini-rest-extension` to groupId
  `io.vidocq.runtime.extensions.jakartaee.core` and `vidocq-runtime-chappe-webserver-extension`
  to `io.vidocq.runtime.extensions.essentials`. `grimm-tck` (out-of-reactor) still declared the
  old `io.vidocq.runtime` groupId, which is no longer installed locally; resolution then falls
  back to the invalid snapshot on central-snapshots (see vidocq/BUG.md) and fails.
  `vidocq-runtime-core` did not move.
- **status**: FIXED — grimm-tck/pom.xml updated to the new groupIds;
  full MP OpenAPI 4.1 TCK green again (349 tests, 0 failures, 0 errors, 0 skipped).


## BUG-20261001-01 — Grimm ships automatic modules: `/openapi` lost in a modular application (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/named-modules`, with vauban `fix/inject-constructor-over-no-arg`, cassini `fix/no-split-into-automatic-module` and the Vidocq extension's `requires io.vidocq.grimm.cdi.vauban`)
- **Symptom** (reported by Sébastien Blanc on 0.4.0-SNAPSHOT): a Vidocq application whose build runs `cassini-maven-plugin:generate` with `scope=dependencies` does not serve `/openapi`. Grimm is "not active in the container", the Cassini report misses `OpenApiResource`, and nothing says why. Depending on the launch shape, the layer fails instead: `LayerInstantiationException: Package io.vidocq.grimm.cdi in both module grimm.cdi.vauban and module <app>`.
- **Minimal repro**: an application module with Cassini REST + the Grimm extension, and the Cassini plugin execution above. `/openapi` answers 404, and 200 without that execution.
- **Cause**:
  1. The `module-info.java` of grimm-core, grimm-processor and grimm-cdi-vauban, under `src/main/module-info/`, were never compiled into the jars, so Grimm shipped automatic modules (`grimm.cdi.vauban`, ...).
  2. cassini-maven-plugin took the jar without module-info for a class-path jar and wrote `io/vidocq/grimm/cdi/OpenApiResource$$CassiniAdapter.class` into the application module, which split the package.
  3. The application layer's loader then owned `io.vidocq.grimm.cdi`, so `GrimmModelCache` and the other Grimm beans could not be loaded, and Vauban's scan skipped them without a word.
- **Fix**: the three module descriptors are compiled at `prepare-package` and ship in the jars, so Grimm is made of named modules (`io.vidocq.grimm.*`), never automatic ones. grimm-cdi-vauban:
  - `provides` its generated `_VaubanComponents`;
  - `requires transitive` the MicroProfile OpenAPI and JAX-RS APIs its own API exposes, with jakarta.ws.rs-api moved to compile scope to match.

  The Cassini plugin then repackages grimm-cdi-vauban instead of splitting its package. Being a named module also exposed a Vauban defect, fixed in Vauban: with an `@Inject` constructor next to a no-arg one, the generated provider only knew the no-arg one. Verified on the reporter's shape, packaged distribution: `/openapi` 200, and the document lists the application's routes.
