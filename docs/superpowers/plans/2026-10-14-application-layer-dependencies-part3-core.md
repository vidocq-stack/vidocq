# The application layer holds the application's dependencies — part 3, Vidocq core (vidocq#247)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `vidocq.app.layer=all` behind a setting (default `classes`): the application layer holds the application and every dependency outside the runtime's closure, for `vidocq:run`, `vidocq:dev`, `vidocq:package` and the `Vidocq.run` trampoline.

**Architecture:** One pure helper, `LayerSplit` in `vidocq-runtime-core`, decides the split from module descriptors: the runtime (name rules, Vidocq extensions, modules whose packages the Vauban loader refuses, pinned modules) plus its `requires` closure stays in the parent layer, everything else goes to the application layer, automatic modules included. The Maven plugin calls it at build time to cut the module path and `-Dvidocq.app.path`; `VidocqAppLayer` calls it for the trampoline and hands the kept roots to Vauban's four-argument `applicationPaths`. The dev reload deregisters the JDBC drivers of the closing layer and warns about threads that still hold it.

**Tech Stack:** Java 25 (Class-File API, `java.lang.module`), Maven 3.9.16 plugin (hand-written `plugin.xml`), JUnit 5, Failsafe + maven-invoker-plugin for the ITs.

**Spec:** `docs/superpowers/specs/2026-10-13-application-layer-dependencies-design.md` (branch `docs/app-layer-dependencies-design`). This plan covers its "Order of work" item 4. Items 5 (reload cache, with the before/after reload measurement of §4 in BENCH.md), 6 (vidocq#246) and 7 (campaign, `all` as default) are later plans. Already done: vauban#155/#156 (code source, multi-release jars, kept roots; tests 4 and 5) and mansart#43 (the pool's drivers; test 2).

## Global Constraints

- Java 25, Maven 3.9.16, `modelVersion 4.0.0`; no new runtime dependency (core and extensions stay zero-dep).
- English for code, Javadoc, comments, commits, docs. Commits signed and signed-off: `git commit -s -S`, Conventional Commits with the ticket, ending with the trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY`.
- TDD: every production change has a test that failed first.
- Verification runs in an **isolated local Maven repository** (`-Dmaven.repo.local=<scratchpad>/m2-applayer`), never `~/.m2`. Maven is run through a `.sh` script under the scratchpad (`bash script.sh`), because a hook redirects direct `mvn`/`./mvnw` calls.
- `vidocq.app.layer` accepts exactly `classes` (default) and `all`; any other value fails with a message naming the property and both values.
- `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml` is written by hand: every new mojo parameter gets a `<parameter>` entry **and** a `<configuration>` entry.
- Visible changes get a What's new entry and a `[.tag-new]#NEW#` badge on the documented section, in the same PR.
- Never touch the erasmus or foy repositories.

## Review Focus

1. **A dependency jar whose automatic module name cannot be derived** (`ModuleFinder` throws `FindException`): the build must fail with one message naming the jar, not a stack trace from deep inside `ModuleFinder`. Pinned by `ApplicationLaunchLayerTest.aJarWithoutAModuleNameFailsWithItsFileName` (Task 4).
2. **A library that a Vidocq extension reads and that is an automatic module** (Liquibase's `liquibase.core`, read by the Liquibase migration extension): it must stay in the parent layer, otherwise the boot layer cannot resolve the extension. Pinned by `LayerSplitTest.anAutomaticModuleReadByAnExtensionStays` (Task 2).
3. **An automatic library with a `javax.*` package** (jsr305's `javax.annotation`): the Vauban loader refuses to define `javax.*`, so it must stay in the parent layer. Pinned by `LayerSplitTest.aModuleWithAPackageTheLoaderRefusesStays` (Task 2).
4. **A dependency with classes parked by `vidocq:generate` for `--patch-module`**: `--patch-module` only patches boot-layer modules, so the jar must stay in the parent layer and be listed as pinned with that reason. Pinned by `ApplicationLaunchLayerTest.aPatchedJarStaysInTheParentLayer` (Task 4).
5. **An application whose own module name starts with `io.vidocq.`** (every example of this repository): the name rule must not keep it, nor drag what it reads. Pinned by `LayerSplitTest.anApplicationModuleUnderARuntimePrefixMoves` (Task 2).

---

## File structure

| File | Responsibility |
|---|---|
| vauban: `vauban-classloader/.../VaubanClassLoader.java` | `excludesPackage` becomes public (Task 1) |
| `vidocq-runtime-core/.../core/LayerSplit.java` (new) | the split rule, the setting names, pattern matching, the pinned-list encoding |
| `vidocq-runtime-core/.../core/VidocqAppLayer.java` | trampoline uses `LayerSplit` under `all`; `Installation` carries the pinned list; reset deregisters drivers and warns about threads |
| `vidocq-runtime-core/.../core/LayerJdbcDrivers.java` (new) | deregisters the JDBC drivers a closing layer defined |
| `vidocq-runtime-core/.../core/JdbcLeakPrevention.java` (new) | the helper whose bytes are defined inside the closing layer |
| `vidocq-runtime-core/.../core/LayerThreads.java` (new) | finds live threads that still hold a layer's loader |
| `vidocq-runtime-core/.../core/StartupFacts.java`, `report/CoreSections.java` | library count and pinned rows in the `layer` section |
| `vidocq-runtime-maven-plugin/.../maven/ApplicationLaunch.java` | `layered(...)`: the build-time split for run and dev |
| `vidocq-runtime-maven-plugin/.../maven/VidocqRunMojo.java`, `dev/VidocqDevMojo.java`, `VidocqPackageMojo.java`, `plugin.xml` | the `vidocq.app.layer` / `vidocq.app.layer.keep` parameters |
| `vidocq-runtime-extensions/.../migration/MigrationExtension.java` | migrators through the context loader |
| `vidocq-runtime-extensions/.../liquibase/ApplicationDrivers.java` (new), `LiquibaseSchemaMigrator.java`, `module-info.java` | Liquibase connects through `Driver.connect` with the application's loader |
| `vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer/` (new) | the three launch-shape ITs on a flat-classpath-library fixture |
| `docs/en/modules/ROOT/pages/...` | usage, plugin reference, What's new |

---

### Task 1: Vauban — `VaubanClassLoader.excludesPackage` becomes public

The build-time split must keep a jar whose packages the Vauban loader refuses to define (`javax.*`, `sun.*`…) in the parent layer, with the exact rule the loader applies. That rule is package-private today.

Repository: `vauban` (not vidocq). Worktree: `git -C /Users/yblazart/projects/perso/vidocq/vauban fetch origin && git -C /Users/yblazart/projects/perso/vidocq/vauban worktree add -b feat/154-excludes-package-public <scratchpad>/wt-vauban-excl origin/main`.

**Files:**
- Modify: `vauban-classloader/src/main/java/io/vidocq/vauban/classloader/VaubanClassLoader.java` (method `excludesPackage`, around line 425)
- Test: `vauban-classloader/src/test/java/io/vidocq/vauban/classloader/ExcludedPackagesTest.java` (new)
- Modify: the What's new page of Vauban's docs (`docs/**/whats-new.adoc`, find it with `git ls-files | grep whats-new`)

**Interfaces:**
- Produces: `public static boolean io.vidocq.vauban.classloader.VaubanClassLoader.excludesPackage(String pkg)` — `true` for `java.x`, `javax.annotation`, `jdk.internal`, `sun.misc`, `com.sun.net`, the Vauban container packages; `false` for `acme.app`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.vauban.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packages the loader refuses to define, as a launcher outside this module reads them (vauban#154). */
@DisplayName("VaubanClassLoader.excludesPackage — public, same rule as the loader")
class ExcludedPackagesTest {

    @Test
    @DisplayName("a launcher outside the module can call it")
    void isPublic() throws Exception {
        var method = VaubanClassLoader.class.getDeclaredMethod("excludesPackage", String.class);
        assertTrue(Modifier.isPublic(method.getModifiers()), "Vidocq's build-time split needs the rule");
    }

    @Test
    @DisplayName("platform and container packages are refused, an application package is not")
    void rule() {
        assertTrue(VaubanClassLoader.excludesPackage("javax.annotation"));
        assertTrue(VaubanClassLoader.excludesPackage("sun.misc"));
        assertTrue(VaubanClassLoader.excludesPackage("io.vidocq.vauban.core.impl"));
        assertFalse(VaubanClassLoader.excludesPackage("acme.app"));
        assertFalse(VaubanClassLoader.excludesPackage("javaxx.app"), "a prefix match stops at the dot");
    }
}
```

- [ ] **Step 2: Run it, watch `isPublic` fail**

Script `<scratchpad>/vauban-excl-test.sh`:
```bash
#!/bin/bash
S=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq/d7ea8f44-fd18-4b7c-9213-1d2b758c463b/scratchpad
cd $S/wt-vauban-excl || exit 1
source ~/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1
export MAVEN_OPTS="-Dmaven.repo.local=$S/m2-applayer"
./mvnw -B -ntp -pl vauban-classloader -am install -Dtest=ExcludedPackagesTest -Dsurefire.failIfNoSpecifiedTests=false > $S/vexcl-$1.log 2>&1
echo "rc=$?"; grep -E 'Tests run:|BUILD|FAIL' $S/vexcl-$1.log | tail -6
```
Run: `bash <scratchpad>/vauban-excl-test.sh red`
Expected: `isPublic` FAILS ("Vidocq's build-time split needs the rule"); `rule` passes.

- [ ] **Step 3: Make it public**

In `VaubanClassLoader.java`, change the signature and Javadoc:
```java
    /**
     * Whether this loader refuses to define the classes of package {@code pkg} (platform and
     * container prefixes). A launcher keeps a module owning such a package in its parent layer:
     * re-layered, it would be an empty shell whose classes come back from its parent twin. Public
     * so that a build-time split applies the same rule (vauban#154).
     */
    public static boolean excludesPackage(String pkg) {
        return isExcluded(pkg + ".");
    }
```

- [ ] **Step 4: Run it, then the module's whole suite**

Run: `bash <scratchpad>/vauban-excl-test.sh green` → both tests PASS.
Then the full build with tests (`-Ptck` not needed: no behaviour change): edit the script's mvn line to `./mvnw -B -ntp install` and run `bash <scratchpad>/vauban-excl-test.sh full`. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: What's new, commit, push, PR**

Add one bullet to Vauban's What's new page under the current 0.4.0 section: "`VaubanClassLoader.excludesPackage` is public, so that a launcher or a build tool applies the loader's own rule (vauban#154)." with `[.tag-new]#NEW#` on the bullet.

```bash
git add -A && git commit -s -S -m "feat(classloader): excludesPackage is public for build-time layer splits (#154)

Vidocq's build-time split (vidocq#247) keeps a jar whose packages the loader
refuses in the parent layer, with the loader's own rule.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
git push -u origin feat/154-excludes-package-public
```
Open the PR on Codefloe (`Vidocq/vauban`, base `main`) through the API, body ending with the Claude Code footer. **Do not merge**: the maintainer merges. Tasks 2+ build against this branch installed in the isolated repository (`m2-applayer`), so they do not wait for the merge; the vidocq PR waits for it.

---

### Task 2: `LayerSplit` — the rule

**Files:**
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/LayerSplit.java`
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/LayerSplitTest.java`

**Interfaces:**
- Consumes: `VaubanClassLoader.excludesPackage(String)` (Task 1).
- Produces (all in `io.vidocq.runtime.core`, an exported package):

```java
public final class LayerSplit {
    public static final String MODE_PROPERTY = "vidocq.app.layer";
    public static final String KEEP_PROPERTY = "vidocq.app.layer.keep";
    public static final String PINNED_PROPERTY = "vidocq.app.layer.pinned";
    public static final String PARENT_LAYER_ATTRIBUTE = "Vidocq-Parent-Layer";
    public enum Mode { CLASSES, ALL; public static Mode of(String value); }
    public record Candidate(ModuleDescriptor descriptor, Path location, String coordinates, List<String> parentLayerPatterns) {}
    public record Result(List<Candidate> parent, List<Candidate> application, Map<String, String> pinned) {
        public List<Path> parentPaths();
        public List<Path> applicationPaths();
        public Set<String> parentNames();
    }
    public static Result split(List<Candidate> candidates, Set<String> applicationModules,
                               List<String> keepPatterns, Map<String, String> forced);
    public static Candidate candidate(Path jarOrDir, String coordinates) throws IOException;
    public static List<String> patterns(String commaSeparated);
    public static String encodePinned(Map<String, String> pinned);
    public static Map<String, String> decodePinned(String encoded);
}
```

The rule, in order:
1. A module named in `applicationModules` is never kept by a rule (it is the application).
2. Seeds of the parent layer: a name under `java.`, `jdk.`, `jakarta.`, `org.eclipse.microprofile.` or `io.vidocq.`; a module that `provides io.vidocq.runtime.spi.VidocqExtension`; a module with a package `VaubanClassLoader.excludesPackage` refuses; a module matched by a `Vidocq-Parent-Layer` pattern of any candidate (reason `Vidocq-Parent-Layer of <declaring module>`), by a `keepPatterns` entry (reason `vidocq.app.layer.keep`) or named in `forced` (reason: the map's value).
3. Closure: every module a kept module `requires`, transitively, is kept. An automatic module is kept when reached but its own reads are not followed (it reads everything). A `requires` naming no candidate is ignored (a platform module or a `static` dependency that is absent).
4. If the closure of a seed reaches a module of `applicationModules`, `split` throws `IllegalStateException`: `"<seed> is kept in the parent layer (<reason>) but reads <app>, a module of the application: a parent-layer module cannot read the application layer. Remove the pin, or move the code it needs out of <app>."` (for a name-rule seed the reason is `a runtime module`).
5. Everything else goes to the application layer, automatic modules included. Both lists keep the candidates' order. `pinned` holds the pinned and forced seeds only, in candidate order.

A pattern containing `:` is `groupId:artifactId` matched against `coordinates` (`null` coordinates never match); any other pattern is a module name. In both, `*` alone matches anything and a trailing `*` is a prefix match.

- [ ] **Step 1: Write the failing tests**

```java
package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which modules stay in the parent layer and which move to the application layer (spec §1, §2; test 6). */
@DisplayName("LayerSplit — the closure of the runtime stays, the rest is the application's")
class LayerSplitTest {

    private static final String EXTENSION = "io.vidocq.runtime.spi.VidocqExtension";

    @Test
    @DisplayName("an automatic module goes to the application layer; a library a brick reads stays")
    void automaticMovesBrickDependencyStays() {
        var core = explicit("io.vidocq.runtime.core", "acme.codec");
        var codec = explicit("acme.codec");
        var vaadin = automatic("flow.server");
        var result = LayerSplit.split(List.of(core, codec, vaadin), Set.of("acme.app"), List.of(), Map.of());

        assertEquals(List.of("io.vidocq.runtime.core", "acme.codec"), names(result.parent()));
        assertEquals(List.of("flow.server"), names(result.application()));
        assertEquals(Map.of(), result.pinned(), "name rules and closures are not pins");
    }

    @Test
    @DisplayName("a module providing a Vidocq extension is a runtime root, whatever its name")
    void aThirdPartyExtensionIsARoot() {
        var ext = new LayerSplit.Candidate(ModuleDescriptor.newModule("org.acme.vidocq.ext")
                .requires("acme.client").packages(Set.of("org.acme.vidocq.ext"))
                .provides(EXTENSION, List.of("org.acme.vidocq.ext.Ext")).build(),
                Path.of("/libs/org.acme.vidocq.ext.jar"), null, List.of());
        var client = explicit("acme.client");
        var result = LayerSplit.split(List.of(ext, client), Set.of(), List.of(), Map.of());

        assertEquals(List.of("org.acme.vidocq.ext", "acme.client"), names(result.parent()));
    }

    @Test
    @DisplayName("an automatic module read by an extension stays, and its own reads are not followed")
    void anAutomaticModuleReadByAnExtensionStays() {
        var ext = explicit("io.vidocq.runtime.extensions.essentials.migration.liquibase", "liquibase.core");
        var liquibase = automatic("liquibase.core");
        var other = automatic("acme.other");
        var result = LayerSplit.split(List.of(ext, liquibase, other), Set.of(), List.of(), Map.of());

        assertEquals(List.of(ext.descriptor().name(), "liquibase.core"), names(result.parent()));
        assertEquals(List.of("acme.other"), names(result.application()),
                "an automatic module reads every module: following it would keep everything");
    }

    @Test
    @DisplayName("a module with a package the Vauban loader refuses stays")
    void aModuleWithAPackageTheLoaderRefusesStays() {
        var jsr305 = new LayerSplit.Candidate(ModuleDescriptor.newAutomaticModule("jsr305")
                .packages(Set.of("javax.annotation")).build(), Path.of("/libs/jsr305.jar"), null, List.of());
        var result = LayerSplit.split(List.of(jsr305), Set.of(), List.of(), Map.of());

        assertEquals(List.of("jsr305"), names(result.parent()),
                "the loader refuses javax.*: in the application layer it would be an empty shell");
    }

    @Test
    @DisplayName("an application module under a runtime prefix moves, and so does what it reads")
    void anApplicationModuleUnderARuntimePrefixMoves() {
        var app = explicit("io.vidocq.runtime.examples.servlet", "acme.pricing");
        var pricing = explicit("acme.pricing");
        var result = LayerSplit.split(List.of(app, pricing), Set.of("io.vidocq.runtime.examples.servlet"),
                List.of(), Map.of());

        assertEquals(List.of("io.vidocq.runtime.examples.servlet", "acme.pricing"), names(result.application()));
    }

    @Test
    @DisplayName("a pinned module drags its closure and is listed with its reason")
    void aPinnedModuleDragsItsClosure() {
        var jni = automaticWith("acme.jni", null, List.of());
        var vaadin = explicitWith("flow.server", "com.vaadin:flow-server", List.of(), "flow.commons");
        var commons = explicit("flow.commons");
        var brick = explicitWith("io.vidocq.foy.core", null, List.of("flow.server")); // its Vidocq-Parent-Layer
        var result = LayerSplit.split(List.of(jni, vaadin, commons, brick), Set.of(), List.of("acme.j*"),
                Map.of());

        assertEquals(List.of("acme.jni", "flow.server", "flow.commons", "io.vidocq.foy.core"),
                names(result.parent()));
        assertEquals(new LinkedHashMap<>(Map.of(
                        "acme.jni", "vidocq.app.layer.keep",
                        "flow.server", "Vidocq-Parent-Layer of io.vidocq.foy.core")).entrySet(),
                result.pinned().entrySet());
    }

    @Test
    @DisplayName("an artifact pattern matches the coordinates; a candidate without coordinates never matches it")
    void artifactPatterns() {
        var vaadin = explicitWith("flow.server", "com.vaadin:flow-server", List.of());
        var noCoordinates = explicitWith("flow.client", null, List.of());
        var result = LayerSplit.split(List.of(vaadin, noCoordinates), Set.of(), List.of("com.vaadin:*"),
                Map.of());

        assertEquals(List.of("flow.server"), names(result.parent()));
        assertEquals(List.of("flow.client"), names(result.application()));
    }

    @Test
    @DisplayName("a forced module stays with the given reason")
    void forced() {
        var patched = explicit("acme.patched");
        var result = LayerSplit.split(List.of(patched), Set.of(), List.of(),
                Map.of("acme.patched", "patched with --patch-module"));

        assertEquals(Map.of("acme.patched", "patched with --patch-module"), result.pinned());
    }

    @Test
    @DisplayName("a pinned module that reads an application module fails, naming both")
    void aPinnedModuleThatReadsTheApplicationFails() {
        var helper = explicit("acme.helper", "acme.app");
        var app = explicit("acme.app");
        var e = assertThrows(IllegalStateException.class, () -> LayerSplit.split(List.of(helper, app),
                Set.of("acme.app"), List.of("acme.helper"), Map.of()));

        assertTrue(e.getMessage().startsWith("acme.helper is kept in the parent layer (vidocq.app.layer.keep) "
                + "but reads acme.app, a module of the application"), e.getMessage());
    }

    @Test
    @DisplayName("the mode accepts classes and all, nothing else")
    void mode() {
        assertEquals(LayerSplit.Mode.CLASSES, LayerSplit.Mode.of(null));
        assertEquals(LayerSplit.Mode.CLASSES, LayerSplit.Mode.of(" classes "));
        assertEquals(LayerSplit.Mode.ALL, LayerSplit.Mode.of("all"));
        var e = assertThrows(IllegalArgumentException.class, () -> LayerSplit.Mode.of("libs"));
        assertEquals("vidocq.app.layer must be classes or all, not 'libs'", e.getMessage());
    }

    @Test
    @DisplayName("the pinned list survives a system property")
    void pinnedEncoding() {
        var pinned = new LinkedHashMap<String, String>();
        pinned.put("acme.jni", "vidocq.app.layer.keep");
        pinned.put("flow.server", "Vidocq-Parent-Layer of io.vidocq.foy.core");
        assertEquals(pinned, LayerSplit.decodePinned(LayerSplit.encodePinned(pinned)));
        assertEquals(Map.of(), LayerSplit.decodePinned(""));
        assertEquals(Map.of(), LayerSplit.decodePinned(null));
    }

    @Test
    @DisplayName("a candidate is read from a jar: its descriptor and its Vidocq-Parent-Layer patterns")
    void candidateFromAJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("acme-lib-1.0.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "acme.lib");
        manifest.getMainAttributes().putValue(LayerSplit.PARENT_LAYER_ATTRIBUTE, "flow.server, com.vaadin:*");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("acme/lib/Lib.class"));
            out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            out.closeEntry();
        }
        var candidate = LayerSplit.candidate(jar, "acme:acme-lib");

        assertEquals("acme.lib", candidate.descriptor().name());
        assertTrue(candidate.descriptor().isAutomatic());
        assertEquals(List.of("flow.server", "com.vaadin:*"), candidate.parentLayerPatterns());
        assertEquals("acme:acme-lib", candidate.coordinates());
    }

    // ---------------------------------------------------------------- fixtures

    private static List<String> names(List<LayerSplit.Candidate> candidates) {
        return candidates.stream().map(c -> c.descriptor().name()).toList();
    }

    private static LayerSplit.Candidate explicit(String name, String... requires) {
        return explicitWith(name, null, List.of(), requires);
    }

    private static LayerSplit.Candidate explicitWith(String name, String coordinates, List<String> patterns,
                                                     String... requires) {
        var builder = ModuleDescriptor.newModule(name).packages(Set.of(name));
        for (var required : requires) {
            builder.requires(required);
        }
        return new LayerSplit.Candidate(builder.build(), Path.of("/libs/" + name + ".jar"), coordinates, patterns);
    }

    private static LayerSplit.Candidate automatic(String name) {
        return automaticWith(name, null, List.of());
    }

    private static LayerSplit.Candidate automaticWith(String name, String coordinates, List<String> patterns) {
        return new LayerSplit.Candidate(ModuleDescriptor.newAutomaticModule(name).packages(Set.of(name)).build(),
                Path.of("/libs/" + name + ".jar"), coordinates, patterns);
    }
}
```

In `aPinnedModuleDragsItsClosure`, the third argument of `explicitWith` for `io.vidocq.foy.core` is its `Vidocq-Parent-Layer` patterns; it has no `requires`, so `flow.server` is in the parent because of the pin, and `flow.commons` because `flow.server` requires it.

- [ ] **Step 2: Run them, watch them fail to compile**

Script `<scratchpad>/core-test.sh` (reused by later tasks):
```bash
#!/bin/bash
# Runs tests of one vidocq module in the wt-applayer worktree; usage: core-test.sh <tag> <module path> [mvn args]
S=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq/d7ea8f44-fd18-4b7c-9213-1d2b758c463b/scratchpad
cd $S/wt-applayer || exit 1
source ~/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1
TAG=$1; MOD=$2; shift 2
export MAVEN_OPTS="-Dmaven.repo.local=$S/m2-applayer"
./mvnw -B -ntp -pl "$MOD" -am install -Dsurefire.failIfNoSpecifiedTests=false "$@" > $S/applayer-$TAG.log 2>&1
echo "rc=$?"
grep -E 'Tests run:.*Fail|BUILD|ERROR\]   ' $S/applayer-$TAG.log | tail -12 | cut -c1-230
```
Run: `bash <scratchpad>/core-test.sh t2-red vidocq-runtime-core -Dtest=LayerSplitTest`
Expected: `BUILD FAILURE`, compilation error `cannot find symbol: class LayerSplit`.

The isolated repository must hold the Task 1 Vauban branch: before this run, install it once with `bash <scratchpad>/vauban-excl-test.sh full` (Task 1, Step 4 already did, same `m2-applayer`).

- [ ] **Step 3: Implement `LayerSplit`**

```java
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Which modules of a launch stay in the parent layer and which form the application layer, when the application
 * layer holds the application's dependencies ({@value #MODE_PROPERTY}{@code =all}, Vidocq/vidocq#247).
 *
 * <p>The parent layer is the closure of the runtime: the platform, Jakarta and MicroProfile APIs and the Vidocq
 * modules by name, every module that provides a Vidocq extension, every module with a package the Vauban loader
 * refuses to define, every pinned module, and, transitively, what they {@code requires}. Everything else is the
 * application's, automatic modules included: a framework that looks things up through its own class loader then
 * sees the application's classes and resources, as in a servlet container's {@code WEB-INF/lib}.
 *
 * <p>One rule for every launch shape: the Maven plugin applies it at build time, {@link VidocqAppLayer} applies it
 * to the boot layer of an IDE launch.
 */
public final class LayerSplit {

    /** {@code classes} (the project's classes only) or {@code all} (this rule). */
    public static final String MODE_PROPERTY = "vidocq.app.layer";
    /** Comma-separated patterns of modules the application keeps in the parent layer. */
    public static final String KEEP_PROPERTY = "vidocq.app.layer.keep";
    /** The pinned modules and their reasons, as the build computed them, for the startup report. */
    public static final String PINNED_PROPERTY = "vidocq.app.layer.pinned";
    /** Manifest attribute by which a jar pins modules to the parent layer: comma-separated patterns. */
    public static final String PARENT_LAYER_ATTRIBUTE = "Vidocq-Parent-Layer";

    private static final String EXTENSION_SERVICE = "io.vidocq.runtime.spi.VidocqExtension";
    private static final List<String> RUNTIME_PREFIXES = List.of(
            "java.", "jdk.", "jakarta.", "org.eclipse.microprofile.", "io.vidocq.");

    /** The value of {@value #MODE_PROPERTY}. */
    public enum Mode {
        CLASSES, ALL;

        /** {@code classes} when {@code value} is {@code null} or blank. */
        public static Mode of(String value) {
            String v = value == null ? "" : value.strip();
            return switch (v) {
                case "", "classes" -> CLASSES;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException(
                        MODE_PROPERTY + " must be classes or all, not '" + value + "'");
            };
        }
    }

    /**
     * One module of the launch.
     *
     * @param descriptor          its descriptor, derived for an automatic module
     * @param location            the jar or directory it comes from
     * @param coordinates         {@code groupId:artifactId}, or {@code null} when unknown (an IDE launch)
     * @param parentLayerPatterns the patterns of its {@value #PARENT_LAYER_ATTRIBUTE} manifest attribute
     */
    public record Candidate(ModuleDescriptor descriptor, Path location, String coordinates,
                            List<String> parentLayerPatterns) {
        public Candidate {
            parentLayerPatterns = List.copyOf(parentLayerPatterns);
        }
    }

    /**
     * The split.
     *
     * @param parent      the modules of the parent layer, in the candidates' order
     * @param application the modules of the application layer, in the candidates' order
     * @param pinned      each pinned or forced module and why, in the candidates' order
     */
    public record Result(List<Candidate> parent, List<Candidate> application, Map<String, String> pinned) {
        public Result {
            parent = List.copyOf(parent);
            application = List.copyOf(application);
            pinned = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pinned));
        }

        public List<Path> parentPaths() {
            return parent.stream().map(Candidate::location).toList();
        }

        public List<Path> applicationPaths() {
            return application.stream().map(Candidate::location).toList();
        }

        public Set<String> parentNames() {
            Set<String> names = new LinkedHashSet<>();
            parent.forEach(c -> names.add(c.descriptor().name()));
            return names;
        }
    }

    private LayerSplit() {}

    /**
     * Splits {@code candidates}.
     *
     * @param applicationModules the application's own modules: never kept by a rule
     * @param keepPatterns       the patterns of {@value #KEEP_PROPERTY}
     * @param forced             modules the caller must keep, with the reason
     * @throws IllegalStateException when a kept module reads a module of {@code applicationModules}
     */
    public static Result split(List<Candidate> candidates, Set<String> applicationModules,
                               List<String> keepPatterns, Map<String, String> forced) {
        Map<String, Candidate> byName = new LinkedHashMap<>();
        candidates.forEach(c -> byName.put(c.descriptor().name(), c));
        Map<String, String> pinned = new LinkedHashMap<>();
        Map<String, String> seedReason = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String name = c.descriptor().name();
            if (applicationModules.contains(name)) {
                continue;
            }
            String pin = pinReason(c, candidates, keepPatterns, forced);
            if (pin != null) {
                pinned.put(name, pin);
                seedReason.put(name, pin);
            } else if (isRuntime(c.descriptor())) {
                seedReason.put(name, "a runtime module");
            }
        }
        Set<String> kept = new LinkedHashSet<>(seedReason.keySet());
        record Visit(String module, String seed) {}
        var queue = new ArrayDeque<Visit>();
        seedReason.keySet().forEach(n -> queue.add(new Visit(n, n)));
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            ModuleDescriptor d = byName.get(visit.module()).descriptor();
            if (d.isAutomatic()) {
                continue;
            }
            for (ModuleDescriptor.Requires r : d.requires()) {
                String read = r.name();
                if (applicationModules.contains(read)) {
                    throw new IllegalStateException(visit.seed() + " is kept in the parent layer ("
                            + seedReason.get(visit.seed()) + ") but reads " + read
                            + ", a module of the application: a parent-layer module cannot read the application"
                            + " layer. Remove the pin, or move the code it needs out of " + read + ".");
                }
                if (byName.containsKey(read) && kept.add(read)) {
                    queue.add(new Visit(read, visit.seed()));
                }
            }
        }
        List<Candidate> parent = new ArrayList<>();
        List<Candidate> application = new ArrayList<>();
        for (Candidate c : candidates) {
            (kept.contains(c.descriptor().name()) ? parent : application).add(c);
        }
        return new Result(parent, application, pinned);
    }

    /** The module of {@code jarOrDir}, with its {@value #PARENT_LAYER_ATTRIBUTE} patterns. */
    public static Candidate candidate(Path jarOrDir, String coordinates) throws IOException {
        ModuleDescriptor descriptor;
        try {
            descriptor = ModuleFinder.of(jarOrDir).findAll().stream().findFirst()
                    .orElseThrow(() -> new IOException("No Java module in " + jarOrDir))
                    .descriptor();
        } catch (FindException e) {
            throw new IOException("Cannot read the module of " + jarOrDir.getFileName() + ": " + e.getMessage(), e);
        }
        return new Candidate(descriptor, jarOrDir, coordinates, patterns(manifestValue(jarOrDir)));
    }

    /** The non-blank entries of a comma-separated list; empty for {@code null}. */
    public static List<String> patterns(String commaSeparated) {
        if (commaSeparated == null) {
            return List.of();
        }
        return Stream.of(commaSeparated.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code module=reason;module=reason}: neither a module name nor a reason holds {@code ;} or {@code =}. */
    public static String encodePinned(Map<String, String> pinned) {
        StringBuilder out = new StringBuilder();
        pinned.forEach((module, reason) -> {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(module).append('=').append(reason);
        });
        return out.toString();
    }

    /** The inverse of {@link #encodePinned}; empty for {@code null} or blank. */
    public static Map<String, String> decodePinned(String encoded) {
        Map<String, String> pinned = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return pinned;
        }
        for (String entry : encoded.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                pinned.put(entry.substring(0, eq).strip(), entry.substring(eq + 1).strip());
            }
        }
        return pinned;
    }

    private static boolean isRuntime(ModuleDescriptor d) {
        for (String prefix : RUNTIME_PREFIXES) {
            if (d.name().startsWith(prefix)) {
                return true;
            }
        }
        for (ModuleDescriptor.Provides p : d.provides()) {
            if (p.service().equals(EXTENSION_SERVICE)) {
                return true;
            }
        }
        for (String pkg : d.packages()) {
            if (VaubanClassLoader.excludesPackage(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String pinReason(Candidate c, Collection<Candidate> all, List<String> keepPatterns,
                                    Map<String, String> forced) {
        String name = c.descriptor().name();
        if (forced.containsKey(name)) {
            return forced.get(name);
        }
        for (Candidate declaring : all) {
            for (String pattern : declaring.parentLayerPatterns()) {
                if (matches(pattern, c)) {
                    return PARENT_LAYER_ATTRIBUTE + " of " + declaring.descriptor().name();
                }
            }
        }
        for (String pattern : keepPatterns) {
            if (matches(pattern, c)) {
                return KEEP_PROPERTY;
            }
        }
        return null;
    }

    static boolean matches(String pattern, Candidate c) {
        int colon = pattern.indexOf(':');
        if (colon < 0) {
            return token(pattern.strip(), c.descriptor().name());
        }
        if (c.coordinates() == null) {
            return false;
        }
        int own = c.coordinates().indexOf(':');
        String group = c.coordinates().substring(0, own);
        String artifact = c.coordinates().substring(own + 1);
        String a = pattern.substring(colon + 1).strip();
        return token(pattern.substring(0, colon).strip(), group) && token(a.isEmpty() ? "*" : a, artifact);
    }

    private static boolean token(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    private static String manifestValue(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            Path file = jarOrDir.resolve(JarFile.MANIFEST_NAME);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return new Manifest(in).getMainAttributes().getValue(PARENT_LAYER_ATTRIBUTE);
            }
        }
        try (JarFile jar = new JarFile(jarOrDir.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(PARENT_LAYER_ATTRIBUTE);
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `bash <scratchpad>/core-test.sh t2-green vidocq-runtime-core -Dtest=LayerSplitTest`
Expected: `Tests run: 12, Failures: 0`, `BUILD SUCCESS`.
Then the whole core module: `bash <scratchpad>/core-test.sh t2-core vidocq-runtime-core` → `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/LayerSplit.java vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/LayerSplitTest.java
git commit -s -S -m "feat(core): LayerSplit, the closure of the runtime stays in the parent layer (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 3: The trampoline under `all`

`Vidocq.run` from an IDE re-layers boot-layer modules. Under `-Dvidocq.app.layer=all` it keeps the `LayerSplit` parent (plus what Vauban keeps: automatic modules, which cannot be re-created next to their boot twin) and moves the rest. Under `classes` (default) nothing changes. The installation remembers the pinned list for the report.

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqAppLayer.java`
- Create: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/TestModules.java`
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/VidocqAppLayerAllTest.java`

**Interfaces:**
- Consumes: `LayerSplit.split`, `LayerSplit.candidate`, `LayerSplit.Mode`, `LayerSplit.decodePinned`, `LayerSplit.patterns` (Task 2); `VaubanLayerFactory.applicationPaths(Configuration, Collection<String>, Set<String>, Set<String>)` (vauban#156).
- Produces:
  - `static List<Path> VidocqAppLayer.applicationPaths(Configuration configuration, String callerModule, LayerSplit.Mode mode, List<String> keepPatterns, Map<String, String> pinnedOut)` — `CLASSES` returns what the existing two-argument method returns; `ALL` applies the split and fills `pinnedOut`.
  - `record Installation(String origin, List<String> archives, boolean listed, Map<String, String> pinned)` — the fourth component is new; `installIfConfigured` fills it from `System.getProperty(LayerSplit.PINNED_PROPERTY)`, the trampoline from the split.
  - Test support `TestModules.explodedModule(Path dir, String name, List<String> requires, String className)` and `TestModules.automaticJar(Path dir, String fileName, String className)`.

- [ ] **Step 1: Move the test helper**

Create `TestModules.java` (package `io.vidocq.runtime.core`, test sources) with the `explodedModule` method copied verbatim from `VidocqAppLayerSelectionTest` (made package-private static), plus:

```java
    /** An automatic module: a jar named {@code fileName}, without a descriptor, holding one empty class. */
    static Path automaticJar(Path dir, String fileName, String className) throws IOException {
        Path jar = dir.resolve(fileName);
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry(className.replace('.', '/') + ".class"));
            out.write(ClassFile.of().build(ClassDesc.of(className),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }
```
In `VidocqAppLayerSelectionTest`, delete its private `explodedModule` and call `TestModules.explodedModule`. Run `bash <scratchpad>/core-test.sh t3-move vidocq-runtime-core -Dtest=VidocqAppLayerSelectionTest` → still green (pure move).

- [ ] **Step 2: Write the failing tests**

```java
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq.run from an IDE with vidocq.app.layer=all (spec §4, tests 1 and 6). */
@DisplayName("Vidocq.run — vidocq.app.layer=all")
class VidocqAppLayerAllTest {

    @Test
    @DisplayName("a pinned module and what it reads stay; only the application moves")
    void anExtensionClosureStays(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.web"), "acme.app.Main");
        var web = TestModules.explodedModule(dir, "acme.web", List.of(), "acme.web.Page");
        var ext = TestModules.explodedModule(dir, "org.acme.ext", List.of("acme.web"), "org.acme.ext.Ext");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, web, ext), ModuleFinder.of(), Set.of("acme.app", "org.acme.ext"));

        var pinned = new LinkedHashMap<String, String>();
        var moved = VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.ALL,
                List.of("org.acme.ext"), pinned);

        assertEquals(Set.of(app.toRealPath()), real(moved),
                "org.acme.ext is pinned, and it reads acme.web: both stay, only the application moves");
        assertEquals(Map.of("org.acme.ext", "vidocq.app.layer.keep"), pinned);
    }

    @Test
    @DisplayName("an automatic module stays in the boot layer: its twin would clash")
    void automaticModulesStay(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("flowserver"), "acme.app.Main");
        var flow = TestModules.automaticJar(dir, "flowserver.jar", "com.vaadin.flow.Server");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, flow), ModuleFinder.of(), Set.of("acme.app"));

        var moved = VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.ALL, List.of(),
                new LinkedHashMap<>());

        assertEquals(Set.of(app.toRealPath()), real(moved));
    }

    @Test
    @DisplayName("classes keeps today's policy")
    void classesIsToday(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.pricing"), "acme.app.Main");
        var pricing = TestModules.explodedModule(dir, "acme.pricing", List.of(), "acme.pricing.Price");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, pricing), ModuleFinder.of(), Set.of("acme.app"));

        assertEquals(real(VidocqAppLayer.applicationPaths(config, "acme.app")),
                real(VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.CLASSES, List.of(),
                        new LinkedHashMap<>())));
    }

    @Test
    @DisplayName("a library read by a brick and by the application is defined once: the application gets the brick's Class")
    void aSharedLibraryIsDefinedOnce(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.codec"), "acme.app.Main");
        var brick = TestModules.explodedModule(dir, "io.vidocq.fake.brick", List.of("acme.codec"),
                "io.vidocq.fake.brick.Brick");
        var codec = TestModules.explodedModule(dir, "acme.codec", List.of(), "acme.codec.Codec");
        var candidates = List.of(LayerSplit.candidate(app, null), LayerSplit.candidate(brick, null),
                LayerSplit.candidate(codec, null));
        var split = LayerSplit.split(candidates, Set.of("acme.app"), List.of(), Map.of());

        Configuration parentConfig = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(split.parentPaths().toArray(Path[]::new)), ModuleFinder.of(), split.parentNames());
        ModuleLayer parent = ModuleLayer.boot().defineModulesWithOneLoader(parentConfig,
                ClassLoader.getSystemClassLoader());
        var appLayer = VaubanLayerFactory.createAppLayer(split.applicationPaths(), parent,
                parent.findLoader("acme.codec"), PluginContext.empty());
        try {
            assertEquals(Set.of("acme.app"), appLayer.moduleNames());
            assertSame(parent.findLoader("acme.codec").loadClass("acme.codec.Codec"),
                    Class.forName("acme.codec.Codec", false, appLayer.loader()),
                    "one Codec class: the application layer reads the parent's");
        } finally {
            appLayer.loader().close();
        }
    }

    private static Set<Path> real(List<Path> paths) {
        return paths.stream().map(p -> {
            try {
                return p.toRealPath();
            } catch (java.io.IOException e) {
                return p.toAbsolutePath().normalize();
            }
        }).collect(java.util.stream.Collectors.toSet());
    }
}
```

Note on `aSharedLibraryIsDefinedOnce`: the exploded modules hold empty classes; `defineModulesWithOneLoader` and `loadClass` are enough, nothing is initialized. If `createAppLayer` refuses a parent loader that is not the parent layer's own, use `parent.findLoader("io.vidocq.fake.brick")` — both modules share one loader.

- [ ] **Step 3: Run them, watch them fail to compile**

Run: `bash <scratchpad>/core-test.sh t3-red vidocq-runtime-core -Dtest=VidocqAppLayerAllTest`
Expected: compilation error, no five-argument `applicationPaths`.

- [ ] **Step 4: Implement**

In `VidocqAppLayer.java`:

1. `Installation` gains `Map<String, String> pinned` (compact constructor: `pinned = Collections.unmodifiableMap(new LinkedHashMap<>(pinned));`). Update its Javadoc `@param pinned the modules kept in the parent layer by a pin, and why`.
2. `installIfConfigured()` builds `new Installation(APP_PATH_PROPERTY, ..., true, LayerSplit.decodePinned(System.getProperty(LayerSplit.PINNED_PROPERTY)))`.
3. Add the overload and use it from `installFromBootLayer`:

```java
    /**
     * The trampoline's re-layer policy under {@code mode}: {@link LayerSplit.Mode#CLASSES} is the policy of
     * {@link #applicationPaths(java.lang.module.Configuration, String)}; {@link LayerSplit.Mode#ALL} keeps the
     * {@link LayerSplit} parent layer and Vauban's own rules (automatic modules stay: re-created next to their boot
     * twin they would not resolve) and moves everything else.
     *
     * @param pinnedOut receives the pinned modules and why, under {@code ALL}
     */
    static List<Path> applicationPaths(java.lang.module.Configuration configuration, String callerModule,
            LayerSplit.Mode mode, List<String> keepPatterns, java.util.Map<String, String> pinnedOut) {
        if (mode == LayerSplit.Mode.CLASSES) {
            return applicationPaths(configuration, callerModule);
        }
        var candidates = new java.util.ArrayList<LayerSplit.Candidate>();
        for (var resolved : configuration.modules()) {
            var location = resolved.reference().location()
                    .filter(uri -> "file".equals(uri.getScheme())).map(Path::of).orElse(null);
            if (location == null) {
                continue;
            }
            try {
                candidates.add(LayerSplit.candidate(location, null));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the module " + resolved.name(), e);
            }
        }
        var split = LayerSplit.split(candidates, java.util.Set.of(callerModule), keepPatterns,
                java.util.Map.of());
        pinnedOut.putAll(split.pinned());
        return VaubanLayerFactory.applicationPaths(configuration, List.of(), split.parentNames(),
                java.util.Set.of(callerModule));
    }
