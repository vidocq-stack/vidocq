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
package io.vidocq.peano.core.internal.evaluation;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.Predicate;
import jakarta.el.ELException;
import jakarta.el.LambdaExpression;

/** Scalar and array conversions from EL 6.0 section 1.25. */
public final class Coercions {
    private Coercions() {
    }

    /**
     * Converts a value, preserving null for reference targets except String.
     * An unspecified target type preserves the original value.
     * Supports the statically compiled Predicate adapter and registered application
     * functional-interface adapters. PropertyEditor conversions remain unimplemented.
     *
     * @param value input value
     * @param type target type
     * @param <T> target type
     * @return converted value
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Class<T> type) {
        if (type == null) return (T) value;
        try {
            return (T) convertValue(value, type);
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Cannot coerce to " + type.getTypeName(), error);
        }
    }

    private static Object convertValue(Object value, Class<?> type) {
        if (value == null && !type.isPrimitive() && type != String.class) {
            return null;
        }
        Class<?> boxed = boxed(type);
        if (boxed.isInstance(value)) {
            return value;
        }
        if (type == Predicate.class && value instanceof LambdaExpression lambda) {
            return new LambdaPredicate(lambda);
        }
        if (value instanceof LambdaExpression lambda && type.isInterface()) {
            Object adapted = GeneratedAccessorELResolver.adapt(lambda, type);
            if (adapted != null) return adapted;
        }
        if (boxed == String.class) {
            return value == null ? "" : value instanceof Enum<?> e ? e.name() : value.toString();
        }
        if (boxed == Boolean.class) {
            if (value == null || "".equals(value)) {
                return false;
            }
            if (value instanceof String text) {
                return Boolean.valueOf(text);
            }
            throw failure(type);
        }
        if (boxed == Character.class) {
            if (value == null || "".equals(value)) {
                return (char) 0;
            }
            if (value instanceof Number number) {
                return (char) number.shortValue();
            }
            if (value instanceof String text) {
                return text.charAt(0);
            }
            throw failure(type);
        }
        if (Number.class.isAssignableFrom(boxed)) {
            return number(value, boxed);
        }
        if (type.isEnum()) {
            if ("".equals(value)) {
                return null;
            }
            if (value instanceof String text) {
                return enumValue(type, text);
            }
            throw failure(type);
        }
        if (type.isArray()) {
            if (value == null) {
                return null;
            }
            if (!value.getClass().isArray()) {
                throw failure(type);
            }
            int length = Array.getLength(value);
            Class<?> elementType = type.getComponentType();
            Object result = Array.newInstance(elementType, length);
            for (int i = 0; i < length; i++) {
                Array.set(result, i, convert(Array.get(value, i), elementType));
            }
            return result;
        }
        if ("".equals(value) && !type.isPrimitive()) {
            return null;
        }
        throw failure(type);
    }

    private static Number number(Object value, Class<?> type) {
        if (value == null || "".equals(value)) {
            value = 0L;
        }
        if (value instanceof Character character) {
            value = (short) character.charValue();
        }
        if (value instanceof String text) {
            if (type == Byte.class) return Byte.valueOf(text);
            if (type == Short.class) return Short.valueOf(text);
            if (type == Integer.class) return Integer.valueOf(text);
            if (type == Long.class) return Long.valueOf(text);
            if (type == Float.class) return Float.valueOf(text);
            if (type == Double.class) return Double.valueOf(text);
            if (type == BigInteger.class) return new BigInteger(text);
            if (type == BigDecimal.class) return new BigDecimal(text);
        }
        if (value instanceof Number number) {
            if (type == Byte.class) return number.byteValue();
            if (type == Short.class) return number.shortValue();
            if (type == Integer.class) return number.intValue();
            if (type == Long.class) return number.longValue();
            if (type == Float.class) return number.floatValue();
            if (type == Double.class) return number.doubleValue();
            if (type == BigInteger.class) {
                return number instanceof BigDecimal decimal ? decimal.toBigInteger()
                        : BigInteger.valueOf(number.longValue());
            }
            if (type == BigDecimal.class) {
                return number instanceof BigInteger integer ? new BigDecimal(integer)
                        : new BigDecimal(number.doubleValue());
            }
        }
        throw failure(type);
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> enumValue(Class<?> type, String value) {
        return Enum.valueOf((Class) type, value);
    }

    private static ELException failure(Class<?> type) {
        return new ELException("Cannot coerce to " + type.getTypeName());
    }

    /** A normal compiled class, with no dynamic proxy or runtime bytecode generation. */
    private record LambdaPredicate(LambdaExpression lambda) implements Predicate<Object> {
        @Override
        public boolean test(Object value) {
            return convert(lambda.invoke(value), boolean.class);
        }
    }
}

