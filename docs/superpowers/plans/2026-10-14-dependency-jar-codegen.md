# Every brick's generator runs on dependency jars — plugin, Foy, report (vidocq#246)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `vidocq:generate` runs the annotation processors of every brick whose `-codegen` bundle declares dependency hints over the dependency jars that match them, once per jar version, and puts the output in the jar's enriched copy; Foy is the first brick; the startup report and `vidocq:analyze-deps` show it.

**Architecture:** Four small units in the Maven plugin, then wiring. `DependencyCodegenHints` parses a bundle's `META-INF/vidocq/dependency-codegen.properties`; `CodegenBundles` finds the bundles on the compiler's `annotationProcessorPaths` and resolves their processor classpaths; `DependencyCodegen` runs `javac -proc:only` then compiles the output, with one-line diagnostics; `DependencyCodegenCache` stores the output by content key in the user cache. `EnrichedJars` learns to merge listed resources, to stamp `Vidocq-Enriched-By`, and to keep an automatic jar automatic. `VidocqGenerateMojo` runs the pass after Vauban's and before the enrichment. The core reads `Vidocq-Enriched-By` for the report.

**Tech Stack:** Java 25 (`javax.tools`, Class-File API), Maven 3.9.16 plugin API with Aether (`RepositorySystem`), JUnit 5, Failsafe ITs.

**Spec:** `docs/superpowers/specs/2026-10-14-dependency-jar-codegen-design.md` (branch `docs/app-layer-dependencies-design`). This plan covers its order-of-work items 1–4. Item 5 (Mansart) is a plan in the mansart repository.

## Global Constraints

- Java 25, Maven 3.9.16, `modelVersion 4.0.0`. No new dependency in shipped runtime modules; the plugin may add test-scope dependencies (foy-processor, jakarta.servlet-api) and must justify any compile-scope one in the PR (none expected: `javax.tools` is in the JDK).
- English everywhere in artifacts; commits `git commit -s -S`, Conventional Commits with `(#246)`, trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY`.
- TDD; Maven through `.sh` scripts in the scratchpad; isolated repository `<scratchpad>/m2-applayer`; never `~/.m2`.
- `plugin.xml` is hand-written: a new mojo parameter needs a `<parameter>` and a `<configuration>` entry.
- Build failure wording (spec §6): `vidocq:generate: <jar file name> — <Brick[, Brick]> cannot process <what>: <processor message>. <fix>. Full javac output: target/vidocq-depgen/<artifactId>.log`
- The cache directory: `vidocq.generate.cacheDir` if set, else `$XDG_CACHE_HOME/vidocq/depgen`, else `${user.home}/.cache/vidocq/depgen`.
- What's new entry and `[.tag-new]#NEW#` badge for the visible changes.

## Review Focus

1. **A jar that two bricks want** (a library with both a `@WebListener` and a Jakarta Data repository): one javac run with both processors, `Vidocq-Enriched-By: Foy, Mansart`. Pinned by `DependencyCodegenTest.twoBricksRunTogether` (Task 3, with two fixture processors).
2. **A jar that Vauban also enriches** (a bean archive with a `@WebListener`): the Vauban classes and the Foy classes end up in the same copy, the Vauban providers still declared. Pinned by `EnrichedJarsBrickTest.vaubanAndBrickOutputShareOneCopy` (Task 4).
3. **A jar already processed by the brick** (compiled with foy-processor): not processed again, no copy written for it by this pass. Pinned by `DependencyCodegenSelectionTest.aMarkerSkipsTheJar` (Task 1).
4. **A processor that generates nothing for a candidate jar** (the byte hint matched a class that is not a component): no enriched copy, no cache miss on the next build. Pinned by `DependencyCodegenCacheTest.anEmptyResultIsCachedToo` (Task 5).
5. **An explicit-module jar** (has `module-info.class`): the copy's descriptor `provides` the brick's service with the generated classes and `requires` the brick modules they call. Pinned by `EnrichedJarsBrickTest.anExplicitModuleProvidesTheGeneratedService` (Task 4).

---

## File structure