```

`LayerSplit.candidate` reads the descriptor again from the file; for a module of the boot layer that is the same descriptor. In `installFromBootLayer`, replace the `applicationPaths(configuration, callerModule.getName())` call:

```java
        var mode = LayerSplit.Mode.of(System.getProperty(LayerSplit.MODE_PROPERTY));
        var pinned = new java.util.LinkedHashMap<String, String>();
        var paths = explicit.isEmpty()
                ? applicationPaths(configuration, callerModule.getName(), mode,
                        LayerSplit.patterns(System.getProperty(LayerSplit.KEEP_PROPERTY)), pinned)
                : explicitPaths(configuration, java.util.Set.of(explicit.split("\\s*,\\s*")));
```
and pass `pinned` as the fourth `Installation` component. The `RUNTIME_MODULE_PREFIXES` list stays for `CLASSES`.

- [ ] **Step 5: Run the tests, then the core module**

Run: `bash <scratchpad>/core-test.sh t3-green vidocq-runtime-core -Dtest='VidocqAppLayer*'` → all PASS.
Run: `bash <scratchpad>/core-test.sh t3-core vidocq-runtime-core` → `BUILD SUCCESS` (the `Installation` constructor change must compile everywhere: fix the callers in tests by adding `Map.of()`).

- [ ] **Step 6: Commit**

```bash
git add -A vidocq-runtime-core
git commit -s -S -m "feat(core): Vidocq.run keeps the runtime closure under vidocq.app.layer=all (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 4: `ApplicationLaunch.layered` — the build-time split

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/ApplicationLaunch.java`
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchLayerTest.java` (new)

