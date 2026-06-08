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
package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema.SchemaType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SchemaGenerator} — spec §3.10 ({@code @Schema}) and §3.11
 * (Schema generation from Java types).
 */
class SchemaGeneratorTest {

    private final SchemaRegistry registry = new SchemaRegistry();
    private final SchemaGenerator generator = new SchemaGenerator(registry);

    // ------------- Primitives & scalars (§3.11) -------------

    @Test
    void generate_mapsPrimitiveIntegers() {
        var s = generator.generate(int.class);
        assertEquals(SchemaType.INTEGER, s.getType().get(0));
        assertEquals("int32", s.getFormat());
    }

    @Test
    void generate_mapsLongAsInt64() {
        var s = generator.generate(Long.class);
        assertEquals(SchemaType.INTEGER, s.getType().get(0));
        assertEquals("int64", s.getFormat());
    }

    @Test
    void generate_mapsDoubleAsNumberDouble() {
        var s = generator.generate(double.class);
        assertEquals(SchemaType.NUMBER, s.getType().get(0));
        assertEquals("double", s.getFormat());
    }

    @Test
    void generate_mapsBooleanAndString() {
        var b = generator.generate(boolean.class);
        assertEquals(SchemaType.BOOLEAN, b.getType().get(0));
        var s = generator.generate(String.class);
        assertEquals(SchemaType.STRING, s.getType().get(0));
        assertNull(s.getFormat());
    }

    @Test
    void generate_mapsUuidLocalDateInstantBigDecimal() {
        assertEquals("uuid", generator.generate(UUID.class).getFormat());
        assertEquals("date", generator.generate(LocalDate.class).getFormat());
        assertEquals("date-time", generator.generate(Instant.class).getFormat());
        var bd = generator.generate(BigDecimal.class);
        assertEquals(SchemaType.NUMBER, bd.getType().get(0));
        assertNull(bd.getFormat());
        var bi = generator.generate(BigInteger.class);
        assertEquals(SchemaType.INTEGER, bi.getType().get(0));
        assertEquals("int64", bi.getFormat());
    }

    // ------------- Collections / arrays / maps -------------

    @Test
    void generate_mapsArrayToArrayOfItems() throws Exception {
        Type t = Holder.class.getDeclaredField("ints").getGenericType();
        var s = generator.generate(t);
        assertEquals(SchemaType.ARRAY, s.getType().get(0));
        assertEquals(SchemaType.INTEGER, s.getItems().getType().get(0));
    }

    @Test
    void generate_mapsListOfStringToArrayOfStrings() throws Exception {
        Type t = Holder.class.getDeclaredField("names").getGenericType();
        var s = generator.generate(t);
        assertEquals(SchemaType.ARRAY, s.getType().get(0));
        assertEquals(SchemaType.STRING, s.getItems().getType().get(0));
    }

    @Test
    void generate_mapsSetOfLongToArrayOfInt64() throws Exception {
        Type t = Holder.class.getDeclaredField("ids").getGenericType();
        var s = generator.generate(t);
        assertEquals(SchemaType.ARRAY, s.getType().get(0));
        assertEquals(SchemaType.INTEGER, s.getItems().getType().get(0));
        assertEquals("int64", s.getItems().getFormat());
    }

    @Test
    void generate_mapsMapToObjectWithAdditionalProperties() throws Exception {
        Type t = Holder.class.getDeclaredField("labels").getGenericType();
        var s = generator.generate(t);
        assertEquals(SchemaType.OBJECT, s.getType().get(0));
        assertNotNull(s.getAdditionalPropertiesSchema());
        assertEquals(SchemaType.STRING,
                s.getAdditionalPropertiesSchema().getType().get(0));
    }

    // ------------- Enums (§3.11) -------------

    @Test
    void generate_mapsEnumAsRefStringWithEnumerationInComponents() {
        var ref = generator.generate(Status.class);
        assertEquals("#/components/schemas/Status", ref.getRef());
        var body = registry.snapshot().get("Status");
        assertNotNull(body);
        assertEquals(SchemaType.STRING, body.getType().get(0));
        assertEquals(List.of("OPEN", "CLOSED"),
                body.getEnumeration());
    }

    // ------------- POJOs (§3.11) -------------

