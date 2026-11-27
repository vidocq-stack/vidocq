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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI commands and the Maven plugin goals they stand for do not drift apart, in name or in options
 * (Vidocq/vidocq#779): each row pairs a command with its goal, and each option of the command with the property of the
 * goal it sets. A command and a goal that are on one side only, or an option that has no counterpart, are listed with
 * the reason. The plugin's descriptor is read from its sources (it is written by hand), and the table of the
 * documentation, {@code cli-plugin-correspondence.adoc}, must list every row.
 */
class CliGoalAlignmentTest {

    private static final Path PLUGIN_XML = Path.of("..", "vidocq-runtime-maven-plugin", "src", "main", "resources",
            "META-INF", "maven", "plugin.xml");
    private static final Path DOC_TABLE = Path.of("..", "docs", "en", "modules", "ROOT", "partials",
            "cli-plugin-correspondence.adoc");

    /**
     * A command (its words), the goal, and each option of the command (its long form, with the value a test types
     * when it takes one) with the user property of the goal it sets. {@code delegated}: the command runs the goal
     * through Maven; otherwise both run the same code of {@code vidocq-runtime-tooling} in their own process.
     */
    private record Row(String command, String goal, boolean delegated, Map<String, String> options) {
        List<String> words() {
            return List.of(command.split(" "));
        }
    }

    /** {@code option} typed with {@code value}, as a key of {@link Row#options()}. */
    private static String valued(String option, String value) {
        return option + "=" + value;
    }

    private static final List<Row> ROWS = List.of(
            new Row("run", "run", true, options(
                    valued("--port", "9090"), "vidocq.run.systemProperties",
                    valued("--config", "app.properties"), "vidocq.run.systemProperties",
                    "--debug", "vidocq.run.debug")),
            new Row("dev", "dev", true, options(
                    valued("--port", "9090"), "vidocq.dev.systemProperties",
                    valued("--profile", "prod"), "vidocq.profile",
                    "--debug", "vidocq.dev.debug")),
            new Row("test", "test", true, options("--once", "vidocq.test.once")),
            new Row("idea", "idea", true, options(
                    "--check", "vidocq.idea.check",
                    "--strict", "vidocq.idea.check",
                    valued("--kind", "application"), "vidocq.idea.kind")),
            new Row("vscode", "vscode", true, options("--check", "vidocq.vscode.check")),
            new Row("eclipse", "eclipse", true, options("--check", "vidocq.eclipse.check")),
            new Row("doctor", "doctor", false, options(
                    "--verbose", "vidocq.doctor.verbose",
                    "--fix", "vidocq.doctor.fix")),
            new Row("create", "create", false, options(
                    valued("--name", "demo"), "name",
                    valued("--group-id", "com.acme"), "groupId",
                    valued("--package", "com.acme.demo"), "package",
                    valued("--extension", "health-knock"), "extensions",
                    valued("--vidocq-version", "1.2.3"), "vidocqVersion",
                    valued("--parent-version", "1.2.3"), "parentVersion",
                    "--with-parent", "withParent",
                    "--main", "main",
                    "--open-module", "openModule")),
            new Row("extension add", "add-extension", false, options("health-knock", "extensions")),
            new Row("extension remove", "remove-extension", false, options("health-knock", "extensions")),
            new Row("extension list", "list-extensions", false, options("--all", "vidocq.extensions.available")));

    /**
     * Options of a paired command with no property of its goal, on purpose: what Maven itself takes, and what only
     * the CLI's way of answering has.
     */
    private static final Map<String, String> CLI_ONLY_OPTIONS = Map.of(
            "--maven-profile", "Maven's own -P, not a property of the goal",
            "--installed", "extension list: the default of both, the goal always lists the installed extensions",
            "--available", "extension list: the catalog alone, without running Maven; the goal resolves the project",
            "--refresh", "extension list: skips the CLI's cache of the resolved dependencies; the goal has none");

    /** Commands with no goal of their own. */
    private static final Set<String> CLI_ONLY_COMMANDS = Set.of(
            "version", "info", "help", "config", "completion", "update", "pom",
            "build", // the umbrella of package, vidocq:jlink, vidocq:jpackage and vidocq:docker
            "clean"); // mvn clean

    /** Goals with no command of their own: bound to the build, build-level reports, or run by vidocq build. */
    private static final Set<String> PLUGIN_ONLY_GOALS = Set.of(
            "generate", "checkpom", "check-module-info", "test-path", "build-info",
            "analyze-deps", "complete-module-info",
            "package", "jlink", "jpackage", "docker");

    /** Properties of the plugin's own parameters, which no user sets. */
    private static final Pattern BUILT_IN = Pattern.compile("(project|session|plugin|basedir|mojo|settings|reactor)\\b.*");

    private static Map<String, Set<String>> goals;

    @BeforeAll
    static void readThePluginDescriptor() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element plugin = factory.newDocumentBuilder().parse(PLUGIN_XML.toFile()).getDocumentElement();
        goals = new LinkedHashMap<>();
        NodeList mojos = plugin.getElementsByTagName("mojo");
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            Set<String> properties = new LinkedHashSet<>();
            NodeList configuration = mojo.getElementsByTagName("configuration");
            if (configuration.getLength() > 0) {
                Matcher property = Pattern.compile("\\$\\{([^}]+)}").matcher(configuration.item(0).getTextContent());
                while (property.find()) {
                    properties.add(property.group(1));
                }
            }
            goals.put(childText(mojo, "goal"), properties);
        }
    }

    @Test
    void eachGoalExistsAndReadsThePropertiesItsCommandSets() {
        List<String> missing = new ArrayList<>();
        for (Row row : ROWS) {
            Set<String> properties = goals.get(row.goal());
            if (properties == null) {
                missing.add("vidocq:" + row.goal() + " (for vidocq " + row.command() + ") is no goal of the plugin");
                continue;
            }
            row.options().forEach((option, property) -> {
                if (!properties.contains(property)) {
                    missing.add("vidocq:" + row.goal() + " reads no ${" + property + "} (vidocq " + row.command()
                            + " " + option + ")");
                }
            });
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void eachCommandParsesEachOfItsOptions() {
        for (Row row : ROWS) {
            for (String option : row.options().keySet()) {
                List<String> args = new ArrayList<>(row.words());
                args.addAll(typed(option));
                if (row.command().equals("create") && !option.startsWith("--name")) {
                    args.addAll(List.of("--name", "demo"));
                }
                CliParser.parse(args.toArray(String[]::new)); // throws on an option it does not know
            }
        }
    }

    /** A command that runs its goal through Maven hands it each option as the property of the row. */
    @Test
    void aDelegatedCommandSetsThePropertyOfEachOption() {
        for (Row row : ROWS.stream().filter(Row::delegated).toList()) {
            for (var option : row.options().entrySet()) {
                List<String> args = new ArrayList<>(row.words());
                args.addAll(typed(option.getKey()));
                List<String> maven = CommandRunner.mavenGoals(CliParser.parse(args.toArray(String[]::new)))
                        .orElseThrow(() -> new AssertionError("vidocq " + row.command() + " runs no Maven goal"));

                assertTrue(maven.contains("vidocq:" + row.goal()), row.command() + ": " + maven);
                assertTrue(maven.stream().anyMatch(a -> a.startsWith("-D" + option.getValue() + "=")),
                        "vidocq " + row.command() + " " + option.getKey() + ": " + maven);
            }
        }
    }

    /** An option added to a command, with no property of its goal and no reason, fails here. */
    @Test
    void everyOptionOfAPairedCommandHasItsPropertyOrItsReason() {
        List<String> unpaired = new ArrayList<>();
        for (Row row : ROWS) {
            Set<String> mapped = new TreeSet<>();
            row.options().keySet().forEach(option -> mapped.add(typed(option).getFirst()));
            for (String offered : CompletionSpec.candidates(row.words())) {
                if (offered.startsWith("--") && !mapped.contains(offered)
                        && !CLI_ONLY_OPTIONS.containsKey(offered)) {
                    unpaired.add("vidocq " + row.command() + " " + offered);
                }
            }
        }
        assertEquals(List.of(), unpaired);
    }

    /**
     * A goal run in its own process has no property a command could not set: a parameter added to the goal, with no
     * option of the command, fails here.
     */
    @Test
    void everyPropertyOfAnInProcessGoalHasItsOption() {
        List<String> unpaired = new ArrayList<>();
        for (Row row : ROWS.stream().filter(r -> !r.delegated()).toList()) {
            for (String property : goals.getOrDefault(row.goal(), Set.of())) {
                if (!BUILT_IN.matcher(property).matches() && !row.options().containsValue(property)) {
                    unpaired.add("vidocq:" + row.goal() + " ${" + property + "}");
                }
            }
        }
        assertEquals(List.of(), unpaired);
    }

    /** A command or a goal added on one side only fails here until it is paired, or listed with its reason. */
    @Test
    void everyCommandAndEveryGoalIsPairedOrOnOneSideOnPurpose() {
        Set<String> pairedCommands = new TreeSet<>();
        ROWS.forEach(row -> pairedCommands.add(row.words().getFirst()));
        Set<String> commands = new TreeSet<>(CommandCatalog.COMMANDS);
        commands.removeAll(pairedCommands);
        assertEquals(new TreeSet<>(CLI_ONLY_COMMANDS), commands, "commands without a goal");

        Set<String> unpairedGoals = new TreeSet<>(goals.keySet());
        ROWS.forEach(row -> unpairedGoals.remove(row.goal()));
        assertEquals(new TreeSet<>(PLUGIN_ONLY_GOALS), unpairedGoals, "goals without a command");
    }

    /** The table of the reference pages of the CLI and of the plugin gives each row, its options and properties. */
    @Test
    void theDocumentedTableListsEveryRow() throws Exception {
        String table = Files.readString(DOC_TABLE);
        List<String> missing = new ArrayList<>();
        for (Row row : ROWS) {
            String cells = row(table, row.command());
            if (cells == null) {
                missing.add("no row starting with | `vidocq " + row.command());
                continue;
            }
            if (!cells.contains("`vidocq:" + row.goal() + "`")) {
                missing.add(row.command() + ": `vidocq:" + row.goal() + "`");
            }
            row.options().forEach((option, property) -> {
                String name = typed(option).getFirst();
                if (name.startsWith("--") && !cells.contains("`" + name)) {
                    missing.add(row.command() + ": `" + name + "`");
                }
                if (!cells.contains("`-D" + property)) {
                    missing.add(row.command() + ": `-D" + property + "`");
                }
            });
        }
        assertEquals(List.of(), missing);
    }

    /**
     * The cells of the row of {@code command}: from its first cell, {@code | `vidocq <command>`} or
     * {@code | `vidocq <command> <arguments>`}, to the blank line that ends it.
     */
    private static String row(String table, String command) {
        Matcher first = Pattern.compile("\n\\| `vidocq " + Pattern.quote(command) + "[` ]").matcher(table);
        if (!first.find()) {
            return null;
        }
        int end = table.indexOf("\n\n", first.start() + 1);
        return table.substring(first.start(), end < 0 ? table.length() : end);
    }

    /** The words a user types for {@code option}: the option, then its value when it takes one. */
    private static List<String> typed(String option) {
        int equals = option.indexOf('=');
        return equals < 0 ? List.of(option) : List.of(option.substring(0, equals), option.substring(equals + 1));
    }

    private static Map<String, String> options(String... optionsAndProperties) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < optionsAndProperties.length; i += 2) {
            map.put(optionsAndProperties[i], optionsAndProperties[i + 1]);
        }
        return map;
    }

    private static String childText(Element element, String name) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && e.getTagName().equals(name)) {
                return e.getTextContent().strip();
            }
        }
        throw new AssertionError("no <" + name + "> in <" + element.getTagName() + ">");
    }
}
