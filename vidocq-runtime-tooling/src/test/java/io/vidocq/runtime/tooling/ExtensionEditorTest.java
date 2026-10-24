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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adding and removing extensions on a project directory, as the CLI and the Maven plugin both do. */
class ExtensionEditorTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.vidocq.runtime</groupId>
                <artifactId>vidocq-runtime-parent</artifactId>
                <version>9.9.9</version>
              </parent>
              <artifactId>demo</artifactId>
            %s</project>
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

    /** The Vauban indexer, as an application without a Vidocq parent lists it, before its first path. */
    private static final String INDEXER_THEN_PATH = "<path><groupId>io.vidocq.runtime</groupId>"
            + "<artifactId>vidocq-runtime-core-codegen</artifactId><version>0.4.0</version></path>\n<path>";

    @TempDir
    Path project;

    private final RecordingReporter reporter = new RecordingReporter();

    @Test
    void addWritesTheDependencyTheCodegenPathAndTheModuleDirectives() throws IOException {
        writePom("");
        writeModuleInfo();

        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest"), true);

        assertEquals(new ExtensionEditor.Outcome(true, true, true), outcome);
        String pom = pom();
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"), pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>"), pom);
        assertEquals(1, pom.split("<version>").length - 1, "the parent's version only, the BOM gives the rest:\n" + pom);
        assertTrue(Files.readString(moduleInfo()).contains("requires jakarta.ws.rs;"));
        assertTrue(reporter.lines.contains(
                "success: Added cassini-rest (io.vidocq.runtime.extensions.jakartaee.core:vidocq-runtime-cassini-rest-extension)"),
                reporter.lines.toString());
        assertTrue(reporter.lines.contains(
                "success: Wired vidocq-runtime-cassini-rest-extension-codegen (annotationProcessorPaths)"),
                reporter.lines.toString());
    }

    @Test
    void removeUndoesAdd() throws IOException {
        writePom("");
        writeModuleInfo();
        String pom = pom();
        String moduleInfo = Files.readString(moduleInfo());
        edit(List.of("cassini-rest"), true);

        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest"), false);

        assertEquals(new ExtensionEditor.Outcome(true, true, true), outcome);
        assertEquals(pom, pom());
        assertEquals(moduleInfo, Files.readString(moduleInfo()));
        assertTrue(reporter.lines.contains("success: Removed cassini-rest"
                + " (io.vidocq.runtime.extensions.jakartaee.core:vidocq-runtime-cassini-rest-extension)"));
        assertTrue(reporter.lines.contains(
                "success: Unwired vidocq-runtime-cassini-rest-extension-codegen (annotationProcessorPaths)"));
    }

    @Test
    void addingTwiceChangesNothing() throws IOException {
        writePom("");
        edit(List.of("cassini-rest"), true);
        String once = pom();

        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest"), true);

        assertEquals(new ExtensionEditor.Outcome(true, false, false), outcome);
        assertEquals(once, pom());
        assertTrue(reporter.lines.contains("note: • cassini-rest already present — skipped."), reporter.lines.toString());
    }

    @Test
    void removingAnAbsentExtensionWarns() throws IOException {
        writePom("");

        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest"), false);

        assertEquals(new ExtensionEditor.Outcome(true, false, false), outcome);
        assertTrue(reporter.lines.contains("warning: cassini-rest not found in pom.xml — skipped."));
    }

    /** BUG-20261024-04: an unknown id used to become a coordinate that does not exist. */
    @Test
    void anIdOutsideTheCatalogFailsWritesNothingAndNamesTheClosestIds() throws IOException {
        writePom("");
        String before = pom();

        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest", "mansart-persistence"), true);

        assertFalse(outcome.succeeded());
        assertEquals(before, pom());
        assertTrue(reporter.lines.contains("error: Invalid extension id 'mansart-persistence': not in the Vidocq"
                + " catalog. Did you mean: mansart-data, mansart-pool, mansart-transactions? An extension outside"
                + " the catalog is added as groupId:artifactId."), reporter.lines.toString());
    }

    @Test
    void anExplicitCoordinateOutsideTheCatalogIsAddedAsGiven() throws IOException {
        writePom("");

        edit(List.of("com.acme:acme-thing"), true);

        assertTrue(pom().contains("<groupId>com.acme</groupId>"), pom());
        assertTrue(pom().contains("<artifactId>acme-thing</artifactId>"), pom());
        assertTrue(reporter.lines.contains("note: ↳ not in the Vidocq catalog — added as given; verify the coordinate."),
                reporter.lines.toString());
    }

    /** BUG-20261024-02: a block that lists the Vauban indexer itself drops nothing, so nothing to warn about. */
    @Test
    void replacingPathsThatListTheIndexerDoNotWarn() throws IOException {
        writePom(REPLACING_PATHS.replaceFirst("<path>", INDEXER_THEN_PATH));

        edit(List.of("knock-health"), true);

        assertFalse(reporter.lines.stream().anyMatch(l -> l.contains("drops the Vauban indexer")),
                reporter.lines.toString());
    }

    /** BUG-20261023-02: wiring a codegen bundle makes a replacing block append to the parent's. */
    @Test
    void wiringACodegenBundleMakesReplacingPathsAppend() throws IOException {
        writePom(REPLACING_PATHS);

        edit(List.of("cassini-rest"), true);

        assertTrue(pom().contains("<annotationProcessorPaths combine.children=\"append\">"), pom());
        assertTrue(reporter.lines.stream().anyMatch(l -> l.startsWith(
                "success: annotationProcessorPaths now appends to the parent's")), reporter.lines.toString());
    }

    /** BUG-20261023-02: an extension without a codegen bundle leaves a replacing block as is, and says so. */
    @Test
    void anExtensionWithoutCodegenWarnsAboutReplacingPaths() throws IOException {
        writePom(REPLACING_PATHS);

        edit(List.of("knock-health"), true);

        assertFalse(pom().contains("combine.children"), pom());
        assertTrue(reporter.lines.stream().anyMatch(l -> l.startsWith("warning:") && l.contains("drops the Vauban indexer")),
                reporter.lines.toString());
    }

    /** Before 0.4.0 the parent managed the runtime at {@code ${project.version}} (BUG-20261006-02). */
    @Test
    void underAParentBefore040TheParentVersionIsPinned() throws IOException {
        writePom("");
        Files.writeString(project.resolve("pom.xml"), pom().replace("9.9.9", "0.3.0"));

        edit(List.of("cassini-rest"), true);

        assertTrue(pom().contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>\n"
                + "            <version>0.3.0</version>"), pom());
        assertTrue(pom().contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>\n"
                + "                            <version>0.3.0</version>"), pom());
    }

    /** Vidocq/vidocq#339: an application that keeps a parent of its own and imports the BOM gets no version. */
    @Test
    void aPomThatImportsTheBomGetsNoVersion() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <artifactId>demo</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-bom</artifactId>
                        <version>0.4.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        edit(List.of("knock-health"), true);

        assertEquals(1, pom().split("<version>").length - 1, pom());
    }

    @Test
    void theDefaultRuntimeVersionPinsWhatTheParentDoesNotGive() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project>\n  <artifactId>demo</artifactId>\n</project>\n");

        edit(List.of("knock-health"), true);

        assertTrue(pom().contains("<version>1.2.3</version>"), pom());
    }

    @Test
    void aBlankIdFailsAndWritesNothing() throws IOException {
        writePom("");
        String before = pom();

        ExtensionEditor.Outcome outcome = edit(List.of(" "), true);

        assertFalse(outcome.succeeded());
        assertEquals(before, pom());
        assertTrue(reporter.lines.stream().anyMatch(l -> l.startsWith("error: Invalid extension id")),
                reporter.lines.toString());
    }

    @Test
    void aDirectoryWithoutPomFails() {
        ExtensionEditor.Outcome outcome = edit(List.of("cassini-rest"), true);

        assertFalse(outcome.succeeded());
        assertTrue(reporter.lines.stream().anyMatch(l -> l.startsWith("error: No pom.xml in ")),
                reporter.lines.toString());
    }

    @Test
    void anApplicationPackageWithoutClassIsNotOpenedAndTheUserIsTold() throws IOException {
        writePom("");
        writeModuleInfo();
        Files.createDirectories(project.resolve("src/main/java/com/acme/demo"));

        edit(List.of("cassini-rest"), true);

        assertFalse(Files.readString(moduleInfo()).contains("opens com.acme.demo"));
        assertTrue(reporter.lines.stream().anyMatch(l -> l.startsWith("warning:") && l.contains("has no class yet")
                && l.contains("opens com.acme.demo;")), reporter.lines.toString());
    }

    @Test
    void anApplicationPackageWithAClassIsOpened() throws IOException {
        writePom("");
        writeModuleInfo();
        Path pkg = Files.createDirectories(project.resolve("src/main/java/com/acme/demo"));
        Files.writeString(pkg.resolve("HelloResource.java"), "package com.acme.demo; class HelloResource {}");

        edit(List.of("cassini-rest"), true);

        assertTrue(Files.readString(moduleInfo()).contains("opens com.acme.demo;"));
    }

    private ExtensionEditor.Outcome edit(List<String> ids, boolean add) {
        return ExtensionEditor.edit(project, ids, add, "1.2.3", reporter);
    }

    private void writePom(String build) throws IOException {
        Files.writeString(project.resolve("pom.xml"), POM.formatted(build));
    }

    private void writeModuleInfo() throws IOException {
        Files.createDirectories(moduleInfo().getParent());
        Files.writeString(moduleInfo(), "module com.acme.demo {\n}\n");
    }

    private Path moduleInfo() {
        return project.resolve("src/main/java/module-info.java");
    }

    private String pom() throws IOException {
        return Files.readString(project.resolve("pom.xml"));
    }
}
