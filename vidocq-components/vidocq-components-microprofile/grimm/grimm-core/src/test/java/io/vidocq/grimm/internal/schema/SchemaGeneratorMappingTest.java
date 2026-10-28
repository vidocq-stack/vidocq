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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.DependentRequired;
import org.eclipse.microprofile.openapi.annotations.media.DependentSchema;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.PatternProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.ARRAY;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.BOOLEAN;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.INTEGER;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.NULL;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.NUMBER;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.OBJECT;
import static org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaGenerator}: MP OpenAPI 4.2 §5.2.5 ({@code @Schema} attributes on a declaration
 * site) and the type-to-schema rules for scalars, containers, enums and POJOs.
 */
class SchemaGeneratorMappingTest {

    private static final String REF_PREFIX = "#/components/schemas/";
    private static final String DATE_TIME = "date-time";
    private static final String PET = "Pet";
    private static final String NAME = "name";

    private final SchemaRegistry registry = new SchemaRegistry();
    private final SchemaGenerator generator = new SchemaGenerator(registry);

    // ---------------- fixtures ----------------

    static final class Pet {
        String name;
    }

    static final class Owner {
        int age;
    }

    enum Color { RED, GREEN }

    @Schema(name = "Shade", enumeration = {"light", "dark"})
    enum Shade { LIGHT, DARK }

    @Schema(hidden = true)
    static final class Secret {
        String code;
    }

    @Schema(description = "a named pet", requiredProperties = NAME)
    static class Animal {
        String name;
        @Schema(hidden = true) String internal;

        public boolean isTame() {
            return true;
        }

        public Boolean isWild() {
            return Boolean.FALSE;
        }

        @NotNull
        public String getNickname() {
            return name;
        }

        @Schema(name = "label", required = true)
        public String getTag() {
            return name;
        }

        @Schema(hidden = true)
        public String getHiddenByGetter() {
            return name;
        }

        public String getInternal() {
            return internal;
        }

        @Size(max = 10)
        public String getName() {
            return name;
        }

        public String is() {
            return name;
        }

        public String get() {
            return name;
        }

        public String compute(int factor) {
            return name + factor;
        }

        public static String getStaticValue() {
            return "static";
        }
    }

    static final class Dog extends Animal {
        @NotNull String breed;
        @Schema(required = true) String chip;
    }

