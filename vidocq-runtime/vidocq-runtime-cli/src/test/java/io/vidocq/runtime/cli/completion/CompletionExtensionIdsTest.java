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
package io.vidocq.runtime.cli.completion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Vidocq/vidocq#809: the bash script completes the function-first ids, and names the extensions of a pom by them, so
 * {@code vidocq extension add rest<TAB>} offers {@code rest-cassini rest-client-cyrano}.
 */
class CompletionExtensionIdsTest {

    private static final Path BASH = Path.of("/bin/bash");

    @TempDir
    Path project;

    @Test
    void addCompletesTheIdsThatStartWithTheFunction() throws Exception {
        assertEquals("rest-cassini rest-client-cyrano", complete("extension", "add", "rest"));
    }

    @Test
    void theExtensionsOfThePomAreNamedByTheirIdsAndNotOfferedAgain() throws Exception {
        writePom("vidocq-runtime-knock-health-extension", "vidocq-runtime-mansart-pool-telemetry-extension",
                "vidocq-runtime-acme-extension");

        assertEquals("health-knock jdbc-telemetry acme", complete("extension", "remove", ""));
        assertEquals("", complete("extension", "add", "health"));
        assertEquals("telemetry-humboldt", complete("extension", "add", "telemetry"));
    }

    private void writePom(String... artifactIds) throws IOException {
        StringBuilder pom = new StringBuilder("<project>\n  <dependencies>\n");
        for (String artifactId : artifactIds) {
            pom.append("    <dependency>\n      <artifactId>").append(artifactId).append("</artifactId>\n")
                    .append("    </dependency>\n");
        }
        Files.writeString(project.resolve("pom.xml"), pom.append("  </dependencies>\n</project>\n"));
    }

    /** What bash offers for the last of {@code words}, typed after {@code vidocq}, in the project directory. */
    private String complete(String... words) throws Exception {
        assumeTrue(Files.isExecutable(BASH), "no bash to run the script");
        Path script = project.resolve("vidocq.bash");
        Files.writeString(script, CompletionScripts.bash(CommandCatalog.COMMANDS, "test"));
        StringBuilder line = new StringBuilder("source vidocq.bash; COMP_WORDS=(vidocq");
        for (String word : words) {
            line.append(" '").append(word).append('\'');
        }
        line.append("); COMP_CWORD=").append(words.length).append("; _vidocq; echo \"${COMPREPLY[*]}\"");
        Process bash = new ProcessBuilder(List.of(BASH.toString(), "--norc", "--noprofile", "-c", line.toString()))
                .directory(project.toFile()).redirectErrorStream(true).start();
        assertTrue(bash.waitFor(20, TimeUnit.SECONDS), "bash did not answer");
        String out = new String(bash.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, bash.exitValue(), out);
        return out;
    }
}
