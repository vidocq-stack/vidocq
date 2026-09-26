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
package io.vidocq.runtime.spi.devconsole;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Something a {@link DevConsolePanel} offers to do from the dev console's page, such as setting a log level or
 * migrating a database again, as its {@link DevConsolePanel#actions() actions()} lists it. Checked when it is built,
 * so that a mistake fails where it is written rather than on the page.
 *
 * <p><b>Development only.</b> The console calls {@code actions()}, shows the buttons and accepts a request to run one
 * in a {@link io.vidocq.runtime.spi.report.LaunchMode#DEV dev} launch only, and only a request that its own page
 * sent (ADR 0001). An action is still code that changes the running application: keep it harmless on a developer's
 * machine.
 *
 * <p><b>What it receives.</b> Only the arguments it declared, each a string the console checked against its
 * {@link Argument} before calling {@link #run}: a value among a list, or a whole match of a regular expression, 200
 * characters at most. An action never takes a class name, a file path or a URL to act on: it acts on what its
 * panel already holds, and an argument picks among those things, such as a pool by name or a level. A
 * {@link Argument#json json} argument is the exception: its value is the text of a JSON object, of any length the
 * console's body limit lets through, described by a JSON Schema.
 *
 * <p><b>What it returns.</b> One short line of text, such as {@code 3 migrations applied}, that the page shows and the
 * console logs; {@code null} reads as {@code done}. It runs on a virtual thread of the console, one action at a time
 * per panel; past 60 seconds the page stops waiting and the outcome shows once it ends. An exception it throws is
 * shown and logged by its class only, never its message, which may carry a secret.
 *
 * <pre>{@code
 * new PanelAction("set-level", "Set level", null,
 *         List.of(PanelAction.Argument.matching("logger", "Logger", "[A-Za-z0-9_.$]{1,120}"),
 *                 PanelAction.Argument.oneOf("level", "Level", "ERROR", "WARNING", "INFO", "DEBUG")),
 *         arguments -> levels.set(arguments.get("logger"), arguments.get("level")))
 * }</pre>
 *
 * @param id           identifies the action among those of its panel, stable across boots; it follows the rule of
 *                     {@link PanelSample#requireKey}, such as {@code clean-and-migrate}
 * @param label        what its button says, such as {@code Clean and migrate}; neither {@code null} nor blank
 * @param confirmation the question the page asks, and has answered, before sending the request, such as
 *                     {@code Drop every table of @Default and migrate again?}; {@code null} to send on the first
 *                     click, never blank
 * @param arguments    what the page asks for before sending, in that order, each name once; an immutable copy
 * @param run          does the work with the checked arguments, by name, and returns one short line of text
 */
public record PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                          Function<Map<String, String>, String> run) {

    /** The longest value an argument accepts. */
    public static final int MAX_VALUE_LENGTH = 200;

    public PanelAction {
        PanelSample.requireKey(id);
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("action '" + id + "' has a blank label");
        }
        if (confirmation != null && confirmation.isBlank()) {
            throw new IllegalArgumentException("action '" + id + "' has a blank confirmation: null for none");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(run, "run");
        Set<String> names = new HashSet<>();
        for (Argument argument : arguments) {
            if (!names.add(argument.name())) {
                throw new IllegalArgumentException("action '" + id + "' declares argument " + argument.name()
                        + " twice");
            }
        }
        if (arguments.stream().filter(argument -> argument.schema() != null).count() > 1) {
            throw new IllegalArgumentException("action '" + id + "' declares more than one json argument");
        }
    }

    /**
     * An action that takes no argument.
     *
     * @param id           its id, by the key rule
     * @param label        its button
     * @param confirmation the question asked before sending, or {@code null}
     * @param run          what it does, called with an empty map
     */
    public PanelAction(String id, String label, String confirmation, Function<Map<String, String>, String> run) {
        this(id, label, confirmation, List.of(), run);
    }

    /**
     * One value an action takes, and what the console accepts for it: a value of {@code allowedValues}, which the
     * page offers as a list; a whole match of {@code pattern}, which it offers as a text field; or a JSON object
     * described by the JSON Schema {@code schema}, which it offers as a form when the schema is flat and as a JSON
     * editor otherwise. Exactly one of the three is set.
     *
     * @param name          the key of the value in the request and in the map the action receives; it follows the
     *                      rule of {@link PanelSample#requireKey}, such as {@code level}
     * @param label         what the page writes next to its field; neither {@code null} nor blank
     * @param allowedValues the values accepted, in the order the page lists them, at least one, none blank nor
     *                      longer than {@value #MAX_VALUE_LENGTH} characters; an immutable copy, or {@code null}
     * @param pattern       the regular expression, {@link Pattern} syntax, that a value must match as a whole, or
     *                      {@code null}
     * @param schema        a JSON Schema, as the text of a JSON object of at most {@value #MAX_SCHEMA} characters,
     *                      or {@code null}; the console never validates a value against it, the action's target does
     */
    public record Argument(String name, String label, List<String> allowedValues, String pattern, String schema) {

        /** The longest schema of a {@link #json json} argument, in characters. */
        public static final int MAX_SCHEMA = 32 * 1024;
        /** How deep a {@link #json json} value or schema may nest. */
        public static final int MAX_JSON_DEPTH = 64;

        public Argument {
            PanelSample.requireKey(name);
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("argument '" + name + "' has a blank label");
            }
            int kinds = (allowedValues == null ? 0 : 1) + (pattern == null ? 0 : 1) + (schema == null ? 0 : 1);
            if (kinds != 1) {
                throw new IllegalArgumentException("argument '" + name
                        + "' needs exactly one of its allowed values, a pattern or a schema");
            }
            if (allowedValues != null) {
                allowedValues = List.copyOf(allowedValues);
                if (allowedValues.isEmpty()) {
                    throw new IllegalArgumentException("argument '" + name + "' allows no value");
                }
                for (String value : allowedValues) {
                    if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                        throw new IllegalArgumentException("argument '" + name + "' allows a blank value or one "
                                + "longer than " + MAX_VALUE_LENGTH + " characters");
                    }
                }
            } else if (pattern != null) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException invalid) {
                    throw new IllegalArgumentException("argument '" + name + "' has an invalid pattern", invalid);
                }
            } else if (schema.length() > MAX_SCHEMA || !JsonCheck.isObject(schema, MAX_JSON_DEPTH)) {
                throw new IllegalArgumentException("argument '" + name + "' has a schema that is not a JSON object "
                        + "of at most " + MAX_SCHEMA + " characters");
            }
        }

        /**
         * A value among a list, or a whole match of a pattern: the two string kinds, as before the {@link #json json}
         * kind existed.
         *
         * @param name          its name, by the key rule
         * @param label         what the page writes next to it
         * @param allowedValues the values accepted, or {@code null} when {@code pattern} is set
         * @param pattern       the regular expression, or {@code null} when {@code allowedValues} is set
         */
        public Argument(String name, String label, List<String> allowedValues, String pattern) {
            this(name, label, allowedValues, pattern, null);
        }

        /**
         * An argument whose value is one of {@code values}.
         *
         * @param name   its name, by the key rule
         * @param label  what the page writes next to it
         * @param values the values accepted, at least one
         * @return the argument
         */
        public static Argument oneOf(String name, String label, String... values) {
            return new Argument(name, label, List.of(values), null, null);
        }

        /**
         * An argument whose value matches {@code regex} as a whole.
         *
         * @param name  its name, by the key rule
         * @param label what the page writes next to it
         * @param regex a regular expression, {@link Pattern} syntax; keep it as narrow as the value it takes
         * @return the argument
         */
        public static Argument matching(String name, String label, String regex) {
            return new Argument(name, label, null, Objects.requireNonNull(regex, "regex"), null);
        }

        /**
         * An argument whose value is a JSON object, such as the arguments of an MCP tool. The action receives it as
         * its JSON text. One action has one such argument at most.
         *
         * @param name   its name, by the key rule
         * @param label  what the page writes next to it
         * @param schema a JSON Schema describing the object, as the text of a JSON object
         * @return the argument
         * @throws IllegalArgumentException when {@code schema} is not a JSON object of at most
         *                                  {@value #MAX_SCHEMA} characters
         */
        public static Argument json(String name, String label, String schema) {
            return new Argument(name, label, null, null, Objects.requireNonNull(schema, "schema"));
        }

        /**
         * Whether the console passes {@code value} to the action: not {@code null}, and for a {@link #json json}
         * argument one JSON object, whatever its length; for a string argument at most {@value #MAX_VALUE_LENGTH}
         * characters, and one of the allowed values or a whole match of the pattern.
         *
         * @param value the value a request carries
         * @return {@code true} when it is accepted
         */
        public boolean accepts(String value) {
            if (value == null) {
                return false;
            }
            if (schema != null) {
                return JsonCheck.isObject(value, MAX_JSON_DEPTH);
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                return false;
            }
            return allowedValues != null ? allowedValues.contains(value) : Pattern.matches(pattern, value);
        }
    }
}