**Interfaces:**
- Consumes: `LayerSplit` (Task 2); existing `EnrichedJars.resolve`, `EnrichedJars.isEnriched`, `DevOnlyJars.isDevOnly`, `JpmsPatches.patchDirFor(Path buildDir, String artifactId)`.
- Produces:

```java
public record Layered(List<Path> modulePath, List<Path> appPath, Map<String, String> pinned) {}
public static Layered layered(MavenProject project, Path buildDir, Path classesDir, Consumer<Path> modularized,
                              boolean dropDevOnly, Consumer<String> dropped, List<String> keepPatterns)
        throws MojoExecutionException;
```
`modulePath` = the parent jars (mutable `ArrayList`: callers append dev-services jars); `appPath` = `classesDir` first, then the application jars in artifact order; `pinned` = `LayerSplit.Result.pinned()`. The forced reason for a patched jar is the constant `ApplicationLaunch.PATCHED_REASON = "classes parked by vidocq:generate need --patch-module"`.

- [ ] **Step 1: Write the failing tests**

Model the project and artifacts exactly as `ApplicationLaunchTest` does (its `project()`, `artifact(id, jar)` and `jar(dir, name, devOnly)` helpers: copy them into the new test class). Add a helper writing a jar with a given `Automatic-Module-Name` or an explicit `module-info.class`:

