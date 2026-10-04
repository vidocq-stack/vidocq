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

import io.vidocq.grimm.internal.serialization.JsonSerializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261004-07: {@code @SchemaProperty} and {@code @Schema} are mapped by one shared mapping,
 * and {@code examples()} is mapped on both.
 */
class SchemaPropertyMappingTest {

    private final SchemaRegistry registry = new SchemaRegistry();
    private final SchemaGenerator generator = new SchemaGenerator(registry);

    static final class Cat {
        String name;
    }

    static final class Dog {
        String bark;
    }

    @Schema(properties = {
            @SchemaProperty(name = "age", minimum = "0", maximum = "150", exclusiveMaximum = true,
                    multipleOf = 1, deprecated = true, readOnly = true, defaultValue = "1"),
            @SchemaProperty(name = "code", minLength = 2, maxLength = 8, pattern = "[A-Z]+",
                    enumeration = {"AB", "CD"}, nullable = true, writeOnly = true),
            @SchemaProperty(name = "tags", minItems = 1, maxItems = 3, uniqueItems = true),
            @SchemaProperty(name = "meta", minProperties = 1, maxProperties = 4,
                    requiredProperties = {"a", "b"}),
            @SchemaProperty(name = "pet", oneOf = {Cat.class, Dog.class}, not = Dog.class,
                    discriminatorProperty = "kind",
                    discriminatorMapping = @DiscriminatorMapping(value = "cat", schema = Cat.class)),
            @SchemaProperty(name = "both", allOf = Cat.class, anyOf = {Cat.class, Dog.class},
                    contentEncoding = "base64", contentMediaType = "image/png"),
            @SchemaProperty(name = "lone", examples = {"\"a\"", "\"b\""})})
    static final class DeclaredByProperties {
        int age;
        String code;
        List<String> tags;
        java.util.Map<String, Object> meta;
        Object pet;
        Object both;
        String lone;
    }

    @Test
    void schemaProperty_mapsNumericAndFlagConstraints() {
        generator.generate(DeclaredByProperties.class);
        var age = registry.snapshot().get("DeclaredByProperties").getProperties().get("age");

        assertAll(
                () -> assertEquals(new BigDecimal("0"), age.getMinimum()),
                () -> assertEquals(new BigDecimal("150"), age.getExclusiveMaximum()),
                () -> assertEquals(BigDecimal.valueOf(1d), age.getMultipleOf()),
                () -> assertEquals(Boolean.TRUE, age.getDeprecated()),
                () -> assertEquals(Boolean.TRUE, age.getReadOnly()),
                () -> assertEquals("1", age.getDefaultValue()));
    }

    @Test
    void schemaProperty_mapsStringAndEnumerationConstraints() {
        generator.generate(DeclaredByProperties.class);
        var code = registry.snapshot().get("DeclaredByProperties").getProperties().get("code");

        assertAll(
                () -> assertEquals(Integer.valueOf(2), code.getMinLength()),
                () -> assertEquals(Integer.valueOf(8), code.getMaxLength()),
                () -> assertEquals("[A-Z]+", code.getPattern()),
                () -> assertEquals(List.of("AB", "CD"), code.getEnumeration()),
                () -> assertTrue(code.getType().contains(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.NULL)),
                () -> assertEquals(Boolean.TRUE, code.getWriteOnly()));
    }

    @Test
    void schemaProperty_mapsArrayAndObjectConstraints() {
        generator.generate(DeclaredByProperties.class);
        var properties = registry.snapshot().get("DeclaredByProperties").getProperties();
        var tags = properties.get("tags");
        var meta = properties.get("meta");

        assertAll(
                () -> assertEquals(Integer.valueOf(1), tags.getMinItems()),
                () -> assertEquals(Integer.valueOf(3), tags.getMaxItems()),
                () -> assertEquals(Boolean.TRUE, tags.getUniqueItems()),
                () -> assertEquals(Integer.valueOf(1), meta.getMinProperties()),
                () -> assertEquals(Integer.valueOf(4), meta.getMaxProperties()),
                () -> assertEquals(List.of("a", "b"), meta.getRequired()));
    }

    @Test
    void schemaProperty_mapsCompositionDiscriminatorAndContent() {
        generator.generate(DeclaredByProperties.class);
        var properties = registry.snapshot().get("DeclaredByProperties").getProperties();
        var pet = properties.get("pet");
        var both = properties.get("both");

        assertAll(
                () -> assertEquals(2, pet.getOneOf().size()),
                () -> assertNotNull(pet.getNot()),
                () -> assertEquals("kind", pet.getDiscriminator().getPropertyName()),
                () -> assertEquals("#/components/schemas/Cat", pet.getDiscriminator().getMapping().get("cat")),
                () -> assertEquals(1, both.getAllOf().size()),
                () -> assertEquals(2, both.getAnyOf().size()),
                () -> assertEquals("base64", both.getContentEncoding()),
                () -> assertEquals("image/png", both.getContentMediaType()));
    }

    @Test
    void schemaProperty_mapsExamples() {
        generator.generate(DeclaredByProperties.class);
        var lone = registry.snapshot().get("DeclaredByProperties").getProperties().get("lone");

        assertEquals(List.of("\"a\"", "\"b\""), lone.getExamples());
    }

