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

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:jlink} and {@code vidocq:jpackage} turn {@code vidocq.mainModule} / {@code vidocq.mainClass} into
 * an {@link ImageLaunch} before any work, and start the application the way it says.
 */
class ImageLaunchMojosTest {

    private static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    @TempDir
    Path dir;

    /** The info lines a goal logs. */
    static final class Infos extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            lines.add(content.toString());
        }
    }

    // ---------------------------------------------------------------- vidocq:jlink

    @Test
    void jlinkWithoutAMainModuleFailsBeforeStagingAnything() throws Exception {
        VidocqJlinkMojo mojo = jlink(null, null, new Infos());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
        assertTrue(Files.notExists(dir.resolve("jlink-mods")), "nothing is staged before the launch is known");
    }

    @Test
    void jlinkLogsTheRuntimeMainLaunchThenReportsAFailedStaging() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", null, log);

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertEquals("Failed to create jlink image", failure.getMessage());
        assertEquals("Vidocq jlink — main: " + RUNTIME_MAIN
                + " --add-modules=acme.app -Dvidocq.app.module=acme.app", log.lines.getFirst());
    }

    @Test
    void theJlinkRunBakesTheLaunchIntoTheImage() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
        set(VidocqJlinkMojo.class, mojo, "stripDebug", true);

        List<String> args = mojo.jlinkArgs("acme.app,java.base", ImageLaunch.of("acme.app", null));

        assertEquals(dir.resolve("jlink-mods") + File.pathSeparator + System.getProperty("java.home") + "/jmods",
                args.get(args.indexOf("--module-path") + 1));
        assertEquals("acme.app,java.base", args.get(args.indexOf("--add-modules") + 1));
        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=--add-modules=acme.app -Dvidocq.app.module=acme.app"), args.toString());
        assertEquals(dir.resolve("dist").toString(), args.get(args.indexOf("--output") + 1));
        assertTrue(args.contains("--strip-debug"), args.toString());
        assertTrue(args.contains("--compress=zip-6"), args.toString());
    }

    @Test
    void theJlinkRunOfATrampolineAddsNoOption() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", new Infos());
        set(VidocqJlinkMojo.class, mojo, "compress", " ");

        List<String> args = mojo.jlinkArgs("acme.app", ImageLaunch.of("acme.app", "acme.app.App"));

        assertEquals("app=acme.app/acme.app.App", args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.stream().noneMatch(a -> a.startsWith("--add-options") || a.startsWith("--compress")
                || a.equals("--strip-debug")), args.toString());
    }

    private VidocqJlinkMojo jlink(String mainModule, String mainClass, Infos log) throws Exception {
        var project = new MavenProject();
        var artifact = new DefaultArtifact("acme", "app", "1.0", "compile", "jar", "", new DefaultArtifactHandler("jar"));
        artifact.setFile(dir.resolve("absent-app-1.0.jar").toFile());
        project.setArtifact(artifact);

        var mojo = new VidocqJlinkMojo();
        set(VidocqJlinkMojo.class, mojo, "project", project);
        set(VidocqJlinkMojo.class, mojo, "mainModule", mainModule);
        set(VidocqJlinkMojo.class, mojo, "mainClass", mainClass);
        set(VidocqJlinkMojo.class, mojo, "launcher", "app");
        set(VidocqJlinkMojo.class, mojo, "distDir", dir.resolve("dist").toFile());
        set(VidocqJlinkMojo.class, mojo, "modsDir", dir.resolve("jlink-mods").toFile());
        set(VidocqJlinkMojo.class, mojo, "buildDir", dir.toFile());
        set(VidocqJlinkMojo.class, mojo, "compress", "zip-6");
        mojo.setLog(log);
        return mojo;
    }

    // ---------------------------------------------------------------- vidocq:jpackage

    @Test
    void jpackageWithoutAMainModuleFailsBeforePackagingAnything() throws Exception {
        VidocqJpackageMojo mojo = jpackage(null, new Infos());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
        assertTrue(Files.notExists(dir.resolve("installer")), "nothing is packaged before the launch is known");
    }

    @Test
    void jpackageStartsAMainLessApplicationWithTheRuntimeMain() throws Exception {
        var log = new Infos();
        // An unknown --type makes jpackage stop right after reading the arguments.
        VidocqJpackageMojo mojo = jpackage("acme.app", log);

        assertThrows(MojoExecutionException.class, mojo::execute);

        assertEquals("Vidocq jpackage — type=no-such-type name=app version=1.0.0 main=" + RUNTIME_MAIN,
                log.lines.getFirst());
    }

    private VidocqJpackageMojo jpackage(String mainModule, Infos log) throws Exception {
        Path image = Files.createDirectories(dir.resolve("image"));
        var mojo = new VidocqJpackageMojo();
        set(VidocqJpackageMojo.class, mojo, "mainModule", mainModule);
        set(VidocqJpackageMojo.class, mojo, "appName", "app");
        set(VidocqJpackageMojo.class, mojo, "appVersion", "1.0.0");
        set(VidocqJpackageMojo.class, mojo, "type", "no-such-type");
        set(VidocqJpackageMojo.class, mojo, "runtimeImage", image.toFile());
        set(VidocqJpackageMojo.class, mojo, "installerDir", dir.resolve("installer").toFile());
        mojo.setLog(log);
        return mojo;
    }

    private static <T> void set(Class<T> type, T mojo, String field, Object value) throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