    @SuppressWarnings("unused")
    static final class Sites {
        @Schema(implementation = Schema.True.class) Object anything;
        @Schema(implementation = Schema.False.class) Object nothing;
        @Schema(implementation = Pet.class, description = "override") Object implemented;
        @Schema(type = SchemaType.ARRAY) Pet arrayOfRef;
        @Schema(format = "custom", title = "T", comment = "C", pattern = "p", example = "ex",
                defaultValue = "d", minLength = 1, maxLength = 9, minProperties = 1, maxProperties = 5,
                minItems = 2, maxItems = 4, uniqueItems = true, deprecated = true, readOnly = true,
                writeOnly = true, contentEncoding = "base64", contentMediaType = "image/png")
        String scalarFacets;
        @Schema(minimum = "1", maximum = "10", exclusiveMinimum = true, exclusiveMaximum = true,
                multipleOf = 0.5)
        BigDecimal bounds;
        @Schema(minimum = "2", maximum = "3")
        BigDecimal inclusiveBounds;
        @Schema(nullable = true) String nullable;
        @Schema(nullable = true, type = SchemaType.STRING) String nullableTyped;
        @Schema(type = SchemaType.INTEGER, examples = {"1", "2"}, constValue = "3") Object typedExamples;
        @Schema(examples = {"a"}, type = SchemaType.STRING) Object stringExamples;
        @Schema(contentSchema = Pet.class, not = Schema.False.class,
                allOf = Pet.class, anyOf = {Pet.class, Owner.class}, oneOf = Schema.True.class)
        Object composition;
        @Schema(ifSchema = Pet.class, thenSchema = Owner.class, elseSchema = Schema.True.class,
                contains = String.class, minContains = 1, maxContains = 3,
                prefixItems = {String.class, int.class}, propertyNames = String.class)
        Object conditional;
        @Schema(patternProperties = {
                @PatternProperty(regex = "^x-", schema = String.class),
                @PatternProperty(regex = "", schema = String.class),
                @PatternProperty(regex = "^y-", schema = Void.class)},
                dependentRequired = {
                        @DependentRequired(name = "card", requires = {"cvv", "expiry"}),
                        @DependentRequired(name = "", requires = "ignored")},
                dependentSchemas = {
                        @DependentSchema(name = "card", schema = Owner.class),
                        @DependentSchema(name = "", schema = Owner.class)})
        Map<String, String> dependencies;
        @Schema(additionalProperties = Schema.True.class) Map<String, Object> openMap;
        @Schema(additionalProperties = Schema.False.class) Map<String, Object> closedMap;
        @Schema(additionalProperties = Integer.class) Object typedMap;
        @Schema(discriminatorProperty = "kind", discriminatorMapping = {
                @DiscriminatorMapping(value = "pet", schema = Pet.class),
                @DiscriminatorMapping(value = "text", schema = String.class),
                @DiscriminatorMapping(value = "", schema = Pet.class),
                @DiscriminatorMapping(value = "none", schema = Void.class)})
        Object discriminated;
        @Schema(discriminatorMapping = @DiscriminatorMapping(value = "owner", schema = Owner.class))
        Object mappingOnly;
        @Schema(requiredProperties = {"a", "", "a", "b"}, enumeration = {"x", "y"})
        Object requiredAndEnum;
        @Schema(requiredProperties = "") Object emptyRequired;
        @Schema(externalDocs = @ExternalDocumentation(url = "https://example.com/doc", description = "doc"))
        String documented;
        @Schema(properties = {
                @SchemaProperty(name = "kept", type = SchemaType.STRING),
                @SchemaProperty(name = "", type = SchemaType.STRING),
                @SchemaProperty(name = NAME, hidden = true),
                @SchemaProperty(name = "linked", ref = "#/components/schemas/Linked"),
                @SchemaProperty(name = "typed", implementation = Owner.class)})
        Pet withProperties;
        @Schema(properties = @SchemaProperty(name = "only", hidden = true)) Object onlyHidden;
        @Schema(ref = "#/components/schemas/Elsewhere") Pet referenced;

        List<?> wildcard;
        List<? extends Pet> boundedWildcard;
        Optional<String> optional;
    }

    private static Field field(String name) throws NoSuchFieldException {
        return Sites.class.getDeclaredField(name);
    }

    private org.eclipse.microprofile.openapi.models.media.Schema site(String name) throws NoSuchFieldException {
        Field f = field(name);
        return generator.generate(f.getGenericType(), f.getAnnotation(Schema.class));
    }

    private static Type typeOf(String name) throws NoSuchFieldException {
        return field(name).getGenericType();
    }

    private org.eclipse.microprofile.openapi.models.media.Schema component(String name) {
        return registry.snapshot().get(name);
    }

    // ---------------- scalars ----------------

    @Test
    void charactersAndSmallIntegersMapToStringAndInt32() {
        assertEquals(List.of(STRING), generator.generate(char.class).getType());
        assertEquals(List.of(STRING), generator.generate(Character.class).getType());
        assertEquals(List.of(STRING), generator.generate(CharSequence.class).getType());
        assertEquals("int32", generator.generate(byte.class).getFormat());
        assertEquals("int32", generator.generate(Short.class).getFormat());
        assertEquals("float", generator.generate(float.class).getFormat());
        assertEquals("float", generator.generate(Float.class).getFormat());
        assertEquals(List.of(NUMBER), generator.generate(Number.class).getType());
        assertEquals(List.of(BOOLEAN), generator.generate(Boolean.class).getType());
    }

