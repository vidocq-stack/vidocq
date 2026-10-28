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

import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static-file mapping of JSON Schema 2020-12 keywords onto the typed {@link Schema} model
 * (MP OpenAPI 4.2 §4.2 and the {@code Schema} model Javadoc).
 */
class StaticSchemaMappingTest {

    private static final String MINIMUM = "minimum";
    private static final String MAX_LENGTH = "maxLength";
    private static final String STRING_SCHEMA = "{\"type\": \"string\"}";

    private static Schema schema(String json) {
        return OpenApiModelMapper.toSchema(JsonDeserializer.parseRaw(json));
    }

    @Test
    void applicatorKeywordsBecomeSchemas() {
        Schema schema = schema("""
                {"if": %1$s, "then": %1$s, "else": %1$s, "contains": %1$s, "propertyNames": %1$s,
                 "unevaluatedItems": false, "unevaluatedProperties": %1$s, "contentSchema": %1$s,
                 "allOf": [%1$s, 1], "anyOf": [%1$s], "oneOf": [%1$s], "prefixItems": [%1$s, true],
                 "dependentSchemas": {"a": %1$s, "b": 1}, "patternProperties": {"^x": %1$s},
                 "items": %1$s, "properties": {"p": %1$s}}
                """.formatted(STRING_SCHEMA));

        assertNotNull(schema.getIfSchema());
        assertNotNull(schema.getThenSchema());
        assertNotNull(schema.getElseSchema());
        assertNotNull(schema.getContains());
        assertNotNull(schema.getPropertyNames());
        assertEquals(Boolean.FALSE, schema.getUnevaluatedItems().getBooleanSchema());
        assertNotNull(schema.getUnevaluatedProperties());
        assertNotNull(schema.getContentSchema());
        assertEquals(1, schema.getAllOf().size());
        assertEquals(1, schema.getAnyOf().size());
        assertEquals(1, schema.getOneOf().size());
        assertEquals(2, schema.getPrefixItems().size());
        assertEquals(List.of("a"), List.copyOf(schema.getDependentSchemas().keySet()));
        assertNotNull(schema.getPatternProperties().get("^x"));
        assertNotNull(schema.getItems());
        assertNotNull(schema.getProperties().get("p"));
        assertTrue(schema.getExtensions().isEmpty());
    }

    @Test
    void validationAndAnnotationKeywords() {
        Schema schema = schema("""
                {"type": ["string", "null"], "format": "email", "description": "d", "title": "t",
                 "multipleOf": 2, "maximum": 10, "exclusiveMaximum": 11, "minimum": 1, "exclusiveMinimum": 0,
                 "maxLength": 5, "minLength": 1, "maxItems": 3, "minItems": 1, "maxProperties": 4,
                 "minProperties": 2, "maxContains": 2, "minContains": 1, "uniqueItems": true,
                 "required": ["a"], "dependentRequired": {"a": ["b"], "c": 1},
                 "contentEncoding": "base64", "contentMediaType": "image/png", "const": "c",
                 "default": "x", "pattern": "^a", "deprecated": true, "readOnly": false, "writeOnly": true,
                 "enum": ["x", "y"], "examples": ["x"], "example": "x", "$schema": "https://dialect",
                 "$comment": "note", "$ref": "#/components/schemas/Other", "x-vendor": "v", "custom": {"k": 1}}
                """);

        assertEquals(List.of(Schema.SchemaType.STRING, Schema.SchemaType.NULL), schema.getType());
        assertEquals("email", schema.getFormat());
        assertEquals(new BigDecimal("2"), schema.getMultipleOf());
        assertEquals(new BigDecimal("11"), schema.getExclusiveMaximum());
        assertEquals(new BigDecimal("0"), schema.getExclusiveMinimum());
        assertEquals(5, schema.getMaxLength());
        assertEquals(4, schema.getMaxProperties());
        assertEquals(2, schema.getMinProperties());
        assertEquals(2, schema.getMaxContains());
        assertEquals(1, schema.getMinContains());
        assertEquals(Boolean.TRUE, schema.getUniqueItems());
        assertEquals(Map.of("a", List.of("b")), schema.getDependentRequired());
        assertEquals("base64", schema.getContentEncoding());
        assertEquals("image/png", schema.getContentMediaType());
        assertEquals("c", schema.getConstValue());
        assertEquals("^a", schema.getPattern());
        assertEquals(Boolean.FALSE, schema.getReadOnly());
        assertEquals(Boolean.TRUE, schema.getWriteOnly());
        assertEquals(List.of("x"), schema.getExamples());
        assertEquals("https://dialect", schema.getSchemaDialect());
        assertEquals("note", schema.getComment());
        assertEquals("#/components/schemas/Other", schema.getRef());
        assertEquals("v", schema.get("x-vendor"));
        assertEquals(Map.of("k", 1L), schema.get("custom"));
    }

    @Test
    void everyJsonSchemaTypeName() {
        Schema schema = schema("""
                {"type": ["array", "boolean", "integer", "null", "number", "object", "string"]}
                """);

        assertEquals(List.of(Schema.SchemaType.ARRAY, Schema.SchemaType.BOOLEAN, Schema.SchemaType.INTEGER,
                Schema.SchemaType.NULL, Schema.SchemaType.NUMBER, Schema.SchemaType.OBJECT,
                Schema.SchemaType.STRING), schema.getType());
    }

