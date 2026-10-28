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
package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaRegistry}: the named schemas generated from Java types land under
 * {@code components/schemas} (MP OpenAPI 4.2 §5.2.5), merged with the ones the application
 * declared itself.
 */
class SchemaRegistryTest {

    private static final String ITEM = "Item";
    private static final String ID = "id";

    private final SchemaRegistry registry = new SchemaRegistry();

    static final class First {
    }

    static final class Second {
    }

    static final class Third {
    }

    private static OpenAPI openApiWith(String name, Schema schema) {
        OpenAPI openAPI = OASFactory.createOpenAPI();
        openAPI.setComponents(OASFactory.createComponents().addSchema(name, schema));
        return openAPI;
    }

    private static Schema property(Schema.SchemaType type) {
        return OASFactory.createSchema().type(List.of(type));
    }

    @Test
    void reserveIsStableAndSuffixesCollisions() {
        String first = registry.reserve(First.class, ITEM);
        String again = registry.reserve(First.class, "Other");
        String second = registry.reserve(Second.class, ITEM);
        String third = registry.reserve(Third.class, ITEM);

        assertEquals(ITEM, first);
        assertEquals(ITEM, again);
        assertEquals("Item2", second);
        assertEquals("Item3", third);
        assertEquals(ITEM, registry.nameOf(First.class));
        assertNull(registry.nameOf(String.class));
    }

    @Test
    void reservedButUnpublishedNamesAreNotInTheSnapshot() {
        registry.reserve(First.class, ITEM);
        assertTrue(registry.snapshot().isEmpty());

        Schema body = property(Schema.SchemaType.OBJECT);
        registry.publish(ITEM, body);
        assertSame(body, registry.snapshot().get(ITEM));
    }

    @Test
    void nullArgumentsAreRejected() {
        Schema body = OASFactory.createSchema();
        assertThrows(NullPointerException.class, () -> registry.reserve(null, ITEM));
        assertThrows(NullPointerException.class, () -> registry.reserve(First.class, null));
        assertThrows(NullPointerException.class, () -> registry.publish(null, body));
        assertThrows(NullPointerException.class, () -> registry.publish(ITEM, null));
    }

    @Test
    void applyToAnEmptyRegistryLeavesTheDocumentAlone() {
        OpenAPI openAPI = OASFactory.createOpenAPI();
        registry.applyTo(openAPI);
        assertNull(openAPI.getComponents());
    }

    @Test
    void applyToCreatesComponentsWhenMissing() {
        registry.publish(registry.reserve(First.class, ITEM), property(Schema.SchemaType.OBJECT));
        OpenAPI openAPI = OASFactory.createOpenAPI();

        registry.applyTo(openAPI);

        assertEquals(List.of(Schema.SchemaType.OBJECT), openAPI.getComponents().getSchemas().get(ITEM).getType());
    }

    @Test
    void explicitSchemaWinsAttributeByAttributeAndPropertiesAreUnited() {
        Schema generated = property(Schema.SchemaType.OBJECT)
                .title("generated").description("from the class")
                .addProperty(ID, property(Schema.SchemaType.INTEGER))
                .addProperty("name", property(Schema.SchemaType.STRING))
                .required(List.of(ID));
        Schema explicit = OASFactory.createSchema()
                .description("from the document")
                .addProperty("name", property(Schema.SchemaType.BOOLEAN))
                .addProperty("extra", property(Schema.SchemaType.NUMBER))
                .required(List.of(ID, "extra"));
        registry.publish(registry.reserve(First.class, ITEM), generated);
        OpenAPI openAPI = openApiWith(ITEM, explicit);

        registry.applyTo(openAPI);

        Schema merged = openAPI.getComponents().getSchemas().get(ITEM);
        assertEquals("generated", merged.getTitle());
        assertEquals("from the document", merged.getDescription());
        assertEquals(List.of(Schema.SchemaType.OBJECT), merged.getType());
        assertEquals(List.of(ID, "name", "extra"), List.copyOf(merged.getProperties().keySet()));
        assertEquals(List.of(Schema.SchemaType.BOOLEAN), merged.getProperties().get("name").getType());
        assertEquals(List.of(ID, "extra"), merged.getRequired());
    }

