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
import java.util.Collection;
import java.util.Map;
import jakarta.el.ELException;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Reference tree-walking evaluator for the P1 literal and operator subset. */
public final class Interpreter {
    private Interpreter() {
    }

    public static Object evaluate(Template template) {
        if (template.parts().size() == 1 && template.parts().getFirst() instanceof Template.Evaluation part) {
            return evaluate(part.body());
        }
        var result = new StringBuilder();
        for (Template.Part part : template.parts()) {
            switch (part) {
                case Template.Text text -> result.append(text.value());
                case Template.Evaluation expression -> result.append(
                        Coercions.convert(evaluate(expression.body()), String.class));
            }
        }
        return result.toString();
    }

    public static Object evaluate(Ast ast) {
        return switch (ast) {
            case Ast.Literal literal -> literal(literal.token());
            case Ast.Unary unary -> unary(unary);
            case Ast.Binary binary -> binary(binary);
            case Ast.Conditional conditional -> Coercions.convert(evaluate(conditional.condition()), boolean.class)
                    ? evaluate(conditional.whenTrue()) : evaluate(conditional.whenFalse());
            default -> throw new ELException("Evaluation not implemented for " + ast.getClass().getSimpleName());
        };
    }

    private static Object literal(Token token) {
        try {
            return switch (token.kind()) {
                case INTEGER -> Long.valueOf(token.text());
                case FLOATING_POINT -> Double.valueOf(token.text());
                case STRING -> token.value();
                case TRUE -> true;
                case FALSE -> false;
                case NULL -> null;
                default -> throw new ELException("Invalid literal token " + token.kind());
            };
        } catch (NumberFormatException error) {
            throw new ELException("Invalid numeric literal at offset " + token.start(), error);
        }
    }

    private static Object unary(Ast.Unary unary) {
        // Parse the signed literal directly: Long.MIN_VALUE's magnitude does not fit in Long.
        if (unary.operator().kind() == TokenKind.MINUS && unary.operand() instanceof Ast.Literal literal
                && literal.token().kind() == TokenKind.INTEGER) {
            try {
                return Long.valueOf("-" + literal.token().text());
            } catch (NumberFormatException error) {
                throw new ELException("Invalid numeric literal at offset " + literal.token().start(), error);
            }
        }
        Object value = evaluate(unary.operand());
        return switch (unary.operator().kind()) {
            case MINUS -> Arithmetic.negate(value);
            case NOT -> !Coercions.convert(value, boolean.class);
            case EMPTY -> isEmpty(value);
            default -> throw new ELException("Invalid unary operator " + unary.operator().kind());
        };
    }

    private static Object binary(Ast.Binary binary) {
        TokenKind operator = binary.operator().kind();
        if (operator == TokenKind.ASSIGN) {
            throw new ELException("Assignment evaluation is not implemented (P3)");
        }
        Object left = evaluate(binary.left());
        // Logical and conditional operators must not evaluate a discarded branch.
        if (operator == TokenKind.AND) {
            return Coercions.convert(left, boolean.class)
                    && Coercions.convert(evaluate(binary.right()), boolean.class);
        }
        if (operator == TokenKind.OR) {
            return Coercions.convert(left, boolean.class)
                    || Coercions.convert(evaluate(binary.right()), boolean.class);
        }
        Object right = evaluate(binary.right());
        return switch (operator) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, MODULO -> Arithmetic.binary(operator, left, right);
            case CONCAT -> Coercions.convert(left, String.class) + Coercions.convert(right, String.class);
            case EQUAL -> Comparison.equal(left, right);
            case NOT_EQUAL -> !Comparison.equal(left, right);
            case LESS, GREATER, LESS_EQUAL, GREATER_EQUAL -> Comparison.relate(operator, left, right);
            case SEMICOLON -> right;
            default -> throw new ELException("Invalid binary operator " + operator);
        };
    }

    public static boolean isEmpty(Object value) {
        return value == null || value instanceof String text && text.isEmpty()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty()
                || value.getClass().isArray() && Array.getLength(value) == 0;
    }
}

