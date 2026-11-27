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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.completion.CommandCatalog;
import io.vidocq.runtime.cli.completion.CompletionSpec;
import io.vidocq.runtime.cli.completion.ShortOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq run} runs {@code vidocq:run}, under the goal's name; {@code vidocq start}, its name up to 0.4.0, stays
 * a deprecated alias that does the same thing (Vidocq/vidocq#779).
 */
class CliParserRunTest {

    @Test
    void runParsesTheOptionsOfTheRunGoal() {
        Command.Start run = assertInstanceOf(Command.Start.class, CliParser.parse(new String[]{
                "run", "--port", "9090", "--config", "app.properties", "--debug", "--maven-profile", "h2"}));

        assertEquals(new Command.Start(9090, true, Path.of("app.properties"), true, List.of("h2")), run);
    }

    @Test
    void startIsTheSameCommand() {
        String[] options = {"-p", "9090", "-c", "app.properties", "--debug"};
        assertEquals(CliParser.parse(with("run", options)), CliParser.parse(with("start", options)));
        assertEquals(CliParser.parse(new String[]{"run"}), CliParser.parse(new String[]{"start"}));
    }

    @Test
    void startSaysItIsDeprecatedInOneLine() {
        Optional<String> hint = CliParser.deprecation(new String[]{"start", "--port", "9090"});

        assertTrue(hint.isPresent());
        assertTrue(hint.get().contains("'vidocq run'"), hint.get());
        assertFalse(hint.get().contains("\n"), hint.get());
        assertEquals(Optional.empty(), CliParser.deprecation(new String[]{"run"}));
        assertEquals(Optional.empty(), CliParser.deprecation(new String[]{"dev", "start"}));
    }

    @Test
    void anUnknownOptionNamesRunEvenWhenStartWasTyped() {
        for (String command : List.of("run", "start")) {
            CliException e = assertThrows(CliException.class,
                    () -> CliParser.parse(new String[]{command, "--bogus"}));

            assertEquals("Unknown option '--bogus' for 'run'. Run 'vidocq help run'.", e.getMessage());
        }
    }

    @Test
    void theHelpOfStartIsTheHelpOfRun() {
        assertEquals(help("run"), help("start"));
        assertEquals(new Command.Help("run"), CliParser.parse(new String[]{"start", "--help"}));
        assertEquals(new Command.Help("run"), CliParser.parse(new String[]{"run", "-h"}));
        String run = help("run");
        assertTrue(run.startsWith("vidocq run — Build and run the application"), run);
        assertTrue(run.contains("'vidocq start'"), "the alias is named: " + run);
    }

    @Test
    void theCommandListNamesRunAndNotStart() {
        String commands = help(null);

        assertTrue(commands.contains("run "), commands);
        assertFalse(commands.matches("(?s).*\\bstart\\b.*"), commands);
    }

    @Test
    void completionOffersRunAndHidesStartButStillCompletesItsOptions() {
        assertTrue(CommandCatalog.COMMANDS.contains("run"));
        assertFalse(CommandCatalog.COMMANDS.contains("start"));
        assertTrue(ShortOptions.contexts().contains("run"));
        assertFalse(ShortOptions.contexts().contains("start"));
        assertEquals(CompletionSpec.candidates(List.of("run"), "-"), CompletionSpec.candidates(List.of("start"), "-"));
        assertTrue(CompletionSpec.candidates(List.of("run")).contains("--port"));
    }

    @Test
    void createSaysToRunWithRun() {
        assertTrue(CommandRunner.nextSteps(false).stream().anyMatch(l -> l.contains("vidocq run")),
                CommandRunner.nextSteps(false).toString());
        assertTrue(CommandRunner.nextSteps(false).stream().noneMatch(l -> l.contains("vidocq start")));
    }

    private static String[] with(String command, String... options) {
        String[] args = new String[options.length + 1];
        args[0] = command;
        System.arraycopy(options, 0, args, 1, options.length);
        return args;
    }

    private static String help(String topic) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CliParser.printHelp(topic);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
