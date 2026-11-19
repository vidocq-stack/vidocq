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

