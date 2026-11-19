/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.maven;

import io.vidocq.vauban.weaver.ProxyLinkWeaver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("vidocq:jlink — the staged copy of a library gains the client proxy the application layer would place")
class StagedProxiesTest {

    private static final String GADGET = "acme.gadget.Gadget";
    private static final String PROXY_ENTRY = "acme/gadget/Gadget_ClientProxy.class";
    private static final String PLACED_ENTRY = "META-INF/vauban/placed/" + PROXY_ENTRY;
    private static final String PROVIDER_SERVICE = "io.vidocq.vauban.api.VaubanComponentProvider";
    private static final String VERSIONED = "META-INF/versions/";

    /** A class a proxy outside its package cannot serve: a package-private member a subclass may override. */
    private static final String OPEN_CLASS = """
            public class Gadget {
              public String name() { return "gadget"; }
              String label(String suffix) { return suffix; }
            }
            """;

    /** The body of a class whose only constructor refuses the default values a proxy would pass, and counts. */
    private static final String STRICT_BODY = """
              public static int built;
              private final String name;
              public Gadget(String name) { this.name = java.util.Objects.requireNonNull(name); built++; }
              public String name() { return name; }
              String label(String suffix) { return name + suffix; }
            """;

    /** A library class with no constructor a proxy may run (BUG-20261118-91). */
    private static final String STRICT_CLASS = "public class Gadget {\n" + STRICT_BODY + "}\n";

    /** A superclass whose only constructor refuses {@code null}, and counts. */
    private static final String STRICT_BASE = """
            public class Base {
              public static int built;
              public Base(String name) { java.util.Objects.requireNonNull(name); built++; }
              String label(String suffix) { return suffix; }
            }
            """;

    @TempDir
    Path dir;

