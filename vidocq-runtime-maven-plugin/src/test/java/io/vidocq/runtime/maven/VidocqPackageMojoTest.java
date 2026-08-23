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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the optionality of {@code <jvmArgs>} in the {@code package} goal.
 *
 * <p>{@code @Parameter(defaultValue = "")} does not make Maven inject an empty string: an omitted
 * {@code <jvmArgs>} leaves the field {@code null}, and the launcher generators used to dereference
 * it unconditionally. Reported downstream as a {@code NullPointerException} on the very first
 * project a newcomer writes, whose natural configuration is {@code mainClass} plus
 * {@code scriptName} — see BUG-20260710-01 and Vidocq/vidocq#40.
 */
class VidocqPackageMojoTest {

    @Test
    void launchers_areGeneratedWhenJvmArgsIsNotConfigured(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        String sh = Files.readString(binDir.resolve("app.sh"));
        assertTrue(sh.contains("exec java \\"),
                "an absent <jvmArgs> must leave the java invocation bare: " + sh);
        assertTrue(sh.contains("--module io.repro.app/io.repro.app.Main"), sh);

        String cmd = Files.readString(binDir.resolve("app.cmd"));
        assertTrue(cmd.contains("java ^"), "an absent <jvmArgs> must leave the java invocation bare: " + cmd);
    }

    @Test
    void launchers_carryConfiguredJvmArgs(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith("-Dfile.encoding=UTF-8 -Xmx512m");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertTrue(Files.readString(binDir.resolve("app.sh"))
                .contains("exec java -Dfile.encoding=UTF-8 -Xmx512m \\"));
        assertTrue(Files.readString(binDir.resolve("app.cmd"))
                .contains("java -Dfile.encoding=UTF-8 -Xmx512m ^"));
    }

    @Test
    void blankJvmArgsAreTreatedAsAbsent(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith("   ");

        mojo.generateShScript(binDir);

        String sh = Files.readString(binDir.resolve("app.sh"));
        assertTrue(sh.contains("exec java \\"), sh);
        assertFalse(sh.contains("java    "), "whitespace-only jvmArgs must not leak into the launcher: " + sh);
    }

    @Test
    void shLauncherIsExecutable(@TempDir Path binDir) throws Exception {
        mojoWith(null).generateShScript(binDir);

        assertTrue(Files.isExecutable(binDir.resolve("app.sh")));
        assertEquals("app.sh", binDir.resolve("app.sh").getFileName().toString());
    }

    private static VidocqPackageMojo mojoWith(String jvmArgs) throws Exception {
        VidocqPackageMojo mojo = new VidocqPackageMojo();
        set(mojo, "scriptName", "app");
        set(mojo, "mainClass", "io.repro.app/io.repro.app.Main");
        set(mojo, "jvmArgs", jvmArgs);
        return mojo;
    }

    private static void set(VidocqPackageMojo mojo, String field, String value) throws Exception {
        Field f = VidocqPackageMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
