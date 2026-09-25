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
        assertFalse(pom.contains("${project."),
                "pom must not rely on ${project.*} for versions — the parent's "
                        + "dependencyManagement re-evaluates them to the app's own version");
    }

    @Test
    void scaffoldedAppInheritsTheParentVersion(@TempDir Path dir) throws IOException {
        // The released parent's dependencyManagement pins every runtime artifact to
        // ${project.version}. An app declaring its own version would re-evaluate that
        // to itself and break transitive resolution (e.g. vidocq-runtime-spi), so the
        // scaffolded pom must NOT declare a version of its own.
        ProjectScaffolder.scaffold(create(null), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertFalse(pom.contains("<artifactId>demo</artifactId>\n    <version>"),
                "the app must inherit the parent version, pom was:\n" + pom);
    }

    @Test
    void explicitParentVersionOverridesRuntimeVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create("9.9.9"), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<version>9.9.9</version>"),
                "explicit --parent-version must win, pom was:\n" + pom);
    }

    @Test
    void scaffoldedDependenciesCarryExplicitRuntimeVersion(@TempDir Path dir) throws IOException {
        // The released parent's dependencyManagement uses ${project.version}, which
        // re-evaluates to the app's own version — dependencies must pin explicitly.
        ProjectScaffolder.scaffold(
                new Command.Create("demo", "com.acme", "com.acme.demo",
                        Set.of("cassini-rest"), "2.2.2"), dir);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<artifactId>vidocq-runtime-core</artifactId>\n"
                        + "            <version>2.2.2</version>"),
                "core dependency must pin the runtime version explicitly, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>\n"
                        + "            <version>2.2.2</version>"),
                "extension dependencies must pin the runtime version explicitly, pom was:\n" + pom);
    }

    @Test
    void snapshotParentDeclaresTheCentralSnapshotRepository() {
        String pom = ProjectScaffolder.buildPom(create("0.4.0-SNAPSHOT"), "0.3.0");

        String repository = """
                    <repositories>
                        <repository>
                            <id>central-snapshots</id>
                            <url>https://central.sonatype.com/repository/maven-snapshots/</url>
                            <releases><enabled>false</enabled></releases>
                            <snapshots><enabled>true</enabled></snapshots>
                        </repository>
                    </repositories>
                """;
        assertTrue(pom.contains(repository),
                "a SNAPSHOT parent must resolve its artifacts from Central snapshots, pom was:\n" + pom);
        assertTrue(pom.contains("""
                    <pluginRepositories>
                        <pluginRepository>
                            <id>central-snapshots</id>
                """),
                "the SNAPSHOT vidocq-runtime-maven-plugin needs a plugin repository, pom was:\n" + pom);
    }

    @Test
    void snapshotRuntimeOfTheCliDeclaresTheCentralSnapshotRepository() {
        // A CLI installed from a SNAPSHOT scaffolds with its own SNAPSHOT runtime.
        String pom = ProjectScaffolder.buildPom(create(null), "0.4.0-SNAPSHOT");

        assertTrue(pom.contains("<id>central-snapshots</id>"),
                "the CLI's SNAPSHOT runtime must resolve from Central snapshots, pom was:\n" + pom);
    }

    @Test
    void releaseParentDeclaresNoRepository() {
        String pom = ProjectScaffolder.buildPom(create(null), "0.3.0");

        assertFalse(pom.contains("<repositories>"),
                "a released parent resolves from Maven Central alone, pom was:\n" + pom);
        assertFalse(pom.contains("<pluginRepositories>"),
                "a released parent resolves from Maven Central alone, pom was:\n" + pom);
    }

    @Test
    void tellsWhereSnapshotArtifactsComeFrom() {
        String out = captureStdout(() -> ProjectScaffolder.buildPom(create("0.4.0-SNAPSHOT"), "0.3.0"));
        assertTrue(out.contains("https://central.sonatype.com/repository/maven-snapshots/"),
                "a SNAPSHOT parent must say where its artifacts come from, got: " + out);
    }

    @Test
    void saysNothingForReleaseParentVersion() {
        String out = captureStdout(() -> ProjectScaffolder.buildPom(create(null), "0.2.0"));
        assertFalse(out.contains("SNAPSHOT"),
                "a release parent must not trigger a note, got: " + out);
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
