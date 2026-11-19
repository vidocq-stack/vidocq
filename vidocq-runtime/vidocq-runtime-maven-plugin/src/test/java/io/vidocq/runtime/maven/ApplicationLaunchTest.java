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

import io.vidocq.runtime.maven.dev.DevServicesExtensionJar;
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
import java.util.Map;
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

    /** What the dev services look into for a driver (spec 2026-09-29-devservice-postgres-kind §5). */
    @Test
    void theClasspathOfALaunchIsItsModulePathAndItsClassesOnce(@TempDir Path dir) {
        Path classes = dir.resolve("target/classes");
        Path lib = dir.resolve("lib.jar");

        assertEquals(List.of(lib, classes), ApplicationLaunch.classpathOf(List.of(lib), classes),
                "layer mode keeps the classes off the module path: they are added");
        assertEquals(List.of(classes, lib), ApplicationLaunch.classpathOf(List.of(classes, lib), classes),
                "never twice");
    }

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

    /**
     * vidocq:run with dev services: a dev-only jar the project declares is dropped from the path, so the opt-in step
     * must see only what was kept and add the plugin's own copy back.
     */
    @Test
    void theDevServicesStepSeesOnlyTheKeptArtifacts(@TempDir Path dir) throws Exception {
        Artifact lib = artifact("vidocq-runtime-app-lib", jar(dir, "app-lib.jar", null));
        Artifact declared = artifact("vidocq-runtime-devservices-extension", jar(dir, "declared-ds.jar", "true"));
        Path pluginCopy = jar(dir, "plugin-ds.jar", "true");
        Path pluginSpi = jar(dir, "plugin-spi.jar", null);
        Artifact spi = artifact("vidocq-runtime-devconsole-spi", jar(dir, "app-spi.jar", null));
        Artifact pluginDs = artifact("vidocq-runtime-devservices-extension", pluginCopy);
        Artifact pluginSpiArtifact = artifact("vidocq-runtime-devconsole-spi", pluginSpi);

        List<Artifact> kept = ApplicationLaunch.keptArtifacts(List.of(lib, declared, spi), true);

        assertEquals(List.of(lib, spi), kept);
        assertEquals(List.of(lib, declared, spi), ApplicationLaunch.keptArtifacts(List.of(lib, declared, spi), false));
        assertEquals(List.of(pluginCopy), DevServicesExtensionJar.resolve(Map.of(
                "io.vidocq.runtime:vidocq-runtime-devservices-extension", pluginDs,
                "io.vidocq.runtime:vidocq-runtime-devconsole-spi", pluginSpiArtifact), kept));
    }

    /** The console SPI is an ordinary API jar: an all-in-one extension that requires it keeps it on the path. */
    @Test
    void theConsoleSpiIsKeptOnTheModulePath(@TempDir Path dir) throws Exception {
        Path spiJar = Path.of(io.vidocq.runtime.spi.devconsole.DevConsolePanel.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("vidocq-runtime-devconsole-spi", spiJar)));
        Path build = dir.resolve("target");
        List<String> dropped = new ArrayList<>();

        List<Path> kept = ApplicationLaunch.modulePath(project, build, build.resolve("classes"), true, jar -> { },
                true, dropped::add);

        assertEquals(List.of(spiJar), kept);
        assertEquals(List.of(), dropped);
    }

    @Test
    void anEnrichedCopyReplacesTheJarAndNeedsNoPatch(@TempDir Path dir) throws Exception {
        Path lib = jar(dir, "dep-lib.jar", null);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("dep-lib", lib)));
        Path build = dir.resolve("target");
        Files.createDirectories(JpmsPatches.patchDirFor(build, "dep-lib").resolve("a"));
        assertEquals("--patch-module", ApplicationLaunch.patchModuleArgs(project, build).getFirst(),
                "without a copy, the parked classes are patched in");

        Path enriched = EnrichedJars.root(build).resolve("dep-lib.jar");
        Files.createDirectories(enriched.getParent());
        Files.copy(lib, enriched);
        List<Path> replaced = new ArrayList<>();

        assertEquals(List.of(enriched),
                ApplicationLaunch.modulePath(project, build, build.resolve("classes"), true, replaced::add));
        assertEquals(List.of(lib), replaced);
        assertEquals(List.of(), ApplicationLaunch.patchModuleArgs(project, build), "its classes are in the copy");
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
