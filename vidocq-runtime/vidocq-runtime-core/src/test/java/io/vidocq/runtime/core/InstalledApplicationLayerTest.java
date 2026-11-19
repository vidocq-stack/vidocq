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

import io.vidocq.runtime.spi.ApplicationLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application layer the core publishes to the extensions, and why they need it: its loader serves a file of
 * the application by name, but may list none of its directories (vidocq#96). Vauban lists those of a plain jar or
 * a directory since Vidocq/vauban#178, never those of an archive a byte-source plugin reads, such as an sjar.
 */
class InstalledApplicationLayerTest {

    @TempDir
    Path dir;

    @Test
    void noLayerIsPublishedWhileNoneIsInstalled() {
        assertTrue(new InstalledApplicationLayer().layer().isEmpty());
        assertTrue(ApplicationLayer.current().isEmpty());
    }

    /**
     * An extension that declares something for the application by default, such as the persistence unit of its
     * entities, needs the module the banner names, which only the core resolves.
     */
    @Test
    void theApplicationModuleIsTheOneTheRuntimeNames() {
        String previous = System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "java.logging");
        try {
            assertEquals("java.logging", ApplicationLayer.applicationModule().orElseThrow().getName());
            assertEquals(Vidocq.applicationModule(), new InstalledApplicationLayer().module().orElseThrow());
        } finally {
            restore(VidocqAppLayer.APP_MODULE_PROPERTY, previous);
        }
    }

    /**
     * {@code @VidocqTest} names the application module and hands its classes through {@value
     * VidocqAppLayer#APP_PATH_PROPERTY}, as {@code vidocq:run} does: the module is in the application layer, and in no
     * other (BUG-20261114-07).
     */
    @Test
    void theNamedModuleIsTheOneOfTheApplicationLayer() throws Exception {
        Path app = application();
        String previousPath = System.getProperty(VidocqAppLayer.APP_PATH_PROPERTY);
        String previousModule = System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "acme.app");
        System.setProperty(VidocqAppLayer.APP_PATH_PROPERTY, app.toString());
        try {
            assertTrue(VidocqAppLayer.installIfConfigured());

            Module module = ApplicationLayer.current().orElseThrow().findModule("acme.app").orElseThrow();
            assertEquals(module, Vidocq.applicationModule());
            assertEquals(module, ApplicationLayer.applicationModule().orElseThrow());
        } finally {
            VidocqAppLayer.resetForReload();
            restore(VidocqAppLayer.APP_PATH_PROPERTY, previousPath);
            restore(VidocqAppLayer.APP_MODULE_PROPERTY, previousModule);
        }
    }

    @Test
    void theInstalledLayerIsPublishedUntilADevReloadTearsItDown() throws Exception {
        Path app = application();
        Path script = app.resolve("db/migration/V1__create.sql");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "CREATE TABLE a (id INT);");
        String previous = System.getProperty(VidocqAppLayer.APP_PATH_PROPERTY);
        System.setProperty(VidocqAppLayer.APP_PATH_PROPERTY, app.toString());
        try {
            assertTrue(VidocqAppLayer.installIfConfigured());

            ModuleLayer layer = ApplicationLayer.current().orElseThrow();
            assertTrue(layer.findModule("acme.app").isPresent());
            assertEquals(List.of("db/migration/V1__create.sql"), ApplicationLayer.list(layer, "db/migration"));
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            assertNotNull(loader.getResource("db/migration/V1__create.sql"), "the layer's loader serves a file by name");
        } finally {
            VidocqAppLayer.resetForReload();
            restore(VidocqAppLayer.APP_PATH_PROPERTY, previous);
        }
        assertTrue(ApplicationLayer.current().isEmpty(), "a reload withdraws the layer it tears down");
    }

    /** An exploded named module {@code acme.app}, its descriptor alone. */
    private Path application() throws java.io.IOException {
        Path app = Files.createDirectories(dir.resolve("acme.app"));
        Files.write(app.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), module -> module.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        return app;
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
