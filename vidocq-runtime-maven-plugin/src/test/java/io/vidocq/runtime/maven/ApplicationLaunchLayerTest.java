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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ApplicationLaunch#layered}: the build-time split into a module path and an application path (#247). */
class ApplicationLaunchLayerTest {

    @Test
    void aFlatLibraryGoesToTheApplicationLayerAndTheRuntimeStays(@TempDir Path dir) throws Exception {
        Path core = moduleJar(dir, "vidocq-runtime-core.jar", "io.vidocq.runtime.core", true, "acme.codec");
        Path codec = moduleJar(dir, "codec.jar", "acme.codec", true);
        Path flow = moduleJar(dir, "flow-server.jar", "flow.server", false);
        MavenProject project = project();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact("vidocq-runtime-core", core),
                artifact("codec", codec), artifact("flow-server", flow))));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        var layered = ApplicationLaunch.layered(project, build, classes, jar -> { }, true, id -> { }, List.of());

        assertEquals(List.of(core, codec), layered.modulePath());
        assertEquals(List.of(classes, flow), layered.appPath(), "the classes first, then the libraries");
        assertEquals(Map.of(), layered.pinned());
    }

    @Test
    void aPatchedJarStaysInTheParentLayer(@TempDir Path dir) throws Exception {
        Path lib = moduleJar(dir, "lib.jar", "acme.lib", true);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("lib", lib)));
        Path build = dir.resolve("target");
        Files.createDirectories(JpmsPatches.patchDirFor(build, "lib"));

        var layered = ApplicationLaunch.layered(project, build, build.resolve("classes"), jar -> { }, true,
                id -> { }, List.of());

        assertEquals(List.of(lib), layered.modulePath(), "--patch-module only patches boot-layer modules");
        assertEquals(Map.of("acme.lib", ApplicationLaunch.PATCHED_REASON), layered.pinned());
    }

    @Test
    void keepPatternsPinAnArtifact(@TempDir Path dir) throws Exception {
        Path jni = moduleJar(dir, "jni.jar", "acme.jni", false);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("jni", jni)));
        Path build = dir.resolve("target");

        var layered = ApplicationLaunch.layered(project, build, build.resolve("classes"), jar -> { }, true,
                id -> { }, List.of("*:jni"));

        assertEquals(List.of(jni), layered.modulePath());
        assertEquals(Map.of("acme.jni", "vidocq.app.layer.keep"), layered.pinned());
    }

    @Test
    void aJarWithoutAModuleNameFailsWithItsFileName(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("1-bad.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(bad))) {
            out.putNextEntry(new ZipEntry("x/Y.class"));
            out.write(new byte[] {(byte) 0xCA});
            out.closeEntry();
        }
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("bad", bad)));
        Path build = dir.resolve("target");

        var e = assertThrows(MojoExecutionException.class, () -> ApplicationLaunch.layered(project, build,
                build.resolve("classes"), jar -> { }, true, id -> { }, List.of()));
        assertTrue(e.getMessage().contains("1-bad.jar"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.app.layer=all"), e.getMessage());
    }

    /** An automatic module declares no dependency: Maven's trail says what the kept {@code liquibase.core} needs. */
    @Test
    void aDependencyOfAKeptAutomaticModuleStays(@TempDir Path dir) throws Exception {
        Path extension = moduleJar(dir, "liquibase-extension.jar",
                "io.vidocq.runtime.extensions.essentials.migration.liquibase", true, "liquibase.core");
        Path liquibase = moduleJar(dir, "liquibase-core.jar", "liquibase.core", false);
        Path opencsv = moduleJar(dir, "opencsv.jar", "opencsv", false);
        Path flow = moduleJar(dir, "flow-server.jar", "flow.server", false);
        Artifact extensionArtifact = artifact("vidocq-runtime-liquibase-extension", extension);
        Artifact liquibaseArtifact = artifact("liquibase-core", liquibase);
        Artifact opencsvArtifact = artifact("opencsv", opencsv);
        Artifact flowArtifact = artifact("flow-server", flow);
        extensionArtifact.setDependencyTrail(List.of(PROJECT, coordinates(extensionArtifact)));
        liquibaseArtifact.setDependencyTrail(
                List.of(PROJECT, coordinates(extensionArtifact), coordinates(liquibaseArtifact)));
        opencsvArtifact.setDependencyTrail(List.of(PROJECT, coordinates(extensionArtifact),
                coordinates(liquibaseArtifact), coordinates(opencsvArtifact)));
        flowArtifact.setDependencyTrail(List.of(PROJECT, coordinates(flowArtifact)));
        MavenProject project = project();
        project.setArtifacts(new LinkedHashSet<>(
                List.of(extensionArtifact, liquibaseArtifact, opencsvArtifact, flowArtifact)));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        var layered = ApplicationLaunch.layered(project, build, classes, jar -> { }, true, id -> { }, List.of());

        assertEquals(List.of(extension, liquibase, opencsv), layered.modulePath());
        assertEquals(List.of(classes, flow), layered.appPath());
    }

    private static final String PROJECT = "com.example:app:jar:1.0";

    private static String coordinates(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType() + ":" + a.getVersion();
    }

    /** A jar with an explicit descriptor requiring {@code requires}, or an automatic one when {@code explicit} is false. */
    private static Path moduleJar(Path dir, String fileName, String module, boolean explicit, String... requires)
            throws Exception {
        Path jar = dir.resolve(fileName);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (!explicit) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (explicit) {
                out.putNextEntry(new ZipEntry("module-info.class"));
                out.write(ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (String r : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(r), 0, null));
                    }
                })));
                out.closeEntry();
            }
            String pkg = module.replace('.', '/');
            out.putNextEntry(new ZipEntry(pkg + "/Marker.class"));
            out.write(ClassFile.of().build(ClassDesc.of(module + ".Marker"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }

    private static Artifact artifact(String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", artifactId, "0.4.0-SNAPSHOT", "runtime", "jar",
                "", new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    private static MavenProject project() {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        return new MavenProject(model);
    }
}
