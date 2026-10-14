# Every brick's generator runs on dependency jars — design

Issue: Vidocq/vidocq#246. Brick sides: Vidocq/foy#43 (done, foy#46), Vidocq/mansart#40 and Vidocq/mansart#41.
Parent design: `2026-10-13-application-layer-dependencies-design.md`, whose developer-experience criteria 1, 2, 4,
5 and 6 this design implements.

## Goal

An external dependency is treated like application code (maintainer rule, 2026-10-13). Today `vidocq:generate`
runs only Vauban on dependency jars. The other bricks (Foy, Mansart, Cassini, Cyrano, Ravel, Peano) generate code
for the application's own sources only, through their annotation processors. So a dependency that needs a brick's
generated code gets none:

- Vaadin's `@WebListener` in `flow-server.jar` is never seen by Foy, and `/` answers 404.
- A library's Jakarta Data `@Repository` interfaces are never implemented by Mansart.

The developer gets, with nothing to declare:

1. `mvn vidocq:dev` (or `vidocq:generate` in any build) finds the dependency jars a brick must process, runs that
   brick's own annotation processor over their compiled classes, and puts the output in the jar's enriched copy.
2. This runs once per dependency version. A second build with the same jars and processors reuses the result.
3. When a brick cannot process a jar, the build fails with one line that names the jar, the brick, the member and
   the fix.
4. The startup report says how many libraries were enriched, by which brick; `vidocq:analyze-deps` gives the details.

## Spike evidence (2026-10-13, recorded on #246)

`javac -proc:only` with binary class names taken from a jar works for Foy's and Mansart's processors. They see
the RUNTIME and CLASS annotations and produce what they produce from sources, byte-identical for Mansart when the
jar was compiled with `-parameters`. Without `-parameters`, Mansart fails loudly on a query that names a
parameter. Foy now visits each type once (foy#46), so nested classes can be passed with their outer class.

## Design

### 1. Each `-codegen` bundle declares what it wants, as data

A brick's `-codegen` bundle jar (for example `vidocq-runtime-foy-servlet-extension-codegen`) ships
`META-INF/vidocq/dependency-codegen.properties`:

```properties
# The brick's name, as the build and the startup report print it.
brick=Foy
# A jar is a candidate when it holds one of these resources...
resources=META-INF/services/jakarta.servlet.ServletContainerInitializer,META-INF/web-fragment.xml
# ...or when one of its class files contains one of these byte strings (constant-pool search, no parse).
class-bytes=jakarta/servlet/annotation/Web,jakarta/servlet/annotation/HandlesTypes
# A jar holding one of these resources was already processed by this brick: it is skipped.
markers=META-INF/io.vidocq.foy/generated,META-INF/foy/class-index.list,META-INF/services/io.vidocq.foy.spi.gen.WebComponent
# Generated resources merged line by line with the jar's own copy, instead of replacing it.
merge=META-INF/services/io.vidocq.foy.spi.gen.WebComponent
# Generated resources that describe every class of the jar. The processors see only the hinted classes (section 4),
# so these would be partial: they are dropped from the output, and the brick's run-time fallback covers the jar
# (Foy scans a jar without class-index.list for @HandlesTypes itself; a partial index made it skip Vaadin's
# LookupInitializer, found on Vaadin, 2026-10-14).
whole-jar=META-INF/foy/class-index.list
```

It is data, not code: the plugin never loads a brick's classes to decide. The values come from the brick
(for Foy, `io.vidocq.foy.processor.DependencyHints`), and the bundle's own test checks that the file matches the
brick's constants. A bundle without this file takes no part. Vauban keeps its existing path
(`VaubanGenerator`, `ScanSelection`); it is not moved to this mechanism.

### 2. The plugin finds the bundles where the compiler finds them

`vidocq:generate` reads the `annotationProcessorPaths` of the project's `maven-compiler-plugin` (the walk
`vidocq:check-pom` already does), resolves each path with its transitive dependencies through Aether, and reads
each resolved bundle's `dependency-codegen.properties`. The processor classpath of a brick is that resolution.

### 3. Selection

For every dependency jar of the project (compile and runtime scopes; a reactor classes directory is skipped):

- **candidate for a brick** when one of the brick's `resources` exists or one of its `class-bytes` occurs in a class
  file of the jar;
