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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:docker} wraps a jlink image into a Linux image. An image linked on macOS or Windows cannot start in
 * the container (BUG-20260711-02, #199): the goal then links the image again inside a Linux JDK, in the first stage
 * of the Dockerfile, from the modules {@code vidocq:jlink} staged (BUG-20261030-17).
 */
class VidocqDockerMojoTest {

    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
    private static final byte[] MACH_O_64 = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0, 0, 0, 0};
    private static final byte[] PE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    private static final String ENTRY_POINT = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    /** Collects the warnings the goal logs. */
    static final class Warnings extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            lines.add(content.toString());
        }
    }

    @Test
    void aLinuxImageIsWrappedAsItIs(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java", ELF, "auto", false, warnings).execute();

        String dockerfile = Files.readString(target.resolve("Dockerfile"));
        assertFalse(dockerfile.contains(" AS link"), dockerfile);
        assertTrue(dockerfile.contains("COPY dist /opt/app/"), dockerfile);
        assertEquals(List.of(), warnings.lines);
    }

    /** The base image has no shell, so the launcher script of the image cannot be the entry point. */
    @Test
    void theEntryPointRunsTheImagesJavaWithoutAShell(@TempDir Path target) throws Exception {
        mojo(target, "java", ELF, "auto", false, new Warnings()).execute();

        assertTrue(Files.readString(target.resolve("Dockerfile")).contains(
                "ENTRYPOINT [\"/opt/app/bin/java\", \"-m\", \"" + ENTRY_POINT + "\"]"));
    }

    @Test
    void aMacOsImageIsLinkedAgainInsideALinuxJdk(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java", MACH_O_64, "auto", false, warnings).execute();

        String dockerfile = Files.readString(target.resolve("Dockerfile"));
        assertTrue(dockerfile.contains("FROM eclipse-temurin:" + Runtime.version().feature() + "-jdk AS link"),
                dockerfile);
        assertTrue(dockerfile.contains("COPY jlink-mods /build/mods"), dockerfile);
        assertTrue(dockerfile.contains("RUN jlink @/build/jlink.args --module-path /build/mods:${JAVA_HOME}/jmods --output /opt/app"),
                dockerfile);
        assertTrue(dockerfile.contains("COPY --from=link /opt/app /opt/app"), dockerfile);
        assertTrue(dockerfile.contains("FROM gcr.io/distroless/base-debian12:nonroot"), dockerfile);
        assertFalse(dockerfile.contains("COPY dist "), "the macOS image must not reach the container");
        assertEquals(List.of(), warnings.lines, "the goal handles it: nothing for the user to do");
    }

    @Test
    void aWindowsImageIsLinkedAgainInsideALinuxJdk(@TempDir Path target) throws Exception {
        mojo(target, "java.exe", PE, "auto", false, new Warnings()).execute();

        assertTrue(Files.readString(target.resolve("Dockerfile")).contains(" AS link"));
    }

    /** Only the files the Dockerfile copies are sent to the Docker daemon. */
    @Test
    void theBuildContextHoldsOnlyWhatTheDockerfileCopies(@TempDir Path target) throws Exception {
        mojo(target, "java", MACH_O_64, "auto", false, new Warnings()).execute();

        assertEquals("*\n!jlink-mods\n!jlink-image\n", Files.readString(target.resolve(".dockerignore")));
    }

    @Test
    void linkingInsideAJdkNeedsTheRecipeOfVidocqJlink(@TempDir Path target) throws Exception {
        var mojo = mojo(target, "java", MACH_O_64, "auto", false, new Warnings());
        deleteRecursive(target.resolve(PortableImage.DIRECTORY));

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(failure.getMessage().contains("vidocq:jlink"), failure.getMessage());
    }

    @Test
    void withoutLinkingInsideAJdkANonLinuxImageOnlyWarns(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java", MACH_O_64, "false", false, warnings).execute();

        assertEquals(1, warnings.lines.size(), warnings.lines.toString());
        var warning = warnings.lines.getFirst();
        assertTrue(warning.contains("Mach-O") && warning.contains("macOS") && warning.contains("Linux"), warning);
    }

    @Test
    void withoutLinkingInsideAJdkBuildingANonLinuxImageFailsBeforeRunningDocker(@TempDir Path target)
            throws Exception {
        var mojo = mojo(target, "java", MACH_O_64, "false", true, new Warnings());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(failure.getMessage().contains("Mach-O") && failure.getMessage().contains("Linux"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("docker build failed"), "docker must not have run");
    }

    @Test
    void linkingInsideAJdkCanBeForcedForALinuxImage(@TempDir Path target) throws Exception {
        var mojo = mojo(target, "java", ELF, "TRUE", false, new Warnings());
        set(mojo, "jdkImage", " eclipse-temurin:25-jdk-noble ");
        mojo.execute();

        assertTrue(Files.readString(target.resolve("Dockerfile")).contains("FROM eclipse-temurin:25-jdk-noble AS link"));
    }

    @Test
    void anUnknownLinkingModeIsRefused(@TempDir Path target) throws Exception {
        var mojo = mojo(target, "java", ELF, "sometimes", false, new Warnings());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(failure.getMessage().contains("auto, true or false"), failure.getMessage());
    }

    /** An image linked before the recipe existed keeps its launcher as the entry point. */
    @Test
    void anImageWithoutARecipeStartsWithItsLauncher(@TempDir Path target) throws Exception {
        var mojo = mojo(target, "java", ELF, null, false, new Warnings());
        deleteRecursive(target.resolve(PortableImage.DIRECTORY));
        mojo.execute();

        assertTrue(Files.readString(target.resolve("Dockerfile")).contains("ENTRYPOINT [\"/opt/app/bin/app\"]"));
    }

    private static VidocqDockerMojo mojo(Path target, String javaName, byte[] header, String linkInContainer,
            boolean build, Warnings log) throws Exception {
        Path bin = Files.createDirectories(target.resolve("dist/bin"));
        Files.write(bin.resolve(javaName), header);
        Files.createDirectories(target.resolve("jlink-mods"));
        PortableImage.write(target, List.of("--add-modules", "acme.app"), ENTRY_POINT, List.of());
        var mojo = new VidocqDockerMojo();
        set(mojo, "runtimeImage", target.resolve("dist").toFile());
        set(mojo, "buildDir", target.toFile());
        set(mojo, "baseImage", "gcr.io/distroless/base-debian12:nonroot");
        set(mojo, "imageTag", "app:1");
        set(mojo, "launcher", "app");
        set(mojo, "exposedPort", 8080);
        set(mojo, "build", build);
        set(mojo, "linkInContainer", linkInContainer);
        mojo.setLog(log);
        return mojo;
    }

    private static void deleteRecursive(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void set(VidocqDockerMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqDockerMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
