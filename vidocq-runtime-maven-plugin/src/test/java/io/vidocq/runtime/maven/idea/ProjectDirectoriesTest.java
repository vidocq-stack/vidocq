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
package io.vidocq.runtime.maven.idea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the detection of a {@code vidocq:idea} run inside a module of a larger reactor.
 *
 * <p>The generated {@code $PROJECT_DIR$} paths are relative to the directory the goal runs in. IntelliJ
 * loads a {@code .run/*.run.xml} anywhere in the project content, so a file written by a run inside a
 * module is still picked up when IntelliJ opens the reactor root; its {@code $PROJECT_DIR$/pom.xml} then
 * points at the root aggregator, where {@code vidocq:generate} finds no classes and the application
 * starts with an incomplete bean index, without any error. The goal therefore looks for an ancestor
 * aggregator that lists the directory as a module, at any depth.
 */
class ProjectDirectoriesTest {

    @Test
    void aModuleOfTheParentAggregatorIsDetected(@TempDir Path root) throws IOException {
        pom(root, "<modules><module>app</module></modules>");
        Path app = pom(root.resolve("app"), "");

        assertEquals(root.resolve("pom.xml").toRealPath(), ProjectDirectories.aggregatorOf(app));
    }

    @Test
    void aModuleListedFromAnyAncestorIsDetected(@TempDir Path root) throws IOException {
        pom(root, "<modules><module>apps/server</module></modules>");
        pom(root.resolve("apps"), "<modules><module>other</module></modules>");
        Path server = pom(root.resolve("apps/server"), "");

        assertEquals(root.resolve("pom.xml").toRealPath(), ProjectDirectories.aggregatorOf(server));
    }

    @Test
    void aModuleNamingItsPomFileOrListedInAProfileIsDetected(@TempDir Path root) throws IOException {
        pom(root, "<modules><module>a/pom.xml</module></modules>"
                + "<profiles><profile><id>more</id><modules><module>b</module></modules></profile></profiles>");
        Path a = pom(root.resolve("a"), "");
        Path b = pom(root.resolve("b"), "");

        assertEquals(root.resolve("pom.xml").toRealPath(), ProjectDirectories.aggregatorOf(a));
        assertEquals(root.resolve("pom.xml").toRealPath(), ProjectDirectories.aggregatorOf(b));
    }

    @Test
    void theReactorRootAndUnrelatedDirectoriesAreNotModules(@TempDir Path root) throws IOException {
        pom(root, "<modules><module>app</module></modules>");
        pom(root.resolve("app"), "");
        Path tool = pom(root.resolve("tool"), "");

        assertNull(ProjectDirectories.aggregatorOf(root));
        assertNull(ProjectDirectories.aggregatorOf(tool));
    }

    @Test
    void anUnparsableAncestorPomIsIgnored(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><modules><module>app</module>");
        Path app = pom(root.resolve("app"), "");

        assertNull(ProjectDirectories.aggregatorOf(app));
    }

    @Test
    void aSymbolicLinkToAModuleIsStillAModule(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        pom(root, "<modules><module>app</module></modules>");
        Path app = pom(root.resolve("app"), "");
        Path link = Files.createSymbolicLink(tmp.resolve("link-to-app"), app);

        assertEquals(root.resolve("pom.xml").toRealPath(), ProjectDirectories.aggregatorOf(link));
    }

    private static Path pom(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  %s
                </project>
                """.formatted(content));
        return directory;
    }
}
