/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261028-21: the YAML reader of static files (spec §4.2, YAML 1.2) accepts the layouts
 * real documents use — any indentation, sequences at the indentation of their key, comments,
 * flow collections, multi-line scalars, block chomping and document markers.
 */
class YamlDeserializerTest {

    private static final String DOC = "openapi: 3.1.0\n";
    private static final String TAGS = "tags";
    private static final String KEY = "k";

    private static Map<?, ?> parse(String... lines) {
        return (Map<?, ?>) YamlDeserializer.parseRaw(String.join("\n", lines) + "\n");
    }

    private static Object value(String... lines) {
        return parse(lines).get(KEY);
    }

    @Test
    void fourSpaceIndentationIsRead() {
        OpenAPI openAPI = new YamlDeserializer().deserialize(DOC + "info:\n    title: t\n    version: '1'\n");

        assertEquals("t", openAPI.getInfo().getTitle());
        assertEquals("1", openAPI.getInfo().getVersion());
    }

    @Test
    void sequenceAtTheIndentationOfItsKey() {
        OpenAPI openAPI = new YamlDeserializer().deserialize(
                DOC + "servers:\n- url: http://a\n  description: first\n- url: http://b\ninfo:\n  title: t\n");

        assertEquals(2, openAPI.getServers().size());
        assertEquals("first", openAPI.getServers().get(0).getDescription());
        assertEquals("http://b", openAPI.getServers().get(1).getUrl());
        assertEquals("t", openAPI.getInfo().getTitle());
    }

    @Test
    void commentsAreIgnoredOutsideQuotes() {
        Map<?, ?> map = parse("# leading", "a: 1 # trailing", "  # indented comment", "b: '# kept'",
                "c: \"x # y\"", "d: a#b");

        assertEquals(1L, map.get("a"));
        assertEquals("# kept", map.get("b"));
        assertEquals("x # y", map.get("c"));
        assertEquals("a#b", map.get("d"));
    }

    @Test
    void flowSequencesAndMappings() {
        Map<?, ?> map = parse("tags: [a, 'b, c', \"d\"]", "empty: []", "obj: {x: 1, y: [true, null], z: {}}",
                "trailing: [1, 2, ]", "keys: {\"q\": v, 'r': w, n:, m}");

        assertEquals(List.of("a", "b, c", "d"), map.get(TAGS));
        assertEquals(List.of(), map.get("empty"));
        Map<?, ?> obj = (Map<?, ?>) map.get("obj");
        assertEquals(1L, obj.get("x"));
        assertEquals(Arrays.asList(true, null), obj.get("y"));
        assertEquals(Map.of(), obj.get("z"));
        assertEquals(List.of(1L, 2L), map.get("trailing"));
        Map<?, ?> keys = (Map<?, ?>) map.get("keys");
        assertEquals("v", keys.get("q"));
        assertEquals("w", keys.get("r"));
        assertTrue(keys.containsKey("n"));
        assertNull(keys.get("m"));
    }

    @Test
    void flowCollectionOverSeveralLines() {
        Map<?, ?> map = parse("tags: [", "  a,", "  b", "]", "next: 1");

        assertEquals(List.of("a", "b"), map.get(TAGS));
        assertEquals(1L, map.get("next"));
    }

    @Test
    void flowCollectionAsSequenceItem() {
        Map<?, ?> map = parse("list:", "  - [1, 2]", "  - {a: b}", "  - 'it''s'");

        assertEquals(List.of(List.of(1L, 2L), Map.of("a", "b"), "it's"), map.get("list"));
    }

    @Test
    void multiLinePlainAndQuotedScalars() {
        Map<?, ?> map = parse("plain: first", "  second", "quoted: \"one", "  two\"", "after: x");

        assertEquals("first second", map.get("plain"));
        assertEquals("one two", map.get("quoted"));
        assertEquals("x", map.get("after"));
    }

    @Test
    void literalBlockKeepsLinesAndClipsTheFinalNewline() {
        assertEquals("line 1\n  line 2\n", value("k: |", "  line 1", "    line 2", "", "other: x"));
    }

    @Test
    void foldedBlockJoinsLines() {
        assertEquals("a b\nc\n", value("k: >", "    a", "    b", "", "    c"));
    }

    @Test
    void blockChompingIndicators() {
        assertEquals("a", value("k: |-", "  a", ""));
        assertEquals("a b", value("k: >-", "  a", "  b"));
        assertEquals("a\n\n", value("k: |+", "  a", "", "z: 1"));
        assertEquals("", value("k: |", "z: 1"));
    }

