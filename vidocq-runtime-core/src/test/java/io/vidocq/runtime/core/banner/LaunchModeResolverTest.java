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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.core.banner.BannerTestSupport.Records;
import io.vidocq.runtime.core.banner.LaunchModeResolver.Inputs;
import io.vidocq.runtime.core.banner.LaunchModeResolver.Resolution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The six resolution rules, first match wins, and what each reason says. */
class LaunchModeResolverTest {

    /** A tree of build outputs, built once: the archive rules are the only ones that touch a disk. */
    private static final Path TREE = tree();

    private static final List<String> NO_FRAME = List.of("io.vidocq.runtime.core.VidocqBootstrap",
            "io.vidocq.tools.Application");
    private static final List<String> JUNIT_FRAME = List.of("io.vidocq.runtime.core.VidocqBootstrap",
            "org.junit.platform.engine.support.hierarchical.NodeTestTask");

    @AfterAll
    static void removeTheTree() throws IOException {
        try (Stream<Path> paths = Files.walk(TREE)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                // (a) the configured mode wins over every other signal
                Arguments.of("the configured mode", inputs("prod", "dev", "/tmp/reload", true, JUNIT_FRAME,
                        List.of(TREE.resolve("maven-app/target/classes")), true), LaunchMode.PROD,
                        "vidocq.launch.mode"),
                Arguments.of("its value is read like a name", inputs(" DeV ", null, null, false, NO_FRAME,
                        List.of(), false), LaunchMode.DEV, "vidocq.launch.mode"),
                // (a) an invalid value is no signal: the detection goes on
                Arguments.of("an invalid configured mode", inputs("wild", "test", null, false, NO_FRAME,
                        List.of(), false), LaunchMode.TEST, "profile test"),
                // (b) the profile, when it is a mode
                Arguments.of("the profile", inputs(null, "dev", null, false, NO_FRAME, List.of(), false),
                        LaunchMode.DEV, "profile dev"),
                Arguments.of("a prod profile is a signal", inputs(null, "PROD", null, false, NO_FRAME,
                        List.of(), false), LaunchMode.PROD, "profile prod"),
                // (b) another profile is not a mode: it falls through
                Arguments.of("a profile that is no mode", inputs(null, "staging", "/tmp/reload", false, NO_FRAME,
                        List.of(), false), LaunchMode.DEV, "dev reload loop"),
                Arguments.of("a profile that is no mode, alone", inputs(null, "staging", null, false, NO_FRAME,
                        List.of(), false), LaunchMode.PROD, "no dev or test signal"),
                // (c) the reload loop, before the test frames
                Arguments.of("the reload loop", inputs(null, null, "/tmp/reload", true, JUNIT_FRAME,
                        List.of(), false), LaunchMode.DEV, "dev reload loop"),
                // (d) a test frame on the booting thread, then the test runtime probe
                Arguments.of("a JUnit frame", inputs(null, null, null, false, JUNIT_FRAME,
                        List.of(TREE.resolve("maven-app/target/classes")), true), LaunchMode.TEST,
                        "JUnit on the stack"),
                Arguments.of("a TestNG frame", inputs(null, null, null, false, List.of("org.testng.TestRunner"),
                        List.of(), false), LaunchMode.TEST, "TestNG on the stack"),
                Arguments.of("a Surefire frame", inputs(null, null, null, false,
                        List.of("org.apache.maven.surefire.booter.ForkedBooter"), List.of(), false),
                        LaunchMode.TEST, "Surefire on the stack"),
                Arguments.of("an Arquillian frame", inputs(null, null, null, false,
                        List.of("org.jboss.arquillian.junit5.ArquillianExtension"), List.of(), false),
                        LaunchMode.TEST, "Arquillian on the stack"),
                Arguments.of("the test runtime probe", inputs(null, null, null, true, NO_FRAME, List.of(), true),
                        LaunchMode.TEST, "JUnit on the class path"),
                // (e) a build tree, then IntelliJ's Run console
                Arguments.of("a Maven classes directory", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("maven-app/target/classes")), false), LaunchMode.DEV,
                        "target/classes with a pom.xml above"),
                Arguments.of("a Gradle classes directory", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("gradle-app/build/classes/java/main")), false), LaunchMode.DEV,
                        "build/classes/java/main"),
                Arguments.of("an IntelliJ output directory", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("idea-app/out/production/idea-app")), false), LaunchMode.DEV,
                        "out/production/idea-app"),
                Arguments.of("classes under a build file", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("plain/classes")), false), LaunchMode.DEV,
                        "a build.gradle above the classes"),
                Arguments.of("the application jar, then a classes directory", inputs(null, null, null, false,
                        NO_FRAME, List.of(TREE.resolve("packaged/app.jar"),
                                TREE.resolve("maven-app/target/classes")), false), LaunchMode.DEV,
                        "target/classes with a pom.xml above"),
                Arguments.of("IntelliJ's Run console", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("packaged/app.jar")), true), LaunchMode.DEV, "IntelliJ agent"),
                // (f) nothing at all
                Arguments.of("a packaged application", inputs(null, null, null, false, NO_FRAME,
                        List.of(TREE.resolve("packaged/app.jar")), false), LaunchMode.PROD,
                        "no dev or test signal"),
                Arguments.of("nothing at all", inputs(null, null, null, false, List.of(), List.of(), false),
                        LaunchMode.PROD, "no dev or test signal"));
    }

    @ParameterizedTest(name = "{0} -> {2} ({3})")
    @MethodSource("rules")
    void firstMatchWins(String signal, Inputs inputs, LaunchMode mode, String reason) {
        Resolution resolution = LaunchModeResolver.resolve(inputs);

        assertEquals(mode, resolution.mode(), signal);
        assertEquals(reason, resolution.reason(), signal);
        assertEquals(mode.label() + " (" + reason + ")", resolution.text());
    }

    @Test
    void anInvalidConfiguredModeWarnsOnceAndLetsTheDetectionDecide() {
        try (Records records = new Records(LaunchModeResolver.class.getName())) {
            Resolution resolution = LaunchModeResolver.resolve(
                    inputs("staging", null, null, false, NO_FRAME, List.of(), false));

            assertEquals(List.of("Configuration key 'vidocq.launch.mode' has an unknown value 'staging'"
                    + " (expected dev, test or prod); detecting the launch mode"), records.messages(Level.WARNING));
            assertEquals(LaunchMode.PROD, resolution.mode());
            assertEquals("no dev or test signal", resolution.reason());
        }
    }

    @Test
    void aProdThatNoSignalProvesNeverDropsItsReason() {
        Resolution nothing = LaunchModeResolver.resolve(inputs(null, null, null, false, List.of(), List.of(), false));
        Resolution configured = LaunchModeResolver.resolve(inputs("prod", null, null, false, List.of(), List.of(),
                false));

        assertFalse(nothing.signalled());
        assertEquals(null, nothing.shortText(), "'prod' alone would claim more than is known");
        assertTrue(configured.signalled());
        assertEquals("prod", configured.shortText());
    }

    @Test
    void anArchiveThatIsNoDirectoryOrThatIsGoneIsNoSignal() {
        Resolution resolution = LaunchModeResolver.resolve(inputs(null, null, null, false, NO_FRAME,
                List.of(TREE.resolve("packaged/app.jar"), TREE.resolve("no/such/target/classes")), false));

        assertEquals(LaunchMode.PROD, resolution.mode());
        assertEquals("no dev or test signal", resolution.reason());
    }

    @Test
    void onlyTheFirstArchivesAreLookedAt() {
        List<Path> archives = new java.util.ArrayList<>(List.of(TREE.resolve("packaged"), TREE.resolve("packaged"),
                TREE.resolve("packaged"), TREE.resolve("packaged")));
        archives.add(TREE.resolve("maven-app/target/classes"));

        assertEquals(LaunchMode.PROD, LaunchModeResolver.resolve(
                inputs(null, null, null, false, NO_FRAME, archives, false)).mode(),
                "the fifth archive is beyond the bound");
    }

    @Test
    void theInputsOfThisJvmAreReadWithoutThrowing() {
        Inputs inputs = Inputs.current(key -> {
            throw new IllegalStateException("no configuration yet");
        }, LaunchModeResolver.class.getModule(), BannerTestSupport.pipe());

        assertEquals(null, inputs.configuredMode());
        assertEquals(null, inputs.profile());
        assertFalse(inputs.stackFrames().isEmpty(), "the booting thread has frames");
        assertEquals(LaunchMode.TEST, LaunchModeResolver.resolve(inputs).mode(), "this JVM is a test run");
    }

    private static Inputs inputs(String mode, String profile, String reloadFile, boolean testRuntime,
                                 List<String> frames, List<Path> archives, boolean intellij) {
        return new Inputs(mode, profile, reloadFile, testRuntime, frames, archives, intellij);
    }

    private static Path tree() {
        try {
            Path root = Files.createTempDirectory("vidocq-launch-mode");
            Files.createDirectories(root.resolve("maven-app/target/classes"));
            Files.writeString(root.resolve("maven-app/pom.xml"), "<project/>");
            Files.createDirectories(root.resolve("gradle-app/build/classes/java/main"));
            Files.writeString(root.resolve("gradle-app/settings.gradle.kts"), "// four levels above the classes");
            Files.createDirectories(root.resolve("idea-app/out/production/idea-app"));
            Files.createDirectories(root.resolve("plain/classes"));
            Files.writeString(root.resolve("plain/build.gradle"), "// a Gradle build");
            Files.createDirectories(root.resolve("packaged"));
            Files.writeString(root.resolve("packaged/app.jar"), "not really a jar");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
