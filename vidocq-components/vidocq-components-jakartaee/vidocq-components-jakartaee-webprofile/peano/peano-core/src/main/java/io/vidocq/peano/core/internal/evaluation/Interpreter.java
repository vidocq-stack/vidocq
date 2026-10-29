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
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import jakarta.el.ELException;
import jakarta.el.ELContext;
import jakarta.el.ValueExpression;
import jakarta.el.ELClass;
import jakarta.el.LambdaExpression;
import jakarta.el.MethodNotFoundException;
import jakarta.el.PropertyNotWritableException;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Reference tree-walking evaluator with context-owned resolver state. */
public final class Interpreter {
    private final ELContext context;
    private final Map<String, ValueExpression> variables;
    private final Map<String, Method> functions;

    private Interpreter(ELContext context, Map<String, ValueExpression> variables, Map<String, Method> functions) {
        this.context = context;
        this.variables = variables;
        this.functions = functions;
    }

    public static Object evaluate(Template template) {
        return evaluate(template, null, Map.of());
    }

    public static Object evaluate(Template template, ELContext context, Map<String, ValueExpression> variables) {
        return evaluate(template, context, variables, Map.of());
    }

    public static Object evaluate(Template template, ELContext context, Map<String, ValueExpression> variables,
            Map<String, Method> functions) {
        return new Interpreter(context, variables, functions).template(template);
    }

    private Object template(Template template) {
        if (template.parts().size() == 1 && template.parts().getFirst() instanceof Template.Evaluation(Ast body)) {
            return eval(body);
        }
        var result = new StringBuilder();
        for (Template.Part part : template.parts()) {
            switch (part) {
                case Template.Text(String value) -> result.append(value);
                case Template.Evaluation(Ast body) -> result.append(
                        convert(eval(body), String.class));
            }
        }
        return result.toString();
    }

    public static Object evaluate(Ast ast) {
        return evaluate(ast, null, Map.of());
    }

    public static Object evaluate(Ast ast, ELContext context, Map<String, ValueExpression> variables) {
        return evaluate(ast, context, variables, Map.of());
    }

    public static Object evaluate(Ast ast, ELContext context, Map<String, ValueExpression> variables,
            Map<String, Method> functions) {
        return new Interpreter(context, variables, functions).eval(ast);
    }

    private Object eval(Ast ast) {
        return switch (ast) {
            case Ast.Literal(Token token) -> literal(token);
            case Ast.Identifier(String name) -> ResolverAccess.identifier(name, context, variables);
            case Ast.Property property -> property(property);
            case Ast.Call call -> call(call);
            case Ast.Function function -> function(function);
            case Ast.ListLiteral(List<Ast> elements) -> list(elements);
            case Ast.SetLiteral(List<Ast> elements) -> new LinkedHashSet<>(list(elements));
            case Ast.MapLiteral map -> map(map);
            case Ast.Lambda lambda -> lambda(lambda);
            case Ast.Unary unary -> unary(unary);
            case Ast.Binary binary -> binary(binary);
            case Ast.Conditional(Ast condition, Ast whenTrue, Ast whenFalse) -> truth(eval(condition))
                    ? eval(whenTrue) : eval(whenFalse);
        };
    }

    private List<Object> list(List<Ast> expressions) {
        var values = new ArrayList<Object>(expressions.size());
        for (Ast expression : expressions) values.add(eval(expression));
        return values;
    }

    private Map<Object, Object> map(Ast.MapLiteral map) {
        var values = new LinkedHashMap<Object, Object>();
        for (Ast.Entry entry : map.entries()) {
            Object key = eval(entry.key());
            Object value = eval(entry.value());
            values.put(key, value);
        }
        return values;
    }