    @Test
    void blockScalarAsSequenceItem() {
        assertEquals(List.of("text\n", "next"), value("k:", "  - |", "    text", "  - next"));
    }

    @Test
    void documentMarkers() {
        Map<?, ?> map = parse("---", "a: 1", "...", "ignored: true");

        assertEquals(Map.of("a", 1L), map);
        assertEquals(Map.of(), YamlDeserializer.parseRaw("---\n# nothing\n"));
        assertEquals(Map.of(), YamlDeserializer.parseRaw(""));
    }

    @Test
    void coreSchemaScalars() {
        Map<?, ?> map = parse("n1: ~", "n2: Null", "t: True", "f: FALSE", "i: +12", "big: 123456789012345678901",
                "oct: 0o17", "hex: 0xff", "hugeHex: 0xffffffffffffffffff", "fl: 1.5e3", "s: 1.2.3");

        assertTrue(map.containsKey("n1"));
        assertNull(map.get("n2"));
        assertEquals(Boolean.TRUE, map.get("t"));
        assertEquals(Boolean.FALSE, map.get("f"));
        assertEquals(12L, map.get("i"));
        assertEquals(new BigInteger("123456789012345678901"), map.get("big"));
        assertEquals(15L, map.get("oct"));
        assertEquals(255L, map.get("hex"));
        assertEquals("0xffffffffffffffffff", map.get("hugeHex"));
        assertEquals(1500.0, map.get("fl"));
        assertEquals("1.2.3", map.get("s"));
    }

    @Test
    void floatScalarForms() {
        Map<?, ?> map = parse("lead: .5", "trail: 2.", "neg: -1.25E-2", "exp: 3e2", "plus: +4.0e+1",
                "noMantissa: e5", "dotOnly: .", "badExp: 1e", "twoSigns: --1.0");

        assertEquals(0.5, map.get("lead"));
        assertEquals(2.0, map.get("trail"));
        assertEquals(-0.0125, map.get("neg"));
        assertEquals(300.0, map.get("exp"));
        assertEquals(40.0, map.get("plus"));
        assertEquals("e5", map.get("noMantissa"));
        assertEquals(".", map.get("dotOnly"));
        assertEquals("1e", map.get("badExp"));
        assertEquals("--1.0", map.get("twoSigns"));
    }

    @Test
    void doubleQuotedEscapes() {
        assertEquals("a\nb\tc\r\"\\A\0/", value("k: \"a\\nb\\tc\\r\\\"\\\\\\u0041\\0\\/\""));
        assertEquals("end\\", value("k: \"end\\\""));
    }

    @Test
    void quotedAndJsonLikeKeys() {
        Map<?, ?> map = parse("'x: y': 1", "\"j\":2", "\"/p/{id}\": 3", "plain key: 4");

        assertEquals(1L, map.get("x: y"));
        assertEquals(2L, map.get("j"));
        assertEquals(3L, map.get("/p/{id}"));
        assertEquals(4L, map.get("plain key"));
    }

    @Test
    void nestedSequencesAndWideItemIndentation() {
        assertEquals(List.of(List.of("a", "b"), Map.of("x", 1L, "y", 2L), "c"),
                value("k:", "- - a", "  - b", "-   x: 1", "    y: 2", "- c"));
    }

    @Test
    void emptyValues() {
        Map<?, ?> map = parse("a:", "b:", "  - ", "c: x");

        assertTrue(map.containsKey("a"));
        assertNull(map.get("a"));
        assertEquals(Arrays.asList((Object) null), map.get("b"));
        assertEquals("x", map.get("c"));
    }

    @Test
    void lineWithoutColonIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> YamlDeserializer.parseRaw("a: 1\nnot a mapping\n"));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void strayIndentationIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> YamlDeserializer.parseRaw("a:\n    b: 1\n  c: 2\n"));
        assertTrue(error.getMessage().contains("indentation"), error.getMessage());
    }

    @Test
    void malformedFlowCollectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: [a, b\n"));
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: [a, , b]\n"));
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: [a] b\n"));
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: {a: 1] }\n"));
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: ['open]\n"));
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("k: [\n"));
    }

    @Test
    void unterminatedQuotedKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> YamlDeserializer.parseRaw("'open: 1\n"));
    }

    @Test
    void nullDocumentIsRejected() {
        YamlDeserializer deserializer = new YamlDeserializer();
        assertThrows(NullPointerException.class, () -> deserializer.deserialize(null));
    }
}
