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

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The served document (spec §2.2: {@code /openapi} in JSON and YAML) reads back to the same model:
 * a document written by either serializer and read by either reader writes the same JSON again.
 */
class SerializationRoundTripTest {

    private static final String VERSION = "3.1.0";
    private static final String CONTROL = "bell\u0007 back\b feed\f nul\0 tab\t";

    private static OpenAPI full() {
        return new JsonDeserializer().deserialize(StaticDocumentMappingTest.DOCUMENT);
    }

    @Test
    void jsonOutputReadsBackToTheSameDocument() {
        String json = new JsonSerializer().serialize(full());

        String again = new JsonSerializer().serialize(new JsonDeserializer().deserialize(json));

        assertEquals(json, again);
    }

    @Test
    void yamlOutputReadsBackToTheSameDocument() {
        OpenAPI openAPI = full();
        String json = new JsonSerializer().serialize(openAPI);
        String yaml = new YamlSerializer().serialize(openAPI);

        OpenAPI back = new YamlDeserializer().deserialize(yaml);

        assertEquals(json, new JsonSerializer().serialize(back));
        assertEquals(yaml, new YamlSerializer().serialize(back));
    }

    @Test
    void yamlOutputUsesFlowStyleForEmptyCollectionsAndQuotesOddKeys() {
        String yaml = new YamlSerializer().serialize(full());

        assertTrue(yaml.contains("put: {}"), yaml);
        assertTrue(yaml.contains("\"{$request.body#/url}\":"), yaml);
        assertTrue(yaml.contains("enum:\n"), yaml);
        assertTrue(yaml.contains("key: []"), yaml);
    }

    @Test
    void nestedAndEmptyListsInExtensions() {
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class).openapi(VERSION);
        openAPI.addExtension("x-matrix", List.of(List.of(1, 2), List.of(),
                Map.of(), Map.of("k", "v")));

        String yaml = new YamlSerializer().serialize(openAPI);
        OpenAPI back = new YamlDeserializer().deserialize(yaml);

        assertEquals(new JsonSerializer().serialize(openAPI), new JsonSerializer().serialize(back));
        assertTrue(yaml.contains("  - []\n"), yaml);
        assertTrue(yaml.contains("  - {}\n"), yaml);
    }

    @Test
    void scalarsAreWrittenAsJsonAndYamlScalars() {
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class).openapi(VERSION);
        openAPI.addExtension("x-decimal", new BigDecimal("1E-10"));
        openAPI.addExtension("x-flag", true);
        openAPI.addExtension("x-quote", "say \"hi\"\\ \r\n");

        String json = new JsonSerializer().serialize(openAPI);
        String yaml = new YamlSerializer().serialize(openAPI);

        assertTrue(json.contains("\"x-decimal\":0.0000000001"), json);
        assertTrue(json.contains("\"x-flag\":true"), json);
        assertTrue(json.contains("\"x-quote\":\"say \\\"hi\\\"\\\\ \\r\\n\""), json);
        assertTrue(yaml.contains("x-decimal: 0.0000000001"), yaml);
        assertTrue(yaml.contains("x-flag: true"), yaml);
        assertEquals("say \"hi\"\\ \r\n", new YamlDeserializer().deserialize(yaml).getExtensions().get("x-quote"));
    }

    @Test
    void controlCharactersAreEscaped() {
        // BUG-20261028-23: RFC 8259 §7 and YAML 1.2 §5.7 forbid raw C0 control characters in strings.
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class).openapi(VERSION)
                .info(OASFactory.createObject(Info.class).title(CONTROL).version("1"));

        String json = new JsonSerializer().serialize(openAPI);
        String yaml = new YamlSerializer().serialize(openAPI);

        assertFalse(json.chars().anyMatch(c -> c < 0x20), json);
        assertFalse(yaml.chars().anyMatch(c -> c < 0x20 && c != '\n'), yaml);
        assertEquals(CONTROL, new JsonDeserializer().deserialize(json).getInfo().getTitle());
        assertEquals(CONTROL, new YamlDeserializer().deserialize(yaml).getInfo().getTitle());
    }

    @Test
    void nullDocumentIsRejected() {
        JsonSerializer json = new JsonSerializer();
        YamlSerializer yaml = new YamlSerializer();

        assertThrows(NullPointerException.class, () -> json.serialize(null));
        assertThrows(NullPointerException.class, () -> yaml.serialize(null));
    }
}
