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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.core.LayerSplit;
import io.vidocq.runtime.maven.DevOnlyJars;
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
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq:dev} and {@code vidocq.app.layer} (Vidocq/vidocq#247). */
class VidocqDevLaunchPathsTest {

    @Test
    void devLaunchPathsKeepDevOnlyJars(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("devonly.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "acme.devonly");
        manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, "true");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("acme/devonly/Marker.class"));
            out.write(ClassFile.of().build(ClassDesc.of("acme.devonly.Marker"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", "acme-devonly", "0.4.0-SNAPSHOT", "runtime",
                "jar", "", new DefaultArtifactHandler("jar"));
        artifact.setFile(jar.toFile());
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact)));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        for (LayerSplit.Mode mode : LayerSplit.Mode.values()) {
            List<String> dropped = new ArrayList<>();
            var launch = VidocqDevMojo.launchPaths(project, build, classes, mode, List.of(), j -> { },
                    dropped::add);
            assertTrue(launch.modulePath().contains(jar) || launch.appPath().contains(jar), "" + mode);
            assertEquals(List.of(), dropped, "" + mode);
        }
    }

    @Test
    void devRejectsAllWithoutTheLayer() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setLayerMode(false);
        mojo.setAppLayer("all");

        var e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("vidocq.app.layer=all needs vidocq.dev.layer=true", e.getMessage());
    }
}