```java
    /** A jar with an explicit descriptor requiring {@code requires}, or an automatic one when {@code explicit} is false. */
    private static Path moduleJar(Path dir, String fileName, String module, boolean explicit, String... requires)
            throws Exception {
        Path jar = dir.resolve(fileName);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (!explicit) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (explicit) {
                out.putNextEntry(new ZipEntry("module-info.class"));
                out.write(ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (String r : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(r), 0, null));
                    }
                })));
                out.closeEntry();
            }
            String pkg = module.replace('.', '/');
            out.putNextEntry(new ZipEntry(pkg + "/Marker.class"));
            out.write(ClassFile.of().build(ClassDesc.of(module + ".Marker"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }
```

Tests:

```java
    @Test
    void aFlatLibraryGoesToTheApplicationLayerAndTheRuntimeStays(@TempDir Path dir) throws Exception {
        Path core = moduleJar(dir, "vidocq-runtime-core.jar", "io.vidocq.runtime.core", true, "acme.codec");
        Path codec = moduleJar(dir, "codec.jar", "acme.codec", true);
        Path flow = moduleJar(dir, "flow-server.jar", "flow.server", false);
        MavenProject project = project();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact("vidocq-runtime-core", core),
                artifact("codec", codec), artifact("flow-server", flow))));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        var layered = ApplicationLaunch.layered(project, build, classes, jar -> { }, true, id -> { }, List.of());

        assertEquals(List.of(core, codec), layered.modulePath());
        assertEquals(List.of(classes, flow), layered.appPath(), "the classes first, then the libraries");
        assertEquals(Map.of(), layered.pinned());
    }

    @Test
    void aPatchedJarStaysInTheParentLayer(@TempDir Path dir) throws Exception {
        Path lib = moduleJar(dir, "lib.jar", "acme.lib", true);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("lib", lib)));
        Path build = dir.resolve("target");
        Files.createDirectories(JpmsPatches.patchDirFor(build, "lib"));

        var layered = ApplicationLaunch.layered(project, build, build.resolve("classes"), jar -> { }, true,
                id -> { }, List.of());

        assertEquals(List.of(lib), layered.modulePath(), "--patch-module only patches boot-layer modules");
        assertEquals(Map.of("acme.lib", ApplicationLaunch.PATCHED_REASON), layered.pinned());
    }

    @Test
    void keepPatternsPinAnArtifact(@TempDir Path dir) throws Exception {
        Path jni = moduleJar(dir, "jni.jar", "acme.jni", false);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("jni", jni)));
        Path build = dir.resolve("target");

        var layered = ApplicationLaunch.layered(project, build, build.resolve("classes"), jar -> { }, true,
                id -> { }, List.of("*:jni"));

        assertEquals(List.of(jni), layered.modulePath());
        assertEquals(Map.of("acme.jni", "vidocq.app.layer.keep"), layered.pinned());
    }

    @Test
    void aJarWithoutAModuleNameFailsWithItsFileName(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("1-bad.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(bad))) {
            out.putNextEntry(new ZipEntry("x/Y.class"));
            out.write(new byte[] {(byte) 0xCA});
            out.closeEntry();
        }
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("bad", bad)));
        Path build = dir.resolve("target");

        var e = assertThrows(MojoExecutionException.class, () -> ApplicationLaunch.layered(project, build,
                build.resolve("classes"), jar -> { }, true, id -> { }, List.of()));
        assertTrue(e.getMessage().contains("1-bad.jar"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.app.layer=all"), e.getMessage());
    }
```

(`1-bad.jar` derives no valid automatic module name: `ModuleFinder` throws `FindException`.) The `artifact(...)` helper of `ApplicationLaunchTest` gives `groupId` `io.vidocq.test` (check and use what it sets); the pattern `*:jni` matches any group.

- [ ] **Step 2: Run, watch them fail to compile**

Run: `bash <scratchpad>/core-test.sh t4-red vidocq-runtime-maven-plugin -Dtest=ApplicationLaunchLayerTest`
Expected: compilation error, no `layered`.

- [ ] **Step 3: Implement**

In `ApplicationLaunch.java`, factor the artifact loop of `modulePath(...)` (seven-argument version) into a private method used by both, then add `layered`:

```java
    /** Why a jar with classes parked for {@code --patch-module} stays in the parent layer. */
    public static final String PATCHED_REASON = "classes parked by vidocq:generate need --patch-module";

    /** A launch split by {@link LayerSplit}: the module path, the application path, the pinned modules. */
    public record Layered(List<Path> modulePath, List<Path> appPath, Map<String, String> pinned) {}

    private record Entry(Artifact artifact, Path path) {}

    private static List<Entry> entries(MavenProject project, Path buildDir, Consumer<Path> modularized,
                                       boolean dropDevOnly, Consumer<String> dropped) {
        List<Entry> entries = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                Path jar = artifact.getFile().toPath();
                if (dropDevOnly && DevOnlyJars.isDevOnly(jar)) {
                    dropped.accept(artifact.getArtifactId());
                    continue;
                }
                Path resolved = EnrichedJars.resolve(buildDir, jar);
                if (!resolved.equals(jar)) {
                    modularized.accept(jar);
                }
                entries.add(new Entry(artifact, resolved));
            }
        }
        return entries;
    }

    /**
     * The launch of {@code vidocq.app.layer=all}: the runtime's closure on the module path, the project's classes
     * and every other dependency on the application path (Vidocq/vidocq#247).
     *
     * @param keepPatterns the patterns of {@code vidocq.app.layer.keep}
     * @throws MojoExecutionException when a jar has no readable module, or a pin reads the application
     */
    public static Layered layered(MavenProject project, Path buildDir, Path classesDir, Consumer<Path> modularized,
                                  boolean dropDevOnly, Consumer<String> dropped, List<String> keepPatterns)
            throws MojoExecutionException {
        List<LayerSplit.Candidate> candidates = new ArrayList<>();
        Map<String, String> forced = new LinkedHashMap<>();
        for (Entry entry : entries(project, buildDir, modularized, dropDevOnly, dropped)) {
            Artifact a = entry.artifact();
            LayerSplit.Candidate candidate;
            try {
                candidate = LayerSplit.candidate(entry.path(), a.getGroupId() + ":" + a.getArtifactId());
            } catch (IOException e) {
                throw new MojoExecutionException("vidocq.app.layer=all reads the module of every dependency, and "
                        + entry.path().getFileName() + " has none: " + e.getMessage()
                        + ". Keep it out of the application layer with vidocq.app.layer.keep=" + a.getGroupId()
                        + ":" + a.getArtifactId() + ", or use vidocq.app.layer=classes.", e);
            }
            candidates.add(candidate);
            if (!EnrichedJars.isEnriched(buildDir, a.getFile().toPath())
                    && Files.isDirectory(JpmsPatches.patchDirFor(buildDir, a.getArtifactId()))) {
                forced.put(candidate.descriptor().name(), PATCHED_REASON);
            }
        }
        LayerSplit.Result split;
        try {
            split = LayerSplit.split(candidates, applicationModules(classesDir), keepPatterns, forced);
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        List<Path> appPath = new ArrayList<>();
        appPath.add(classesDir);
        appPath.addAll(split.applicationPaths());
        return new Layered(new ArrayList<>(split.parentPaths()), appPath, split.pinned());
    }

    /** The project's own module name, when its classes have a descriptor. */
    private static Set<String> applicationModules(Path classesDir) {
        if (!Files.isRegularFile(classesDir.resolve("module-info.class"))) {
            return Set.of();
        }
        return ModuleFinder.of(classesDir).findAll().stream().map(r -> r.descriptor().name())
                .collect(Collectors.toSet());
    }
```

