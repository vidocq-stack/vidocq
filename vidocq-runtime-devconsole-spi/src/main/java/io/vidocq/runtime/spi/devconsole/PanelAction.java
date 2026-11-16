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
 * <p><b>What it returns.</b> One short line of text, such as {@code 3 migrations applied}, that the page shows and
 * the console logs, or an {@link ActionResult} that adds a body, an error flag and the details of the exchange,
 * which the page shows under the line; the line never carries a secret. It runs on a virtual thread of the console,
 * one action at a time per panel; past 60 seconds the page stops waiting and the outcome shows once it ends. An
 * exception it throws is shown and logged by its class only, never its message, which may carry a secret.
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
 * @param call         does the work with the checked arguments, by name, and returns its result
 * @param group        a short title such as {@code Tools}: the page shows each group as a tab of the panel, next to
 *                     a <i>Monitoring</i> tab, in order of first appearance, and adds a filter past ten actions of a
 *                     group; a table with a {@link PanelSample#REPLAY_COLUMN replay} column moves to the group tabs,
 *                     filtered to their actions; {@code null} for none
 * @param description  a longer text shown under the label, such as a tool's description, line breaks kept;
 *                     {@code null} for none
 */
public record PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                          Function<Map<String, String>, ActionResult> call, String group, String description) {

