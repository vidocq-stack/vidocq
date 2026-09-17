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
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    @Test
    void addsJdwpAgentWhenDebugEnabled() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"),
                mojo.debugJvmArgs());
    }

    @Test
    void honoursCustomPortAndSuspend() {
        VidocqRunMojo mojo = new VidocqRunMojo();
        mojo.setExtraJvmArgs("-Xmx256m -XX:+UseZGC");
        mojo.setDebugOptions(true, 18099, true);

        assertEquals(List.of("-Xmx256m", "-XX:+UseZGC",
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:18099"),
                mojo.debugJvmArgs());
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
