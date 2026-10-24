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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#339 (evolution 4): {@code vidocq create} writes a project that is finished, a first test included. */
class ProjectScaffolderFinishedProjectTest {

    private static final String RUNTIME = "0.4.0";

    private static Path scaffold(Path dir, Set<String> extensions, String parentVersion, Reporter reporter)
            throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", extensions, parentVersion,
                false), dir, RUNTIME, reporter);
        return dir.resolve("demo");
    }

    @Test
    void aRestApplicationGetsAFirstTestOfItsResource(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("cassini-rest"), null, new RecordingReporter());

        String test = Files.readString(project.resolve("src/test/java/com/acme/demo/HelloResourceTest.java"));
        assertTrue(test.startsWith("package com.acme.demo;\n"), test);
        assertTrue(test.contains("@VidocqTest\nclass HelloResourceTest {"), test);
        assertTrue(test.contains("application.get(\"/hello\")"), test);
        assertTrue(test.contains("assertEquals(\"Hello from Vidocq!\", response.body());"), test);

        String pom = Files.readString(project.resolve("pom.xml"));
        for (String artifact : new String[] {"vidocq-runtime-junit", "junit-jupiter"}) {
            assertTrue(pom.contains("<artifactId>" + artifact + "</artifactId>\n"
                    + "            <scope>test</scope>"), "a versionless test dependency on " + artifact + ":\n" + pom);
        }
    }

    /** No class to test, nothing that listens: the tests start from JUnit alone. */
    @Test
    void anApplicationWithoutRestGetsJUnitButNoTest(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), null, new RecordingReporter());

        assertFalse(Files.exists(project.resolve("src/test")));
        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>junit-jupiter</artifactId>"), pom);
        assertFalse(pom.contains("vidocq-runtime-junit"), pom);
    }

    /** {@code @VidocqTest} comes with 0.4.0, and the older parents do not manage it. */
    @Test
    void underAParentBefore040NoTestIsWrittenAndTheUserIsTold(@TempDir Path dir) throws IOException {
        RecordingReporter reporter = new RecordingReporter();

        Path project = scaffold(dir, Set.of("cassini-rest"), "0.3.0", reporter);

        assertFalse(Files.exists(project.resolve("src/test")));
        String pom = Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("junit"), pom);
        assertTrue(reporter.lines.contains("note: Parent version 0.3.0 predates 0.4.0: no first test, as @VidocqTest"
                + " (vidocq-runtime-junit) comes with 0.4.0."), reporter.lines.toString());
    }

    @Test
    void theProjectIgnoresWhatBuildsAndIdesWrite(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), null, new RecordingReporter());

        String ignored = Files.readString(project.resolve(".gitignore"));
        for (String entry : new String[] {"target/", ".idea/", "*.iml", ".vscode/", ".classpath", ".project",
                ".settings/", ".DS_Store"}) {
            assertTrue(ignored.lines().anyMatch(entry::equals), entry + " missing from:\n" + ignored);
        }
    }

    @Test
    void theReadmeGivesTheCommandsThatWork(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("cassini-rest"), null, new RecordingReporter());

        String readme = Files.readString(project.resolve("README.md"));
        assertTrue(readme.startsWith("# demo\n"), readme);
        for (String command : new String[] {"vidocq dev", "vidocq test", "vidocq build", "vidocq build jlink",
                "./mvnw vidocq:dev", "./mvnw test", "./mvnw package", "curl http://localhost:8080/hello"}) {
            assertTrue(readme.contains(command), command + " missing from:\n" + readme);
        }
    }

    @Test
    void withoutRestTheReadmeHasNoEndpointToCall(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), null, new RecordingReporter());

        assertFalse(Files.readString(project.resolve("README.md")).contains("curl"));
    }
}
