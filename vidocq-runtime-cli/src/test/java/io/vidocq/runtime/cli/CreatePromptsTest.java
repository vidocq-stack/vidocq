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

import io.vidocq.runtime.tooling.ProjectSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#339 (evolution 4): {@code vidocq create} without {@code --name} asks for what it needs. */
class CreatePromptsTest {

    /** Answers from a script; a {@code null} answer is the end of the input. */
    private static final class Scripted implements CreatePrompts.Prompter {
        final Deque<String> answers;
        final List<String> questions = new ArrayList<>();
        final List<String> shown = new ArrayList<>();

        Scripted(String... answers) {
            this.answers = new ArrayDeque<>();
            for (String a : answers) {
                this.answers.add(a == null ? "\0" : a);
            }
        }

        @Override
        public String ask(String question, String defaultValue) {
            questions.add(question + (defaultValue == null ? "" : " [" + defaultValue + "]"));
            String answer = answers.poll();
            if (answer == null || answer.equals("\0")) {
                return null;
            }
            return answer.isEmpty() ? (defaultValue == null ? "" : defaultValue) : answer;
        }

        @Override
        public void show(String line) {
            shown.add(line);
        }
    }

    private static final Command.CreateInteractively NOTHING_GIVEN =
            new Command.CreateInteractively(null, null, Set.of(), null, false, false);

    @Test
    void asksTheNameTheGroupAndTheExtensions() {
        Scripted prompter = new Scripted("todo", "com.acme", "cassini-rest, knock-health");

        ProjectSpec spec = CreatePrompts.ask(NOTHING_GIVEN, prompter);

        assertEquals("todo", spec.name());
        assertEquals("com.acme", spec.groupId());
        assertEquals("com.acme.todo", spec.pkg());
        assertEquals(Set.of("cassini-rest", "knock-health"), spec.extensions());
        assertEquals(List.of("Project name", "Group id [io.example]", "Extensions, comma-separated [cassini-rest]"),
                prompter.questions);
    }

    /** Enter takes the defaults: a REST application, which {@code vidocq create} gives a first test. */
    @Test
    void enterTakesTheDefaults() {
        ProjectSpec spec = CreatePrompts.ask(NOTHING_GIVEN, new Scripted("todo", "", ""));

        assertEquals("io.example", spec.groupId());
        assertEquals(Set.of("cassini-rest"), spec.extensions());
    }

    @Test
    void noneMeansNoExtension() {
        assertEquals(Set.of(), CreatePrompts.ask(NOTHING_GIVEN, new Scripted("todo", "", "none")).extensions());
    }

    @Test
    void theCatalogIsShownBeforeTheExtensionsAreAsked() {
        Scripted prompter = new Scripted("todo", "", "");

        CreatePrompts.ask(NOTHING_GIVEN, prompter);

        assertTrue(prompter.shown.stream().anyMatch(l -> l.contains("cassini-rest")
                && l.contains("Jakarta REST")), prompter.shown.toString());
    }

    /** Grouped by family, as {@code vidocq extension list --available} shows it (Vidocq/vidocq#353). */
    @Test
    void theCatalogIsShownByFamily() {
        Scripted prompter = new Scripted("todo", "", "");

        CreatePrompts.ask(NOTHING_GIVEN, prompter);

        List<String> shown = prompter.shown;
        int jakarta = shown.indexOf("  Jakarta EE");
        int microprofile = shown.indexOf("  MicroProfile");
        assertTrue(jakarta >= 0 && microprofile > jakarta, shown.toString());
        int rest = shown.indexOf(shown.stream().filter(l -> l.contains("cassini-rest")).findFirst().orElseThrow());
        int health = shown.indexOf(shown.stream().filter(l -> l.contains("knock-health")).findFirst().orElseThrow());
        assertTrue(jakarta < rest && rest < microprofile && microprofile < health, shown.toString());
    }

    /** What the command line gave is not asked again. */
    @Test
    void optionsGivenOnTheCommandLineAreKept() {
        Scripted prompter = new Scripted("todo");

        ProjectSpec spec = CreatePrompts.ask(
                new Command.CreateInteractively("com.acme", null, Set.of("knock-health"), "0.4.0", true, false), prompter);

        assertEquals(List.of("Project name"), prompter.questions);
        assertEquals("com.acme", spec.groupId());
        assertEquals(Set.of("knock-health"), spec.extensions());
        assertEquals("0.4.0", spec.parentVersion());
        assertTrue(spec.main());
    }

    @Test
    void aWrongAnswerIsExplainedAndAskedAgain() {
        Scripted prompter = new Scripted("", "ft-030", "todo", "", "cassini-rst", "cassini-rest");

        ProjectSpec spec = CreatePrompts.ask(NOTHING_GIVEN, prompter);

        assertEquals("todo", spec.name());
        assertEquals(Set.of("cassini-rest"), spec.extensions());
        assertTrue(prompter.shown.stream().anyMatch(l -> l.contains("'io.example.ft.030' is not a valid Java package")),
                prompter.shown.toString());
        assertTrue(prompter.shown.stream().anyMatch(l -> l.contains("unknown extension 'cassini-rst'")
                && l.contains("Did you mean: cassini-rest")), prompter.shown.toString());
    }

    @Test
    void theEndOfTheInputStopsTheCommand() {
        Scripted prompter = new Scripted((String) null);

        var e = assertThrows(CliException.class, () -> CreatePrompts.ask(NOTHING_GIVEN, prompter));

        assertEquals("'create' stopped: no project name given.", e.getMessage());
    }

    /** Surefire gives the tests no terminal: a script or a CI job, where nobody would answer. */
    @Test
    void withoutAConsoleANameIsRequired() {
        Command command = CliParser.parse(new String[]{"create"});

        var e = assertThrows(CliException.class, () -> CommandRunner.run(command));

        assertEquals("'create' requires --name <app-name>.", e.getMessage());
    }

    @Test
    void withoutANameTheParserLeavesTheQuestionsToTheRunner() {
        Command command = CliParser.parse(new String[]{"create", "-g", "com.acme", "-x", "knock-health"});

        Command.CreateInteractively interactive = (Command.CreateInteractively) command;
        assertEquals("com.acme", interactive.groupId());
        assertEquals(Set.of("knock-health"), interactive.extensions());
        assertNull(interactive.parentVersion());
    }
}