- **skipped** when the jar holds one of the brick's `markers`, or when it is in `scanExcludes`;
- **signed jar**: skipped with one info line, unless `scanDependencies` names it, in which case its signatures are
  dropped from the enriched copy, as Vauban's enrichment already does.

- **not a candidate**: a jar of the Vidocq runtime itself (`groupId` `io.vidocq` or `io.vidocq.*`), and, for one
  `class-bytes` hint, a jar that defines classes under that hint (for example `jakarta.servlet-api`, which holds
  `jakarta/servlet/annotation/WebListener` itself: an API is not a user of its own annotations).

A jar selected by any brick is also scanned by Vauban, as application code always is: several bricks find their
components among CDI beans (Foy's `WebAppDiscovery`), so a brick's output alone is not enough (found by the
end-to-end test, 2026-10-14). In `ScanSelection` this is a new source, `selected by a brick (<Brick>)`, after
`scanDependencies`, the extensions' manifests and the bean-archive detection; `scanExcludes` and the processed
markers still apply.

`scanDependencies` and `scanExcludes` keep working as manual overrides. The selection does not depend on
`vidocq.app.layer`: a parent-layer jar is processed too. The startup report counts enriched archives of the
application layer only, so it shows them under `vidocq.app.layer=all`.

### 4. Generation

For one jar, all the bricks it is a candidate for run together, in one in-process `javac`
(`ToolProvider.getSystemJavaCompiler()`):

1. `-proc:only`, `-processorpath` = the union of the selected bricks' processor classpaths, `-classpath` = the jar
   plus the project's compile and runtime classpath, `-s <gensrc>`, `-d <genres>`, `--release` = the project's
   release; the class names are the top-level classes of the jar that contain one of the selected bricks'
   `class-bytes` hints, plus the classes named in the matched resource hints that are service files (base entries
   only). Passing every class made javac fail on classes that need an optional API absent from the build, such as
   atmosphere-runtime's websocket endpoints (found on Vaadin, 2026-10-14).
2. A second `javac` compiles the generated sources against the same classpath into `<genclasses>`.
3. Every generated class and resource lands in the patch directory of the jar
   (`target/vidocq-patches/<artifactId>/`), which `EnrichedJars` already turns into the enriched copy. Resources
   named in a brick's `merge` list are merged line by line with the jar's own copy (union, original order first);
   any other generated resource that the jar already has is an error that names both.
4. A jar that has no module descriptor and was selected by a brick stays an automatic module in its copy, even when
   Vauban also generated providers for it: a synthesized descriptor must list every `uses`, and its detection misses
   `ServiceLoader.load(Class, ClassLoader)` (Vaadin's `FeatureFlagProvider`, found on Vaadin, 2026-10-14), while an
   automatic module may use any service and gets its `provides` from `META-INF/services`. Jars Vauban selects by its
   own rules keep today's synthesized descriptor.
5. The enriched copy's manifest gains `Vidocq-Enriched-By: <brick>, <brick>` (sorted), next to the existing
   `Vidocq-Enriched-From/-Digest/-Inputs`.
6. The enrichment inputs digest gains one line per brick: `brick <name> <bundle coordinates> <sha256 of the
   processor classpath>`, so that a processor change rewrites the copy.

### 5. Once per dependency version

The output of step 4.1–4.2 for one jar is cached under the user's cache directory,
`${user.home}/.cache/vidocq/depgen/<key>/` (`XDG_CACHE_HOME` when set; `vidocq.generate.cacheDir` overrides it).
The key is the SHA-256 of: the jar's SHA-256, each selected brick's name and processor-classpath SHA-256, the
jar's compile classpath as sorted `groupId:artifactId:version` coordinates (a processor's output can depend on the
types it resolves there, such as Foy's `class-index.list` of resolved supertypes), the `--release` value and the
running JDK's feature version. The selection decision of a jar no brick wants is memoized the same way (jar SHA-256
and the hints' digest), so that a build does not search every class of every jar again. A hit copies the cached files into the patch directory and
runs no `javac`. The cache is content-addressed, so it is shared safely between projects and survives
`mvn clean`. Entries are written to a temporary directory and moved into place atomically; a damaged entry (missing
its completion file) is ignored and rewritten.

### 6. One line when a brick cannot process a jar

A processor `ERROR` diagnostic, or a generated source that does not compile, fails the build with one line per
problem:

```
vidocq:generate: books-1.0.jar — Mansart cannot process com.acme.Books.byTitle: @Query references :title but the
method has no parameter with that name (the jar was compiled without -parameters). Use @Param in the library, or
leave the jar out with <scanExcludes>com.acme:books</scanExcludes>.
```

The line names the jar, the brick, the type and member (from the diagnostic), the processor's message and the
exclusion that unblocks the build. No stack trace; the javac output of that jar is written to
`target/vidocq-depgen/<artifactId>.log` and the line points to it. Processor `WARNING`s become build warnings with
the same prefix. Nothing is cached for a failed jar.

### 7. Visible at startup and in `vidocq:analyze-deps`

- The `layer` section of the startup report adds `<n> enriched (Foy 1, Mansart 2)` to its headline when any
  archive of the application layer carries `Vidocq-Enriched-By`, read from the archives' manifests when the report
  is built. The detailed report lists the bricks on each enriched archive's row.
- `vidocq:analyze-deps` adds, per dependency: the bricks it is a candidate for and which hint matched, the brick
  markers found, whether it is cached, and what was generated the last time (class count per brick).

### 8. Dropped from the parent design

Explicit descriptors for every automatic jar of the application layer (parent design §4 and order of work item 6)
are dropped: since vidocq#249, `vidocq:package` no longer uses the trampoline under `all`, and a plain IDE run of
`main` reads the original jars, never the enriched copies, so a descriptor in the copy would change nothing. The
documented limit of the trampoline stays.

## Bricks

| Brick | Bundle | Hints | Work in its repository |
|---|---|---|---|
| Foy | `vidocq-runtime-foy-servlet-extension-codegen` | `DependencyHints` (foy#46) | none: done |
| Mansart | `vidocq-runtime-mansart-data-extension-codegen` | `jakarta/data/repository/Repository` | mansart#40 (marker, ignore other providers' generated classes), mansart#41 (`@Param`, `@Find`, JDQL without `FROM`) |
| Cassini, Cyrano, Ravel, Peano | their `-codegen` bundles | to define | later, one issue each, when a real case shows up |

Mansart's separate external-jar path (`ExternalRepositoryCodegen`, relocation into an application package) is not
used by `vidocq:generate`: the output goes into the jar's enriched copy, like every other brick.

## Tests, one per risk

1. **Selection**: unit tests on synthetic jars: a resource hint, a class-byte hint, a marker, a signed jar, an
   excluded jar, a jar no brick wants.
2. **Hints file**: parsing, a missing key, an unknown key (error naming the bundle); the Foy bundle's file matches
   `DependencyHints`.
3. **Generation, Foy**: a jar holding a `@WebServlet` and a `@WebListener` gets its `$$FoyComponent` classes, the
   merged `WebComponent` services file and `class-index.list`, and `Vidocq-Enriched-By: Foy`.
4. **Merge**: a jar that already ships a `WebComponent` services file keeps its lines and gains the new ones.
5. **Cache**: a second run with the same inputs runs no `javac` (counter) and produces a byte-identical copy; a
   changed processor jar misses the cache; a damaged entry is rewritten.
6. **Failure**: a Mansart repository jar compiled without `-parameters` fails the build with the one line of
   section 6 (once mansart#40 lands; until then, the same with a fixture processor that reports an error).
7. **End to end**: an IT whose fixture depends on a jar holding a `@WebListener` (a minimal Vaadin stand-in) and
   no workaround class: under `vidocq:run` the listener runs, and the startup report says `1 enriched (Foy 1)`.
8. **The Vaadin spike** boots without its `@WebListener` subclass (manual check, recorded in the PR).

## Out of scope

- Cassini, Cyrano, Ravel and Peano hints (later issues).
- SOURCE-retention annotations, which class files cannot carry (documented).
- Generating for classes under `META-INF/versions/N/` of a multi-release jar (documented; base classes only).

## Order of work

1. Plugin: the hints file and its reader, the bundle resolution, the selection (tests 1, 2).
2. Plugin: generation, merge, `Vidocq-Enriched-By`, the inputs digest, the diagnostics (tests 3, 4, 6 with a
   fixture processor).
3. Plugin: the cache (test 5).
4. The Foy bundle's hints file; the startup report line and `vidocq:analyze-deps` (test 7, 8); docs and What's new.
5. Mansart: mansart#41 then mansart#40 in the mansart repository; then its bundle's hints file and test 6 for real.