    @Test
    void valuesOfAnotherJsonTypeAreKeptAsWritten() {
        Schema schema = schema("""
                {"type": ["string", "date"], "minimum": "low", "maxLength": 1.5, "minItems": "x",
                 "uniqueItems": "yes", "not": 1, "allOf": 1, "dependentSchemas": 1, "dependentRequired": 1,
                 "discriminator": 1, "xml": 1, "externalDocs": 1, "contentEncoding": 1, "contentMediaType": 2,
                 "enum": "x", "examples": "x", "additionalProperties": "x"}
                """);

        assertEquals(List.of("string", "date"), schema.get("type"));
        assertEquals("low", schema.get(MINIMUM));
        assertEquals(1.5, schema.get(MAX_LENGTH));
        assertEquals("x", schema.get("minItems"));
        assertEquals("yes", schema.get("uniqueItems"));
        assertEquals(1L, schema.get("not"));
        assertEquals(1L, schema.get("allOf"));
        assertEquals(1L, schema.get("dependentSchemas"));
        assertEquals(1L, schema.get("dependentRequired"));
        assertEquals(1L, schema.get("discriminator"));
        assertEquals(1L, schema.get("xml"));
        assertEquals(1L, schema.get("externalDocs"));
        assertEquals(1L, schema.get("contentEncoding"));
        assertEquals(2L, schema.get("contentMediaType"));
        assertNull(schema.getEnumeration());
        assertNull(schema.getExamples());
        assertNull(schema.getAdditionalPropertiesSchema());
    }

    @Test
    void unknownTypeNameAndNonStringTypeAreKept() {
        assertEquals("date", schema("{\"type\": \"date\"}").get("type"));
        assertEquals(1L, schema("{\"type\": 1}").get("type"));
        assertEquals(Arrays.asList("string", null), schema("{\"type\": [\"string\", null]}").get("type"));
    }

    @Test
    void numbersFromJavaValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("maximum", new BigDecimal("1.25"));
        raw.put(MINIMUM, Double.NaN);
        raw.put(MAX_LENGTH, 3_000_000_000L);
        raw.put("minLength", null);

        Schema schema = OpenApiModelMapper.toSchema(raw);

        assertEquals(new BigDecimal("1.25"), schema.getMaximum());
        assertEquals(Double.NaN, schema.get(MINIMUM));
        assertEquals(3_000_000_000L, schema.get(MAX_LENGTH));
        assertNull(schema.getMinLength());
    }

    @Test
    void additionalPropertiesNullableAndBooleans() {
        assertEquals(Boolean.FALSE,
                schema("{\"additionalProperties\": false}").getAdditionalPropertiesSchema().getBooleanSchema());
        assertNotNull(schema("{\"additionalProperties\": " + STRING_SCHEMA + "}").getAdditionalPropertiesSchema());
        assertEquals(List.of(Schema.SchemaType.STRING, Schema.SchemaType.NULL),
                schema("{\"type\": \"string\", \"nullable\": true}").getType());
        assertEquals(List.of(Schema.SchemaType.STRING), schema("{\"type\": \"string\", \"nullable\": false}").getType());
        assertEquals(Boolean.TRUE, schema("{\"deprecated\": \"true\"}").getDeprecated());
        assertNull(schema("{\"readOnly\": null}").getReadOnly());
        assertEquals(Boolean.TRUE, OpenApiModelMapper.toSchema(true).getBooleanSchema());
        assertNull(OpenApiModelMapper.toSchema("not a schema"));
    }

    @Test
    void discriminatorAndXml() {
        Schema schema = schema("""
                {"discriminator": {"propertyName": "kind", "mapping": {"cat": "#/components/schemas/Cat"},
                                   "x-d": 1, "other": 1},
                 "xml": {"name": "pet", "namespace": "https://ns", "prefix": "p", "attribute": true, "wrapped": false,
                         "x-xml": 1, "other": 1},
                 "externalDocs": {"url": "https://schema-docs"}}
                """);
        XML xml = schema.getXml();

        assertEquals("kind", schema.getDiscriminator().getPropertyName());
        assertEquals(Map.of("cat", "#/components/schemas/Cat"), schema.getDiscriminator().getMapping());
        assertEquals("pet", xml.getName());
        assertEquals("https://ns", xml.getNamespace());
        assertEquals("p", xml.getPrefix());
        assertEquals(Boolean.TRUE, xml.getAttribute());
        assertEquals(Boolean.FALSE, xml.getWrapped());
        assertEquals(Map.of("x-xml", 1L), xml.getExtensions());
        assertEquals("https://schema-docs", schema.getExternalDocs().getUrl());
    }

    @Test
    void discriminatorWithoutMappingObject() {
        Schema schema = schema("{\"discriminator\": {\"propertyName\": \"kind\", \"mapping\": 1}}");

        assertNull(schema.getDiscriminator().getMapping());
    }

    @Test
    void schemasMapSkipsNonSchemas() {
        Map<Object, Object> properties = new HashMap<>();
        properties.put("ok", Map.of());
        properties.put("bad", 1);

        Schema schema = OpenApiModelMapper.toSchema(Map.of("properties", properties));

        assertEquals(List.of("ok"), List.copyOf(schema.getProperties().keySet()));
        assertTrue(schema.getProperties().get("ok").getExtensions() == null
                || schema.getProperties().get("ok").getExtensions().isEmpty());
    }
}
