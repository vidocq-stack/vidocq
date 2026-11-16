/*
 * Copyright (c) 2026 Vidocq contributors
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --maven-profile <ids>}: the Maven profiles of the commands that run the project's build, apart from the
 * Vidocq configuration profile of {@code vidocq dev -P}.
 */
class CliParserMavenProfileTest {

    private static Command parse(String... args) {
        return CliParser.parse(args);
    }

    @Test
    void devKeepsItsConfigProfileApartFromTheMavenOnes() {
        Command.Dev dev = (Command.Dev) parse("dev", "-P", "staging", "--maven-profile", "jlink");

        assertEquals("staging", dev.profile());
        assertEquals(List.of("jlink"), dev.mavenProfiles());
    }

    @Test
    void theIdsAreCommaSeparatedAndTheOptionRepeatable() {
        Command.Start start = (Command.Start) parse("start", "--maven-profile", "jlink, native", "--maven-profile",
                "!tests");

        assertEquals(List.of("jlink", "native", "!tests"), start.mavenProfiles());
    }

    @Test
    void everyCommandThatRunsTheBuildTakesIt() {
        assertEquals(List.of("a"), ((Command.Start) parse("start", "--maven-profile", "a")).mavenProfiles());
        assertEquals(List.of("a"), ((Command.Dev) parse("dev", "--maven-profile", "a")).mavenProfiles());
        assertEquals(List.of("a"), ((Command.ContinuousTest) parse("test", "--maven-profile", "a")).mavenProfiles());
        assertEquals(List.of("a"), ((Command.Build) parse("build", "jlink", "--maven-profile", "a")).mavenProfiles());
        assertEquals(List.of("a"), ((Command.Clean) parse("clean", "--maven-profile", "a")).mavenProfiles());
    }

    @Test
    void withoutTheOptionNoMavenProfileIsAsked() {
        assertEquals(List.of(), ((Command.Dev) parse("dev")).mavenProfiles());
        assertEquals(List.of(), ((Command.ContinuousTest) parse("test", "--once")).mavenProfiles());
        assertEquals(List.of(), ((Command.Build) parse("build")).mavenProfiles());
    }

    @Test
    void itCombinesWithThePassThroughOfBuild() {
        Command.Build build = (Command.Build) parse("build", "jlink", "--maven-profile", "jlink", "--", "-X");

        assertEquals(List.of("jlink"), build.mavenProfiles());
        assertEquals(List.of("-X"), build.passthrough());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "jlink,", ",jlink", "a,,b"})
    void anEmptyIdIsRefused(String value) {
        CliException refused = assertThrows(CliException.class, () -> parse("dev", "--maven-profile", value));

        assertTrue(refused.getMessage().startsWith("Option --maven-profile expects"), refused.getMessage());
    }

    @Test
    void theOptionNeedsAValue() {
        CliException refused = assertThrows(CliException.class, () -> parse("test", "--maven-profile"));

        assertEquals("Option --maven-profile requires a value.", refused.getMessage());
    }
}
