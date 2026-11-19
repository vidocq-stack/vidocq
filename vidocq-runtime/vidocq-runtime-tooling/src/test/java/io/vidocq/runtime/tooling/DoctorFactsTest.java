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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The facts {@code vidocq doctor} and {@code vidocq:doctor} both diagnose (Vidocq/vidocq#314). */
class DoctorFactsTest {

    @TempDir
    Path dir;

    @Test
    void aVidocqProjectIsDescribedWithItsExtensions() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(project.resolve("pom.xml"), "<project><parent><groupId>io.vidocq.runtime</groupId></parent></project>");
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(resources.resolve("vidocq.properties"), "vidocq.http.port=8081\nvidocq.typo=1\n");

        DoctorContext context = DoctorFacts.gather(project, dir.toString(), () -> DoctorFacts.Extensions.found(3));

        assertEquals(Runtime.version().feature(), context.javaFeatureVersion());
        assertEquals(Diagnostics.MINIMUM_JAVA_VERSION, context.minimumJavaVersion());
        assertTrue(context.javaHomeIsDirectory());
        assertTrue(context.mavenWrapperPresent(), "mvnw of a parent directory");
        assertTrue(context.pomPresent());
        assertTrue(context.vidocqProject());
        assertEquals(3, context.extensionCount());
        assertNull(context.extensionsError());
        assertTrue(context.configPresent());
        assertEquals(List.of("vidocq.http.port", "vidocq.typo"), context.configKeys());
    }

    @Test
    void extensionsAreResolvedOnlyForAVidocqProject() throws IOException {
        AtomicBoolean asked = new AtomicBoolean();
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>other</artifactId></project>");

        DoctorContext context = DoctorFacts.gather(dir, null, () -> {
            asked.set(true);
            return DoctorFacts.Extensions.found(1);
        });

        assertFalse(asked.get());
        assertTrue(context.pomPresent());
        assertFalse(context.vidocqProject());
        assertFalse(context.javaHomeSet());
        assertFalse(context.configPresent());
    }

    @Test
    void aResolutionFailureIsAFact() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><groupId>io.vidocq.runtime</groupId></project>");

        DoctorContext context = DoctorFacts.gather(dir, dir.resolve("missing").toString(),
                () -> DoctorFacts.Extensions.failed("Maven exited with code 1"));

        assertEquals("Maven exited with code 1", context.extensionsError());
        assertFalse(context.javaHomeIsDirectory());
        assertTrue(Diagnostics.run(context).stream().anyMatch(d -> d.name().equals("Extensions")
                && d.status() == Diagnostic.Status.WARN));
    }

    @Test
    void withoutPomThereIsNoProject() {
        DoctorContext context = DoctorFacts.gather(dir, null, () -> DoctorFacts.Extensions.found(1));

        assertFalse(context.pomPresent());
        assertFalse(context.vidocqProject());
        assertEquals(0, context.extensionCount());
    }
}
