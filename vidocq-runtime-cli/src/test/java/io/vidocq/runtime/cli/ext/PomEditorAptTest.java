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
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN);

        assertTrue(r.changed());
        assertTrue(r.pom().contains(PATH_XML), "path must be appended, pom was:\n" + r.pom());
        assertTrue(r.pom().indexOf(PATH_XML) < r.pom().indexOf("</annotationProcessorPaths>"),
                "path must sit inside annotationProcessorPaths, pom was:\n" + r.pom());
        assertTrue(r.pom().contains("<version>${project.version}</version>"),
                "APT paths use ${project.version} like the canonical example pom");
    }

    @Test
    void createsCompilerPluginBlockWhenAbsent() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_WITH_BUILD, CASSINI_CODEGEN);

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
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM_MINIMAL, CASSINI_CODEGEN);

        assertTrue(r.changed());
        assertTrue(r.pom().contains("<build>"), "a build section must be created, pom was:\n" + r.pom());
        assertTrue(r.pom().contains(PATH_XML));
    }

    @Test
    void isIdempotentWhenPathAlreadyPresent() {
        String once = PomEditor.addAnnotationProcessorPath(POM_WITH_APT, CASSINI_CODEGEN).pom();
        PomEditor.Result again = PomEditor.addAnnotationProcessorPath(once, CASSINI_CODEGEN);

        assertFalse(again.changed(), "adding the same codegen path twice must be a no-op");
    }
}
