# BUG — Vidocq Runtime

Tracking reproducible bugs in the Vidocq runtime (orchestrator + extension wrappers).
Vidocq workspace convention: short id, date, symptom, minimal repro, cause hypothesis, status.

---

## BUG-20261006-02 — an application with its own version resolves every Vidocq artifact at that version

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/parent-literal-versions` for the parent, `feat/cli-update` for the CLI)
- **Module touché** : vidocq-runtime-parent (root pom), vidocq-runtime-cli
- **Symptôme** : an application that declares `<version>1.0.0</version>` under
  `vidocq-runtime-parent` cannot build: Maven looks for `vidocq-runtime-maven-plugin:1.0.0`, and
  for `vidocq-runtime-cassini-rest-extension:1.0.0` when the dependency carries no version.
  `vidocq extension add` wrote dependencies without a version and the codegen path with
  `${project.version}`, so it hit the same wall; `extension remove` left the codegen path behind.
- **Reproduction minimale** : `vidocq create --name app`, add `<version>1.0.0</version>`, then
  `mvn help:effective-pom` — every managed `io.vidocq.*` artifact, and the plugin, is at `1.0.0`.
- **Hypothèse de cause** : the parent's `dependencyManagement` and `pluginManagement` declare
  Vidocq artifacts at `${project.version}`, which Maven interpolates in the child: the deployed
  parent pom still carries the expression, so it becomes the application's own version.
- **Correction** : CLI side, `extension add` pins the parent's version on the dependency and on
  the codegen path, and `extension remove` unwires the codegen path (and the compiler plugin block
  `add` created). Parent side, the 48 dependencyManagement and pluginManagement entries name the
  version literally, which versions:set (run by the release) rewrites; the core codegen processor
  path has no version and takes it from dependencyManagement. An application at 1.0.0 packages.

## BUG-20261006-04 — `vidocq dev` and `vidocq start` ran an empty runtime

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/cli-dev`)
- **Module touché** : vidocq-runtime-cli, docs
- **Symptôme** : in a project where `mvn vidocq:dev` serves its endpoints, `vidocq dev` logged
  "No Vidocq extensions discovered" and "no application layer", and nothing answered on the port;
  `vidocq start` likewise.
- **Reproduction minimale** : `vidocq create --name todo -x cassini-rest`, add a resource, then
  `vidocq dev` and `curl localhost:8080/<path>`.
- **Hypothèse de cause** : both commands booted `VidocqBootstrap` inside the CLI's own JVM, whose
  module path holds neither the project's classes nor its dependencies and extensions.
- **Correction** : `vidocq dev` runs `mvn process-classes vidocq:dev` and `vidocq start` runs
  `mvn vidocq:run` (`./mvnw` when present), passing `--port` as the canonical listener key,
  `--profile`, `--config` and `--debug` to the goals. The CLI waits for Maven on Ctrl+C. The
  in-JVM watcher, spinner and profile layering (`io.vidocq.runtime.cli.dev`) are gone.

## BUG-20261006-03 — the CLI's HTTP clients never ran a request

- **Date** : 2026-10-06
- **Statut** : FIXED (`feat/cli-update`)
- **Module touché** : vidocq-runtime-cli
- **Symptôme** : every request of `HttpRegistryFetcher` timed out, so `extension list --available`
  always fell back to the built-in catalog — unnoticed while the remote registry does not exist.
- **Hypothèse de cause** : `.executor(Thread.ofVirtual().factory()::newThread)` creates a thread
  per task but never starts it.
- **Correction** : `.executor(task -> Thread.ofVirtual().start(task))`, covered by a test against a
  local `HttpServer` (`HttpDownloadsTest`).

