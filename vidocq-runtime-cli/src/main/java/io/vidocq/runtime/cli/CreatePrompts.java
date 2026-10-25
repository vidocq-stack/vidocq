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

import io.vidocq.runtime.tooling.KnownExtensions;
import io.vidocq.runtime.tooling.ProjectSpec;
import io.vidocq.runtime.tooling.RegistryEntry;

import java.io.Console;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The questions {@code vidocq create} asks on a console when {@code --name} is missing: the name, then the group id
 * and the extensions unless the command line gave them (Vidocq/vidocq#339). A wrong answer is explained and asked
 * again; the end of the input stops the command.
 */
final class CreatePrompts {

    /** The extensions Enter takes: a REST application, which {@code vidocq create} gives a first test. */
    static final String DEFAULT_EXTENSIONS = "cassini-rest";

    private static final String PACKAGE_OPTION = "--package <pkg>";

    /** Where the questions go and the answers come from. */
    interface Prompter {
        /**
         * The answer: {@code defaultValue} for an empty one, or {@code ""} without a default; {@code null} at the end
         * of the input.
         */
        String ask(String question, String defaultValue);

        void show(String line);
    }

    private CreatePrompts() {}

    static ProjectSpec ask(Command.CreateInteractively given, Prompter prompter) {
        String name = askUntilValid(prompter, "Project name", null, "no project name given",
                answer -> spec(given, answer, given.groupId(), Set.of()));
        String groupId = given.groupId() != null ? given.groupId()
                : askUntilValid(prompter, "Group id", ProjectSpec.DEFAULT_GROUP_ID, "no group id given",
                        answer -> spec(given, name, answer, Set.of()));
        Set<String> extensions = given.extensions();
        if (extensions.isEmpty()) {
            showCatalog(prompter);
            extensions = ids(askUntilValid(prompter, "Extensions, comma-separated", DEFAULT_EXTENSIONS,
                    "no extensions given", answer -> spec(given, name, groupId, ids(answer))));
        }
        return spec(given, name, groupId, extensions);
    }

    /** A console prompter, or {@code null} when the CLI does not run on one. */
    static Prompter console() {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return null;
        }
        return new Prompter() {
            @Override
            public String ask(String question, String defaultValue) {
                String line = console.readLine("%s%s: ", question,
                        defaultValue == null ? "" : " [" + defaultValue + "]");
                if (line == null) {
                    return null;
                }
                if (!line.isBlank()) {
                    return line.strip();
                }
                return defaultValue == null ? "" : defaultValue;
            }

            @Override
            public void show(String line) {
                CliOutput.println(line);
            }
        };
    }

    /** Asks {@code question} until {@code check} accepts the answer, showing why it did not. */
    private static String askUntilValid(Prompter prompter, String question, String defaultValue, String missing,
                                        Consumer<String> check) {
        while (true) {
            String answer = prompter.ask(question, defaultValue);
            if (answer == null) {
                throw new CliException("'create' stopped: " + missing + ".");
            }
            try {
                check.accept(answer);
                return answer;
            } catch (IllegalArgumentException e) {
                prompter.show("  " + e.getMessage());
            }
        }
    }

    private static void showCatalog(Prompter prompter) {
        prompter.show("Extensions of the Vidocq catalog (\"none\" for no extension):");
        for (RegistryEntry e : KnownExtensions.catalog()) {
            prompter.show(String.format("  %-28s %s", e.id(), e.description()));
        }
    }

    private static Set<String> ids(String answer) {
        if (answer.isBlank() || answer.strip().equalsIgnoreCase("none")) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(answer.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(ids::add);
        return ids;
    }

    private static ProjectSpec spec(Command.CreateInteractively given, String name, String groupId,
                                    Set<String> extensions) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A project needs a name.");
        }
        return ProjectSpec.of(name, groupId, given.pkg(), extensions, given.parentVersion(), given.main(),
                PACKAGE_OPTION);
    }
}
