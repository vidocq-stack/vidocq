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

import io.vidocq.runtime.maven.ConsoleColors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VidocqDevMojoTest {

    @Test
    void connectionReportFallsBackToClassesDirParentWhenBuildDirIsNull(@TempDir Path tmp) throws Exception {
        VidocqDevMojo mojo = new VidocqDevMojo();
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        mojo.setClassesDir(classes.toFile());
        // buildDir deliberately left null (the bug Arago's vidocq:dev hit): must not NPE — the report
        // falls back to the parent of the classes dir (target/classes → target).
        mojo.reportConnectionInformation(Map.of(
                "vidocq.pool.url", "jdbc:postgresql://localhost:5432/app",
                "vidocq.pool.username", "app"));
        Path report = tmp.resolve("target/vidocq-dev-services.properties");
        assertTrue(Files.exists(report), "report written under the classes-dir parent (target)");
        assertTrue(Files.readString(report).contains("datasource.default.url="));
    }

    @Test
    void addsJdwpAgentWhenDebugEnabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"), args);
    }

    @Test
    void honoursCustomPortAndSuspend() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 6789, true);

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:6789"),
                mojo.debugJvmArgs());
    }

    @Test
    void noAgentWhenDebugDisabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx512m");
        mojo.setDebugOptions(false, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-Xmx512m"), args);
        assertFalse(args.stream().anyMatch(a -> a.contains("jdwp")));
    }

    @Test
    void extraJvmArgsComeBeforeTheDebugAgent() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx256m -XX:+UseZGC");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals("-Xmx256m", args.get(0));
        assertEquals("-XX:+UseZGC", args.get(1));
        assertTrue(args.get(2).startsWith("-agentlib:jdwp="));
    }

    /**
     * {@code mvn vidocq:dev -Dvidocq.console.color=always} sets the property on the <em>Maven</em> JVM, and
     * the application runs in another one: unless the goal passes it on, asking for colour explicitly used to
     * turn colour off, because nothing else seeds it (unlike {@code vidocq:run}, which forwards every
     * application {@code -Dvidocq.*} of the command line).
     */
    @Test
    void anExplicitColourPolicyReachesTheChild() {
        withProperties("always", "strip", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("");

            assertEquals("always", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** What {@code vidocq.dev.systemProperties} declares for the child is never second-guessed. */
    @Test
    void aDeclaredColourPolicyIsNotOverwritten() {
        withProperties("always", "force", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("vidocq.console.color=never");

            assertEquals("never", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** With nothing asked, the child still inherits Maven's own policy — the IDE Maven console case. */
    @Test
    void mavensOwnColoursReachTheChild() {
        assumeTrue(System.getenv("NO_COLOR") == null || System.getenv("NO_COLOR").isEmpty(),
                "NO_COLOR stops the decision");
        withProperties(null, "force", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("");

            assertEquals("always", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** Runs {@code body} with {@code vidocq.console.color} and {@code jansi.mode} set, then restores them. */
    private static void withProperties(String colorKey, String jansiMode, Runnable body) {
        String previousColor = System.getProperty(ConsoleColors.COLOR_KEY);
        String previousJansi = System.getProperty("jansi.mode");
        try {
            set(ConsoleColors.COLOR_KEY, colorKey);
            set("jansi.mode", jansiMode);
            body.run();
        } finally {
            set(ConsoleColors.COLOR_KEY, previousColor);
            set("jansi.mode", previousJansi);
        }
    }

    private static void set(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
