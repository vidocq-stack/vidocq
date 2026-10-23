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

    @Test
    @DisplayName("an application main class is launched as it is, with no option added to the image")
    void anApplicationMainClassIsLaunchedAsItIs() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop.App");
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
        assertEquals(List.of(), launch.vmOptions());
    }

    @Test
    @DisplayName("a legacy module/class main class keeps its class part")
    void aLegacyModuleSlashClassKeepsItsClass() {
        var launch = ImageLaunch.of("com.acme.shop", "com.acme.shop/com.acme.shop.App");
        assertEquals("com.acme.shop/com.acme.shop.App", launch.entryPoint());
    }

    @Test
    @DisplayName("without a main class, the runtime's main starts and re-layers the application module")
    void withoutAMainClassTheRuntimeMainStarts() {
        var expected = List.of("--add-modules=com.acme.shop", "-Dvidocq.app.module=com.acme.shop");
        for (String none : new String[] {null, "", "  ", "io.vidocq.runtime.core.Vidocq"}) {
            var launch = ImageLaunch.of("com.acme.shop", none);
            assertEquals("io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq", launch.entryPoint(),
                    "main class " + none);
            assertEquals(expected, launch.vmOptions(), "main class " + none);
        }
    }

    @Test
    @DisplayName("no main module is a failure that names the property to set")
    void noMainModuleNamesTheProperty() {
        var failure = assertThrows(IllegalArgumentException.class, () -> ImageLaunch.of(" ", null));
        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
    }

    @Test
    @DisplayName("the runtime's module as main module, without an application main class, is a failure")
    void theRuntimeModuleIsNotTheApplication() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ImageLaunch.of("io.vidocq.runtime.core", "io.vidocq.runtime.core.Vidocq"));
        assertTrue(failure.getMessage().contains("the application's own module"), failure.getMessage());
    }

    @Test
    @DisplayName("jlink bakes the options into the image, so that every launcher of the image starts the same way")
    void jlinkBakesTheOptionsIntoTheImage() {
        assertEquals(List.of("--launcher", "shop=io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq",
                        // One argument: jlink refuses a separate value that starts with "--".
                        "--add-options=--add-modules=com.acme.shop -Dvidocq.app.module=com.acme.shop"),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", null)));
        assertEquals(List.of("--launcher", "shop=com.acme.shop/com.acme.shop.App"),
                VidocqJlinkMojo.launcherArgs("shop", ImageLaunch.of("com.acme.shop", "com.acme.shop.App")));
    }

    @Test
    @DisplayName("jpackage launches the same entry point, and passes the options for an image jlink did not build")
    void jpackageLaunchesTheSameEntryPoint() {
        assertEquals(List.of("--module", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq",
                        "--java-options", "--add-modules=com.acme.shop",
                        "--java-options", "-Dvidocq.app.module=com.acme.shop"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", null)));
        assertEquals(List.of("--module", "com.acme.shop/com.acme.shop.App"),
                VidocqJpackageMojo.entryArgs(ImageLaunch.of("com.acme.shop", "com.acme.shop.App")));
    }
}
