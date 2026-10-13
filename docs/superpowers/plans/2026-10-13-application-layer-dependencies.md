# Application layer holds the application's dependencies — Implementation Plan (part 1: Vauban, vauban#154)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Vauban what the Vidocq launchers need to put the application and all its libraries in one
application layer: multi-release jars read correctly, a re-layer policy that keeps a runtime's closure rather
than name prefixes, and tests that pin the behaviours the design relies on.

**Architecture:** All changes are in `vauban-classloader`. `BuiltInReaders.JarReader` opens jars as multi-release
for the running feature version. `VaubanLayerFactory.applicationPaths` gains an overload taking `keptRoots`: the
modules whose `requires` closure stays in the parent layer whatever their names. Two tests pin behaviours that
already hold, because the Vidocq side builds on them:
- an automatic module defined in the application layer sees the application's resources through its own loader;
- a plain library class is not transformed.

**Tech Stack:** Java 25, JPMS (`ModuleLayer`, `Configuration`), Class-File API (JEP 484) for test fixtures,
JUnit 5, Maven 3.9.16 (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-10-13-application-layer-dependencies-design.md` (vidocq repo, branch
`docs/app-layer-dependencies-design`). Read sections "Developer experience", 1, 4 and 6.

## The plans of this effort

The spec spans three repositories. Each part is its own plan, written when the previous one lands, so that it
builds on merged code:

1. **This plan**: Vauban, vauban#154. It depends on vauban#155 (`CodeSource`), already in auto-merge.
2. **Mansart lookups** (Mansart issue to open): the pool and the Liquibase migrator load `java.sql.Driver`
   through `ServiceLoader` with the layer's loader and call `Driver.connect` instead of `DriverManager`.
3. **Vidocq core, vidocq#247**:
   - `LayerSplit`, the `vidocq.app.layer` setting and the `Vidocq-Parent-Layer` / `vidocq.app.layer.keep` escape
     hatch;
   - `run`, `dev`, `package` and the trampoline;
   - `MigrationExtension` and the dev console `CdiInventory` lookups;
   - the startup report line, and tests 1, 3, 6 and 7 of the spec.
4. **Reload cache**: archive readers of unchanged jars kept across dev reloads, measured in BENCH.md against
   the target of criterion 3.
5. **Dependency generation, vidocq#246 + foy#43 + mansart#40**:
   - explicit descriptors for application-layer automatic jars;
   - `-proc:only` runs of every brick's processor, once per jar version;
   - the one-line diagnostics.
6. **Campaign and default switch**: everything in the spec's campaign with `vidocq.app.layer=all`, then
   `all` becomes the default.

## Global Constraints

- Java 25 (Temurin) and Maven 3.9.16, through `./mvnw` in the vauban repository.
- JPMS strict: no new `opens`, no automatic module in Vauban's own artifacts.
- Zero external dependency in `vauban-classloader`: JDK only.
- TDD: write the test, watch it fail for the stated reason, implement, watch it pass.
- English for code, comments, Javadoc, commits and docs. Commits signed: `git commit -s -S`, with the trailers
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY`.
- Every visible change gets a What's new entry and a `[.tag-new]#NEW#` badge on the documented section; every
  reproducible bug gets a `BUG.md` entry.
- Verification runs in an isolated local Maven repository (`-Dmaven.repo.local=<scratch>/m2-…`), never
  `~/.m2`. The final check is the full `CI=true ./mvnw install`.

## Review Focus

1. A jar that is **not** multi-release, or a directory archive, must read exactly as today: the base entries,
   no `META-INF/versions/` class in the index. Pinned in Task 1.
2. A class that exists **only** under `META-INF/versions/N/` must still be loadable on a JDK ≥ N. Pinned in
   Task 1.
3. A **resource** under `META-INF/versions/N/` must win over the base one, as `JarFile` does for the module
   path. Pinned in Task 1.
4. A module that a kept root reads **transitively**, two hops away, must stay in the parent layer, or the kept
   root cannot read it. Pinned in Task 3.
5. The old `applicationPaths(Configuration, Collection, Set)` must keep its exact behaviour for the Java SE
   launcher. Covered by the existing `LaunchSelectionTest`, which must stay green unchanged.

---

### Task 1: Multi-release jars

**Files:**
- Modify: `vauban-classloader/src/main/java/io/vidocq/vauban/classloader/BuiltInReaders.java` (class `JarReader`,
  constructor and `classEntries()`)
- Test: `vauban-classloader/src/test/java/io/vidocq/vauban/classloader/MultiReleaseJarTest.java` (create)
- Docs: `docs/en/modules/ROOT/pages/se.adoc` (section `[#code-source]`'s neighbour, see step 6),
  `docs/en/modules/ROOT/pages/whats-new.adoc`, `BUG.md`

**Interfaces:**
- Consumes: `VaubanClassLoader.of(List<Path>, ClassLoader, PluginContext)` (existing).
- Produces: no new API. `JarReader` reads a multi-release jar for `Runtime.version()`.

- [ ] **Step 1: Write the failing test**

```java
/*
 * (license header: copy the 18-line header of VaubanClassLoaderTest.java verbatim)
 */
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A multi-release jar read by the Vauban loader as the module path reads it: the entries of
 * {@code META-INF/versions/N/} win for a JDK at least N (vauban#154). Jackson, among others, ships such jars;
 * the loader used to index raw entry names, so the base version always won.
 */
@DisplayName("VaubanClassLoader — multi-release jars")
class MultiReleaseJarTest {

    private static final String VALUE = "com.example.mr.Value";
    private static final String ONLY_VERSIONED = "com.example.mr.OnlyVersioned";

    @Test
    @DisplayName("the versioned class, the versioned resource and a versions-only class win on this JDK")
    void versionedEntriesWin(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("mr.jar"), true);
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("versioned", Class.forName(VALUE, true, loader).getMethod("value").invoke(null));
            assertEquals("versioned", Class.forName(ONLY_VERSIONED, true, loader).getMethod("value").invoke(null));
            try (var in = loader.getResourceAsStream("com/example/mr/data.txt")) {
                assertNotNull(in);
                assertEquals("versioned", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(loader.managedClassNames().stream().noneMatch(n -> n.startsWith("META-INF")),
                    "no class named after a META-INF/versions/ entry: " + loader.managedClassNames());
        }
    }

    @Test
    @DisplayName("a jar without Multi-Release: true reads its base entries only")
    void plainJarReadsBaseEntries(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("plain.jar"), false);
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("base", Class.forName(VALUE, true, loader).getMethod("value").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(ONLY_VERSIONED, true, loader));
        }
    }

    /** A jar with a base {@code Value}, a versioned {@code Value} and resource, and a versions-only class. */
    private static Path jar(Path file, boolean multiRelease) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out, manifest)) {
            put(jar, "com/example/mr/Value.class", valueClass(VALUE, "base"));
            put(jar, "com/example/mr/data.txt", "base".getBytes(StandardCharsets.UTF_8));
            put(jar, "META-INF/versions/21/com/example/mr/Value.class", valueClass(VALUE, "versioned"));
            put(jar, "META-INF/versions/21/com/example/mr/data.txt", "versioned".getBytes(StandardCharsets.UTF_8));
            put(jar, "META-INF/versions/21/com/example/mr/OnlyVersioned.class", valueClass(ONLY_VERSIONED, "versioned"));
        }
        return file;
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    /** {@code public class <name> { public static String value() { return "<value>"; } }} */
    private static byte[] valueClass(String binaryName, String value) {
        return ClassFile.of().build(ClassDesc.of(binaryName), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("value", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> cob.ldc(value).areturn()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -B -ntp -pl vauban-classloader -am install -Dtest=MultiReleaseJarTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected:
- FAIL in `versionedEntriesWin`: `expected: <versioned> but was: <base>`;
- `plainJarReadsBaseEntries` PASSES. It pins today's behaviour, Review Focus 1.

- [ ] **Step 3: Implement**

In `BuiltInReaders.JarReader`, open the jar for the running version, and list entries through the versioned
view. On a multi-release jar opened this way, `getEntry` and `versionedStream` resolve each base name to the
highest applicable `META-INF/versions/N/` entry, and `JarEntry.getName()` is the base name. On a plain jar
they behave as before. Signatures stay unverified, as today (`verify = false`).

```java
        JarReader(Path path) throws IOException {
            // Multi-release aware, as the module path reads a jar: the base name resolves to the highest
            // META-INF/versions/N entry the running JDK accepts (vauban#154). No signature verification,
            // as before.
            this.jar = new JarFile(path.toFile(), false, java.util.zip.ZipFile.OPEN_READ, Runtime.version());
        }

        @Override
        public List<String> classEntries() {
            return jar.versionedStream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                    .map(java.util.jar.JarEntry::getName)
                    .toList();
        }
```

`readClass` and `readResource` keep `jar.getEntry(entryName)`, which is version-aware on a jar opened this way.
Leave `SjarArchiveReader` (`vauban-sjar`) unchanged: an sjar is produced by Vauban's own build, which does not
emit multi-release content.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B -ntp -pl vauban-classloader -am install -Dtest=MultiReleaseJarTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 2/2.

- [ ] **Step 5: Run the module's tests**

Run: `./mvnw -B -ntp -pl vauban-classloader install`
Expected: BUILD SUCCESS. The existing classloader tests stay green.

- [ ] **Step 6: Docs and BUG.md**

- `se.adoc`: after the `[#code-source]` subsection, add:

```asciidoc
[#multi-release]
=== Multi-release jars [.tag-new]#NEW#

The layer's loader reads a multi-release jar as the module path does: for a class or a resource, the entry of
`META-INF/versions/N/` with the highest `N` that the running JDK accepts wins over the base entry, and a class
that exists only under `META-INF/versions/N/` loads too. Before, the base entries always won (vauban#154).
Signatures are not verified in the layer, as before.
```

- `whats-new.adoc`: in "Running inside a Vauban layer", after the "A layer class knows its archive" bullet:

```asciidoc
* **Multi-release jars in the layer** — the layer's loader now reads a multi-release jar as the module path does: the `META-INF/versions/N/` entries win for the running JDK, so a library such as Jackson defines its versioned classes. The base version always won before (vauban#154). xref:se.adoc#multi-release[Multi-release jars].
```

- `BUG.md`: append at the end, in the format of `BUG-20261013-01`:

```markdown
## BUG-20261013-02 — The layer loader ignores the versioned entries of a multi-release jar

- **Date** : 2026-10-13
- **Statut** : FIXED (vauban#154)
- **Module touché** : vauban-classloader / BuiltInReaders.JarReader
- **Symptôme** : in a Vauban layer, a multi-release jar defines its base classes and resources, never the
  `META-INF/versions/N/` ones; a class that exists only there is not found.
- **Reproduction minimale** : `MultiReleaseJarTest.versionedEntriesWin`.
- **Hypothèse de cause** : `new JarFile(File)` is not version-aware, and `classEntries()` listed raw names.
- **Investigations** :
  - 2026-10-13 : found while designing vidocq#247 (every library in the application layer). Fixed by opening
    the jar for `Runtime.version()` and listing `versionedStream()`.
```

- [ ] **Step 7: Commit**

```bash
git add vauban-classloader/src/main/java/io/vidocq/vauban/classloader/BuiltInReaders.java \
        vauban-classloader/src/test/java/io/vidocq/vauban/classloader/MultiReleaseJarTest.java \
        docs/en/modules/ROOT/pages/se.adoc docs/en/modules/ROOT/pages/whats-new.adoc BUG.md
git commit -s -S -m "fix(classloader): read multi-release jars as the module path does (#154)"
```

(with the two trailers of the Global Constraints in the message body)

---

### Task 2: Pin the behaviours the Vidocq side relies on

These two tests pin what already holds. They are not TDD cycles. Verify that each one guards its behaviour by
breaking it on purpose, as Step 3 says, then restore the code.

**Files:**
- Test: `vauban-classloader/src/test/java/io/vidocq/vauban/classloader/ApplicationLibrariesInLayerTest.java` (create)

**Interfaces:**
- Consumes: `VaubanLayerFactory.createAppLayer(List<Path>, ModuleLayer, ClassLoader, PluginContext)` and
  `VaubanClassLoader.of(...)` (existing); `VaubanClassLoaderTest.simpleClass(String)` (existing
  package-private fixture).
- Produces: nothing.

- [ ] **Step 1: Write the tests**

```java
/*
 * (license header: copy the 18-line header of VaubanClassLoaderTest.java verbatim)
 */
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Vidocq launchers rely on once the application's libraries live in its layer (vidocq#247): a library
 * that looks resources up through its own class loader sees the application's, as in a servlet container's
 * {@code WEB-INF/lib}; and a library class that is not a bean is defined as read.
 */
@DisplayName("Application libraries in the Vauban layer")
class ApplicationLibrariesInLayerTest {

    private static final String FINDER = "auto.lib.Finder";
    private static final String APP_MODULE = "com.example.app";
    private static final String MARKER = "META-INF/app/marker.txt";

    @Test
    @DisplayName("an automatic module in the layer finds an application resource through its own loader")
    void automaticLibrarySeesApplicationResources(@TempDir Path dir) throws Exception {
        var library = automaticJar(dir.resolve("auto-lib.jar"));
        var app = applicationModule(dir.resolve("app"));

        var layer = VaubanLayerFactory.createAppLayer(List.of(library, app), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());

        var module = layer.layer().findModule("auto.lib").orElseThrow();
        assertTrue(module.getDescriptor().isAutomatic());
        var finder = Class.forName(module, FINDER);
        assertSame(layer.loader(), finder.getClassLoader(), "one loader for the application and its libraries");
        var url = (URL) finder.getMethod("find", String.class).invoke(null, MARKER);
        assertNotNull(url, "the library's own loader must see the application's resource");
        try (var in = url.openStream()) {
            assertEquals("app", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a library class that is not a bean gains no ProxyLink constructor")
    void plainLibraryClassIsNotWoven(@TempDir Path dir) throws Exception {
        var root = dir.resolve("lib");
        var file = root.resolve("com/example/lib/Plain.class");
        Files.createDirectories(file.getParent());
        Files.write(file, VaubanClassLoaderTest.simpleClass("com.example.lib.Plain"));

        try (var loader = VaubanClassLoader.of(List.of(root), getClass().getClassLoader(),
                PluginContext.empty())) {
            var plain = Class.forName("com.example.lib.Plain", true, loader);
            var proxyLink = Class.forName("io.vidocq.vauban.api.ProxyLink");
            assertThrows(NoSuchMethodException.class, () -> plain.getDeclaredConstructor(proxyLink));
        }
    }

    /** An automatic module {@code auto.lib}: {@code Finder.find(name)} = its own loader's {@code getResource}. */
    private static Path automaticJar(Path file) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "auto.lib");
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("auto/lib/Finder.class"));
            jar.write(finderClass());
            jar.closeEntry();
        }
        return file;
    }

    private static byte[] finderClass() {
        var self = ClassDesc.of(FINDER);
        var loader = ClassDesc.of("java.lang.ClassLoader");
        var url = ClassDesc.of("java.net.URL");
        return ClassFile.of().build(self, clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("find", MethodTypeDesc.of(url, ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> cob
                                .ldc(self)
                                .invokevirtual(ConstantDescs.CD_Class, "getClassLoader", MethodTypeDesc.of(loader))
                                .aload(0)
                                .invokevirtual(loader, "getResource", MethodTypeDesc.of(url, ConstantDescs.CD_String))
                                .areturn()));
    }

    /** An explicit module {@code com.example.app} that ships {@value #MARKER}. */
    private static Path applicationModule(Path root) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(APP_MODULE),
                mb -> mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
        var marker = root.resolve(MARKER);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "app");
        return root;
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./mvnw -B -ntp -pl vauban-classloader install -Dtest=ApplicationLibrariesInLayerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 2/2.

- [ ] **Step 3: Prove each test guards its behaviour**

- In `VaubanLayerFactory.createAppLayer`, replace `moduleName -> loader` with a loader per module, for example a
  fresh `VaubanClassLoader.forLayer(...)` per name. `automaticLibrarySeesApplicationResources` must then FAIL.
  Restore the code.
- In `CdiProxifierTransformer.interested(...)`, return `true` for every class. `plainLibraryClassIsNotWoven`
  must then FAIL, or the class must still gain no constructor. If it still passes, write in the commit message
  that the transformer decides on the class content and not on `interested`, and keep the test as a pin.
  Restore the code.

- [ ] **Step 4: Commit**

```bash
git add vauban-classloader/src/test/java/io/vidocq/vauban/classloader/ApplicationLibrariesInLayerTest.java
git commit -s -S -m "test(classloader): pin library resources and untouched plain classes in the layer (#154)"
```

---

### Task 3: Keep a runtime's closure, not only name prefixes

**Files:**
- Modify: `vauban-classloader/src/main/java/io/vidocq/vauban/classloader/VaubanLayerFactory.java`
  (`applicationPaths`, `keptByRule`)
- Test: `vauban-classloader/src/test/java/io/vidocq/vauban/classloader/KeptRootsPolicyTest.java` (create)
- Docs: `docs/en/modules/ROOT/pages/se.adoc` (subsection "Which modules are re-layered"), `whats-new.adoc`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `public static List<Path> applicationPaths(Configuration configuration, Collection<String> keptPrefixes,
    Set<String> keptRoots, Set<String> roots)`: as today, and also keeps every module named in `keptRoots`,
    whatever its name. What a kept module reads is kept transitively, as for any kept module.
  - The existing three-argument `applicationPaths` delegates with `keptRoots = Set.of()`.
  - Plan 3 (Vidocq) calls the new overload from `VidocqAppLayer.applicationPaths`, with
    `keptRoots = {"io.vidocq.runtime.core"} ∪ the Vidocq extension modules found`.

- [ ] **Step 1: Write the failing test**

```java
/*
 * (license header: copy the 18-line header of VaubanClassLoaderTest.java verbatim)
 */
package io.vidocq.vauban.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-layer policy with kept roots (vauban#154): a runtime names its root modules, and their whole
 * {@code requires} closure stays in the parent layer whatever the names; every other explicit module moves to the
 * application layer.
 */
@DisplayName("Re-layer policy — kept roots")
class KeptRootsPolicyTest {

    @Test
    @DisplayName("the kept roots' closure stays, two hops included; the application and its own library move")
    void keptRootsClosureStays(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "rt.lib");
        module(dir, "rt.lib", "rt.deep");
        module(dir, "rt.deep");
        module(dir, "app.main", "rt.core", "third.lib");
        module(dir, "third.lib");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(),
                Set.of("rt.core"), Set.of("app.main")));

        assertEquals(Set.of("app.main", "third.lib"), moved);
    }

    @Test
    @DisplayName("a library that a kept root reads stays in the parent layer, even if the application reads it too")
    void sharedLibraryStays(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "shared.lib");
        module(dir, "shared.lib");
        module(dir, "app.main", "rt.core", "shared.lib");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(),
                Set.of("rt.core"), Set.of("app.main")));

        assertEquals(Set.of("app.main"), moved);
    }

    @Test
    @DisplayName("without kept roots, the three-argument form keeps its behaviour")
    void threeArgumentFormUnchanged(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "rt.lib");
        module(dir, "rt.lib");
        module(dir, "app.main", "rt.core");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(), Set.of("app.main")));

        assertEquals(Set.of("app.main", "rt.core", "rt.lib"), moved);
    }

    private static void module(Path dir, String name, String... requires) throws IOException {
        var root = dir.resolve(name);
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(name), mb -> {
                    mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
                    for (var r : requires) {
                        mb.requires(ModuleDesc.of(r), 0, null);
                    }
                })));
    }

    private static Configuration resolve(Path dir, String root) throws IOException {
        try (var dirs = Files.list(dir)) {
            var finder = ModuleFinder.of(dirs.toArray(Path[]::new));
            return Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                    ModuleFinder.of(), Set.of(root));
        }
    }

    private static Set<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -B -ntp -pl vauban-classloader install -Dtest=KeptRootsPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `no suitable method found for applicationPaths(Configuration,List<Object>,Set<String>,Set<String>)`.
