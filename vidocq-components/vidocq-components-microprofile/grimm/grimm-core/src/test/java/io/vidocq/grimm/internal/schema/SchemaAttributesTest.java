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

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.media.DependentRequired;
import org.eclipse.microprofile.openapi.annotations.media.DependentSchema;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.PatternProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaAttributes}: the adapter shared by {@code @Schema} and {@code @SchemaProperty}
 * (MP OpenAPI 4.2 §5.2.5).
 */
class SchemaAttributesTest {

    private static final String V = "1";

    @Schema(examples = {"a", "b"}, enumeration = {"x", "y"}, requiredProperties = "id")
    static final class Annotated {
    }

    @Schema(examples = {"a", "b"}, enumeration = {"x", "y"}, requiredProperties = "id")
    static final class SameContent {
    }

    @Schema(examples = {"a", "c"})
    static final class OtherContent {
    }

    @Schema
    static final class Empty {
    }

    @Schema(properties = @SchemaProperty(name = "p", type = SchemaType.STRING))
    static final class WithProperty {
    }

    /** One field per attribute, each set alone: every attribute on its own makes the annotation say something. */
    @SuppressWarnings("unused")
    static final class SingleAttributes {
        @Schema(type = SchemaType.STRING) Object a0;
        @Schema(format = V) Object a1;
        @Schema(title = V) Object a2;
        @Schema(description = V) Object a3;
        @Schema(comment = V) Object a4;
        @Schema(pattern = V) Object a5;
        @Schema(example = V) Object a6;
        @Schema(examples = V) Object a7;
        @Schema(defaultValue = V) Object a8;
        @Schema(minLength = 1) Object a9;
        @Schema(maxLength = 1) Object a10;
        @Schema(minProperties = 1) Object a11;
        @Schema(maxProperties = 1) Object a12;
        @Schema(minItems = 1) Object a13;
        @Schema(maxItems = 1) Object a14;
        @Schema(uniqueItems = true) Object a15;
        @Schema(minimum = V) Object a16;
        @Schema(maximum = V) Object a17;
        @Schema(exclusiveMinimum = true) Object a18;
        @Schema(exclusiveMaximum = true) Object a19;
        @Schema(multipleOf = 2) Object a20;
        @Schema(deprecated = true) Object a21;
        @Schema(readOnly = true) Object a22;
        @Schema(writeOnly = true) Object a23;
        @Schema(nullable = true) Object a24;
        @Schema(contentEncoding = V) Object a25;
        @Schema(contentMediaType = V) Object a26;
        @Schema(contentSchema = String.class) Object a27;
        @Schema(constValue = V) Object a28;
        @Schema(requiredProperties = V) Object a29;
        @Schema(enumeration = V) Object a30;
        @Schema(dependentRequired = @DependentRequired(name = V, requires = V)) Object a31;
        @Schema(dependentSchemas = @DependentSchema(name = V, schema = String.class)) Object a32;
        @Schema(additionalProperties = String.class) Object a33;
        @Schema(externalDocs = @ExternalDocumentation(url = V)) Object a34;
        @Schema(extensions = @Extension(name = "x-n", value = V)) Object a35;
        @Schema(allOf = String.class) Object a36;
        @Schema(anyOf = String.class) Object a37;
        @Schema(oneOf = String.class) Object a38;
        @Schema(not = String.class) Object a39;
        @Schema(discriminatorProperty = V) Object a40;
        @Schema(discriminatorMapping = @DiscriminatorMapping(value = V, schema = String.class)) Object a41;
        @Schema(ifSchema = String.class) Object a42;
        @Schema(thenSchema = String.class) Object a43;
        @Schema(elseSchema = String.class) Object a44;
        @Schema(contains = String.class) Object a45;
        @Schema(minContains = 1) Object a46;
        @Schema(maxContains = 1) Object a47;
        @Schema(prefixItems = String.class) Object a48;
        @Schema(patternProperties = @PatternProperty(regex = V, schema = String.class)) Object a49;
        @Schema(propertyNames = String.class) Object a50;
    }

    private static Schema schemaOf(Class<?> type) {
        return type.getAnnotation(Schema.class);
    }

    @Test
    void equalsComparesArrayComponentsByContent() {
        // Every annotation accessor returns a fresh array clone: reference equality would fail.
        SchemaAttributes first = SchemaAttributes.of(schemaOf(Annotated.class));
        SchemaAttributes second = SchemaAttributes.of(schemaOf(SameContent.class));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentArrayContentIsNotEqual() {
        SchemaAttributes first = SchemaAttributes.of(schemaOf(Annotated.class));
        SchemaAttributes other = SchemaAttributes.of(schemaOf(OtherContent.class));

        assertNotEquals(first, other);
        Object notAttributes = "not attributes";
        assertNotEquals(first, notAttributes);
    }

    @Test
    void toStringShowsArrayContent() {
        String text = SchemaAttributes.of(schemaOf(Annotated.class)).toString();

        assertTrue(text.startsWith("SchemaAttributes["), text);
        assertTrue(text.contains("[a, b]"), text);
        assertTrue(text.contains("[x, y]"), text);
    }

    @Test
    void hasContentIsFalseForDefaults() {
        assertFalse(SchemaAttributes.of(schemaOf(Empty.class)).hasContent());
        assertTrue(SchemaAttributes.of(schemaOf(Annotated.class)).hasContent());
    }

    @Test
    void everyAttributeAloneHasContent() {
        java.lang.reflect.Field[] fields = SingleAttributes.class.getDeclaredFields();
        assertEquals(51, fields.length);
        assertAll(java.util.Arrays.stream(fields)
                .map(f -> () -> assertTrue(SchemaAttributes.of(f.getAnnotation(Schema.class)).hasContent(), f.getName())));
    }

    @Test
    void schemaPropertyAdapterReadsTheSameAttributes() {
        SchemaProperty property = schemaOf(WithProperty.class).properties()[0];

        SchemaAttributes attributes = SchemaAttributes.of(property);

        assertEquals(SchemaType.STRING, attributes.type());
        assertTrue(attributes.hasContent());
    }
}
