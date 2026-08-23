# BUG — Vidocq Runtime

Tracking reproducible bugs in the Vidocq runtime (orchestrator + extension wrappers).
Vidocq workspace convention: short id, date, symptom, minimal repro, cause hypothesis, status.

---

## BUG-20260809-01 — the documented `vidocq.http.port` was read by nobody

- **Date** : 2026-08-09
- **Statut** : FIXED (2026-08-09, `pr/ybl/vidocq-http-port-alias`)
- **Module touché** : vidocq-runtime-chappe-webserver-extension, vidocq-runtime-cli,
  vidocq-runtime-core, docs
- **Symptôme** : an application setting `vidocq.http.port` silently stayed on 8080. The key is
  published everywhere — reference documentation, getting-started, the scaffolded
  `vidocq.properties`, the blog tutorial — but the only key the extension read was
  `vidocq.chappe.listener.<name>.port`. Invisible whenever the configured value happened to *be*
  the default, which in a quickstart setting it to 8080 it always is.
- **Reproduction minimale** :
  ```properties
  # src/main/resources/vidocq.properties, nothing else set
  vidocq.http.port=9099
  ```
  ```
  $ ./bin/app.sh && curl http://127.0.0.1:9099/   # connection refused; the app is on 8080
  ```
- **Hypothèse de cause** : the key was documented before the multi-listener configuration landed,
  and nothing tied the reference table to the keys the code actually reads.
- **Investigations** :
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

- **Date** : 2026-06-12
- **Statut** : OPEN
- **Module touché** : vidocq-runtime-cassini-rest-extension (published snapshot, timestamp 0.2.0-20260608.152249-5)
- **Symptôme** : any out-of-reactor consumer resolving the published snapshot gets
  "The POM ... is invalid, transitive dependencies (if any) will not be available:
  'dependencies.dependency.version' for io.vidocq.runtime:vidocq-runtime-chappe-webserver-extension:jar is missing"
  → transitive cassini jars silently dropped → `ClassNotFoundException: io.vidocq.cassini.spi.bean.BeanProvider`
  (grimm-tck: 29 Arquillian deployment failures / 719 skips). The resolver PREFERS the remote
  timestamped snapshot over the locally installed one, so a local `mvn install` does not help.
- **Reproduction minimale** :
  ```
  rm -rf ~/.m2/repository/io/vidocq/runtime
  cd grimm && ./run-official-tck-mp-openapi-4.1.sh all     # before the grimm-tck workaround
  ```
- **Hypothèse de cause** : the snapshot published on 2026-06-08 predates the groupId/dependencyManagement
  fix of the chappe-webserver-extension dependency (the current source pom uses the managed
  io.vidocq.runtime.extensions.essentials groupId and is valid). Republishing a fresh snapshot
  via the vidocq CI publish job should fix all consumers.
- **Investigations** :
  - 2026-06-12 : root-caused while re-validating grimm-tck on 0.2.0 jars (frozen-runner trap, CG-06).
    Contained workaround committed in grimm-tck/pom.xml (lost transitives declared explicitly) —
    remove it once a valid snapshot is republished.

## BUG-20260704-01 — Released CLI 0.2.0 reports "Vidocq CLI 0.2.0-SNAPSHOT" for --version

- **Date** : 2026-07-04
- **Statut** : FIXED (a9d3a59 on main, 9a13640 on hotfix/0.2.1-cli — ships with CLI 0.2.1)
- **Module touché** : vidocq-runtime-cli (version banner)
- **Symptôme** : the jar published on Maven Central as `io.vidocq.runtime:vidocq-runtime-cli:0.2.0`
  prints `Vidocq CLI 0.2.0-SNAPSHOT` when invoked with `--version`.
- **Reproduction minimale** :
  ```
  # pristine local repo, resolve from Central only
  mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=modules \
      -Dmaven.repo.local=$(mktemp -d)   # pom with a single dep on vidocq-runtime-cli:0.2.0
  java -p modules -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli --version
  # → Vidocq CLI 0.2.0-SNAPSHOT
  ```
- **Hypothèse de cause** : the version string is not derived from the pom at build time
  (hardcoded constant, or a resource that is not filtered), so the release build — which
  runs `versions:set 0.2.0` on the release branch before packaging — does not update it.
