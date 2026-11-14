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

import io.vidocq.runtime.core.LayerSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("vidocq:jlink and vidocq:jpackage — how the image starts the application")
class ImageLaunchTest {

    private static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    @Test
    @DisplayName("an application main class runs inside the layer: the runtime's main starts it, never the launcher")
    void anApplicationMainClassRunsInsideTheLayer() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL);
        // A launcher running com.acme.shop/com.acme.shop.App would resolve the module into the boot layer, and the
        // layer would hold a second copy of it (BUG-20261114-11).
        assertEquals(RUNTIME_MAIN, launch.entryPoint());
        assertEquals(List.of("-Dvidocq.app.module=com.acme.shop", "-Dvidocq.app.modules=com.acme.shop",
                "-Dvidocq.app.main=com.acme.shop.App", "-Dvidocq.app.layer=all"), launch.vmOptions());
    }

    @Test
    @DisplayName("a legacy module/class main class keeps its class part")
    void aLegacyModuleSlashClassKeepsItsClass() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop/com.acme.shop.App", LayerSplit.Mode.ALL);
        assertTrue(launch.vmOptions().contains("-Dvidocq.app.main=com.acme.shop.App"), launch.vmOptions().toString());
    }

    @Test
    @DisplayName("without a main class, the runtime's main starts the application module")
    void withoutAMainClassTheRuntimeMainStarts() {
        var expected = List.of("-Dvidocq.app.module=com.acme.shop", "-Dvidocq.app.modules=com.acme.shop",
                "-Dvidocq.app.layer=all");
        for (String none : new String[] {null, "", "  ", "io.vidocq.runtime.core.Vidocq"}) {
            var launch = ImageLaunch.of("com.acme.shop", none, LayerSplit.Mode.ALL);
            assertEquals(RUNTIME_MAIN, launch.entryPoint(), "main class " + none);
            assertEquals(expected, launch.vmOptions(), "main class " + none);
        }
    }

    @Test
    @DisplayName("the boot layer roots the parent layer; the application layer's modules, resource-only ones too,"
            + " are listed for the layer and never rooted (BUG-20261114-05, BUG-20261114-11)")
    void theBootLayerRootsTheParentLayerOnly() {
        var layer = List.of("com.acme.shop", "vaadin.lumo.theme", "flow.push");
        var roots = List.of("java.sql", "io.vidocq.foy.core", "flow.push", "java.base", "com.acme.shop");
        for (String mainClass : new String[] {null, "com.acme.shop.App"}) {
            var launch = ImageLaunch.of("com.acme.shop", mainClass, LayerSplit.Mode.ALL, roots, layer);
            assertEquals("--add-modules=io.vidocq.foy.core,java.sql", launch.vmOptions().getFirst(), "" + mainClass);
            assertTrue(launch.vmOptions().contains("-Dvidocq.app.modules=com.acme.shop,flow.push,vaadin.lumo.theme"),
                    launch.vmOptions().toString());
            assertEquals(List.of("com.acme.shop", "flow.push", "vaadin.lumo.theme"), launch.layerModules());
        }
    }

    @Test
    @DisplayName("the application module is the first module of the layer, listed once, even when the split omits it")
    void theApplicationModuleIsTheFirstOfTheLayerListedOnce() {
        var launch = ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.CLASSES, List.of(),
                List.of("flow.push", "flow.push"));
        assertEquals(List.of("com.acme.shop", "flow.push"), launch.layerModules());
    }

    @Test
    @DisplayName("the image carries the mode it was built under, classes too")
    void theImageCarriesItsMode() {
        assertEquals("-Dvidocq.app.layer=classes",
                ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.CLASSES).vmOptions().getLast());
        assertEquals("-Dvidocq.app.layer=classes",
                ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.CLASSES).vmOptions().getLast());
    }

    @Test
    @DisplayName("no main module is a failure that names the property to set")
    void noMainModuleNamesTheProperty() {
        var failure = assertThrows(IllegalArgumentException.class, () -> ImageLaunch.of(" ", null, LayerSplit.Mode.ALL));
        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
    }

    @Test
    @DisplayName("the runtime's module as main module is a failure, with or without a main class")
    void theRuntimeModuleIsNotTheApplication() {
        for (String mainClass : new String[] {"io.vidocq.runtime.core.Vidocq", "com.acme.shop.App"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ImageLaunch.of("io.vidocq.runtime.core", mainClass, LayerSplit.Mode.ALL));
            assertTrue(failure.getMessage().contains("the application's own module"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("jlink bakes the options into the image, so that every launcher of the image starts the same way")
    void jlinkBakesTheOptionsIntoTheImage() {
        assertEquals(List.of("--launcher", "shop=" + RUNTIME_MAIN,
                        // One argument: jlink refuses a separate value that starts with "--".
                        "--add-options=--add-modules=io.vidocq.foy.core -Dvidocq.app.module=com.acme.shop"
                                + " -Dvidocq.app.modules=com.acme.shop,flow.push -Dvidocq.app.main=com.acme.shop.App"
                                + " -Dvidocq.app.layer=all",
                        // The layer's modules, for vidocq:jpackage, which starts from the image.
                        "--release-info", "add:" + ImageLaunch.RELEASE_KEY + "=com.acme.shop,flow.push"),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", "com.acme.shop.App",
                        LayerSplit.Mode.ALL, List.of("io.vidocq.foy.core"), List.of("flow.push"))));
    }

    @Test
    @DisplayName("jpackage launches the same entry point, and passes the options for an image jlink did not build")
    void jpackageLaunchesTheSameEntryPoint() {
        assertEquals(List.of("--module", RUNTIME_MAIN,
                        "--java-options", "-Dvidocq.app.module=com.acme.shop",
                        "--java-options", "-Dvidocq.app.modules=com.acme.shop",
                        "--java-options", "-Dvidocq.app.main=com.acme.shop.App",
                        "--java-options", "-Dvidocq.app.layer=all"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL)));
    }
}
