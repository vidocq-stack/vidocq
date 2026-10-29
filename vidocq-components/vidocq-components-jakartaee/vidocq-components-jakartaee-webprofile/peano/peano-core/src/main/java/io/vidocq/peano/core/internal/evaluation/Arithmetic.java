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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import jakarta.el.ELException;
import jakarta.el.ELContext;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Numeric domain selection and arithmetic from EL 6.0 section 1.7. */
public final class Arithmetic {
    private Arithmetic() {
    }

    public static Number binary(TokenKind operator, Object a, Object b) {
        return binary(operator, a, b, null);
    }

    public static Number binary(TokenKind operator, Object a, Object b, ELContext context) {
        return binaryValues(operator, a, b, context);
    }

    private static Number binaryValues(TokenKind operator, Object a, Object b, ELContext context) {
        try {
            if (a == null && b == null) {
                return 0L;
            }
            if (operator == TokenKind.DIVIDE) {
                if (a instanceof BigDecimal || b instanceof BigDecimal
                        || a instanceof BigInteger || b instanceof BigInteger) {
                    return number(a, BigDecimal.class, context).divide(number(b, BigDecimal.class, context), RoundingMode.HALF_UP);
                }
                return number(a, Double.class, context) / number(b, Double.class, context);
            }
            if (operator == TokenKind.MODULO) {
                if (a instanceof BigDecimal || b instanceof BigDecimal || floating(a) || floating(b)) {
                    return number(a, Double.class, context) % number(b, Double.class, context);
                }
                if (a instanceof BigInteger || b instanceof BigInteger) {
                    return number(a, BigInteger.class, context).remainder(number(b, BigInteger.class, context));
                }
                return number(a, Long.class, context) % number(b, Long.class, context);
            }
            if (a instanceof BigDecimal || b instanceof BigDecimal
                    || ((floating(a) || floating(b)) && (a instanceof BigInteger || b instanceof BigInteger))) {
                return decimal(operator, number(a, BigDecimal.class, context), number(b, BigDecimal.class, context));
            }
            if (floating(a) || floating(b)) {
                return floating(operator, number(a, Double.class, context), number(b, Double.class, context));
            }
            if (a instanceof BigInteger || b instanceof BigInteger) {
                return integer(operator, number(a, BigInteger.class, context), number(b, BigInteger.class, context));
            }
            return integral(operator, number(a, Long.class, context), number(b, Long.class, context));
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Arithmetic error for " + operator, error);
        }
    }

    public static Number negate(Object value) {
        return negate(value, null);
    }

    public static Number negate(Object value, ELContext context) {
        return negateValue(value, context);
    }

    private static Number negateValue(Object value, ELContext context) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof String) {
            // EL 6.0 section 1.8.1: a String operand is coerced to Double or to Long, each kept as is.
            Class<? extends Number> domain = floating(value) ? Double.class : Long.class;
            value = number(value, domain, context);
        }
        return switch (value) {
            case BigDecimal n -> n.negate();
            case BigInteger n -> n.negate();
            case Byte n -> Byte.valueOf((byte) -n.byteValue());
            case Short n -> Short.valueOf((short) -n.shortValue());
            case Integer n -> Integer.valueOf(-n.intValue());
            case Long n -> Long.valueOf(-n.longValue());
            case Float n -> Float.valueOf(-n.floatValue());
            case Double n -> Double.valueOf(-n.doubleValue());
            default -> throw new ELException("Invalid operand for unary minus");
        };
    }

    private static <T extends Number> T number(Object value, Class<T> type, ELContext context) {
        Object input = value == null ? 0L : value;
        return context == null ? Coercions.convert(input, type) : context.convertToType(input, type);
    }

    private static boolean floating(Object value) {
        return value instanceof Float || value instanceof Double
                || value instanceof String text && (text.indexOf('.') >= 0 || text.indexOf('e') >= 0
                        || text.indexOf('E') >= 0);
    }

    private static BigDecimal decimal(TokenKind op, BigDecimal a, BigDecimal b) {
        return switch (op) {
            case PLUS -> a.add(b);
            case MINUS -> a.subtract(b);
            case MULTIPLY -> a.multiply(b);
            default -> throw new ELException("Invalid arithmetic operator " + op);
        };
    }

    private static BigInteger integer(TokenKind op, BigInteger a, BigInteger b) {
        return switch (op) {
            case PLUS -> a.add(b);
            case MINUS -> a.subtract(b);
            case MULTIPLY -> a.multiply(b);
            default -> throw new ELException("Invalid arithmetic operator " + op);
        };
    }

    private static double floating(TokenKind op, double a, double b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            default -> throw new ELException("Invalid arithmetic operator " + op);
        };
    }

    private static long integral(TokenKind op, long a, long b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            default -> throw new ELException("Invalid arithmetic operator " + op);
        };
    }
}

