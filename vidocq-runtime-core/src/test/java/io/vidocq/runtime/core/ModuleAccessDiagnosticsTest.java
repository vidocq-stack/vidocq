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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InaccessibleObjectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library that reaches an application package by reflection, while the application's module does not open it,
 * fails with an {@code InaccessibleObjectException} or an {@code IllegalAccessException} the build checks could not
 * foresee (a JSON-B call made by hand, a third-party library). The runtime names the package and the line to add,
 * and under {@code vidocq dev} asks the plugin to write it (BUG-20261031-15).
 */
class ModuleAccessDiagnosticsTest {

    private static final String LOGGER = ModuleAccessDiagnostics.class.getName();

    @TempDir
    Path dir;

    @Test
    void namesThePackageAFieldIsDeniedIn() throws Exception {
        Class<?> book = applicationClass();
        var title = book.getDeclaredField("title");
        var denied = assertThrows(InaccessibleObjectException.class, () -> title.setAccessible(true));
        // as a library reports it: wrapped, as the cause of its own exception
        var failure = new IllegalStateException("cannot bind shop.book.Book", denied);

        var denial = ModuleAccessDiagnostics.find(failure, "shop"::equals).orElseThrow();

        assertEquals("shop", denial.module());
        assertEquals("shop.book", denial.pkg());
        String message = denial.message(false);
        assertTrue(message.contains("opens shop.book;"), message);
        assertTrue(message.contains("module-info.java"), message);
        assertTrue(message.contains("field private java.lang.String shop.book.Book.title"), message);
    }

    @Test
    void namesThePackageAPublicMethodIsDeniedIn() throws Exception {
        Class<?> book = applicationClass();
        var label = book.getMethod("label");
        var denied = assertThrows(IllegalAccessException.class, () -> label.invoke(null));

        var denial = ModuleAccessDiagnostics.find(denied, "shop"::equals).orElseThrow();

        assertEquals("shop.book", denial.pkg());
        assertTrue(denial.message(false).contains("opens shop.book;"));
    }

    @Test
    void saysNothingAboutAModuleOfTheRuntimeOrTheJdk() throws Exception {
        Class<?> book = applicationClass();
        var title = book.getDeclaredField("title");
        var denied = assertThrows(InaccessibleObjectException.class, () -> title.setAccessible(true));
        var value = String.class.getDeclaredField("value");
        var jdk = assertThrows(InaccessibleObjectException.class, () -> value.setAccessible(true));

        assertTrue(ModuleAccessDiagnostics.find(denied, "other.app"::equals).isEmpty());
        assertTrue(ModuleAccessDiagnostics.find(jdk, "shop"::equals).isEmpty());
        assertTrue(ModuleAccessDiagnostics.find(new IllegalStateException("plain"), "shop"::equals).isEmpty());
    }