Rewrite the body of the seven-argument `modulePath` as:
```java
        List<Path> paths = new ArrayList<>();
        if (!layerMode) {
            paths.add(classesDir);
        }
        entries(project, buildDir, modularized, dropDevOnly, dropped).forEach(e -> paths.add(e.path()));
        return paths;
```
Add the imports (`io.vidocq.runtime.core.LayerSplit`, `org.apache.maven.plugin.MojoExecutionException`, `java.lang.module.ModuleFinder`, `java.nio.file.Files`, `java.util.LinkedHashMap`, `java.util.Set`, `java.util.stream.Collectors`).

- [ ] **Step 4: Run the new and the existing launch tests**

Run: `bash <scratchpad>/core-test.sh t4-green vidocq-runtime-maven-plugin -Dtest='ApplicationLaunch*'`
Expected: all PASS (the refactor keeps `ApplicationLaunchTest` green).

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-maven-plugin
git commit -s -S -m "feat(maven-plugin): ApplicationLaunch.layered, the build-time split (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 5: `vidocq:run` and `vidocq:dev` read `vidocq.app.layer`

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java` (fields near the other `@Parameter`s; `execute()` lines ~208-250)
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java` (fields near `layerMode` ~234; `buildModulePath` ~613, `buildAppPath` ~630, where `sysProps` is built)
- Modify: `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml` (the `run` and `dev` mojos)
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/VidocqRunMojoTest.java`, `.../dev/` the dev mojo's existing test class (find with `ls vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/`), `PluginDescriptorTest` if one exists (`grep -rl plugin.xml vidocq-runtime-maven-plugin/src/test`)

**Interfaces:**
- Consumes: `ApplicationLaunch.layered`, `ApplicationLaunch.Layered` (Task 4); `LayerSplit.Mode`, `LayerSplit.patterns`, `LayerSplit.encodePinned`, `LayerSplit.PINNED_PROPERTY` (Task 2).
- Produces: mojo parameters `appLayer` (property `vidocq.app.layer`, default `classes`) and `appLayerKeep` (property `vidocq.app.layer.keep`, no default) on `run` and `dev`. Under `all`, the child JVM gets `-Dvidocq.app.layer.pinned=<encoded>` when something is pinned, and the build logs `application layer: <n> libraries, <m> kept in the parent layer by a pin`.

- [ ] **Step 1: Write the failing tests**

Read `VidocqRunMojoTest` first: it shows how the mojo is instantiated and its fields set (reflection or package-private setters). Following that pattern, add:

1. `runRejectsAnUnknownAppLayer`: `appLayer = "libs"` → `execute()` throws `MojoExecutionException` whose message is `vidocq.app.layer must be classes or all, not 'libs'`.
2. A pure test of the launch selection. Extract in `VidocqRunMojo` a package-private static method and test it:

```java
    /** The module path and the application path of the child, by {@code vidocq.app.layer}. */
    static ApplicationLaunch.Layered launchPaths(MavenProject project, Path build, Path classes,
            LayerSplit.Mode mode, List<String> keep, Consumer<Path> modularized, Consumer<String> dropped)
            throws MojoExecutionException
```
`CLASSES` returns `new Layered(ApplicationLaunch.modulePath(project, build, classes, true, modularized, true, dropped), ApplicationLaunch.appPath(classes, true), Map.of())`; `ALL` returns `ApplicationLaunch.layered(project, build, classes, modularized, true, dropped, keep)`.
Test `launchPathsByMode`: with the jars of Task 4's first test (copy the `moduleJar` helper), `CLASSES` gives every jar on the module path and `[classes]` as app path; `ALL` gives the split.
3. For the dev mojo, the same with `dropDevOnly = false` (dev keeps a declared console), as a static `VidocqDevMojo.launchPaths(...)` with the identical signature. Test `devLaunchPathsKeepDevOnlyJars`: a dev-only jar, written like `ApplicationLaunchTest`'s `jar(dir, name, "true")` helper (it puts the dev-only marker in the manifest) plus `Automatic-Module-Name: acme.devonly`, is in `modulePath` or `appPath` under `ALL`; the run mojo's `launchPaths` drops it.
4. `devRejectsAllWithoutTheLayer`: `layerMode = false`, `appLayer = "all"` → `MojoExecutionException` `vidocq.app.layer=all needs vidocq.dev.layer=true`.

- [ ] **Step 2: Run, watch them fail**

Run: `bash <scratchpad>/core-test.sh t5-red vidocq-runtime-maven-plugin -Dtest='VidocqRunMojoTest,VidocqDevMojo*Test'`
Expected: compilation errors (`launchPaths`, `appLayer`).

- [ ] **Step 3: Implement**

`VidocqRunMojo`:
```java
    /**
     * {@code classes}: the application layer holds the project's classes, its dependencies stay on the module path.
     * {@code all}: it holds every dependency outside the runtime's closure too (Vidocq/vidocq#247).
     */
    @Parameter(property = "vidocq.app.layer", defaultValue = "classes")
    private String appLayer;

    /** Comma-separated patterns ({@code module} or {@code groupId:artifactId}) kept in the parent layer under {@code all}. */
    @Parameter(property = "vidocq.app.layer.keep")
    private String appLayerKeep;
