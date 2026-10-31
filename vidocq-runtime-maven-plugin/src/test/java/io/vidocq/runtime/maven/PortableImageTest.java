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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe {@code vidocq:jlink} leaves next to its image, so that {@code vidocq:docker} can link the same image
 * again inside a Linux JDK when the build host is macOS or Windows (BUG-20261030-17).
 */
class PortableImageTest {

    @Test
    void theArgumentFileQuotesAnArgumentWithASpace() {
        assertEquals("""
                --add-modules
                acme.app,java.base
                "--add-options=--add-modules=acme.app -Dvidocq.app.module=acme.app"
                --strip-debug
                """, PortableImage.argumentFile(List.of("--add-modules", "acme.app,java.base",
                "--add-options=--add-modules=acme.app -Dvidocq.app.module=acme.app", "--strip-debug")));
    }

    @Test
    void theRecipeHoldsTheArgumentsTheEntryPointAndTheConfiguration(@TempDir Path target) throws Exception {
        Path classes = Files.createDirectories(target.resolve("classes"));
        Files.writeString(classes.resolve("vidocq.properties"), "vidocq.http.port=8080\n");

        PortableImage.write(target, List.of("--add-modules", "acme.app"), "acme.app/acme.app.App",
                List.of(classes.resolve("vidocq.properties")));

        Path dir = target.resolve(PortableImage.DIRECTORY);
        assertEquals("--add-modules\nacme.app\n", Files.readString(dir.resolve(PortableImage.ARGUMENTS)));
        assertEquals("acme.app/acme.app.App", PortableImage.entryPoint(target).orElseThrow());
        assertTrue(Files.isRegularFile(dir.resolve("conf/vidocq.properties")));
    }

    @Test
    void anImageWithoutARecipeHasNoEntryPoint(@TempDir Path target) {
        assertTrue(PortableImage.entryPoint(target).isEmpty());
    }
}
