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
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Equality and ordered comparison from EL 6.0 section 1.9. */
public final class Comparison {
    private Comparison() {
    }

    public static boolean equal(Object a, Object b) {
        try {
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (a instanceof BigDecimal || b instanceof BigDecimal) {
                return Coercions.convert(a, BigDecimal.class).equals(Coercions.convert(b, BigDecimal.class));
            }
            if (floating(a) || floating(b)) {
                return Coercions.convert(a, Double.class).doubleValue() == Coercions.convert(b, Double.class).doubleValue();
            }
            if (a instanceof BigInteger || b instanceof BigInteger) {
                return Coercions.convert(a, BigInteger.class).equals(Coercions.convert(b, BigInteger.class));
            }
            if (integral(a) || integral(b)) {
                return Coercions.convert(a, Long.class).equals(Coercions.convert(b, Long.class));
            }
            if (a instanceof Boolean || b instanceof Boolean) {
                return Coercions.convert(a, Boolean.class).equals(Coercions.convert(b, Boolean.class));
            }
            if (a instanceof Enum<?> e) {
                return e.equals(Coercions.convert(b, e.getDeclaringClass()));
            }
            if (b instanceof Enum<?> e) {
                return e.equals(Coercions.convert(a, e.getDeclaringClass()));
            }
            if (a instanceof String || b instanceof String) {
                return Coercions.convert(a, String.class).equals(Coercions.convert(b, String.class));
            }
            return a.equals(b);
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Equality comparison failed", error);
        }
    }

    public static boolean relate(TokenKind operator, Object a, Object b) {
        try {
            if (a == b) {
                return operator == TokenKind.LESS_EQUAL || operator == TokenKind.GREATER_EQUAL;
            }
            if (a == null || b == null) return false;
            if (a instanceof BigDecimal || b instanceof BigDecimal) {
                return ordered(operator, Coercions.convert(a, BigDecimal.class).compareTo(Coercions.convert(b, BigDecimal.class)));
            }
            if (floating(a) || floating(b)) {
                double left = Coercions.convert(a, Double.class);
                double right = Coercions.convert(b, Double.class);
                return switch (operator) {
                    case LESS -> left < right;
                    case GREATER -> left > right;
                    case LESS_EQUAL -> left <= right;
                    case GREATER_EQUAL -> left >= right;
                    default -> throw new ELException("Invalid comparison operator " + operator);
                };
            }
            if (a instanceof BigInteger || b instanceof BigInteger) {
                return ordered(operator, Coercions.convert(a, BigInteger.class).compareTo(Coercions.convert(b, BigInteger.class)));
            }
            if (integral(a) || integral(b)) {
                return ordered(operator, Long.compare(Coercions.convert(a, Long.class), Coercions.convert(b, Long.class)));
            }
            if (a instanceof String || b instanceof String) {
                return ordered(operator, Coercions.convert(a, String.class).compareTo(Coercions.convert(b, String.class)));
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

    @SuppressWarnings("unchecked")
    private static int compare(Comparable<?> value, Object other) {
        return ((Comparable<Object>) value).compareTo(other);
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