- **Investigations** :
  - 2026-07-04 : found while validating the Central-based CLI install recipe for the blog
    tutorial (pages PR #2). Cosmetic only — the artifact itself is the correct 0.2.0 build.
    Central is immutable, so 0.2.0 will keep the wrong banner; fix for 0.2.1+.
  - 2026-07-10 : root cause confirmed — hardcoded `VidocqCli.VERSION` constant, also used
    for the `<parent><version>` of every scaffolded pom (the actual issue #3 wall: generated
    projects referenced an unresolvable 0.2.0-SNAPSHOT parent). Fixed by deriving
    cliVersion/runtimeVersion from a Maven-filtered version.properties; scaffold now emits
    the runtime parent version. Ships with CLI 0.2.1 (hotfix/0.2.1-cli).

## BUG-20260710-01 — vidocq:package 0.2.0 NPEs when jvmArgs is not configured

- **Date** : 2026-07-10
- **Statut** : FIXED (2026-08-09, `pr/ybl/vidocq-40-jvmargs-npe`)
- **Module touché** : vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptôme** : `Cannot invoke "String.isBlank()" because "this.jvmArgs" is null` — the
  `package` goal fails on any pom that does not set `<jvmArgs>` explicitly. An empty
  `<jvmArgs></jvmArgs>` element does NOT help (Plexus maps both the absent element and
  `defaultValue = ""` to null).
- **Reproduction minimale** :
  ```
  vidocq create --name demo -g com.acme   # scaffold without <jvmArgs>
  cd demo && mvn package                   # with the plugin's package goal wired
  ```
- **Hypothèse de cause** : `@Parameter(defaultValue = "")` yields null under Maven/Plexus;
  the mojo dereferences `jvmArgs.isBlank()` without a null guard.
- **Investigations** :
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

- **Date** : 2026-07-10
- **Statut** : OPEN
- **Module touché** : vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptôme** : the generated `bin/<app>.sh` launcher runs
  `java --module-path lib --module <mainClass>` — with the default or a plain class name
  this fails at boot with `FindException: Module <mainClass> not found`. The mojo ignores
  `vidocq.mainModule` (used by vidocq:dev/jlink) and passes `mainClass` verbatim where the
  JVM expects `module/class`.
- **Reproduction minimale** :
  ```
  mvn package   # with vidocq:package wired and vidocq.mainClass=com.acme.demo.DemoApp
  sh target/demo-0.2.0/bin/demo.sh
  ```
- **Hypothèse de cause** : the script template concatenates `--module ${mainClass}`
  instead of `--module ${mainModule}/${mainClass}`.
- **Investigations** :
  - 2026-07-10 : found together with BUG-20260710-01. Workaround baked into the scaffold:
    `<mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>` on the package
    execution. Align the mojo with vidocq:dev/jlink (separate mainModule parameter) on main.

## BUG-20260711-01 — extension add does not wire the codegen bundle, next build fails checkpom

- **Date** : 2026-07-11
- **Statut** : FIXED (fix/extension-add-codegen — ships with CLI 0.2.2)
- **Module touché** : vidocq-runtime-cli / CommandRunner + PomEditor
- **Symptôme** : `vidocq extension add cassini-rest` adds the dependency but not the
  `vidocq-runtime-cassini-rest-extension-codegen` annotationProcessorPaths entry; since the
  0.2.1 scaffold wires the vidocq plugin (checkpom active), the next `vidocq build` /
  `mvn package` fails: "codegen bundle ... is missing from annotationProcessorPaths".
  Breaks the published blog tutorial flow (create → extension add cassini-rest → build).
- **Reproduction minimale** :
  ```
  vidocq create --name hello --group-id com.example
  cd hello && vidocq extension add cassini-rest && vidocq build   # checkpom FAIL
  ```
- **Hypothèse de cause** : `extension add` only edits `<dependencies>`; the APT wiring rule
  introduced with the 0.2.1 scaffold was not mirrored there.
- **Investigations** :
  - 2026-07-11 : found by replaying the published blog tutorial block-by-block in a clean
    container. Fixed: PomEditor.addAnnotationProcessorPath (append into existing APT block,
    create the compiler plugin block or the whole build section when absent, idempotent)
    wired into `extension add` via KnownExtensions.codegenBundle. Verified E2E: patched
    add + `vidocq build` passes on a 0.2.1-scaffolded project.

## BUG-20260711-02 — vidocq:docker wraps the host-platform jlink image (broken container on macOS)

- **Date** : 2026-07-11
- **Statut** : OPEN
- **Module touché** : vidocq-runtime-maven-plugin / VidocqDockerMojo
- **Symptôme** : on macOS, `vidocq build jlink` produces a Mach-O arm64 runtime; `vidocq build
  docker` then generates a Dockerfile that COPYs that dist into a Linux base image — the
  resulting container fails at start with `exec /opt/app/bin/todo: no such file or directory`.
  Also note the goal only GENERATES the Dockerfile and prints the `docker build` command
  ("Build with: …") despite logging "Building Docker image…".
- **Reproduction minimale** :
  ```
  # on macOS
  vidocq build jlink && vidocq build docker
  docker build -t app -f target/Dockerfile target && docker run --rm app   # exec format error
  ```
- **Hypothèse de cause** : jlink links against the host's jmods; the mojo neither cross-links
  with Linux jmods nor warns when host OS ≠ linux.
- **Investigations** :
  - 2026-07-11 : found while replaying the published tutorials end to end. Options: warn on
    non-linux hosts, document the CI-only expectation, or support --jmods cross-linking.

## BUG-20260815-01 — jlink launcher requires an explicit `exports … to io.vidocq.runtime.core`

- **Date**: 2026-08-15
- **Status**: OPEN
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
