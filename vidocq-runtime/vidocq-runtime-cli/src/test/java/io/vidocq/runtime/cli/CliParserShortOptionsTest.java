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

import io.vidocq.runtime.cli.completion.ShortOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The short aliases the parser accepts, the ones its help shows and the ones completion offers are the same:
 * {@link ShortOptions} (Vidocq/vidocq#782).
 */
class CliParserShortOptionsTest {

    /** The commands that take options; {@code -h} asks for help everywhere and is not an alias. */
    private static final List<String> COMMANDS = List.of("start", "dev", "test", "doctor", "idea", "vscode",
            "eclipse", "create", "build", "clean", "update");

    @Test
    void aShortAliasParsesAsItsLongForm() {
        ShortOptions.contexts().forEach(context -> ShortOptions.of(context).forEach((alias, longForm) -> {
            String value = "--port".equals(longForm) ? "9090" : "value";
            boolean takesValue = needsValue(context, longForm);
            List<String> withLong = takesValue ? List.of(longForm, value) : List.of(longForm);
            List<String> withAlias = takesValue ? List.of(alias, value) : List.of(alias);
            assertEquals(parse(context, withLong.toArray(String[]::new)),
                    parse(context, withAlias.toArray(String[]::new)), context + " " + alias);
        }));
    }

    @Test
    void theParserAcceptsNoShortAliasTheTableLacks() {
        for (String command : COMMANDS) {
            for (char letter = 'A'; letter <= 'z'; letter++) {
                if (!Character.isLetter(letter) || letter == 'h') {
                    continue;
                }
                String alias = "-" + letter;
                boolean known = ShortOptions.of(command).containsKey(alias);
                assertEquals(known, accepts(command, alias),
                        "'" + command + " " + alias + "' parses: " + accepts(command, alias) + ", in the table: "
                                + known);
            }
        }
    }

    @Test
    void theHelpShowsTheShortAliasesOfTheTable() {
        for (String command : COMMANDS) {
            Matcher shown = Pattern.compile("(--[a-z-]+), (-[a-zA-Z])\\b").matcher(help(command));
            Map<String, String> inHelp = new TreeMap<>();
            while (shown.find()) {
                inHelp.put(shown.group(2), shown.group(1));
            }
            assertEquals(new TreeMap<>(ShortOptions.of(command)), inHelp, "help of " + command);
        }
    }

    /** Whether {@code option} reads the next word as its value. */
    private static boolean needsValue(String context, String option) {
        try {
            CliParser.parse(new String[]{context, option});
            return false;
        } catch (CliException e) {
            return e.getMessage().contains("requires a value");
        }
    }

    /** Whether the parser knows {@code option} (with a value when it takes one). */
    private static boolean accepts(String command, String option) {
        for (List<String> args : List.of(List.of(command, option), List.of(command, option, "8080"))) {
            try {
                CliParser.parse(args.toArray(String[]::new));
                return true;
            } catch (CliException e) {
                if (e.getMessage().startsWith("Unknown option '" + option + "'")) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object parse(String command, String... options) {
        List<String> args = new ArrayList<>(List.of(command));
        args.addAll(List.of(options));
        return CliParser.parse(args.toArray(String[]::new));
    }

    private static String help(String command) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CliParser.printHelp(command);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
