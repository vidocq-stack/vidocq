# The application layer holds the application's dependencies — design

Issues: Vidocq/vidocq#247 (launch shapes), Vidocq/vauban#154 (re-layer policy). Depends on Vidocq/vauban#153
(code source of layer classes, PR vauban#155).

## Goal

An external dependency is treated like application code (maintainer rule, 2026-10-13). For class loading, that
means the `WEB-INF/lib` model of a servlet container:

- the **parent layer** holds the runtime (`vidocq-runtime-core`, `-spi`), the Vidocq bricks and extensions, the
  Jakarta and MicroProfile APIs, and what they read;
- the **application layer** holds the application and **every other dependency**, on the one `VaubanClassLoader`
  of the layer.

Frameworks that assume a flat class path, and look things up through their own class loader, then see the
application's classes and resources. Vaadin's `ResourceProviderImpl` is one. In the Vaadin-on-Foy spike
(2026-10-13), Vaadin in the boot layer could not find the application's `META-INF/VAADIN/webapp/index.html`. With
its 68 jars in the application layer, plus the vauban#153 fix, the page answered 200 with no workaround.

## Developer experience: the acceptance criteria

This section comes first because every other choice serves it. It applies to this design and to Vidocq/vidocq#246
(the bricks' generators on dependency jars). The work is not done until a developer gets exactly this:

1. **Nothing to declare.** I write my application and add dependencies to the pom: Vaadin, a library compiled
   against Jakarta Data, anything. There is no `<scanDependencies>`, no hand-written `provides` and no workaround
   class. Those settings stay available as overrides, never as a required step.
2. **One command.** `mvn vidocq:dev`, or the `(dev)` configuration that `vidocq:idea` writes, compiles, generates
   and starts:
   - the bricks' processors run on my classes, as today;
   - `vidocq:generate` finds the dependencies that need code, through cheap hints, and runs the same processors on
     them: proxies, Foy components, Mansart repositories, service files and indexes, all in enriched copies;
   - the application starts with my classes and all my libraries in one layer.
3. **A fast loop.** Changing a class reloads in place. With the libraries now in the reloaded layer, the archive
   readers of unchanged jars are cached across reloads. That cache is part of this delivery, not a fallback. The
   target is set after the first measurement (BENCH.md), for example a reload of the Vaadin example no more than
   20 % slower than the Servlet example's today.
4. **Generation once per dependency version.** Today an enriched copy is not rewritten when its inputs are
   unchanged (`Vidocq-Enriched-Digest`), but the generation pass itself runs on every build. A dependency jar is
   generated for once, then reused until the jar or the processors' versions change. A first `vidocq:dev` with
   Vaadin's 92 jars may take a few seconds; the next ones must not.
5. **One clear line when something cannot be done.** A dependency the build cannot fully process, for example a
   repository jar compiled without `-parameters`, gives one build message that names the jar, the method and the
   fix. There is no silent fallback and no stack trace.
6. **Visible at startup.** The startup report says it in one line, for example `68 libraries in the application
   layer, 3 enriched (Foy 1, Mansart 2)`. `mvn vidocq:analyze-deps` gives the details: why each jar was selected
   or left out, and what was generated for it.
7. **No setting to learn.** `vidocq.app.layer` is an escape hatch for the rollout and for odd cases. `all` becomes
   the default as soon as the validation campaign is green. The campaign is part of this work, not a later
   project.

## Today

| Launch shape | Application layer | Boot module path |
|---|---|---|
| `vidocq:run` (always layer mode) | `target/classes` (`ApplicationLaunch.appPath`) | every dependency, enriched copies included |
| `vidocq:dev` (`vidocq.dev.layer`, default on) | `target/classes`, rebuilt on each in-JVM reload | every dependency, survives reloads |
| IDE / trampoline (`Vidocq.run` from the boot layer) | the caller plus what `VaubanLayerFactory.applicationPaths` moves | the rest; automatic modules always stay |
| `vidocq:package` (`vidocq.package.layer`, default on) | `app/` (the application jar) | `lib/` (every dependency) |
| `vidocq:jlink`, jpackage, docker | none, unless the main is a trampoline | everything is linked into the image |

`VidocqAppLayer.RUNTIME_MODULE_PREFIXES` lists what the trampoline keeps. Foy is missing from that list: today Foy
is kept only because it is reached from kept modules.

## Design

### 1. One rule decides the split: the closure of the runtime

The build computes the split from the module graph instead of from name prefixes alone.

- **Parent layer**: resolve the runtime roots (`io.vidocq.runtime.core` plus every Vidocq extension present on the
  path) and keep their whole `requires` closure. That closure contains the bricks, the APIs and anything a brick
  reads.
- **Application layer**: everything else, automatic modules included.

A library that a brick reads therefore stays in the parent layer, once, which closes the "two copies of one class"
risk. Our bricks never read an automatic module (Vidocq refuses them in its own artifacts), so an automatic module
always lands in the application layer.

One implementation serves every launch shape: a `LayerSplit` helper in `vidocq-runtime-core`, used by the Maven
plugin at build time and by `VidocqAppLayer` for the trampoline. Vauban's `VaubanLayerFactory.applicationPaths`
gains the matching option (vauban#154): keep the closure of the given roots. It still keeps automatic modules: in
the trampoline they already sit in the boot layer (section 4), and the build-time split handles them for the
other launch shapes.

### 2. Escape hatch: modules that must stay in the parent layer

Some lookups cannot see a child layer: section 5 lists them. The default answer is to **fix the lookup**, so that
the brick uses the layer's loader. While a brick is not fixed, or for a third-party case, a module can be pinned to
the parent layer:

- by a brick, through the manifest attribute `Vidocq-Parent-Layer: <module or artifact patterns>`, read by the
  build like `Vidocq-Scan-Dependencies`;
- by the application, through `<vidocq.app.layer.keep>` (comma-separated patterns).

A pinned module drags its own `requires` closure with it, and the build checks that it does not read an
application module, failing with a message that names both.

### 3. The setting and the rollout

`vidocq.app.layer` = `classes` (today: the project's classes only) or `all` (this design). It is read by
`vidocq:run`, `vidocq:dev`, `vidocq:package` and, as `-Dvidocq.app.layer`, by the trampoline.

- **Step 1**: ship `all` behind the setting, with `classes` as the default.
- **Step 2**: run the validation campaign (below) with `all`.
- **Step 3**: as soon as the campaign is green, make `all` the default, with an entry in the migration guide.
  Steps 2 and 3 belong to this work (criterion 7). From then on, `classes` is only an escape hatch.

### 4. Each launch shape with `all`

- **`vidocq:run` / `vidocq:dev`**: `ApplicationLaunch.appPath` returns `target/classes` plus every
  application-layer jar, enriched copy first. `modulePath` returns the parent-layer jars only.
- **`vidocq:dev` reload**: the whole application layer is rebuilt, libraries included. The reload time is measured
  before and after, on the Servlet example and on a Vaadin application, and recorded in BENCH.md. The archive
  readers of unchanged jars are cached across reloads, as part of this delivery (criterion 3). A separate loader
  is not an option: it would bring back the Vaadin problem.
- **IDE / trampoline**: `VidocqAppLayer.applicationPaths` uses `LayerSplit` instead of the prefix list. Foy then
  needs no prefix. **Constraint**: here every dependency was already resolved into the boot layer. An
  **automatic** module re-created in the child layer would read its own twin in the parent layer, and
  resolution fails, which is why Vauban keeps automatic modules today. Explicit modules shadow their twins
  fine. So the trampoline can move an automatic library only once it has an explicit descriptor:
  `vidocq:generate` gives one to every application-layer automatic jar, in its enriched copy (the
  `Modularizer.synthesizeOne` path that enriched jars already use). Until then, the docs say that a plain IDE
  run of `main` keeps automatic libraries in the boot layer, and that the `(dev)` configuration does not have
  this limit.
- **`vidocq:package`**: `lib/` holds the parent layer, `app/` the application and its libraries. The layer script
  passes every `app/` entry in `-Dvidocq.app.path`.
- **`vidocq:jlink`, jpackage, docker**: unchanged in step 1. Every module of an image is served from `jrt:`, and
  Vauban already re-layers `jrt:` modules (`archiveOf`), but `explicitPaths` keeps `file:` locations only. That
  is a follow-up issue, and the docs say that an image still runs the application in one layer with its
  libraries in the image.

### 5. Lookups that must use the layer's loader

From a survey of origin/main on 2026-10-13. Single-argument `ServiceLoader.load` and the TCCL already see the
layer: the Vidocq extensions, config sources, mount configs, Mansart dialects, Foy initializers and Cassini.
These do not:

| Call site | Problem with `all` | Fix |
|---|---|---|
| Mansart `ConnectionPool` (`DriverManager.getConnection`), `LiquibaseSchemaMigrator` | `DriverManager` rejects a driver its caller's loader cannot see: "No suitable driver" | Load `java.sql.Driver` through `ServiceLoader` with the layer's loader and call `Driver.connect`, as servlet containers do. Mansart issue to open |
| `MigrationExtension` (`ServiceLoader.load(SchemaMigrator.class, MigrationExtension.class.getClassLoader())`) | a migrator shipped by the application is not found | the TCCL |
| dev console `CdiInventory` (`ModuleLayer.boot()`) | misses application modules that moved | the installed application layer |
| `DevServiceManager` (its own loader, host side) | none: runs in the Maven JVM | — |

### 6. Vauban's loader on jars it never saw

- **Multi-release jars**: `BuiltInReaders.JarReader` opens jars with a plain `JarFile` and indexes
  `META-INF/versions/N/…` under raw names, so the base version of a class always wins. This blocks `all` from
  becoming the default: Jackson, for example, ships versioned classes. Fix in vauban#154: open jars as
  multi-release for the running feature version, in both the class index and resource lookup.
- **Signed jars**: no `CodeSigner` and no verification. Enriched copies already drop signatures. Step 1 documents
  that signature checking does not apply in the layer; verification is a separate issue if anyone needs it.
- **Transformers**: the cdi-proxifier must leave a library class alone unless that class is a bean or a produced
  type. A test checks that a non-bean library class is defined byte-for-byte as read.

### 7. Startup report

The `layer` section prints the number of application libraries and, in the detailed report, one row per archive,
as it does for the application's modules today. Libraries pinned to the parent layer are listed with the reason:
which brick's manifest, or `vidocq.app.layer.keep`.

## Tests, one per risk

1. **A library read by a brick and by the application** stays in the parent layer, once. The application
   gets the same `Class`.
2. **A JDBC driver in the application layer**: with the Mansart fix, the pool connects. Without it, the error
   names the driver and the fix.
3. **Reload with a library that starts a thread**: the reload completes, and the startup report warns about the
   leaked thread. Also a reload with a JNI library: it is documented as a known limit, and keeping it in the parent
   layer is the workaround.
4. **A multi-release jar**: the versioned class is the one defined.
5. **A non-bean library class** is not transformed.
6. **`LayerSplit`**: unit tests on synthetic module graphs. An automatic module goes to the application layer, a
   brick's dependency to the parent layer. A pinned module drags its closure, and a pinned module that reads an
   application module fails.
7. **Launch shapes**: an invoker IT per shape (`run`, `dev`, `package`) with `all`, on a fixture holding a
   flat-classpath library that looks up an application resource through its own loader. That is a minimal Vaadin
   stand-in.

## Validation campaign before `all` becomes the default

Run in a fresh local Maven repository, with `vidocq.app.layer=all` forced:

- the 8 TCK runners on the assembled runtime;
- every example and every integration test;
- the dev console, and the dev reload on the Servlet example and the Mansart example;
- `vidocq:package` with its scripts;
- the Vaadin example, once vidocq#246 and foy#43 land.

## Out of scope

- jlink, jpackage and docker images in the application layer (follow-up issue).
- Verifying jar signatures in the layer.
- Generators on dependency jars: vidocq#246, foy#43, mansart#40.

## Order of work

1. vauban#155 (code source), already in auto-merge.
2. vauban#154: the policy option (keep the roots' closure) and multi-release jars,
   with tests 4 and 5.
3. Mansart: the `DriverManager` fix (test 2), and the other lookups of section 5.
4. vidocq#247: `LayerSplit`, the setting, `run`/`dev`/`package`/trampoline, the escape hatch, the report, and
   tests 1, 3, 6 and 7.
5. The reload cache, measured against its target (criterion 3).
6. With Vidocq/vidocq#246: explicit descriptors for application-layer automatic jars (trampoline
   constraint, section 4), generation on dependency jars, once per jar version (criterion 4), the one-line
   diagnostics (criterion 5) and the startup report line (criterion 6).
7. The campaign, then `all` becomes the default (criterion 7).
