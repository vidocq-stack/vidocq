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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application layer the core publishes to the extensions, and why they need it: its loader serves a file of
 * the application by name, but lists none of its directories (vidocq#96).
 */
class InstalledApplicationLayerTest {

    @TempDir
    Path dir;

    @Test
    void noLayerIsPublishedWhileNoneIsInstalled() {
        assertTrue(new InstalledApplicationLayer().layer().isEmpty());
        assertTrue(ApplicationLayer.current().isEmpty());
    }

    @Test
    void theInstalledLayerIsPublishedUntilADevReloadTearsItDown() throws Exception {
        Path app = Files.createDirectories(dir.resolve("acme.app"));
        Files.write(app.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), module -> module.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
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
            assertEquals(List.of(), Collections.list(loader.getResources("db/migration")),
                    "but lists no directory: what Flyway's own scanner relies on");
        } finally {
            VidocqAppLayer.resetForReload();
            if (previous == null) {
                System.clearProperty(VidocqAppLayer.APP_PATH_PROPERTY);
            } else {
                System.setProperty(VidocqAppLayer.APP_PATH_PROPERTY, previous);
            }
        }
        assertTrue(ApplicationLayer.current().isEmpty(), "a reload withdraws the layer it tears down");
    }
}
