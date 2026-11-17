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
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanClassLoader;
import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-09: a class of the application's directories is loaded when the layer starts, so that it no longer has
 * to be in the output when the application first uses it.
 */
class ClassPreloadTest {

    private static final String INITIALIZED = "io.vidocq.runtime.core.ClassPreloadTest.initialized";

    @Test
    void aClassLoadedAheadNoLongerNeedsItsFile(@TempDir Path dir) throws Exception {
        Path later = emptyClass(dir, "acme.app.Later");
        try (URLClassLoader loader = loaderOf(dir)) {
            ClassPreload.Outcome outcome = ClassPreload.load(List.of(dir), loader);
            Files.delete(later);

            assertEquals(new ClassPreload.Outcome(1, List.of()), outcome);
            assertEquals("acme.app.Later", Class.forName("acme.app.Later", false, loader).getName());
        }
    }

    /** The control: without the loading, the class that left the output is the failure of BUG-20261117-09. */
    @Test
    void aClassNotLoadedAheadIsMissedOnceItsFileIsGone(@TempDir Path dir) throws Exception {
        Path later = emptyClass(dir, "acme.app.Later");
        try (URLClassLoader loader = loaderOf(dir)) {
            Files.delete(later);

            assertThrows(ClassNotFoundException.class, () -> Class.forName("acme.app.Later", false, loader));
        }
    }

    @Test
    void theClassesOfALayerAreLoadedThroughItsLoader(@TempDir Path dir) throws Exception {
        Path module = TestModules.explodedModule(dir, "acme.app", List.of(), "acme.app.Main");
        Path later = emptyClass(module, "acme.app.Later");
        var layer = VaubanLayerFactory.createAppLayer(List.of(module), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());
        try (var loader = layer.loader()) {
            ClassPreload.Outcome outcome = ClassPreload.load(List.of(module), loader);
            Files.delete(later);

            assertEquals(new ClassPreload.Outcome(2, List.of()), outcome);
            Class<?> loaded = Class.forName("acme.app.Later", false, loader);
            assertEquals("acme.app", loaded.getModule().getName());
            assertSame(loader, loaded.getClassLoader());
        }
    }

