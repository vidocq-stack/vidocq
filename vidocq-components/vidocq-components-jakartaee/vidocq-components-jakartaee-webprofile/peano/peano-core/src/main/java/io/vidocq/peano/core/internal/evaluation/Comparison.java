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
import jakarta.el.ELException;
import jakarta.el.ELContext;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Equality and ordered comparison from EL 6.0 section 1.9. */
public final class Comparison {
    private final ELContext context;

    private Comparison(ELContext context) {
        this.context = context;
    }

    public static boolean isEqual(Object a, Object b) {
        return isEqual(a, b, null);
    }

    public static boolean isEqual(Object a, Object b, ELContext context) {
        return new Comparison(context).equalValues(a, b);
    }

    private boolean equalValues(Object a, Object b) {
        try {
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (numeric(a) || numeric(b)) return equalNumbers(a, b);
            return equalOthers(a, b);
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Equality comparison failed", error);
        }
    }

    private boolean equalNumbers(Object a, Object b) {
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return convert(a, BigDecimal.class).equals(convert(b, BigDecimal.class));
        }
        if (floating(a) || floating(b)) {
            return convert(a, Double.class).doubleValue() == convert(b, Double.class).doubleValue();
        }
        if (a instanceof BigInteger || b instanceof BigInteger) {
            return convert(a, BigInteger.class).equals(convert(b, BigInteger.class));
        }
        return convert(a, Long.class).equals(convert(b, Long.class));
    }

    private boolean equalOthers(Object a, Object b) {
        if (a instanceof Boolean || b instanceof Boolean) {
            return convert(a, Boolean.class).equals(convert(b, Boolean.class));
        }
        if (a instanceof Enum<?> e) {
            return e.equals(convert(b, e.getDeclaringClass()));
        }
        if (b instanceof Enum<?> e) {
            return e.equals(convert(a, e.getDeclaringClass()));
        }
        if (a instanceof String || b instanceof String) {
            return convert(a, String.class).equals(convert(b, String.class));
        }
        return a.equals(b);
    }

    public static boolean relate(TokenKind operator, Object a, Object b) {
        return relate(operator, a, b, null);
    }

    public static boolean relate(TokenKind operator, Object a, Object b, ELContext context) {
        return new Comparison(context).relateValues(operator, a, b);
    }

    private boolean relateValues(TokenKind operator, Object a, Object b) {
        try {
            if (a == b) {
                return operator == TokenKind.LESS_EQUAL || operator == TokenKind.GREATER_EQUAL;
            }
            if (a == null || b == null) return false;
            if (numeric(a) || numeric(b)) return relateNumbers(operator, a, b);
            if (a instanceof String || b instanceof String) {
                return ordered(operator, convert(a, String.class).compareTo(convert(b, String.class)));
            }
            if (a instanceof Comparable<?> c) return ordered(operator, compare(c, b));
            if (b instanceof Comparable<?> c) return ordered(operator, -Integer.signum(compare(c, a)));
            throw new ELException("Operands are not comparable");
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Ordered comparison failed", error);
        }
    }

    private boolean relateNumbers(TokenKind operator, Object a, Object b) {
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return ordered(operator, convert(a, BigDecimal.class).compareTo(convert(b, BigDecimal.class)));
        }
        if (floating(a) || floating(b)) {
            double left = convert(a, Double.class);
            double right = convert(b, Double.class);
            return switch (operator) {
                case LESS -> left < right;
                case GREATER -> left > right;
                case LESS_EQUAL -> left <= right;
                case GREATER_EQUAL -> left >= right;
                default -> throw new ELException("Invalid comparison operator " + operator);
            };
        }
        if (a instanceof BigInteger || b instanceof BigInteger) {
            return ordered(operator, convert(a, BigInteger.class).compareTo(convert(b, BigInteger.class)));
        }
        return ordered(operator, Long.compare(convert(a, Long.class), convert(b, Long.class)));
    }

    private <T> T convert(Object value, Class<T> type) {
        return context == null ? Coercions.convert(value, type) : context.convertToType(value, type);
    }

    @SuppressWarnings("unchecked")
    private static int compare(Comparable<?> value, Object other) {
        return ((Comparable<Object>) value).compareTo(other);
    }

    private static boolean numeric(Object value) {
        return value instanceof BigDecimal || value instanceof BigInteger || floating(value) || integral(value);
    }

    private static boolean floating(Object value) {
        return value instanceof Float || value instanceof Double;
    }

    private static boolean integral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Character
                || value instanceof Integer || value instanceof Long;
    }

    private static boolean ordered(TokenKind operator, int result) {
        return switch (operator) {
            case LESS -> result < 0;
            case GREATER -> result > 0;
            case LESS_EQUAL -> result <= 0;
            case GREATER_EQUAL -> result >= 0;
            default -> throw new ELException("Invalid comparison operator " + operator);
        };
    }
}

