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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq extension add} must wire the extension's APT codegen bundle in
 * {@code annotationProcessorPaths} — {@code vidocq:checkpom} fails the build
 * otherwise (found by replaying the blog tutorial, BUG-20260711-01).
 */
class PomEditorAptTest {

    private static final ExtensionCoordinate CASSINI_CODEGEN = new ExtensionCoordinate(
            "io.vidocq.runtime.extensions.jakartaee.core",
            "vidocq-runtime-cassini-rest-extension-codegen");

    private static final String PATH_XML =
            "<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>";

    /** Shape produced by `vidocq create` without codegen extensions: build/plugins exists. */
    private static final String POM_WITH_BUILD = """
            <project>
                <artifactId>hello-world</artifactId>
                <dependencies>
                </dependencies>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    /** Shape produced by `vidocq create -x cassini-rest`: APT block already present. */
    private static final String POM_WITH_APT = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <annotationProcessorPaths combine.children="append">
                                <path>
                                    <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
                                    <artifactId>vidocq-runtime-mansart-data-extension-codegen</artifactId>
                                    <version>0.2.0</version>
                                    <type>pom</type>
                                </path>
                            </annotationProcessorPaths>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    private static final String POM_MINIMAL = """
            <project>
                <artifactId>bare</artifactId>
            </project>
            """;

    @Test
    void appendsPathToExistingAnnotationProcessorPaths() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed());
        assertTrue(r.pom().contains(PATH_XML), "path must be appended, pom was:\n" + r.pom());
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</annotationProcessorPaths>"),
                "path must sit inside annotationProcessorPaths, pom was:\n" + r.pom());
        assertTrue(r.pom().contains("<version>0.4.0</version>"),
                "the path pins the runtime version: ${project.version} is the application's own");
        assertFalse(r.pom().contains("${project.version}"));
    }

    /** Vidocq/vidocq#237: IntelliJ's Maven import ignores a processor path of type pom. */
    @Test
    void theAddedPathHasNoPomType() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_BUILD, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed());
        assertFalse(r.pom().contains("<type>pom</type>"), r.pom());
    }

    @Test
    void createsCompilerPluginBlockWhenAbsent() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_BUILD, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed());
        assertTrue(r.pom().contains("<artifactId>maven-compiler-plugin</artifactId>"),
                "a compiler plugin block must be created, pom was:\n" + r.pom());
        assertTrue(r.pom().contains("combine.children=\"append\""),
                "the created block must append to the parent's Vauban indexer entry");
        assertTrue(r.pom().contains(PATH_XML));
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</plugins>"),
                "the block must land inside build/plugins, pom was:\n" + r.pom());
    }

    @Test
    void createsBuildSectionWhenAbsent() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_MINIMAL, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed());
        assertTrue(r.pom().contains("<build>"), "a build section must be created, pom was:\n" + r.pom());
        assertTrue(r.pom().contains(PATH_XML));
    }

    @Test
    void isIdempotentWhenPathAlreadyPresent() {
        String once = PomEditor.addAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN, "0.4.0").pom();
        PomEditor.Result again = PomEditor.addAnnotationProcessorPath(once, CASSINI_CODEGEN, "0.4.0");

        assertFalse(again.changed(), "adding the same codegen path twice must be a no-op");
    }

    @Test
    void removesOnlyThePathOfTheGivenCodegen() {
        String both = PomEditor.addAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN, "0.4.0").pom();

        PomEditor.Result r = PomEditor.removeAnnotationProcessorPath(both, CASSINI_CODEGEN);

        assertTrue(r.changed());
        assertFalse(r.pom().contains(PATH_XML), "cassini's path must be gone, pom was:\n" + r.pom());
        assertTrue(r.pom().contains("vidocq-runtime-mansart-data-extension-codegen"),
                "the other codegen path must stay, pom was:\n" + r.pom());
        assertEquals(POM_WITH_APT, r.pom(), "removing restores the pom as it was before adding");
    }

    @Test
    void removingAnAbsentPathIsANoOp() {
        PomEditor.Result r = PomEditor.removeAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN);

        assertFalse(r.changed());
        assertEquals(POM_WITH_APT, r.pom());
    }

    @Test
    void removingTheLastPathDropsTheCompilerPluginBlockAddCreated() {
        String added = PomEditor.addAnnotationProcessorPath(POM_WITH_BUILD, CASSINI_CODEGEN, "0.4.0").pom();

        assertEquals(POM_WITH_BUILD, PomEditor.removeAnnotationProcessorPath(added, CASSINI_CODEGEN).pom());
    }

    @Test
    void removingTheLastPathKeepsACompilerPluginThatConfiguresMore() {
        String pom = POM_WITH_APT.replace("<configuration>", "<configuration>\n<release>25</release>");
        String added = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0").pom();
        String withoutMansart = PomEditor.removeAnnotationProcessorPath(added, new ExtensionCoordinate(
                "io.vidocq.runtime.extensions.jakartaee.web", "vidocq-runtime-mansart-data-extension-codegen")).pom();

        String removed = PomEditor.removeAnnotationProcessorPath(withoutMansart, CASSINI_CODEGEN).pom();

        assertTrue(removed.contains("<release>25</release>"), "pom was:\n" + removed);
        assertTrue(removed.contains("maven-compiler-plugin"), "pom was:\n" + removed);
    }

    /** An application that configures its compiler plugin, without any processor path yet. */
    private static final String POM_WITH_COMPILER = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <compilerArgs><arg>-Xlint:all</arg></compilerArgs>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    /** BUG-20261023-01: Maven warns on a second maven-compiler-plugin declaration. */
    @Test
    void fillsTheCompilerPluginAlreadyDeclared() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_COMPILER, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed());
        assertEquals(1, occurrences(r.pom(), "<artifactId>maven-compiler-plugin</artifactId>"), r.pom());
        assertTrue(r.pom().contains("<compilerArgs><arg>-Xlint:all</arg></compilerArgs>"), r.pom());
        assertTrue(r.pom().contains("<annotationProcessorPaths combine.children=\"append\">"), r.pom());
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</configuration>"), r.pom());
        assertEquals(POM_WITH_COMPILER, PomEditor.removeAnnotationProcessorPath(r.pom(), CASSINI_CODEGEN).pom(),
                "removing restores the pom as it was before adding");
    }

    @Test
    void fillsACompilerPluginThatHasNoConfiguration() {
        String pom = POM_WITH_COMPILER.replaceAll("(?s)<configuration>.*</configuration>", "<version>3.15.0</version>");

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertEquals(1, occurrences(r.pom(), "<artifactId>maven-compiler-plugin</artifactId>"), r.pom());
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</plugin>"), r.pom());
        assertEquals(pom, PomEditor.removeAnnotationProcessorPath(r.pom(), CASSINI_CODEGEN).pom());
    }

    /**
     * BUG-20261023-02: without combine.children="append", the application's paths replace the parent's
     * vidocq-runtime-core-codegen entry, and the Vauban indexer no longer runs.
     */
    @Test
    void makesTheProcessorPathsAppendToTheParentEntry() {
        String pom = POM_WITH_APT.replace(" combine.children=\"append\"", "");

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.pom().contains("<annotationProcessorPaths combine.children=\"append\">"), r.pom());
        assertTrue(r.pom().contains("vidocq-runtime-mansart-data-extension-codegen"), r.pom());
        assertTrue(r.pom().contains(PATH_XML), r.pom());
        assertFalse(PomEditor.dropsInheritedProcessorPaths(r.pom()), r.pom());
        assertTrue(PomEditor.dropsInheritedProcessorPaths(pom));
    }

    @Test
    void anOverridingProcessorPathsBlockAlsoDropsTheParentEntry() {
        String pom = POM_WITH_APT.replace("combine.children=\"append\"", "combine.self=\"override\"");

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertTrue(PomEditor.dropsInheritedProcessorPaths(pom));
        assertTrue(r.pom().contains("<annotationProcessorPaths combine.children=\"append\">"), r.pom());
        assertFalse(r.pom().contains("combine.self"), r.pom());
    }

    @Test
    void anApplicationWithoutProcessorPathsKeepsTheParentEntry() {
        assertFalse(PomEditor.dropsInheritedProcessorPaths(POM_WITH_BUILD));
        assertFalse(PomEditor.dropsInheritedProcessorPaths(POM_WITH_APT));
    }

    /** A profile, before build, whose compiler plugin declares processor paths of its own. */
    private static final String POM_WITH_PROFILE = """
            <project>
                <profiles>
                    <profile>
                        <id>lombok</id>
                        <build>
                            <plugins>
                                <plugin>
                                    <artifactId>maven-compiler-plugin</artifactId>
                                    <configuration>
                                        <annotationProcessorPaths combine.children="append">
                                            <path>
                                                <groupId>io.vidocq.runtime.extensions.jakartaee.core</groupId>
                                                <artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>
                                                <version>0.3.0</version>
                                            </path>
                                        </annotationProcessorPaths>
                                    </configuration>
                                </plugin>
                            </plugins>
                        </build>
                    </profile>
                </profiles>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    /** BUG-20261023-03: an inactive profile's processor paths do not reach the default build. */
    @Test
    void wiresTheProjectBuildRatherThanAProfile() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_PROFILE, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed(), "the profile's path does not count, pom was:\n" + r.pom());
        String profile = r.pom().substring(0, r.pom().indexOf("</profiles>"));
        String build = r.pom().substring(r.pom().indexOf("</profiles>"));
        assertEquals(POM_WITH_PROFILE.substring(0, POM_WITH_PROFILE.indexOf("</profiles>")), profile);
        assertTrue(build.contains(PATH_XML) && build.contains("<version>0.4.0</version>"), r.pom());
        assertEquals(POM_WITH_PROFILE, PomEditor.removeAnnotationProcessorPath(r.pom(), CASSINI_CODEGEN).pom(),
                "removing leaves the profile alone");
    }

    @Test
    void wiresTheProjectBuildRatherThanPluginManagement() {
        String pom = POM_WITH_PROFILE
                .replace("<profiles>\n        <profile>\n            <id>lombok</id>\n            <build>",
                        "<build>\n        <pluginManagement>")
                .replace("        </build>\n        </profile>\n    </profiles>\n    <build>",
                        "        </pluginManagement>");
        assertEquals(1, occurrences(pom, "<build>"), pom);

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.changed(), r.pom());
        String managed = r.pom().substring(0, r.pom().indexOf("</pluginManagement>"));
        assertEquals(pom.substring(0, pom.indexOf("</pluginManagement>")), managed);
        assertTrue(r.pom().lastIndexOf(PATH_XML) > r.pom().indexOf("</pluginManagement>"), r.pom());
    }

    @Test
    void addsPluginsToABuildThatHasNone() {
        String pom = """
                <project>
                    <build>
                        <finalName>app</finalName>
                    </build>
                </project>
                """;

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertEquals(1, occurrences(r.pom(), "<build>"), r.pom());
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</plugins>"), r.pom());
    }

    @Test
    void aCommentedOutCompilerPluginIsNotEdited() {
        String pom = POM_WITH_BUILD.replace("<plugins>", "<plugins>\n<!-- <plugin><artifactId>maven-compiler-plugin"
                + "</artifactId><configuration><annotationProcessorPaths></annotationProcessorPaths>"
                + "</configuration></plugin> -->");

        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(pom, CASSINI_CODEGEN, "0.4.0");

        assertTrue(r.pom().contains("<annotationProcessorPaths></annotationProcessorPaths>"
                + "</configuration></plugin> -->"), r.pom());
        assertTrue(r.pom().lastIndexOf(PATH_XML) > r.pom().indexOf("-->"), r.pom());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
