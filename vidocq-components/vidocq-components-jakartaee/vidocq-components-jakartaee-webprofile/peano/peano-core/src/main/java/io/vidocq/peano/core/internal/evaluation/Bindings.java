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
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.FunctionMapper;
import jakarta.el.ValueExpression;
import jakarta.el.VariableMapper;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;

/** Parse-time variable mappings, independent of later mapper mutations (EL 6.0 section 1.19). */
public final class Bindings {
    private Bindings() {
    }

    public static Map<String, ValueExpression> capture(Template template, ELContext context) {
        VariableMapper mapper = context.getVariableMapper();
        if (mapper == null) return Map.of();
        var variables = new HashMap<String, ValueExpression>();
        var visited = new HashSet<String>();
        for (Template.Part part : template.parts()) {
            if (part instanceof Template.Evaluation evaluation) {
                visit(evaluation.body(), mapper, variables, visited, Set.of());
            }
        }
        return Map.copyOf(variables);
    }

    private static void visit(Ast ast, VariableMapper mapper, Map<String, ValueExpression> variables,
            Set<String> visited, Set<String> locals) {
        switch (ast) {
            case Ast.Identifier identifier -> {
                if (!locals.contains(identifier.name()) && visited.add(identifier.name())) {
                    ValueExpression expression = mapper.resolveVariable(identifier.name());
                    if (expression != null) variables.put(identifier.name(), expression);
                }
            }
            case Ast.Literal ignored -> { }
            case Ast.Unary unary -> visit(unary.operand(), mapper, variables, visited, locals);
            case Ast.Binary binary -> {
                visit(binary.left(), mapper, variables, visited, locals);
                visit(binary.right(), mapper, variables, visited, locals);
            }
            case Ast.Conditional choice -> {
                visit(choice.condition(), mapper, variables, visited, locals);
                visit(choice.whenTrue(), mapper, variables, visited, locals);
                visit(choice.whenFalse(), mapper, variables, visited, locals);
            }
            case Ast.Property property -> {
                visit(property.base(), mapper, variables, visited, locals);
                visit(property.key(), mapper, variables, visited, locals);
            }
            case Ast.Call call -> {
                visit(call.target(), mapper, variables, visited, locals);
                call.arguments().forEach(a -> visit(a, mapper, variables, visited, locals));
            }
            case Ast.Function function -> function.arguments()
                    .forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.Lambda lambda -> {
                var nested = new HashSet<>(locals);
                nested.addAll(lambda.parameters());
                visit(lambda.body(), mapper, variables, visited, nested);
            }
            case Ast.ListLiteral list -> list.elements().forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.SetLiteral set -> set.elements().forEach(a -> visit(a, mapper, variables, visited, locals));
            case Ast.MapLiteral map -> map.entries().forEach(entry -> {
                visit(entry.key(), mapper, variables, visited, locals);
                visit(entry.value(), mapper, variables, visited, locals);
            });
        }
    }

    public static String functionKey(String prefix, String name) {
        return prefix + ":" + name;
    }

    public static Map<String, Method> captureFunctions(Template template, ELContext context) {
        var functions = new HashMap<String, Method>();
        var visited = new HashSet<String>();
        for (Template.Part part : template.parts()) {
            if (part instanceof Template.Evaluation evaluation) {
                visitFunctions(evaluation.body(), context.getFunctionMapper(), functions, visited, Set.of());
            }
        }
        return Map.copyOf(functions);
    }

    private static void bindFunction(String prefix, String name, FunctionMapper mapper,
            Map<String, Method> functions, Set<String> visited) {
        String key = functionKey(prefix, name);
        if (!visited.add(key)) return;
        Method method = mapper == null ? null : mapper.resolveFunction(prefix, name);
        if (method == null) {
            if (!prefix.isEmpty()) throw new ELException("Unmapped function " + key);
            return;
        }
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                || !Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            throw new ELException("Function must reference a public static method: " + key);
        }
        functions.put(key, method);
    }

    private static void visitFunctions(Ast ast, FunctionMapper mapper, Map<String, Method> functions,
            Set<String> visited, Set<String> locals) {
        switch (ast) {
            case Ast.Literal ignored -> { }
            case Ast.Identifier ignored -> { }
            case Ast.Unary unary -> visitFunctions(unary.operand(), mapper, functions, visited, locals);
            case Ast.Binary binary -> {
                visitFunctions(binary.left(), mapper, functions, visited, locals);
                visitFunctions(binary.right(), mapper, functions, visited, locals);
            }
            case Ast.Conditional choice -> {
                visitFunctions(choice.condition(), mapper, functions, visited, locals);
                visitFunctions(choice.whenTrue(), mapper, functions, visited, locals);
                visitFunctions(choice.whenFalse(), mapper, functions, visited, locals);
            }
            case Ast.Property property -> {
                visitFunctions(property.base(), mapper, functions, visited, locals);
                visitFunctions(property.key(), mapper, functions, visited, locals);
            }
            case Ast.Call call -> {
                if (call.target() instanceof Ast.Identifier identifier && !locals.contains(identifier.name())) {
                    bindFunction("", identifier.name(), mapper, functions, visited);
                }
                visitFunctions(call.target(), mapper, functions, visited, locals);
                call.arguments().forEach(a -> visitFunctions(a, mapper, functions, visited, locals));
            }
            case Ast.Function function -> {
                bindFunction(function.prefix(), function.name(), mapper, functions, visited);
                function.arguments().forEach(a -> visitFunctions(a, mapper, functions, visited, locals));
            }
            case Ast.Lambda lambda -> {
                var nested = new HashSet<>(locals);
                nested.addAll(lambda.parameters());
                visitFunctions(lambda.body(), mapper, functions, visited, nested);
            }
            case Ast.ListLiteral list -> list.elements().forEach(a -> visitFunctions(a, mapper, functions, visited, locals));
            case Ast.SetLiteral set -> set.elements().forEach(a -> visitFunctions(a, mapper, functions, visited, locals));
            case Ast.MapLiteral map -> map.entries().forEach(entry -> {
                visitFunctions(entry.key(), mapper, functions, visited, locals);
                visitFunctions(entry.value(), mapper, functions, visited, locals);
            });
        }
    }
}

