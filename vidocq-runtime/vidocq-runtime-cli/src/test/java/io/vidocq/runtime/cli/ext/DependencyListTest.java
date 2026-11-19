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
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyListTest {

    @Test
    void parsesResolvedArtifactsAndSkipsTheHeader() {
        String output = """
                The following files have been resolved:
                   io.vidocq.runtime:vidocq-runtime-core:jar:0.4.0:compile:/m2/vidocq-runtime-core-0.4.0.jar -- module io.vidocq.runtime.core
                   io.vidocq.chappe:chappe-http:jar:0.4.0:runtime:/m2/chappe-http-0.4.0.jar

                """;

        List<ResolvedArtifact> artifacts = DependencyList.parse(output);

        assertEquals(List.of(
                new ResolvedArtifact("io.vidocq.runtime", "vidocq-runtime-core", "0.4.0",
                        Path.of("/m2/vidocq-runtime-core-0.4.0.jar")),
                new ResolvedArtifact("io.vidocq.chappe", "chappe-http", "0.4.0",
                        Path.of("/m2/chappe-http-0.4.0.jar"))), artifacts);
    }

    @Test
    void stripsAnsiColoursMavenWritesIntoTheOutputFile() {
        String output = "   g:a:jar:1.0:compile:/m2/a-1.0.jar\u001B[36m -- module a\u001B[m\n";

        assertEquals(List.of(new ResolvedArtifact("g", "a", "1.0", Path.of("/m2/a-1.0.jar"))),
                DependencyList.parse(output));
    }

    @Test
    void handlesAClassifier() {
        String output = "   g:a:jar:linux-x86_64:1.0:compile:/m2/a-1.0-linux-x86_64.jar\n";

        ResolvedArtifact artifact = DependencyList.parse(output).getFirst();
        assertEquals("1.0", artifact.version());
        assertEquals(Path.of("/m2/a-1.0-linux-x86_64.jar"), artifact.file());
    }

    @Test
    void keepsAWindowsDriveLetterInThePath() {
        String output = "   g:a:jar:1.0:compile:C:\\m2\\a-1.0.jar -- module a\n";

        assertEquals("C:\\m2\\a-1.0.jar", DependencyList.parse(output).getFirst().file().toString()
                .replace('/', '\\'));
    }

    @Test
    void ignoresLinesWithoutAScopeOrAFile() {
        assertTrue(DependencyList.parse("none\n   g:a:pom:1.0\n").isEmpty());
    }

    @Test
    void commandResolvesTheRuntimeScopeInBatchModeIntoTheGivenFile() {
        List<String> command = DependencyList.command("mvn", Path.of("/tmp/deps.txt"));

        assertEquals("mvn", command.getFirst());
        assertTrue(command.contains("-B"));
        assertTrue(command.contains("-q"));
        assertTrue(command.contains("org.apache.maven.plugins:maven-dependency-plugin:"
                + DependencyList.PLUGIN_VERSION + ":list"));
        assertTrue(command.contains("-DincludeScope=runtime"));
        assertTrue(command.contains("-DoutputAbsoluteArtifactFilename=true"));
        assertTrue(command.contains("-DoutputFile=" + Path.of("/tmp/deps.txt")));
    }
}
