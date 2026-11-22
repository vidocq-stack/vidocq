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
package io.vidocq.runtime.config.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KeyCatalogTest {

    /** A file of format 1 named {@code origin}, with these lines. */
    private static KeyDescriptions file(String origin, String... lines) {
        return KeyDescriptions.read(origin, "format=1\n" + String.join("\n", lines));
    }

    /** A jar that has the written file only. */
    private static KeyCatalog.Jar jar(String origin, String... lines) {
        return new KeyCatalog.Jar(file(origin, lines), KeyDescriptions.none());
    }

    private static KeyCatalog catalog(KeyCatalog.Jar... jars) {
        return KeyCatalog.of(Arrays.asList(jars));
    }

    private static Problem onlyProblem(KeyCatalog catalog) {
        assertEquals(1, catalog.problems().size(), catalog.problems().toString());
        return catalog.problems().getFirst();
    }

    private static KeyDescription key(KeyCatalog catalog, String pattern) {
        return catalog.keys().stream().filter(k -> k.key().text().equals(pattern)).findFirst().orElseThrow();
    }

    @Test
    void the_file_names_are_those_of_the_format() {
        assertEquals("META-INF/vidocq/config-user-keys.properties", KeyCatalog.WRITTEN);
        assertEquals("META-INF/vidocq/config-keys.properties", KeyCatalog.GENERATED);
    }

    @Test
    void in_a_jar_the_written_fact_wins_and_the_generated_file_gives_the_rest() {
        KeyCatalog catalog = catalog(new KeyCatalog.Jar(
                file("written", "shop.mail.port|doc=The port."),
                file("generated", "shop.mail.port|type=int", "shop.mail.port|default=25", "shop.mail.port|doc=port")));

        assertEquals(List.of(), catalog.problems());
        KeyDescription port = key(catalog, "shop.mail.port");
        assertEquals(Optional.of("The port."), port.doc());
        assertEquals("int", port.type());
        assertEquals(Optional.of("25"), port.defaultValue());
    }

    @Test
    void in_a_jar_the_values_of_the_two_files_add_up() {
        KeyCatalog catalog = catalog(new KeyCatalog.Jar(file("written", "a.b|values=x"),
                file("generated", "a.b|values=y,x")));

        assertEquals(List.of(), catalog.problems());
        assertEquals(List.of("x", "y"), key(catalog, "a.b").values());
    }

    @Test
    void across_jars_the_values_of_a_key_add_up() {
        KeyCatalog catalog = catalog(
                jar("chappe", "vidocq.http.mount.<mount>.type|values=static,websocket"),
                jar("cassini", "vidocq.http.mount.<mount>.type|values=restful"));

        assertEquals(List.of(), catalog.problems());
        assertEquals(List.of("static", "websocket", "restful"),
                key(catalog, "vidocq.http.mount.<mount>.type").values());
        assertEquals(1, catalog.keys().size());
    }

    @Test
    void across_jars_the_names_of_a_family_add_up() {
        KeyCatalog catalog = catalog(
                jar("chappe", "<listener>|names=default", "<listener>|doc=A listener."),
                jar("devconsole", "<listener>|names=dev,default"));

        assertEquals(List.of(), catalog.problems());
        assertEquals(Optional.of(new KeyFamily("listener", List.of("default", "dev"), Optional.of("A listener."))),
                catalog.family("listener"));
        assertEquals(Optional.empty(), catalog.family("mount"));
    }

    @Test
    void a_family_a_pattern_names_exists_with_no_line_of_its_own() {
        KeyCatalog catalog = catalog(jar("pool", "vidocq.pool.[<datasource>].url|type=string"));

        assertEquals(Optional.of(new KeyFamily("datasource", List.of(), Optional.empty())),
                catalog.family("datasource"));
    }

    @Test
    void two_jars_that_disagree_on_a_type_are_a_problem_and_the_first_is_kept() {
        KeyCatalog catalog = catalog(jar("one", "a.b|type=int"), jar("two", "a.b|type=long"));

        Problem problem = onlyProblem(catalog);
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals("two", problem.origin());
        assertTrue(problem.message().contains("a.b") && problem.message().contains("one"), problem.message());
        assertEquals("int", key(catalog, "a.b").type());
    }

    @Test
    void two_jars_that_disagree_on_a_default_are_a_problem_and_the_first_is_kept() {
        KeyCatalog catalog = catalog(jar("one", "a.b|default=1"), jar("two", "a.b|default=2"));

        assertEquals(Problem.Kind.WRONG, onlyProblem(catalog).kind());
        assertEquals(Optional.of("1"), key(catalog, "a.b").defaultValue());
    }

    @Test
    void two_jars_that_agree_are_no_problem() {
        KeyCatalog catalog = catalog(jar("one", "a.b|type=int", "a.b|default=1", "a.b|doc=One."),
                jar("two", "a.b|type=int", "a.b|default=1", "a.b|doc=Two."));

        assertEquals(List.of(), catalog.problems());
        assertEquals(Optional.of("One."), key(catalog, "a.b").doc());
    }

    @Test
    void a_written_default_that_contradicts_the_generated_one_is_a_problem_and_is_kept() {
        KeyCatalog catalog = catalog(new KeyCatalog.Jar(file("written", "a.b|default=1"),
                file("generated", "a.b|default=2")));

        Problem problem = onlyProblem(catalog);
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals("written", problem.origin());
        assertEquals(Optional.of("1"), key(catalog, "a.b").defaultValue());
    }

    @Test
    void a_written_type_may_correct_the_generated_one() {
        KeyCatalog catalog = catalog(new KeyCatalog.Jar(file("written", "a.b|type=duration"),
                file("generated", "a.b|type=string")));

        assertEquals(List.of(), catalog.problems());
        assertEquals("duration", key(catalog, "a.b").type());
    }

    @Test
    void a_default_outside_the_allowed_values_is_a_problem() {
        assertEquals(Problem.Kind.WRONG, onlyProblem(catalog(jar("one", "a.b|values=x,y", "a.b|default=Z"))).kind());
    }

    @Test
    void a_default_is_compared_with_the_allowed_values_without_regard_to_case() {
        assertEquals(List.of(), catalog(jar("one", "a.b|values=x,y", "a.b|default=X")).problems());
    }

    @Test
    void a_default_is_compared_with_the_values_every_jar_gives() {
        assertEquals(List.of(), catalog(jar("one", "a.b|values=x", "a.b|default=y"), jar("two", "a.b|values=y"))
                .problems());
    }

    @Test
    void a_condition_on_a_sibling_nobody_describes_is_a_problem() {
        Problem problem = onlyProblem(catalog(jar("one", "a.<m>.classpath|when=type=static")));

        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertTrue(problem.message().contains("a.<m>.type"), problem.message());
    }

    @Test
    void a_condition_on_a_sibling_another_jar_describes_is_fine() {
        assertEquals(List.of(), catalog(jar("one", "a.<m>.classpath|when=type=static"),
                jar("two", "a.<m>.type|values=static")).problems());
    }

    @Test
    void a_condition_on_a_value_the_sibling_does_not_allow_is_a_problem() {
        Problem problem = onlyProblem(catalog(jar("one", "a.<m>.type|values=static",
                "a.<m>.classpath|when=type=statik")));

        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertTrue(problem.message().contains("statik"), problem.message());
    }

    @Test
    void a_condition_on_a_sibling_that_allows_any_value_is_fine() {
        assertEquals(List.of(), catalog(jar("one", "a.<m>.type|doc=Any handler.",
                "a.<m>.classpath|when=type=Static")).problems());
        assertEquals(List.of(), catalog(jar("one", "a.<m>.type|values=static",
                "a.<m>.classpath|when=type=STATIC")).problems());
    }

    @Test
    void a_reference_to_a_family_nobody_knows_is_a_problem() {
        Problem problem = onlyProblem(catalog(jar("one", "a.b|ref=datasource")));

        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertTrue(problem.message().contains("datasource"), problem.message());
    }

    @Test
    void a_reference_to_a_family_another_jar_names_is_fine() {
        assertEquals(List.of(), catalog(jar("one", "a.b|ref=datasource"), jar("two", "<datasource>|doc=x"))
                .problems());
        assertEquals(List.of(), catalog(jar("one", "a.b|ref=datasource"),
                jar("two", "p.<datasource>.url|type=string")).problems());
    }

    @Test
    void a_literal_wins_over_a_name_and_a_described_key_over_a_tail() {
        KeyCatalog catalog = catalog(jar("one",
                "vidocq.chappe.listener.<listener>.*|doc=3",
                "vidocq.chappe.listener.<listener>.port|doc=2",
                "vidocq.chappe.listener.default.port|doc=1"));

        assertEquals(Optional.of("1"), catalog.describe("vidocq.chappe.listener.default.port").orElseThrow().doc());
        assertEquals(Optional.of("2"), catalog.describe("vidocq.chappe.listener.admin.port").orElseThrow().doc());
        assertEquals(Optional.of("3"), catalog.describe("vidocq.chappe.listener.admin.host").orElseThrow().doc());
        assertEquals(Optional.empty(), catalog.describe("vidocq.other"));
        assertEquals(Optional.empty(), catalog.describe(null));
    }

    @Test
    void two_patterns_that_match_as_well_answer_the_first_in_the_order_of_their_text() {
        // '<' sorts before a letter: a.<x>.c comes before a.b.<y>, whatever the order of the file.
        KeyCatalog catalog = catalog(jar("one", "a.b.<y>|doc=2", "a.<x>.c|doc=1"));

        assertEquals(Optional.of("1"), catalog.describe("a.b.c").orElseThrow().doc());
    }

    @Test
    void the_keys_come_in_the_order_of_their_text() {
        KeyCatalog catalog = catalog(jar("one", "b.b|type=int", "a.a|type=int"), jar("two", "a.0|type=int"));

        assertEquals(List.of("a.0", "a.a", "b.b"), catalog.keys().stream().map(k -> k.key().text()).toList());
    }

    @Test
    void the_problems_of_each_file_come_first() {
        KeyCatalog catalog = catalog(jar("one", "nonsense", "a.b|ref=nobody"));

        assertEquals(2, catalog.problems().size());
        assertEquals(2, catalog.problems().get(0).line());
        assertEquals(3, catalog.problems().get(1).line());
    }

    @Test
    void the_problems_of_every_file_come_before_those_between_files() {
        KeyCatalog catalog = catalog(jar("one", "a.b|ref=nobody", "nonsense"), jar("two", "more nonsense"));

        assertEquals(List.of("one:3", "two:2", "one:2"),
                catalog.problems().stream().map(p -> p.origin() + ":" + p.line()).toList());
    }

    @Test
    void a_key_wins_over_its_optional_twin() {
        KeyCatalog catalog = catalog(jar("one", "vidocq.pool.[<datasource>].url|doc=2", "vidocq.pool.url|doc=1"));

        assertEquals(Optional.of("1"), catalog.describe("vidocq.pool.url").orElseThrow().doc());
        assertEquals(Optional.of("2"), catalog.describe("vidocq.pool.audit.url").orElseThrow().doc());
    }

    @Test
    void a_tail_after_a_name_wins_over_a_tail_alone() {
        KeyCatalog catalog = catalog(jar("one", "a.*|doc=1", "a.<x>.*|doc=2"));

        assertEquals(Optional.of("2"), catalog.describe("a.b.c").orElseThrow().doc());
        assertEquals(Optional.of("1"), catalog.describe("a.b").orElseThrow().doc());
    }

    @Test
    void the_default_of_a_list_is_compared_item_by_item() {
        assertEquals(List.of(), catalog(jar("one", "a.b|type=list:string", "a.b|values=x,y,z", "a.b|default=x, y"))
                .problems());

        Problem problem = onlyProblem(catalog(jar("one", "a.b|type=list:string", "a.b|values=x,y,z",
                "a.b|default=x,w")));
        assertTrue(problem.message().contains("'w'"), problem.message());
    }

    @Test
    void an_empty_default_is_no_value_to_compare() {
        assertEquals(List.of(), catalog(jar("one", "a.b|values=x,y", "a.b|default=")).problems());
    }

    @Test
    void a_default_that_is_not_of_its_type_is_a_problem_at_the_line_of_the_default() {
        Problem problem = onlyProblem(catalog(jar("a", "a.b|type=int", "a.b|default=x")));

        assertEquals(new Problem("a", 3, Problem.Kind.WRONG, "a.b|default is 'x', which is not an int"), problem);
    }

    @Test
    void a_default_that_is_not_a_boolean_is_a_problem() {
        Problem problem = onlyProblem(catalog(jar("a", "a.b|default=maybe", "a.b|type=boolean")));

        assertEquals(2, problem.line());
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertTrue(problem.message().startsWith("a.b|default is 'maybe', which is not a boolean"), problem.message());
    }

    @Test
    void the_default_of_a_list_is_checked_against_the_type_item_by_item() {
        Problem problem = onlyProblem(catalog(jar("a", "a.b|type=list:int", "a.b|default=1,x")));

        assertEquals(new Problem("a", 3, Problem.Kind.WRONG, "a.b|default is '1,x', and 'x' is not an int"), problem);
    }

    @Test
    void a_default_that_is_an_expression_or_empty_is_not_checked_against_the_type() {
        assertEquals(List.of(), catalog(jar("a", "a.b|type=int", "a.b|default=${PORT}")).problems());
        assertEquals(List.of(), catalog(jar("a", "a.b|type=int", "a.b|default=")).problems());
        assertEquals(List.of(), catalog(jar("a", "a.b|type=int", "a.b|default=8080")).problems());
    }

    @Test
    void a_default_that_is_an_expression_is_not_compared_with_the_allowed_values() {
        assertEquals(List.of(), catalog(jar("a", "a.b|values=x,y", "a.b|default=${OTHER}")).problems());
    }

    @Test
    void a_default_of_another_jar_is_checked_against_the_type_the_catalog_keeps() {
        Problem problem = onlyProblem(catalog(jar("a", "a.b|type=int"), jar("b", "a.b|default=x")));

        assertEquals(new Problem("b", 2, Problem.Kind.WRONG, "a.b|default is 'x', which is not an int"), problem);
    }

    @Test
    void a_problem_between_files_names_the_file_and_the_line_of_the_fact() {
        assertEquals(new Problem("one", 3, Problem.Kind.WRONG, ""), withoutMessage(
                onlyProblem(catalog(jar("one", "a.b|values=x,y", "a.b|default=Z")))));
        assertEquals(new Problem("one", 2, Problem.Kind.WRONG, ""), withoutMessage(
                onlyProblem(catalog(jar("one", "a.b|ref=datasource")))));
        assertEquals(new Problem("one", 3, Problem.Kind.WRONG, ""), withoutMessage(
                onlyProblem(catalog(jar("one", "# c", "a.<m>.classpath|when=type=static")))));
        assertEquals(new Problem("two", 4, Problem.Kind.WRONG, ""), withoutMessage(
                onlyProblem(catalog(jar("one", "a.b|type=int"), jar("two", "# c", "# c", "a.b|type=long")))));
    }

    private static Problem withoutMessage(Problem problem) {
        return new Problem(problem.origin(), problem.line(), problem.kind(), "");
    }

    @Test
    void a_merged_key_says_where_each_fact_it_kept_comes_from() {
        KeyCatalog catalog = catalog(
                new KeyCatalog.Jar(file("written", "a.b|doc=Written."), file("generated", "a.b|type=int")),
                jar("other", "# c", "a.b|values=x", "a.b|doc=Other."));

        KeyDescription key = key(catalog, "a.b");
        assertEquals(Optional.of(new Fact("Written.", "written", 2)), key.fact(Attribute.DOC));
        assertEquals(Optional.of(new Fact("int", "generated", 2)), key.fact(Attribute.TYPE));
        assertEquals(Optional.of(new Fact("x", "other", 3)), key.fact(Attribute.VALUES));
    }

    @Test
    void a_jar_has_its_two_files_or_none() {
        KeyDescriptions none = KeyDescriptions.none();

        assertThrows(NullPointerException.class, () -> new KeyCatalog.Jar(null, none));
    }

    @Test
    void no_jar_is_an_empty_catalog() {
        KeyCatalog catalog = KeyCatalog.of(List.of());

        assertEquals(List.of(), catalog.keys());
        assertEquals(List.of(), catalog.problems());
        assertEquals(Optional.empty(), catalog.describe("a.b"));
    }
}
