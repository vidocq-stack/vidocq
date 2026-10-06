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
package io.vidocq.runtime.cli.completion;

import io.vidocq.runtime.cli.build.BuildType;
import io.vidocq.runtime.cli.ext.KnownExtensions;
import io.vidocq.runtime.cli.ext.RegistryEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@code vidocq <TAB>} offers after each word — the single description both
 * {@link CompletionScripts} turn into a bash and a zsh script, and that
 * {@link #candidates(List)} answers in Java so it can be tested against {@link io.vidocq.runtime.cli.CliParser}.
 *
 * <p>A context is the command, plus the sub-command for the commands that have some
 * ({@code extension list}). A word starting with {@code @} is computed by the script when
 * completing, without starting a JVM: {@link #FILES}, {@link #CONFIG_KEYS},
 * {@link #POM_EXTENSIONS}.</p>
 */
public final class CompletionSpec {

    /** Paths, completed by the shell. */
    public static final String FILES = "@files";
    /** Keys of the project's {@code vidocq.properties}. */
    public static final String CONFIG_KEYS = "@config-keys";
    /** Extension ids the project's {@code pom.xml} declares. */
    public static final String POM_EXTENSIONS = "@pom-extensions";

    static final Map<String, String> COMMAND_ALIASES = Map.of("ext", "extension");
    static final Map<String, String> SUBCOMMAND_ALIASES = Map.of("ls", "list", "rm", "remove");

    /** Commands whose first argument is a sub-command, with the sub-commands offered. */
    static final Map<String, List<String>> SUBCOMMANDS = ordered(
            "extension", List.of("list", "add", "remove"),
            "config", List.of("get", "set", "list"),
            "completion", List.of("bash", "zsh", "install", "uninstall"));

    /** Offered only as the first argument of the command, before {@link #ARGUMENTS}. */
    static final Map<String, List<String>> FIRST_ARGUMENTS = ordered(
            "build", Arrays.stream(BuildType.values()).map(BuildType::token).toList(),
            "help", CommandCatalog.COMMANDS,
            "config get", List.of(CONFIG_KEYS),
            "config set", List.of(CONFIG_KEYS),
            "completion install", Shell.tokens(),
            "completion uninstall", Shell.tokens());

    /** Offered anywhere after the context. */
    static final Map<String, List<String>> ARGUMENTS = ordered(
            "start", List.of("--port", "--config", "--debug"),
            "dev", List.of("--port", "--profile", "--debug"),
            "doctor", List.of("--verbose"),
            "create", List.of("--name", "--group-id", "--package", "--extension", "--parent-version"),
            "build", List.of("--offline", "--skip-tests", "--dry-run"),
            "clean", List.of("--offline", "--dry-run"),
            "extension list", List.of("--installed", "--available", "--all", "--refresh"),
            "extension add", extensionIds(),
            "extension remove", List.of(POM_EXTENSIONS));

    /** Values offered right after an option, keyed {@code "<context> <option>"}. */
    static final Map<String, List<String>> OPTION_VALUES = ordered(
            "create --extension", extensionIds(),
            "create -x", extensionIds(),
            "start --config", List.of(FILES),
            "start -c", List.of(FILES));

    /** Options followed by a value — nothing is offered for a free-form one. */
    static final Set<String> VALUE_OPTIONS = Set.of(
            "--port", "-p", "--config", "-c", "--profile", "-P", "--name", "-n",
            "--group-id", "-g", "--package", "--extension", "-x", "--parent-version");

    private CompletionSpec() {}

    /** Whether {@code option} consumes the next word. */
    public static boolean takesValue(String option) {
        return VALUE_OPTIONS.contains(option);
    }

    /**
     * The words offered after {@code typed} — the words already typed after
     * {@code vidocq}, the one being completed excluded.
     */
    public static List<String> candidates(List<String> typed) {
        if (typed.isEmpty()) {
            return CommandCatalog.COMMANDS;
        }
        String command = COMMAND_ALIASES.getOrDefault(typed.getFirst(), typed.getFirst());
        String context = command;
        int position = typed.size(); // index of the word being completed, 1 = first argument
        if (SUBCOMMANDS.containsKey(command)) {
            if (typed.size() == 1) {
                return SUBCOMMANDS.get(command);
            }
            String sub = SUBCOMMAND_ALIASES.getOrDefault(typed.get(1), typed.get(1));
            context = command + " " + sub;
            position = typed.size() - 1;
        }
        String previous = typed.getLast();
        List<String> values = OPTION_VALUES.get(context + " " + previous);
        if (values != null) {
            return values;
        }
        if (takesValue(previous)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (position == 1) {
            result.addAll(FIRST_ARGUMENTS.getOrDefault(context, List.of()));
        }
        result.addAll(ARGUMENTS.getOrDefault(context, List.of()));
        return List.copyOf(result);
    }

    /** Every context, as the words that reach it — for tests walking the whole spec. */
    public static List<List<String>> contexts() {
        List<List<String>> contexts = new ArrayList<>();
        for (String command : CommandCatalog.COMMANDS) {
            if (SUBCOMMANDS.containsKey(command)) {
                SUBCOMMANDS.get(command).forEach(sub -> contexts.add(List.of(command, sub)));
            } else {
                contexts.add(List.of(command));
            }
        }
        return contexts;
    }

    private static List<String> extensionIds() {
        return KnownExtensions.catalog().stream().map(RegistryEntry::id).toList();
    }

    private static Map<String, List<String>> ordered(Object... keysAndValues) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> value = (List<String>) keysAndValues[i + 1];
            map.put((String) keysAndValues[i], List.copyOf(value));
        }
        return Collections.unmodifiableMap(map);
    }
}
