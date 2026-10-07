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
 * {@code vidocq:docker} copies the jlink image into a Linux base image, so an image linked on macOS or
 * Windows cannot start in the container: the goal says so, and refuses to build it (BUG-20260711-02, #199).
 */
class VidocqDockerMojoTest {

    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
    private static final byte[] MACH_O_64 = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0, 0, 0, 0};
    private static final byte[] PE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

    /** Collects the warnings the goal logs. */
    static final class Warnings extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            lines.add(content.toString());
        }
    }

    @Test
    void aLinuxImageIsWrappedWithoutAWarning(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java", ELF, false, warnings).execute();

        assertTrue(Files.exists(target.resolve("Dockerfile")));
        assertEquals(List.of(), warnings.lines);
    }

    @Test
    void aMacOsImageIsWrappedWithAWarning(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java", MACH_O_64, false, warnings).execute();

        assertTrue(Files.exists(target.resolve("Dockerfile")), "generating stays possible: the build must not break on macOS");
        assertEquals(1, warnings.lines.size(), warnings.lines.toString());
        var warning = warnings.lines.getFirst();
        assertTrue(warning.contains("Mach-O") && warning.contains("macOS") && warning.contains("Linux"), warning);
    }

    @Test
    void aWindowsImageIsWrappedWithAWarning(@TempDir Path target) throws Exception {
        var warnings = new Warnings();
        mojo(target, "java.exe", PE, false, warnings).execute();

        assertEquals(1, warnings.lines.size(), warnings.lines.toString());
        assertTrue(warnings.lines.getFirst().contains("Windows"), warnings.lines.getFirst());
    }

    @Test
    void buildingANonLinuxImageFailsBeforeRunningDocker(@TempDir Path target) throws Exception {
        var mojo = mojo(target, "java", MACH_O_64, true, new Warnings());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(failure.getMessage().contains("Mach-O") && failure.getMessage().contains("Linux"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("docker build failed"), "docker must not have run");
    }

    private static VidocqDockerMojo mojo(Path target, String javaName, byte[] header, boolean build, Warnings log)
            throws Exception {
        Path bin = Files.createDirectories(target.resolve("dist/bin"));
        Files.write(bin.resolve(javaName), header);
        var mojo = new VidocqDockerMojo();
        set(mojo, "runtimeImage", target.resolve("dist").toFile());
        set(mojo, "buildDir", target.toFile());
        set(mojo, "baseImage", "gcr.io/distroless/base-debian12:nonroot");
        set(mojo, "imageTag", "app:1");
        set(mojo, "launcher", "app");
        set(mojo, "exposedPort", 8080);
        set(mojo, "build", build);
        mojo.setLog(log);
        return mojo;
    }

    private static void set(VidocqDockerMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqDockerMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