    @Test
    void generate_mapsPojoAsRefAndRegistersComponentSchema() {
        var ref = generator.generate(Pet.class);
        assertEquals("#/components/schemas/Pet", ref.getRef());
        var body = registry.snapshot().get("Pet");
        assertNotNull(body);
        assertEquals(SchemaType.OBJECT, body.getType().get(0));
        assertTrue(body.getProperties().containsKey("name"));
        assertTrue(body.getProperties().containsKey("age"));
    }

    @Test
    void generate_handlesRecursiveTypesViaReservation() {
        var ref = generator.generate(Node.class);
        assertEquals("#/components/schemas/Node", ref.getRef());
        var body = registry.snapshot().get("Node");
        assertNotNull(body);
        // 'next' must be a $ref to Node, not an inlined copy.
        var nextProp = body.getProperties().get("next");
        assertEquals("#/components/schemas/Node", nextProp.getRef());
    }

    @Test
    void generate_appliesClassLevelSchemaName() {
        var ref = generator.generate(WithCustomName.class);
        assertEquals("#/components/schemas/MyName", ref.getRef());
        assertTrue(registry.snapshot().containsKey("MyName"));
    }

    @Test
    void generate_skipsHiddenFields() {
        generator.generate(SecretBox.class);
        var body = registry.snapshot().get("SecretBox");
        assertTrue(body.getProperties().containsKey("publicValue"));
        assertFalse(body.getProperties().containsKey("secret"));
    }

    @Test
    void generate_appliesFieldLevelSchemaOverrides() {
        generator.generate(Constrained.class);
        var body = registry.snapshot().get("Constrained");
        var name = body.getProperties().get("name");
        assertEquals("the name", name.getDescription());
        assertEquals(Integer.valueOf(3), name.getMinLength());
        assertEquals(Integer.valueOf(20), name.getMaxLength());
        assertEquals("Alice", name.getExample());
        assertTrue(body.getRequired().contains("name"));
    }

    @Test
    void generate_mergesGetterSchemaOverridesWithFieldProperty() {
        generator.generate(GetterAnnotated.class);
        var body = registry.snapshot().get("GetterAnnotated");
        assertNotNull(body.getRequired());
        assertTrue(body.getRequired().contains("name"));
        assertEquals("doggie", body.getProperties().get("name").getExample());
    }

    @Test
    void generate_mapsExclusiveBoundsFromSchemaAnnotation() {
        generator.generate(ExclusiveBounds.class);
        var body = registry.snapshot().get("ExclusiveBounds");
        var id = body.getProperties().get("id");
        assertEquals(new BigDecimal("101"), id.getExclusiveMaximum());
        assertEquals(new BigDecimal("9"), id.getExclusiveMinimum());
    }

    @Test
    void generate_mapsSchemaExternalDocs() {
        generator.generate(WithExternalDocs.class);
        var body = registry.snapshot().get("WithExternalDocs");
        var code = body.getProperties().get("code");
        assertNotNull(code.getExternalDocs());
        assertEquals("Pet Types", code.getExternalDocs().getDescription());
        assertEquals("http://example.com/pettypes", code.getExternalDocs().getUrl());
    }

    @Test
    void generate_mapsSchemaAdditionalPropertiesAndExtensions() {
        generator.generate(AdditionalPropertiesFixture.class);
        var body = registry.snapshot().get("AdditionalPropertiesFixture");

        var metadata = body.getProperties().get("metadata");
        assertEquals(Boolean.TRUE, metadata.getAdditionalPropertiesBoolean());

        var strict = body.getProperties().get("strict");
        assertEquals(Boolean.FALSE, strict.getAdditionalPropertiesBoolean());

        var typed = body.getProperties().get("typed");
        assertNotNull(typed.getAdditionalPropertiesSchema());
        assertEquals(SchemaType.STRING, typed.getAdditionalPropertiesSchema().getType().get(0));
        assertEquals("field-ext", typed.getExtensions().get("x-field"));
    }

    @Test
    void generate_mapsBeanValidationConstraints() {
        generator.generate(BeanValidated.class);
        var body = registry.snapshot().get("BeanValidated");

        var sizedText = body.getProperties().get("sizedText");
        assertEquals(Integer.valueOf(1), sizedText.getMinLength());
        assertEquals(Integer.valueOf(6), sizedText.getMaxLength());

        var positiveInt = body.getProperties().get("positiveInt");
        assertEquals(new BigDecimal("0"), positiveInt.getExclusiveMinimum());

        var positiveOrZeroInt = body.getProperties().get("positiveOrZeroInt");
        assertEquals(new BigDecimal("0"), positiveOrZeroInt.getMinimum());

        var sizedList = body.getProperties().get("sizedList");
        assertEquals(Integer.valueOf(1), sizedList.getMinItems());

        var sizedMap = body.getProperties().get("sizedMap");
        assertEquals(Integer.valueOf(3), sizedMap.getMinProperties());

        assertNotNull(body.getRequired());
        assertTrue(body.getRequired().contains("requiredName"));
    }