    @Test
    void aLoggedFailureGetsTheMessageOnceAndTheDevFileThePackage() throws Exception {
        Class<?> book = applicationClass();
        var title = book.getDeclaredField("title");
        var denied = assertThrows(InaccessibleObjectException.class, () -> title.setAccessible(true));
        Path request = dir.resolve("target").resolve(ModuleAccessDiagnostics.OPENS_FILE_NAME);
        var handler = new ModuleAccessDiagnostics("shop"::equals, request);

        try (var records = new LogRecords(LOGGER)) {
            for (int i = 0; i < 2; i++) {
                var logged = new LogRecord(Level.SEVERE, "Cassini dispatch error");
                logged.setThrown(new RuntimeException("dispatch", denied));
                handler.publish(logged);
            }
            List<String> errors = records.messages(LOGGER, Level.SEVERE);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("vidocq dev adds"), errors.getFirst());
        }
        assertEquals(List.of("shop.book"), Files.readAllLines(request));
    }

    @Test
    void outsideDevTheMessageNamesTheLineOnly() throws Exception {
        Class<?> book = applicationClass();
        var title = book.getDeclaredField("title");
        var denied = assertThrows(InaccessibleObjectException.class, () -> title.setAccessible(true));
        var handler = new ModuleAccessDiagnostics("shop"::equals, null);

        try (var records = new LogRecords(LOGGER)) {
            var logged = new LogRecord(Level.SEVERE, "Boot failed");
            logged.setThrown(denied);
            handler.publish(logged);
            String error = records.messages(LOGGER, Level.SEVERE).getFirst();
            assertTrue(error.contains("opens shop.book;"), error);
            assertFalse(error.contains("vidocq dev adds"), error);
        }
    }

    @Test
    void everyBootInstallsTheHandlerOnceOnTheRootLogger() {
        VidocqBootstrap.create().banner(BannerMode.OFF).configure(List.of());
        VidocqBootstrap.create().banner(BannerMode.OFF).configure(List.of());

        long handlers = java.util.Arrays.stream(java.util.logging.Logger.getLogger("").getHandlers())
                .filter(ModuleAccessDiagnostics.class::isInstance)
                .count();
        assertEquals(1, handlers);
    }

    @Test
    void aMessageItCannotReadIsNoDenial() {
        for (String message : List.of("Unable to make x", "Unable to make x accessible: module m does not",
                "a cannot access b (in module m", "a cannot access b (in module m) because module m does not",
                "Unable to make f accessible: module two words does not \"opens p\" to module r")) {
            assertTrue(ModuleAccessDiagnostics.parse(message).isEmpty(), message);
        }
        var noReader = ModuleAccessDiagnostics.parse(
                "x cannot access class a.B (in module app) because module app does not export a").orElseThrow();
        assertEquals("a", noReader.pkg());
        assertEquals("a class on the class path", noReader.reader());
    }

    @Test
    void theRuntimeTheJdkAndJakartaAreNeverTheApplication() {
        for (String name : List.of("java.base", "jdk.unsupported", "jakarta.json.bind", "io.vidocq.runtime.core")) {
            assertTrue(ModuleAccessDiagnostics.platformModule(name), name);
        }
        assertFalse(ModuleAccessDiagnostics.platformModule("com.example.shop"));
        // no application layer and no Vidocq.run in this JVM
        assertFalse(ModuleAccessDiagnostics.isApplicationModule("com.example.shop"));
    }

    @Test
    void aRequestFileItCannotWriteLeavesTheMessageOutsideDev() throws Exception {
        Class<?> book = applicationClass();
        var title = book.getDeclaredField("title");
        var denied = assertThrows(InaccessibleObjectException.class, () -> title.setAccessible(true));
        Path notADirectory = Files.writeString(dir.resolve("target"), "a file");
        var handler = new ModuleAccessDiagnostics("shop"::equals, notADirectory.resolve("request"));

        try (var records = new LogRecords(LOGGER)) {
            var logged = new LogRecord(Level.SEVERE, "Boot failed");
            logged.setThrown(denied);
            handler.publish(logged);
            handler.publish(new LogRecord(Level.INFO, "no failure"));
            handler.flush();
            handler.close();
            String error = records.messages(LOGGER, Level.SEVERE).getFirst();
            assertFalse(error.contains("vidocq dev adds"), error);
        }
    }

    /**
     * Class {@code shop.book.Book} of a module {@code shop} that neither exports nor opens {@code shop.book}: a private
     * field {@code title} and a public static method {@code label()}.
     */
    private Class<?> applicationClass() throws IOException, ClassNotFoundException {
        Path root = dir.resolve("shop");
        Files.createDirectories(root.resolve("shop/book"));
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("shop"), mb -> mb.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        Files.write(root.resolve("shop/book/Book.class"), ClassFile.of().build(ClassDesc.of("shop.book.Book"), cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withField("title", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE)
                .withMethodBody("label", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.ldc("Vidocq").areturn())));
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(ModuleFinder.of(root), ModuleFinder.of(), Set.of("shop"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        return layer.findLoader("shop").loadClass("shop.book.Book");
    }
}