That is the expected red: the overload does not exist yet.

- [ ] **Step 3: Implement**

In `VaubanLayerFactory`, replace the body of the three-argument `applicationPaths` with a delegation. Add the
overload, and pass `keptRoots` to `keptByRule`. Keep the existing Javadoc on the overload, and add the
`@param keptRoots` line.

```java
    public static List<Path> applicationPaths(Configuration configuration,
            Collection<String> keptPrefixes, Set<String> roots) {
        return applicationPaths(configuration, keptPrefixes, Set.of(), roots);
    }

    /**
     * (the Javadoc of the three-argument form, plus:)
     * @param keptRoots modules kept whatever their names, with what they read transitively: a runtime's own
     *                  roots, so that its closure stays in the parent layer (vauban#154)
     */
    public static List<Path> applicationPaths(Configuration configuration,
            Collection<String> keptPrefixes, Set<String> keptRoots, Set<String> roots) {
        var kept = new LinkedHashSet<ResolvedModule>();
        var queue = new ArrayDeque<ResolvedModule>();
        for (var resolved : configuration.modules()) {
            if (keptByRule(resolved, keptPrefixes, keptRoots, roots) && kept.add(resolved)) {
                queue.add(resolved);
            }
        }
        // (the rest of today's body, unchanged)
    }

    private static boolean keptByRule(ResolvedModule resolved, Collection<String> keptPrefixes,
            Set<String> keptRoots, Set<String> roots) {
        var descriptor = resolved.reference().descriptor();
        if (descriptor.isAutomatic()) return true;
        for (var pkg : descriptor.packages()) {
            if (VaubanClassLoader.excludesPackage(pkg)) return true;
        }
        var name = resolved.name();
        if (roots.contains(name)) return false;
        if (keptRoots.contains(name)) return true;
        if (CONTAINER_MODULES.contains(name)) return true;
        for (var prefix : KEPT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (var prefix : keptPrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
```