    @Test
    void temporalTypesMapToTheirFormats() {
        assertEquals(DATE_TIME, generator.generate(Date.class).getFormat());
        assertEquals(DATE_TIME, generator.generate(LocalDateTime.class).getFormat());
        assertEquals(DATE_TIME, generator.generate(OffsetDateTime.class).getFormat());
        assertEquals(DATE_TIME, generator.generate(ZonedDateTime.class).getFormat());
        assertEquals("time", generator.generate(LocalTime.class).getFormat());
    }

    @Test
    void objectIsAnOpenObject() {
        assertEquals(List.of(OBJECT), generator.generate(Object.class).getType());
    }

    // ---------------- containers ----------------

    @Test
    void wildcardsUseTheirUpperBound() throws Exception {
        var unbounded = generator.generate(typeOf("wildcard"));
        assertEquals(List.of(ARRAY), unbounded.getType());
        assertEquals(List.of(OBJECT), unbounded.getItems().getType());

        var bounded = generator.generate(typeOf("boundedWildcard"));
        assertEquals(REF_PREFIX + PET, bounded.getItems().getRef());
    }

    @Test
    void otherParameterizedTypesAreTreatedAsTheirRawClass() throws Exception {
        var optional = generator.generate(typeOf("optional"));
        assertEquals(REF_PREFIX + "Optional", optional.getRef());
    }

    // ---------------- enums ----------------

    @Test
    void enumsAreInternedOnceWithTheirConstants() {
        var first = generator.generate(Color.class);
        var second = generator.generate(Color.class);

        assertEquals(REF_PREFIX + "Color", first.getRef());
        assertEquals(first.getRef(), second.getRef());
        assertEquals(List.of("RED", "GREEN"), component("Color").getEnumeration());
    }

    @Test
    void enumSchemaAnnotationNamesAndEnumeratesTheComponent() {
        var s = generator.generate(Shade.class);

        assertEquals(REF_PREFIX + "Shade", s.getRef());
        assertEquals(List.of("light", "dark"), component("Shade").getEnumeration());
    }

    // ---------------- POJOs ----------------