    /** The longest value an argument accepts. */
    public static final int MAX_VALUE_LENGTH = 200;
    /** The longest group title. */
    public static final int MAX_GROUP = 40;
    /** The longest description. */
    public static final int MAX_DESCRIPTION = 2000;

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
        Objects.requireNonNull(call, "call");
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
        if (group != null && (group.isBlank() || group.length() > MAX_GROUP)) {
            throw new IllegalArgumentException("action '" + id + "' has a blank group or one longer than "
                    + MAX_GROUP + " characters: null for none");
        }
        if (description != null && (description.isBlank() || description.length() > MAX_DESCRIPTION)) {
            throw new IllegalArgumentException("action '" + id + "' has a blank description or one longer than "
                    + MAX_DESCRIPTION + " characters: null for none");
        }
    }

    /**
     * An action that returns one line, as before {@link ActionResult} existed: {@code run} is called through
     * {@link #call}, its line becoming the result's summary.
     *
     * @param id           its id, by the key rule
     * @param label        its button
     * @param confirmation the question asked before sending, or {@code null}
     * @param arguments    what it takes
     * @param run          what it does, returning one short line
     */
    public PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                       Function<Map<String, String>, String> run) {
        this(id, label, confirmation, arguments, summaryOnly(run), null, null);
    }

    /**
     * An action that takes no argument and returns one line.
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
     * What the action does, as one line: the summary of {@link #call}, {@code done} when it returns {@code null}.
     *
     * @return a function of the checked arguments to that line
     */
    public Function<Map<String, String>, String> run() {
        return arguments -> {
            ActionResult result = call.apply(arguments);
            return result == null ? ActionResult.of(null).summary() : result.summary();
        };
    }

    private static Function<Map<String, String>, ActionResult> summaryOnly(Function<Map<String, String>, String> run) {
        Objects.requireNonNull(run, "run");
        return arguments -> ActionResult.of(run.apply(arguments));
    }

    /**
     * One value an action takes, and what the console accepts for it: a value of {@code allowedValues}, which the
     * page offers as a list; a whole match of {@code pattern}, which it offers as a text field; or a JSON object
     * described by the JSON Schema {@code schema}, which it offers as a form when the schema allows one and as a JSON
     * editor otherwise (see {@link #json}). Exactly one of the three is set.
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
                allowedValues = checkedValues(name, allowedValues);
            } else if (pattern != null) {
                checkPattern(name, pattern);
            } else if (schema.length() > MAX_SCHEMA || !JsonCheck.isObject(schema, MAX_JSON_DEPTH)) {
                throw new IllegalArgumentException("argument '" + name + "' has a schema that is not a JSON object "
                        + "of at most " + MAX_SCHEMA + " characters");
            }
        }

        /** An immutable copy of {@code allowedValues}, refused when empty or when one is blank or too long. */
        private static List<String> checkedValues(String name, List<String> allowedValues) {
            List<String> values = List.copyOf(allowedValues);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("argument '" + name + "' allows no value");
            }
            for (String value : values) {
                if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("argument '" + name + "' allows a blank value or one "
                            + "longer than " + MAX_VALUE_LENGTH + " characters");
                }
            }
            return values;
        }

        private static void checkPattern(String name, String pattern) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException invalid) {
                throw new IllegalArgumentException("argument '" + name + "' has an invalid pattern", invalid);
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
         * <p>The page reads these keywords of {@code schema}: {@code type}, {@code properties}, {@code required},
         * {@code additionalProperties}, {@code items}, {@code enum}, {@code maxLength}, {@code format} ({@code date},
         * {@code time}, {@code date-time}, {@code uuid}; {@code textarea} for a field of several lines),
         * {@code contentMediaType} ({@code text/csv}; {@code text/x-query} for a query, with {@code x-language}, the
         * id of one of the panel's {@link DevConsolePanel#languages() languages}), {@code x-parameters-of} (the
         * query property whose parameters a property holds), {@code readOnly}, {@code description}, {@code default},
         * and a local {@code $ref} ({@code #/$defs/...}, {@code #/definitions/...}). It generates a form when the root
         * is an object whose properties are scalars, enums of strings, or objects of those one level down, and offers
         * its JSON editor otherwise, which checks and completes the value against the schema; neither refuses to send
         * it: the action's target stays the judge.
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

    /**
     * What an action returns: the line the page shows and the console logs, and optionally what to show under it.
     *
     * @param summary     one line, at most {@value #MAX_SUMMARY} characters, a longer one cut with {@code ...};
     *                    {@code null} reads as {@code done}
     * @param contentType {@value #TEXT}, {@value #JSON}, which the page pretty-prints, {@value #CSV}, which it
     *                    shows as text with a Download button, {@value #ROWS}, which it draws as a table, or
     *                    {@value #GRAPH}, which it draws as boxes and lines;
     *                    {@code null} when there is no body, {@value #TEXT} when there is one and none was given
     * @param body        what to show, or {@code null}; at most {@value #MAX_CONTENT} characters, a longer one
     *                    truncated and ending with the marker {@code … truncated at 256 KiB}
     * @param error       {@code true} when the call went through but its outcome is an error of its target, such as
     *                    a tool returning {@code isError}; an exception thrown by the action is not a result
     * @param details     JSON the page shows folded under "Exchange", such as a request and its response, or
     *                    {@code null}; truncated as {@code body}
     */
    public record ActionResult(String summary, String contentType, String body, boolean error, String details) {

        /** The longest summary, in characters. */
        public static final int MAX_SUMMARY = 200;
        /** The longest body or details, in characters. */
        public static final int MAX_CONTENT = 256 * 1024;
        /** A body shown as it is. */
        public static final String TEXT = "text/plain";
        /** A body the page pretty-prints. */
        public static final String JSON = "application/json";
        /**
         * A CSV file: the page shows it as text with a Download button that saves it in the browser as
         * {@code <action id>-<yyyyMMdd-HHmmss>.csv}, without a request. The body cap is the same.
         */
        public static final String CSV = "text/csv";
        /**
         * A table of rows, such as the answer of a SQL query: the page draws it as a table, a column's type dimmed
         * under its name, a {@code null} as a dimmed {@code NULL}, with a switch to its JSON viewer and Copy as CSV.
         * The body is {@code {"columns": [{"name": …, "type": …}…], "rows": [[…]…], "more": bool}}, each row one value
         * per column, a value {@code null}, a boolean, a number or a string; {@link #rows} writes it. A body of
         * another shape is shown as any other JSON. A column named {@link PanelSample#REPLAY_COLUMN replay} holds
         * replays, as a sample table's does: each is a button, named after its action, that fills that action's form.
         * A body may also carry, between its columns and its rows, {@code "menu": [{"label", "action",
         * "arguments"}…]}: the entries of a right-click menu on the selected rows (see {@link MenuItem}).
         */
        public static final String ROWS = "application/x-rows+json";
        /** What ends a body or details that was truncated. */
        public static final String TRUNCATED = "\n… truncated at 256 KiB";

        /**
         * A graph of nodes and edges, such as the entities of a persistence unit and their relations: the page lays it
         * out as boxes and lines, lists a node's facts beside it on a click, and offers its JSON viewer. The body is
         * {@code {"nodes": [{"id", "label", "kind", "facts": [[name, value]…]}…], "edges": [{"from", "to", "label",
         * "arrow", "dashed"}…]}}; {@link #graph} writes it. A body of another shape is shown as any other JSON.
         */
        public static final String GRAPH = "application/x-graph+json";

        /** The most entries of the menu a {@link #ROWS} result offers on its selected rows. */
        public static final int MAX_MENU = 16;

        /** The most nodes a {@link #graph} holds. */
        public static final int MAX_NODES = 500;

        /** The most edges a {@link #graph} holds. */
        public static final int MAX_EDGES = 2000;

        public ActionResult {
            summary = summary == null ? "done" : cut(summary);
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)
                    && !CSV.equals(contentType) && !ROWS.equals(contentType) && !GRAPH.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + ", " + JSON + ", " + CSV
                        + ", " + ROWS + " or " + GRAPH);
            }
            if (body == null) {
                contentType = null;
            } else if (contentType == null) {
                contentType = TEXT;
            }
            body = truncated(body);
            details = truncated(details);
        }

        /**
         * A result that is one line, and nothing else.
         *
         * @param summary the line, or {@code null} for {@code done}
         * @return the result
         */
        public static ActionResult of(String summary) {
            return new ActionResult(summary, null, null, false, null);
        }

        /**
         * A {@link #ROWS} result: a line, and a body of these columns and rows that stays within
         * {@value #MAX_CONTENT} characters, the rows that would pass it left out and {@code more} set.
         *
         * @param summary the line, such as {@code 3 rows in 12 ms}, or {@code null} for {@code done}
         * @param columns the columns, in order
         * @param rows    the rows, in order, each one value per column: {@code null}, a {@link Boolean}, a
         *                {@link Number} or a {@link String}; anything else, a number JSON cannot write such as
         *                {@code NaN} included, is written as its {@link String#valueOf}
         * @param more    whether there are rows past those given
         * @return the result
         * @throws IllegalArgumentException when a row has another number of values than there are columns, or when
         *                                  the columns alone pass {@value #MAX_CONTENT} characters
         */
        public static ActionResult rows(String summary, List<Column> columns, List<? extends List<?>> rows,
                                        boolean more) {
            return rows(summary, columns, rows, more, List.of());
        }

        /**
         * A {@link #ROWS} result whose table offers {@code menu} on its selected rows: the page adds a box to tick on
         * each row, and a right-click menu whose entries fill another action's form, as a replay does. Nothing is
         * sent: the user still submits.
         *
         * @param summary the line, or {@code null} for {@code done}
         * @param columns the columns, in order
         * @param rows    the rows, in order, as {@link #rows(String, List, List, boolean)} takes them
         * @param more    whether there are rows past those given
         * @param menu    the entries, at most {@value #MAX_MENU}, in order; empty for none, which writes the body of
         *                {@link #rows(String, List, List, boolean)}
         * @return the result
         * @throws IllegalArgumentException as {@link #rows(String, List, List, boolean)} does, or when there are more
         *                                  than {@value #MAX_MENU} entries
         */
        public static ActionResult rows(String summary, List<Column> columns, List<? extends List<?>> rows,
                                        boolean more, List<MenuItem> menu) {
            return new ActionResult(summary, ROWS, RowsBody.write(columns, rows, more, menu), false, null);
        }

        /**
         * How many of the leading {@code rows} the body of {@link #rows} holds: all of them, unless it would pass
         * {@value #MAX_CONTENT} characters. A summary that counts rows says this number, not the number read.
         *
         * @param columns the columns, in order
         * @param rows    the rows, in order, as {@link #rows} takes them
         * @return how many rows the body holds
         * @throws IllegalArgumentException as {@link #rows} does
         */
        public static int rowsHeld(List<Column> columns, List<? extends List<?>> rows) {
            return rowsHeld(columns, rows, List.of());
        }

        /**
         * How many of the leading {@code rows} the body of {@link #rows(String, List, List, boolean, List)} holds
         * with {@code menu}, which takes room from the rows.
         *
         * @param columns the columns, in order
         * @param rows    the rows, in order
         * @param menu    the menu entries
         * @return how many rows the body holds
         * @throws IllegalArgumentException as {@link #rows(String, List, List, boolean, List)} does
         */
        public static int rowsHeld(List<Column> columns, List<? extends List<?>> rows, List<MenuItem> menu) {
            return RowsBody.held(columns, rows, menu);
        }

        /**
         * An entry of the menu a {@link #ROWS} result offers on its selected rows, such as <i>Delete these rows</i>.
         * Clicking it fills the form of {@code action} with {@code arguments}, where each {@code ${name}} inside one
         * of their strings, {@code name} a column of the result, becomes the selected rows' values of that column,
         * written as query literals ({@code 'it''s'}, {@code 42}, {@code TRUE}, {@code NULL}) and joined with
         * {@code ", "}: {@code "DELETE FROM Note e WHERE e.id IN (${id})"} becomes
         * {@code "DELETE FROM Note e WHERE e.id IN (1, 3)"}. The page sends nothing: the user reviews and submits.
         *
         * @param label     what the menu shows, not blank, at most {@value #MAX_LABEL} characters
         * @param action    the id of an action of the same panel, by the key rule; the page leaves out an entry whose
         *                  action the panel does not have
         * @param arguments a JSON object of that action's arguments by name, as a replay cell carries them, at most
         *                  {@value PanelSample#MAX_REPLAY_CELL} characters
         */
        public record MenuItem(String label, String action, String arguments) {

            /** The longest label of an entry. */
            public static final int MAX_LABEL = 80;

            public MenuItem {
                Objects.requireNonNull(label, "label");
                if (label.isBlank() || label.length() > MAX_LABEL) {
                    throw new IllegalArgumentException("a menu entry's label is not blank and at most " + MAX_LABEL
                            + " characters");
                }
                PanelSample.requireKey(action);
                Objects.requireNonNull(arguments, "arguments");
                if (arguments.length() > PanelSample.MAX_REPLAY_CELL
                        || !JsonCheck.isObject(arguments, Argument.MAX_JSON_DEPTH)) {
                    throw new IllegalArgumentException("the arguments of menu entry '" + label + "' are not one JSON "
                            + "object of at most " + PanelSample.MAX_REPLAY_CELL + " characters");
                }
            }
        }

        /**
         * A {@link #GRAPH} result: a line, and a body of these nodes and edges. A graph is never cut, half of one
         * meaning nothing: one past {@value #MAX_CONTENT} characters is refused.
         *
         * @param summary the line, such as {@code 4 entities, 3 relations}, or {@code null} for {@code done}
         * @param nodes   the nodes, at most {@value #MAX_NODES}, each id once
         * @param edges   the edges, at most {@value #MAX_EDGES}, each between two of the nodes; an edge from a node
         *                to itself is drawn as a loop
         * @return the result
         * @throws IllegalArgumentException when a node id is given twice, an edge names a node not given, there are
         *                                  too many nodes or edges, or the body passes {@value #MAX_CONTENT}
         *                                  characters
         */
        public static ActionResult graph(String summary, List<Node> nodes, List<Edge> edges) {
            return new ActionResult(summary, GRAPH, GraphBody.write(nodes, edges), false, null);
        }

        /**
         * A node of a {@link #GRAPH} result.
         *
         * @param id    what edges name it by, not blank, at most {@value Column#MAX_NAME} characters
         * @param label what its box shows, not blank, at most {@value Column#MAX_NAME} characters
         * @param kind  a word shown dimmed under the label, such as {@code entity}, at most {@value #MAX_KIND}
         *              characters; empty for none
         * @param facts what the page lists beside the graph when the node is clicked, in order; an immutable copy
         */
        public record Node(String id, String label, String kind, List<Fact> facts) {

            /** The longest kind of a node. */
            public static final int MAX_KIND = 40;

            public Node {
                requireName(id, "a node's id");
                requireName(label, "a node's label");
                Objects.requireNonNull(kind, "kind");
                if (kind.length() > MAX_KIND) {
                    throw new IllegalArgumentException("a node's kind is at most " + MAX_KIND + " characters");
                }
                facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            }
        }

        /**
         * A fact of a {@link Node}, such as its table, or one attribute and its column.
         *
         * @param name  what the fact is about, such as {@code table}, not blank, at most {@value Column#MAX_NAME}
         *              characters
         * @param value the fact, such as {@code NOTE}, at most {@value PanelAction#MAX_DESCRIPTION} characters
         */
        public record Fact(String name, String value) {

            public Fact {
                requireName(name, "a fact's name");
                Objects.requireNonNull(value, "value");
                if (value.length() > MAX_DESCRIPTION) {
                    throw new IllegalArgumentException("a fact's value is at most " + MAX_DESCRIPTION
                            + " characters");
                }
            }
        }

        /**
         * An edge of a {@link #GRAPH} result.
         *
         * @param from   the id of the node it starts from
         * @param to     the id of the node it goes to
         * @param label  what the page writes at its middle, such as {@code author *..1}, at most
         *               {@value Column#MAX_NAME} characters; empty for none
         * @param arrow  whether it ends with an arrow at {@code to}; an arrow that is not dashed also tells the
         *               layout to place {@code to} above {@code from}, as a parent above its subclass
         * @param dashed whether its line is dashed, such as for an embedded value
         */
        public record Edge(String from, String to, String label, boolean arrow, boolean dashed) {

            public Edge {
                Objects.requireNonNull(from, "from");
                Objects.requireNonNull(to, "to");
                Objects.requireNonNull(label, "label");
                if (label.length() > Column.MAX_NAME) {
                    throw new IllegalArgumentException("an edge's label is at most " + Column.MAX_NAME
                            + " characters");
                }
            }
        }

        /** Refuses a {@code null}, blank or too long name of a graph. */
        private static void requireName(String name, String what) {
            Objects.requireNonNull(name, what);
            if (name.isBlank() || name.length() > Column.MAX_NAME) {
                throw new IllegalArgumentException(what + " is not blank and at most " + Column.MAX_NAME
                        + " characters");
            }
        }

        /**
         * A column of a {@link #ROWS} result.
         *
         * @param name its name, such as {@code title}, at most {@value #MAX_NAME} characters
         * @param type its type, shown dimmed under its name, such as {@code VARCHAR(200)}, at most
         *             {@value #MAX_NAME} characters; empty for none
         */
        public record Column(String name, String type) {

            /** The longest name or type of a column. */
            public static final int MAX_NAME = 200;

            public Column {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
                if (name.length() > MAX_NAME || type.length() > MAX_NAME) {
                    throw new IllegalArgumentException("a column's name and type are at most " + MAX_NAME
                            + " characters");
                }
            }
        }

        private static String cut(String summary) {
            if (summary.length() <= MAX_SUMMARY) {
                return summary;
            }
            int end = MAX_SUMMARY - 3;
            if (Character.isHighSurrogate(summary.charAt(end - 1))) {
                end--;
            }
            return summary.substring(0, end) + "...";
        }

        private static String truncated(String text) {
            if (text == null || text.length() <= MAX_CONTENT) {
                return text;
            }
            int end = MAX_CONTENT - TRUNCATED.length();
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            return text.substring(0, end) + TRUNCATED;
        }
    }
}