    static final class ExamplesOnFields {
        @Schema(examples = {"x", "y"})
        String many;

        @Schema(example = "solo")
        String one;
    }

    @Test
    void schema_mapsExamples() {
        generator.generate(ExamplesOnFields.class);
        var properties = registry.snapshot().get("ExamplesOnFields").getProperties();

        assertAll(
                () -> assertEquals(List.of("x", "y"), properties.get("many").getExamples()),
                () -> assertEquals("solo", properties.get("one").getExample()),
                () -> assertEquals(List.of("solo"), properties.get("one").getExamples()));
    }

    /** The same attribute values on a field {@code @Schema} and on a {@code @SchemaProperty} give the same schema. */
    static final class OnField {
        @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "9", exclusiveMinimum = true, multipleOf = 2,
                title = "T", description = "D", format = "int32", deprecated = true, readOnly = true,
                defaultValue = "3", examples = {"3", "5"}, constValue = "3", comment = "c",
                oneOf = {Cat.class, Dog.class}, not = Dog.class, discriminatorProperty = "kind")
        Object value;
    }

    @Schema(properties = @SchemaProperty(name = "value", type = SchemaType.INTEGER, minimum = "1", maximum = "9",
            exclusiveMinimum = true, multipleOf = 2, title = "T", description = "D", format = "int32",
            deprecated = true, readOnly = true, defaultValue = "3", examples = {"3", "5"}, constValue = "3",
            comment = "c", oneOf = {Cat.class, Dog.class}, not = Dog.class, discriminatorProperty = "kind"))
    static final class OnProperty {
        Object value;
    }

    @Test
    void schemaAndSchemaProperty_shareOneMapping() {
        generator.generate(OnField.class);
        generator.generate(OnProperty.class);
        var fromField = registry.snapshot().get("OnField").getProperties().get("value");
        var fromProperty = registry.snapshot().get("OnProperty").getProperties().get("value");

        assertEquals(json(fromField), json(fromProperty));
        assertTrue(json(fromField).contains("\"minimum\""), "the comparison is not vacuous");
    }

    private static String json(org.eclipse.microprofile.openapi.models.media.Schema schema) {
        var document = OASFactory.createOpenAPI().components(
                OASFactory.createComponents().addSchema("probe", schema));
        return new JsonSerializer().serialize(document);
    }

    @Schema(properties = @SchemaProperty(name = "name", description = "from the class", maxLength = 5))
    static final class DeclaredOverField {
        @Schema(description = "from the field", minLength = 1, title = "field title")
        String name;
    }

    /**
     * A property declared both by the field's own {@code @Schema} and by the enclosing
     * {@code @Schema(properties = ...)}: the {@code @SchemaProperty} attributes that are set win,
     * the field's other attributes are kept (the class-level annotation is applied last).
     */
    @Test
    void schemaProperty_overridesFieldSchemaAttributeByAttribute() {
        generator.generate(DeclaredOverField.class);
        var name = registry.snapshot().get("DeclaredOverField").getProperties().get("name");

        assertAll(
                () -> assertEquals("from the class", name.getDescription()),
                () -> assertEquals(Integer.valueOf(5), name.getMaxLength()),
                () -> assertEquals(Integer.valueOf(1), name.getMinLength()),
                () -> assertEquals("field title", name.getTitle()));
    }

    @Schema(properties = {
            @SchemaProperty(name = "pet", discriminatorProperty = "kind",
                    discriminatorMapping = {@DiscriminatorMapping(value = "none"),
                            @DiscriminatorMapping(value = "cat", schema = Cat.class)}),
            @SchemaProperty(name = "dyn", patternProperties = {
                    @org.eclipse.microprofile.openapi.annotations.media.PatternProperty(regex = "^x-", schema = Void.class),
                    @org.eclipse.microprofile.openapi.annotations.media.PatternProperty(regex = "^y-", schema = Cat.class)})})
    static final class DefaultedSchemas {
        Object pet;
        Object dyn;
    }

    @Test
    void discriminatorMappingAndPatternProperty_withoutSchemaAreSkipped() {
        generator.generate(DefaultedSchemas.class);
        var properties = registry.snapshot().get("DefaultedSchemas").getProperties();

        assertAll(
                () -> assertEquals(java.util.Set.of("cat"),
                        properties.get("pet").getDiscriminator().getMapping().keySet()),
                () -> assertEquals(java.util.Set.of("^y-"), properties.get("dyn").getPatternProperties().keySet()));
    }

    static final class Node {
        String label;
        @Schema(allOf = Node.class, not = Node.class)
        Object self;
    }

    @Test
    void compositionOnSelfReferencingClass_resolvesToRefWithoutRecursion() {
        generator.generate(Node.class);
        var self = registry.snapshot().get("Node").getProperties().get("self");

        assertAll(
                () -> assertEquals("#/components/schemas/Node", self.getAllOf().get(0).getRef()),
                () -> assertEquals("#/components/schemas/Node", self.getNot().getRef()));
    }
}