| File (plugin: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/`) | Responsibility |
|---|---|
| `depgen/DependencyCodegenHints.java` (new) | parse and validate a bundle's hints file |
| `depgen/DependencyCodegenSelection.java` (new) | which bricks want a jar, and why |
| `depgen/CodegenBundles.java` (new) + `depgen/ArtifactResolver.java` (new) | find the bundles on the processor path, resolve their classpaths |
| `depgen/DependencyCodegen.java` (new) | `javac -proc:only` + compile, diagnostics, log file |
| `depgen/DependencyCodegenCache.java` (new) | content-keyed cache in the user cache directory |
| `depgen/ModuleDescriptorAugmenter.java` (new) | add `provides`/`requires` to a `module-info.class` |
| `EnrichedJars.java` | merge listed resources, `Vidocq-Enriched-By`, brick input lines, automatic copies |
| `VidocqGenerateMojo.java`, `META-INF/maven/plugin.xml` | the pass, `cacheDir` parameter |
| `VidocqCheckPomMojo.java` | uses the shared processor-path reader |
| `AnalyzeReport.java`, `VidocqAnalyzeDepsMojo.java` | per-jar brick lines |
| `vidocq-runtime-extensions/*/vidocq-runtime-foy-servlet-extension-codegen/src/main/resources/META-INF/vidocq/dependency-codegen.properties` (new) | Foy's hints |
| `vidocq-runtime-core/.../StartupFacts.java`, `report/CoreSections.java` | `N enriched (Foy 1)` |
| `vidocq-runtime-integration-tests/vidocq-runtime-it-dependency-codegen/` (new) | end-to-end IT |
| `docs/en/modules/ROOT/pages/...` | docs |

---

### Task 1: Hints file and selection

**Files:**
- Create: `depgen/DependencyCodegenHints.java`, `depgen/DependencyCodegenSelection.java`
- Test: `src/test/java/io/vidocq/runtime/maven/depgen/DependencyCodegenHintsTest.java`, `DependencyCodegenSelectionTest.java`

**Interfaces:**
- Produces:

```java
public record DependencyCodegenHints(String brick, List<String> resources, List<String> classBytes,
                                     List<String> markers, List<String> merge) {
    public static final String PATH = "META-INF/vidocq/dependency-codegen.properties";
    /** @param origin names the bundle in error messages */
    public static DependencyCodegenHints parse(Properties p, String origin);   // IllegalArgumentException on error
    /** The hints file of a bundle jar or directory; empty when it has none. */
    public static Optional<DependencyCodegenHints> read(Path bundleJarOrDir) throws IOException;
}

