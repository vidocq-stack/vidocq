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

import io.vidocq.runtime.core.LayerSplit;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq:run} and {@code vidocq.app.layer} (Vidocq/vidocq#247). */
class VidocqRunLaunchPathsTest {

    @Test
    void runRejectsAnUnknownAppLayer() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setAppLayer("libs");

        var e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("vidocq.app.layer must be classes or all, not 'libs'", e.getMessage());
    }

    @Test
    void launchPathsByMode(@TempDir Path dir) throws Exception {
        Path core = moduleJar(dir, "vidocq-runtime-core.jar", "io.vidocq.runtime.core", true, "acme.codec");
        Path codec = moduleJar(dir, "codec.jar", "acme.codec", true);
        Path flow = moduleJar(dir, "flow-server.jar", "flow.server", false);
        MavenProject project = project();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact("vidocq-runtime-core", core),
                artifact("codec", codec), artifact("flow-server", flow))));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        var classesMode = VidocqRunMojo.launchPaths(project, build, classes, LayerSplit.Mode.CLASSES, List.of(),
                jar -> { }, id -> { });
        assertEquals(List.of(core, codec, flow), classesMode.modulePath());
        assertEquals(List.of(classes), classesMode.appPath());
        assertEquals(Map.of(), classesMode.pinned());

        var all = VidocqRunMojo.launchPaths(project, build, classes, LayerSplit.Mode.ALL, List.of(),
                jar -> { }, id -> { });
        assertEquals(List.of(core, codec), all.modulePath());
        assertEquals(List.of(classes, flow), all.appPath());
    }

    @Test
    void runDropsADevOnlyJarInBothModes(@TempDir Path dir) throws Exception {
        Path devOnly = devOnlyJar(dir, "devonly.jar", "acme.devonly");
        MavenProject project = project();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact("acme-devonly", devOnly))));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        for (LayerSplit.Mode mode : LayerSplit.Mode.values()) {
            List<String> dropped = new ArrayList<>();
            var launch = VidocqRunMojo.launchPaths(project, build, classes, mode, List.of(), jar -> { },
                    dropped::add);
            assertTrue(!launch.modulePath().contains(devOnly) && !launch.appPath().contains(devOnly), "" + mode);
            assertEquals(List.of("acme-devonly"), dropped, "" + mode);
        }
    }

    static Path devOnlyJar(Path dir, String fileName, String module) throws Exception {
        Path jar = dir.resolve(fileName);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, "true");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry(module.replace('.', '/') + "/Marker.class"));
            out.write(ClassFile.of().build(ClassDesc.of(module + ".Marker"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }

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

    static Artifact artifact(String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", artifactId, "0.4.0-SNAPSHOT", "runtime", "jar",
                "", new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    static MavenProject project() {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        return new MavenProject(model);
    }
}
