# Dev hot swap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `vidocq:dev` redefines changed method bodies in place when that gives the same result as a layer swap,
swaps otherwise, and says which path ran and why.

**Architecture:** The mojo snapshots the compiled output, classifies each recompile against the last acknowledged
snapshot with the Class-File API and the start-time declarations the extensions ship, and writes the verdict into the
reload file. The child reads it: a `redefine` goes through an in-process `java.lang.instrument` agent with the bytes
Vauban's loader would define, then to the `RedefinitionListener`s of the `-dev` companions; anything else swaps.

**Tech Stack:** Java 25, Java Modules, Class-File API (`java.lang.classfile`), `java.lang.instrument`, Maven plugin
API, JUnit 5, Vaadin Flow 25 (companion only).

**Spec:** `docs/superpowers/specs/2026-11-16-dev-hotswap-design.md` (revised a1857898). Section numbers below (§) are
the spec's.

## Global Constraints

- Strict Java Modules, no reflection, virtual threads, no `ThreadLocal`; `vidocq-runtime-architecture-tests` passes.
- Nothing of the agent, the listeners or the Vaadin companion is in a packaged application: every new jar carries
  `Vidocq-Dev-Only: true`, except the declaration files, which are inert data in the main jars.
- Declaration file: `META-INF/vidocq/dev/start-time.properties`; keys exactly `long-lived-annotations`,
  `long-lived-types`, `start-annotations`, `observer-annotations`, `start-events`, `start-callbacks`; callbacks as
  `owner#name(descriptor)`.
- Property `vidocq.dev.hotSwap`, default `true`.
- Log lines in the shapes of the spec: `Hot reload - redefined 1 class in 7 ms, the application keeps running:
  MainView`; `Hot reload - restarting on a fresh application layer: <reason>`; `the compiled output did not change:
  nothing reloaded`; `Hot swap: start-time declarations from N jars`; `Hot swap off: <why>`.
- **No declaration file found at all (VIDOCQ, 2026-11-16): today's behaviour, byte for byte** — reload file content
  `<nanoTime>\n`, every change reloads, today's log line. Only the startup line `Hot swap off: …` is new.
- English everywhere in code, docs and commits; commits `git commit -S -s`, Conventional Commits, trailers
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and the `Claude-Session:` line.
- Per pull request, before the push: `./mvnw -ntp -Pquality,it verify` on the whole reactor, then the local SonarQube
  branch analysis (workspace `AGENTS.md`), gate OK and no new issue. Merge: lock `/tmp/merge.lock.vidocq`, one
  `/merge` at a time after a green CI and Sonar comment.
- Vidocq PR (b) needs Vidocq/vauban#317 merged and its `0.4.0-SNAPSHOT` published (check the build-info commit).

## Review Focus

