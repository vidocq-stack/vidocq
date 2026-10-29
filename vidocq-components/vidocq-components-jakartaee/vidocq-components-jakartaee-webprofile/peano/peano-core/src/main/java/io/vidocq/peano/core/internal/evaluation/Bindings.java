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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.Collection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.FunctionMapper;
import jakarta.el.ValueExpression;
import jakarta.el.VariableMapper;
import io.vidocq.peano.core.internal.ExpressionAnalysis;
import io.vidocq.peano.core.internal.syntax.Ast;

/** Parse-time variable mappings, independent of later mapper mutations (EL 6.0 section 1.19). */
public final class Bindings {
    private Bindings() {
    }

    /** Captures caller-owned values from ordered syntax descriptors, never from a shared context. */
    public static Map<String, ValueExpression> capture(ExpressionAnalysis analysis, ELContext context) {
        VariableMapper mapper = context.getVariableMapper();
        if (mapper == null || analysis.variableNames().isEmpty()) return Map.of();
        if (analysis.variableNames().size() == 1) {
            String name = analysis.variableNames().getFirst();
            ValueExpression expression = mapper.resolveVariable(name);
            return expression == null ? Map.of() : Map.of(name, expression);
        }
        Map<String, ValueExpression> variables = null;
        for (String name : analysis.variableNames()) {
            ValueExpression expression = mapper.resolveVariable(name);
            if (expression != null) {
                if (variables == null) variables = new HashMap<>();
                variables.put(name, expression);
            }
        }
        return variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** Retains a separate function phase and one mapper access for every evaluation part. */
    public static Map<String, Method> captureFunctions(ExpressionAnalysis analysis, ELContext context) {
        Map<String, Method> functions = null;
        for (var part : analysis.functionsByPart()) {
            FunctionMapper mapper = context.getFunctionMapper();
            for (var name : part) {
                Method method = resolveFunction(name.prefix(), name.name(), name.key(), mapper);
                if (method != null) {
                    if (functions == null) functions = new HashMap<>();
                    functions.put(name.key(), method);
                }
            }
        }
        return functions == null ? Map.of() : Map.copyOf(functions);
    }

    private static void visit(Ast ast, VariableMapper mapper, Map<String, ValueExpression> variables,
            Set<String> visited, Collection<String> locals) {
        switch (ast) {
            case Ast.Identifier(String name) -> {
                if (!locals.contains(name) && visited.add(name)) {
                    ValueExpression expression = mapper.resolveVariable(name);
                    if (expression != null) variables.put(name, expression);
                }
            }
            case Ast.Literal _ -> {
                // A literal references no variable.
            }
            case Ast.Unary(_, Ast operand) -> visit(operand, mapper, variables, visited, locals);
            case Ast.Binary(_, Ast left, Ast right) -> {
                visit(left, mapper, variables, visited, locals);
                visit(right, mapper, variables, visited, locals);
            }
            case Ast.Conditional(Ast condition, Ast whenTrue, Ast whenFalse) -> {
                visit(condition, mapper, variables, visited, locals);
                visit(whenTrue, mapper, variables, visited, locals);
                visit(whenFalse, mapper, variables, visited, locals);
            }
            case Ast.Property(Ast base, Ast key) -> {
                visit(base, mapper, variables, visited, locals);
                visit(key, mapper, variables, visited, locals);
            }
            case Ast.Call(Ast target, List<Ast> arguments) -> {
                visit(target, mapper, variables, visited, locals);
                arguments.forEach(a -> visit(a, mapper, variables, visited, locals));
            }
            case Ast.Function(_, _, List<Ast> arguments) ->
                    arguments.forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.Lambda(List<String> parameters, Ast body) -> {
                var nested = new HashSet<>(locals);
                nested.addAll(parameters);
                visit(body, mapper, variables, visited, nested);
            }
            case Ast.ListLiteral(List<Ast> elements) -> elements.forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.SetLiteral(List<Ast> elements) -> elements.forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.MapLiteral(List<Ast.Entry> entries) -> entries.forEach(entry -> {
                visit(entry.key(), mapper, variables, visited, locals);
                visit(entry.value(), mapper, variables, visited, locals);
            });
        }
    }

    public static String functionKey(String prefix, String name) {
        return prefix + ":" + name;
    }

    public static Map<String, Object> captureLambdaArguments(Ast.Lambda lambda, ELContext context) {
        var mapper = new LambdaCapture(context);
        Collection<String> parameters = lambda.parameters().size() <= 2
                ? lambda.parameters() : new HashSet<>(lambda.parameters());
        visit(lambda.body(), mapper, Map.of(), new HashSet<>(), parameters);
        return mapper.captured.isEmpty() ? Map.of() : Collections.unmodifiableMap(mapper.captured);
    }

    private static final class LambdaCapture extends VariableMapper {
        private final ELContext context;
        private Map<String, Object> captured = Map.of();

        private LambdaCapture(ELContext context) { this.context = context; }

        @Override
        public ValueExpression resolveVariable(String name) {
            if (context.isLambdaArgument(name)) {
                if (captured.isEmpty()) captured = new HashMap<>();
                captured.put(name, context.getLambdaArgument(name));
            }
            return null;
        }

        @Override
        public ValueExpression setVariable(String name, ValueExpression expression) {
            throw new UnsupportedOperationException("Capture mapper is read-only");
        }
    }

    private static Method resolveFunction(String prefix, String name, String key, FunctionMapper mapper) {
        Method method = mapper == null ? null : mapper.resolveFunction(prefix, name);
        if (method == null) {
            if (!prefix.isEmpty()) throw new ELException("Unmapped function " + key);
            return null;
        }
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                || !Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            throw new ELException("Function must reference a public static method: " + key);
        }
        return method;
    }
}

