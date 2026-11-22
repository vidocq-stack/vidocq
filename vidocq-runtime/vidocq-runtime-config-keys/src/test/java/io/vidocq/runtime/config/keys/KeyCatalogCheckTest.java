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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link KeyCatalog#check}: what a value written for a key has against its description. */
class KeyCatalogCheckTest {

    /** A catalog of one jar that has one written file of format 1, with these lines. */
    private static KeyCatalog catalog(String... lines) {
        return KeyCatalog.of(List.of(new KeyCatalog.Jar(
                KeyDescriptions.read("written", "format=1\n" + String.join("\n", lines)), KeyDescriptions.none())));
    }

    private static List<ValueProblem.Kind> kinds(List<ValueProblem> problems) {
        return problems.stream().map(ValueProblem::kind).toList();
    }

    private static ValueProblem only(List<ValueProblem> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    @Test
    void a_whole_number_is_an_int() {
        assertEquals(List.of(), catalog("a.b|type=int").check("a.b", "80"));
    }

    @Test
    void a_word_is_not_an_int() {
        ValueProblem problem = only(catalog("a.b|type=int").check("a.b", "eighty"));

        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertEquals("is not an int", problem.message());
        assertEquals("a.b", problem.key());
        assertEquals("eighty", problem.value());
        assertEquals("a.b", problem.description().key().text());
    }

    @Test
    void a_number_too_large_for_an_int_is_not_one_and_is_a_long() {
        assertEquals(List.of(ValueProblem.Kind.NOT_OF_TYPE),
                kinds(catalog("a.b|type=int").check("a.b", "99999999999")));
        assertEquals(List.of(), catalog("a.b|type=long").check("a.b", "99999999999"));
    }

    @Test
    void a_word_is_not_a_long() {
        assertEquals("is not a long", only(catalog("a.b|type=long").check("a.b", "many")).message());
    }

    @Test
    void a_decimal_number_is_a_double_and_a_word_is_not() {
        KeyCatalog catalog = catalog("a.b|type=double");

        assertEquals(List.of(), catalog.check("a.b", "1.5"));
        ValueProblem problem = only(catalog.check("a.b", "one"));
        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertEquals("is not a double", problem.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "YES", "on", "0", "n", "False", "y", "1", "no", "OFF"})
    void a_boolean_is_written_as_the_runtime_reads_it(String value) {
        assertEquals(List.of(), catalog("a.b|type=boolean").check("a.b", value));
    }

    @Test
    void another_word_is_not_a_boolean_and_the_message_lists_the_spellings() {
        ValueProblem problem = only(catalog("a.b|type=boolean").check("a.b", "maybe"));

        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertEquals("is not a boolean (true, yes, y, on, 1, false, no, n, off, 0)", problem.message());
    }

    @Test
    void a_duration_is_written_as_java_reads_it() {
        KeyCatalog catalog = catalog("a.b|type=duration");

        assertEquals(List.of(), catalog.check("a.b", "PT30S"));
        ValueProblem problem = only(catalog.check("a.b", "30s"));
        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertTrue(problem.message().contains("PT30S"), problem.message());
    }

    @Test
    void a_value_of_a_java_type_is_not_checked() {
        assertEquals(List.of(), catalog("a.b|type=java.net.URI").check("a.b", "::"));
    }

    @Test
    void a_string_is_not_checked() {
        assertEquals(List.of(), catalog("a.b|doc=Free.").check("a.b", "1,2 ${"));
    }

    @Test
    void a_value_is_compared_with_the_allowed_ones_without_regard_to_case() {
        KeyCatalog catalog = catalog("a.b|values=x,y");

        assertEquals(List.of(), catalog.check("a.b", "Y"));
        ValueProblem problem = only(catalog.check("a.b", "z"));
        assertEquals(ValueProblem.Kind.NOT_ALLOWED, problem.kind());
        assertEquals("is not one of x, y", problem.message());
    }

    @Test
    void a_list_is_checked_item_by_item() {
        KeyCatalog catalog = catalog("a.b|type=list:int");

        assertEquals(List.of(), catalog.check("a.b", "1, 2,3"));
        ValueProblem problem = only(catalog.check("a.b", "1,two"));
        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertEquals("'two' is not an int", problem.message());
        assertEquals("1,two", problem.value());
    }

    @Test
    void every_item_of_a_list_that_is_wrong_is_a_problem() {
        List<ValueProblem> problems = catalog("a.b|type=list:int").check("a.b", "one,2,three");

        assertEquals(List.of("'one' is not an int", "'three' is not an int"),
                problems.stream().map(ValueProblem::message).toList());
    }

    @Test
    void the_items_of_a_list_are_compared_with_the_allowed_values() {
        ValueProblem problem = only(catalog("a.b|type=list:string", "a.b|values=x,y").check("a.b", "x,z"));

        assertEquals(ValueProblem.Kind.NOT_ALLOWED, problem.kind());
        assertEquals("'z' is not one of x, y", problem.message());
    }

    @Test
    void an_escaped_comma_does_not_split_a_list() {
        assertEquals(List.of(), catalog("a.b|type=list:string").check("a.b", "x\\,y"));
        assertEquals("'1,2' is not an int", only(catalog("a.b|type=list:int").check("a.b", "1\\,2")).message());
    }

    @Test
    void an_empty_item_of_a_list_is_not_checked() {
        assertEquals(List.of(), catalog("a.b|type=list:int").check("a.b", "1,,2,"));
    }

    @Test
    void a_long_item_of_a_list_is_not_quoted_whole() {
        String item = "x".repeat(40) + "y".repeat(20);
        KeyCatalog catalog = catalog("a.b|type=list:int", "a.b|values=1,2");

        String start = "'" + "x".repeat(40) + "…' ";
        assertEquals(List.of(start + "is not an int", start + "is not one of 1, 2"),
                catalog.check("a.b", "1," + item).stream().map(ValueProblem::message).toList());
        assertEquals("'" + "x".repeat(40) + "' is not an int",
                only(catalog("a.b|type=list:int").check("a.b", "x".repeat(40))).message());
    }

    @Test
    void the_type_is_reported_before_the_allowed_values() {
        assertEquals(List.of(ValueProblem.Kind.NOT_OF_TYPE, ValueProblem.Kind.NOT_ALLOWED),
                kinds(catalog("a.b|type=int", "a.b|values=1,2").check("a.b", "three")));
    }

    @Test
    void a_deprecated_key_is_reported_whatever_its_value() {
        KeyCatalog catalog = catalog("a.b|deprecated=true", "a.b|type=int");

        ValueProblem problem = only(catalog.check("a.b", "1"));
        assertEquals(ValueProblem.Kind.DEPRECATED, problem.kind());
        assertEquals("is deprecated", problem.message());
        assertEquals(List.of(ValueProblem.Kind.DEPRECATED), kinds(catalog.check("a.b", "")));
        assertEquals(List.of(ValueProblem.Kind.DEPRECATED), kinds(catalog.check("a.b", null)));
        assertEquals(List.of(ValueProblem.Kind.NOT_OF_TYPE, ValueProblem.Kind.DEPRECATED),
                kinds(catalog.check("a.b", "x")));
    }

    @Test
    void an_empty_value_is_no_value() {
        KeyCatalog catalog = catalog("a.b|type=int", "a.b|values=1,2");

        assertEquals(List.of(), catalog.check("a.b", ""));
        assertEquals(List.of(), catalog.check("a.b", "   "));
        assertEquals(List.of(), catalog.check("a.b", null));
    }

    @Test
    void a_value_that_holds_an_expression_is_not_checked() {
        KeyCatalog catalog = catalog("a.b|type=int", "a.b|values=1,2");

        assertEquals(List.of(), catalog.check("a.b", "${PORT:8080}"));
        assertEquals(List.of(), catalog.check("a.b", "80${SUFFIX}"));
    }

    @Test
    void an_escaped_dollar_is_no_expression() {
        assertEquals(List.of(ValueProblem.Kind.NOT_OF_TYPE), kinds(catalog("a.b|type=int").check("a.b", "\\${x}")));
        assertEquals(List.of(), catalog("a.b|type=int").check("a.b", "\\$${x}"),
                "the dollar is escaped, the expression after it is one");
    }

    @Test
    void white_space_around_a_value_is_not_the_value() {
        assertEquals(List.of(), catalog("a.b|type=int", "a.b|values=80,81").check("a.b", " 80 "));
    }

    @Test
    void a_key_no_file_describes_is_not_checked() {
        assertEquals(List.of(), catalog("a.b|type=int").check("a.c", "x"));
        assertEquals(List.of(), catalog("a.b|type=int").check("", "x"));
        assertEquals(List.of(), catalog("a.b|type=int").check(null, "x"));
    }

    @Test
    void a_key_with_a_quoted_name_is_checked_like_any_other() {
        ValueProblem problem = only(catalog("a.<n>.port|type=int").check("a.\"my.name\".port", "x"));

        assertEquals(ValueProblem.Kind.NOT_OF_TYPE, problem.kind());
        assertEquals("a.\"my.name\".port", problem.key());
        assertEquals("a.<n>.port", problem.description().key().text());
    }

    @Test
    void a_key_under_a_free_tail_is_not_checked() {
        assertEquals(List.of(), catalog("a.*|doc=Free.").check("a.b.c", "x"));
    }
}