1. **A change in another reactor module the session watches** (its classes on the application path, outside this
   project's `target/classes`): it must never classify as "nothing". Test in Task A6.
2. **A change that compiles to identical output, with continuous testing on**: nothing reloads, and the tests still
   run on a `ReadyGate.NOW`. Test in Task A6.
3. **A nested class of an application source** (`MainView$Inner`, `SourceFile` `MainView.java`): traced to the
   project's sources, redefined, not taken for generated code. Test in Task A5.
4. **A Vauban-woven bean**: the bytes handed to the JVM are the loader's `definitionBytes`, not the file on disk.
   Test in Task B2.
5. **A supertype that cannot be read** (a class missing from every jar): the walk warns once and counts the class as
   long-lived, so a constructor change swaps. Test in Task A4.

---

## PR (a): declarations and classifier — branch `feat/dev-hotswap` (with the spec and this plan)

### Task A1: the reload request format (core)

**Files:**
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/ReloadRequest.java`
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/ReloadRequestTest.java`

**Interfaces:**
- Produces (public, exported package; the plugin writes with it, the child reads with it):

```java
public sealed interface ReloadRequest permits ReloadRequest.Redefine, ReloadRequest.Swap {
    /** Class binary name -> names of the methods whose code changed, in classification order. */
    record Redefine(SequencedMap<String, SequencedSet<String>> methodsByClass) implements ReloadRequest {}
    record Swap(String reason) implements ReloadRequest {}   // "" when the file has no mode line
    String format(long nanoTime);                            // "<nanoTime>\n" + mode lines, each ending in "\n"
    static ReloadRequest parse(String content);              // never throws
}
```

- [ ] **Step 1: Write the failing tests**

```java
@Test void aRedefineRequestListsEachClassWithItsMethods() {
    var r = new ReloadRequest.Redefine(linked("io.x.MainView", "<init>", "lambda$new$0", "io.x.Service", "greet"));
    assertEquals("42\nredefine\nio.x.MainView <init> lambda$new$0\nio.x.Service greet\n", r.format(42));
}
@Test void aSwapRequestCarriesItsReason() {
    assertEquals("42\nswap MainView gained a method lambda$new$1\n",
            new ReloadRequest.Swap("MainView gained a method lambda$new$1").format(42));
}
@Test void parsingGivesBackWhatWasFormatted() { /* both kinds round-trip */ }
@Test void aFileWithoutAModeLineIsASwapWithoutReason() {
    assertEquals(new ReloadRequest.Swap(""), ReloadRequest.parse("123456\n"));
}
@Test void anUnknownModeOrAnEmptyRedefineIsASwap() {
    assertInstanceOf(ReloadRequest.Swap.class, ReloadRequest.parse("1\nreplace\n"));
    assertInstanceOf(ReloadRequest.Swap.class, ReloadRequest.parse("1\nredefine\n"));
}
```

- [ ] **Step 2: Run** `./mvnw -ntp -q test -pl vidocq-runtime-core -Dtest=ReloadRequestTest` — FAIL, `ReloadRequest`
  does not exist.
- [ ] **Step 3: Implement `ReloadRequest`.** Parse line by line; the first line (nanoTime) is ignored.
- [ ] **Step 4: Run the test** — PASS.
- [ ] **Step 5: Commit** `feat(core): the reload request names its mode, redefine or swap`.

### Task A2: reading the start-time declarations (plugin)

**Files:**
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/StartTimeDeclarations.java`
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/StartTimeDeclarationsTest.java`

**Interfaces:**
- Produces:

```java
record StartTimeDeclarations(Set<String> longLivedAnnotations, Set<String> longLivedTypes,
        Set<String> startAnnotations, Set<String> observerAnnotations, Set<String> startEvents,
        Set<Callback> startCallbacks, List<String> sources) {
    static final String FILE = "META-INF/vidocq/dev/start-time.properties";
    record Callback(String owner, String name, String descriptor) {}
    /** Each entry a jar or a directory; entries without the file add nothing. */
    static StartTimeDeclarations read(List<Path> jarsAndDirectories, Consumer<String> warn);
    StartTimeDeclarations merge(StartTimeDeclarations other);
    boolean isEmpty();   // no source declared anything
}
```

Names are dotted binary names (`jakarta.enterprise.context.ApplicationScoped`). `sources` holds the jar file names
(or directory paths) that declared, for the startup line.

- [ ] **Step 1: Write the failing tests**: `readsTheFileFromAJar`, `readsTheFileFromADirectory`,
  `mergesSeveralJars`, `aJarWithoutTheFileAddsNothing`, `valuesAreCommaSeparatedAndTrimmed` (with a `\`
  continuation line and a `#` comment), `anUnknownKeyWarnsNamingTheJarAndTheKey` (warning contains the jar file name
  and `long-lived-annotation`; the other keys still apply), `aMalformedCallbackWarnsAndIsSkipped` (`Servlet#init`
  without a descriptor, and `Servlet#init(Lbad)V`; both warnings name the jar and the entry), `anUnreadableJarWarns`.
  Jars built in the test with `java.util.jar.JarOutputStream`.
- [ ] **Step 2: Run** `-Dtest=StartTimeDeclarationsTest` — FAIL, class missing.
- [ ] **Step 3: Implement.** `java.util.Properties` for the syntax; a callback's descriptor is checked with
  `MethodTypeDesc.ofDescriptor`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(plugin): read the start-time declarations of the application's jars`.

### Task A3: the declaration files of the core, Foy, Cassini and Vaadin

**Files:**
- Create: `vidocq-runtime-core/src/main/resources/META-INF/vidocq/dev/start-time.properties`
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/src/main/resources/META-INF/vidocq/dev/start-time.properties`
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-core/vidocq-runtime-cassini-rest-extension/src/main/resources/META-INF/vidocq/dev/start-time.properties`
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-vaadin-extension/src/main/resources/META-INF/vidocq/dev/start-time.properties`
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/ExtensionDeclarationsTest.java`

**Interfaces:**
- Consumes: `StartTimeDeclarations.read` (A2).
- Produces: the four files, with the content of the §2.1 table. Callback descriptors in full, e.g.
  `jakarta.servlet.Servlet#init(Ljakarta/servlet/ServletConfig;)V`,
  `jakarta.servlet.GenericServlet#init()V`, `jakarta.servlet.Filter#init(Ljakarta/servlet/FilterConfig;)V`,
  `jakarta.servlet.ServletContextListener#contextInitialized(Ljakarta/servlet/ServletContextEvent;)V`,
  `jakarta.servlet.ServletContainerInitializer#onStartup(Ljava/util/Set;Ljakarta/servlet/ServletContext;)V`,
  `jakarta.ws.rs.core.Application#getClasses()Ljava/util/Set;`, `#getSingletons()Ljava/util/Set;`,
  `#getProperties()Ljava/util/Map;`, `jakarta.ws.rs.core.Feature#configure(Ljakarta/ws/rs/core/FeatureContext;)Z`,
  `jakarta.ws.rs.container.DynamicFeature#configure(Ljakarta/ws/rs/container/ResourceInfo;Ljakarta/ws/rs/core/FeatureContext;)V`,
  `com.vaadin.flow.server.VaadinServiceInitListener#serviceInit(Lcom/vaadin/flow/server/ServiceInitEvent;)V`.

- [ ] **Step 1: Write the failing test**, one method per file, each reading the real file from the source tree
  (relative to the plugin's base directory, `../vidocq-runtime-core/...`, as the parent alignment test reads the
  parent pom): `coreDeclaresCdi`, `foyDeclaresServlet`, `cassiniDeclaresJaxRs`, `vaadinDeclaresItsScopes`. Each
  asserts no warning and the presence of named entries (`jakarta.enterprise.context.ApplicationScoped` in
  `longLivedAnnotations`; the `Servlet#init` callback; `jakarta.ws.rs.ext.Provider`;
  `com.vaadin.cdi.annotation.UIScoped`). The classification side of each is in A5.
- [ ] **Step 2: Run** `-Dtest=ExtensionDeclarationsTest` — FAIL, files missing.
- [ ] **Step 3: Write the four files**, with a header comment saying what reads them (`vidocq:dev` only).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(extensions): declare the start-time code of CDI, Servlet, JAX-RS and Vaadin`.

### Task A4: fingerprints, the snapshot and the supertype walk (plugin)

**Files:**
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/ClassFingerprint.java`
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/ClassesSnapshot.java`
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/SupertypeIndex.java`
- Test: `ClassFingerprintTest`, `ClassesSnapshotTest`, `SupertypeIndexTest` (same test package), with a shared
  `HotSwapFixtures` that compiles sources with `DepgenFixtures.jar` and writes stand-in API classes with the
  Class-File API (a fake `jakarta.servlet.GenericServlet implements jakarta.servlet.Servlet`, so no API jar is needed).

**Interfaces:**
- Produces:

```java
record ClassFingerprint(String binaryName, String sourceFile, String shape,
        SequencedMap<String, String> codeByMethod) {          // key: name + descriptor
    static ClassFingerprint of(byte[] classFile);
    /** "gained a method lambda$new$1", "lost a field total", "changed an annotation on hello()", ... */
    Optional<String> firstShapeDifference(ClassFingerprint previous);
    Set<String> methodsWithChangedCode(ClassFingerprint previous);   // method names, "<init>" included
}
record ClassesSnapshot(Map<Path, Map<String, Entry>> byRoot) {    // root -> relative path -> entry
    record Entry(long size, FileTime modified, String sha256, Optional<ClassFingerprint> fingerprint) {}
    static ClassesSnapshot take(List<Path> classRoots, ClassesSnapshot previous);   // previous may be EMPTY
    static final ClassesSnapshot EMPTY;
}
final class SupertypeIndex {
    static SupertypeIndex of(ClassesSnapshot application, List<Path> dependencyJars, Consumer<String> warn);
    /** True when binaryName is, extends or implements type, through application classes and jars. */
    boolean isA(String binaryName, String type);
    boolean unresolved(String binaryName);   // a supertype could not be read
}
```

- [ ] **Step 1: Write the failing tests.** Fingerprint: `aRenumberedConstantPoolIsNoChange`;
  `aMovedLineChangesTheCodeNotTheShape`; `aNewLambdaChangesTheShape` (difference `gained a method lambda$new$1`);
  `lostFieldAndChangedSignatureAndModifier`; `anAnnotationOnAParameterChangesTheShape`;
  `theLookupInnerClassEntryIsIgnored` (a first string concatenation adds `MethodHandles$Lookup`: same shape);
  `stackMapsAreIgnored`. Snapshot: `onlyMovedFilesAreParsedAgain` (counts parses through a package-private hook);
  `resourcesHaveNoFingerprint`. Supertypes: `walksApplicationThenJars`; `stopsAtJavaClasses`;
  `aMissingSupertypeWarnsOnceAndIsUnresolved` (Review Focus 5).
- [ ] **Step 2: Run** `-Dtest='ClassFingerprintTest,ClassesSnapshotTest,SupertypeIndexTest'` — FAIL, classes missing.
- [ ] **Step 3: Implement.** Constants and members are compared resolved (symbolic names), never by constant-pool
  index; the shape excludes `Code`, `BootstrapMethods`, `SourceFile`, `SourceDebugExtension` and `InnerClasses`
  entries whose inner class is outside the application; the code keeps instructions, exception table, line numbers
  and local variable tables. Jar class files are read on demand and cached for the session.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(plugin): fingerprint compiled classes for the dev reload`.

### Task A5: the classifier (plugin)

**Files:**
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/HotSwapClassifier.java`
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/StartTimeCode.java`
- Test: `HotSwapClassifierTest`, `StartTimeCodeTest`

**Interfaces:**
- Consumes: `ReloadRequest` (A1), `StartTimeDeclarations` (A2), `ClassesSnapshot`, `ClassFingerprint`,
  `SupertypeIndex` (A4).
- Produces:

```java
final class StartTimeCode {
    /** "binaryName#name+descriptor" of every start-time method of the application, call graph included. */
    static Set<String> of(ClassesSnapshot app, StartTimeDeclarations declarations, SupertypeIndex supertypes);
}
final class HotSwapClassifier {
    HotSwapClassifier(List<Path> projectSourceRoots, StartTimeDeclarations declarations);
    /** Empty: nothing to reload (rule 1). */
    Optional<ReloadRequest> classify(ClassesSnapshot base, ClassesSnapshot next, SupertypeIndex supertypes);
}
```

- [ ] **Step 1: Write the failing tests**, one per rule of §2 and per line of the spec's "Classifier" test list:
  - rule 1: `identicalOutputIsNothing`; `buildInfoFilesAloneAreNothing`;
  - rule 2: `anAddedOrRemovedClassSwaps`, `aNewAnonymousClassSwaps`;
  - rule 3: `aChangedResourceSwaps`, `moduleInfoSwaps`;
  - rule 4: `aGeneratedClassWithASourceUnderGeneratedSourcesSwaps`, `aClassWithNoSourceSwaps`,
    `aClassOfAnotherClassRootSwaps` (Review Focus 1), `aNestedClassOfAnApplicationSourceIsTheProjects`
    (Review Focus 3: `MainView$Inner`, redefined);
  - rule 5: the shape cases of A4, with the reason in the request (`swap MainView gained a method lambda$new$1`);
  - rule 6, through declarations built in the test: `aStaticInitializerSwapsWithNoDeclaration`;
    `aLongLivedAnnotationMakesConstructorFieldInitializerAndInitializerMethodStartTime`;
    `aLongLivedTypeThroughAnApplicationSuperclass`, `…ThroughAJarSuperclass`; `aStartAnnotation`;
    `anObserverOfAStartEvent` (and an observer of another event redefines); `aDeclaredCallback`;
    `aHelperCalledFromALongLivedConstructor`; `aMethodOfAnotherClassCalledFromIt`;
    `anOverrideReachedThroughInvokevirtual`; `anUnresolvedSupertypeCountsAsLongLived`;
    `theSameClassWithNoDeclarationRedefines`;
  - rule 6, with the real files of A3: `aUIScopedConstructorSwaps`, `serviceInitSwaps`,
    `aServletInitSwaps`, `contextInitializedSwaps`, `applicationGetClassesSwaps`, `aProviderConstructorSwaps`,
    `anApplicationScopedConstructorAndAStartupObserverSwap`;
  - a third-party jar built in the test, declaring its own annotation and callback, and the application's own
    `target/classes` file: `aThirdPartyDeclarationApplies`, `theApplicationsOwnDeclarationApplies`;
  - rule 7: `aMethodBodyRedefines`, `aLambdaBodyRedefines`, `aBodyThatMovesTheConstantPoolRedefines`,
    `aViewConstructorRedefines`, `aLambdaCreatedInALongLivedConstructorRedefines`; the request lists the changed
    methods per class (`io.x.MainView <init> lambda$new$0`); classes whose bytes differ with an equal fingerprint are
    left out.
- [ ] **Step 2: Run** `-Dtest='HotSwapClassifierTest,StartTimeCodeTest'` — FAIL.
- [ ] **Step 3: Implement**, rules in the spec's order, first match wins. "The project's own sources": a compile
  source root outside the build directory holding `<package path>/<SourceFile>`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(plugin): classify a recompile as nothing, a redefinition or a swap`.

### Task A6: the mojo session: base, acknowledgement, request (plugin)

**Files:**
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/HotSwapSession.java`
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java`
  (`signalHotReload` at 704-720, `recompileAndReload` at 752-785, session start near 342-372)
- Test: `HotSwapSessionTest`; extend `VidocqDevMojoTest`

**Interfaces:**
- Consumes: A1–A5; `ReloadAck` (existing); `RELOAD_ACK_TIMEOUT`.
- Produces:

```java
final class HotSwapSession {
    /** Empty when no jar and no class root declares anything: today's reload, byte for byte. */
    static Optional<HotSwapSession> open(List<Path> classRoots, List<Path> projectSourceRoots,
            List<Path> dependencyJars, boolean redefineAvailable, Consumer<String> info, Consumer<String> warn);
    /** After a successful recompile. Empty: nothing to reload. */
    Optional<ReloadRequest> next();
    void acknowledged();          // the base becomes the snapshot of the last request
    void lost();                  // timeout or dead child: the next request is a swap
}
```

`redefineAvailable` is `false` in this pull request: a `Redefine` verdict is written as
`Swap("<n> classes changed in method bodies only; class redefinition is not available yet")`.

- [ ] **Step 1: Write the failing tests.** `HotSwapSessionTest`: `anApplicationWithNoDeclarationHasNoSession`;
  `theStartupLineCountsTheDeclaringJars`; `aFailedRecompileKeepsTheBase`; `theBaseMovesOnlyOnAcknowledgement`;
  `aLostRequestMakesTheNextOneASwap`; `theApplicationsOwnFileIsReadAgainAfterEachRecompile`.
  `VidocqDevMojoTest`: `withoutDeclarationsTheReloadFileIsTodays` (content matches `\d+\n` exactly, log line
  `Hot reload signalled after N ms (in-JVM layer swap).`, an unchanged output still reloads — VIDOCQ's note);
  `theRequestCarriesTheModeAndTheReason`; `anUnchangedOutputReloadsNothingAndTheTestsStillRun` (Review Focus 2:
  `ReadyGate.NOW`); `theNextRecompileWaitsForTheAcknowledgement`; `theClassRootsAreEveryDirectoryOfTheApplicationPath`
  (Review Focus 1); `theHostLineNamesTheReason` (`… (layer swap: MainView gained a method lambda$new$1)`).
- [ ] **Step 2: Run** `-Dtest='HotSwapSessionTest,VidocqDevMojoTest'` — FAIL.
- [ ] **Step 3: Implement.** `signalHotReload` takes the content to write (today's `nanoTime + "\n"` when there is no
  session). `recompileAndReload` blocks on the previous gate before recompiling when a session exists. The class roots
  are the directories of `layered.appPath()`; the dependency jars are the jars of the module path and the app path.
- [ ] **Step 4: Run** — PASS, then the whole plugin module: `./mvnw -ntp -q test -pl vidocq-runtime-maven-plugin`.
- [ ] **Step 5: Commit** `feat(dev): classify each recompile and say why the layer is swapped`.

### Task A7: the child reads the reason (core)

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqDevReloadLoop.java` (the swap log line)
- Test: `VidocqDevReloadLoopTest`

- [ ] **Step 1: Write the failing tests** `theSwapLineCarriesTheReason` (`Hot reload - restarting on a fresh
  application layer: MainView gained a method lambda$new$1`) and `aFileWithoutAModeLineKeepsTodaysLine`.
- [ ] **Step 2: Run** `-Dtest=VidocqDevReloadLoopTest` — FAIL.
- [ ] **Step 3: Implement**: read the file with `ReloadRequest.parse` on a new stamp; a `Redefine` request swaps in
  this pull request.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(core): the dev reload line says why the layer is swapped`.

### Task A8: docs, gates, pull request

**Files:**
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (dev reload: the reasons, "nothing reloaded")
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (new section "Declaring start-time
  code": the file, its keys, the four shipped files as examples, warnings)

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Gates**: `./mvnw -ntp -Pquality,it verify` (whole reactor) — BUILD SUCCESS; local Sonar on
  `feat/dev-hotswap` — gate OK, 0 new issue.
- [ ] **Step 3: Commit, push, open the PR**; watch CI and the Sonar comment; merge under the lock.
- [ ] **Step 4: Replay R1** (below).

---

## PR (b): agent and redefinition — branch `feat/dev-hotswap-agent`

### Task B1: the agent module

**Files:**
- Create: `vidocq-runtime-dev-agent/pom.xml` (manifest `Premain-Class: io.vidocq.runtime.dev.agent.HotSwap`,
  `Can-Redefine-Classes: true`, `Vidocq-Dev-Only: true`)
- Create: `vidocq-runtime-dev-agent/src/main/java/module-info.java` (`module io.vidocq.runtime.dev.agent { requires
  java.instrument; exports io.vidocq.runtime.dev.agent; }`)
- Create: `vidocq-runtime-dev-agent/src/main/java/io/vidocq/runtime/dev/agent/HotSwap.java`,
  `RedefinitionRefused.java`
- Modify: root `pom.xml` (module after `vidocq-runtime-devconsole-spi`), `vidocq-runtime-bom/pom.xml`
- Test: `vidocq-runtime-dev-agent/src/test/java/io/vidocq/runtime/dev/agent/HotSwapProcessTest.java`

**Interfaces:**
- Produces:

```java
public final class HotSwap {
    public static void premain(String args, Instrumentation instrumentation);
    public static boolean installed();
    /** All or nothing. Loaded classes of loader among names are redefined with bytes.apply(name). */
    public static Outcome redefine(ClassLoader loader, Collection<String> names, Function<String, byte[]> bytes)
            throws RedefinitionRefused;
    public record Outcome(List<Class<?>> redefined, List<String> notLoaded) {}
}
public final class RedefinitionRefused extends Exception {}   // message: the JVM's reason
```

- [ ] **Step 1: Write the failing test** (forks a JVM as `ConsoleLoggingProcessTest` does; the jar is built in the
  test from the agent's classes and put on `--module-path`, `--add-modules` and `-javaagent`):
  `premainRunsInTheNamedModule`; `aBodyChangeOfAChildLayerClassIsLive`; `anAddedMethodIsRefusedAndNothingChanged`;
  `aClassNotLoadedIsReportedNotLoaded`.
- [ ] **Step 2: Run** `./mvnw -ntp -q test -pl vidocq-runtime-dev-agent` — FAIL.
- [ ] **Step 3: Implement.** Loaded classes come from `Instrumentation.getInitiatedClasses(loader)` filtered by name
  and defining loader; `UnmodifiableClassException` and `UnsupportedOperationException`/`ClassFormatError`/
  `VerifyError` become `RedefinitionRefused`.
- [ ] **Step 4: Run** — PASS. Then `vidocq-runtime-architecture-tests` and `ApplicationBomAndParentTest`.
- [ ] **Step 5: Commit** `feat(dev): an in-process agent that redefines application classes`.

### Task B2: redefinition in the reload loop (core)

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/module-info.java` (`requires static io.vidocq.runtime.dev.agent;`)
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/ClassRedefinition.java`
- Modify: `VidocqDevReloadLoop.java` (the loop keeps the bootstrap while redefinitions succeed)
- Modify: `vidocq-runtime-core/pom.xml` (the agent, `provided`/optional), root `pom.xml` (`vauban.version` needs the
  `definitionBytes` snapshot)
- Test: `ClassRedefinitionTest`, `VidocqDevReloadLoopTest`

**Interfaces:**
- Consumes: `HotSwap` (B1), `VaubanClassLoader.definitionBytes(String)` (vauban#317), `ReloadRequest.Redefine` (A1).
- Produces:

```java
final class ClassRedefinition {
    interface Redefiner {   // HotSwap in production, a stand-in in tests
        HotSwap.Outcome redefine(ClassLoader loader, Collection<String> names, Function<String, byte[]> bytes)
                throws Exception;
    }
    sealed interface Result permits Done, Refused {}
    record Done(List<Class<?>> redefined, List<String> notLoaded, long millis) implements Result {}
    record Refused(String reason) implements Result {}
    static Optional<Redefiner> agent();   // empty when the agent module is not in the boot layer, or not installed
    static Result apply(ReloadRequest.Redefine request, VaubanClassLoader loader, Redefiner redefiner);
}
```

- [ ] **Step 1: Write the failing tests.** `ClassRedefinitionTest`: `theBytesAreTheLoadersDefinitionBytes` (Review
  Focus 4: a stand-in redefiner captures the bytes; they equal `loader.definitionBytes(name)` for a woven class, not
  the file); `anUnreadableClassIsRefused`; `aRefusalNamesTheJvmReason`. `VidocqDevReloadLoopTest`:
  `aRedefineRequestDoesNotShutTheBootstrapDown`; `aRefusalSwaps`; `noAgentSwaps`; `aRequestWithoutModeSwaps`;
  `theAcknowledgementIsWrittenOnEveryPath`; `theLogLinesOfEachPath` (`redefined 1 class in N ms, the application
  keeps running: MainView`, `none of them loaded yet`, `redefinition refused (<reason>): restarting …`);
  `theBreakpointSentenceOnlyWithAJdwpAgent` (input arguments given to the loop, not read from the JVM in the test).
- [ ] **Step 2: Run** `-Dtest='ClassRedefinitionTest,VidocqDevReloadLoopTest'` — FAIL.
- [ ] **Step 3: Implement.** The loop becomes boot → acknowledge → { await → parse → redefine and acknowledge, or
  break to swap } . The JDWP check reads `ManagementFactory.getRuntimeMXBean().getInputArguments()` for
  `-agentlib:jdwp`.
- [ ] **Step 4: Run** — PASS, then the core module and the architecture tests (`requires static` entry if a rule
  needs one, with its reason).
- [ ] **Step 5: Commit** `feat(core): redefine method bodies in place on a redefine request`.

### Task B3: the mojo launches the agent and writes redefinitions

**Files:**
- Modify: `VidocqDevMojo.java` (`@Parameter(property = "vidocq.dev.hotSwap", defaultValue = "true")`; resolution of
  `vidocq-runtime-dev-agent` at `DevConsoleJars.runtimeVersion(...)`; JVM args), `HotSwapSession.open(...,
  redefineAvailable = true when the agent is on)`
- Test: `VidocqDevMojoTest`, `HotSwapSessionTest`

- [ ] **Step 1: Write the failing tests**: `theAgentIsOnBothPathsOnlyWhenLayerHotReloadAndHotSwapAreOn` (module
  path entry, `--add-modules io.vidocq.runtime.dev.agent`, `-javaagent:<jar>`); `hotSwapOffSaysWhy` for each of:
  property false, no layer mode, agent not resolved; `aRedefineVerdictIsWrittenAsRedefine`;
  `theHostLineCountsTheClasses` (`… (class redefinition: 1 class)`).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** (both options go through the JVM args; `--add-modules` may repeat).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(dev): load the hot swap agent in the dev child`.

### Task B4: docs, gates, pull request

**Files:** `usage.adoc` (the two paths, the rules in short, the log lines, `vidocq.dev.hotSwap`, the debugger
warning), `modules/vidocq-runtime-maven-plugin.adoc` (the property, the agent artifact), `vaadin.adoc` (a body-only
change keeps the session).

- [ ] **Step 1: Docs.** **Step 2: Gates** as A8 on `feat/dev-hotswap-agent`. **Step 3: PR, merge under the lock.**
  **Step 4: Replay R2.**

---

## PR (c): listeners and the Vaadin refresh — branch `feat/dev-hotswap-listeners`

### Task C1: the listener SPI and its call (devconsole SPI, core)

**Files:**
- Create: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/RedefinitionListener.java`,
  `Redefinition.java`, `RedefinedClass.java`
- Modify: core `module-info.java` (`requires static io.vidocq.runtime.spi.devconsole;` `uses
  io.vidocq.runtime.spi.devconsole.RedefinitionListener;`)
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/RedefinitionListeners.java`
- Modify: `VidocqDevReloadLoop.java` (after a `Done` with at least one loaded class)
- Test: `RedefinitionListenersTest`

**Interfaces:**
- Produces: the three SPI types of §5, and

```java
final class RedefinitionListeners implements AutoCloseable {
    /** Empty when the SPI module is not in the boot layer. One virtual thread, calls in order. */
    static Optional<RedefinitionListeners> start(Consumer<String> warn);
    /** Returns at once; listeners are loaded with ServiceLoader.load(layer, RedefinitionListener.class). */
    void redefined(ModuleLayer layer, Redefinition redefinition);
}
```

- [ ] **Step 1: Write the failing tests**: `calledWithTheLoadedClassesAndTheirMethods`;
  `notCalledAfterASwapARefusalOrWhenNothingWasLoaded` (in `VidocqDevReloadLoopTest`); `aThrowingListenerIsLoggedAndTheNextRuns`
  (warning `Hot reload - the redefinition listener <class> failed: <exception>`); `aBlockedListenerDelaysNeitherTheAcknowledgementNorTheNextRedefinition`;
  `twoRedefinitionsReachTheListenersInOrder`; `noSpiModuleMeansNoListenerAndNoFailure`. Listeners are provided by a
  test layer built in the test (`ModuleLayer.defineModulesWithOneLoader`).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** with `Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())`.
  **Step 4: Run** — PASS, plus the architecture tests.
- [ ] **Step 5: Commit** `feat(core): tell the dev companions what was redefined`.

### Task C2: a companion that reads the application's framework rides in the application layer (plugin)

**Files:**
- Modify: `VidocqDevMojo.java` around line 361 (`devTools(...)` result split by manifest attribute
  `LayerSplit.APPLICATION_LAYER_ATTRIBUTE`)
- Test: `VidocqDevLaunchPathsTest`

- [ ] **Step 1: Failing test** `aCompanionMarkedForTheApplicationLayerGoesOnTheApplicationPath` (and the others stay
  on the module path). **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(dev): a -dev companion can ride in the application layer`.

### Task C3: `vidocq-runtime-vaadin-extension-dev`

**Files:**
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-vaadin-extension-dev/`
  (`pom.xml` with `Vidocq-Dev-Only` and `Vidocq-Application-Layer`; Vaadin and Servlet `provided`)
- Create: in package `io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev`: `OpenUis` (registry, fed by a
  `VaadinServiceInitListener` adding a `UIInitListener`, removed on detach), `VaadinRedefinitionListener`
  (`RedefinitionListener`), `RedefinedEvents` (the event stream servlet and its `ServletContainerInitializer`, path
  `/.vidocq/dev/redefined`), `RefreshScript` (an `IndexHtmlRequestListener` adding the script)
- Create: `module-info.java` (`requires static flow.server; requires static com.vaadin.flow.server;` as the extension
  does; `requires io.vidocq.runtime.spi.devconsole; requires jakarta.servlet;` `provides` the listener, the
  initializer and the Vaadin service init listener)
- Modify: the Vaadin extension's `src/main/resources/META-INF/vidocq/dev-module` (create, naming the companion);
  `vidocq-runtime-extensions-jakartaee-web/pom.xml` and the BOM
- Test: `VaadinRedefinitionListenerTest`, `RefreshScriptTest`, `RedefinedEventsTest`

- [ ] **Step 1: Failing tests**: `onlyLambdaBodiesRefreshNothing`; `aRedefinedViewConstructorQueuesAReloadOnTheUisShowingIt`
  (and on no other UI); `aDetachedUiLeavesTheRegistry`; `theIndexPageGetsTheScript`;
  `oneEventPerRedefinition`; the refresh log line (`Hot reload - Vaadin: refreshed 1 open page showing MainView`).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** First check, in a browser on Vaadin 25 production mode,
  whether `Vaadin.Flow.clients[id].poll()` exists; if not, the event names the UI ids and the script reloads the page.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(vaadin): refresh the open pages that show a redefined view`.

### Task C4: docs, gates, pull request

**Files:** `dev-console-panels.adoc` (new section "Reacting to a redefinition", next to `[#dev-module]`: the SPI,
when it is called, what a listener may and may not do), `vaadin.adoc` (the refresh; **the refresh and the Vaadin
declarations need `vidocq-runtime-vaadin-extension` as a dependency** — VIDOCQ's note).

- [ ] **Step 1: Docs.** **Step 2: Gates** as A8 on `feat/dev-hotswap-listeners`. **Step 3: PR, merge under the
  lock.** **Step 4: Replay R3**, by hand in a browser, with push and without: the open page refreshes and shows the
  new constructor's output, same JSESSIONID.

---

## Replays on VidocqTools/vaadin-on-vidocq (open the PR only; Yann merges)

- **R1** (after PR (a) is published): a pull request adding `vidocq-runtime-vaadin-extension` to the pom (and to
  `module-info` if the launch needs it). Then, with the published snapshot: a view method body change logs a swap with
  the reason "… class redefinition is not available yet", an unchanged recompile logs "nothing reloaded".
- **R2** (after PR (b)): the three trial cases plus a CDI bean's method body, with the dev log lines and the same
  JSESSIONID; a constructor change of a `@UIScoped` view swaps.
- **R3** (after PR (c)): the refresh, with and without push.