    @Test
    @DisplayName("a type of the placement manifest gets its proxy and a provider in the copy of its library")
    void aListedTypeGetsItsProxyInTheCopyOfItsLibrary() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, OPEN_CLASS);
        application(stage, "app.jar", "acme.app", "# one FQN per line", GADGET);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> "acme:" + jar);

        assertEquals(List.of(new StagedProxies.Placed(GADGET, "gadget.jar")), outcome.placed());
        assertEquals(List.of(), outcome.warnings());
        assertTrue(entries(library).contains(PROXY_ENTRY), () -> "no proxy in " + entries(library));
        assertTheLibraryProvidesItsProxies(stage);
        assertNoWorkDirectoryIsLeft();
    }

    @Test
    @DisplayName("a multi-release library whose descriptor is a versioned one provides its proxies too")
    void aVersionedDescriptorProvidesTheProxiesToo() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path classes = compile("acme.gadget", "exports acme.gadget;", Map.of("Gadget", OPEN_CLASS));
        jar(stage.resolve("gadget.jar"), classes,
                entry -> entry.equals("module-info.class") ? VERSIONED + "9/module-info.class" : entry);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(new StagedProxies.Placed(GADGET, "gadget.jar")), outcome.placed());
        assertTheLibraryProvidesItsProxies(stage);
    }

    @Test
    @DisplayName("a class that refers to a type the image does not hold stays listed, and the others get their proxy")
    void aClassThatRefersToAnAbsentTypeStaysListed() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        library(stage, OPEN_CLASS);
        // Widget takes a type of an optional dependency, which the image does not hold: reflection cannot read it.
        Path classes = compile("acme.widget", "exports acme.widget;", Map.of(
                "Widget", """
                        public class Widget {
                          String label(String s) { return s; }
                          public void meter(Meter meter) { }
                        }
                        """,
                "Meter", "public class Meter { }\n"));
        Path widget = jar(stage.resolve("widget.jar"), classes,
                entry -> entry.endsWith("/Meter.class") ? null : entry);
        byte[] before = Files.readAllBytes(widget);
        Path application = application(stage, "app.jar", "acme.app", GADGET, "acme.widget.Widget");

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(new StagedProxies.Placed(GADGET, "gadget.jar")), outcome.placed());
        assertEquals(1, outcome.warnings().size(), outcome.warnings()::toString);
        String warning = outcome.warnings().getFirst();
        assertTrue(warning.startsWith("acme.widget.Widget: widget.jar cannot be read ("), warning);
        assertTrue(warning.contains("acme/widget/Meter"), warning);
        assertEquals(List.of("acme.widget.Widget"), manifest(application));
        assertArrayEquals(before, Files.readAllBytes(widget), "the library is left as it was staged");
        assertNoWorkDirectoryIsLeft();
    }

    @Test
    @DisplayName("the proxy is a class of the library's package that extends the produced type")
    void theProxyExtendsTheTypeFromItsOwnPackage() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, OPEN_CLASS);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.place(stage, jar -> jar);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {library.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> type = Class.forName(GADGET, false, loader);
            Class<?> proxy = Class.forName(GADGET + "_ClientProxy", false, loader);
            assertEquals(type, proxy.getSuperclass());
            assertEquals(type.getPackageName(), proxy.getPackageName());
            // The package-private member is overridden: that is what only a class of the package can do.
            assertEquals(proxy, proxy.getDeclaredMethod("label", String.class).getDeclaringClass());
        }
    }

    @Test
    @DisplayName("the staged archive that listed the type no longer asks for a placement at the start")
    void theArchiveNoLongerListsThePlacedType() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        library(stage, OPEN_CLASS);
        Path application = application(stage, "app.jar", "acme.app", "# one FQN per line", GADGET);
        Path other = application(stage, "other.jar", "acme.other", GADGET, "acme.elsewhere.Widget");

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(new StagedProxies.Placed(GADGET, "gadget.jar")), outcome.placed());
        // Nothing is left to place for app.jar: its manifest and the proxy it shipped as a resource are gone.
        assertFalse(entries(application).contains(StagedProxies.MANIFEST), () -> entries(application).toString());
        assertFalse(entries(application).contains(PLACED_ENTRY), () -> entries(application).toString());
        // other.jar keeps what the build could not place, and only that.
        assertEquals(List.of("acme.elsewhere.Widget"), manifest(other));
        assertEquals(1, outcome.warnings().size(), outcome.warnings()::toString);
        assertTrue(outcome.warnings().getFirst().contains("acme.elsewhere.Widget"), outcome.warnings()::toString);
    }

    @Test
    @DisplayName("a type no proxy can extend stays listed, and the build says why")
    void aTypeThatCannotBeProxiedStaysListed() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, "public final class Gadget {\n  String label(String s) { return s; }\n}\n");
        Path application = application(stage, "app.jar", "acme.app", GADGET);
        byte[] before = Files.readAllBytes(library);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(), outcome.placed());
        assertEquals(1, outcome.warnings().size(), outcome.warnings()::toString);
        assertTrue(outcome.warnings().getFirst().contains(GADGET), outcome.warnings()::toString);
        assertEquals(List.of(GADGET), manifest(application));
        assertArrayEquals(before, Files.readAllBytes(library), "the library is left as it was staged");
    }

    @Test
    @DisplayName("a library that already holds generated Vauban components in the package is left as it was staged")
    void aPackageThatAlreadyHoldsVaubanComponentsIsLeftAlone() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, OPEN_CLASS);
        // A bean archive of its own: the provider of its package is already there, and a second one cannot be added.
        try (var zip = FileSystems.newFileSystem(library)) {
            Files.write(zip.getPath("acme/gadget/_VaubanComponents.class"), new byte[] {1});
        }
        Path application = application(stage, "app.jar", "acme.app", GADGET);
        byte[] before = Files.readAllBytes(library);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(), outcome.placed());
        assertEquals(1, outcome.warnings().size(), outcome.warnings()::toString);
        assertTrue(outcome.warnings().getFirst().startsWith(GADGET + ": gadget.jar already holds"),
                outcome.warnings()::toString);
        assertEquals(List.of(GADGET), manifest(application));
        assertArrayEquals(before, Files.readAllBytes(library));
    }

    @Test
    @DisplayName("a stage whose archives list nothing is left untouched")
    void aStageWithoutAPlacementManifestIsLeftUntouched() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, OPEN_CLASS);
        Path application = application(stage, "app.jar", "acme.app");
        byte[] libraryBefore = Files.readAllBytes(library);
        byte[] applicationBefore = Files.readAllBytes(application);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(new StagedProxies.Outcome(List.of(), List.of()), outcome);
        assertArrayEquals(libraryBefore, Files.readAllBytes(library));
        assertArrayEquals(applicationBefore, Files.readAllBytes(application));
        assertNoWorkDirectoryIsLeft();
    }

    @Test
    @DisplayName("the proxy runs no constructor of the library, as the proxy the application layer places")
    void theProxyRunsNoConstructorOfTheLibrary() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, STRICT_CLASS);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.Outcome outcome = StagedProxies.place(stage, jar -> jar);

        assertEquals(List.of(new StagedProxies.Placed(GADGET, "gadget.jar")), outcome.placed());
        try (URLClassLoader loader = loader(library)) {
            Class<?> type = Class.forName(GADGET, false, loader);
            assertMarker(type);
            assertEquals(0, newProxy(loader), "the proxy ran a constructor of the library");
        }
    }

    @Test
    @DisplayName("a superclass of the library with no constructor free of side effects gets the marker too")
    void aSuperclassOfTheLibraryGetsTheMarkerToo() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = jar(stage.resolve("gadget.jar"), compile("acme.gadget", "exports acme.gadget;", Map.of(
                "Base", STRICT_BASE,
                "Gadget", "public class Gadget extends Base {\n" + STRICT_BODY.replace("this.name =", "super(name);"
                        + " this.name =") + "}\n")), entry -> entry);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.place(stage, jar -> jar);

        try (URLClassLoader loader = loader(library)) {
            assertMarker(Class.forName("acme.gadget.Base", false, loader));
            assertMarker(Class.forName(GADGET, false, loader));
            assertEquals(0, newProxy(loader), "the proxy ran a constructor of the library");
            assertEquals(0, Class.forName("acme.gadget.Base", false, loader).getDeclaredField("built").getInt(null));
        }
    }

    @Test
    @DisplayName("a superclass in another staged jar gets the marker in its own jar")
    void aSuperclassInAnotherStagedJarGetsTheMarkerInItsJar() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path baseClasses = compile("acme.base", "exports acme.base;",
                Map.of("Base", STRICT_BASE.replace("String label(", "protected String tag(")));
        Path base = jar(stage.resolve("base.jar"), baseClasses, entry -> entry);
        Path library = jar(stage.resolve("gadget.jar"), compile("acme.gadget",
                "requires transitive acme.base; exports acme.gadget;", Map.of("Gadget",
                        "public class Gadget extends acme.base.Base {\n" + STRICT_BODY.replace("this.name =",
                                "super(name); this.name =") + "}\n"), List.of(baseClasses)), entry -> entry);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.place(stage, jar -> jar);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {base.toUri().toURL(), library.toUri().toURL()},
                getClass().getClassLoader())) {
            assertMarker(Class.forName("acme.base.Base", false, loader));
            assertEquals(0, newProxy(loader), "the proxy ran a constructor of the library");
        }
    }

    @Test
    @DisplayName("a library class that extends a JDK class with a no-arg constructor chains to it")
    void aJdkSuperclassWithANoArgConstructorEndsTheChain() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, """
                public class Gadget extends java.io.OutputStream {
                  public Gadget(String name) { java.util.Objects.requireNonNull(name); }
                  @Override public void write(int b) { }
                  String label(String suffix) { return suffix; }
                }
                """);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.place(stage, jar -> jar);

        try (URLClassLoader loader = loader(library)) {
            assertMarker(Class.forName(GADGET, false, loader));
            newProxy(loader);
        }
    }

    @Test
    @DisplayName("a chain that must run a constructor of the JDK with made-up arguments fails the build")
    void aChainThatCannotBeFreeOfSideEffectsFailsTheBuild() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        library(stage, """
                public class Gadget extends java.io.FilterOutputStream {
                  public Gadget(java.io.OutputStream out) { super(java.util.Objects.requireNonNull(out)); }
                  String label(String suffix) { return suffix; }
                }
                """);
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxyLinks.Refused refused = org.junit.jupiter.api.Assertions.assertThrows(
                StagedProxyLinks.Refused.class, () -> StagedProxies.place(stage, jar -> jar));

        String message = refused.getMessage();
        assertTrue(message.contains(GADGET) && message.contains("java.io.FilterOutputStream"), message);
        assertTrue(message.contains("interface") && message.contains("@Dependent")
                && message.contains("@Singleton"), message);
    }

    @Test
    @DisplayName("a Serializable class without serialVersionUID keeps the one the JVM computed before the weaving")
    void aSerializableClassKeepsItsSerialVersionUid() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path library = library(stage, """
                public class Gadget implements java.io.Serializable, Comparable<Gadget> {
                  static final java.util.List<String> NAMES = new java.util.ArrayList<>();
                  private static int count;
                  protected transient int cache;
                  public volatile long stamp;
                  private final String name;
                  public Gadget(String name) { this.name = java.util.Objects.requireNonNull(name); count++; }
                  Gadget(String name, int cache) { this(name); this.cache = cache; }
                  private Gadget() { this("x"); }
                  public String name() { return name; }
                  String label(String suffix) { return name + suffix; }
                  protected synchronized void touch(int[] values, Object... more) { }
                  @Override public int compareTo(Gadget other) { return name.compareTo(other.name); }
                  public static final class Part implements java.io.Serializable { }
                }
                """);
        long before;
        try (URLClassLoader original = loader(Files.copy(library, dir.resolve("original.jar")))) {
            before = java.io.ObjectStreamClass.lookup(Class.forName(GADGET, false, original)).getSerialVersionUID();
        }
        application(stage, "app.jar", "acme.app", GADGET);

        StagedProxies.place(stage, jar -> jar);

        try (URLClassLoader loader = loader(library)) {
            Class<?> woven = Class.forName(GADGET, false, loader);
            assertMarker(woven);
            assertEquals(before, java.io.ObjectStreamClass.lookup(woven).getSerialVersionUID());
        }
    }

    @Test
    @DisplayName("the default serialVersionUID read from the bytes is the one the JVM computes")
    void theDefaultSerialVersionUidIsTheJvmOne() throws Exception {
        Path classes = compile("acme.gadget", "exports acme.gadget;", Map.of(
                "Gadget", """
                        public abstract class Gadget implements java.io.Serializable, Cloneable {
                          static { System.getProperty("x"); }
                          private static final long LIMIT = 3;
                          private transient Object ignored;
                          int[][] grid;
                          public abstract String name();
                          private void hidden() { }
                          protected static Gadget of(String n) { return null; }
                          public interface Kind extends java.io.Serializable { String id(); }
                          static class Nested implements java.io.Serializable { private int x; }
                          public interface Empty extends java.io.Serializable { }
                        }
                        """));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            for (String name : List.of(GADGET, GADGET + "$Kind", GADGET + "$Nested", GADGET + "$Empty")) {
                byte[] bytes = Files.readAllBytes(classes.resolve(name.replace('.', '/') + ".class"));
                assertEquals(java.io.ObjectStreamClass.lookup(Class.forName(name, false, loader))
                        .getSerialVersionUID(), ProxyLinkWeaver.defaultSerialVersionUid(bytes), name);
            }
        }
    }

    /** {@code type} declares the {@code protected synthetic (ProxyLink)} constructor the proxy chains to. */
    private static void assertMarker(Class<?> type) {
        var markers = Stream.of(type.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 1
                        && constructor.getParameterTypes()[0] == io.vidocq.vauban.api.ProxyLink.class).toList();
        assertEquals(1, markers.size(), () -> type + " has no (ProxyLink) constructor");
        assertTrue(java.lang.reflect.Modifier.isProtected(markers.getFirst().getModifiers()), type::toString);
        assertTrue(markers.getFirst().isSynthetic(), type::toString);
    }

    /** Creates a client proxy of {@code Gadget}; returns how many times a constructor of {@code Gadget} ran. */
    private static int newProxy(ClassLoader loader) throws Exception {
        Class<?> proxy = Class.forName(GADGET + "_ClientProxy", false, loader);
        var constructor = Stream.of(proxy.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 1)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        constructor.newInstance((java.util.function.Supplier<Object>) () -> null);
        Class<?> type = Class.forName(GADGET, false, loader);
        return Stream.of(type.getDeclaredFields()).anyMatch(field -> field.getName().equals("built"))
                ? type.getDeclaredField("built").getInt(null) : 0;
    }

    private URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    }

    /** The module {@code acme.gadget} of {@code stage} provides the generated components, and reads their API. */
    private static void assertTheLibraryProvidesItsProxies(Path stage) {
        ModuleDescriptor descriptor = ModuleFinder.of(stage).find("acme.gadget").orElseThrow().descriptor();
        assertFalse(descriptor.isAutomatic(), "the library is an explicit module");
        assertEquals(List.of("acme.gadget._VaubanComponents"), descriptor.provides().stream()
                .filter(provides -> provides.service().equals(PROVIDER_SERVICE))
                .flatMap(provides -> provides.providers().stream()).toList());
        assertTrue(descriptor.requires().stream().anyMatch(r -> r.name().equals("io.vidocq.vauban.api")),
                () -> "the module reads Vauban's API: " + descriptor.requires());
    }

    /** The enhanced copies are written next to the stage before they replace the staged jars. */
    private void assertNoWorkDirectoryIsLeft() throws IOException {
        try (Stream<Path> siblings = Files.list(dir)) {
            assertEquals(List.of("stage"), siblings.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("stage")).toList());
        }
    }

    /** The types the placement manifest of {@code jar} still lists; empty when the jar has no manifest. */
    private static List<String> manifest(Path jar) throws IOException {
        try (var zip = FileSystems.newFileSystem(jar)) {
            Path manifest = zip.getPath(StagedProxies.MANIFEST);
            if (!Files.exists(manifest)) {
                return List.of();
            }
            return Files.readAllLines(manifest).stream().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        }
    }

    private static List<String> entries(Path jar) {
        try (var zip = FileSystems.newFileSystem(jar); Stream<Path> files = Files.walk(zip.getPath("/"))) {
            return files.filter(Files::isRegularFile).map(file -> file.toString().substring(1)).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** {@code stage/gadget.jar}: the module {@code acme.gadget}, which exports the package of {@code Gadget}. */
    private Path library(Path stage, String gadget) throws Exception {
        Path classes = compile("acme.gadget", "exports acme.gadget;", Map.of("Gadget", gadget));
        return jar(stage.resolve("gadget.jar"), classes, entry -> entry);
    }

    /**
     * {@code stage/<name>}: a bean archive, the module {@code module}, whose placement manifest holds {@code lines}
     * and which ships the proxy of {@code Gadget} as a resource. No manifest at all without lines.
     */
    private Path application(Path stage, String name, String module, String... lines) throws Exception {
        Path classes = compile(module, "", Map.of());
        if (lines.length > 0) {
            Path manifest = classes.resolve(StagedProxies.MANIFEST);
            Files.createDirectories(manifest.getParent());
            Files.write(manifest, List.of(lines));
            Path placed = classes.resolve(PLACED_ENTRY);
            Files.createDirectories(placed.getParent());
            Files.write(placed, "the proxy the processor shipped".getBytes(StandardCharsets.UTF_8));
        }
        return jar(stage.resolve(name), classes, entry -> entry);
    }

    /**
     * Compiles the module {@code name} with {@code directives} and {@code classes}, the body of each class by its
     * simple name, in the package named as the module.
     */
    private Path compile(String name, String directives, Map<String, String> classes) throws IOException {
        return compile(name, directives, classes, List.of());
    }

    /** As {@link #compile(String, String, Map)}, with {@code modulePath} for the modules it requires. */
    private Path compile(String name, String directives, Map<String, String> classes, List<Path> modulePath)
            throws IOException {
        Path src = Files.createDirectories(dir.resolve("src-" + name + "-" + System.nanoTime()));
        Path out = Files.createDirectories(dir.resolve("classes-" + name + "-" + System.nanoTime()));
        Files.writeString(src.resolve("module-info.java"), "module " + name + " { " + directives + " }\n");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), src.resolve("module-info.java").toString()));
        if (!modulePath.isEmpty()) {
            args.addAll(List.of("-p", String.join(java.io.File.pathSeparator,
                    modulePath.stream().map(Path::toString).toList())));
        }
        Path packageDir = Files.createDirectories(src.resolve(name.replace('.', '/')));
        for (Map.Entry<String, String> type : classes.entrySet()) {
            Path file = packageDir.resolve(type.getKey() + ".java");
            Files.writeString(file, "package " + name + ";\n" + type.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                args.toArray(String[]::new)), name);
        return out;
    }

    /**
     * Writes {@code jar} from the files of {@code classes}, each under the name {@code naming} gives it, and left
     * out when it gives none. An entry under {@code META-INF/versions/} makes the jar a multi-release one.
     */
    private static Path jar(Path jar, Path classes, UnaryOperator<String> naming) throws IOException {
        Map<String, Path> entries = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String entry = naming.apply(classes.relativize(file).toString().replace('\\', '/'));
                if (entry != null) {
                    entries.put(entry, file);
                }
            }
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (entries.keySet().stream().anyMatch(entry -> entry.startsWith(VERSIONED))) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Map.Entry<String, Path> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(Files.readAllBytes(entry.getValue()));
                out.closeEntry();
            }
        }
        return jar;
    }
}
