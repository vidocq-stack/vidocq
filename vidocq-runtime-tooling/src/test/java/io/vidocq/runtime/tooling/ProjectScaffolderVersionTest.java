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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderVersionTest {

    private static final String RUNTIME = "1.2.3";
    private static final Reporter SILENT = new RecordingReporter();

    private static ProjectSpec create(String parentVersion) {
        return new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of(), parentVersion, false);
    }

    @Test
    void scaffoldedPomInheritsRuntimeParentVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create(null), dir, RUNTIME, SILENT);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<version>" + RUNTIME + "</version>"),
                "scaffolded parent version must be the runtime parent version, pom was:\n" + pom);
        assertFalse(pom.contains("${project."),
                "pom must not rely on ${project.*} for versions — the parent's "
                        + "dependencyManagement re-evaluates them to the app's own version");
    }

    /** BUG-20261024-06: the application took the runtime's version, and moved with every upgrade. */
    @Test
    void theApplicationHasAVersionOfItsOwn(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create(null), dir, RUNTIME, SILENT);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<artifactId>demo</artifactId>\n    <version>1.0.0-SNAPSHOT</version>"), pom);
    }

    /**
     * Before 0.4.0, the parent's dependencyManagement named the runtime version {@code ${project.version}}, which
     * Maven interpolates in the child (BUG-20261006-02): an application of its own version would ask for every
     * runtime artifact at that version. Under such a parent the application keeps inheriting the parent's version.
     */
    @Test
    void underAParentBefore040TheApplicationInheritsItsVersion(@TempDir Path dir) throws IOException {
        RecordingReporter reporter = new RecordingReporter();

        ProjectScaffolder.scaffold(create("0.3.0"), dir, RUNTIME, reporter);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertFalse(pom.contains("1.0.0-SNAPSHOT"), pom);
        assertTrue(reporter.lines.contains("note: Parent version 0.3.0 predates 0.4.0: the project inherits its"
                + " version, as the parent's dependencyManagement resolves at the project's version."),
                reporter.lines.toString());
    }

    @Test
    void explicitParentVersionOverridesRuntimeVersion(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(create("9.9.9"), dir, RUNTIME, SILENT);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<version>9.9.9</version>"),
                "explicit --parent-version must win, pom was:\n" + pom);
    }

    @Test
    void underAParentBefore040DependenciesPinTheRuntimeVersion(@TempDir Path dir) throws IOException {
        // A parent before 0.4.0 named the runtime version ${project.version} in its
        // dependencyManagement, which re-evaluates to the app's version: dependencies pin it.
        ProjectScaffolder.scaffold(
                new ProjectSpec("demo", "com.acme", "com.acme.demo",
                        Set.of("cassini-rest"), "0.3.0", false), dir, RUNTIME, SILENT);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertTrue(pom.contains("<artifactId>vidocq-runtime-core</artifactId>\n"
                        + "            <version>0.3.0</version>"),
                "core dependency must pin the runtime version explicitly, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>\n"
                        + "            <version>0.3.0</version>"),
                "extension dependencies must pin the runtime version explicitly, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>\n"
                        + "                        <version>0.3.0</version>"),
                "codegen paths must pin the runtime version explicitly, pom was:\n" + pom);
    }

    /**
     * Vidocq/vidocq#339: from 0.4.0 the application parent imports vidocq-runtime-bom, which versions every Vidocq
     * artifact; the pom names two versions, its parent's and its own, and an upgrade changes one line.
     */
    @Test
    void underTheApplicationParentTheBomVersionsEveryVidocqArtifact(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(
                new ProjectSpec("demo", "com.acme", "com.acme.demo",
                        Set.of("cassini-rest"), "0.4.0", false), dir, RUNTIME, SILENT);

        String pom = Files.readString(dir.resolve("demo/pom.xml"));
        assertEquals(List.of("0.4.0", "1.0.0-SNAPSHOT"), pom.lines()
                .filter(l -> l.strip().startsWith("<version>"))
                .map(l -> l.strip().replaceAll("</?version>", ""))
                .toList(), pom);
        assertFalse(pom.contains("codegen"), "the parent's plugin wires the codegen bundle (#339):\n" + pom);
    }

    @Test
    void snapshotParentDeclaresTheCentralSnapshotRepository() {
        String pom = ProjectScaffolder.buildPom(create("0.4.0-SNAPSHOT"), "0.3.0", SILENT);

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
        String pom = ProjectScaffolder.buildPom(create(null), "0.4.0-SNAPSHOT", SILENT);

        assertTrue(pom.contains("<id>central-snapshots</id>"),
                "the CLI's SNAPSHOT runtime must resolve from Central snapshots, pom was:\n" + pom);
    }

    @Test
    void releaseParentDeclaresNoRepository() {
        String pom = ProjectScaffolder.buildPom(create(null), "0.3.0", SILENT);

        assertFalse(pom.contains("<repositories>"),
                "a released parent resolves from Maven Central alone, pom was:\n" + pom);
        assertFalse(pom.contains("<pluginRepositories>"),
                "a released parent resolves from Maven Central alone, pom was:\n" + pom);
    }

    @Test
    void tellsWhereSnapshotArtifactsComeFrom() {
        RecordingReporter reporter = new RecordingReporter();
        ProjectScaffolder.buildPom(create("0.4.0-SNAPSHOT"), "0.3.0", reporter);
        String out = String.join("\n", reporter.lines);
        assertTrue(out.contains("https://central.sonatype.com/repository/maven-snapshots/"),
                "a SNAPSHOT parent must say where its artifacts come from, got: " + out);
    }

    @Test
    void saysNothingForReleaseParentVersion() {
        RecordingReporter reporter = new RecordingReporter();
        ProjectScaffolder.buildPom(create(null), "0.2.0", reporter);
        String out = String.join("\n", reporter.lines);
        assertFalse(out.contains("SNAPSHOT"),
                "a release parent must not trigger a note, got: " + out);
    }
}
