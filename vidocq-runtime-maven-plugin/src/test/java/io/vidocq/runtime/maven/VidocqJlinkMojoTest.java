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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqJlinkMojoTest {

    @TempDir
    Path buildDir;

    /** A modularize report whose only entry is a jar the goal deliberately left automatic. */
    private void writeReport() throws IOException {
        Path dir = ModularizedJars.root(buildDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.txt"), """
                # vidocq:modularize report

                kept automatic: langchain4j-core-1.7.1.jar — ServiceLoader of \
                dev.langchain4j.spi.ServiceHelper from langchain4j.core cannot be declared \
                (module cycle); jlink will reject it, dev mode works

                skipped: something-else-1.0.jar
                """);
    }

    /**
     * Advising "run vidocq:modularize" for a jar the goal already decided it cannot patch sends the
     * user round a loop that cannot terminate; the recorded reason has to replace the hint.
     */
    @Test
    void aKeptAutomaticModuleIsExplainedWithItsReportedReason() throws IOException {
        writeReport();

        String message = VidocqJlinkMojo.automaticModulesMessage(
                List.of("langchain4j.core (file:///m2/langchain4j-core-1.7.1.jar)"), buildDir);

        assertTrue(message.contains("kept it automatic on purpose"), message);
        assertTrue(message.contains("module cycle"), message);
        assertFalse(message.contains("run vidocq:modularize"), message);
    }

    @Test
    void anUnexplainedModuleStillGetsTheGenericHint() throws IOException {
        writeReport();

        String message = VidocqJlinkMojo.automaticModulesMessage(
                List.of("some.other (file:///m2/some-other-1.0.jar)"), buildDir);

        assertTrue(message.contains("run vidocq:modularize"), message);
        assertTrue(message.contains("some.other"), message);
        assertFalse(message.contains("kept it automatic on purpose"), message);
    }

    /** With no report at all — modularize never ran — only the generic hint is available. */
    @Test
    void withoutAReportTheGenericHintIsKept() {
        String message = VidocqJlinkMojo.automaticModulesMessage(
                List.of("langchain4j.core (file:///m2/langchain4j-core-1.7.1.jar)"), buildDir);

        assertTrue(message.contains("run vidocq:modularize"), message);
    }
}
