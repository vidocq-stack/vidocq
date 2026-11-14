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

    @Test
    @DisplayName("an application main class is launched as it is, with the mode of the application layer as the only option")
    void anApplicationMainClassIsLaunchedAsItIs() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL);
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
        assertEquals(List.of("--add-modules=com.acme.shop", "-Dvidocq.app.layer=all"), launch.vmOptions());
    }

    @Test
    @DisplayName("a legacy module/class main class keeps its class part")
    void aLegacyModuleSlashClassKeepsItsClass() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop/com.acme.shop.App", LayerSplit.Mode.ALL);
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
    }

    @Test
    @DisplayName("without a main class, the runtime's main starts and re-layers the application module")
    void withoutAMainClassTheRuntimeMainStarts() {
        var expected = List.of("--add-modules=com.acme.shop", "-Dvidocq.app.module=com.acme.shop",
                "-Dvidocq.app.layer=all");
        for (String none : new String[] {null, "", "  ", "io.vidocq.runtime.core.Vidocq"}) {
            var launch = ImageLaunch.of("com.acme.shop", none, LayerSplit.Mode.ALL);
            assertEquals("io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq", launch.entryPoint(),
                    "main class " + none);
            assertEquals(expected, launch.vmOptions(), "main class " + none);
        }
    }

    @Test
    @DisplayName("every staged module is resolved at start, in both launch shapes (BUG-20261114-05)")
    void everyStagedModuleIsResolvedAtStart() {
        var staged = List.of("com.acme.shop", "flow.push", "vaadin.lumo.theme");
        var withoutMain = ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.ALL, staged);
        assertEquals(List.of("--add-modules=com.acme.shop,flow.push,vaadin.lumo.theme",
                "-Dvidocq.app.module=com.acme.shop", "-Dvidocq.app.layer=all"), withoutMain.vmOptions());
        var withMain = ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL, staged);
        assertEquals(List.of("--add-modules=com.acme.shop,flow.push,vaadin.lumo.theme", "-Dvidocq.app.layer=all"),
                withMain.vmOptions());
    }

    @Test
    @DisplayName("the application module is the first root, listed once, wherever the stage lists it")
    void theApplicationModuleIsTheFirstRootListedOnce() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL,
                List.of("flow.push", "com.acme.shop", "flow.push"));
        assertEquals("--add-modules=com.acme.shop,flow.push", launch.vmOptions().getFirst());
    }

    @Test
    @DisplayName("the image carries the mode it was built under, classes too")
    void theImageCarriesItsMode() {
        assertEquals("-Dvidocq.app.layer=classes",
                ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.CLASSES).vmOptions().getLast());
        assertEquals(List.of("--add-modules=com.acme.shop", "-Dvidocq.app.layer=classes"),
                ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.CLASSES).vmOptions());
    }

    @Test
    @DisplayName("no main module is a failure that names the property to set")
    void noMainModuleNamesTheProperty() {
        var failure = assertThrows(IllegalArgumentException.class, () -> ImageLaunch.of(" ", null, LayerSplit.Mode.ALL));
        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
    }

    @Test
    @DisplayName("the runtime's module as main module, without an application main class, is a failure")
    void theRuntimeModuleIsNotTheApplication() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ImageLaunch.of("io.vidocq.runtime.core", "io.vidocq.runtime.core.Vidocq", LayerSplit.Mode.ALL));
        assertTrue(failure.getMessage().contains("the application's own module"), failure.getMessage());
    }

    @Test
    @DisplayName("jlink bakes the options into the image, so that every launcher of the image starts the same way")
    void jlinkBakesTheOptionsIntoTheImage() {
        assertEquals(List.of("--launcher", "shop=io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq",
                        // One argument: jlink refuses a separate value that starts with "--".
                        "--add-options=--add-modules=com.acme.shop -Dvidocq.app.module=com.acme.shop"
                                + " -Dvidocq.app.layer=all"),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.ALL)));
        assertEquals(List.of("--launcher", "shop=com.acme.shop/com.acme.shop.App",
                        "--add-options=--add-modules=com.acme.shop -Dvidocq.app.layer=all"),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL)));
    }

    @Test
    @DisplayName("jpackage launches the same entry point, and passes the options for an image jlink did not build")
    void jpackageLaunchesTheSameEntryPoint() {
        assertEquals(List.of("--module", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq",
                        "--java-options", "--add-modules=com.acme.shop",
                        "--java-options", "-Dvidocq.app.module=com.acme.shop",
                        "--java-options", "-Dvidocq.app.layer=all"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", null, LayerSplit.Mode.ALL)));
        assertEquals(List.of("--module", "com.acme.shop/com.acme.shop.App",
                        "--java-options", "--add-modules=com.acme.shop",
                        "--java-options", "-Dvidocq.app.layer=all"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", "com.acme.shop.App", LayerSplit.Mode.ALL)));
    }
}
