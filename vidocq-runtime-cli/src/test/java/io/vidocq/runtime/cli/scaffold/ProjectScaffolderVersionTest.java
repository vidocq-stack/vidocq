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
package io.vidocq.runtime.cli.scaffold;

import io.vidocq.runtime.cli.Command;
import io.vidocq.runtime.cli.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderVersionTest {

    private static Command.Create create(String parentVersion) {
        return new Command.Create("demo", "com.acme", "com.acme.demo", Set.of(), parentVersion);
    }

    @Test
    void scaffoldedPomInheritsRuntimeParentVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create(null), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<version>" + Version.runtime() + "</version>"),
                "scaffolded parent version must be the runtime parent version, pom was:\n" + pom);
        assertFalse(pom.contains("${"), "pom must not contain unfiltered placeholders");
    }

    @Test
    void scaffoldedAppHasItsOwnInitialVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create(null), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<artifactId>demo</artifactId>\n    <version>1.0.0-SNAPSHOT</version>"),
                "the app must declare its own initial version instead of inheriting the parent's, pom was:\n" + pom);
    }

    @Test
    void explicitParentVersionOverridesRuntimeVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create("9.9.9"), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<version>9.9.9</version>"),
                "explicit --parent-version must win, pom was:\n" + pom);
    }

    @Test
    void warnsWhenParentVersionIsSnapshot() {
        String out = captureStdout(() -> ProjectScaffolder.buildPom(create(null), "0.3.0-SNAPSHOT"));
        assertTrue(out.contains("--parent-version"),
                "a SNAPSHOT parent must trigger a warning suggesting --parent-version, got: " + out);
    }

    @Test
    void doesNotWarnForReleaseParentVersion() {
        String out = captureStdout(() -> ProjectScaffolder.buildPom(create(null), "0.2.0"));
        assertFalse(out.contains("--parent-version"),
                "a release parent must not trigger a warning, got: " + out);
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
