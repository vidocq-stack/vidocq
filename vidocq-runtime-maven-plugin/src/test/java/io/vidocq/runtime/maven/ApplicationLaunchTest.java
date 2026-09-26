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
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ApplicationLaunch#modulePath}'s {@code dropDevOnly} overload: {@code vidocq:run} drops a jar
 * marked {@code Vidocq-Dev-Only: true} even when the project declares it, while the five-argument overload
 * that {@code vidocq:dev} uses keeps it (Vidocq/vidocq#143).
 */
class ApplicationLaunchTest {

    @Test
    void dropDevOnlyDropsAMarkedJarAndWarnsWithItsArtifactId(@TempDir Path dir) throws Exception {
        Path plainJar = jar(dir, "app-lib.jar", null);
        Path markedJar = jar(dir, "devconsole-extension.jar", "true");
        MavenProject project = project();
        project.setArtifacts(Set.of(
                artifact("vidocq-runtime-app-lib", plainJar),
                artifact("vidocq-runtime-devconsole-extension", markedJar)));
        Path build = dir.resolve("target");
        Path classes = build.resolve("classes");

        List<String> dropped = new ArrayList<>();
        List<Path> kept = ApplicationLaunch.modulePath(project, build, classes, true, jar -> { }, true, dropped::add);

        assertEquals(List.of(plainJar), kept);
        assertEquals(List.of("vidocq-runtime-devconsole-extension"), dropped);
        assertEquals(List.of(plainJar, markedJar).size(),
                ApplicationLaunch.modulePath(project, build, classes, true, jar -> { }).size(),
                "vidocq:dev keeps a declared dev-only jar");
    }

    private static Path jar(Path dir, String name, String devOnly) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (devOnly != null) {
            manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, devOnly);
        }
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
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