    @Test
    void hiddenClassesGiveAPlainObject() {
        var s = generator.generate(Secret.class);
        assertNull(s.getRef());
        assertEquals(List.of(OBJECT), s.getType());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void gettersBecomePropertiesUnlessHidden() {
        generator.generate(Animal.class);
        var body = component("Animal");
        Map<String, org.eclipse.microprofile.openapi.models.media.Schema> properties = body.getProperties();

        assertTrue(properties.containsKey(NAME));
        assertTrue(properties.containsKey("tame"));
        assertTrue(properties.containsKey("wild"));
        assertTrue(properties.containsKey("nickname"));
        assertTrue(properties.containsKey("label"));
        assertFalse(properties.containsKey("tag"));
        assertFalse(properties.containsKey("hiddenByGetter"));
        assertFalse(properties.containsKey("internal"));
        assertFalse(properties.containsKey("staticValue"));
        assertEquals(10, properties.get(NAME).getMaxLength());
        assertEquals("a named pet", body.getDescription());
        assertEquals(3, body.getRequired().size());
        assertTrue(body.getRequired().containsAll(List.of("nickname", "label", NAME)));
    }

    @Test
    void subclassesInheritPropertiesAndAddRequiredFields() {
        generator.generate(Dog.class);
        var body = component("Dog");

        assertTrue(body.getProperties().containsKey("breed"));
        assertTrue(body.getProperties().containsKey(NAME));
        assertTrue(body.getRequired().containsAll(List.of("breed", "chip", "nickname")));
    }

    // ---------------- site-level @Schema ----------------

    @Test
    void booleanImplementationsGiveBooleanSchemas() throws Exception {
        assertEquals(Boolean.TRUE, site("anything").getBooleanSchema());
        assertEquals(Boolean.FALSE, site("nothing").getBooleanSchema());
    }

    @Test
    void implementationReplacesTheDeclaredType() throws Exception {
        var s = site("implemented");
        assertEquals(REF_PREFIX + PET, s.getRef());
        assertEquals("override", s.getDescription());
    }

    @Test
    void siteRefWinsOutright() throws Exception {
        assertEquals("#/components/schemas/Elsewhere", site("referenced").getRef());
    }

    @Test
    void arrayTypeOnAReferenceMovesTheRefToItems() throws Exception {
        var s = site("arrayOfRef");
        assertNull(s.getRef());
        assertEquals(List.of(ARRAY), s.getType());
        assertEquals(REF_PREFIX + PET, s.getItems().getRef());
    }

    @Test
    void scalarFacetsAreCopied() throws Exception {
        var s = site("scalarFacets");
        assertEquals("custom", s.getFormat());
        assertEquals("T", s.getTitle());
        assertEquals("C", s.getComment());
        assertEquals("p", s.getPattern());
        assertEquals("ex", s.getExample());
        assertEquals(List.of("ex"), s.getExamples());
        assertEquals("d", s.getDefaultValue());
        assertEquals(1, s.getMinLength());
        assertEquals(9, s.getMaxLength());
        assertEquals(1, s.getMinProperties());
        assertEquals(5, s.getMaxProperties());
        assertEquals(2, s.getMinItems());
        assertEquals(4, s.getMaxItems());
        assertEquals(Boolean.TRUE, s.getUniqueItems());
        assertEquals(Boolean.TRUE, s.getDeprecated());
        assertEquals(Boolean.TRUE, s.getReadOnly());
        assertEquals(Boolean.TRUE, s.getWriteOnly());
        assertEquals("base64", s.getContentEncoding());
        assertEquals("image/png", s.getContentMediaType());
    }

    @Test
    void numericBoundsHonourExclusivity() throws Exception {
        var exclusive = site("bounds");
        assertEquals(new BigDecimal("1"), exclusive.getExclusiveMinimum());
        assertEquals(new BigDecimal("10"), exclusive.getExclusiveMaximum());
        assertEquals(BigDecimal.valueOf(0.5), exclusive.getMultipleOf());

        var inclusive = site("inclusiveBounds");
        assertEquals(new BigDecimal("2"), inclusive.getMinimum());
        assertEquals(new BigDecimal("3"), inclusive.getMaximum());
        assertNull(inclusive.getExclusiveMinimum());
    }

    @Test
    void nullableAddsTheNullTypeOnce() throws Exception {
        assertEquals(List.of(STRING, NULL), site("nullable").getType());
        assertEquals(List.of(STRING, NULL), site("nullableTyped").getType());
    }

    @Test
    void examplesAndConstAreJsonUnlessTheTypeIsString() throws Exception {
        var typed = site("typedExamples");
        assertEquals(List.of(INTEGER), typed.getType());
        assertEquals(2, typed.getExamples().size());
        assertFalse(typed.getExamples().get(0) instanceof String);
        assertFalse(typed.getConstValue() instanceof String);

        assertEquals(List.of("a"), site("stringExamples").getExamples());
    }

    @Test
    void compositionKeywordsReferenceTheNamedClasses() throws Exception {
        var s = site("composition");
        assertEquals(REF_PREFIX + PET, s.getContentSchema().getRef());
        assertEquals(Boolean.FALSE, s.getNot().getBooleanSchema());
        assertEquals(1, s.getAllOf().size());
        assertEquals(REF_PREFIX + "Owner", s.getAnyOf().get(1).getRef());
        assertEquals(Boolean.TRUE, s.getOneOf().get(0).getBooleanSchema());
    }

    @Test
    void conditionalAndArrayKeywordsAreMapped() throws Exception {
        var s = site("conditional");
        assertEquals(REF_PREFIX + PET, s.getIfSchema().getRef());
        assertEquals(REF_PREFIX + "Owner", s.getThenSchema().getRef());
        assertEquals(Boolean.TRUE, s.getElseSchema().getBooleanSchema());
        assertEquals(List.of(STRING), s.getContains().getType());
        assertEquals(1, s.getMinContains());
        assertEquals(3, s.getMaxContains());
        assertEquals(2, s.getPrefixItems().size());
        assertEquals(List.of(STRING), s.getPropertyNames().getType());
    }

    @Test
    void patternPropertiesAndDependenciesSkipIncompleteEntries() throws Exception {
        var s = site("dependencies");
        assertEquals(1, s.getPatternProperties().size());
        assertTrue(s.getPatternProperties().containsKey("^x-"));
        assertEquals(Map.of("card", List.of("cvv", "expiry")), s.getDependentRequired());
        assertEquals(1, s.getDependentSchemas().size());
        assertEquals(REF_PREFIX + "Owner", s.getDependentSchemas().get("card").getRef());
    }

    @Test
    void additionalPropertiesBooleanOrSchema() throws Exception {
        assertEquals(Boolean.TRUE, site("openMap").getAdditionalPropertiesSchema().getBooleanSchema());
        assertEquals(Boolean.FALSE, site("closedMap").getAdditionalPropertiesSchema().getBooleanSchema());
        assertEquals("int32", site("typedMap").getAdditionalPropertiesSchema().getFormat());
    }

    @Test
    void discriminatorKeepsOnlyReferenceMappings() throws Exception {
        var discriminator = site("discriminated").getDiscriminator();
        assertEquals("kind", discriminator.getPropertyName());
        assertEquals(Map.of("pet", REF_PREFIX + PET), discriminator.getMapping());

        var mappingOnly = site("mappingOnly").getDiscriminator();
        assertNull(mappingOnly.getPropertyName());
        assertEquals(Map.of("owner", REF_PREFIX + "Owner"), mappingOnly.getMapping());
    }

    @Test
    void requiredPropertiesAreDeduplicatedAndEnumerationCopied() throws Exception {
        var s = site("requiredAndEnum");
        assertEquals(List.of("a", "b"), s.getRequired());
        assertEquals(List.of("x", "y"), s.getEnumeration());
        assertNull(site("emptyRequired").getRequired());
    }

    @Test
    void externalDocsAreMapped() throws Exception {
        var docs = site("documented").getExternalDocs();
        assertEquals("https://example.com/doc", docs.getUrl());
        assertEquals("doc", docs.getDescription());
    }

    @Test
    void schemaPropertiesAreMergedHiddenRemovedAndRefsKept() throws Exception {
        var s = site("withProperties");
        var properties = s.getProperties();

        assertEquals(List.of(STRING), properties.get("kept").getType());
        assertFalse(properties.containsKey(NAME));
        assertFalse(properties.containsKey(""));
        assertEquals("#/components/schemas/Linked", properties.get("linked").getRef());
        assertEquals(REF_PREFIX + "Owner", properties.get("typed").getRef());
    }

    @Test
    void onlyHiddenPropertiesLeaveNoProperties() throws Exception {
        var s = site("onlyHidden");
        assertNull(s.getProperties());
        assertNull(s.getRequired());
    }

    // ---------------- hasContent ----------------

    @Test
    void hasContentDetectsEveryKindOfAttribute() throws Exception {
        assertTrue(SchemaGenerator.hasContent(field("implemented").getAnnotation(Schema.class)));
        assertTrue(SchemaGenerator.hasContent(field("referenced").getAnnotation(Schema.class)));
        assertTrue(SchemaGenerator.hasContent(field("withProperties").getAnnotation(Schema.class)));
        assertTrue(SchemaGenerator.hasContent(field("scalarFacets").getAnnotation(Schema.class)));
        assertTrue(SchemaGenerator.hasContent(Dog.class.getDeclaredField("chip").getAnnotation(Schema.class)));
        assertFalse(SchemaGenerator.hasContent(SchemaAttributesTest.Empty.class.getAnnotation(Schema.class)));
    }

    @Test
    void hasContentChecksEachSharedAttribute() {
        for (Field f : Sites.class.getDeclaredFields()) {
            Schema annotation = f.getAnnotation(Schema.class);
            if (annotation != null) {
                assertTrue(SchemaAttributes.of(annotation).hasContent() || SchemaGenerator.hasContent(annotation),
                        f.getName());
            }
        }
    }
}