    private LambdaExpression lambda(Ast.Lambda lambda) {
        if (context == null) throw new ELException("Lambda construction requires an ELContext");
        var expression = new LambdaExpression(lambda.parameters(), new LambdaBody(lambda, context, variables, functions));
        expression.setELContext(context);
        return expression;
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
        if (unary.operator().kind() == TokenKind.MINUS && unary.operand() instanceof Ast.Literal(Token token)
                && token.kind() == TokenKind.INTEGER) {
            try {
                return Long.valueOf("-" + token.text());
            } catch (NumberFormatException error) {
                throw new ELException("Invalid numeric literal at offset " + token.start(), error);
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
            Consumer<Object> target = writer(binary.left());
            Object value = eval(binary.right());
            target.accept(value);
            return value;
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
            case EQUAL -> Comparison.isEqual(left, right, context);
            case NOT_EQUAL -> !Comparison.isEqual(left, right, context);
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

    private Object[] arguments(List<Ast> expressions) {
        Object[] arguments = new Object[expressions.size()];
        for (int i = 0; i < arguments.length; i++) arguments[i] = eval(expressions.get(i));
        return arguments;
    }

    private Object function(Ast.Function function) {
        Method method = functions.get(Bindings.functionKey(function.prefix(), function.name()));
        if (method == null) throw new MethodNotFoundException("Unmapped function " + function.name());
        return ResolverAccess.function(context, method, arguments(function.arguments()));
    }

    private Object call(Ast.Call call) {
        if (call.target() instanceof Ast.Property(Ast baseNode, Ast keyNode)) {
            Object base = eval(baseNode);
            if (base == null) return null;
            Object key = eval(keyNode);
            if (key == null) return null;
            return ResolverAccess.invoke(context, new ResolverAccess.Location(base, convert(key, String.class)),
                    null, arguments(call.arguments()));
        }
        if (call.target() instanceof Ast.Identifier(String name)) {
            Object callable = ResolverAccess.callable(name, context, variables);
            if (callable instanceof LambdaExpression lambda) return lambda.invoke(context, arguments(call.arguments()));
            Method method = functions.get(Bindings.functionKey("", name));
            if (method != null) return ResolverAccess.function(context, method, arguments(call.arguments()));
            Class<?> type = context.getImportHandler().resolveClass(name);
            if (type != null) {
                return ResolverAccess.invoke(context, new ResolverAccess.Location(new ELClass(type), "<init>"),
                        null, arguments(call.arguments()));
            }
            type = context.getImportHandler().resolveStatic(name);
            if (type != null) {
                return ResolverAccess.invoke(context, new ResolverAccess.Location(new ELClass(type), name),
                        null, arguments(call.arguments()));
            }
            throw new MethodNotFoundException("Unresolved function " + name);
        }
        Object callable = eval(call.target());
        if (callable instanceof LambdaExpression lambda) return lambda.invoke(context, arguments(call.arguments()));
        throw new MethodNotFoundException("Expression does not resolve to a callable value");
    }

    private Consumer<Object> writer(Ast target) {
        if (context == null) throw new ELException("Assignment requires an ELContext");
        if (target instanceof Ast.Identifier(String name)) {
            if (context.isLambdaArgument(name)) {
                throw new PropertyNotWritableException("Lambda arguments are read-only");
            }
            ValueExpression variable = variables.get(name);
            if (variable != null) {
                var reference = variable.getValueReference(context);
                if (reference != null) {
                    var location = new ResolverAccess.Location(reference.getBase(), reference.getProperty());
                    return value -> ResolverAccess.write(context, location, value);
                }
                if (variable.isReadOnly(context)) throw new PropertyNotWritableException("Variable is read-only");
                return value -> variable.setValue(context, value);
            }
            var location = new ResolverAccess.Location(null, name);
            return value -> ResolverAccess.write(context, location, value);
        }
        if (target instanceof Ast.Property property) {
            var location = ResolverAccess.property(property, context, variables, functions);
            return value -> ResolverAccess.write(context, location, value);
        }
        throw new PropertyNotWritableException("Assignment target is not an lvalue");
    }

    private boolean truth(Object value) {
        return convert(value, boolean.class);
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

