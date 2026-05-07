/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP Config 3.1 §5.4 — split d'une chaîne brute en éléments par {@code ,}
 * avec échappement {@code \,}.
 */
@DisplayName("ArraySplitter — split par virgule + échappement \\, (MP Config 3.1 §5.4)")
class ArraySplitterTest {

    @Test
    void split_spec_section_5_4_simple() {
        assertEquals(List.of("a", "b", "c"), ArraySplitter.split("a,b,c"));
    }

    @Test
    void split_spec_section_5_4_ignores_empty_segments() {
        // §5.4 — les éléments vides sont ignorés.
        assertEquals(List.of("a", "b"), ArraySplitter.split("a,,b"));
        assertEquals(List.of("a"), ArraySplitter.split("a,"));
        assertEquals(List.of("a"), ArraySplitter.split(",a"));
        assertTrue(ArraySplitter.split(",,").isEmpty());
    }

    @Test
    void split_spec_section_5_4_escaped_comma() {
        // \, → virgule littérale dans la valeur
        assertEquals(List.of("a,b", "c"), ArraySplitter.split("a\\,b,c"));
        assertEquals(List.of("a,b,c"), ArraySplitter.split("a\\,b\\,c"));
    }

    @Test
    void split_spec_section_5_4_only_escaped_commas_yields_one_element() {
        assertEquals(List.of(",,"), ArraySplitter.split("\\,\\,"));
    }

    @Test
    void split_keeps_backslash_when_not_followed_by_comma() {
        // La spec ne définit pas \\, on conserve l'anti-slash verbatim.
        assertEquals(List.of("a\\b", "c"), ArraySplitter.split("a\\b,c"));
    }

    @Test
    void split_empty_string_yields_empty_list() {
        assertTrue(ArraySplitter.split("").isEmpty());
    }

    @Test
    void split_single_element_no_comma() {
        assertEquals(List.of("only"), ArraySplitter.split("only"));
    }
}

