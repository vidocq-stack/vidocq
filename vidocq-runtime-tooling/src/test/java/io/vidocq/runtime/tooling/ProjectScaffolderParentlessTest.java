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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: a project {@code vidocq create} writes builds on the BOM and the plugin as a build extension,
 * without {@code vidocq-runtime-parent}, by default; {@code --with-parent} writes the parent as before. The plugin
 * gives the build what the parent carried, from 0.4.0 on: an older runtime keeps the parent.
 */
class ProjectScaffolderParentlessTest {

    private static final String RUNTIME = "0.4.0";

    private static ProjectSpec rest(boolean withParent) {
        return new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"), null, false, false,
                withParent);
    }

    @Test
    void aProjectBuildsOnTheBomAndThePluginWithoutAParentByDefault() {
        String pom = ProjectScaffolder.buildPom(rest(false), RUNTIME, new RecordingReporter());

        assertFalse(pom.contains("<parent>"), pom);
        assertTrue(pom.contains("<artifactId>demo</artifactId>\n    <version>1.0.0-SNAPSHOT</version>"), pom);
        assertTrue(pom.contains("<vidocq.version>0.4.0</vidocq.version>"), pom);
        // IntelliJ IDEA reads the language level from the pom alone; Maven and the plugin know it without the line.
        assertTrue(pom.contains("<maven.compiler.release>25</maven.compiler.release>"), pom);
        // The project is created in UTF-8, the charset of its sources and resources, whatever the Maven in use.
        assertTrue(pom.contains("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"), pom);
        assertTrue(pom.contains("""
                            <dependency>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-bom</artifactId>
                                <version>${vidocq.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                """), pom);
        assertTrue(pom.contains("<artifactId>junit-bom</artifactId>\n                <version>"
                + ProjectScaffolder.JUNIT_BOM_VERSION + "</version>"), pom);
        assertTrue(pom.contains("""
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <version>${vidocq.version}</version>
                                <extensions>true</extensions>
                            </plugin>
                """), pom);
        assertFalse(pom.contains("<executions>"), "the plugin binds its executions itself:\n" + pom);
        assertFalse(pom.contains("annotationProcessorPaths"), "the plugin wires the codegen bundles:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>\n        </dependency>"),
                "a versionless extension dependency:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-junit</artifactId>\n            <scope>test</scope>"), pom);
        assertTrue(pom.contains("<artifactId>junit-jupiter</artifactId>\n            <scope>test</scope>"), pom);
    }

    @Test
    void withParentWritesTheParentAsBefore() {
        String pom = ProjectScaffolder.buildPom(rest(true), RUNTIME, new RecordingReporter());

        assertTrue(pom.contains("""
                    <parent>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-parent</artifactId>
                        <version>0.4.0</version>
                        <relativePath/>
                    </parent>
                """), pom);
        assertFalse(pom.contains("vidocq-runtime-bom"), "the parent imports the BOM:\n" + pom);
        assertFalse(pom.contains("<extensions>"), "the parent loads the plugin as an extension:\n" + pom);
        assertFalse(pom.contains("maven.compiler.release"), "the parent sets the release:\n" + pom);
        assertFalse(pom.contains("sourceEncoding"), "the parent sets the encoding:\n" + pom);
    }

    /** The plugin gives a parentless build its executions from 0.4.0: an older runtime, or a release candidate, keeps the parent. */
    @Test
    void aRuntimeBeforeTheInjectionKeepsTheParentAndSaysSo() {
        for (String version : new String[] {"0.3.0", "0.4.0-RC1", "0.4.0-RC2"}) {
            RecordingReporter reporter = new RecordingReporter();
            ProjectSpec spec = new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of(), version, false, false,
                    false);

            String pom = ProjectScaffolder.buildPom(spec, RUNTIME, reporter);

            assertTrue(pom.contains("<artifactId>vidocq-runtime-parent</artifactId>\n        <version>" + version
                    + "</version>"), version + ":\n" + pom);
            assertTrue(reporter.lines.stream().anyMatch(l -> l.contains(version) && l.contains("vidocq-runtime-parent")),
                    version + ": " + reporter.lines);
        }
        assertTrue(ProjectScaffolder.pluginGivesTheBuild("0.4.0"));
        assertTrue(ProjectScaffolder.pluginGivesTheBuild("0.4.0-SNAPSHOT"));
        assertTrue(ProjectScaffolder.pluginGivesTheBuild("0.5.0"));
        assertFalse(ProjectScaffolder.pluginGivesTheBuild("0.4.0-RC2"));
        assertFalse(ProjectScaffolder.pluginGivesTheBuild("0.3.0"));
    }

    @Test
    void aSnapshotWithoutAParentDeclaresTheSnapshotRepositories() {
        String pom = ProjectScaffolder.buildPom(rest(false), "0.4.0-SNAPSHOT", new RecordingReporter());

        assertTrue(pom.contains("<vidocq.version>0.4.0-SNAPSHOT</vidocq.version>"), pom);
        assertTrue(pom.contains("<id>vidocq-snapshots</id>"), pom);
        assertTrue(pom.contains("<pluginRepositories>"), pom);
    }

    /** The dev services are wired on the plugin block as under the parent, which keeps its extension switch. */
    @Test
    void theDevServicesAreWiredOnThePluginBlockWithoutAParent() {
        ProjectSpec spec = new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("mansart-pool"), null, false,
                false, false);

        String pom = ProjectScaffolder.buildPom(spec, RUNTIME, new RecordingReporter());

        int plugin = pom.indexOf("<artifactId>vidocq-runtime-maven-plugin</artifactId>");
        int end = pom.indexOf("</plugin>", plugin);
        String block = pom.substring(plugin, end);
        assertTrue(block.contains("<extensions>true</extensions>"), block);
        assertTrue(block.contains("<artifactId>vidocq-runtime-devservice-postgres</artifactId>"), block);
        assertTrue(block.contains("<version>${vidocq.version}</version>"), block);
    }

    /** A project of its own version: the parent is not where it comes from. */
    @Test
    void theDefaultSpecHasNoParent() {
        ProjectSpec spec = new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of(), null, false);

        assertFalse(spec.withParent());
        assertEquals(spec, new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of(), null, false, false, false));
    }
}
