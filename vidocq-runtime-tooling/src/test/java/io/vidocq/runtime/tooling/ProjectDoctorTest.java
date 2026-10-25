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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#339 (evolution 3): {@code vidocq doctor} checks the project as well as the machine — codegen
 * bundles, the Vauban indexer, the {@code module-info.java} directives, Maven, the HTTP port — and {@code --fix}
 * repairs what the tooling knows how to.
 */
class ProjectDoctorTest {

    private static final String RUNTIME = "0.4.0";
    private static final String CODEGEN = "vidocq-runtime-cassini-rest-extension-codegen";

    /** A parent before 0.4.0, whose projects wire their codegen bundles themselves. */
    private static final String OLD_PARENT = "0.3.0";

    private static Path create(Path dir, Set<String> extensions, boolean main) throws IOException {
        return create(dir, extensions, main, null);
    }

    private static Path create(Path dir, Set<String> extensions, boolean main, String parentVersion)
            throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", extensions, parentVersion,
                main), dir, RUNTIME, new RecordingReporter());
        return dir.resolve("demo");
    }

    private static Map<String, Diagnostic> check(Path project) {
        return byName(ProjectDoctor.check(ProjectDoctor.read(project, port -> true)));
    }

    private static Map<String, Diagnostic> byName(List<Diagnostic> diagnostics) {
        return diagnostics.stream().collect(Collectors.toMap(Diagnostic::name, Function.identity()));
    }

    private static void edit(Path file, String from, String to) throws IOException {
        String text = Files.readString(file);
        assertTrue(text.contains(from), from + " not in:\n" + text);
        Files.writeString(file, text.replace(from, to));
    }

    // -------------------------------------------------------------------------
    // A created project
    // -------------------------------------------------------------------------

    /** What {@code vidocq create} writes is healthy: every check of {@code vidocq doctor} passes, none warns. */
    @Test
    void aCreatedProjectReportsNoWarning(@TempDir Path dir) throws IOException {
        for (var extensions : List.of(Set.of("cassini-rest"), Set.<String>of())) {
            Path project = create(dir.resolve(extensions.isEmpty() ? "plain" : "rest"), extensions,
                    extensions.isEmpty());
            DoctorContext environment = DoctorFacts.gather(project, System.getProperty("java.home"),
                    () -> DoctorFacts.Extensions.found(extensions.size()));

            List<Diagnostic> all = Stream.concat(Diagnostics.run(environment).stream(),
                    ProjectDoctor.check(ProjectDoctor.read(project, port -> true)).stream()).toList();

            assertEquals(List.of(), all.stream().filter(d -> d.status() != Diagnostic.Status.OK).toList(),
                    extensions.toString());
        }
    }

    @Test
    void theProjectChecksAreNamed(@TempDir Path dir) throws IOException {
        assertEquals(Set.of("Codegen bundles", "Vauban indexer", "Module directives", "Maven version",
                "HTTP port"), check(create(dir, Set.of("cassini-rest"), false)).keySet());
    }

    @Test
    void outsideAVidocqProjectThereIsNothingToCheck(@TempDir Path dir) throws IOException {
        assertNull(ProjectDoctor.read(dir, port -> true));
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>other</artifactId></project>");
        assertNull(ProjectDoctor.read(dir, port -> true));
        assertEquals(List.of(), ProjectDoctor.check(null));
    }

    // -------------------------------------------------------------------------
    // Codegen bundles
    // -------------------------------------------------------------------------

    @Test
    void aMissingCodegenBundleFailsAndIsFixed(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false, OLD_PARENT);
        Path pom = project.resolve("pom.xml");
        removePath(pom, CODEGEN);

        Diagnostic codegen = check(project).get("Codegen bundles");
        assertEquals(Diagnostic.Status.FAIL, codegen.status());
        assertTrue(codegen.detail().contains("cassini-rest: " + CODEGEN), codegen.detail());
        assertTrue(codegen.fixable());

        RecordingReporter reporter = new RecordingReporter();
        assertTrue(ProjectDoctor.fix(project, RUNTIME, reporter));

        assertEquals(Diagnostic.Status.OK, check(project).get("Codegen bundles").status());
        assertTrue(Files.readString(pom).contains("<artifactId>" + CODEGEN + "</artifactId>"));
        // Before 0.4.0 no BOM versions it: the path carries the parent's runtime version.
        String fixed = Files.readString(pom);
        int at = fixed.indexOf("<artifactId>" + CODEGEN + "</artifactId>");
        assertTrue(fixed.substring(at, fixed.indexOf("</path>", at)).contains("<version>" + OLD_PARENT + "</version>"),
                fixed);
        assertTrue(reporter.lines.stream().anyMatch(l -> l.contains("Wired " + CODEGEN)), reporter.lines.toString());
    }

    /** From 0.4.0 the plugin, a build extension, puts the bundles on the processor path itself (#339). */
    @Test
    void underAParentFrom040TheCodegenBundlesAreThePluginsJob(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        Path pom = project.resolve("pom.xml");
        removePath(pom, CODEGEN);
        edit(pom, "<annotationProcessorPaths combine.children=\"append\">", "<annotationProcessorPaths>");
        String before = Files.readString(pom);

        Map<String, Diagnostic> checks = check(project);
        assertEquals(Diagnostic.Status.OK, checks.get("Codegen bundles").status());
        assertTrue(checks.get("Codegen bundles").detail().contains("wired by vidocq-runtime-maven-plugin"));
        assertEquals(Diagnostic.Status.OK, checks.get("Vauban indexer").status());

        assertFalse(ProjectDoctor.fix(project, RUNTIME, new RecordingReporter()));
        assertEquals(before, Files.readString(pom));
    }

    @Test
    void thePluginWiresTheCodegenUnderA040ParentOrAsABuildExtension() {
        String parent = "<project><parent><groupId>io.vidocq.runtime</groupId>"
                + "<artifactId>vidocq-runtime-parent</artifactId><version>%s</version></parent>%s</project>";
        String plugin = "<build><plugins><plugin><groupId>io.vidocq.runtime</groupId>"
                + "<artifactId>vidocq-runtime-maven-plugin</artifactId>%s</plugin></plugins></build>";
        assertTrue(PomDependencies.codegenWiredByPlugin(parent.formatted("0.4.0", "")));
        assertTrue(PomDependencies.codegenWiredByPlugin(parent.formatted("0.4.0-SNAPSHOT", "")));
        assertFalse(PomDependencies.codegenWiredByPlugin(parent.formatted("0.3.0", "")));
        assertFalse(PomDependencies.codegenWiredByPlugin(parent.formatted("0.3.0", plugin.formatted(""))));
        assertTrue(PomDependencies.codegenWiredByPlugin(
                "<project>" + plugin.formatted("<extensions> true </extensions>") + "</project>"));
        assertFalse(PomDependencies.codegenWiredByPlugin("<project>" + plugin.formatted("") + "</project>"));
    }

    @Test
    void aCodegenBundleWithoutItsExtensionWarnsAndIsRemoved(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        Path pom = project.resolve("pom.xml");
        removeDependency(pom, "vidocq-runtime-cassini-rest-extension");

        Diagnostic codegen = check(project).get("Codegen bundles");
        assertEquals(Diagnostic.Status.WARN, codegen.status());
        assertTrue(codegen.detail().contains(CODEGEN + " without its extension"), codegen.detail());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        assertFalse(Files.readString(pom).contains(CODEGEN), Files.readString(pom));
        assertEquals(Diagnostic.Status.OK, check(project).get("Codegen bundles").status());
    }

    // -------------------------------------------------------------------------
    // Vauban indexer
    // -------------------------------------------------------------------------

    /** Processor paths that replace the parent's drop the Vauban indexer (BUG-20261023-02). */
    @Test
    void processorPathsThatReplaceTheParentsFailAndAreMadeToAppend(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false, OLD_PARENT);
        Path pom = project.resolve("pom.xml");
        edit(pom, "<annotationProcessorPaths combine.children=\"append\">", "<annotationProcessorPaths>");

        Diagnostic indexer = check(project).get("Vauban indexer");
        assertEquals(Diagnostic.Status.FAIL, indexer.status());
        assertTrue(indexer.fixable());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        assertTrue(Files.readString(pom).contains("<annotationProcessorPaths combine.children=\"append\">"));
        assertFalse(Files.readString(pom).contains("<artifactId>vidocq-runtime-core-codegen</artifactId>"),
                "the parent's entry is kept, not copied");
        assertEquals(Diagnostic.Status.OK, check(project).get("Vauban indexer").status());
    }

    /** An application that keeps its own parent declares the indexer itself (#47). */
    @Test
    void withoutTheVidocqParentTheIndexerMustBeDeclared(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        Path pom = project.resolve("pom.xml");
        String text = Files.readString(pom);
        int start = text.indexOf("<parent>");
        int end = text.indexOf("</parent>") + "</parent>".length();
        Files.writeString(pom, text.substring(0, start) + "<parent>\n        <groupId>com.acme</groupId>\n"
                + "        <artifactId>acme-parent</artifactId>\n        <version>1</version>\n    </parent>"
                + text.substring(end));

        Diagnostic indexer = check(project).get("Vauban indexer");
        assertEquals(Diagnostic.Status.FAIL, indexer.status());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        String fixed = Files.readString(pom);
        assertTrue(fixed.contains("<artifactId>vidocq-runtime-core-codegen</artifactId>\n"), fixed);
        assertTrue(fixed.contains("<version>" + RUNTIME + "</version>"), "no BOM: the path is versioned\n" + fixed);
        assertEquals(Diagnostic.Status.OK, check(project).get("Vauban indexer").status());
    }

    // -------------------------------------------------------------------------
    // Module directives
    // -------------------------------------------------------------------------

    @Test
    void aMissingRequiresFailsAndIsAddedWithItsMarker(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        Path moduleInfo = project.resolve("src/main/java/module-info.java");
        String text = Files.readString(moduleInfo);
        Files.writeString(moduleInfo, text.lines().filter(l -> !l.contains("jakarta.ws.rs"))
                .collect(Collectors.joining("\n", "", "\n")));

        Diagnostic directives = check(project).get("Module directives");
        assertEquals(Diagnostic.Status.FAIL, directives.status());
        assertTrue(directives.detail().contains("cassini-rest: requires jakarta.ws.rs"), directives.detail());
        assertTrue(directives.fixable());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        assertTrue(Files.readString(moduleInfo).contains("requires jakarta.ws.rs; // vidocq:cassini-rest"),
                Files.readString(moduleInfo));
        assertEquals(Diagnostic.Status.OK, check(project).get("Module directives").status());
    }

    @Test
    void aProjectWithoutModuleInfoIsWarned(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of(), true);
        Files.delete(project.resolve("src/main/java/module-info.java"));

        Diagnostic directives = check(project).get("Module directives");
        assertEquals(Diagnostic.Status.WARN, directives.status());
        assertFalse(directives.fixable());
    }

    // -------------------------------------------------------------------------
    // Maven
    // -------------------------------------------------------------------------

    @Test
    void anOlderMavenThanThePinnedOneWarnsAndIsUpgraded(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of(), true);
        Path properties = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        edit(properties, ProjectDoctor.PINNED_MAVEN_VERSION, "3.9.9");

        Diagnostic maven = check(project).get("Maven version");
        assertEquals(Diagnostic.Status.WARN, maven.status());
        assertTrue(maven.detail().contains("3.9.9"), maven.detail());
        assertTrue(maven.detail().contains(ProjectDoctor.PINNED_MAVEN_VERSION), maven.detail());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        assertTrue(Files.readString(properties).contains("apache-maven-" + ProjectDoctor.PINNED_MAVEN_VERSION
                + "-bin.zip"), Files.readString(properties));
        assertEquals(Diagnostic.Status.OK, check(project).get("Maven version").status());
    }

    @Test
    void aNewerMavenIsFine(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of(), true);
        edit(project.resolve(".mvn/wrapper/maven-wrapper.properties"), ProjectDoctor.PINNED_MAVEN_VERSION, "3.10.0");

        assertEquals(Diagnostic.Status.OK, check(project).get("Maven version").status());
    }

    @Test
    void aMissingWrapperIsWritten(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of(), true);
        Files.delete(project.resolve("mvnw"));
        Files.delete(project.resolve("mvnw.cmd"));
        Files.delete(project.resolve(".mvn/wrapper/maven-wrapper.properties"));

        assertFalse(check(project).containsKey("Maven version"), "no wrapper, no version to compare");
        DoctorContext environment = DoctorFacts.gather(project, null, () -> DoctorFacts.Extensions.found(0));
        Diagnostic wrapper = byName(Diagnostics.run(environment)).get("Maven wrapper");
        assertEquals(Diagnostic.Status.WARN, wrapper.status());
        assertTrue(wrapper.fixable());

        ProjectDoctor.fix(project, RUNTIME, new RecordingReporter());

        assertTrue(Files.isExecutable(project.resolve("mvnw")));
        assertTrue(Files.isRegularFile(project.resolve(".mvn/wrapper/maven-wrapper.properties")));
    }

    @Test
    void mavenVersionsCompareByNumber() {
        assertTrue(ProjectDoctor.olderThan("3.9.9", "3.9.16"));
        assertFalse(ProjectDoctor.olderThan("3.9.16", "3.9.16"));
        assertFalse(ProjectDoctor.olderThan("4.0.0-rc-4", "3.9.16"));
        assertTrue(ProjectDoctor.olderThan("3.8", "3.9.16"));
    }

    // -------------------------------------------------------------------------
    // HTTP port
    // -------------------------------------------------------------------------

    @Test
    void aTakenHttpPortWarnsWithTheKeyThatMovesIt(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);

        Diagnostic port = byName(ProjectDoctor.check(ProjectDoctor.read(project, p -> p != 8080))).get("HTTP port");

        assertEquals(Diagnostic.Status.WARN, port.status());
        assertTrue(port.detail().contains("8080"), port.detail());
        assertTrue(port.hint().contains("vidocq.http.port"), port.hint());
        assertFalse(port.fixable());
    }

    @Test
    void aBoundPortIsNotFree() throws IOException {
        try (var taken = new java.net.ServerSocket(0)) {
            assertFalse(DoctorFacts.portFree(taken.getLocalPort()));
        }
    }

    @Test
    void theConfiguredPortIsTheOneChecked(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        Path config = project.resolve("src/main/resources/vidocq.properties");
        edit(config, "vidocq.http.port=8080", "vidocq.http.port=9090");
        assertEquals(9090, ProjectDoctor.read(project, p -> true).httpPort());

        Files.writeString(config, Files.readString(config) + "vidocq.chappe.listener.default.port=9191\n");
        assertEquals(9191, ProjectDoctor.read(project, p -> true).httpPort(), "the explicit listener key wins");
    }

    // -------------------------------------------------------------------------
    // Fix
    // -------------------------------------------------------------------------

    @Test
    void aHealthyProjectNeedsNoFix(@TempDir Path dir) throws IOException {
        Path project = create(dir, Set.of("cassini-rest"), false);
        String pom = Files.readString(project.resolve("pom.xml"));

        assertFalse(ProjectDoctor.fix(project, RUNTIME, new RecordingReporter()));
        assertEquals(pom, Files.readString(project.resolve("pom.xml")));
    }

    private static void removePath(Path pom, String artifactId) throws IOException {
        String text = Files.readString(pom);
        int at = text.indexOf("<artifactId>" + artifactId + "</artifactId>");
        int start = text.lastIndexOf("<path>", at);
        start = text.lastIndexOf('\n', start) + 1;
        int end = text.indexOf("</path>", at) + "</path>".length() + 1;
        Files.writeString(pom, text.substring(0, start) + text.substring(end));
    }

    private static void removeDependency(Path pom, String artifactId) throws IOException {
        String text = Files.readString(pom);
        int at = text.indexOf("<artifactId>" + artifactId + "</artifactId>");
        int start = text.lastIndexOf("<dependency>", at);
        start = text.lastIndexOf('\n', start) + 1;
        int end = text.indexOf("</dependency>", at) + "</dependency>".length() + 1;
        Files.writeString(pom, text.substring(0, start) + text.substring(end));
    }
}