    @Test
    void noClassIsInitialized(@TempDir Path dir) throws Exception {
        write(dir, "acme.app.Eager", ClassFile.of().build(ClassDesc.of("acme.app.Eager"), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC,
                        code -> code.ldc(INITIALIZED).ldc("yes")
                                .invokestatic(ClassDesc.of("java.lang.System"), "setProperty", MethodTypeDesc.of(
                                        ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                                .pop().return_())));
        try (URLClassLoader loader = loaderOf(dir)) {
            ClassPreload.load(List.of(dir), loader);
            assertNull(System.getProperty(INITIALIZED), "loading ahead ran a static initializer");

            // The initializer is a real one: using the class runs it.
            Class.forName("acme.app.Eager", true, loader);
            assertEquals("yes", System.getProperty(INITIALIZED));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void aClassThatCannotBeLoadedIsNamedAndTheOthersAreLoaded(@TempDir Path dir) throws Exception {
        emptyClass(dir, "acme.app.Fine");
        write(dir, "acme.app.Broken", new byte[] {1, 2, 3});
        write(dir, "acme.app.Orphan", ClassFile.of().build(ClassDesc.of("acme.app.Orphan"), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.of("absent.Base"))));
        try (URLClassLoader loader = loaderOf(dir)) {
            ClassPreload.Outcome outcome = ClassPreload.load(List.of(dir), loader);

            assertEquals(new ClassPreload.Outcome(1, List.of("acme.app.Broken", "acme.app.Orphan")), outcome);
            assertEquals("acme.app.Fine", Class.forName("acme.app.Fine", false, loader).getName());
        }
    }

    @Test
    void aJarOrADirectoryThatIsNotThereHoldsNothingToLoad(@TempDir Path dir) throws Exception {
        Path jar = TestModules.automaticJar(dir, "acme-lib-1.0.jar", "acme.lib.Lib");
        Path classes = Files.createDirectory(dir.resolve("classes"));
        emptyClass(classes, "acme.app.Main");
        try (URLClassLoader loader = loaderOf(classes)) {
            ClassPreload.Outcome outcome = ClassPreload.load(List.of(jar, dir.resolve("absent"), classes), loader);

            assertEquals(new ClassPreload.Outcome(1, List.of()), outcome);
        }
        assertEquals(List.of(), ClassPreload.classNames(jar));
        assertEquals(List.of(), ClassPreload.classNames(dir.resolve("absent")));
    }

    @Test
    void theNamesAreSortedSoThatAClassComesBeforeWhatIsGeneratedFromIt(@TempDir Path dir) throws Exception {
        emptyClass(dir, "acme.b.Zeta");
        emptyClass(dir, "acme.app.Bean_ClientProxy");
        emptyClass(dir, "acme.app.Bean$Inner");
        emptyClass(dir, "acme.app.Bean");
        emptyClass(dir, "Unnamed");

        assertEquals(List.of("Unnamed", "acme.app.Bean", "acme.app.Bean$Inner", "acme.app.Bean_ClientProxy",
                "acme.b.Zeta"), ClassPreload.classNames(dir));
    }

    @Test
    void theModuleDescriptorAndWhatMetaInfHoldsAreNotClassesOfTheApplication(@TempDir Path dir) throws Exception {
        Path module = TestModules.explodedModule(dir, "acme.app", List.of(), "acme.app.Main");
        emptyClass(module.resolve("META-INF/versions/25"), "acme.app.Newer");
        Files.writeString(module.resolve("acme/app/notes.txt"), "not a class");
        Files.createDirectories(module.resolve("META-INF/acme"));
        Files.writeString(module.resolve("META-INF/acme/index.html"), "a page");

        assertEquals(List.of("acme.app.Main"), ClassPreload.classNames(module));
    }

    @Test
    void aPlacedClientProxyIsNamedByItsOwnNameAfterTheClasses(@TempDir Path dir) throws Exception {
        emptyClass(dir, "acme.app.Producers");
        emptyClass(dir.resolve("META-INF/vauban/placed"), "acme.lib.Client_ClientProxy");
        emptyClass(dir.resolve("META-INF/vauban/placed"), "acme.lib.Account_ClientProxy");

        assertEquals(List.of("acme.app.Producers", "acme.lib.Account_ClientProxy", "acme.lib.Client_ClientProxy"),
                ClassPreload.classNames(dir));
    }

    /**
     * The layer defines a placed proxy in the package of its produced type, so it places only the proxy of a type it
     * defines itself: that one is loaded ahead, the proxy of a type that lives elsewhere is left to its first use.
     */
    @Test
    void thePlacedClientProxyOfATypeOfTheLayerIsLoadedThroughItsLoader(@TempDir Path dir) throws Exception {
        Path module = TestModules.explodedModule(dir, "acme.app", List.of(), "acme.app.Main");
        emptyClass(module, "acme.app.Account");
        Path placed = emptyClass(module.resolve(VaubanClassLoader.PLACED_PREFIX), "acme.app.Account_ClientProxy");
        emptyClass(module.resolve(VaubanClassLoader.PLACED_PREFIX), "acme.lib.Client_ClientProxy");
        Files.writeString(module.resolve(VaubanClassLoader.PLACEMENT_MANIFEST), "acme.app.Account\nacme.lib.Client\n");
        var layer = VaubanLayerFactory.createAppLayer(List.of(module), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());
        try (var loader = layer.loader()) {
            ClassPreload.Outcome outcome = ClassPreload.load(List.of(module), loader);
            Files.delete(placed);

            assertEquals(new ClassPreload.Outcome(3, List.of("acme.lib.Client_ClientProxy")), outcome);
            Class<?> proxy = Class.forName("acme.app.Account_ClientProxy", false, loader);
            assertEquals("acme.app", proxy.getModule().getName());
            assertSame(loader, proxy.getClassLoader());
        }
    }

    @Test
    void aLinkToADirectoryIsNotFollowed(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectory(dir.resolve("classes"));
        emptyClass(classes, "acme.app.Main");
        Files.createSymbolicLink(classes.resolve("acme/loop"), classes);

        assertEquals(List.of("acme.app.Main"), ClassPreload.classNames(classes));
    }

    @Test
    void theLineCountsTheClassesAndNamesTheFirstOnesLeftToTheirFirstUse() {
        assertEquals("Application layer: 71 classes of the application's directories loaded ahead of their first use",
                new ClassPreload.Outcome(71, List.of()).line());
        assertEquals("Application layer: 1 class of the application's directories loaded ahead of its first use;"
                + " 2 could not be loaded now and are left to their first use: acme.A, acme.B",
                new ClassPreload.Outcome(1, List.of("acme.A", "acme.B")).line());
        assertEquals("Application layer: 0 classes of the application's directories loaded ahead of their first use;"
                + " 7 could not be loaded now and are left to their first use: a.A, a.B, a.C, a.D, a.E and 2 more",
                new ClassPreload.Outcome(0, List.of("a.A", "a.B", "a.C", "a.D", "a.E", "a.F", "a.G")).line());
        assertEquals("Application layer: 3 classes of the application's directories loaded ahead of their first use;"
                + " 1 could not be loaded now and is left to its first use: acme.A",
                new ClassPreload.Outcome(3, List.of("acme.A")).line());
    }

    /** The name {@code vidocq:dev} gives the child: {@code VidocqDevMojoTest} holds the other end. */
    @Test
    void thePropertyIsTheOneVidocqDevGivesTheChild() {
        assertEquals("vidocq.dev.preloadClasses", ClassPreload.PROPERTY);
    }

    @Test
    void theLoadingIsOnUnlessThePropertySaysFalse() {
        assertTrue(ClassPreload.on(null));
        assertTrue(ClassPreload.on(""));
        assertTrue(ClassPreload.on("true"));
        assertTrue(ClassPreload.on("anything"));
        assertFalse(ClassPreload.on("false"));
        assertFalse(ClassPreload.on(" FALSE "));
    }

    private static URLClassLoader loaderOf(Path directory) throws IOException {
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, null);
    }

    /** Writes a public class without a member under {@code root}; returns its file. */
    private static Path emptyClass(Path root, String name) throws IOException {
        return write(root, name, ClassFile.of().build(ClassDesc.of(name),
                builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
    }

    private static Path write(Path root, String name, byte[] bytes) throws IOException {
        Path file = root.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }
}
