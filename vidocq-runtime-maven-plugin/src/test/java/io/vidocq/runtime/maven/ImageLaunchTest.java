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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("vidocq:jlink and vidocq:jpackage — how the image starts the application")
class ImageLaunchTest {

    private static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";
    private static final String NO_LAYER = "-Dvidocq.app.layer.off=true";

    @Test
    @DisplayName("an application main class is what the launcher runs, with no application layer")
    void anApplicationMainClassIsLaunched() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop.App");
        // The image has one layer: the trampoline runs where the JVM resolved it, in one copy (BUG-20261114-11).
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
        assertEquals(List.of(NO_LAYER), launch.vmOptions());
    }

    @Test
    @DisplayName("a legacy module/class main class keeps its class part")
    void aLegacyModuleSlashClassKeepsItsClass() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop/com.acme.shop.App");
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
    }

    @Test
    @DisplayName("without a main class, the runtime's main starts and is told the application module")
    void withoutAMainClassTheRuntimeMainStarts() {
        var expected = List.of(NO_LAYER, "-Dvidocq.app.module=com.acme.shop");
        for (String none : new String[] {null, "", "  ", "io.vidocq.runtime.core.Vidocq"}) {
            var launch = ImageLaunch.of("com.acme.shop", none);
            assertEquals(RUNTIME_MAIN, launch.entryPoint(), "main class " + none);
            assertEquals(expected, launch.vmOptions(), "main class " + none);
        }
    }

    @Test
    @DisplayName("every staged module is rooted, resource-only ones too, but java.base and the launched module"
            + " (BUG-20261114-05)")
    void everyStagedModuleIsRooted() {
        var roots = List.of("java.sql", "io.vidocq.foy.core", "flow.push", "java.base", "com.acme.shop",
                "io.vidocq.runtime.core", "vaadin.lumo.theme");
        assertEquals("--add-modules=flow.push,io.vidocq.foy.core,io.vidocq.runtime.core,java.sql,vaadin.lumo.theme",
                ImageLaunch.of("com.acme.shop", "com.acme.shop.App", roots).vmOptions().getFirst());
        assertEquals("--add-modules=com.acme.shop,flow.push,io.vidocq.foy.core,java.sql,vaadin.lumo.theme",
                ImageLaunch.of("com.acme.shop", null, roots).vmOptions().getFirst());
    }

    @Test
    @DisplayName("no main module is a failure that names the property to set")
    void noMainModuleNamesTheProperty() {
        var failure = assertThrows(IllegalArgumentException.class, () -> ImageLaunch.of(" ", null));
        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
    }

    @Test
    @DisplayName("the runtime's module as main module is a failure, with or without a main class")
    void theRuntimeModuleIsNotTheApplication() {
        for (String mainClass : new String[] {"io.vidocq.runtime.core.Vidocq", "com.acme.shop.App"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ImageLaunch.of("io.vidocq.runtime.core", mainClass));
            assertTrue(failure.getMessage().contains("the application's own module"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("jlink bakes the options into the image, so that every launcher of the image starts the same way")
    void jlinkBakesTheOptionsIntoTheImage() {
        assertEquals(List.of("--launcher", "shop=com.acme.shop/com.acme.shop.App",
                        // One argument: jlink refuses a separate value that starts with "--".
                        "--add-options=--add-modules=flow.push,io.vidocq.foy.core " + NO_LAYER),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", "com.acme.shop.App",
                        List.of("io.vidocq.foy.core", "flow.push"))));
    }

    @Test
    @DisplayName("jpackage launches the same entry point, and passes the options for an image jlink did not build")
    void jpackageLaunchesTheSameEntryPoint() {
        assertEquals(List.of("--module", RUNTIME_MAIN,
                        "--java-options", NO_LAYER,
                        "--java-options", "-Dvidocq.app.module=com.acme.shop"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", null)));
    }
}
