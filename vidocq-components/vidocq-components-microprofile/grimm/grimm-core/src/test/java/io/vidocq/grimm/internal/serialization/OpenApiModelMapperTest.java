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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static-file mapping (spec §4.2 + §3.1 model). Since MP OpenAPI 4.2 (#698) every schema
 * property the model does not know is an extension, so the static reader must map each standard
 * keyword onto its typed property: a keyword left as a raw JSON/YAML value would show up in
 * {@link Schema#getExtensions()} and disappear when an {@code OASFilter} clears the extensions.
 */
class OpenApiModelMapperTest {

    private static final String MINIMAL_JSON = """
            {
              "openapi": "3.1.0",
              "info": {"title": "t", "version": "1"},
              "components": {
                "schemas": {
                  "S": {"type": "integer", "minimum": 0, "maxLength": 3, "not": {"type": "string"}, "x-vendor": "v"}
                }
              }
            }
            """;

    private static final String MINIMAL_YAML = """
            openapi: 3.1.0
            info:
              title: t
              version: "1"
            components:
              schemas:
                S:
                  type: integer
                  minimum: 0
                  maxLength: 3
                  not:
                    type: string
                  x-vendor: v
            """;

    @Test
    void jsonSchemaKeywordsLandOnTypedPropertiesNotExtensions() {
        assertMinimalSchema(schema(new JsonDeserializer().deserialize(MINIMAL_JSON), "S"));
    }

    @Test
    void yamlSchemaKeywordsLandOnTypedPropertiesNotExtensions() {
        assertMinimalSchema(schema(new YamlDeserializer().deserialize(MINIMAL_YAML), "S"));
    }

    @Test
    void clearingExtensionsKeepsStandardKeywords() {
        // What an OASFilter does when it calls setExtensions(Map.of()) on a schema.
        OpenAPI doc = new JsonDeserializer().deserialize(MINIMAL_JSON);
        schema(doc, "S").setExtensions(Map.of());

        String json = new JsonSerializer().serialize(doc);
        assertTrue(json.contains("\"minimum\":0"), json);
        assertTrue(json.contains("\"maxLength\":3"), json);
        assertTrue(json.contains("\"not\":{"), json);
        assertFalse(json.contains("x-vendor"), json);

        String yaml = new YamlSerializer().serialize(doc);
        assertTrue(yaml.contains("minimum: 0"), yaml);
        assertTrue(yaml.contains("maxLength: 3"), yaml);
        assertTrue(yaml.contains("not:"), yaml);
        assertFalse(yaml.contains("x-vendor"), yaml);
    }

    @Test
    void everyStandardSchemaKeywordLandsOnItsTypedProperty() {
        String json = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "t", "version": "1"},
                  "components": {
                    "schemas": {
                      "All": {
                        "type": "object",
                        "multipleOf": 0.5,
                        "maximum": 10,
                        "exclusiveMaximum": 11,
                        "minimum": 1,
                        "exclusiveMinimum": 0,
                        "maxLength": 20,
                        "minLength": 2,
                        "maxItems": 5,
                        "minItems": 1,
                        "uniqueItems": true,
                        "maxProperties": 9,
                        "minProperties": 3,
                        "maxContains": 4,
                        "minContains": 2,
                        "not": {"type": "string"},
                        "if": {"type": "integer"},
                        "then": {"minimum": 0},
                        "else": {"type": "string"},
                        "contains": {"type": "integer"},
                        "propertyNames": {"pattern": "^[a-z]+$"},
                        "unevaluatedItems": {"type": "string"},
                        "unevaluatedProperties": false,
                        "contentSchema": {"type": "string"},
                        "additionalProperties": {"type": "string"},
                        "allOf": [{"type": "object"}],
                        "anyOf": [{"type": "string"}],
                        "oneOf": [{"type": "integer"}],
                        "prefixItems": [{"type": "string"}],
                        "dependentSchemas": {"a": {"required": ["b"]}},
                        "patternProperties": {"^x": {"type": "string"}},
                        "dependentRequired": {"a": ["b", "c"]},
                        "discriminator": {"propertyName": "kind", "mapping": {"cat": "#/components/schemas/Cat"}},
                        "xml": {"name": "all", "wrapped": true, "x-xml": "x"},
                        "externalDocs": {"url": "https://example.com/docs", "description": "docs"},
                        "const": "fixed",
                        "example": "ex",
                        "contentEncoding": "base64",
                        "contentMediaType": "text/plain",
                        "x-vendor": "v"
                      }
                    }
                  }
                }
                """;
        Schema s = schema(new JsonDeserializer().deserialize(json), "All");

        assertEquals(Map.of("x-vendor", "v"), s.getExtensions());

        assertEquals(new BigDecimal("0.5"), s.getMultipleOf());
        assertEquals(new BigDecimal("10"), s.getMaximum());
        assertEquals(new BigDecimal("11"), s.getExclusiveMaximum());
        assertEquals(new BigDecimal("1"), s.getMinimum());
        assertEquals(new BigDecimal("0"), s.getExclusiveMinimum());
        assertEquals(20, s.getMaxLength());
        assertEquals(2, s.getMinLength());
        assertEquals(5, s.getMaxItems());
        assertEquals(1, s.getMinItems());
        assertEquals(Boolean.TRUE, s.getUniqueItems());
        assertEquals(9, s.getMaxProperties());
        assertEquals(3, s.getMinProperties());
        assertEquals(4, s.getMaxContains());
        assertEquals(2, s.getMinContains());

        assertEquals(List.of(Schema.SchemaType.STRING), s.getNot().getType());
        assertEquals(List.of(Schema.SchemaType.INTEGER), s.getIfSchema().getType());
        assertEquals(new BigDecimal("0"), s.getThenSchema().getMinimum());
        assertTrue(s.getThenSchema().getExtensions().isEmpty(), "nested schemas are mapped the same way");
        assertEquals(List.of(Schema.SchemaType.STRING), s.getElseSchema().getType());
        assertEquals(List.of(Schema.SchemaType.INTEGER), s.getContains().getType());
        assertEquals("^[a-z]+$", s.getPropertyNames().getPattern());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getUnevaluatedItems().getType());
        assertEquals(Boolean.FALSE, s.getUnevaluatedProperties().getBooleanSchema());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getContentSchema().getType());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getAdditionalPropertiesSchema().getType());

        assertEquals(List.of(Schema.SchemaType.OBJECT), assertInstanceOf(Schema.class, s.getAllOf().getFirst()).getType());
        assertEquals(List.of(Schema.SchemaType.STRING), assertInstanceOf(Schema.class, s.getAnyOf().getFirst()).getType());
        assertEquals(List.of(Schema.SchemaType.INTEGER), assertInstanceOf(Schema.class, s.getOneOf().getFirst()).getType());
        assertEquals(List.of(Schema.SchemaType.STRING), assertInstanceOf(Schema.class, s.getPrefixItems().getFirst()).getType());
        assertEquals(List.of("b"), assertInstanceOf(Schema.class, s.getDependentSchemas().get("a")).getRequired());
        assertEquals(List.of(Schema.SchemaType.STRING),
                assertInstanceOf(Schema.class, s.getPatternProperties().get("^x")).getType());
        assertEquals(Map.of("a", List.of("b", "c")), s.getDependentRequired());

        assertEquals("kind", s.getDiscriminator().getPropertyName());
        assertEquals(Map.of("cat", "#/components/schemas/Cat"), s.getDiscriminator().getMapping());
        assertEquals("all", s.getXml().getName());
        assertEquals(Boolean.TRUE, s.getXml().getWrapped());
        assertEquals(Map.of("x-xml", "x"), s.getXml().getExtensions());
        assertEquals("https://example.com/docs", s.getExternalDocs().getUrl());
        assertEquals("docs", s.getExternalDocs().getDescription());

        assertEquals("fixed", s.getConstValue());
        assertEquals("ex", s.getExample());
        assertEquals("base64", s.getContentEncoding());
        assertEquals("text/plain", s.getContentMediaType());
    }

    @Test
    void booleanSubschemasStayBooleanSchemas() {
        // JSON Schema 2020-12 §4.3.2: true / false are valid schemas wherever a schema is expected.
        String json = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "t", "version": "1"},
                  "components": {
                    "schemas": {
                      "B": {"not": false, "items": true, "properties": {"free": true}}
                    }
                  }
                }
                """;
        OpenAPI doc = new JsonDeserializer().deserialize(json);
        Schema s = schema(doc, "B");

        assertTrue(s.getExtensions().isEmpty(), s.getExtensions()::toString);
        assertEquals(Boolean.FALSE, s.getNot().getBooleanSchema());
        assertEquals(Boolean.TRUE, s.getItems().getBooleanSchema());
        assertEquals(Boolean.TRUE, s.getProperties().get("free").getBooleanSchema());

        String out = new JsonSerializer().serialize(doc);
        assertTrue(out.contains("\"not\":false"), out);
        assertTrue(out.contains("\"items\":true"), out);
        assertTrue(out.contains("\"free\":true"), out);
    }

    @Test
    void keywordWithNonStandardValueIsKeptAsWritten() {
        // Schema.set Javadoc: alternative dialects may use a standard name with another data type.
        String json = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "t", "version": "1"},
                  "components": {"schemas": {"C": {"minimum": "low", "not": "nothing"}}}
                }
                """;
        Schema s = schema(new JsonDeserializer().deserialize(json), "C");

        assertEquals("low", s.get("minimum"));
        assertEquals("nothing", s.get("not"));
    }

    @Test
    void staticHeaderExampleAndExamplesAreMapped() {
        // MP OpenAPI 4.2 (#697): Header example / examples, read from a static file.
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                components:
                  headers:
                    Rate:
                      description: rate limit
                      example: 42
                      examples:
                        low:
                          summary: a low limit
                          value: 1
                        high:
                          value: 1000
                """;
        Header header = new YamlDeserializer().deserialize(yaml).getComponents().getHeaders().get("Rate");

        assertNotNull(header);
        assertEquals(42L, header.getExample());
        assertEquals(Set.of("low", "high"), header.getExamples().keySet());
        assertEquals("a low limit", header.getExamples().get("low").getSummary());
        assertEquals(1L, header.getExamples().get("low").getValue());
        assertEquals(1000L, header.getExamples().get("high").getValue());
    }

    private static final String DISCRIMINATOR_JSON = """
            {
              "openapi": "3.1.0",
              "info": {"title": "t", "version": "1"},
              "components": {
                "schemas": {
                  "Pet": {
                    "oneOf": [{"$ref": "#/components/schemas/Cat"}],
                    "discriminator": {
                      "propertyName": "kind",
                      "mapping": {"cat": "#/components/schemas/Cat"},
                      "x-vendor": "v",
                      "x-flags": {"a": 1}
                    }
                  }
                }
              }
            }
            """;

    @Test
    void discriminatorExtensionsSurviveAJsonRoundTrip() {
        // MP OpenAPI 4.2: Discriminator is not Extensible, yet x- keys of a static file must not be lost.
        OpenAPI doc = new JsonDeserializer().deserialize(DISCRIMINATOR_JSON);
        var discriminator = schema(doc, "Pet").getDiscriminator();
        assertEquals("kind", discriminator.getPropertyName());
        assertEquals(Map.of("cat", "#/components/schemas/Cat"), discriminator.getMapping());

        String json = new JsonSerializer().serialize(doc);
        Object parsed = JsonDeserializer.parseRaw(json);
        Map<?, ?> out = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) parsed).get("components"))
                .get("schemas")).get("Pet");
        Map<?, ?> disc = (Map<?, ?>) out.get("discriminator");
        assertEquals("kind", disc.get("propertyName"));
        assertEquals("v", disc.get("x-vendor"));
        assertEquals(Map.of("a", 1L), disc.get("x-flags"));
    }

    @Test
    void discriminatorExtensionsSurviveAYamlRoundTrip() {
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                components:
                  schemas:
                    Pet:
                      discriminator:
                        propertyName: kind
                        x-vendor: v
                """;
        OpenAPI doc = new YamlDeserializer().deserialize(yaml);
        String written = new YamlSerializer().serialize(doc);
        OpenAPI again = new YamlDeserializer().deserialize(written);
        assertTrue(written.contains("x-vendor"), written);
        assertEquals("kind", schema(again, "Pet").getDiscriminator().getPropertyName());
        String json = new JsonSerializer().serialize(again);
        assertTrue(json.contains("\"x-vendor\""), json);
    }

    @Test
    void staticRefsAreKeptVerbatimInEverySchemaPosition() {
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                paths:
                  /p:
                    $ref: Shared.yaml
                components:
                  headers:
                    H:
                      $ref: Rate
                  schemas:
                    S:
                      $ref: Pet.yaml
                      items:
                        $ref: Item
                      properties:
                        a:
                          $ref: Other.json
                      not:
                        $ref: Not
                      allOf:
                        - $ref: Base
                      additionalProperties:
                        $ref: Extra
                """;
        OpenAPI doc = new YamlDeserializer().deserialize(yaml);
        Schema s = schema(doc, "S");
        assertEquals("Pet.yaml", s.getRef());
        assertEquals("Item", s.getItems().getRef());
        assertEquals("Other.json", s.getProperties().get("a").getRef());
        assertEquals("Not", s.getNot().getRef());
        assertEquals("Base", s.getAllOf().get(0).getRef());
        assertEquals("Extra", s.getAdditionalPropertiesSchema().getRef());
        assertEquals("Rate", doc.getComponents().getHeaders().get("H").getRef());
        assertEquals("Shared.yaml", doc.getPaths().getPathItem("/p").getRef());
        // The programmatic API still expands short names (MP OpenAPI Reference#setRef).
        Schema programmatic = org.eclipse.microprofile.openapi.OASFactory.createSchema().ref("Pet");
        assertEquals("#/components/schemas/Pet", programmatic.getRef());
    }

    @Test
    void yamlReadsCoreSchemaNumbers() {
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                components:
                  schemas:
                    S:
                      default: 1e3
                      examples:
                        - .5
                        - +1
                        - -.5e-2
                        - 1.5E+2
                        - 0x1F
                        - 0o17
                        - 12
                        - -3
                        - 2.50
                """;
        Schema s = schema(new YamlDeserializer().deserialize(yaml), "S");
        assertEquals(1000.0, ((Number) s.getDefaultValue()).doubleValue());
        List<?> ex = s.getExamples();
        assertEquals(0.5, ((Number) ex.get(0)).doubleValue());
        assertEquals(1L, ((Number) ex.get(1)).longValue());
        assertEquals(-0.005, ((Number) ex.get(2)).doubleValue());
        assertEquals(150.0, ((Number) ex.get(3)).doubleValue());
        assertEquals(31L, ((Number) ex.get(4)).longValue());
        assertEquals(15L, ((Number) ex.get(5)).longValue());
        assertEquals(12L, ex.get(6));
        assertEquals(-3L, ex.get(7));
        assertEquals(2.5, ((Number) ex.get(8)).doubleValue());
    }

    @Test
    void yamlKeepsNumberLookalikesAsStrings() {
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                components:
                  schemas:
                    S:
                      examples:
                        - 1.2.3
                        - e5
                        - "1e3"
                        - 1e
                        - +
                        - .e1
                        - 0x
                        - 1_000
                        - v1e3
                """;
        List<?> ex = schema(new YamlDeserializer().deserialize(yaml), "S").getExamples();
        assertEquals(List.of("1.2.3", "e5", "1e3", "1e", "+", ".e1", "0x", "1_000", "v1e3"), ex);
    }

    @Test
    void staticHeaderStyleExplodeAndContentAreMapped() {
        String yaml = """
                openapi: 3.1.0
                info:
                  title: t
                  version: "1"
                components:
                  headers:
                    Rate:
                      style: simple
                      explode: true
                      content:
                        application/json:
                          schema:
                            type: string
                """;
        Header header = new YamlDeserializer().deserialize(yaml).getComponents().getHeaders().get("Rate");
        assertEquals(Header.Style.SIMPLE, header.getStyle());
        assertEquals(Boolean.TRUE, header.getExplode());
        assertEquals(List.of(Schema.SchemaType.STRING),
                header.getContent().getMediaType("application/json").getSchema().getType());
        assertFalse(header.hasExtension("style"));
    }

    private static void assertMinimalSchema(Schema s) {
        assertEquals(new BigDecimal("0"), s.getMinimum());
        assertEquals(3, s.getMaxLength());
        assertNotNull(s.getNot());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getNot().getType());
        assertFalse(s.hasExtension("minimum"));
        assertFalse(s.hasExtension("maxLength"));
        assertFalse(s.hasExtension("not"));
        assertEquals(Map.of("x-vendor", "v"), s.getExtensions());
    }

    private static Schema schema(OpenAPI doc, String name) {
        Schema schema = doc.getComponents().getSchemas().get(name);
        assertNotNull(schema, "schema " + name);
        return schema;
    }
}
