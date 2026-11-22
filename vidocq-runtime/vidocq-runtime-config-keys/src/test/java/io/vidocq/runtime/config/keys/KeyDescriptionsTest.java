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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeyDescriptionsTest {

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private static KeyDescriptions read(String... lines) {
        return KeyDescriptions.read("f", String.join("\n", lines));
    }

    private static Problem onlyProblem(KeyDescriptions file) {
        assertEquals(1, file.problems().size(), file.problems().toString());
        return file.problems().getFirst();
    }

    @Test
    void a_key_is_read_with_its_attributes() {
        KeyDescriptions file = read("format=1",
                "vidocq.devconsole.port|type=int",
                "vidocq.devconsole.port|default=8888",
                "vidocq.devconsole.port|doc=Port the dev console listens on.");

        assertEquals(List.of(), file.problems());
        assertEquals("f", file.origin());
        assertEquals(1, file.keys().size());
        KeyDescription port = file.keys().getFirst();
        assertEquals("vidocq.devconsole.port", port.key().text());
        assertEquals("int", port.type());
        assertEquals(Optional.of("8888"), port.defaultValue());
        assertEquals(Optional.of("Port the dev console listens on."), port.doc());
        assertEquals(Optional.empty(), port.when());
        assertEquals(Optional.empty(), port.ref());
        assertEquals(Optional.empty(), port.source());
    }

    @Test
    void a_key_is_a_string_and_optional_until_the_file_says_otherwise() {
        KeyDescription key = read("format=1", "a.b|values= x , y,z").keys().getFirst();

        assertEquals("string", key.type());
        assertEquals(List.of("x", "y", "z"), key.values());
        assertFalse(key.required());
        assertFalse(key.deprecated());
        assertEquals(Optional.empty(), key.defaultValue());
    }

    @Test
    void the_other_attributes_are_read() {
        KeyDescriptions file = read("format=1",
                "a.b|required=true", "a.b|deprecated=true", "a.b|ref=datasource", "a.b|source=com.example.A#b");

        assertEquals(List.of(), file.problems());
        KeyDescription key = file.keys().getFirst();
        assertTrue(key.required());
        assertTrue(key.deprecated());
        assertEquals(Optional.of("datasource"), key.ref());
        assertEquals(Optional.of("com.example.A#b"), key.source());
    }

    @Test
    void a_condition_names_a_sibling_and_its_values() {
        KeyDescriptions file = read("format=1", "vidocq.http.mount.<mount>.classpath|when=type=static, websocket");

        assertEquals(List.of(), file.problems());
        assertEquals(Optional.of(new KeyDescription.Condition("type", List.of("static", "websocket"))),
                file.keys().getFirst().when());
    }

    @Test
    void a_line_on_a_family_gives_its_names_and_its_documentation() {
        KeyDescriptions file = read("format=1", "<listener>|names=default,dev", "<listener>|doc=A listener.");

        assertEquals(List.of(), file.problems());
        assertEquals(List.of(), file.keys());
        assertEquals(List.of(new KeyFamily("listener", List.of("default", "dev"), Optional.of("A listener."))),
                file.families());
    }

    @Test
    void comments_blank_lines_a_byte_order_mark_and_windows_line_ends_are_skipped() {
        KeyDescriptions file = KeyDescriptions.read("f",
                BYTE_ORDER_MARK + "# a comment\r\n\r\n  ! another\r\nformat=1\r\n  a.b|type = int  \r\n");

        assertEquals(List.of(), file.problems());
        assertEquals("int", file.keys().getFirst().type());
    }

    @Test
    void a_byte_order_mark_is_skipped_in_a_stream_too() throws IOException {
        byte[] bytes = (BYTE_ORDER_MARK + "format=1\na.b|type=int\n").getBytes(StandardCharsets.UTF_8);
        assertEquals((byte) 0xEF, bytes[0]);

        KeyDescriptions file = KeyDescriptions.read("f", new ByteArrayInputStream(bytes));

        assertEquals(List.of(), file.problems());
        assertEquals("int", file.keys().getFirst().type());
    }

    @Test
    void a_stream_is_read_as_utf8() throws IOException {
        byte[] bytes = "format=1\na.b|doc=Clé de l'été.\n".getBytes(StandardCharsets.UTF_8);

        KeyDescriptions file = KeyDescriptions.read("f", new ByteArrayInputStream(bytes));

        assertEquals(Optional.of("Clé de l'été."), file.keys().getFirst().doc());
    }

    @Test
    void an_empty_default_is_a_default() {
        KeyDescriptions file = read("format=1", "a.b|default=");

        assertEquals(List.of(), file.problems());
        assertEquals(Optional.of(""), file.keys().getFirst().defaultValue());
    }

    @Test
    void a_value_may_hold_an_equals_sign_and_a_bar() {
        KeyDescription key = read("format=1", "a.b|doc=x=1 | y=2").keys().getFirst();

        assertEquals(Optional.of("x=1 | y=2"), key.doc());
    }

    @Test
    void keys_come_in_the_order_the_file_first_names_them() {
        KeyDescriptions file = read("format=1", "b.b|type=int", "a.a|type=int", "b.b|doc=x");

        assertEquals(List.of("b.b", "a.a"), file.keys().stream().map(key -> key.key().text()).toList());
    }

    @Test
    void no_file_is_an_empty_one() {
        assertEquals(List.of(), KeyDescriptions.none().keys());
        assertEquals(List.of(), KeyDescriptions.none().families());
        assertEquals(List.of(), KeyDescriptions.none().problems());
    }

    @Test
    void the_first_fact_of_a_file_is_its_format() {
        KeyDescriptions file = read("# c", "a.b|type=int", "a.c|type=int");

        Problem problem = onlyProblem(file);
        assertEquals(new Problem("f", 2, Problem.Kind.WRONG, problem.message()), problem);
        assertTrue(problem.message().contains("format=1"), problem.message());
        assertEquals(2, file.keys().size());
    }

    @Test
    void an_empty_file_has_no_format_either() {
        assertEquals(Problem.Kind.WRONG, onlyProblem(read("# nothing")).kind());
    }

    @Test
    void a_newer_format_is_not_read() {
        KeyDescriptions file = read("format=2", "a.b|type=int");

        Problem problem = onlyProblem(file);
        assertEquals(Problem.Kind.NEWER, problem.kind());
        assertEquals(1, problem.line());
        assertEquals(List.of(), file.keys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"format=one", "format=0", "format="})
    void a_format_that_is_not_a_version_is_wrong(String format) {
        assertEquals(Problem.Kind.WRONG, onlyProblem(read(format)).kind());
    }

    @Test
    void an_attribute_this_reader_does_not_know_is_reported_as_newer_and_skipped() {
        KeyDescriptions file = read("format=1", "a.b|colour=red", "a.b|type=int");

        Problem problem = onlyProblem(file);
        assertEquals(Problem.Kind.NEWER, problem.kind());
        assertEquals(2, problem.line());
        assertTrue(problem.message().contains("colour"), problem.message());
        assertEquals("int", file.keys().getFirst().type());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "a.b|type", "a.b=int", "|type=int", "a.b|=int"})
    void a_line_that_is_not_a_fact_is_wrong(String line) {
        KeyDescriptions file = read("format=1", line);

        Problem problem = onlyProblem(file);
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals(2, problem.line());
        assertEquals(List.of(), file.keys());
    }

    @Test
    void a_pattern_that_cannot_be_read_is_wrong() {
        Problem problem = onlyProblem(read("format=1", "a..b|type=int"));

        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals(2, problem.line());
        assertTrue(problem.message().contains("'a..b' is not a key pattern"), problem.message());
    }

    @Test
    void a_fact_written_twice_keeps_its_first_value() {
        KeyDescriptions file = read("format=1", "a.b|type=int", "a.b|type=long");

        Problem problem = onlyProblem(file);
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals(3, problem.line());
        assertTrue(problem.message().contains("line 2"), problem.message());
        assertEquals("int", file.keys().getFirst().type());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b|required=maybe", "a.b|deprecated=yes", "a.b|type=itn", "a.b|type=list:", "a.b|type=",
            "a.b|when=type", "a.b|when==static", "a.b|when=type=", "a.b|names=x", "a.b|ref=not a family",
            "a.b|values=", "a.b|values= , ", "<f>|type=int", "<f>|names=", "a.b|doc=x\\", "format=1"})
    void a_fact_that_cannot_be_right_is_wrong(String line) {
        KeyDescriptions file = read("format=1", line);

        Problem problem = onlyProblem(file);
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals(2, problem.line());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b|type=list:int", "a.b|type=java.net.URI", "a.b|type=list:java.net.URI",
            "a.b|type=duration", "a.b|type=boolean", "a.b|type=long", "a.b|type=double", "a.b|type=string",
            "a.b|required=true", "a.b|required=false", "a.b|doc=", "<f>|doc=A family."})
    void a_fact_of_the_format_is_right(String line) {
        assertEquals(List.of(), read("format=1", line).problems());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b|doc=C:\\tmp", "a.b|doc=a\\tb", "a.b |type=int", "a.b| type=int", "a:b|type=int",
            "a.b|ty pe=int"})
    void what_java_util_properties_would_read_otherwise_is_wrong(String line) {
        Problem problem = onlyProblem(read("format=1", line));

        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertEquals(2, problem.line());
    }

    @Test
    void a_colon_and_white_space_are_fine_in_a_value() {
        KeyDescriptions file = read("format=1", "a.b|doc=Note: one of a, b.");

        assertEquals(List.of(), file.problems());
        assertEquals(Optional.of("Note: one of a, b."), file.keys().getFirst().doc());
    }

    @Test
    void a_key_named_by_a_fact_of_a_later_format_still_exists() {
        KeyDescriptions file = read("format=1", "a.b|secret=true");

        assertEquals(Problem.Kind.NEWER, onlyProblem(file).kind());
        assertEquals(List.of("a.b"), file.keys().stream().map(key -> key.key().text()).toList());
        assertEquals("string", file.keys().getFirst().type());
    }

    @Test
    void a_key_named_by_a_wrong_fact_still_exists() {
        KeyDescriptions file = read("format=1", "a.b|type=itn");

        assertEquals(Problem.Kind.WRONG, onlyProblem(file).kind());
        assertEquals(List.of("a.b"), file.keys().stream().map(key -> key.key().text()).toList());
    }

    @Test
    void a_family_named_by_a_fact_of_a_later_format_still_exists() {
        KeyDescriptions file = read("format=1", "<unit>|scope=application");

        assertEquals(Problem.Kind.NEWER, onlyProblem(file).kind());
        assertEquals(List.of(new KeyFamily("unit", List.of(), Optional.empty())), file.families());
    }

    @Test
    void an_attribute_that_is_not_a_word_is_wrong_and_not_newer() {
        KeyDescriptions file = read("format=1", "a.b|c|type=int");

        assertEquals(Problem.Kind.WRONG, onlyProblem(file).kind());
        assertEquals(List.of(), file.keys());
    }

    @Test
    void a_fact_keeps_its_file_and_its_line() {
        KeyDescriptions file = read("format=1", "# c", "a.b|type=int", "a.b|doc=A key.");

        KeyDescription key = file.keys().getFirst();
        assertEquals(Optional.of(new Fact("int", "f", 3)), key.fact(Attribute.TYPE));
        assertEquals(Optional.of(new Fact("A key.", "f", 4)), key.fact(Attribute.DOC));
        assertEquals(Optional.empty(), key.fact(Attribute.DEFAULT));
    }

    @Test
    void a_description_built_by_hand_with_a_condition_that_is_none_has_no_condition() {
        KeyDescription key = new KeyDescription(KeyPattern.parse("a.b"),
                java.util.Map.of(Attribute.WHEN, new Fact("nonsense", "f", 1)));

        assertEquals(Optional.empty(), key.when());
    }

    @Test
    void the_rest_of_a_file_is_read_after_a_wrong_line() {
        KeyDescriptions file = read("format=1", "nonsense", "a.b|type=int");

        assertEquals(1, file.problems().size());
        assertEquals("int", file.keys().getFirst().type());
    }

    @Test
    void an_attribute_is_named_as_the_file_writes_it() {
        assertEquals("default", Attribute.DEFAULT.text());
        assertEquals(Optional.of(Attribute.WHEN), Attribute.of("when"));
        assertEquals(Optional.empty(), Attribute.of("When"));
    }
}