public final class DependencyCodegenSelection {
    /** Why a brick wants a jar: "resource META-INF/web-fragment.xml" or "class bytes jakarta/servlet/annotation/Web in com/acme/Boot.class". */
    public record Match(DependencyCodegenHints brick, String reason) {}
    /** A brick skipped because the jar carries its marker. */
    public record Skip(DependencyCodegenHints brick, String marker) {}
    public record Decision(Path jar, List<Match> matches, List<Skip> skips, boolean signed) {
        public boolean selected();   // !matches.isEmpty()
    }
    public static Decision decide(Path jar, List<DependencyCodegenHints> bricks) throws IOException;
}
```

Rules: `brick` required and non-blank; keys allowed: `brick`, `resources`, `class-bytes`, `markers`, `merge`; any other key → `IllegalArgumentException("<origin>: unknown key '<k>' in META-INF/vidocq/dependency-codegen.properties (allowed: brick, resources, class-bytes, markers, merge)")`; list values comma-separated, stripped, empties dropped; a brick with neither `resources` nor `class-bytes` is an error. `decide` reads the jar once: a brick is skipped (into `skips`) when any of its `markers` is an entry; otherwise it matches on the first `resources` entry found, else on the first class file (base entries, not `META-INF/versions/`) whose bytes contain one of `classBytes` as UTF-8 bytes. `signed` uses `EnrichedJars.isSignature` on the entry names. Directories (reactor modules) are never selected: `decide` returns no match for a directory.

- [ ] **Step 1: Failing tests.** `DependencyCodegenHintsTest`: `parsesEveryKey`, `missingBrickFails`, `unknownKeyFailsNamingTheBundle`, `noHintFails`, `readsFromAJar` (jar with the file), `aBundleWithoutTheFileHasNone`. `DependencyCodegenSelectionTest` (synthetic jars written in `@TempDir` with `JarOutputStream`; class bytes can be any bytes containing the hint string): `aResourceHintMatches`, `aClassByteHintMatchesAndNamesTheClass`, `aVersionedClassDoesNotMatch` (hint only under `META-INF/versions/21/`), `aMarkerSkipsTheJar`, `aJarNoBrickWantsIsNotSelected`, `twoBricksCanMatchOneJar`, `aSignedJarIsFlagged`, `aDirectoryIsNeverSelected`.
- [ ] **Step 2: Run, watch them fail to compile.** Script `<scratchpad>/plugin-test.sh <tag> [-Dtest=...]`: `./mvnw -B -ntp -pl vidocq-runtime-maven-plugin -am install -Dsurefire.failIfNoSpecifiedTests=false "$@"` with `MAVEN_OPTS=-Dmaven.repo.local=<scratchpad>/m2-applayer`, log to `<scratchpad>/depgen-<tag>.log`, print `rc` and the `Tests run`/`BUILD` lines.
- [ ] **Step 3: Implement** the two classes (records, `Properties`, one `JarFile` pass; `byte[]` search with a simple loop or `Arrays.mismatch`-free indexOf helper).
- [ ] **Step 4: Green**, then the plugin module's suite.
- [ ] **Step 5: Commit** `feat(maven-plugin): dependency codegen hints and jar selection (#246)`.

---

### Task 2: Find the bundles and resolve their processor classpaths

**Files:**
- Create: `depgen/CodegenBundles.java`, `depgen/ArtifactResolver.java`, `ProcessorPaths.java` (shared reader)
- Modify: `VidocqCheckPomMojo.java` (use `ProcessorPaths` instead of its private `codegenPathElements`, behaviour unchanged)
- Test: `depgen/CodegenBundlesTest.java`, `ProcessorPathsTest.java`; `VidocqCheckPomMojoTest` stays green

**Interfaces:**
- Produces:

```java
/** A {@code <path>} of the compiler's annotationProcessorPaths. */
public record ProcessorPath(String groupId, String artifactId, String version, String type, String classifier) {}
public final class ProcessorPaths {
    /** Every path of maven-compiler-plugin's configuration and executions, in order, de-duplicated by g:a:type:classifier. */
    public static List<ProcessorPath> of(MavenProject project);
}

public interface ArtifactResolver {
    /** The artifact's own file. */
    Path resolve(String groupId, String artifactId, String version, String type, String classifier) throws IOException;
    /** The artifact and its transitive runtime dependencies, as files. */
    List<Path> resolveWithDependencies(String groupId, String artifactId, String version, String type,
                                       String classifier) throws IOException;
}

public record Brick(DependencyCodegenHints hints, String coordinates, List<Path> processorPath) {}
public final class CodegenBundles {
    /**
     * The bricks of the project: each processor path whose own artifact holds a hints file. A path without a version
     * takes it from the project's dependencyManagement (same g:a, type jar, or the path's type); a path without a
     * version anywhere fails naming it. A {@code type=pom} path is skipped (IntelliJ twin, #237).
     */
    public static List<Brick> of(MavenProject project, ArtifactResolver resolver) throws MojoExecutionException;
}
```

The Aether implementation of `ArtifactResolver` (`AetherArtifactResolver`, package-private, in `depgen/`) uses `RepositorySystem.resolveArtifact` and `RepositorySystem.resolveDependencies` with a `CollectRequest` rooted at the artifact (`JavaScopes.RUNTIME` filter), the project's remote repositories and session — the same objects `VidocqCheckPomMojo` injects.

- [ ] **Step 1: Failing tests.** `ProcessorPathsTest`: a project model with a compiler plugin configuration holding two paths and an execution holding one more → three `ProcessorPath`s in order; duplicates dropped; no compiler plugin → empty. `CodegenBundlesTest` with a fake `ArtifactResolver` (a map from `g:a` to files written in `@TempDir`): a bundle with a hints file becomes a `Brick` whose `processorPath` is the resolver's list; a bundle without the file is ignored; a version taken from `dependencyManagement`; a missing version fails with `annotationProcessorPaths entry g:a has no version and the project's dependencyManagement has none`; a `pom`-typed path is skipped.
- [ ] **Step 2: Red** (`-Dtest='ProcessorPathsTest,CodegenBundlesTest'`).
- [ ] **Step 3: Implement**; move `VidocqCheckPomMojo`'s path walk onto `ProcessorPaths` (it keeps its own vidocq-group filter).
- [ ] **Step 4: Green**, plugin suite (check-pom tests unchanged).
- [ ] **Step 5: Commit** `feat(maven-plugin): find the codegen bundles and their processor classpaths (#246)`.

---

### Task 3: Run the processors over a jar

**Files:**
- Create: `depgen/DependencyCodegen.java`
- Test: `depgen/DependencyCodegenTest.java`, test resources: two tiny fixture processors written as Java sources under `src/test/resources/depgen/processors/` and compiled by the test with `javax.tools` into processor jars

**Interfaces:**
- Consumes: `Brick` (Task 2).
- Produces:

```java
public final class DependencyCodegen {
    /** What one jar produced: generated class files and resources, relative path → bytes; empty when nothing. */
    public record Output(Map<String, byte[]> files) {}
    /** One line per problem, already worded for the build (Global Constraints). */
    public static final class Failure extends Exception { public List<String> lines(); }
    /**
     * @param jar        the dependency jar
     * @param bricks     the bricks that want it (non-empty)
     * @param classpath  the jar's compile classpath: the project's dependency jars (the jar itself included)
     * @param release    the --release value
     * @param coordinates groupId:artifactId of the jar, for the scanExcludes hint of a failure line
     * @param artifactId  for the log file name
     * @param logDir      target/vidocq-depgen/, where <artifactId>.log receives javac's full output
     */
    public static Output run(Path jar, List<Brick> bricks, List<Path> classpath, int release, String coordinates,
                             String artifactId, Path logDir) throws Failure, IOException;
}
```

Steps inside `run`: list the jar's top-level class binary names (base entries ending `.class`, not `module-info`/`package-info`, no `$` in the simple name); first `javac` with `-proc:only -implicit:none --release N -processorpath <union of bricks' processorPath> -cp <classpath> -s <tmp>/src -d <tmp>/res -Xlint:none` and the class names; collect `Diagnostic`s; ERROR diagnostics → `Failure`. Then, if `<tmp>/src` holds sources, a second `javac -proc:none --release N -cp <classpath> -d <tmp>/classes` over them; any ERROR → `Failure` (`… generated code that does not compile: <first message>`). `Output.files` = every file under `<tmp>/classes` and `<tmp>/res` (resources the processors wrote through the `Filer` to `CLASS_OUTPUT`). The temp directory is deleted. The wording of a failure line: `vidocq:generate: <jar file name> — <brick names joined by ", "> cannot process <diagnostic source or "the jar">: <diagnostic message, first line>. Fix the library, or leave the jar out with <scanExcludes><coordinates></scanExcludes>. Full javac output: target/vidocq-depgen/<artifactId>.log`.

- [ ] **Step 1: Failing tests.** Fixture processor A (`@SupportedAnnotationTypes("fixture.Marked")`) generates, for each annotated type, a source class `<Type>$$Gen` and a resource `META-INF/fixture/a.list` listing the types. Fixture processor B (`@SupportedAnnotationTypes("fixture.Other")`) generates `META-INF/fixture/b.list`. Fixture processor E reports `messager.printMessage(ERROR, "cannot handle " + type, type)` for each annotated type. The test compiles them into jars, compiles a dependency jar holding `fixture.Marked` (RUNTIME retention), `fixture.Other` and classes annotated with them. Tests: `generatesClassesAndResources` (A over the jar → `com/acme/Foo$$Gen.class` and `META-INF/fixture/a.list`), `twoBricksRunTogether` (A+B, both resources), `nothingToGenerateGivesAnEmptyOutput`, `aProcessorErrorFailsWithOneLine` (E → one line containing the jar file name, the brick name, `cannot handle com.acme.Foo`, `<scanExcludes>com.acme:lib</scanExcludes>`, and the log file written), `nestedClassesAreNotPassedTwice` (a jar with `Outer$Inner`: no FilerException).
- [ ] **Step 2: Red.**
- [ ] **Step 3: Implement** with `ToolProvider.getSystemJavaCompiler()`, a `DiagnosticCollector`, `StandardJavaFileManager`; write javac's full output (diagnostics formatted plus the `Writer` output) to the log file.
- [ ] **Step 4: Green**, plugin suite.
- [ ] **Step 5: Commit** `feat(maven-plugin): run brick processors over a dependency jar's classes (#246)`.

---

### Task 4: Enriched copies carry brick output

**Files:**
- Create: `depgen/ModuleDescriptorAugmenter.java`
- Modify: `EnrichedJars.java` (`enrichAll`, `write`, `inputs`)
- Test: `EnrichedJarsBrickTest.java` (new), `depgen/ModuleDescriptorAugmenterTest.java`; `EnrichedJarsTest` stays green

**Interfaces:**
- Produces:
  - `ModuleDescriptorAugmenter.augment(byte[] moduleInfo, Map<String, List<String>> provides, Set<String> requires)` → new bytes: adds each `provides service with impls` (merged with an existing `provides` of the same service, no duplicates) and each `requires` not already present (plain `requires`, no flags), using `java.lang.classfile` (`ClassFile.of().transformClass` on the `ModuleAttribute`).
  - `EnrichedJars.Scanned` gains `List<String> bricks` (names, sorted) — a compact-constructor default `List.of()` keeps the existing call sites; and `enrichAll(..., Set<String> mergedResources, Function<Set<String>, Set<String>> modulesOfPackages, ...)`:
    - `mergedResources`: the union of the bricks' `merge` lists. In `write`, a patch entry whose name is in it is merged line by line with the jar's entry (the jar's lines first, then the new ones, no duplicates, comments dropped as for the service file today) instead of `putIfAbsent`; any other patch entry that the jar already has stays the jar's, as today.
    - The manifest gains `Vidocq-Enriched-By: <bricks joined by ", ">` when `bricks` is non-empty.
    - `inputs` gains, per brick of the jar, `brick <name>` and the brick lines the caller passes (`Scanned` carries `List<String> brickInputs`, e.g. `brick Foy io.vidocq.runtime:...-codegen:0.4.0-SNAPSHOT sha256:<processor classpath>`).
    - Descriptor: when the source has a root `module-info.class`, the providers of every merged `META-INF/services/<svc>` file's new lines become `provides <svc> with …`, and the packages the patch classes reference (read from their constant pools) map through `modulesOfPackages` to `requires` (the module itself, `java.base` and unresolved packages are dropped) — through `ModuleDescriptorAugmenter`, after Vauban's `addComponentProvider`. When the source has no root descriptor **and** the patch holds no Vauban provider (`_VaubanComponents`), the copy stays an automatic module: no descriptor is synthesized, the manifest keeps `Automatic-Module-Name` (or none, if the original had none: the copy keeps the original file name, so the derived name is the same). When it has Vauban providers, today's synthesis runs, then the augmentation.
- `modulesOfPackages` is built by the mojo from `ModuleFinder.of(<every dependency jar>)`: package → module name.

- [ ] **Step 1: Failing tests.** `ModuleDescriptorAugmenterTest`: adds a new provides; merges into an existing provides; adds requires once; keeps everything else. `EnrichedJarsBrickTest`: `anAutomaticJarStaysAutomatic` (no `module-info.class` in the copy, generated class and merged services present, `Vidocq-Enriched-By: Foy`); `mergedResourcesKeepTheJarsLines`; `anExplicitModuleProvidesTheGeneratedService`; `vaubanAndBrickOutputShareOneCopy` (patch dir holding a `_VaubanComponents` and a `$$FoyComponent`: one copy, both providers declared, `Vidocq-Enriched-By: Foy`); `aBrickChangeRewritesTheCopy` (different `brickInputs` → new inputs digest).
- [ ] **Step 2: Red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Green**, plugin suite (`EnrichedJarsTest`, `VidocqPackageMojoTest`, `VidocqJlinkMojoTest` unchanged).
- [ ] **Step 5: Commit** `feat(maven-plugin): enriched copies carry brick output, merged and declared (#246)`.

---

### Task 5: Once per dependency version — the cache

**Files:**
- Create: `depgen/DependencyCodegenCache.java`
- Test: `depgen/DependencyCodegenCacheTest.java`

**Interfaces:**
- Produces:

```java
public final class DependencyCodegenCache {
    /** cacheDir property, else $XDG_CACHE_HOME/vidocq/depgen, else ${user.home}/.cache/vidocq/depgen. */
    public static Path directory(String cacheDirProperty, Map<String, String> env, String userHome);
    public DependencyCodegenCache(Path directory);
    /** SHA-256 over: jar sha256, each brick "name processorPathSha256" sorted, "release N", "jdk <feature>". */
    public static String key(Path jar, List<Brick> bricks, int release) throws IOException;
    /** The sha256 of a processor classpath (each file's sha256, in order). */
    public static String classpathDigest(List<Path> processorPath) throws IOException;
    public Optional<DependencyCodegen.Output> get(String key) throws IOException;
    public void put(String key, DependencyCodegen.Output output) throws IOException;  // temp dir + atomic move, then a "complete" file
}
```

An entry is `<dir>/<key>/files/...` plus `<dir>/<key>/complete`. `get` ignores (and deletes) an entry without `complete`. An empty output is stored too (only `complete`), so a candidate jar that yields nothing is not reprocessed.

- [ ] **Step 1: Failing tests:** `putThenGetGivesTheSameFiles`, `anEmptyResultIsCachedToo`, `aDamagedEntryIsIgnoredAndRewritten`, `theKeyChangesWithTheJarTheProcessorsTheRelease`, `directoryPrecedence` (property > XDG > home).
- [ ] **Step 2: Red. Step 3: Implement. Step 4: Green.**
- [ ] **Step 5: Commit** `feat(maven-plugin): cache dependency codegen output by content (#246)`.

---

### Task 6: Wire the pass into `vidocq:generate`

**Files:**
- Modify: `VidocqGenerateMojo.java`, `META-INF/maven/plugin.xml` (`generate` mojo: `cacheDir` parameter, property `vidocq.generate.cacheDir`; plus the injected `repoSession`, `remoteRepos`, `repoSystem` if not yet there)
- Test: `VidocqGenerateDependencyCodegenTest.java` (new), `PluginDescriptorScanTest` / descriptor tests updated

**Interfaces:**
- Consumes: Tasks 1–5.
- Behaviour, in `generate()` after the Vauban part (which keeps running only when `ScanSelection` selected jars) and before `EnrichedJars.enrichAll`:
  1. `bricks = CodegenBundles.of(project, new AetherArtifactResolver(...))`; none → skip the pass silently.
  2. For each dependency jar (`project.getArtifacts()`, regular files only, not excluded by `scanExcludes`): `DependencyCodegenSelection.decide`; signed and not named by `scanDependencies` → info `vidocq:generate: <jar> is signed; not processed for <bricks> (name it in scanDependencies to process it)`; else when selected: `key`, cache `get` or `DependencyCodegen.run` then `put`; write the output files into `JpmsPatches.patchDirFor(buildDir, artifactId)` (create it; never delete what Vauban parked there); remember the jar's bricks and brick input lines.
  3. Collect every `Failure`'s lines across jars; after the loop, if any, log each line as an error and throw `MojoFailureException` with them joined.
  4. Log one info line per processed jar: `vidocq:generate: <jar> — <Brick, Brick> (<n> classes, cached|generated)`.
  5. Pass to `enrichAll` the union of `scanned` (Vauban) and the brick-processed jars (one `Scanned` per jar, with `bricks` and `brickInputs`), the union of the bricks' `merge` lists, and `modulesOfPackages` built from `ModuleFinder.of(<dependency jars>)` (a `FindException` → empty function for that jar set, logged at debug).
  6. The early return "No dependencies to process" must no longer skip this pass: restructure so that `clearEnrichedCopies()` runs only when neither Vauban nor a brick selected anything.
  7. `--release`: the project property `maven.compiler.release`, else `Runtime.version().feature()`.
- [ ] **Step 1: Failing test** `VidocqGenerateDependencyCodegenTest`: build the mojo as `VidocqGenerateMojoTest`-style tests do (read existing generate tests first; if none instantiate the mojo, test the extracted orchestration method `DependencyCodegenPass.run(...)` — create it as a package-private class taking the artifacts, bricks, cache, buildDir and loggers — with fixture processor A from Task 3): a selected jar gets a patch dir with the generated class, a second run hits the cache (a counting `DependencyCodegen` seam: an interface `Generator` with the real implementation by default), a failing jar fails with its line, a signed jar is skipped with its line.
- [ ] **Step 2: Red. Step 3: Implement** (prefer the `DependencyCodegenPass` class to keep the mojo thin). **Step 4: Green**, plugin suite.
- [ ] **Step 5: Commit** `feat(maven-plugin): vidocq:generate runs brick processors on dependency jars (#246)`.

---

### Task 7: Foy declares its hints

**Files:**
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension-codegen/src/main/resources/META-INF/vidocq/dependency-codegen.properties` (find the bundle's exact path with `ls vidocq-runtime-extensions/*/ | grep foy-servlet-extension-codegen`)
- Test: in that module, `src/test/java/.../FoyDependencyHintsTest.java` (add `junit-jupiter` test dependency if the bundle has none, and the plugin's `DependencyCodegenHints` is not needed: parse with `Properties`)

Content:
```properties
brick=Foy
resources=META-INF/services/jakarta.servlet.ServletContainerInitializer,META-INF/web-fragment.xml
class-bytes=jakarta/servlet/annotation/Web,jakarta/servlet/annotation/HandlesTypes
markers=META-INF/io.vidocq.foy/generated,META-INF/foy/class-index.list,META-INF/services/io.vidocq.foy.spi.gen.WebComponent
merge=META-INF/services/io.vidocq.foy.spi.gen.WebComponent,META-INF/foy/class-index.list
```

- [ ] **Step 1: Failing test:** the file exists on the test classpath; `resources` equals `DependencyHints.RESOURCE_HINTS`, `class-bytes` equals `DependencyHints.CLASS_BYTE_HINTS`, `markers` contains `DependencyHints.MARKER` (`io.vidocq.foy.processor.DependencyHints`, from the bundle's `foy-processor` dependency). The bundle uses `<proc>none</proc>` and an empty module: check that its test compile works (add a surefire config only if needed).
- [ ] **Step 2: Red. Step 3: Add the file. Step 4: Green** (module build).
- [ ] **Step 5: Commit** `feat(foy-extension): the Foy codegen bundle declares its dependency hints (#246)`.

---

### Task 8: Startup report and `vidocq:analyze-deps`

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/StartupFacts.java`, `report/CoreSections.java`
- Modify: `AnalyzeReport.java`, `VidocqAnalyzeDepsMojo.java`
- Test: `StartupFactsTest`, `CoreSectionsTest`, `AnalyzeReportTest`

**Interfaces:**
- `CoreSections.LayerModule` gains `List<String> enrichedBy` (compact default `List.of()`, keep the 3-arg constructor). `StartupFacts.modules` reads, for each `file`-located jar of the layer, the manifest attribute `Vidocq-Enriched-By` (open with `JarFile`; failures ignored). `CoreSections.layer` headline: after the library count, `, <n> enriched (<Brick> <count>, ...)` (bricks sorted by name) when `n > 0`; each enriched module's row gets a fourth cell `enriched by Foy, Mansart`.
- `AnalyzeReport` gains a section `Brick codegen` listing, per dependency with a match or a skip: `<artifactId>: Foy (resource META-INF/web-fragment.xml)`, `<artifactId>: Foy skipped (marker META-INF/io.vidocq.foy/generated)`, and `cached` when the cache holds its key. `VidocqAnalyzeDepsMojo` computes the bricks and decisions as Task 6 does (no generation).
- [ ] **Steps:** failing tests (headline with `2 enriched (Foy 1, Mansart 1)`, row cell, manifest read from a real jar in `StartupFactsTest`; analyze report lines), red, implement, green (core and plugin suites), commit `feat: the startup report and analyze-deps show brick-enriched libraries (#246)`.

---

### Task 9: End-to-end IT (spec test 7)

**Files:** a new module `vidocq-runtime-integration-tests/vidocq-runtime-it-dependency-codegen/`, added to the `it` profile of `vidocq-runtime-integration-tests/pom.xml`. Model it on `vidocq-runtime-it-app-layer` (pom, `src/it/it-settings.xml`, `Fixture.java`, `.mvn/maven.config` filtering).

Fixture: an aggregator with `lib` and `app`.
- `lib` (plain jar, no parent, `Automatic-Module-Name: acme.weblib`, `jakarta.servlet:jakarta.servlet-api` `provided`): `acme.weblib.HelloListener` annotated `@WebListener`, implementing `ServletContextListener`, printing `LISTENER-RAN` in `contextInitialized`.
- `app` (runtime parent, depends on `vidocq-runtime-foy-servlet-extension` and `app-layer`-style `lib`; its compiler `annotationProcessorPaths` append `vidocq-runtime-foy-servlet-extension-codegen` with `combine.children="append"`; `module-info` requires `io.vidocq.runtime.core` and `acme.weblib`): a main calling `Vidocq.run(args)`; no listener of its own.

ITs:
- `ListenerFromADependencyRunsIT`: `mvn install` the fixture (warm-up), then `vidocq:run` in `app` with a free port → awaits `LISTENER-RAN` and the startup report line containing `1 enriched (Foy 1)`; the build log contains the Task 6 info line for the lib jar (`vidocq:generate: app-layer...` style: assert it contains `weblib` and `— Foy (`).
- `SecondBuildHitsTheCache`: a second `mvn process-classes` in `app` → log contains `cached`.
- Discrimination: with `-Dvidocq.generate.cacheDir=<fresh>` and the `-codegen` path removed from the app pom (a copy of the fixture with the path deleted), `LISTENER-RAN` never appears within the timeout; implement as a third test that fails fast on `Vidocq - Started in` without `LISTENER-RAN`.

- [ ] **Steps:** write the module, run `bash <scratchpad>/depgen-it.sh <tag>` (copy of `applayer-it.sh` targeting the new module), green, commit `test(it): a @WebListener from a dependency jar runs without a workaround (#246)`.

---

### Task 10: Docs

**Files:** `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc` (`vidocq:generate` section, `vidocq:analyze-deps`), `usage.adoc` (the application-layer section: link), `whats-new.adoc`, and a short "For brick authors" subsection documenting `META-INF/vidocq/dependency-codegen.properties`.

Content: what runs and when; the cache (location, `vidocq.generate.cacheDir`, safe to delete); the failure line and `scanExcludes`; signed jars; SOURCE-retention annotations and multi-release versioned classes are out of reach; the startup report line; `analyze-deps` output. `[.tag-new]#NEW#` on the new sections; What's new entry. Validate with the asciidoctor render check (memory: render with `-o -`, check structure, no WARNING).

- [ ] **Steps:** write, render-check, commit `docs: vidocq:generate runs brick processors on dependency jars (#246)`.

---

## After the tasks

1. Full build `CI=true ./mvnw install -Pit` in the isolated repository: green.
2. Manual check (spec test 8): the Vaadin spike in `<scratchpad>/spike-vaadin` with its `@WebListener` subclass removed, under `vidocq.app.layer=all`: `/` answers 200; record it in the PR.
3. Push `feat/dependency-jar-codegen`, open the PR (no merge).