    @Test
    void mergeCopiesEveryFacetOfTheExplicitSchema() {
        Schema explicit = OASFactory.createSchema()
                .ref("#/components/schemas/Base").format("f").defaultValue("d").example("e")
                .examples(List.of("e")).readOnly(true).writeOnly(false).deprecated(true)
                .discriminator(OASFactory.createDiscriminator().propertyName("kind"))
                .xml(OASFactory.createXML().name("x"))
                .dependentRequired(Map.of("a", List.of("b")))
                .dependentSchemas(Map.of("a", property(Schema.SchemaType.STRING)))
                .propertyNames(property(Schema.SchemaType.STRING))
                .contains(property(Schema.SchemaType.STRING))
                .ifSchema(property(Schema.SchemaType.STRING))
                .thenSchema(property(Schema.SchemaType.STRING))
                .elseSchema(property(Schema.SchemaType.STRING))
                .constValue("c")
                .externalDocs(OASFactory.createExternalDocumentation().url("https://example.com"))
                .items(property(Schema.SchemaType.STRING))
                .allOf(List.of(property(Schema.SchemaType.STRING)))
                .anyOf(List.of(property(Schema.SchemaType.STRING)))
                .oneOf(List.of(property(Schema.SchemaType.STRING)))
                .not(property(Schema.SchemaType.NULL))
                .maximum(BigDecimal.TEN).exclusiveMaximum(BigDecimal.TEN)
                .minimum(BigDecimal.ONE).exclusiveMinimum(BigDecimal.ONE)
                .maxLength(5).minLength(1).pattern("p").maxItems(3).minItems(1).uniqueItems(true)
                .maxProperties(4).minProperties(2).multipleOf(BigDecimal.ONE)
                .enumeration(List.of("v")).additionalPropertiesBoolean(Boolean.FALSE)
                .comment("note").addExtension("x-k", "v");
        registry.publish(registry.reserve(First.class, ITEM), property(Schema.SchemaType.OBJECT));
        OpenAPI openAPI = openApiWith(ITEM, explicit);

        registry.applyTo(openAPI);

        Schema merged = openAPI.getComponents().getSchemas().get(ITEM);
        assertEquals("#/components/schemas/Base", merged.getRef());
        assertEquals("kind", merged.getDiscriminator().getPropertyName());
        assertEquals("x", merged.getXml().getName());
        assertEquals("c", merged.getConstValue());
        assertEquals(BigDecimal.TEN, merged.getExclusiveMaximum());
        assertEquals(BigDecimal.ONE, merged.getExclusiveMinimum());
        assertEquals(3, merged.getMaxItems());
        assertEquals(2, merged.getMinProperties());
        assertEquals(List.of("v"), merged.getEnumeration());
        assertEquals(Boolean.FALSE, merged.getAdditionalPropertiesBoolean());
        assertEquals("note", merged.getComment());
        assertEquals("v", merged.getExtensions().get("x-k"));
        assertEquals(1, merged.getAllOf().size());
        assertEquals(List.of(Schema.SchemaType.NULL), merged.getNot().getType());
        assertEquals(Map.of("a", List.of("b")), merged.getDependentRequired());
    }

    @Test
    void additionalPropertiesSchemaIsCopiedWhenNotBoolean() {
        Schema generated = property(Schema.SchemaType.OBJECT)
                .additionalPropertiesSchema(property(Schema.SchemaType.STRING));
        registry.publish(registry.reserve(First.class, ITEM), generated);
        OpenAPI openAPI = openApiWith(ITEM, OASFactory.createSchema().title("t"));

        registry.applyTo(openAPI);

        Schema merged = openAPI.getComponents().getSchemas().get(ITEM);
        assertEquals(List.of(Schema.SchemaType.STRING), merged.getAdditionalPropertiesSchema().getType());
        assertNull(merged.getProperties());
        assertNull(merged.getRequired());
    }

    @Test
    void otherComponentSchemasAreKept() {
        registry.publish(registry.reserve(First.class, ITEM), property(Schema.SchemaType.OBJECT));
        OpenAPI openAPI = openApiWith("Unrelated", property(Schema.SchemaType.STRING));

        registry.applyTo(openAPI);

        assertEquals(2, openAPI.getComponents().getSchemas().size());
    }

    @Test
    void buildRefPointsToComponents() {
        assertEquals("#/components/schemas/Foo", registry.buildRef("Foo").getRef());
    }
}