```
In `execute()`, replace the `modulePath`/`appPath` computation:
```java
        LayerSplit.Mode mode;
        try {
            mode = LayerSplit.Mode.of(appLayer);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        ApplicationLaunch.Layered launch = launchPaths(project, build, classes, mode,
                LayerSplit.patterns(appLayerKeep),
                jar -> getLog().info(PREFIX + "using the modularized copy of " + jar.getFileName()),
                id -> getLog().warn(DevOnlyJars.droppedWarning(id)));
        List<Path> modulePath = launch.modulePath();
        List<Path> appPath = launch.appPath();
        Map<String, String> systemProperties = buildSystemProperties();
        if (mode == LayerSplit.Mode.ALL) {
            getLog().info(PREFIX + "application layer: " + (appPath.size() - 1) + " libraries, "
                    + launch.pinned().size() + " kept in the parent layer by a pin");
            launch.pinned().forEach((m, why) -> getLog().info(PREFIX + "  parent layer: " + m + " (" + why + ")"));
            if (!launch.pinned().isEmpty()) {
                systemProperties.put(LayerSplit.PINNED_PROPERTY, LayerSplit.encodePinned(launch.pinned()));
            }
        }
```
(the existing `Map<String, String> systemProperties = buildSystemProperties();` line moves up here). The dev-services classpath must see the libraries now on the application path: replace `ApplicationLaunch.classpathOf(modulePath, classes)` with
```java
ApplicationLaunch.classpathOf(java.util.stream.Stream.concat(modulePath.stream(), appPath.stream()).toList(), classes)
```

`VidocqDevMojo`: same two fields; `launchPaths` static as above with `dropDevOnly = false`; at the start of `execute()` (after the skip check), validate: `LayerSplit.Mode.of(appLayer)` (wrap as above) and `if (mode == ALL && !layerMode) throw new MojoExecutionException("vidocq.app.layer=all needs vidocq.dev.layer=true");`. `buildModulePath()` and `buildAppPath()` become one `buildLaunch()` returning the `Layered` (keep `layerMode=false` on the legacy path: `ApplicationLaunch.modulePath(..., false, ...)` and an empty app path). Wherever the dev mojo builds `sysProps`, add the pinned property under `ALL` as in run, and log the same line once at startup.

`plugin.xml`, in both the `run` and the `dev` mojo: add to `<parameters>`
```xml
                <parameter><name>appLayer</name><type>java.lang.String</type><required>false</required><editable>true</editable><description>classes (default): the application layer holds the project's classes; all: it holds every dependency outside the runtime's closure too (Vidocq/vidocq#247).</description></parameter>
                <parameter><name>appLayerKeep</name><type>java.lang.String</type><required>false</required><editable>true</editable><description>Under vidocq.app.layer=all, comma-separated patterns (module name, or groupId:artifactId) kept in the parent layer.</description></parameter>
```
and to `<configuration>`
```xml
                <appLayer implementation="java.lang.String" default-value="classes">${vidocq.app.layer}</appLayer>
                <appLayerKeep implementation="java.lang.String">${vidocq.app.layer.keep}</appLayerKeep>
```
Match the surrounding format of each mojo (the `run` mojo may use the multi-line `<parameter>` form: copy whichever form its neighbours use).

- [ ] **Step 4: Run the plugin's unit tests**

Run: `bash <scratchpad>/core-test.sh t5-green vidocq-runtime-maven-plugin`
Expected: `BUILD SUCCESS`, the new tests among the passing ones.

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-maven-plugin
git commit -s -S -m "feat(maven-plugin): vidocq:run and vidocq:dev read vidocq.app.layer (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 6: `vidocq:package` under `all`

`lib/` holds the parent layer, `app/` the application jar and its libraries. The layer script keeps `-Dvidocq.app.path="$BASEDIR/app"`: `ModuleFinder.of(dir)` reads a directory of jars as a set of modules, so every `app/` jar enters the layer. The trampoline script keeps `--module-path lib:app` and gains `-Dvidocq.app.layer=all`, so that `Vidocq.run` applies Task 3. Both scripts pass the pinned list.

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqPackageMojo.java` (fields, the dependency loop ~127-160, `generateShScript`, `generateCmdScript`)
- Modify: `plugin.xml` (the `package` mojo)
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/VidocqPackageMojoTest.java`

**Interfaces:**
- Consumes: `LayerSplit` (Task 2), `ApplicationLaunch.PATCHED_REASON` (Task 4).
- Produces: parameters `appLayer` / `appLayerKeep` (same properties as Task 5) on `package`.

- [ ] **Step 1: Write the failing tests**

Read `VidocqPackageMojoTest` first and follow its way of building a mojo with a project, a build directory and artifacts. Add:

1. `allPutsTheLibrariesInApp`: artifacts `vidocq-runtime-core` (explicit module `io.vidocq.runtime.core`) and `flow-server` (automatic `flow.server`), `appLayer = "all"`, layer mode on. After `execute()`: `<dist>/lib/vidocq-runtime-core.jar` exists, `<dist>/lib/flow-server.jar` does not, `<dist>/app/flow-server.jar` exists, the application jar is in `app/`.
2. `classesKeepsTodaysLayout`: same artifacts, default `appLayer`: both jars in `lib/`.
3. `allTrampolineScriptSetsTheMode`: with the trampoline configuration the existing tests use (a `mainClass` annotated `@VidocqMain`, see how `VidocqPackageMojoTest` sets `trampolineRef()` up), `appLayer = "all"` → the `.sh` contains `-Dvidocq.app.layer=all` and the `.cmd` too.
4. `pinnedListGoesToTheScripts`: `appLayerKeep = "flow.server"` → the `.sh` contains `"-Dvidocq.app.layer.pinned=flow.server=vidocq.app.layer.keep"` (with the double quotes).

Use the `moduleJar` helper from Task 4 (copy it).

- [ ] **Step 2: Run, watch them fail**

Run: `bash <scratchpad>/core-test.sh t6-red vidocq-runtime-maven-plugin -Dtest=VidocqPackageMojoTest`
Expected: compilation errors (`appLayer`).

- [ ] **Step 3: Implement**

Fields as in Task 5 (`appLayer`, `appLayerKeep`) plus a `private Map<String, String> pinned = Map.of();` set during `execute()`.

In `execute()`, the dependency loop first computes, per kept artifact, `src` exactly as today; collect `(artifact, src, enriched, patchDir)` into a list instead of copying at once. Then:

```java
            LayerSplit.Mode mode = LayerSplit.Mode.of(appLayer);   // wrap IllegalArgumentException as in Task 5
            Set<Path> toApp = new HashSet<>();
            if (mode == LayerSplit.Mode.ALL && layerMode) {
                List<LayerSplit.Candidate> candidates = new ArrayList<>();
                Map<String, String> forced = new LinkedHashMap<>();
                for (Packaged p : packaged) {
                    LayerSplit.Candidate c = LayerSplit.candidate(p.src(),
                            p.artifact().getGroupId() + ":" + p.artifact().getArtifactId());
                    candidates.add(c);
                    if (!p.enriched() && Files.isDirectory(p.patchDir())) {
                        forced.put(c.descriptor().name(), ApplicationLaunch.PATCHED_REASON);
                    }
                }
                LayerSplit.Result split = LayerSplit.split(candidates, Set.of(), LayerSplit.patterns(appLayerKeep),
                        forced);
                toApp.addAll(split.applicationPaths());
                pinned = split.pinned();
                getLog().info("Application layer: " + toApp.size() + " libraries in app/, " + pinned.size()
                        + " kept in lib/ by a pin");
            } else if (mode == LayerSplit.Mode.ALL) {
                throw new MojoExecutionException("vidocq.app.layer=all needs vidocq.package.layer=true");
            }
```
(`Packaged` is a private record `(Artifact artifact, Path src, boolean enriched, Path patchDir)`; the application module itself is the app jar, not a candidate, so `applicationModules` is empty.) `LayerSplit.candidate` throws `IOException` naming the jar: rethrow it as `MojoExecutionException` with the same wording as Task 4 (`vidocq.app.layer=all reads the module of every dependency, and <file> has none: …`). Then copy each `Packaged` exactly as today, with the destination directory `toApp.contains(p.src()) ? appDir : libDir`.

Scripts: add a helper
```java
    /** The JVM options the {@code all} mode adds to a launcher, each followed by the line continuation. */
    private String appLayerOptions(String continuation) {
        if (LayerSplit.Mode.of(appLayer) != LayerSplit.Mode.ALL) {
            return "";
        }
        String options = "\n  -Dvidocq.app.layer=all " + continuation;
        if (!pinned.isEmpty()) {
            options += "\n  \"-D" + LayerSplit.PINNED_PROPERTY + "=" + LayerSplit.encodePinned(pinned) + "\" "
                    + continuation;
        }
        return options;
    }
```
and splice `appLayerOptions("\\")` (sh) / `appLayerOptions("^")` (cmd) right after the `--add-modules ALL-MODULE-PATH` line of both layer-mode branches (the trampoline one and the `-Dvidocq.app.path` one). The layer script needs `-Dvidocq.app.layer=all` only for the report; keep it in both for one rule. Mind the text blocks: insert `%s` placeholders and pass the strings to `formatted`.

`plugin.xml`: the two parameters and configuration entries of Task 5, in the `package` mojo, with property names `vidocq.app.layer` / `vidocq.app.layer.keep`.

- [ ] **Step 4: Run the plugin's unit tests**

Run: `bash <scratchpad>/core-test.sh t6-green vidocq-runtime-maven-plugin`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-maven-plugin
git commit -s -S -m "feat(maven-plugin): vidocq:package splits lib/ and app/ under vidocq.app.layer=all (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 7: The startup report counts the libraries and lists the pins

Spec §7. The summary headline becomes `69 modules, 68 libraries (vidocq.app.path=…)` when the layer holds modules other than the application's; the detailed report adds one `parent layer` row per pinned module, `flow.server (Vidocq-Parent-Layer of io.vidocq.foy.core)`.

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/report/CoreSections.java` (`layer`, ~100)
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/StartupFacts.java` (`layer`, ~68)
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/report/CoreSectionsTest.java`, `.../StartupFactsTest.java`

**Interfaces:**
- Consumes: `VidocqAppLayer.Installation.pinned()` (Task 3).
- Produces: `public static Section CoreSections.layer(String origin, List<String> originPaths, List<LayerModule> modules, int libraries, Map<String, String> pinned, String weaving, DisplayPaths paths)`; the existing five-argument `layer` delegates with `0, Map.of()`.

- [ ] **Step 1: Write the failing tests**

In `CoreSectionsTest`, following the existing `layer` tests (read them for how `DisplayPaths` and the expected `Section` are built):

```java
    @Test
    void theHeadlineCountsTheLibraries() {
        var modules = List.of(new CoreSections.LayerModule("acme.app", "/p/target/classes", "directory"),
                new CoreSections.LayerModule("flow.server", "/p/libs/flow-server.jar", "jar"),
                new CoreSections.LayerModule("flow.commons", "/p/libs/flow-commons.jar", "jar"));
        var section = CoreSections.layer("vidocq.app.path", List.of(), modules, 2, Map.of(), null, PATHS);

        assertTrue(section.summary().startsWith("3 modules, 2 libraries ("), section.summary());
        assertTrue(section.detailed().startsWith("3 modules, 2 libraries ("), section.detailed());
    }

    @Test
    void oneLibraryIsSingular() {
        var modules = List.of(new CoreSections.LayerModule("acme.app", "/p/target/classes", "directory"),
                new CoreSections.LayerModule("flow.server", "/p/libs/flow-server.jar", "jar"));
        var section = CoreSections.layer("vidocq.app.path", List.of(), modules, 1, Map.of(), null, PATHS);

        assertTrue(section.summary().startsWith("2 modules, 1 library ("), section.summary());
    }

    @Test
    void pinnedModulesAreRowsWithTheirReason() {
        var modules = List.of(new CoreSections.LayerModule("acme.app", "/p/target/classes", "directory"));
        var pinned = new LinkedHashMap<String, String>();
        pinned.put("flow.server", "Vidocq-Parent-Layer of io.vidocq.foy.core");
        var section = CoreSections.layer("vidocq.app.path", List.of(), modules, 0, pinned, null, PATHS);

        assertTrue(section.lines().contains(new Section.Row("parent layer",
                "flow.server (Vidocq-Parent-Layer of io.vidocq.foy.core)")), section.lines().toString());
    }
```
Use the names this test class already uses for its `DisplayPaths` constant and for the `Section` accessors (`summary()`/`detailed()`/`lines()` are the expected names; adapt to the record's real component names, which you can read in `Section.java`). If `Section.Row` has no two-argument constructor, use the one the `weaving` row uses.

In `StartupFactsTest`, add `librariesAreTheModulesOtherThanTheApplication`: `StartupFacts.libraries(List.of(module("acme.app"), module("flow.server")), "acme.app")` returns `1`; with application `null` returns `0`.

- [ ] **Step 2: Run, watch them fail**

Run: `bash <scratchpad>/core-test.sh t7-red vidocq-runtime-core -Dtest='CoreSectionsTest,StartupFactsTest'`
Expected: compilation errors.

- [ ] **Step 3: Implement**

`CoreSections.layer` (seven arguments): after building the module `Cells`, add for each pinned entry `lines.add(new Row("parent layer", module + " (" + reason + ")"));` before the weaving row. The count becomes:
```java
        String count = count(modules.size(), "module", "modules")
                + (libraries > 0 ? ", " + count(libraries, "library", "libraries") : "");
```
The five-argument overload: `return layer(origin, originPaths, modules, 0, Map.of(), weaving, paths);`. Update the Javadoc of both.

`StartupFacts`:
```java
    /** The modules of the layer other than the application's; 0 when the application is unknown. */
    static int libraries(List<CoreSections.LayerModule> modules, String application) {
        if (application == null) {
            return 0;
        }
        return (int) modules.stream().filter(m -> !m.name().equals(application)).count();
    }
```
and in `layer(...)`:
```java
        String application = applicationName();
        List<CoreSections.LayerModule> modules = modules(layer, installation.archives(), application);
        return CoreSections.layer(installation.origin(), installation.listed() ? installation.archives() : List.of(),
                modules, libraries(modules, application), installation.pinned(), weaving, paths);
```

- [ ] **Step 4: Run the core module**

Run: `bash <scratchpad>/core-test.sh t7-green vidocq-runtime-core` → `BUILD SUCCESS`. An existing report test whose expected headline had several modules without a known application keeps its text (`libraries` is 0 there); one with a known application and other modules now expects the `, N libraries` suffix: update that expectation, it is the specified behaviour.

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-core
git commit -s -S -m "feat(core): the layer section counts libraries and lists pinned modules (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 8: The dev reload releases the old layer: JDBC drivers and threads

A JDBC driver loaded from the layer registers itself in `DriverManager` from its static initializer; `DriverManager` then holds the old layer after a reload. `DriverManager.getDrivers()` and `deregisterDriver` only act on drivers visible to the **caller's** class loader, so the code that deregisters must be defined in the layer: the core defines a tiny helper class into a package of the layer through `MethodHandles.Lookup.defineClass` (the technique of Tomcat's `JdbcLeakPrevention`). Threads still running with the layer's loader are reported. Ruling on spec test 3 ("the startup report warns about the leaked thread"): the warning is a log line written at the reload, right before the new boot prints its startup report; the report itself only holds strings of the current layer and has no view of the previous one.

**Files:**
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/JdbcLeakPrevention.java`
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/LayerJdbcDrivers.java`
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/LayerThreads.java`
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqAppLayer.java` (`resetForReload`)
- Modify: `vidocq-runtime-core/src/main/java/module-info.java` (`requires static java.sql;`)
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/LayerJdbcDriversTest.java`, `LayerThreadsTest.java`

**Interfaces:**
- Produces: `static int LayerJdbcDrivers.deregister(VaubanLayerFactory.AppLayer layer)` (number of drivers deregistered; `0` when `java.sql` is not in the boot layer); `static List<String> LayerThreads.holding(ClassLoader loader)` (names of live threads, other than the current one, whose context class loader or whose `Thread` subclass's loader is `loader`).

- [ ] **Step 1: Write the failing tests**

```java
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A JDBC driver of the application layer no longer pins the layer after a reload (spec §5, mansart#42 review). */
@DisplayName("LayerJdbcDrivers — the drivers of a closing layer are deregistered")
class LayerJdbcDriversTest {

    private static final ClassDesc DRIVER = ClassDesc.of("java.sql.Driver");
    private static final ClassDesc FAKE = ClassDesc.of("acme.driver.FakeDriver");

    @Test
    @DisplayName("a driver that registered itself from the layer is deregistered once")
    void deregistersTheLayersDrivers(@TempDir Path dir) throws Exception {
        Path module = driverModule(dir);
        var layer = VaubanLayerFactory.createAppLayer(List.of(module), ModuleLayer.boot(),
                ClassLoader.getSystemClassLoader(), PluginContext.empty());
        try {
            Class.forName("acme.driver.FakeDriver", true, layer.loader()); // its <clinit> registers it

            assertEquals(1, LayerJdbcDrivers.deregister(layer));
            assertEquals(0, LayerJdbcDrivers.deregister(layer), "nothing is left to deregister");
        } finally {
            layer.loader().close();
        }
    }

    /** acme.driver: requires java.sql; FakeDriver implements Driver and registers itself in its static initializer. */
    private static Path driverModule(Path dir) throws Exception {
        Path root = dir.resolve("acme.driver");
        Files.createDirectories(root.resolve("acme/driver"));
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.driver"), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.sql"), 0, null));
                })));
        Files.write(root.resolve("acme/driver/FakeDriver.class"), ClassFile.of().build(FAKE, cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(DRIVER)
                .withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, b -> b
                        .aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void)).return_())
                .withMethodBody("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC, b -> b
                        .new_(FAKE).dup().invokespecial(FAKE, "<init>", MethodTypeDesc.of(CD_void))
                        .invokestatic(ClassDesc.of("java.sql.DriverManager"), "registerDriver",
                                MethodTypeDesc.of(CD_void, DRIVER))
                        .return_())));
        return root;
    }
}
```

```java
package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LayerThreads — threads that still hold a closing layer")
class LayerThreadsTest {

