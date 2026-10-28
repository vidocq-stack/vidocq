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

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP OpenAPI 4.2 §4.2 "Bean Validation": each Jakarta Bean Validation constraint of the
 * default group becomes the matching JSON Schema facet.
 */
class BeanValidationConstraintsTest {

    private static final String TEXT = "text";
    private static final String LIST = "list";
    private static final String MAP = "map";

    interface Strict {
    }

    @SuppressWarnings("unused")
    static final class Holder {
        @Size(min = 2, max = 8) String text;
        @Size(min = 1, max = 3) List<String> list;
        @Size(min = 1, max = 4) java.util.Map<String, String> map;
        @Size(max = 5) String maxOnly;
        @Size String noBounds;
        @DecimalMin("1.5") BigDecimal decimalMin;
        @DecimalMin(value = "1.5", inclusive = false) BigDecimal decimalMinExclusive;
        @DecimalMax("9.5") BigDecimal decimalMax;
        @DecimalMax(value = "9.5", inclusive = false) BigDecimal decimalMaxExclusive;
        @Min(3) long min;
        @Max(7) long max;
        @Positive int positive;
        @PositiveOrZero int positiveOrZero;
        @Negative int negative;
        @NegativeOrZero int negativeOrZero;
        @Pattern(regexp = "[a-z]+") String pattern;
        @NotBlank String notBlank;
        @NotEmpty String notEmptyString;
        @NotEmpty List<String> notEmptyList;
        @NotEmpty java.util.Map<String, String> notEmptyMap;
        @Digits(integer = 3, fraction = 1) String digits;
        @Size(min = 4, groups = Strict.class) String otherGroup;
        @Size(min = 4, groups = {Strict.class, Default.class}) String defaultGroup;
        @NotNull String notNull;
        @Deprecated String notAConstraint;
    }

    private static Annotation[] annotationsOf(String field) throws NoSuchFieldException {
        return Holder.class.getDeclaredField(field).getAnnotations();
    }

    private static Schema typed(Schema.SchemaType type) {
        return OASFactory.createSchema().type(List.of(type));
    }

    private static Schema mapped(Schema.SchemaType type, String field) throws NoSuchFieldException {
        Schema schema = typed(type);
        BeanValidationMapper.apply(schema, annotationsOf(field));
        return schema;
    }

    @Test
    void sizeOnStringBoundsTheLength() throws Exception {
        Schema s = mapped(Schema.SchemaType.STRING, TEXT);
        assertEquals(2, s.getMinLength());
        assertEquals(8, s.getMaxLength());
    }

    @Test
    void sizeOnArrayBoundsTheItems() throws Exception {
        Schema s = mapped(Schema.SchemaType.ARRAY, LIST);
        assertEquals(1, s.getMinItems());
        assertEquals(3, s.getMaxItems());
        assertNull(s.getMinLength());
    }

    @Test
    void sizeOnObjectBoundsTheProperties() throws Exception {
        Schema s = mapped(Schema.SchemaType.OBJECT, MAP);
        assertEquals(1, s.getMinProperties());
        assertEquals(4, s.getMaxProperties());
    }

    @Test
    void sizeKeepsTighterExplicitBounds() throws Exception {
        Schema text = typed(Schema.SchemaType.STRING).minLength(5).maxLength(6);
        BeanValidationMapper.apply(text, annotationsOf(TEXT));
        assertEquals(5, text.getMinLength());
        assertEquals(6, text.getMaxLength());

        Schema list = typed(Schema.SchemaType.ARRAY).minItems(2).maxItems(2);
        BeanValidationMapper.apply(list, annotationsOf(LIST));
        assertEquals(2, list.getMinItems());
        assertEquals(2, list.getMaxItems());

        Schema map = typed(Schema.SchemaType.OBJECT).minProperties(3).maxProperties(3);
        BeanValidationMapper.apply(map, annotationsOf(MAP));
        assertEquals(3, map.getMinProperties());
        assertEquals(3, map.getMaxProperties());
    }

    @Test
    void sizeWithDefaultsSetsOnlyWhatIsGiven() throws Exception {
        Schema maxOnly = mapped(Schema.SchemaType.STRING, "maxOnly");
        assertNull(maxOnly.getMinLength());
        assertEquals(5, maxOnly.getMaxLength());

        Schema noBounds = mapped(Schema.SchemaType.STRING, "noBounds");
        assertNull(noBounds.getMinLength());
        assertNull(noBounds.getMaxLength());
    }