    @Test
    void generate_appliesRefOverrideFromAnnotation() throws Exception {
        Field f = HasRef.class.getDeclaredField("ext");
        var ann = f.getAnnotation(Schema.class);
        var s = generator.generate(f.getGenericType(), ann);
        assertEquals("#/components/schemas/External", s.getRef());
    }

    @Test
    void generate_appliesImplementationOverride() throws Exception {
        Field f = HasImpl.class.getDeclaredField("any");
        var ann = f.getAnnotation(Schema.class);
        var s = generator.generate(f.getGenericType(), ann);
        // implementation = Pet.class → should produce a Pet $ref.
        assertEquals("#/components/schemas/Pet", s.getRef());
    }

    @Test
    void applyTo_writesSchemasIntoComponents() {
        generator.generate(Pet.class);
        OpenAPI api = org.eclipse.microprofile.openapi.OASFactory.createObject(OpenAPI.class);
        registry.applyTo(api);
        assertNotNull(api.getComponents());
        assertTrue(api.getComponents().getSchemas().containsKey("Pet"));
    }

    @Test
    void generate_avoidsNameCollisionBetweenDifferentClasses() {
        // Two classes named "Pet" in different inner-class scopes → ensure uniqueness suffix.
        generator.generate(Pet.class);
        generator.generate(Other.Pet.class);
        var keys = registry.snapshot().keySet();
        assertTrue(keys.contains("Pet"));
        assertTrue(keys.contains("Pet2"));
    }

    // ------------------ Fixtures ------------------

    static final class Holder {
        int[] ints;
        List<String> names;
        Set<Long> ids;
        Map<String, String> labels;
    }

    enum Status { OPEN, CLOSED }

    static final class Pet {
        String name;
        int age;
    }

    static final class Node {
        String value;
        Node next;
    }

    @Schema(name = "MyName")
    static final class WithCustomName {
        String x;
    }

    static final class SecretBox {
        String publicValue;
        @Schema(hidden = true)
        String secret;
    }

    static final class Constrained {
        @Schema(description = "the name", minLength = 3, maxLength = 20,
                example = "Alice", required = true)
        String name;
    }

    static final class GetterAnnotated {
        String name;

        @Schema(example = "doggie", required = true)
        public String getName() {
            return name;
        }
    }

    static final class ExclusiveBounds {
        @Schema(maximum = "101", exclusiveMaximum = true, minimum = "9", exclusiveMinimum = true)
        Long id;
    }

    static final class HasRef {
        @Schema(ref = "#/components/schemas/External")
        Object ext;
    }

    static final class HasImpl {
        @Schema(implementation = Pet.class)
        Object any;
    }

    static final class WithExternalDocs {
        @Schema(externalDocs = @org.eclipse.microprofile.openapi.annotations.ExternalDocumentation(
                description = "Pet Types",
                url = "http://example.com/pettypes"
        ))
        String code;
    }

    static final class BeanValidated {
        @jakarta.validation.constraints.Size(min = 1, max = 6)
        String sizedText;

        @jakarta.validation.constraints.Positive
        Integer positiveInt;

        @jakarta.validation.constraints.PositiveOrZero
        Integer positiveOrZeroInt;

        @jakarta.validation.constraints.Size(min = 1)
        List<String> sizedList;

        @jakarta.validation.constraints.Size(min = 3)
        Map<String, String> sizedMap;

        @jakarta.validation.constraints.NotNull
        String requiredName;
    }

    static final class AdditionalPropertiesFixture {
        @Schema(additionalProperties = Schema.True.class)
        Map<String, Object> metadata;

        @Schema(additionalProperties = Schema.False.class)
        Map<String, Object> strict;

        @Schema(additionalProperties = String.class,
                extensions = @Extension(name = "x-field", value = "field-ext"))
        Map<String, Object> typed;
    }

    static final class Other {
        static final class Pet {
            String differentField;
        }
    }

    // unused but kept to ensure HashMap import is meaningful
    @SuppressWarnings("unused")
    private static final HashMap<String, String> SENTINEL = new LinkedHashMap<>();
}

