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
package io.vidocq.grimm.internal.model;

import io.vidocq.grimm.internal.serialization.JsonSerializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.Schema.SchemaType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic property access of {@link SchemaImpl} ({@code get}, {@code set}, {@code getAll},
 * {@code setAll}), against the MP OpenAPI 4.2 {@code Schema} Javadoc: a property is named as in the
 * JSON document, {@code getAll} is "equivalent to calling {@code get} for each property set to a
 * non-null value", and {@code setAll} is "equivalent to clearing all properties, including
 * extensions, and then setting each property with {@code set}" (BUG-20261004-01).
 */
class SchemaImplTest {

    @Test
    void getAllListsStandardPropertiesUnderTheirJsonNamesWithTheExtensions() {
        Schema ifSchema = OASFactory.createSchema();
        Schema s = OASFactory.createSchema()
                .title("t")
                .minimum(BigDecimal.ONE)
                .type(List.of(SchemaType.STRING))
                .defaultValue("d")
                .enumeration(List.of("a"))
                .schemaDialect("https://example.com/dialect")
                .comment("c")
                .ifSchema(ifSchema)
                .constValue("k")
                .addExtension("x-a", "v");
        s.setRef("#/components/schemas/Other");

        assertEquals(Map.ofEntries(
                Map.entry("$ref", "#/components/schemas/Other"),
                Map.entry("title", "t"),
                Map.entry("minimum", BigDecimal.ONE),
                Map.entry("type", List.of(SchemaType.STRING)),
                Map.entry("default", "d"),
                Map.entry("enum", List.of("a")),
                Map.entry("$schema", "https://example.com/dialect"),
                Map.entry("$comment", "c"),
                Map.entry("if", ifSchema),
                Map.entry("const", "k"),
                Map.entry("x-a", "v")), s.getAll());
    }

    @Test
    void getAllIsGetForEachProperty() {
        Schema s = OASFactory.createSchema()
                .title("t")
                .maxLength(3)
                .required(List.of("p"))
                .addProperty("p", OASFactory.createSchema())
                .addExtension("x-a", "v");

        Map<String, ?> all = s.getAll();
        assertEquals(5, all.size(), all.toString());
        all.forEach((name, value) -> assertEquals(s.get(name), value, name));
    }

    @Test
    void getAllOfAnEmptySchemaIsEmpty() {
        assertEquals(Map.of(), OASFactory.createSchema().getAll());
    }

    @Test
    void setAllClearsTheStandardPropertiesToo() {
        Schema s = OASFactory.createSchema()
                .title("t")
                .minimum(BigDecimal.ONE)
                .items(OASFactory.createSchema())
                .addExtension("x-old", "o");
        s.setRef("#/components/schemas/Other");

        s.setAll(Map.of("description", "d", "x-a", "v"));

        assertNull(s.getTitle());
        assertNull(s.getMinimum());
        assertNull(s.getItems());
        assertNull(s.getRef());
        assertFalse(s.hasExtension("x-old"));
        assertEquals("d", s.getDescription());
        assertEquals("v", s.getExtension("x-a"));
        assertEquals(Map.of("description", "d", "x-a", "v"), s.getAll());
    }

    @Test
    void setAllWithNullClearsEverything() {
        Schema s = OASFactory.createSchema().title("t").addExtension("x-a", "v");
        s.setAll(null);
        assertEquals(Map.of(), s.getAll());
    }

    @Test
    void setAllOfGetAllCopiesTheSchema() {
        Schema s = OASFactory.createSchema()
                .title("t")
                .type(List.of(SchemaType.OBJECT))
                .addProperty("p", OASFactory.createSchema().type(List.of(SchemaType.INTEGER)))
                .additionalPropertiesSchema(OASFactory.createSchema().type(List.of(SchemaType.STRING)))
                .examples(List.of("e"))
                .addExtension("x-a", "v")
                .set("$ref", "Pet.yaml");

        Schema copy = OASFactory.createSchema();
        copy.setAll(s.getAll());

        assertEquals("Pet.yaml", copy.getRef());
        assertEquals(json(s), json(copy));
    }

    @Test
    void dollarRefIsAPropertyKeptAsWritten() {
        Schema s = OASFactory.createSchema().set("$ref", "Pet.yaml");
        assertEquals("Pet.yaml", s.getRef());
        assertEquals("Pet.yaml", s.get("$ref"));
        assertFalse(s.hasExtension("$ref"));

        s.set("$ref", null);
        assertNull(s.getRef());
    }

    @Test
    void javaAccessorNamesAreNotSchemaProperties() {
        Map<String, Object> extensions = Map.of("x-a", "v");
        Map<String, Object> all = Map.of("title", "t");
        Schema s = OASFactory.createSchema()
                .set("extensions", extensions)
                .set("all", all)
                .set("ref", "Foo")
                .set("defaultValue", "d");

        assertFalse(s.hasExtension("x-a"));
        assertNull(s.getTitle());
        assertNull(s.getRef());
        assertNull(s.getDefaultValue());
        assertSame(extensions, s.getExtension("extensions"));
        assertSame(all, s.get("all"));
        assertEquals("Foo", s.get("ref"));
        assertEquals("d", s.get("defaultValue"));

        Schema empty = OASFactory.createSchema();
        assertNull(empty.get("all"));
        assertNull(empty.get("extensions"));
        assertNull(empty.get("class"));
    }

    @Test
    void valueOfAnotherTypeReplacesTheTypedPropertyAndBack() {
        Schema s = OASFactory.createSchema().minimum(BigDecimal.ONE);

        // An alternative dialect may give a standard name another type: kept as written.
        s.set("minimum", "low");
        assertNull(s.getMinimum());
        assertEquals("low", s.get("minimum"));
        assertEquals(Map.of("minimum", "low"), s.getAll());

        s.set("minimum", BigDecimal.TEN);
        assertEquals(BigDecimal.TEN, s.getMinimum());
        assertFalse(s.hasExtension("minimum"));
        assertEquals(Map.of("minimum", BigDecimal.TEN), s.getAll());
    }

    @Test
    void additionalPropertiesIsReadAsASchema() {
        Schema s = OASFactory.createSchema().additionalPropertiesBoolean(Boolean.TRUE);
        Schema value = assertInstanceOf(Schema.class, s.getAll().get("additionalProperties"));
        assertEquals(Boolean.TRUE, value.getBooleanSchema());

        s.set("additionalProperties", Boolean.FALSE);
        assertEquals(Boolean.FALSE, s.getAdditionalPropertiesBoolean());
        assertTrue(s.getExtensions().isEmpty());
    }

    private static String json(Schema schema) {
        OpenAPI doc = OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("S", schema));
        return new JsonSerializer().serialize(doc);
    }
}
