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

import io.vidocq.runtime.maven.dev.ChildJvm;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code vidocq:run} hands to the JVM it forks, and what it does with its exit code.
 *
 * <p>The exit-code tests fork a real JVM, but not the application: {@code jdk.compiler}'s {@code javac}
 * entry point is a main class every JDK has, and it chooses its exit code from its arguments — {@code 0}
 * for {@code --version}, {@code 2} for an unknown option.
 */
class VidocqRunMojoTest {

    @Test
    void noDebugAgentByDefault() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("-Xmx512m");
        mojo.setDebugOptions(false, 5005, false);

        assertEquals(List.of("-Xmx512m"), mojo.debugJvmArgs());
    }

    /** Whoever reaches a JDWP agent can run any code in the child: by default only this machine can. */
    @Test
    void addsJdwpAgentOnTheLoopbackInterfaceWhenDebugEnabled() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"),
                mojo.debugJvmArgs());
    }

    @Test
    void honoursCustomPortAndSuspend() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("-Xmx256m -XX:+UseZGC");
        mojo.setDebugOptions(true, 18099, true);

        assertEquals(List.of("-Xmx256m", "-XX:+UseZGC",
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:18099"),
                mojo.debugJvmArgs());
    }

    /** {@code vidocq.run.debug.host} is the explicit opt-in to open the agent beyond this machine. */
    @Test
    void anExplicitHostIsPassedToTheAgentAsWritten() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        mojo.setDebugHost("*");
        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"),
                mojo.debugJvmArgs());
        mojo.setDebugHost("192.168.1.20");
        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=192.168.1.20:5005"),
                mojo.debugJvmArgs());
    }

    @Test
    void theAgentIsAnnouncedWithItsHostAndNoWarningOnLoopback() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);
        mojo.setDebugOptions(true, 5005, false);

        mojo.logDebugAgent();

        assertEquals(List.of("INFO Vidocq run: debug agent (JDWP) on port 5005, host 127.0.0.1 — attach any time"),
                log.lines);
    }

    @Test
    void aHostOpenToTheNetworkIsAWarning() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);
        mojo.setDebugOptions(true, 5005, false);
        mojo.setDebugHost("0.0.0.0");

        mojo.logDebugAgent();

        assertEquals(2, log.lines.size(), log.lines.toString());
        assertEquals("INFO Vidocq run: debug agent (JDWP) on port 5005, host 0.0.0.0 (every interface)"
                + " — attach any time", log.lines.get(0));
        String warning = log.lines.get(1);
        assertTrue(warning.startsWith(
                "WARN Vidocq run: vidocq.run.debug.host=0.0.0.0 makes the debugger reachable from the network"),
                warning);
        assertTrue(warning.contains("whoever connects to it can run any code in the application JVM"), warning);
    }

    /** Keeps the INFO and WARN lines, as {@code "LEVEL message"}. */
    private static final class CapturingLog extends SystemStreamLog {

        final List<String> lines = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            lines.add("INFO " + content);
        }

        @Override
        public void warn(CharSequence content) {
            lines.add("WARN " + content);
        }
    }

    @Test
    void applicationArgumentsAreSplitOnWhitespace() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setAppArgs("  --port 18091   --verbose ");

        assertEquals(List.of("--port", "18091", "--verbose"), mojo.debugAppArgs());

        mojo.setAppArgs("");
        assertEquals(List.of(), mojo.debugAppArgs());
    }

    /**
     * The application runs in another JVM, so a {@code -Dvidocq.*} of the Maven command line only reaches it
     * if the goal forwards it. Everything that configures the build stays in the Maven JVM.
     */
    @Test
    void onlyTheApplicationPropertiesOfTheCommandLineAreForwarded() {
        Properties userProperties = new Properties();
        userProperties.setProperty("vidocq.chappe.listener.default.port", "18092");
        userProperties.setProperty("vidocq.profile", "prod");
        userProperties.setProperty("vidocq.run.debug", "true");
        userProperties.setProperty("vidocq.idea.check", "strict");
        userProperties.setProperty("vidocq.dev.hotReload", "false");
        userProperties.setProperty("vidocq.mainClass", "com.example.Other");
        userProperties.setProperty("vidocq.jvmArgs", "-Xmx1g");
        userProperties.setProperty("maven.test.skip", "true");

        Map<String, String> forwarded = VidocqRunMojo.forwarded(userProperties);

        assertEquals(Map.of("vidocq.chappe.listener.default.port", "18092", "vidocq.profile", "prod"), forwarded);
    }

    @Test
    void systemPropertiesCarryTheDeclaredOnesFirst() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraSystemProperties("vidocq.chappe.listener.default.port=18091, foo = bar ,broken");

        Map<String, String> properties = mojo.debugSystemProperties();

        assertEquals("18091", properties.get("vidocq.chappe.listener.default.port"));
        assertEquals("bar", properties.get("foo"));
        assertFalse(properties.containsKey("broken"), properties.toString());
        assertFalse(properties.containsKey("vidocq.profile"), "vidocq:run never forces a profile: " + properties);
    }

    /**
     * An explicit colour policy reaches the child even by the one route {@link VidocqRunMojo#forwarded} does
     * not see: {@code MAVEN_OPTS}, which sets the property on the Maven JVM without making it a user property.
     */
    @Test
    void anExplicitColourPolicyReachesTheChild() {
        String previous = System.getProperty(ConsoleColors.COLOR_KEY);
        try {
            System.setProperty(ConsoleColors.COLOR_KEY, "always");
            VidocqRunMojo mojo = new VidocqRunMojo();
            mojo.setExtraSystemProperties("");

            assertEquals("always", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        } finally {
            if (previous == null) {
                System.clearProperty(ConsoleColors.COLOR_KEY);
            } else {
                System.setProperty(ConsoleColors.COLOR_KEY, previous);
            }
        }
    }

    @Test
    void skipRunsNothing() throws Exception {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setSkip(true);

        // Nothing else is set: a goal that skipped must not read the project, let alone fork a JVM.
        mojo.execute();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aZeroExitCodeIsASuccess(@TempDir Path tmp) throws Exception {
        VidocqRunMojo mojo = newMojo();

        mojo.await(javac(tmp, "--version"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aNonZeroExitCodeFailsTheBuild(@TempDir Path tmp) {
        VidocqRunMojo mojo = newMojo();

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> mojo.await(javac(tmp, "--no-such-option")));

        assertTrue(failure.getMessage().startsWith("Vidocq run: the application exited with code 2."),
                failure.getMessage());
    }

    /** Layer mode keeps the application classes off the module path: a module must not be on both. */
    @Test
    void theModulePathCarriesTheDependenciesAndTheAppPathTheProjectClasses(@TempDir Path tmp) {
        MavenProject project = project();
        Path classes = tmp.resolve("target/classes");

        assertEquals(List.of(), ApplicationLaunch.modulePath(project, tmp, classes, true, jar -> { }));
        assertEquals(List.of(classes), ApplicationLaunch.appPath(classes, true));
        assertEquals(List.of(classes), ApplicationLaunch.modulePath(project, tmp, classes, false, jar -> { }));
        assertEquals(List.of(), ApplicationLaunch.appPath(classes, false));
    }

    private static VidocqRunMojo newMojo() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setGracePeriodMillis(5000);
        return mojo;
    }

    /** A child JVM that is a real JVM, and whose exit code the arguments choose. */
    private static ChildJvm javac(Path tmp, String argument) {
        return ChildJvm.of(List.of(tmp), List.of(), "jdk.compiler", "com.sun.tools.javac.Main",
                List.of(), Map.of(), tmp, List.of(argument));
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