    @Test
    void decimalBoundsAreInclusiveUnlessStated() throws Exception {
        assertEquals(new BigDecimal("1.5"), mapped(Schema.SchemaType.NUMBER, "decimalMin").getMinimum());
        assertEquals(new BigDecimal("9.5"), mapped(Schema.SchemaType.NUMBER, "decimalMax").getMaximum());

        Schema exclusiveMin = mapped(Schema.SchemaType.NUMBER, "decimalMinExclusive");
        assertNull(exclusiveMin.getMinimum());
        assertEquals(new BigDecimal("1.5"), exclusiveMin.getExclusiveMinimum());

        Schema exclusiveMax = mapped(Schema.SchemaType.NUMBER, "decimalMaxExclusive");
        assertNull(exclusiveMax.getMaximum());
        assertEquals(new BigDecimal("9.5"), exclusiveMax.getExclusiveMaximum());
    }

    @Test
    void minAndMaxSetTheNumericBounds() throws Exception {
        assertEquals(BigDecimal.valueOf(3), mapped(Schema.SchemaType.INTEGER, "min").getMinimum());
        assertEquals(BigDecimal.valueOf(7), mapped(Schema.SchemaType.INTEGER, "max").getMaximum());
    }

    @Test
    void signConstraintsSetZeroBounds() throws Exception {
        assertEquals(BigDecimal.ZERO, mapped(Schema.SchemaType.INTEGER, "positive").getExclusiveMinimum());
        assertEquals(BigDecimal.ZERO, mapped(Schema.SchemaType.INTEGER, "positiveOrZero").getMinimum());
        assertEquals(BigDecimal.ZERO, mapped(Schema.SchemaType.INTEGER, "negative").getExclusiveMaximum());
        assertEquals(BigDecimal.ZERO, mapped(Schema.SchemaType.INTEGER, "negativeOrZero").getMaximum());
    }

    @Test
    void patternSetsThePattern() throws Exception {
        assertEquals("[a-z]+", mapped(Schema.SchemaType.STRING, "pattern").getPattern());
    }

    @Test
    void notBlankRequiresOneNonSpaceCharacter() throws Exception {
        Schema s = mapped(Schema.SchemaType.STRING, "notBlank");
        assertEquals(1, s.getMinLength());
        assertEquals("\\S", s.getPattern());

        Schema withPattern = typed(Schema.SchemaType.STRING).pattern("x+");
        BeanValidationMapper.apply(withPattern, annotationsOf("notBlank"));
        assertEquals("x+", withPattern.getPattern());
    }

    @Test
    void notEmptyDependsOnTheSchemaType() throws Exception {
        assertEquals(1, mapped(Schema.SchemaType.STRING, "notEmptyString").getMinLength());
        assertEquals(1, mapped(Schema.SchemaType.ARRAY, "notEmptyList").getMinItems());
        assertEquals(1, mapped(Schema.SchemaType.OBJECT, "notEmptyMap").getMinProperties());
    }

    @Test
    void digitsWithoutTypeIsIgnored() throws Exception {
        Schema untyped = OASFactory.createSchema();
        BeanValidationMapper.apply(untyped, annotationsOf("digits"));
        assertNull(untyped.getPattern());
        assertNull(untyped.getMultipleOf());

        Schema bool = mapped(Schema.SchemaType.BOOLEAN, "digits");
        assertNull(bool.getPattern());
        assertNull(bool.getMultipleOf());
    }

    @Test
    void onlyConstraintsOfTheDefaultGroupApply() throws Exception {
        assertNull(mapped(Schema.SchemaType.STRING, "otherGroup").getMinLength());
        assertEquals(4, mapped(Schema.SchemaType.STRING, "defaultGroup").getMinLength());
    }

    @Test
    void nonConstraintAnnotationsAndEmptyInputsAreIgnored() throws Exception {
        Schema s = mapped(Schema.SchemaType.STRING, "notAConstraint");
        assertNull(s.getMinLength());
        assertNull(s.getPattern());

        Schema empty = typed(Schema.SchemaType.STRING);
        BeanValidationMapper.apply(empty, null);
        BeanValidationMapper.apply(empty, new Annotation[0]);
        BeanValidationMapper.apply(null, annotationsOf(TEXT));
        assertNull(empty.getMinLength());
    }

    @Test
    void disabledMappingLeavesTheSchemaUntouched() throws Exception {
        BeanValidationMapper.setEnabled(false);
        try {
            assertNull(mapped(Schema.SchemaType.STRING, TEXT).getMinLength());
        } finally {
            BeanValidationMapper.setEnabled(true);
        }
        assertEquals(2, mapped(Schema.SchemaType.STRING, TEXT).getMinLength());
    }

    @Test
    void hasNotNullDetectsTheConstraint() throws Exception {
        assertTrue(BeanValidationMapper.hasNotNull(annotationsOf("notNull")));
        assertFalse(BeanValidationMapper.hasNotNull(annotationsOf(TEXT)));
        assertFalse(BeanValidationMapper.hasNotNull(null));
    }
}
