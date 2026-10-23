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
package io.vidocq.runtime.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq extension add} and {@code remove} on a project directory. */
class CommandRunnerExtensionTest {

    private static final String PARENT = """
              <parent>
                <groupId>io.vidocq.runtime</groupId>
                <artifactId>vidocq-runtime-parent</artifactId>
                <version>9.9.9</version>
              </parent>
            """;

    private static final String REPLACING_PATHS = """
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <annotationProcessorPaths>
                        <path>
                          <groupId>org.mapstruct</groupId>
                          <artifactId>mapstruct-processor</artifactId>
                          <version>1.6.3</version>
                        </path>
                      </annotationProcessorPaths>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            """;

    @TempDir
    Path project;

    @Test
    void addsTheExtensionItsCodegenPathAndItsModuleDirectives() throws IOException {
        writePom("");
        writeModuleInfo();

        Run run = run(List.of("cassini-rest"), true);

        assertEquals(0, run.exit());
        String pom = pom();
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"), pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>"), pom);
        assertTrue(pom.contains("<version>9.9.9</version>"), pom);
        assertTrue(run.out().contains("Wired vidocq-runtime-cassini-rest-extension-codegen"), run.out());
        assertTrue(run.out().contains("Added cassini-rest"), run.out());
        assertTrue(run.out().contains("pom.xml updated."), run.out());
        assertTrue(Files.readString(moduleInfo()).contains("requires jakarta.ws.rs;"));
    }

    @Test
    void addingTwiceLeavesThePomUntouched() throws IOException {
        writePom("");
        run(List.of("cassini-rest"), true);
        String once = pom();

        Run run = run(List.of("cassini-rest"), true);

        assertEquals(0, run.exit());
        assertEquals(once, pom());
        assertTrue(run.out().contains("cassini-rest already present"), run.out());
        assertTrue(run.out().contains("No changes"), run.out());
    }

    @Test
    void removeUndoesAdd() throws IOException {
        writePom("");
        String before = pom();
        run(List.of("cassini-rest"), true);

        Run run = run(List.of("cassini-rest"), false);

        assertEquals(0, run.exit());
        assertEquals(before, pom());
        assertTrue(run.out().contains("Unwired vidocq-runtime-cassini-rest-extension-codegen"), run.out());
        assertTrue(run.out().contains("Removed cassini-rest"), run.out());
    }

    @Test
    void removingAnAbsentExtensionWarns() throws IOException {
        writePom("");

        Run run = run(List.of("cassini-rest"), false);

        assertEquals(0, run.exit());
        assertTrue(run.out().contains("cassini-rest not found in pom.xml"), run.out());
    }

    @Test
    void anExtensionOutsideTheCatalogIsResolvedByConvention() throws IOException {
        writePom("");

        Run run = run(List.of("acme-thing"), true);

        assertEquals(0, run.exit());
        assertTrue(pom().contains("<artifactId>vidocq-runtime-acme-thing-extension</artifactId>"), pom());
        assertTrue(run.out().contains("not a known extension"), run.out());
    }

    /** BUG-20261023-02: wiring a codegen bundle makes the block append to the parent's. */
    @Test
    void wiringACodegenBundleMakesReplacingPathsAppend() throws IOException {
        writePom(REPLACING_PATHS);

        Run run = run(List.of("cassini-rest"), true);

        assertEquals(0, run.exit());
        assertTrue(pom().contains("<annotationProcessorPaths combine.children=\"append\">"), pom());
        assertTrue(run.out().contains("now appends to the parent's"), run.out());
    }

    /** BUG-20261023-02: an extension without a codegen bundle leaves the block as is, and says so. */
    @Test
    void addingAnExtensionWithoutCodegenWarnsAboutReplacingPaths() throws IOException {
        writePom(REPLACING_PATHS);

        Run run = run(List.of("knock-health"), true);

        assertEquals(0, run.exit());
        assertFalse(pom().contains("combine.children"), pom());
        assertTrue(run.out().contains("drops the Vauban indexer"), run.out());
    }

    @Test
    void aBlankIdFails() throws IOException {
        writePom("");
        String before = pom();

        Run run = run(List.of(" "), true);

        assertEquals(1, run.exit());
        assertEquals(before, pom());
        assertTrue(run.out().contains("Invalid extension id"), run.out());
    }

    @Test
    void aDirectoryWithoutPomFails() {
        Run run = run(List.of("cassini-rest"), true);

        assertEquals(1, run.exit());
        assertTrue(run.out().contains("No pom.xml"), run.out());
    }

    private record Run(int exit, String out) {}

    /** Runs the command, with what it prints on stdout and stderr together. */
    private Run run(List<String> ids, boolean add) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        int exit;
        try {
            exit = CommandRunner.editPom(project, ids, add);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    private void writePom(String build) throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                """ + PARENT + """
                  <groupId>com.acme</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                  </dependencies>
                """ + build + """
                </project>
                """);
    }

    private void writeModuleInfo() throws IOException {
        Files.createDirectories(moduleInfo().getParent().resolve("com/acme/demo"));
        Files.writeString(moduleInfo(), """
                module com.acme.demo {
                    requires io.vidocq.runtime.core;
                }
                """);
    }

    private String pom() throws IOException {
        return Files.readString(project.resolve("pom.xml"));
    }

    private Path moduleInfo() {
        return project.resolve("src/main/java/module-info.java");
    }
}
