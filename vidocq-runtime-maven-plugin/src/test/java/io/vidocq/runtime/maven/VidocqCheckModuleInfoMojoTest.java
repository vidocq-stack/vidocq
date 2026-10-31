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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.codegen.commons.Requirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261031-02: after a compilation error under {@code vidocq dev}, {@code target/classes} holds no
 * {@code module-info.class}, and the goal said the module had no {@code module-info.java} although its source
 * declared the directive it asked for.
 */
class VidocqCheckModuleInfoMojoTest {

    private static final Set<Requirement> FLYWAY = Set.of(Requirement.opens("db.migration", "Flyway"));

    @Test
    void aModuleInfoSourceIsFoundInTheCompileSourceRoots(@TempDir Path dir) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/main/java"));
        Path generated = Files.createDirectories(dir.resolve("target/generated-sources/annotations"));

        assertFalse(VidocqCheckModuleInfoMojo.hasModuleInfoSource(List.of(sources.toString(), generated.toString())));

        Files.writeString(sources.resolve("module-info.java"), "module demo {\n}\n");

        assertTrue(VidocqCheckModuleInfoMojo.hasModuleInfoSource(List.of(generated.toString(), sources.toString())));
    }

    @Test
    void withAModuleInfoSourceButNoClassTheCompilationIsBlamed() {
        List<String> issues = VidocqCheckModuleInfoMojo.uncompiledIssues(FLYWAY, true);

        assertTrue(issues.isEmpty(), issues.toString());
        assertTrue(VidocqCheckModuleInfoMojo.UNCOMPILED.contains("compil"), VidocqCheckModuleInfoMojo.UNCOMPILED);
        assertFalse(VidocqCheckModuleInfoMojo.UNCOMPILED.contains("no module-info.java"),
                VidocqCheckModuleInfoMojo.UNCOMPILED);
    }

    @Test
    void withoutAModuleInfoSourceTheDirectivesAreAskedForInPlainWords() {
        List<String> issues = VidocqCheckModuleInfoMojo.uncompiledIssues(FLYWAY, false);

        assertTrue(issues.getFirst().contains("no module-info.java"), issues.toString());
        assertTrue(issues.getFirst().contains("Java module"), issues.toString());
        assertTrue(issues.stream().anyMatch(i -> i.contains("opens db.migration")), issues.toString());
        assertFalse(String.join("\n", issues).contains("JPMS"), issues.toString());
    }

    @Test
    void withoutRequirementsAModuleWithoutModuleInfoIsLeftAlone() {
        assertTrue(VidocqCheckModuleInfoMojo.uncompiledIssues(Set.of(), false).isEmpty());
    }

    /** BUG-20261031-10: a {@code vidocq:dev} in the goals, or the recompile's property, puts the check in dev mode. */
    @Test
    void theCheckRunsInDevModeUnderVidocqDevOrItsRecompile() {
        assertTrue(VidocqCheckModuleInfoMojo.underDev(List.of("process-classes", "vidocq:dev"), false));
        assertTrue(VidocqCheckModuleInfoMojo.underDev(
                List.of("io.vidocq.runtime:vidocq-runtime-maven-plugin:0.4.0-SNAPSHOT:dev"), false));
        assertTrue(VidocqCheckModuleInfoMojo.underDev(List.of("process-classes"), true));
        assertFalse(VidocqCheckModuleInfoMojo.underDev(List.of("package"), false));
        assertFalse(VidocqCheckModuleInfoMojo.underDev(List.of("vidocq:devservices"), false));
    }

    /**
     * BUG-20261031-10: outside dev the failure names the commands that add the lines, the wrapper included, and no
     * longer speaks of a first request: the build fails before any.
     */
    @Test
    void theUnopenedPackagesNameTheCommandsThatOpenThem() {
        List<String> issues = VidocqCheckModuleInfoMojo.unopenedIssues("com.example.lib",
                List.of("com.example.lib.book"), Map.of("com.example.lib.book", "com.example.lib.book.Book, a JPA entity"));

        String text = String.join("\n", issues);
        assertTrue(text.contains("vidocq doctor --fix"), text);
        assertTrue(text.contains("./mvnw vidocq:complete-module-info"), text);
        assertFalse(text.contains("first request"), text);
        assertFalse(text.contains("`mvn "), text);
        assertTrue(text.contains("opens com.example.lib.book;   (com.example.lib.book.Book, a JPA entity)"), text);
    }

    /** BUG-20261031-10: under dev the package is opened in the source and in the compiled descriptor, and printed. */
    @Test
    void underDevThePackageIsOpenedAndPrinted(@TempDir Path dir) throws Exception {
        Path sources = Files.createDirectories(dir.resolve("src/main/java"));
        Path source = Files.writeString(sources.resolve("module-info.java"), "module demo {\n}\n");
        Path compiled = Files.write(dir.resolve("module-info.class"), java.lang.classfile.ClassFile.of().buildModule(
                java.lang.classfile.attribute.ModuleAttribute.of(java.lang.constant.ModuleDesc.of("demo"), mb -> mb
                        .requires(java.lang.constant.ModuleDesc.of("java.base"),
                                java.lang.classfile.ClassFile.ACC_MANDATED, null))));
        List<String> printed = new java.util.ArrayList<>();

        List<String> left = VidocqCheckModuleInfoMojo.openUnderDev(
                VidocqCheckModuleInfoMojo.moduleInfoSource(List.of(sources.toString())), compiled.toFile(),
                List.of("demo.book"), Map.of("demo.book", "demo.book.Book, a JPA entity"), printed::add);

        assertTrue(left.isEmpty(), left.toString());
        assertTrue(Files.readString(source).contains("opens demo.book;"), Files.readString(source));
        assertEquals(List.of("✔ module-info.java: opens demo.book;   (demo.book.Book, a JPA entity)"), printed);
        try (var in = Files.newInputStream(compiled)) {
            assertTrue(java.lang.module.ModuleDescriptor.read(in).opens().stream()
                    .anyMatch(o -> o.source().equals("demo.book")));
        }
    }

    /** Without a {@code module-info.java} to write to, the packages stay for the report. */
    @Test
    void underDevWithoutASourceThePackagesAreReported() throws Exception {
        assertEquals(List.of("demo.book"), VidocqCheckModuleInfoMojo.openUnderDev(null, new java.io.File("x"),
                List.of("demo.book"), Map.of(), line -> { }));
    }

    /** A file that is no module descriptor fails the goal with the source it could not complete. */
    @Test
    void underDevABrokenDescriptorFailsTheGoal(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("module-info.java"), "module demo {\n}\n");
        Path compiled = Files.write(dir.resolve("module-info.class"), new byte[] {1, 2, 3});

        org.junit.jupiter.api.Assertions.assertThrows(org.apache.maven.plugin.MojoFailureException.class,
                () -> VidocqCheckModuleInfoMojo.openUnderDev(source.toFile(), compiled.toFile(), List.of("demo.book"),
                        Map.of(), line -> { }));
        assertEquals("module demo {\n}\n", Files.readString(source));
    }
}