Automatic modules stay kept: in the trampoline they already sit in the boot layer, and a re-layered automatic
module would read its own twin there (spec, section 4). Do not change that rule in this plan.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B -ntp -pl vauban-classloader install -Dtest=KeptRootsPolicyTest,LaunchSelectionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `KeptRootsPolicyTest` 3/3, and `LaunchSelectionTest` unchanged and green (Review Focus 5).

- [ ] **Step 5: Docs**

- `se.adoc`, subsection "Which modules are re-layered": after its list of kept modules, add:

```asciidoc
[#kept-roots]
A runtime that starts the application through this policy can also name its own root modules
[.tag-new]#NEW#: each of them stays in the parent layer with everything it reads, whatever their names, and
every other explicit module moves to the application layer. The Vidocq runtime names its core and its
extensions this way, so that a brick's library stays in the parent layer once, and the application's own
libraries move (vauban#154). Automatic modules still stay where they are: re-created in the layer, one would
read its own twin in the parent layer.
```

- `whats-new.adoc`, in "Running inside a Vauban layer":

```asciidoc
* **Kept roots in the re-layer policy** — `VaubanLayerFactory.applicationPaths` takes the root modules of a runtime and keeps their whole closure in the parent layer, whatever their names; every other explicit module moves to the application layer. The three-argument form is unchanged (vauban#154). xref:se.adoc#kept-roots[Which modules are re-layered].
```

