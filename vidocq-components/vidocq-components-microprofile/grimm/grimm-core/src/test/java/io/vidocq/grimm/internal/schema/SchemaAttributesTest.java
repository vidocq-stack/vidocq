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

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaAttributes}: the adapter shared by {@code @Schema} and {@code @SchemaProperty}
 * (MP OpenAPI 4.2 §5.2.5).
 */
class SchemaAttributesTest {

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
    void schemaPropertyAdapterReadsTheSameAttributes() {
        SchemaProperty property = schemaOf(WithProperty.class).properties()[0];

        SchemaAttributes attributes = SchemaAttributes.of(property);

        assertEquals(SchemaType.STRING, attributes.type());
        assertTrue(attributes.hasContent());
    }
}