## BUG-20261006-01 — `extension list`, `info` and `doctor` never see the project's extensions

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/cli-installed-extensions`)
- **Module touché** : vidocq-runtime-cli, docs
- **Symptôme** : in a project scaffolded with `vidocq create --name todo -g com.acme -x cassini-rest`,
  whose `pom.xml` declares `vidocq-runtime-cassini-rest-extension` and whose distribution ships
  Cassini and Chappe, `vidocq extension list` prints `(none found on classpath)`, `vidocq info`
  `(none — add extension JARs to the classpath)` and `vidocq doctor` warns "no extensions on the
  classpath" — the user concludes the scaffold dropped the extension. The doctor hint also
  suggested `vidocq extension add rest`, an id that does not exist.
- **Reproduction minimale** : `vidocq create --name todo -x cassini-rest && cd todo && vidocq extension list`.
- **Hypothèse de cause** : the three commands ran `ServiceLoader.load(VidocqExtension.class)` in the
  CLI's own JVM. The CLI ships no extension and the project's dependencies are not on its module
  path, so the answer was always empty.
- **Correction** : `ProjectExtensions` resolves the project's runtime classpath with
  `maven-dependency-plugin:list` (transitive) and keeps the JARs whose module provides the
  extension SPI (`ModuleFinder`, explicit or automatic module). Direct dependencies are told apart
  from transitive ones (`chappe-webserver` under `cassini-rest`); a Maven failure is reported, not
  hidden as "no extension". The hint now names `cassini-rest`. The answer is cached per project
  (`ExtensionCache`): final for a release-only resolution, re-checked against the local SNAPSHOT
  files otherwise, bypassed by `vidocq extension list --refresh`.

## BUG-20261001-01 — `@Inject @ConfigProperty` fails the compilation of a Vidocq application (ravel#21)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/ravel-config-codegen-bundle`, with vauban `fix/extension-build-time-signal` and ravel `fix/config-values-checked-at-container-start`)
- **Affected module**: vidocq-runtime-ravel-config-extension (no codegen bundle), vidocq-runtime-cli
- **Symptom**: reported by Sébastien Blanc on 0.3.0 (Vidocq/ravel#21): an application depending on
  `vidocq-runtime-ravel-config-extension` cannot inject `@Inject @ConfigProperty String` — the Vauban
  processor reports "Unsatisfied dependency" and the only way out is `-Avauban.validation=false`, which
  turns off every compile-time check of the module. Resolving
  `vidocq-runtime-ravel-config-extension-codegen` from Central failed: it never existed.
- **Minimal repro**: `vidocq-runtime-integration-tests/vidocq-runtime-it-ravel-config` without
  the bundle on its processor path.
- **Cause hypothesis**: nothing put Ravel's Build Compatible Extension on the processor path, so the
  beans it synthesises for `@ConfigProperty` points did not exist when the processor validated.
- **Correction**: new `vidocq-runtime-ravel-config-extension-codegen` bundle (→ `ravel-cdi-vauban`),
  known to the CLI (`KnownExtensions.codegenBundle("ravel-config")`) and therefore required by
  `checkpom`; added to the two examples and five TCK runners that declare the extension. The IT
  compiles with validation on and boots, injecting both the value and `Config`.

## BUG-20260809-01 — the documented `vidocq.http.port` was read by nobody

- **Date**: 2026-08-09
- **Status**: FIXED (2026-08-09, `pr/ybl/vidocq-http-port-alias`)
- **Affected module**: vidocq-runtime-chappe-webserver-extension, vidocq-runtime-cli,
  vidocq-runtime-core, docs
- **Symptom**: an application setting `vidocq.http.port` silently stayed on 8080. The key is
  published everywhere — reference documentation, getting-started, the scaffolded
  `vidocq.properties`, the blog tutorial — but the only key the extension read was
  `vidocq.chappe.listener.<name>.port`. Invisible whenever the configured value happened to *be*
  the default, which in a quickstart setting it to 8080 it always is.
- **Minimal repro**:
  ```properties
  # src/main/resources/vidocq.properties, nothing else set
  vidocq.http.port=9099
  ```
  ```
  $ ./bin/app.sh && curl http://127.0.0.1:9099/   # connection refused; the app is on 8080
  ```
- **Cause hypothesis**: the key was documented before the multi-listener configuration landed,
  and nothing tied the reference table to the keys the code actually reads.
- **Investigations**:
  - 2026-08-09 : reported by Sébastien Blanc (Vidocq/chappe#7 — filed on the chappe repo, but
    Chappe knows nothing about `vidocq.*` keys: the defect is in the runtime extension). Found
    while building Rossignol, where the inert setting was carried for weeks.
  - Scope was wider than reported. (a) `vidocq start --port` / `vidocq dev --port` published
    `vidocq.http.port` as a system property, so **the CLI's own `--port` option was inert too**.
    (b) The scaffolder writes the key into every generated project. (c) The reference table
    documented three further families read by nobody: `vidocq.https.port` + `vidocq.tls.*`
    (TLS is not implemented) and `vidocq.datasource.*` (the real prefix is `vidocq.pool.*`).
    (d) `vidocq.http.mount.*` *was* live, so the `vidocq.http.` namespace was half-claimed —
    which is precisely why the silence read as normal.
  - Fixed by making `vidocq.http.{host,port}` a declared alias of the `default` listener
    (the explicit listener key still wins), by publishing the canonical listener key from the CLI
    and only when `--port` was actually typed (otherwise the CLI default would outrank the
    project's own configuration), and by correcting the documentation.
  - Generalised, as the reporter suggested: `VidocqExtension.configKeys()` lets an extension
    declare the keys it consumes, and `ConfigKeyAudit` warns at startup about any configured
    `vidocq.*` key that no extension reads. Opt-in per namespace, so an extension that declares
    nothing never produces a false warning.

## VID-001 — Cervantes JWT extension wrapper BCE not openable on the module path

- **Opening date**: 2026-06-02
- **Status**: ✅ FIXED 2026-06-02

### Symptom

Strict module-path boot (Arago Docker) fails:

```
BCE processing failed for io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension:
class io.vidocq.vauban.core.extensions.BceProcessor (in module io.vidocq.vauban.core) cannot access
... because module io.vidocq.runtime.ext.cervantes.jwt does not export io.vidocq.runtime.ext.cervantes
to module io.vidocq.vauban.core
```

Does not reproduce on the class-path (the cervantes acceptance/TCK run there).

### Cause

The wrapper module `io.vidocq.runtime.ext.cervantes.jwt` declares its own
`BuildCompatibleExtension` in the internal package `io.vidocq.runtime.ext.cervantes` and registers it
via `provides ... with`. vauban-core's `BceProcessor` instantiates BCEs by direct reflection
(`getDeclaredConstructor().newInstance()`), which needs the hosting package **opened** to vauban-core;
`provides ... with` alone only grants `ServiceLoader` access. The package was neither exported nor opened.

### Fix

`vidocq-runtime-cervantes-jwt-extension/module-info`:
`opens io.vidocq.runtime.ext.cervantes to io.vidocq.vauban.core;` (qualified — mirrors knock/dirac).

Verified: Arago Docker boots; cervantes MP-JWT 2.1 TCK 206/206 PASS.

### Related — cyrano wrapper: ✅ FIXED 2026-06-11

`vidocq-runtime-cyrano-rest-client-extension` had the **same** shape (own BCE in a non-opened
package). Fixed with the same one-line `opens io.vidocq.runtime.extensions.microprofile.cyrano to
io.vidocq.vauban.core` — the compile-graph concern recorded earlier did not materialize:
`io.vidocq.vauban.core` is reachable transitively through `io.vidocq.cyrano.cdi.vauban`, exactly as
in the cervantes wrapper, and the opens compiles as-is.

**Verified end-to-end this time**: new reactor module `vidocq-runtime-it-cyrano-jpms` boots the
Vidocq runtime with the wrapper ON THE MODULE PATH (main module-info → surefire module path, plus a
named-module sentinel against silent class-path degradation). The vehicle was proven by mutation:
without the opens the boot test fails with the exact VID-001 symptom; with it, green. This also
gives the runtime its first module-path IT — every other integration test runs on the class path,
where this whole bug class is invisible.

## BUG-20260612-01 — Invalid 0.2.0-SNAPSHOT pom of vidocq-runtime-cassini-rest-extension on central-snapshots

- **Date**: 2026-06-12
- **Status**: CLOSED 2026-10-07 — obsolete
- **Affected module**: vidocq-runtime-cassini-rest-extension (published snapshot, timestamp 0.2.0-20260608.152249-5)
- **Symptom**: any out-of-reactor consumer resolving the published snapshot gets
  "The POM ... is invalid, transitive dependencies (if any) will not be available:
  'dependencies.dependency.version' for io.vidocq.runtime:vidocq-runtime-chappe-webserver-extension:jar is missing"
  → transitive cassini jars silently dropped → `ClassNotFoundException: io.vidocq.cassini.spi.bean.BeanProvider`
  (grimm-tck: 29 Arquillian deployment failures / 719 skips). The resolver PREFERS the remote
  timestamped snapshot over the locally installed one, so a local `mvn install` does not help.
- **Minimal repro**:
  ```
  rm -rf ~/.m2/repository/io/vidocq/runtime
  cd grimm && ./run-official-tck-mp-openapi-4.1.sh all     # before the grimm-tck workaround
  ```
- **Cause hypothesis**: the snapshot published on 2026-06-08 predates the groupId/dependencyManagement
  fix of the chappe-webserver-extension dependency (the current source pom uses the managed
  io.vidocq.runtime.extensions.essentials groupId and is valid). Republishing a fresh snapshot
  via the vidocq CI publish job should fix all consumers.
- **Investigations**:
  - 2026-06-12 : root-caused while re-validating grimm-tck on 0.2.0 jars (frozen-runner trap, CG-06).
    Contained workaround committed in grimm-tck/pom.xml (lost transitives declared explicitly) —
    remove it once a valid snapshot is republished.
  - 2026-10-07 : replayed with a throw-away consumer and an empty local repository (`-Dmaven.repo.local`) against
    central-snapshots: `vidocq-runtime-cassini-rest-extension` 0.4.0-SNAPSHOT (`0.4.0-20261007.171041-82`) resolves
    with no "POM is invalid" warning, and its four cassini jars come transitively. The grimm-tck workaround (cassini
    jars declared by hand) can go; done in grimm.

## BUG-20260704-01 — Released CLI 0.2.0 reports "Vidocq CLI 0.2.0-SNAPSHOT" for --version

- **Date**: 2026-07-04
- **Status**: FIXED (a9d3a59 on main, 9a13640 on hotfix/0.2.1-cli — ships with CLI 0.2.1)
- **Affected module**: vidocq-runtime-cli (version banner)
- **Symptom**: the jar published on Maven Central as `io.vidocq.runtime:vidocq-runtime-cli:0.2.0`
  prints `Vidocq CLI 0.2.0-SNAPSHOT` when invoked with `--version`.
- **Minimal repro**:
  ```
  # pristine local repo, resolve from Central only
  mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=modules \
      -Dmaven.repo.local=$(mktemp -d)   # pom with a single dep on vidocq-runtime-cli:0.2.0
  java -p modules -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli --version
  # → Vidocq CLI 0.2.0-SNAPSHOT
  ```
- **Cause hypothesis**: the version string is not derived from the pom at build time
  (hardcoded constant, or a resource that is not filtered), so the release build — which
  runs `versions:set 0.2.0` on the release branch before packaging — does not update it.
- **Investigations**:
  - 2026-07-04 : found while validating the Central-based CLI install recipe for the blog
    tutorial (pages PR #2). Cosmetic only — the artifact itself is the correct 0.2.0 build.
    Central is immutable, so 0.2.0 will keep the wrong banner; fix for 0.2.1+.
  - 2026-07-10 : root cause confirmed — hardcoded `VidocqCli.VERSION` constant, also used
    for the `<parent><version>` of every scaffolded pom (the actual issue #3 wall: generated
    projects referenced an unresolvable 0.2.0-SNAPSHOT parent). Fixed by deriving
    cliVersion/runtimeVersion from a Maven-filtered version.properties; scaffold now emits
    the runtime parent version. Ships with CLI 0.2.1 (hotfix/0.2.1-cli).

## BUG-20260710-01 — vidocq:package 0.2.0 NPEs when jvmArgs is not configured

- **Date**: 2026-07-10
- **Status**: FIXED (2026-08-09, `pr/ybl/vidocq-40-jvmargs-npe`)
- **Affected module**: vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptom**: `Cannot invoke "String.isBlank()" because "this.jvmArgs" is null` — the
  `package` goal fails on any pom that does not set `<jvmArgs>` explicitly. An empty
  `<jvmArgs></jvmArgs>` element does NOT help (Plexus maps both the absent element and
  `defaultValue = ""` to null).
- **Minimal repro**:
  ```
  vidocq create --name demo -g com.acme   # scaffold without <jvmArgs>
  cd demo && mvn package                   # with the plugin's package goal wired
  ```
- **Cause hypothesis**: `@Parameter(defaultValue = "")` yields null under Maven/Plexus;
  the mojo dereferences `jvmArgs.isBlank()` without a null guard.
- **Investigations**:
  - 2026-07-10 : found while validating the scaffolded-project E2E flow for the
    getting-started rewrite (issue #3). Released 0.2.0 is immutable — the scaffold now
    emits `<jvmArgs>-Dfile.encoding=UTF-8</jvmArgs>` as a workaround. Fix the null guard
    on main for the next plugin release.
  - 2026-08-09 : reported again from the outside by Sébastien Blanc (Vidocq/vidocq#40),
    hit on a from-scratch project built for a separate Vauban reproducer — i.e. on the
    exact path a new user takes. Fixed on main: both launcher generators now read the
    parameter through a null-safe `jvmArgsLine()`, and `VidocqPackageMojoTest` pins the
    absent / blank / configured cases. Swept the rest of the plugin for the same
    `defaultValue = ""` trap: `VidocqDevMojo.extraJvmArgs` and `extraSystemProperties`
    were already null-guarded (`splitArgs`), so `package` was the only faulty site.
    The scaffold keeps emitting `<jvmArgs>` while it targets the released 0.2.x plugin;
    drop that workaround (and its assertion in `ProjectScaffolderExtensionsTest`) in the
    commit that bumps the scaffolded version to 0.3.0.

## BUG-20260710-02 — vidocq:package 0.2.0 launcher uses --module <mainClass> without the module name

- **Date**: 2026-07-10
- **Status**: FIXED (vidocq#198, branch `pr/ybl/packaging-launchers`)
- **Affected module**: vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptom**: the generated `bin/<app>.sh` launcher runs
  `java --module-path lib --module <mainClass>` — with the default or a plain class name
  this fails at boot with `FindException: Module <mainClass> not found`. The mojo ignores
  `vidocq.mainModule` (used by vidocq:dev/jlink) and passes `mainClass` verbatim where the
  JVM expects `module/class`.
- **Minimal repro**:
  ```
  mvn package   # with vidocq:package wired and vidocq.mainClass=com.acme.demo.DemoApp
  sh target/demo-0.2.0/bin/demo.sh
  ```
- **Cause hypothesis**: the script template concatenates `--module ${mainClass}`
  instead of `--module ${mainModule}/${mainClass}`.
- **Investigations**:
  - 2026-07-10 : found together with BUG-20260710-01. Workaround baked into the scaffold:
    `<mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>` on the package
    execution. Align the mojo with vidocq:dev/jlink (separate mainModule parameter) on main.
  - 2026-10-07 : narrowed. The default layout (`vidocq.package.layer=true`) launches the runtime with
    `-Dvidocq.app.path`, or the `@VidocqMain` trampoline as `<mainModule>/<mainClass>`, and is correct. The legacy
    layout (`vidocq.package.layer=false`) still wrote `--module <mainClass>`, which failed even with the default
    main class (`--module io.vidocq.runtime.core.Vidocq`).
- **Fix**: `VidocqPackageMojo#legacyModuleRef` gives the legacy launchers `io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq`
  by default, `<mainModule>/<mainClass>` for an application main class, keeps a `module/class` value as is, and fails
  the build, naming `vidocq.mainModule`, for a plain class without it. `VidocqPackageMojoTest` (three tests, failing
  first).

## BUG-20260711-01 — extension add does not wire the codegen bundle, next build fails checkpom

- **Date**: 2026-07-11
- **Status**: FIXED (fix/extension-add-codegen — ships with CLI 0.2.2)
- **Affected module**: vidocq-runtime-cli / CommandRunner + PomEditor
- **Symptom**: `vidocq extension add cassini-rest` adds the dependency but not the
  `vidocq-runtime-cassini-rest-extension-codegen` annotationProcessorPaths entry; since the
  0.2.1 scaffold wires the vidocq plugin (checkpom active), the next `vidocq build` /
  `mvn package` fails: "codegen bundle ... is missing from annotationProcessorPaths".
  Breaks the published blog tutorial flow (create → extension add cassini-rest → build).
- **Minimal repro**:
  ```
  vidocq create --name hello --group-id com.example
  cd hello && vidocq extension add cassini-rest && vidocq build   # checkpom FAIL
  ```
- **Cause hypothesis**: `extension add` only edits `<dependencies>`; the APT wiring rule
  introduced with the 0.2.1 scaffold was not mirrored there.
- **Investigations**:
  - 2026-07-11 : found by replaying the published blog tutorial block-by-block in a clean
    container. Fixed: PomEditor.addAnnotationProcessorPath (append into existing APT block,
    create the compiler plugin block or the whole build section when absent, idempotent)
    wired into `extension add` via KnownExtensions.codegenBundle. Verified E2E: patched
    add + `vidocq build` passes on a 0.2.1-scaffolded project.

## BUG-20260711-02 — vidocq:docker wraps the host-platform jlink image (broken container on macOS)

- **Date**: 2026-07-11
- **Status**: FIXED (vidocq#199, branch `pr/ybl/packaging-launchers`): detected and reported; cross-linking is not supported
- **Affected module**: vidocq-runtime-maven-plugin / VidocqDockerMojo
- **Symptom**: on macOS, `vidocq build jlink` produces a Mach-O arm64 runtime; `vidocq build
  docker` then generates a Dockerfile that COPYs that dist into a Linux base image — the
  resulting container fails at start with `exec /opt/app/bin/todo: no such file or directory`.
  Also note the goal only GENERATES the Dockerfile and prints the `docker build` command
  ("Build with: …") despite logging "Building Docker image…".
- **Minimal repro**:
  ```
  # on macOS
  vidocq build jlink && vidocq build docker
  docker build -t app -f target/Dockerfile target && docker run --rm app   # exec format error
  ```
- **Cause hypothesis**: jlink links against the host's jmods; the mojo neither cross-links
  with Linux jmods nor warns when host OS ≠ linux.
- **Investigations**:
  - 2026-07-11 : found while replaying the published tutorials end to end. Options: warn on
    non-linux hosts, document the CI-only expectation, or support --jmods cross-linking.
  - 2026-10-07 : the misleading "Building Docker image…" log was already gone (the goal says "Dockerfile generated").
    The platform problem remained: on macOS the cassini example's `target/dist/bin/java` is `Mach-O 64-bit
    executable arm64`, and the goal wrapped it without a word.
- **Fix**: `VidocqDockerMojo#nonLinuxPlatform` reads the header of the image's `bin/java` (`java.exe`): ELF passes;
  Mach-O or PE gets a WARNING that names the platform and says the container will not start, and the Dockerfile is
  still generated (failing would break `mvn install` on macOS for every project with the `dist` profile); with
  `vidocq.docker.build=true` the goal fails before running `docker build`. Documented in the plugin page.
  `VidocqDockerMojoTest` (failing first). Linking against Linux jmods from another OS stays out of scope.

## BUG-20260815-01 — jlink launcher requires an explicit `exports … to io.vidocq.runtime.core`

- **Date**: 2026-08-15
- **Status**: CLOSED 2026-10-07 — no longer reproduces on the default launch; residual noted below
- **Affected module**: vidocq-runtime-core / `Vidocq.instantiateInLayer` + `VidocqAppLayer.exportToRuntime`
- **Symptom**: an application packaged with `vidocq:jlink` and started through the generated
  launcher fails at boot unless its main package is exported to the runtime by hand. The same
  application boots under `vidocq:dev` and under surefire without that edge:
  ```
  Exception in thread "main" java.lang.IllegalStateException: Application …ExampleApp failed
  Caused by: java.lang.IllegalAccessException: class io.vidocq.runtime.core.Vidocq
    cannot access class io.vidocq.experiments.lc4j.ExampleApp … because module
    io.vidocq.experiments.lc4j does not export io.vidocq.experiments.lc4j to module io.vidocq.runtime.core
  ```
- **Minimal repro** (`langchain4jcdi-experiment/langchain4jcdi-example`, commit b0470bc):
  ```
  # remove `exports io.vidocq.experiments.lc4j to io.vidocq.runtime.core;` from module-info.java
  ./mvnw -o -pl langchain4jcdi-example clean package
  target/dist/…/lc4j-app        # boot failure, trace above
  ```
- **Cause hypothesis**: the jlink launcher boots the application module on the **boot layer**.
  `Vidocq.instantiateInLayer` (`Vidocq.java:149-165`) grants itself reflective access through
  `VidocqAppLayer.exportToRuntime`, but that method is guarded by
  `if (layer != null && layerClass.getModule().getLayer() == layer.layer())`
  (`VidocqAppLayer.java:210-216`) — true only when the class was re-layered into the Vauban
  application layer. In a jlink image it never is, so the call is a no-op and the export is missing.
- **Investigations**:
  - 2026-08-15: found while validating the LangChain4j-CDI integration end to end (task B4 report,
    section "A third edge the jlink launcher forced"). Worked around application-side with a
    qualified `exports … to io.vidocq.runtime.core;` — an export suffices, class and constructor
    are public. The reference `vidocq-runtime-cassini-rest-example` declares no such edge and is
    likely affected too. Proper fix: make the boot-layer case explicit — either grant the access
    without the re-layering guard (`addExports` on the boot-layer module) or have `vidocq:jlink`
    fail loudly with the required directive rather than at run time.
  - 2026-10-07 : replayed on main `b3dfc30a` with `vidocq-runtime-cassini-rest-example`, whose `module-info` exports
    only its `model` package (no `exports … to io.vidocq.runtime.core`): the jlink image (`-m
    io.vidocq.runtime.examples.rest/…RestExampleApp`) logs `Application layer ready … (boot-layer detection from
    io.vidocq.runtime.examples.rest)`, starts in 121 ms and answers `GET /api/todos` with 200. `Vidocq.run()` now
    re-layers the application from the boot layer, also inside a jlink image (vauban BUG-20260912-02), so
    `exportToRuntime`'s guard holds and the export is granted.
  - Residual: when no application module is found (`VidocqAppLayer` returns before `installLayer`, e.g. an empty
    `applicationPaths`), the application stays in the boot layer and the same `IllegalAccessException` comes back,
    with no hint. Not seen on any launch path we ship.
  - 2026-10-07 : the residual now fails with a message that names the class, the package and both ways out (an
    `exports … to io.vidocq.runtime.core;` or a CDI bean): `Vidocq.inaccessibleApplicationClass`, vidocq#201,
    `BootLayerAccessTest`.

