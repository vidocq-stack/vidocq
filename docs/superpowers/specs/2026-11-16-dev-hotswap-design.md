# Dev reload: redefine method bodies in place, swap the layer otherwise

Date: 2026-11-16. Status: design, for review (item 6b of the Vaadin replay papercuts). Part c (sessions across a
swap) gets its own design after this one.

## Why

Every change `vidocq:dev` recompiles today ends in an in-JVM layer swap: the runtime shuts down, the application
layer is dropped, a new one boots. It takes about 0.2 s on VidocqTools/vaadin-on-vidocq, but everything the old layer
held is lost: the HTTP sessions (Foy invalidates them on undeploy), so a Vaadin page open in the browser gets
`sessionExpired` and reloads with its state gone.

Most edits change only the body of a method: a listener, a service method, a REST resource. The JVM can apply those
in place with class redefinition. A trial on vaadin-on-vidocq on 2026-11-16 (Temurin 25, JDI `redefineClasses`
against the `vidocq:run` JDWP agent) showed:

| Change | Outcome |
|---|---|
| Body of the button's listener lambda | redefined in 6.8 ms. The next click shows the new text in the same page, with the text field's value kept and no reload. |
| Constructor (the button's label) | accepted, but the open view keeps the old label until F5. After F5 a new UI shows the new label, and the session (JSESSIONID) is unchanged. |
| A new method and a new lambda | refused (`add method not implemented`); the old code keeps running. |

Quarkus took the same road: instrumentation-based reload since 1.11, which it now runs only when the user asks for it
(`quarkus.live-reload.instrumentation`). The reason given is that a constructor change was reported as reloaded but
was not visible. This design keeps the layer swap as the general mechanism and adds a fast path that is taken only
when its result is the same as a swap's.

## Goals

- A change that only touches the bodies of methods that run on each call is applied in place. The application keeps
  running, with its sessions, its CDI instances and its open connections.
- Every other change goes through the layer swap, exactly as today.
- The dev log says which path ran and, for a swap, why.
- The redefined code is live at the next call. That is the only promise the fast path makes, and it must be true.

## Non-goals

- Structural changes in place (new methods, fields, classes). That needs a JVM with enhanced redefinition (the JetBrains
  Runtime with HotswapAgent) and framework plugins; plain JDK only here.
- Re-running code that already ran: constructors, static initializers, start-time callbacks.
- Packaged applications. Nothing of this runs or ships outside `vidocq:dev`.
- Telling Vaadin to refresh the browser (its `Hotswapper` exists only in Vaadin dev mode). Possible later.
- Sessions across a swap: part c.

## Anti-goals

- A change reported as applied that is not live. That is Quarkus's constructor problem, and the reason for the
  start-time rule below.
- Taking the JDWP connection: JDWP accepts one debugger, and the IDE needs it.
- A dynamic attach, or any agent at all in a packaged application.

## Constraints

- Plain JDK 25 redefinition: method bodies, constant pool and attributes may change. Adding, removing or renaming a
  method or a field, changing a signature or a modifier, the superclass, the interfaces, or the `NestHost`,
  `NestMembers`, `Record` or `PermittedSubclasses` attributes is refused. A redefinition is all or nothing: on a
  refusal, nothing changed.
- In the application layer, a class is defined from the transformer chain of its `VaubanClassLoader` (sjar decryption,
  cdi-proxifier weaving), not from the file as it is on disk. A redefinition must pass the same bytes the loader would
  define now, or the JVM sees a removed `(ProxyLink)` constructor on an unwoven file and refuses.
- The hot reload already requires the layer mode (`vidocq.app.path`); the fast path does too.
- Strict Java Modules, no reflection, virtual threads; the architecture tests apply.

## Design

### 1. The agent: a dev-only module, loaded at JVM start

A new module, `vidocq-runtime-dev-agent` (module `io.vidocq.runtime.dev.agent`), dev-only like the `-dev`
companions (Vidocq/vidocq#143): its manifest carries `Vidocq-Dev-Only` (`DevOnlyJars`), so `package`, `jlink`,
`jpackage` and `docker` drop it if an application declares it, and `checkpom` warns.

Its manifest has `Premain-Class: io.vidocq.runtime.dev.agent.HotSwap` and `Can-Redefine-Classes: true`. It
`requires java.instrument` and exports one package with a small, stable API:

```java
public final class HotSwap {
    public static void premain(String args, Instrumentation instrumentation); // keeps the Instrumentation
    public static boolean installed();
    /**
     * Redefines, all or nothing, the classes among binaryNames that loader has defined, with definitionBytes(name).
     * Classes not loaded yet are skipped: they will load the new file. A refusal throws RedefinitionRefused with the
     * JVM's reason, and nothing changed.
     */
    public static Outcome redefine(ClassLoader loader, Collection<String> binaryNames,
            Function<String, byte[]> definitionBytes);
    public record Outcome(List<String> redefined, List<String> notLoaded) {}
}
```

`vidocq:dev` launches the child with, in addition to today's arguments:

- the agent jar on the `--module-path`, and its module in `--add-modules`;
- `-javaagent:<the same jar>`.

The JVM loads the `Premain-Class` through the application class loader. Because the jar is also a named module of
the boot layer, the class comes from that module, not from the class path. A trial on 2026-11-16 confirmed it: with
the jar on both paths, `premain` runs in `module spike.agent` and the application sees the same class with the
`Instrumentation` set. With the jar on the class path only, it runs in the unnamed module. So no JDWP, no attach, no
hand-off through system properties.

The core reaches the agent with `requires static io.vidocq.runtime.dev.agent` (the pattern of `requires static
java.sql`). It touches the agent's classes only after `ModuleLayer.boot().findModule("io.vidocq.runtime.dev.agent")`
says the module is there. A packaged application, or a `jlink` image, never resolves the module.

**Version.** The mojo resolves the agent at the version of the application's `vidocq-runtime-core` (base version),
as the dev console is resolved at the application's runtime version (#148). If that artifact does not exist (a runtime
older than the agent), the mojo logs once `Hot swap off: vidocq-runtime-dev-agent <v> not found, every change swaps
the application layer` and launches as today.

**Opt-out.** `-Dvidocq.dev.hotSwap=false` (default `true`): no agent, every change swaps. The agent is never added
when `vidocq.dev.hotReload=false` (respawn mode) or outside the layer mode.

### 2. Classifying a change: in the mojo, with the Class-File API

The mojo already decides when to reload; it also knows the project layout (classes directory, generated-sources
directories, resources). The classifier lives there: `ClassesSnapshot` and `HotSwapClassifier` in
`io.vidocq.runtime.maven.dev`, on `java.lang.classfile`.

**Snapshot.** The mojo records `target/classes` at session start (after the first build) and after every successful
recompile. For every file: its relative path, size and SHA-256. For every `.class` file: the class's fingerprint
below. Only files whose size or modification time moved are hashed and parsed again.

**What a change is compared against: the output the child runs.** Today the mojo does not wait for the child: it can
recompile and write a new request while the previous one is still unread, and the child then reads only the latest
one. With swaps only, that loses nothing, since a swap loads everything on disk. With redefinitions it would. So:

- the mojo starts the next recompile only once the previous request is acknowledged (`.ready`), or once its wait ran
  out (the existing `RELOAD_ACK_TIMEOUT`), or the child died. Changes saved in the meantime wait in the watcher, as
  they do during a recompile;
- the base of the classification is the snapshot of the last *acknowledged* request. After a timeout or a dead
  child, the state of the child is unknown and the next request is a swap.

A recompile that failed leaves the base as it was.

**Fingerprint of a class**, compared symbolically (resolved names and constants, never constant-pool indexes, so
that a renumbering is not a change):

- *shape*: access flags, name, superclass, interfaces; every class attribute except `BootstrapMethods`, `SourceFile`
  and `SourceDebugExtension`. `InnerClasses` entries whose inner class is outside the application, such as
  `java/lang/invoke/MethodHandles$Lookup` (added by the first lambda or string concatenation), are left out. Then each
  field (flags, name, descriptor, attributes) and each method (flags, name, descriptor, every attribute except
  `Code`). Annotations are part of the shape: the JVM would accept an annotation change, but the frameworks read
  annotations at start and would never see it.
- *code*, per method: its instructions with their operands resolved, the exception table, the line numbers and the
  local variable tables. The line numbers keep the debugger right after a change that only moved lines. Stack map
  frames are left out, as they derive from the rest.

**Classification** of the delta between the snapshot and the new output, in this order. The first rule that matches
decides:

1. No file changed: **nothing** to do. The log says `the compiled output did not change: nothing reloaded`.
2. A file added or removed (a new class, a new anonymous or switch-map class, a deleted one): **swap**.
3. A changed file that is not a class (`application.properties`, `META-INF/vauban-beans.list`, the frozen extension
   record, `META-INF/services`, static web resources), or `module-info.class`: **swap**. One exception: the files of
   `META-INF/vidocq/build-info/`. `build-info` runs in `initialize`, so in every recompile, and rewrites them with
   the build time to the second, the commit and the dirty flag. Without the exception, every change would swap. The
   running application keeps the build info of its last swap.
4. A changed class that does not come from the project's own sources: **swap**. "Its own sources" means a compile
   source root outside the build directory (`src/main/java` and the ones the pom adds), with the class's package
   and `SourceFile` found in it. Everything else is generated, with or without a source: `_VaubanComponents`, and
   the client proxies and `$$Intercepted` subclasses Vauban writes as bytecode through `Filer.createClassFile`
   (`GeneratedOutput`, `InterceptedArtefacts`, `ClientProxyArtefacts`). It also covers the output of Cassini and
   Champollion. Generated code mostly registers things at start.
5. A changed class whose shape differs: **swap**, naming the first difference (`MainView gained a method
   lambda$new$1`, `Order lost a field total`, `GreetingResource changed an annotation on hello()`).
6. A changed class whose code differs in a *start-time method*: **swap**. A start-time method ran once, before the
   change, and will not run again until a swap. The set below, as proposed (decision 1):
   - static initializers (`<clinit>`), always;
   - the constructors, initializer methods (`@PostConstruct`) and field initializers of *long-lived* objects. These
     are classes with a long-lived scope annotation: `jakarta.enterprise.context.ApplicationScoped` and
     `SessionScoped`, `jakarta.inject.Singleton`, and Vaadin's `UIScoped`, `VaadinSessionScoped` and `RouteScoped`.
     Also servlets, filters and listeners, by their `jakarta.servlet` supertypes or annotations;
   - producer methods (`jakarta.enterprise.inject.Produces`), and observers (a parameter annotated with
     `jakarta.enterprise.event.Observes` or `ObservesAsync`) of `jakarta.enterprise.event.Startup` or of an
     `@Initialized` event;
   - the start callbacks, by name and descriptor: `init(ServletConfig)` and `init()` of a servlet,
     `init(FilterConfig)` of a filter, `contextInitialized(ServletContextEvent)`,
     `onStartup(Set, ServletContext)`, `serviceInit(ServiceInitEvent)`, and JAX-RS `Application.getClasses()`,
     `getSingletons()` and `Feature.configure(FeatureContext)`;
   - every application method these call, directly or through other application methods: a call graph over the
     classes of `target/classes`, built from the snapshot's parsed classes. An `invokevirtual` or `invokeinterface`
     reaches the target and its overrides in the application. A lambda created in such a method is not reached:
     creating it does not run it, and a listener lambda is the most common edit.

   It is conservative on purpose. A method called both at start and per request swaps: a needless swap costs about
   0.2 s and a session, while a wrongly redefined change is a broken promise. The constructors of other objects (a
   Vaadin view, a `@Dependent` or `@RequestScoped` bean, a plain object) are not start-time. The next instance runs
   the new constructor: the trial's F5 case, with the session kept. The log says so: `new instances use it`.

   The annotation and callback lists are constants of the classifier, with a test per entry.
7. Otherwise: **redefine** the changed classes whose code differs. Classes whose bytes differ but whose fingerprint
   is equal are left alone.

A bean's `$$Intercepted` subclass and its client proxy are generated from its shape. A body-only change leaves them
byte-identical, and they call the redefined method, so interception and proxies keep working. A change that alters
them alters the shape, and rule 4 or 5 swaps.

### 3. The reload request: one file, as today

The mojo still writes the reload file the child polls (`target/.vidocq-dev-reload`). The stamp stays its modification
time, in milliseconds: the child compares it to the previous one and writes it to `.ready`, and `ReloadAck` reads it
back. The first line stays the `System.nanoTime()` it is today, which nothing reads. The lines after it are new:

```
<nanoTime>
redefine
io.vidocq.tools.vaadin.MainView
```

or

```
<nanoTime>
swap MainView gained a method lambda$new$1
```

A child from an older core reads only the modification time and swaps. A newer child reading a file without a second
line swaps. The `.ready` acknowledgement is unchanged (`<stamp>`), so continuous testing waits for both paths the same
way. The mojo logs `Hot reload signalled after N ms (class redefinition: 1 class)` or `… (layer swap: <reason>)`.
With the agent off it keeps today's line.

### 4. In the child: redefine, or fall back to the swap

`VidocqDevReloadLoop`, on a new stamp, reads the request:

- `redefine` with the agent installed: for each listed class, the bytes come from the new Vauban method below and go
  to `HotSwap.redefine` with the layer's loader. The loop does not shut down. It writes the acknowledgement and logs
  `Hot reload - redefined 1 class in 7 ms, the application keeps running: MainView`. If no listed class is loaded yet,
  it logs `… none of them loaded yet: they load the new code when first used`.
- `redefine` that fails (agent missing, a class whose bytes cannot be read, `RedefinitionRefused`): the loop logs
  `Hot reload - redefinition refused (<JVM reason>): restarting on a fresh application layer` and swaps. Nothing was
  redefined, since the JVM applies all or nothing.
- `swap <reason>`, or no mode line: today's swap. The existing line gains the reason: `Hot reload - restarting on a
  fresh application layer: <reason>`.

**Definition bytes: one additive Vauban method** (companion pull request in Vauban, merged and published first):

```java
/** The bytes findClass would define for binaryName now: read from its archive, through the transformer chain. */
public Optional<byte[]> VaubanClassLoader.definitionBytes(String binaryName)
```

It reuses `classIndex`, `readClass` and `applyTransformers`, without defining anything. A Vauban test checks that it
reads the archive again (a directory archive changed on disk returns the new bytes) and applies the transformers.

### 5. Start-time caches

- **Vaadin.** The route registry, the `@Route`/`@PageTitle` metadata and the reflection caches (bean property sets,
  `@ClientCallable`, `@EventHandler`) are built from annotations and signatures. Any change to them alters the shape,
  so it swaps. A body-only change keeps the caches valid. Event handlers run the new code at the next event. A view
  is not long-lived, so a change to its constructor is redefined: the open page keeps the old layout, and after F5 or
  a navigation the new view shows it, in the same session (the trial's second case). A view with
  `@PreserveOnRefresh`, or a `UIScoped` or `RouteScoped` one, is long-lived or kept, and rule 6 swaps.
- **Vauban generated code and bean metadata.** The bean index, `_VaubanComponents`, `$$Intercepted` subclasses,
  client proxies and the frozen build compatible extension record are all generated from the shape at
  `process-classes`. Either they are byte-identical (body-only change) or a generated class or resource changed and
  rules 3 and 4 swap.
- **CDI instances.** Instances already created keep their state. The next call of a business method runs the new
  body, through the client proxy and the interceptors as before.
- **What the fast path cannot see**, stated in the docs: a value a method computed and stored before the change (a
  memoized result, a cached template, a scheduled task) stays as computed. The swap remains one save away. The dev
  console could later offer a "swap now" action.

### 6. The debugger: a redefinition clears the breakpoints of the class

`vidocq:dev` opens a JDWP agent by default. A trial on 2026-11-16 (Temurin 25) checked what happens to a breakpoint
when the application redefines the class itself through `Instrumentation`. A JDI debugger set a breakpoint in
`Target.work` and counted the hits: 10 before the redefinition, **0 after**, and the debugger's request still said
`enabled`. The JVM clears the breakpoints of a redefined class, and only a debugger that did the redefinition itself
sets them again. The IDE would still show them as set, and they would never stop.

The child cannot tell whether a debugger is attached: the JDWP threads are hidden from `Thread.getAllStackTraces()`,
also checked on 2026-11-16. Nor can the mojo. So the fast path redefines anyway and warns (decision 2). When the
JDWP agent is loaded (the child's input arguments), the redefinition line adds `breakpoints in MainView no longer
stop: set them again, or debug with -Dvidocq.dev.hotSwap=false`, and the debugging docs say the same. A layer swap
keeps them working, since the new classes are new classes and the debugger sets its breakpoints again when they load.

### 7. Documentation

The usage page's "Dev reload" section gets the two paths, the rules in short, the log lines and
`vidocq.dev.hotSwap`. The Vaadin guide's `dev-reload-session` section says that a body-only change keeps the session.
The plugin reference lists `vidocq.dev.hotSwap` and the agent artifact.

## Tests (TDD, red first)

- **Vauban**: `VaubanClassLoaderTest`. `definitionBytes` returns the transformed bytes of a class, the new bytes after
  the file changed on disk, and empty for a class outside the archives.
- **Classifier** (plugin, `HotSwapClassifierTest`). Fixtures compiled in-test from source strings, with the system
  Java compiler (as `DepgenFixtures` does), so the real javac output is tested (lambdas, string concatenation, line
  numbers). Each rule gets a test:
  - redefine: a method body; a lambda body; a body change that moves the constant pool; a change that only moves
    lines (redefine, for the debugger);
  - swap, for structure: a new lambda; a new or removed method or field; a changed signature or modifier; an
    annotation added or changed on the class, a method, a field or a parameter; a new superclass or interface;
  - swap, for start-time code: a static initializer; the constructor, a field initializer and a `@PostConstruct` of
    each long-lived scope; each entry of the annotation and callback tables; a private helper called from a
    constructor of a long-lived bean; a method of another application class called from it; an override reached
    through `invokevirtual`;
  - redefine, not start-time: the constructor of a class without a long-lived scope (a Vaadin view); the body of a
    lambda created in a long-lived bean's constructor;
  - swap, for files: an added, removed or anonymous class; a changed resource; `module-info`; a generated class with
    a source under generated-sources; a generated class without any source (bytecode written by a processor);
  - not a change: the files of `META-INF/vidocq/build-info/` alone, which means nothing to reload;
  - nothing: identical output;
  - the reasons: `gained a method lambda$new$1` and the others.
- **Snapshot and base**: only moved files are parsed again; a failed recompile keeps the base; the base moves only
  on an acknowledgement; the next recompile waits for the acknowledgement; a timeout or a dead child makes the next
  request a swap.
- **Mojo**: the request content for each plan; the child's command line has the agent on both paths and in
  `--add-modules` only when the layer mode, the hot reload and `vidocq.dev.hotSwap` are on and the agent resolved;
  the "Hot swap off" line otherwise; the host log lines.
- **Core** (`VidocqDevReloadLoopTest`, with a stand-in redefiner): a redefine request redefines and does not shut the
  bootstrap down; a refusal swaps; no agent swaps; a request without a mode line swaps; the acknowledgement is written
  on every path; the log lines, with the breakpoint sentence only when a JDWP agent is in the input arguments.
- **Agent**: a test forks a JVM with a stand-in jar built in the test from the agent's classes (as
  `ConsoleLoggingProcessTest` builds its stand-in IntelliJ agent). It checks that `premain` runs in the named module,
  that a body change of a class of a child layer is redefined and live, and that an added method is refused with
  nothing changed.
- **Architecture tests**: the core's `requires static` on the agent, and the agent's use of `java.instrument`, get
  their documented entries if a rule needs one.
- **End to end**, by hand, on vaadin-on-vidocq after the merge, with the published snapshots: the three cases of the
  trial, plus a CDI bean's method body, with the dev log lines and the same JSESSIONID.

## Delivery

1. Vauban: `definitionBytes` (one pull request), merged and published.
2. Vidocq: the agent module, the core changes, the classifier, the mojo and the docs, in one pull request on
   `feat/dev-hotswap`. The usual gates apply: quality profile and integration tests on the whole reactor, then the
   local Sonar analysis before the push.
3. Replay on vaadin-on-vidocq.

## Decisions (Yann, 2026-11-16)

1. **How wide is "start-time"**: as proposed in rule 6. Static initializers always; the constructors and
   initializers of long-lived objects only; the producers, start observers and start callbacks; and the call graph
   from all of these. Edits to a Vaadin view's constructor are redefined and visible after F5 with the session kept.
   The stricter alternative, every constructor swaps, is not taken.
2. **Breakpoints**: a redefinition silently clears the breakpoints of the redefined class, and that is acceptable. The
   log and the docs warn about it (§6), and `-Dvidocq.dev.hotSwap=false` is the way to debug without it. The fast
   path stays on while the JDWP agent is on.

## Calls made in this design (to check)

- A change with no compiled difference reloads nothing (rule 1); today it swaps.
- `build-info` files are ignored by the classifier (rule 3).
- The mojo serializes the recompiles behind the acknowledgements, and classifies against the last acknowledged
  snapshot (§2).
- The agent is resolved at the application's `vidocq-runtime-core` version, and is a separate dev-only module that
  the core reaches with `requires static`. The alternative was the core jar as its own agent, which needs no new
  artifact but leaves a dormant `Premain-Class` in every packaged core.
- The definition bytes come from a new additive Vauban method, not from the raw file.
- The fast path is on by default (`vidocq.dev.hotSwap=true`).

## Left to the builder

- The names of the classes inside the plugin, and how the snapshot is stored in memory.
- How instructions are rendered for the comparison, provided operands are compared resolved.
- The exact wording of the log lines, within the shapes above.