    @Test
    @DisplayName("a live thread whose context loader is the layer's is reported by name; others are not")
    void reportsThreadsHoldingTheLoader() throws Exception {
        ClassLoader layerLoader = new ClassLoader(null) { };
        var stop = new CountDownLatch(1);
        Thread holder = new Thread(() -> awaitQuietly(stop), "acme-poller");
        holder.setContextClassLoader(layerLoader);
        holder.setDaemon(true);
        holder.start();
        try {
            var names = LayerThreads.holding(layerLoader);
            assertTrue(names.contains("acme-poller"), names.toString());
            assertFalse(names.contains(Thread.currentThread().getName()));
        } finally {
            stop.countDown();
            holder.join();
        }
        assertFalse(LayerThreads.holding(layerLoader).contains("acme-poller"), "a stopped thread is not reported");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Run, watch them fail**

Run: `bash <scratchpad>/core-test.sh t8-red vidocq-runtime-core -Dtest='LayerJdbcDriversTest,LayerThreadsTest'`
Expected: compilation errors. If, once compiled (Step 4), `deregistersTheLayersDrivers` fails at `createAppLayer` with `java.sql not found`, the test JVM's boot layer lacks `java.sql` (core only `requires static` it): add `--add-modules java.sql` to the core module's Surefire `argLine` in `vidocq-runtime-core/pom.xml`, keeping any existing `argLine` content. That is the real launch condition: a driver in the layer only links when something in the boot layer resolved `java.sql` (Mansart, the migration extensions do).

- [ ] **Step 3: Implement**

`module-info.java` of core: add `requires static java.sql;` with the comment `// LayerJdbcDrivers: the helper whose bytes are defined in a closing layer references java.sql; it never runs in this module.`

`JdbcLeakPrevention.java`:
```java
package io.vidocq.runtime.core;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;

/**
 * Never loaded by the core: {@link LayerJdbcDrivers} reads these bytes and defines them, renamed, in a package of a
 * closing application layer, because {@link DriverManager} only lists and deregisters the drivers its caller's
 * class loader sees. No reference to its own class: the rename touches nothing else.
 */
final class JdbcLeakPrevention {

    private JdbcLeakPrevention() {}

    /** Deregisters every driver defined by {@code owner}; returns how many. */
    public static int deregister(ClassLoader owner) throws SQLException {
        int count = 0;
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == owner) {
                DriverManager.deregisterDriver(driver);
                count++;
            }
        }
        return count;
    }
}
```

`LayerJdbcDrivers.java`:
```java
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanLayerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Deregisters the JDBC drivers a closing application layer defined, so that {@code DriverManager} does not keep the
 * old layer after a dev reload (spec 2026-10-13-application-layer-dependencies §5).
 */
final class LayerJdbcDrivers {

    private static final String HELPER = "JdbcLeakPrevention.class";

    private LayerJdbcDrivers() {}

    /** The number of drivers deregistered; 0 when the boot layer has no java.sql, so no driver can be loaded. */
    static int deregister(VaubanLayerFactory.AppLayer layer) throws ReflectiveOperationException, IOException {
        var javaSql = ModuleLayer.boot().findModule("java.sql");
        if (javaSql.isEmpty()) {
            return 0;
        }
        Class<?> host = anyClassOf(layer);
        if (host == null) {
            return 0;
        }
        Module module = host.getModule();
        Module core = LayerJdbcDrivers.class.getModule();
        layer.controller().addReads(module, javaSql.get());
        layer.controller().addOpens(module, host.getPackageName(), core);
        core.addReads(module);
        var lookup = MethodHandles.privateLookupIn(host, MethodHandles.lookup());
        Class<?> helper = lookup.defineClass(renamed(host.getPackageName()));
        var deregister = lookup.findStatic(helper, "deregister", MethodType.methodType(int.class, ClassLoader.class));
        try {
            return (int) deregister.invoke(layer.loader());
        } catch (Throwable t) {
            throw new IllegalStateException("Deregistering the JDBC drivers of the old layer failed", t);
        }
    }

    private static byte[] renamed(String pkg) throws IOException {
        try (InputStream in = LayerJdbcDrivers.class.getResourceAsStream(HELPER)) {
            if (in == null) {
                throw new IOException(HELPER + " is missing from the core");
            }
            var model = ClassFile.of().parse(in.readAllBytes());
            return ClassFile.of().transformClass(model, ClassDesc.of(pkg + ".VidocqJdbcLeakPrevention"),
                    ClassTransform.ACCEPT_ALL);
        }
    }

    /** A class of the layer, loaded without initialization, to host the helper's package; null when none loads. */
    private static Class<?> anyClassOf(VaubanLayerFactory.AppLayer layer) {
        for (String name : layer.loader().managedClassNames()) {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || name.endsWith("module-info")) {
                continue;
            }
            String pkg = name.substring(0, dot);
            for (Module m : layer.layer().modules()) {
                if (m.getPackages().contains(pkg)) {
                    Class<?> c = Class.forName(m, name);
                    if (c != null) {
                        return c;
                    }
                }
            }
        }
        return null;
    }
}
```
`managedClassNames()` returns binary names (dots); check its Javadoc in Vauban and convert from `a/b/C` if it returns internal names. A second `deregister` call on the same layer defines the helper a second time in the same package: `defineClass` then fails with `LinkageError` (duplicate class). Make the helper name unique per call: append `System.nanoTime()` to `VidocqJdbcLeakPrevention`.

`LayerThreads.java`:
```java
package io.vidocq.runtime.core;

import java.util.ArrayList;
import java.util.List;

/** Live threads that still hold an application layer after its shutdown: they keep it in memory. */
final class LayerThreads {

    private LayerThreads() {}

    /** The names of the live threads, other than the current one, whose context or own class loader is {@code loader}. */
    static List<String> holding(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == Thread.currentThread() || !t.isAlive()) {
                continue;
            }
            if (t.getContextClassLoader() == loader || t.getClass().getClassLoader() == loader) {
                names.add(t.getName());
            }
        }
        names.sort(null);
        return names;
    }
}
```

`VidocqAppLayer.resetForReload()`, after `Thread.currentThread().setContextClassLoader(parentLoaderBeforeInstall);` and before `layer.loader().close()`:
```java
        try {
            int drivers = LayerJdbcDrivers.deregister(layer);
            if (drivers > 0) {
                LOG.log(System.Logger.Level.INFO, "Hot reload: deregistered " + drivers
                        + " JDBC driver(s) of the previous application layer");
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hot reload: the JDBC drivers of the previous application layer"
                    + " could not be deregistered, and keep it in memory: " + e);
        }
        var threads = LayerThreads.holding(layer.loader());
        if (!threads.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Hot reload: " + threads.size() + " thread(s) started by the"
                    + " previous application layer still run after shutdown and keep it in memory: "
                    + String.join(", ", threads) + ". Stop them when the application stops, or keep their library"
                    + " in the parent layer with vidocq.app.layer.keep.");
        }
```
The deregistration runs after the context loader is restored, so that the `DriverManager` initialization it may trigger does not load every driver of the old layer.

- [ ] **Step 4: Run the tests, then the core module**

Run: `bash <scratchpad>/core-test.sh t8-green vidocq-runtime-core -Dtest='LayerJdbcDriversTest,LayerThreadsTest'` → PASS.
Run: `bash <scratchpad>/core-test.sh t8-core vidocq-runtime-core` → `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-core
git commit -s -S -m "feat(core): a dev reload deregisters the old layer's JDBC drivers and names leaked threads (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 9: Lookups that must use the layer's loader

Spec §5. `MigrationExtension` looks migrators up with its own loader; `LiquibaseSchemaMigrator` connects through `DriverManager`. Both miss what the application layer holds. The dev console's `CdiInventory` already reads the installed application layer (`ApplicationLayer.current()`); its `ModuleLayer.boot()` branch only serves a launch without a layer, which `all` never produces: no change there.

**Files:**
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension/src/main/java/io/vidocq/runtime/extensions/essentials/migration/MigrationExtension.java` (~line 118)
- Test: `.../vidocq-runtime-migration-extension/src/test/java/io/vidocq/runtime/extensions/essentials/migration/MigrationExtensionTest.java`
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-liquibase-migration-extension/src/main/java/io/vidocq/runtime/extensions/essentials/migration/liquibase/ApplicationDrivers.java`
- Modify: `.../liquibase/LiquibaseSchemaMigrator.java` (~line 140), `.../vidocq-runtime-liquibase-migration-extension/src/main/java/module-info.java`
- Test: `.../liquibase/ApplicationDriversTest.java` (new)

**Interfaces:**
- Produces: `static ClassLoader MigrationExtension.backendLoader()` (the context loader, else the extension's own); `static Connection ApplicationDrivers.connect(String url, String user, String password, ClassLoader loader) throws SQLException`.

- [ ] **Step 1: Write the failing tests**

`MigrationExtensionTest`:
```java
    @Test
    void migratorsAreLookedUpThroughTheContextLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader application = new ClassLoader(previous) { };
        Thread.currentThread().setContextClassLoader(application);
        try {
            assertSame(application, MigrationExtension.backendLoader(),
                    "a migrator shipped in the application layer is only visible through it");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void withoutAContextLoaderTheExtensionsOwnIsUsed() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            assertSame(MigrationExtension.class.getClassLoader(), MigrationExtension.backendLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
```

`ApplicationDriversTest` (check the Liquibase extension's test dependencies first: `LiquibaseSchemaMigratorTest` runs against an embedded database; use the same JDBC URL scheme it uses, H2 most likely):
```java
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDriversTest {

    @Test
    void connectsThroughADriverTheLoaderSees() throws Exception {
        try (var connection = ApplicationDrivers.connect("jdbc:h2:mem:drivers", "sa", "",
                ApplicationDriversTest.class.getClassLoader())) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void noDriverNamesTheUrlAndTheFix() {
        var e = assertThrows(SQLException.class, () -> ApplicationDrivers.connect("jdbc:nothing:here", "u", "p",
                ApplicationDriversTest.class.getClassLoader()));
        assertTrue(e.getMessage().contains("jdbc:nothing:here"), e.getMessage());
        assertTrue(e.getMessage().contains("add the JDBC driver as a dependency"), e.getMessage());
    }
}
```

- [ ] **Step 2: Run, watch them fail**

Run: `bash <scratchpad>/core-test.sh t9-red vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension -Dtest=MigrationExtensionTest`
and `bash <scratchpad>/core-test.sh t9-red-lb vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-liquibase-migration-extension -Dtest=ApplicationDriversTest`
Expected: compilation errors.

- [ ] **Step 3: Implement**

`MigrationExtension`:
```java
    /** Where migrators are looked up: the context loader, the application layer's; the extension's own without one. */
    static ClassLoader backendLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : MigrationExtension.class.getClassLoader();
    }
```
and line 118 becomes `ServiceLoader.load(SchemaMigrator.class, backendLoader()).forEach(found::add);`.

`ApplicationDrivers`:
```java
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Connects through the JDBC drivers the application's class loader sees, as a servlet container does:
 * {@code DriverManager} refuses a driver its caller's loader cannot see, and a driver of the application layer is
 * not visible from this extension (spec 2026-10-13-application-layer-dependencies §5).
 */
final class ApplicationDrivers {

    private ApplicationDrivers() {}

    static Connection connect(String url, String user, String password, ClassLoader loader) throws SQLException {
        Properties info = new Properties();
        if (user != null) {
            info.setProperty("user", user);
        }
        if (password != null) {
            info.setProperty("password", password);
        }
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            if (driver.acceptsURL(url)) {
                Connection connection = driver.connect(url, info);
                if (connection != null) {
                    return connection;
                }
            }
        }
        throw new SQLException("No JDBC driver accepts " + url + " on the application's class path: add the JDBC"
                + " driver as a dependency of the application");
    }
}
```
`LiquibaseSchemaMigrator` line ~140: `try (Connection conn = ApplicationDrivers.connect(t.jdbcUrl(), t.username(), t.password(), applicationLoader())) {`. Remove the `DriverManager` import if unused.
Liquibase extension `module-info.java`: add `uses java.sql.Driver;` under `requires java.sql;`.

- [ ] **Step 4: Run both extension modules' suites**

Run: `bash <scratchpad>/core-test.sh t9-green vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension`
and `bash <scratchpad>/core-test.sh t9-green-lb vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-liquibase-migration-extension`
Expected: `BUILD SUCCESS` for both, including `LayeredLiquibaseProcessTest` (it boots a layered launch and must still connect).

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-liquibase-migration-extension
git commit -s -S -m "fix(migration): migrators and Liquibase drivers come from the application's loader (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 10: Launch-shape ITs (spec test 7, and test 3's reload)

A fixture whose library behaves like Vaadin's `ResourceProviderImpl`: an automatic module that looks up an application resource through **its own** class loader. Under `classes` it misses it (control); under `all` it finds it, in `vidocq:run`, `vidocq:dev` (and again after a reload, with the leaked-thread warning) and the packaged scripts.

**Files:**
- Create: `vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer/pom.xml`
- Create: `vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer/src/it/it-settings.xml` (copy of `vidocq-runtime-it-continuous-testing/src/it/it-settings.xml`)
- Create: fixture `src/it/fixture/pom.xml`, `src/it/fixture/.mvn/maven.config`, `src/it/fixture/lib/pom.xml`, `src/it/fixture/lib/src/main/java/acme/pages/Pages.java`, `src/it/fixture/app/pom.xml`, `src/it/fixture/app/src/main/java/module-info.java`, `.../app/src/main/java/acme/app/AppLayerApp.java`, `.../app/src/main/java/acme/app/Probe.java`, `.../app/src/main/resources/META-INF/acme/index.html`, `.../app/src/main/resources/META-INF/beans.xml`
- Create: `src/test/java/io/vidocq/runtime/it/applayer/Fixture.java`, `AppLayerRunIT.java`, `AppLayerDevIT.java`, `AppLayerPackageIT.java`
- Modify: `vidocq-runtime-integration-tests/pom.xml` (profile `it`: add the module)

**Interfaces:**
- Consumes: everything above, through the published plugin of this reactor.

- [ ] **Step 1: The IT module**

`pom.xml`: copy `vidocq-runtime-it-continuous-testing/pom.xml`, then change `artifactId` to `vidocq-runtime-it-app-layer`, `name` to `Vidocq Runtime :: IT :: Application layer (vidocq.app.layer=all)`, the header comment to `Vidocq/vidocq#247: vidocq:run, vidocq:dev and vidocq:package with vidocq.app.layer=all on a fixture whose flat-classpath library looks up an application resource through its own loader.`, and keep only the dependencies `vidocq-runtime-maven-plugin` (maven-plugin), `vidocq-runtime-core`, `vidocq-runtime-core-codegen` and `junit-jupiter` (drop devconsole and the JSON ones). In the `copy-resources` execution, the filtered includes become `pom.xml`, `*/pom.xml` and `.mvn/**`, and the unfiltered excludes the same three.

In `vidocq-runtime-integration-tests/pom.xml`, profile `it`: add `<module>vidocq-runtime-it-app-layer</module>` after the continuous-testing one.

- [ ] **Step 2: The fixture**

`src/it/fixture/pom.xml` (aggregator, no parent: the library must not get the runtime parent's module checks):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.vidocq.it</groupId>
    <artifactId>app-layer-fixture</artifactId>
    <version>1.0</version>
    <packaging>pom</packaging>
    <modules>
        <module>lib</module>
        <module>app</module>
    </modules>
</project>
```

`src/it/fixture/.mvn/maven.config`: identical to the continuous-testing fixture's (`-Dmaven.repo.local=@it.repo@`, `--settings`, `@it.settings@`).

`src/it/fixture/lib/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <!-- A flat-classpath library, as Vaadin's: no module-info, an Automatic-Module-Name. -->
    <groupId>io.vidocq.it</groupId>
    <artifactId>app-layer-pages</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Automatic-Module-Name>acme.pages</Automatic-Module-Name>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`lib/src/main/java/acme/pages/Pages.java`:
```java
package acme.pages;

import java.net.URL;

/** Looks a page up through its own class loader, as a framework built for a flat class path does. */
public final class Pages {

    private Pages() {}

    /** The URL of {@code META-INF/acme/<name>} as this library's loader sees it, or {@code null}. */
    public static URL find(String name) {
        return Pages.class.getClassLoader().getResource("META-INF/acme/" + name);
    }

    /** Starts a daemon poller, as a library with a background thread does; it never stops by itself. */
    public static void startPoller() {
        Thread poller = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "acme-pages-poller");
        poller.setDaemon(true);
        poller.start();
    }
}
```

`app/pom.xml`: copy `vidocq-runtime-it-continuous-testing/src/it/fixture/pom.xml`, then: `artifactId` `app-layer-app`; properties `vidocq.mainModule` = `acme.app`, `vidocq.mainClass` = `acme.app.AppLayerApp`; dependencies: `vidocq-runtime-core` and `io.vidocq.it:app-layer-pages:1.0` (no Chappe, no JUnit). Keep the runtime parent and the plugin entry with `@project.version@`.

`app/src/main/java/module-info.java`:
```java
module acme.app {
    requires io.vidocq.runtime.core;
    requires jakarta.cdi;
    requires acme.pages;

    exports acme.app;
}
```

`AppLayerApp.java` — deliberately **without** `@VidocqMain`: with it, `vidocq:package` writes the trampoline script, and the trampoline keeps automatic modules in the boot layer until vidocq#246 gives them a descriptor (spec §4), so the packaged run would not exercise `all`:
```java
package acme.app;

import io.vidocq.runtime.core.Vidocq;

public final class AppLayerApp {

    private AppLayerApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
```

`Probe.java` (a CDI observer, so that the dev reload, which boots without the application main, runs it too):
```java
package acme.app;

import acme.pages.Pages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;

@ApplicationScoped
public class Probe {

    void onStart(@Observes Startup startup) {
        var found = Pages.find("index.html");
        System.out.println(found != null ? "LOOKUP-FOUND " + found : "LOOKUP-MISSING");
        Pages.startPoller();
    }
}
```

`META-INF/acme/index.html`: `<h1>acme</h1>`. `META-INF/beans.xml`: `<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.1" bean-discovery-mode="annotated"/>`.

- [ ] **Step 3: The harness and the ITs**

`Fixture.java`: copy `copy`, `writeSettings`, `logs`, `start`, `read`, `awaitLog` and `occurrences` from the continuous-testing `Fixture` (same package-private signatures, `TIMEOUT` 3 minutes), plus:

```java
    /** Builds the library and the application into the fixture's repository, online, once per IT. */
    static void install(Path project, Path log) throws Exception {
        Process process = start(project, log, "install", "-DskipTests");
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process);
            fail("the fixture build did not end within " + TIMEOUT + ":\n" + read(log));
        }
        assertEquals(0, process.exitValue(), () -> "the fixture build failed:\n" + read(log));
    }

    /** Stops a subprocess and everything it started. */
    static void stop(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
```

`AppLayerRunIT.java`:
```java
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq:run: the library sees the application's resource under all, not under classes. */
class AppLayerRunIT {

    @Test
    void allLetsTheLibrarySeeTheApplicationsResource(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-install.log"));
        Path log = Fixture.logs().resolve("run-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", "-Dvidocq.app.layer=all");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-FOUND");
            assertTrue(Fixture.read(log).contains("application layer: 1 libraries"), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void classesIsTheControl(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-classes-install.log"));
        Path log = Fixture.logs().resolve("run-classes.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-MISSING");
            assertFalse(Fixture.read(log).contains("LOOKUP-FOUND"));
        } finally {
            Fixture.stop(mvn);
        }
    }
}
```
(The run line counts the libraries with the plugin's wording of Task 5, `application layer: <n> libraries`.)

`AppLayerDevIT.java`:
```java
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** vidocq:dev under all: the lookup works, and again after a reload, which names the library's leaked thread. */
class AppLayerDevIT {

    @Test
    void theLookupSurvivesAReloadAndTheLeakedThreadIsNamed(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("dev-install.log"));
        Path log = Fixture.logs().resolve("dev-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:dev", "-Dvidocq.app.layer=all");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-FOUND");
            Path probe = project.resolve("app/src/main/java/acme/app/Probe.java");
            Files.writeString(probe, Files.readString(probe) + "\n// changed by AppLayerDevIT\n");
            Fixture.awaitLog(mvn, log, "acme-pages-poller");
            long deadline = System.currentTimeMillis() + Fixture.TIMEOUT.toMillis();
            while (Fixture.occurrences(log, "LOOKUP-FOUND") < 2) {
                if (System.currentTimeMillis() > deadline) {
                    fail("no second LOOKUP-FOUND after the reload:\n" + Fixture.read(log));
                }
                Thread.sleep(250);
            }
            assertEquals(0, Fixture.occurrences(log, "LOOKUP-MISSING"), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }
}
```
(`acme-pages-poller` appears in the warning of Task 8: `... still run after shutdown and keep it in memory: acme-pages-poller ...`.)

`AppLayerPackageIT.java`:
```java
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq:package under all: the library ships in app/, and the script's launch finds the resource. */
class AppLayerPackageIT {

    @Test
    void theDistributionShipsTheLibraryInApp(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("package-install.log"));
        Path log = Fixture.logs().resolve("package-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "package", "vidocq:package",
                "-Dvidocq.app.layer=all", "-DskipTests");
        assertEquals(0, mvn.waitFor(), () -> Fixture.read(log));

        Path script = script(project.resolve("app/target"));
        Path dist = script.getParent().getParent();
        assertTrue(Files.exists(dist.resolve("app/app-layer-pages-1.0.jar")), "the library is the application's");
        assertTrue(Files.notExists(dist.resolve("lib/app-layer-pages-1.0.jar")));

        Path runLog = Fixture.logs().resolve("package-run.log");
        Process run = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true)
                .redirectOutput(runLog.toFile()).start();
        try {
            Fixture.awaitLog(run, runLog, "LOOKUP-FOUND");
        } finally {
            Fixture.stop(run);
        }
    }

    private static Path script(Path target) throws Exception {
        try (Stream<Path> files = Files.walk(target, 3)) {
            List<Path> scripts = files.filter(p -> p.getFileName().toString().endsWith(".sh")
                    && p.getParent().getFileName().toString().equals("bin")).toList();
            assertEquals(1, scripts.size(), () -> "one launch script expected under " + target + ": " + scripts);
            return scripts.get(0);
        }
    }
}
```
`Fixture.awaitLog` fails fast when the process exits before the text: the run without an HTTP server may exit once booted, which is fine because the probe prints during the boot.

- [ ] **Step 4: Run the ITs**

Script `<scratchpad>/applayer-it.sh`:
```bash
#!/bin/bash
S=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq/d7ea8f44-fd18-4b7c-9213-1d2b758c463b/scratchpad
cd $S/wt-applayer || exit 1
source ~/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1
export MAVEN_OPTS="-Dmaven.repo.local=$S/m2-applayer"
./mvnw -B -ntp -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer -am install -DskipTests=false -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false > $S/applayer-it-$1.log 2>&1
echo "rc=$?"
grep -E 'Tests run:|BUILD|FAIL' $S/applayer-it-$1.log | tail -12
```
Run: `bash <scratchpad>/applayer-it.sh t10`
Expected: `Tests run: 4, Failures: 0, Errors: 0` from Failsafe, `BUILD SUCCESS`. On failure, read `vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer/target/it-logs/*.log`. To prove the ITs discriminate, temporarily change `AppLayerRunIT.allLetsTheLibrarySeeTheApplicationsResource` to run without `-Dvidocq.app.layer=all`: it must fail on `LOOKUP-FOUND`; revert.

- [ ] **Step 5: Commit**

```bash
git add -A vidocq-runtime-integration-tests
git commit -s -S -m "test(it): run, dev and package with vidocq.app.layer=all on a flat-classpath library (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 11: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc` (the `run`, `dev`, `package` parameter tables: find the `vidocq.dev.layer` / `vidocq.package.layer` rows)
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (the section that explains the application layer: `grep -n "vidocq.app.path\|application layer" usage.adoc`)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`

- [ ] **Step 1: Write the docs**

Plugin reference, in each of the three goals' tables, two rows:
- `vidocq.app.layer` | `classes` | `classes`: the application layer holds the project's classes, dependencies stay on the module path. `all`: it also holds every dependency outside the runtime's closure, as `WEB-INF/lib` in a servlet container. `[.tag-new]#NEW#`
- `vidocq.app.layer.keep` | – | Under `all`, comma-separated patterns kept in the parent layer: a module name or `groupId:artifactId`, `*` alone or as a suffix. `[.tag-new]#NEW#`

Usage, a new subsection `=== Dependencies in the application layer [.tag-new]#NEW#` after the existing application-layer explanation, covering, in this order:
1. What `all` does, and why (a framework that looks up resources through its own loader, e.g. Vaadin, sees the application). `classes` is still the default; `all` becomes the default once the validation campaign is green.
2. The rule: the parent layer is the closure of the runtime (Jakarta and MicroProfile APIs, Vidocq modules, modules providing a Vidocq extension, modules with `javax.*`/`sun.*` packages, pinned modules, and what they require); everything else is the application's, automatic modules included.
3. Pinning: `vidocq.app.layer.keep` and the `Vidocq-Parent-Layer` manifest attribute of a brick; a pinned module drags what it requires; a pinned module that reads the application fails the build with both names.
4. The startup report: the `layer` headline counts the libraries; the detailed report lists each pinned module with its reason.
5. Dev reload: the libraries are reloaded with the application. JDBC drivers loaded from the layer are deregistered at each reload; threads a library leaves running are named in a warning and keep the old layer in memory. A library with native code (JNI) cannot be loaded twice by the JVM: keep it in the parent layer with `vidocq.app.layer.keep`.
6. Known limits: a plain IDE run of `main` (the `Vidocq.run` trampoline) keeps automatic libraries in the boot layer, the `(dev)` configuration written by `vidocq:idea` has no such limit; `vidocq:jlink`, jpackage and Docker images are unchanged (the application still runs in one layer with its libraries in the image); jar signatures are not verified in the layer.

What's new, under the 0.4.0 section: one entry "The application layer can hold the application's dependencies (`vidocq.app.layer=all`) [.tag-new]#NEW#" with two sentences and a link to the usage subsection (`xref:usage.adoc#…`, use the anchor Antora generates for the new title or add an explicit `[#app-layer-dependencies]`).

- [ ] **Step 2: Render and check**

Validate the AsciiDoc as memory `reference_asciidoc_local_validation` says: render each changed page to HTML (`asciidoctor -o - <page>`) and check that the new table rows and the subsection appear, and that no `WARNING` is printed. Run it through a `.sh` script in the scratchpad.

- [ ] **Step 3: Commit**

```bash
git add -A docs
git commit -s -S -m "docs: vidocq.app.layer and the application layer's dependencies (#247)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

## After the tasks

1. Full build of the vidocq branch in the isolated repository with the CI profile: `CI=true ./mvnw -B -ntp install -Pit` from a script (the `it` profile runs Task 10's ITs and the plugin's invoker ITs). Expected `BUILD SUCCESS`. The `cyrano-jpms` IT failing in a full reactor is a known, pre-existing failure (memory `reference_vidocq_full_test_gate_it_failure`): compare with `origin/main` in a worktree before blaming the branch.
2. Push `feat/app-layer-all`, open the vidocq PR (body: summary per task, the rulings, the Vauban PR it depends on, the test evidence, the Claude Code footer). The vidocq PR's CI needs the Vauban PR of Task 1 merged and deployed first: say so in the PR body. No merge without the maintainer's go.
