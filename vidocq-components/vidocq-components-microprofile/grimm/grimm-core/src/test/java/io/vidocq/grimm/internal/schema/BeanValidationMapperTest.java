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

import jakarta.validation.constraints.Digits;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean Validation {@code @Digits} mapping (MicroProfile OpenAPI 4.2, issue #717). The fields mirror
 * the official TCK {@code BeanValidationData}.
 */
class BeanValidationMapperTest {

    @SuppressWarnings("unused")
    static class Holder {
        @Digits(integer = 9, fraction = 0) int digitsInt32;
        @Digits(integer = 18, fraction = 0) int digitsInt64;
        @Digits(integer = 5, fraction = 3) float digitsFloat32;
        @Digits(integer = 10, fraction = 6) double digitsFloat64;
        @Digits(integer = 20, fraction = 10) BigDecimal digitsDecimal;
        @Digits(integer = 5, fraction = 0) float digitsFloat32AsInteger;
        @Digits(integer = 10, fraction = 0) double digitsFloat64AsInteger;
        @Digits(integer = 20, fraction = 0) BigDecimal digitsDecimalAsInteger;
        @Digits(integer = 20, fraction = 0) BigInteger digitsInteger;
        @Digits(integer = 10, fraction = 5) String digitsString;
    }

    private static Annotation[] annotationsOf(String field) throws NoSuchFieldException {
        return Holder.class.getDeclaredField(field).getAnnotations();
    }

    private static Schema typed(Schema.SchemaType type) {
        return OASFactory.createSchema().type(List.of(type));
    }

    private static void assertMultipleOf(String expected, Schema s) {
        assertEquals(0, new BigDecimal(expected).compareTo(s.getMultipleOf()),
                () -> "multipleOf was " + s.getMultipleOf());
    }

    @Test
    void digitsOnFloatSetsMultipleOfFromFraction() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER);
        BeanValidationMapper.apply(s, annotationsOf("digitsFloat32"));
        assertMultipleOf("0.001", s);
    }

    @Test
    void digitsOnDoubleSetsMultipleOfFromFraction() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER);
        BeanValidationMapper.apply(s, annotationsOf("digitsFloat64"));
        assertMultipleOf("0.000001", s);
    }

    @Test
    void digitsOnBigDecimalSetsTinyMultipleOf() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER);
        BeanValidationMapper.apply(s, annotationsOf("digitsDecimal"));
        assertMultipleOf("0.0000000001", s);
    }

    @Test
    void digitsWithZeroFractionOnNumberSetsMultipleOfOne() throws Exception {
        for (String field : List.of("digitsFloat32AsInteger", "digitsFloat64AsInteger", "digitsDecimalAsInteger")) {
            Schema s = typed(Schema.SchemaType.NUMBER);
            BeanValidationMapper.apply(s, annotationsOf(field));
            assertMultipleOf("1", s);
        }
    }

    @Test
    void digitsOnIntegerLeavesSchemaUntouched() throws Exception {
        for (String field : List.of("digitsInt32", "digitsInt64", "digitsInteger")) {
            Schema s = typed(Schema.SchemaType.INTEGER);
            BeanValidationMapper.apply(s, annotationsOf(field));
            assertNull(s.getMultipleOf(), field);
        }
    }

    @Test
    void digitsOnStringSetsPattern() throws Exception {
        Schema s = typed(Schema.SchemaType.STRING);
        BeanValidationMapper.apply(s, annotationsOf("digitsString"));
        Pattern p = Pattern.compile(s.getPattern());
        for (String ok : List.of("1", "1.5", "123456789.1234", "1234567890.12345", "-42.1")) {
            assertTrue(p.matcher(ok).matches(), ok);
        }
        for (String ko : List.of("12345678901.12345", "1234567890.123456", "abc", "1.")) {
            assertFalse(p.matcher(ko).matches(), ko);
        }
    }

    @Test
    void userMultipleOfWins() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER).multipleOf(new BigDecimal("1000"));
        BeanValidationMapper.apply(s, annotationsOf("digitsDecimalAsInteger"));
        assertEquals(new BigDecimal("1000"), s.getMultipleOf());
    }

    @Test
    void userPatternWins() throws Exception {
        Schema s = typed(Schema.SchemaType.STRING).pattern("^custom$");
        BeanValidationMapper.apply(s, annotationsOf("digitsString"));
        assertEquals("^custom$", s.getPattern());
    }
}
