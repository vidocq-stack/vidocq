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
package io.vidocq.runtime.maven.depgen;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodegenBundlesTest {

    /** Resolves {@code g:a:version} to a directory of its own; records what was asked. */
    private static final class Fake implements ArtifactResolver {
        final Path dir;
        final Map<String, Path> own;
        final List<String> asked = new ArrayList<>();

        Fake(Path dir, Map<String, Path> own) {
            this.dir = dir;
            this.own = own;
        }

        @Override
        public Path resolve(String g, String a, String v, String type, String classifier) {
            asked.add(g + ":" + a + ":" + v);
            return own.get(g + ":" + a);
        }

        @Override
        public List<Path> resolveWithDependencies(String g, String a, String v, String type, String classifier) {
            return List.of(own.get(g + ":" + a), dir.resolve("dep-of-" + a + ".jar"));
        }
    }

    private static Path bundle(Path root, String name, boolean withHints) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        if (withHints) {
            Path file = dir.resolve(DependencyCodegenHints.PATH);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "brick=" + name + "\nresources=META-INF/x\n");
        }
        return dir;
    }

    private static MavenProject project(String paths, String managedVersion) throws Exception {
        Model model = new Model();
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        compiler.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                "<configuration><annotationProcessorPaths>" + paths + "</annotationProcessorPaths></configuration>")));
        Build build = new Build();
        build.addPlugin(compiler);
        model.setBuild(build);
        if (managedVersion != null) {
            Dependency managed = new Dependency();
            managed.setGroupId("g");
            managed.setArtifactId("managed");
            managed.setVersion(managedVersion);
            DependencyManagement management = new DependencyManagement();
            management.addDependency(managed);
            model.setDependencyManagement(management);
        }
        return new MavenProject(model);
    }

    private static String path(String a, String version, String type) {
        return "<path><groupId>g</groupId><artifactId>" + a + "</artifactId>"
                + (version == null ? "" : "<version>" + version + "</version>")
                + (type == null ? "" : "<type>" + type + "</type>") + "</path>";
    }

    @Test
    void aBundleWithHintsBecomesABrickWithItsProcessorPath(@TempDir Path dir) throws Exception {
        Path with = bundle(dir, "with", true);
        Fake resolver = new Fake(dir, Map.of("g:with", with));

        List<Brick> bricks = CodegenBundles.of(project(path("with", "1.0", null), null), resolver);

        assertEquals(1, bricks.size());
        assertEquals("with", bricks.get(0).hints().brick());
        assertEquals("g:with:1.0", bricks.get(0).coordinates());
        assertEquals(List.of(with, dir.resolve("dep-of-with.jar")), bricks.get(0).processorPath());
    }

    @Test
    void aBundleWithoutHintsIsIgnored(@TempDir Path dir) throws Exception {
        Fake resolver = new Fake(dir, Map.of("g:without", bundle(dir, "without", false)));

        assertTrue(CodegenBundles.of(project(path("without", "1.0", null), null), resolver).isEmpty());
    }

    @Test
    void aMissingVersionComesFromDependencyManagement(@TempDir Path dir) throws Exception {
        Fake resolver = new Fake(dir, Map.of("g:managed", bundle(dir, "managed", true)));

        List<Brick> bricks = CodegenBundles.of(project(path("managed", null, null), "2.5"), resolver);

        assertEquals("g:managed:2.5", bricks.get(0).coordinates());
        assertEquals(List.of("g:managed:2.5"), resolver.asked);
    }

    @Test
    void aMissingVersionNowhereFailsNamingThePath(@TempDir Path dir) throws Exception {
        Fake resolver = new Fake(dir, Map.of("g:lost", bundle(dir, "lost", true)));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> CodegenBundles.of(project(path("lost", null, null), null), resolver));

        assertEquals("annotationProcessorPaths entry g:lost has no version and the project's dependencyManagement has"
                + " none", e.getMessage());
    }

    @Test
    void aMalformedHintsFileFailsNamingTheBundle(@TempDir Path dir) throws Exception {
        Path broken = bundle(dir, "broken", false);
        Path file = broken.resolve(DependencyCodegenHints.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "brick=Broken\nresources=META-INF/x\nflavour=sweet\n");
        Fake resolver = new Fake(dir, Map.of("g:broken", broken));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> CodegenBundles.of(project(path("broken", "1.0", null), null), resolver));

        assertTrue(e.getMessage().startsWith("The codegen bundle g:broken:1.0 declares malformed hints: "),
                e.getMessage());
        assertTrue(e.getMessage().contains("flavour"), e.getMessage());
    }

    @Test
    void aPomTypedPathIsSkipped(@TempDir Path dir) throws Exception {
        Fake resolver = new Fake(dir, Map.of("g:twin", bundle(dir, "twin", true)));

        assertTrue(CodegenBundles.of(project(path("twin", "1.0", "pom"), null), resolver).isEmpty());
        assertTrue(resolver.asked.isEmpty());
    }
}
