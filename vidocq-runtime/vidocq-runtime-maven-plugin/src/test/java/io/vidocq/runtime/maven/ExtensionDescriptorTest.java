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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionDescriptorTest {

    @Test
    void aReactorModuleIsReadFromItsClassesDirectory(@TempDir Path classes) throws Exception {
        Path resource = classes.resolve(ExtensionDescriptor.RESOURCE);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "id = ravel-config\ncodegen =  vidocq-runtime-ravel-config-extension-codegen \n");

        assertEquals(Optional.of("vidocq-runtime-ravel-config-extension-codegen"),
                ExtensionDescriptor.of(classes).orElseThrow().codegen());
    }

    @Test
    void aBlankCodegenMeansNone(@TempDir Path classes) throws Exception {
        Path resource = classes.resolve(ExtensionDescriptor.RESOURCE);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "id = knock-health\ncodegen =\n");

        assertEquals(Optional.empty(), ExtensionDescriptor.of(classes).orElseThrow().codegen());
    }

    @Test
    void thereIsNoDescriptorWithoutTheResource(@TempDir Path dir) throws Exception {
        assertTrue(ExtensionDescriptor.of(dir).isEmpty(), "a classes directory without the resource");
        assertTrue(ExtensionDescriptor.of(DevOnlyJarsTest.jar(dir, "plain.jar", null)).isEmpty(), "a jar without it");
        assertTrue(ExtensionDescriptor.of(dir.resolve("missing.jar")).isEmpty(), "no file");
        assertTrue(ExtensionDescriptor.of(null).isEmpty(), "no artifact");
    }

    @Test
    void anUnreadableJarHasNoDescriptor(@TempDir Path dir) throws Exception {
        Path notAJar = Files.writeString(dir.resolve("broken.jar"), "not a zip");

        assertTrue(ExtensionDescriptor.of(notAJar).isEmpty());
    }
}
