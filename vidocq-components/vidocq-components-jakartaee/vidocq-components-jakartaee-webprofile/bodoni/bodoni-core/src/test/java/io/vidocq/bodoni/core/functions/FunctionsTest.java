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

package io.vidocq.bodoni.core.functions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FunctionsTest {

    @Test
    void lengthOfNullIsZero() {
        assertEquals(0, Functions.length(null));
    }

    @Test
    void lengthOfStringCountsCharacters() {
        assertEquals(3, Functions.length("abc"));
        assertEquals(0, Functions.length(""));
    }

    @Test
    void lengthOfCollectionsAndArraysAndMaps() {
        assertEquals(2, Functions.length(List.of(1, 2)));
        assertEquals(3, Functions.length(new int[3]));
        assertEquals(2, Functions.length(new String[] {"a", "b"}));
        assertEquals(1, Functions.length(Map.of("a", 1)));
    }

    @Test
    void lengthOfIteratorAndEnumeration() {
        assertEquals(2, Functions.length(List.of(1, 2).iterator()));
        assertEquals(2, Functions.length(Collections.enumeration(List.of(1, 2))));
    }

    @Test
    void substringFollowsTheAdjustmentRules() {
        assertEquals("ob", Functions.substring("foobar", 2, 4));
        assertEquals("fo", Functions.substring("foobar", -1, 2));
        assertEquals("bar", Functions.substring("foobar", 3, -1));
        assertEquals("bar", Functions.substring("foobar", 3, 99));
        assertEquals("", Functions.substring("foobar", 4, 1));
        assertEquals("", Functions.substring("foobar", 6, 9));
        assertEquals("", Functions.substring(null, 0, 3));
    }

    @Test
    void splitUsesTokenizerSemantics() {
        assertArrayEquals(new String[] {"a", "b", "c"}, Functions.split("a,b,,c", ","));
        assertArrayEquals(new String[] {"a", "b", "c"}, Functions.split("a,b;c", ",;"));
        assertArrayEquals(new String[] {""}, Functions.split("", ","));
        assertArrayEquals(new String[] {""}, Functions.split(null, ","));
        assertArrayEquals(new String[] {"a,b"}, Functions.split("a,b", ""));
        assertArrayEquals(new String[] {"a,b"}, Functions.split("a,b", null));
    }

    @Test
    void joinConcatenatesWithSeparator() {
        assertEquals("a-b-c", Functions.join(new String[] {"a", "b", "c"}, "-"));
        assertEquals("abc", Functions.join(new String[] {"a", "b", "c"}, ""));
        assertEquals("abc", Functions.join(new String[] {"a", "b", "c"}, null));
        assertEquals("", Functions.join(null, ","));
    }

    @Test
    void indexOf() {
        assertEquals(0, Functions.indexOf("abc", ""));
        assertEquals(1, Functions.indexOf("abc", "bc"));
        assertEquals(-1, Functions.indexOf("abc", "x"));
        assertEquals(0, Functions.indexOf(null, null));
    }

    @Test
    void replace() {
        assertEquals("ab", Functions.replace("aXbX", "X", ""));
        assertEquals("aYbY", Functions.replace("aXbX", "X", "Y"));
        assertEquals("aXbX", Functions.replace("aXbX", "", "Y"));
        assertEquals("aXbX", Functions.replace("aXbX", null, "Y"));
        assertEquals("", Functions.replace(null, "X", "Y"));
        assertEquals("XX", Functions.replace("X", "X", "XX"));
    }

    @Test
    void containsAndIgnoreCase() {
        assertTrue(Functions.contains("abc", "b"));
        assertTrue(Functions.contains("abc", ""));
        assertTrue(Functions.contains(null, null));
        assertFalse(Functions.contains("abc", "B"));
        assertTrue(Functions.containsIgnoreCase("ABC", "b"));
        assertFalse(Functions.containsIgnoreCase("ABC", "x"));
        assertTrue(Functions.containsIgnoreCase(null, null));
    }

    @Test
    void startsAndEndsWith() {
        assertTrue(Functions.startsWith("foobar", "foo"));
        assertTrue(Functions.startsWith("foobar", ""));
        assertTrue(Functions.startsWith(null, null));
        assertFalse(Functions.startsWith("foobar", "bar"));
        assertTrue(Functions.endsWith("foobar", "bar"));
        assertTrue(Functions.endsWith("foobar", null));
        assertFalse(Functions.endsWith("foobar", "foo"));
    }

    @Test
    void substringAfterAndBefore() {
        assertEquals("bar", Functions.substringAfter("foo-bar", "-"));
        assertEquals("foo-bar", Functions.substringAfter("foo-bar", ""));
        assertEquals("", Functions.substringAfter("foo", "x"));
        assertEquals("", Functions.substringAfter(null, "x"));
        assertEquals("foo", Functions.substringBefore("foo-bar", "-"));
        assertEquals("", Functions.substringBefore("foo-bar", ""));
        assertEquals("", Functions.substringBefore("foo", "x"));
        assertEquals("", Functions.substringBefore(null, null));
    }

    @Test
    void caseAndTrim() {
        assertEquals("abc", Functions.toLowerCase("AbC"));
        assertEquals("ABC", Functions.toUpperCase("AbC"));
        assertEquals("", Functions.toLowerCase(null));
        assertEquals("", Functions.toUpperCase(null));
        assertEquals("a b", Functions.trim("  a b \t"));
        assertEquals("", Functions.trim(null));
    }

    @Test
    void escapeXmlDelegatesToXml() {
        assertEquals("&lt;&amp;&gt;&#034;&#039;", Functions.escapeXml("<&>\"'"));
        assertEquals("", Functions.escapeXml(null));
    }
}