- [ ] **Step 6: Commit**

```bash
git add vauban-classloader/src/main/java/io/vidocq/vauban/classloader/VaubanLayerFactory.java \
        vauban-classloader/src/test/java/io/vidocq/vauban/classloader/KeptRootsPolicyTest.java \
        docs/en/modules/ROOT/pages/se.adoc docs/en/modules/ROOT/pages/whats-new.adoc
git commit -s -S -m "feat(classloader): keep a runtime's closure in the re-layer policy (#154)"
```

---

### Task 4: Full verification and PR

- [ ] **Step 1: Full build, as CI runs it**

Run: `CI=true ./mvnw -B -ntp install` in a fresh local repository (`-Dmaven.repo.local=<scratch>/m2-vauban154`).
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 2: Render the docs**

Render `se.adoc` and `whats-new.adoc` to HTML, then check:
- the anchors `#multi-release` and `#kept-roots` resolve;
- the `NEW` badges render.

Use the procedure recorded in the workspace memory "Valider de l'AsciiDoc en local": render with `-o -` and
inspect the structure, not the exit code alone.

- [ ] **Step 3: Open the PR on Vidocq/vauban**

Title: `feat(classloader): multi-release jars and kept roots for the application layer (#154)`. The body lists
the three tasks, the tests and their red reasons, the full-build result, and says that automatic modules stay
kept in the trampoline (spec, section 4). It ends with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY
```

Do not merge: the maintainer merges.
