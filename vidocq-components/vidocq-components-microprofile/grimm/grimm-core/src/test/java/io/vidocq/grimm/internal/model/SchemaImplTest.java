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
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.Schema.SchemaType;
import org.eclipse.microprofile.openapi.models.media.XML;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    @SuppressWarnings("deprecation") // getAdditionalPropertiesBoolean: the form under test
    void additionalPropertiesBooleanFormSurvivesSetAllOfGetAll() {
        Schema s = OASFactory.createSchema().additionalPropertiesBoolean(Boolean.FALSE);

        Schema copy = OASFactory.createSchema();
        copy.setAll(s.getAll());

        assertEquals(Boolean.FALSE, copy.getAdditionalPropertiesBoolean());
        assertEquals(Boolean.FALSE, copy.getAdditionalPropertiesSchema().getBooleanSchema());
        assertEquals(json(s), json(copy));
    }

    @Test
    void setAllClearsABooleanSchemaToo() {
        // "clearing all properties": a schema that was the boolean schema false is one no longer.
        Schema s = OASFactory.createSchema().booleanSchema(Boolean.FALSE);
        s.setAll(Map.of("title", "t"));
        assertNull(s.getBooleanSchema());
        assertEquals("t", s.getTitle());

        Schema t = OASFactory.createSchema().booleanSchema(Boolean.TRUE);
        t.setAll(null);
        assertNull(t.getBooleanSchema());
    }

    @Test
    void listOrMapWhoseElementsDoNotFitTheTypedPropertyIsKeptAsWritten() {
        // set() takes any List or Map of permitted values; one whose elements the typed property
        // cannot hold (an alternative dialect) must not reach the typed field, where it would
        // fail later with a ClassCastException.
        Schema s = OASFactory.createSchema()
                .set("type", List.of("string", "null"))
                .set("required", List.of(1))
                .set("allOf", List.of("not a schema"))
                .set("properties", Map.of("p", "string"))
                .set("dependentRequired", Map.of("a", List.of(2)));

        assertNull(s.getType());
        assertNull(s.getRequired());
        assertNull(s.getAllOf());
        assertNull(s.getProperties());
        assertNull(s.getDependentRequired());
        assertEquals(List.of("string", "null"), s.get("type"));
        assertEquals(List.of(1), s.get("required"));
        assertEquals(List.of("not a schema"), s.get("allOf"));
        assertEquals(Map.of("p", "string"), s.get("properties"));
        assertEquals(Map.of("a", List.of(2)), s.get("dependentRequired"));
        assertTrue(json(s).contains("\"type\":[\"string\",\"null\"]"), json(s));

        // Elements of the right type still land on the typed property.
        Schema item = OASFactory.createSchema();
        s.set("type", List.of(SchemaType.STRING, SchemaType.NULL))
                .set("required", List.of("p"))
                .set("allOf", List.of(item))
                .set("properties", Map.of("p", item))
                .set("dependentRequired", Map.of("a", List.of("b")));
        assertEquals(List.of(SchemaType.STRING, SchemaType.NULL), s.getType());
        assertEquals(List.of("p"), s.getRequired());
        assertEquals(List.of(item), s.getAllOf());
        assertEquals(Map.of("p", item), s.getProperties());
        assertEquals(Map.of("a", List.of("b")), s.getDependentRequired());
        assertTrue(s.getExtensions().isEmpty(), s.getExtensions().toString());
    }

    /**
     * Guard: every typed setter of the MP OpenAPI {@code Schema} interface is a property of the
     * table behind {@code get}/{@code set}/{@code getAll}/{@code setAll}, under its JSON name. A
     * setter added by a future API version and missing from the table would otherwise make its
     * value invisible to {@code getAll}, and {@code set} of its JSON name an extension.
     */
    @Test
    void everyTypedSetterOfTheSchemaInterfaceIsATableProperty() throws ReflectiveOperationException {
        // Not properties: setAll/setExtensions are bulk accessors, a boolean schema is the whole schema.
        Set<String> notProperties = Set.of("setAll", "setExtensions", "setBooleanSchema");
        Map<String, String> irregularNames = Map.ofEntries(
                Map.entry("setRef", "$ref"),
                Map.entry("setSchemaDialect", "$schema"),
                Map.entry("setComment", "$comment"),
                Map.entry("setDefaultValue", "default"),
                Map.entry("setEnumeration", "enum"),
                Map.entry("setConstValue", "const"),
                Map.entry("setIfSchema", "if"),
                Map.entry("setThenSchema", "then"),
                Map.entry("setElseSchema", "else"),
                Map.entry("setAdditionalPropertiesSchema", "additionalProperties"),
                Map.entry("setAdditionalPropertiesBoolean", "additionalProperties"));

        int checked = 0;
        for (Method setter : Schema.class.getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1
                    || notProperties.contains(setter.getName())) {
                continue;
            }
            String suffix = setter.getName().substring(3);
            String jsonName = irregularNames.getOrDefault(setter.getName(),
                    Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));

            Schema schema = new SchemaImpl();
            setter.invoke(schema, sample(setter.getGenericParameterTypes()[0]));

            assertEquals(Set.of(jsonName), schema.getAll().keySet(), setter.getName());
            assertTrue(schema.getExtensions().isEmpty(), setter.getName() + " became an extension");
            checked++;
        }
        assertEquals(56, checked, "typed setters of Schema 4.2 (both additionalProperties forms and setRef)");
    }

    private static Object sample(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (parameterized.getRawType() == List.class) {
                return List.of(sample(arguments[0]));
            }
            if (parameterized.getRawType() == Map.class) {
                return Map.of("k", sample(arguments[1]));
            }
        }
        if (type == String.class) {
            return "v";
        }
        if (type == BigDecimal.class) {
            return BigDecimal.ONE;
        }
        if (type == Integer.class) {
            return 1;
        }
        if (type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == Object.class) {
            return "o";
        }
        if (type == SchemaType.class) {
            return SchemaType.STRING;
        }
        if (type == Schema.class) {
            return new SchemaImpl();
        }
        if (type == Discriminator.class) {
            return OASFactory.createDiscriminator();
        }
        if (type == ExternalDocumentation.class) {
            return OASFactory.createExternalDocumentation();
        }
        if (type == XML.class) {
            return OASFactory.createXML();
        }
        throw new AssertionError("no sample value for " + type + ": extend sample()");
    }

    private static String json(Schema schema) {
        OpenAPI doc = OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("S", schema));
        return new JsonSerializer().serialize(doc);
    }
}
