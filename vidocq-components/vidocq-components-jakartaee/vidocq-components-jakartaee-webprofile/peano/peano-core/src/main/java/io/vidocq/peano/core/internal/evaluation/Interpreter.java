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
import jakarta.el.ELContext;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Reference tree-walking evaluator with context-owned resolver state. */
public final class Interpreter {
    private final ELContext context;
    private final Map<String, ValueExpression> variables;

    private Interpreter(ELContext context, Map<String, ValueExpression> variables) {
        this.context = context;
        this.variables = variables;
    }

    public static Object evaluate(Template template) {
        return evaluate(template, null, Map.of());
    }

    public static Object evaluate(Template template, ELContext context, Map<String, ValueExpression> variables) {
        return new Interpreter(context, variables).template(template);
    }

    private Object template(Template template) {
        if (template.parts().size() == 1 && template.parts().getFirst() instanceof Template.Evaluation part) {
            return eval(part.body());
        }
        var result = new StringBuilder();
        for (Template.Part part : template.parts()) {
            switch (part) {
                case Template.Text text -> result.append(text.value());
                case Template.Evaluation expression -> result.append(
                        convert(eval(expression.body()), String.class));
            }
        }
        return result.toString();
    }

    public static Object evaluate(Ast ast) {
        return evaluate(ast, null, Map.of());
    }

    public static Object evaluate(Ast ast, ELContext context, Map<String, ValueExpression> variables) {
        return new Interpreter(context, variables).eval(ast);
    }

    private Object eval(Ast ast) {
        return switch (ast) {
            case Ast.Literal literal -> literal(literal.token());
            case Ast.Identifier identifier -> ResolverAccess.identifier(identifier.name(), context, variables);
            case Ast.Property property -> property(property);
            case Ast.Unary unary -> unary(unary);
            case Ast.Binary binary -> binary(binary);
            case Ast.Conditional conditional -> convert(eval(conditional.condition()), boolean.class)
                    ? eval(conditional.whenTrue()) : eval(conditional.whenFalse());
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

    private Object unary(Ast.Unary unary) {
        // Parse the signed literal directly: Long.MIN_VALUE's magnitude does not fit in Long.
        if (unary.operator().kind() == TokenKind.MINUS && unary.operand() instanceof Ast.Literal literal
                && literal.token().kind() == TokenKind.INTEGER) {
            try {
                return Long.valueOf("-" + literal.token().text());
            } catch (NumberFormatException error) {
                throw new ELException("Invalid numeric literal at offset " + literal.token().start(), error);
            }
        }
        Object value = eval(unary.operand());
        return switch (unary.operator().kind()) {
            case MINUS -> Arithmetic.negate(value, context);
            case NOT -> !convert(value, boolean.class);
            case EMPTY -> isEmpty(value);
            default -> throw new ELException("Invalid unary operator " + unary.operator().kind());
        };
    }

    private Object binary(Ast.Binary binary) {
        TokenKind operator = binary.operator().kind();
        if (operator == TokenKind.ASSIGN) {
            throw new ELException("Assignment evaluation is not implemented (P3)");
        }
        Object left = eval(binary.left());
        // Logical and conditional operators must not evaluate a discarded branch.
        if (operator == TokenKind.AND) {
            return convert(left, boolean.class)
                    && convert(eval(binary.right()), boolean.class);
        }
        if (operator == TokenKind.OR) {
            return convert(left, boolean.class)
                    || convert(eval(binary.right()), boolean.class);
        }
        Object right = eval(binary.right());
        return switch (operator) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, MODULO -> Arithmetic.binary(operator, left, right, context);
            case CONCAT -> convert(left, String.class) + convert(right, String.class);
            case EQUAL -> Comparison.equal(left, right, context);
            case NOT_EQUAL -> !Comparison.equal(left, right, context);
            case LESS, GREATER, LESS_EQUAL, GREATER_EQUAL -> Comparison.relate(operator, left, right, context);
            case SEMICOLON -> right;
            default -> throw new ELException("Invalid binary operator " + operator);
        };
    }

    private Object property(Ast.Property property) {
        Object base = eval(property.base());
        if (base == null) return null;
        Object key = eval(property.key());
        if (key == null) return null;
        return ResolverAccess.read(context, new ResolverAccess.Location(base, key));
    }

    private <T> T convert(Object value, Class<T> type) {
        return context == null ? Coercions.convert(value, type) : context.convertToType(value, type);
    }

    public static boolean isEmpty(Object value) {
        return value == null || value instanceof String text && text.isEmpty()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty()
                || value.getClass().isArray() && Array.getLength(value) == 0;
    }
}

