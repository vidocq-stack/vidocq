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
package io.vidocq.peano.core.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodExpression;
import jakarta.el.MethodInfo;
import jakarta.el.MethodNotFoundException;
import jakarta.el.MethodReference;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.evaluation.AccessorCatalog;
import io.vidocq.peano.core.internal.evaluation.Bindings;
import io.vidocq.peano.core.internal.evaluation.Interpreter;
import io.vidocq.peano.core.internal.evaluation.MethodMetadata;
import io.vidocq.peano.core.internal.evaluation.GeneratedAccessorELResolver;
import io.vidocq.peano.core.internal.evaluation.ResolverAccess;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;

/** Method expression with parse-time bindings and API-owned method invocation. */
final class PeanoMethodExpression extends MethodExpression {
    private static final long serialVersionUID = 1L;
    private final String source;
    private final Template template;
    private final Template identity;
    private final Ast body;
    private final Class<?> expectedReturnType;
    private final Class<?>[] expectedParameterTypes;
    private final Map<String, ValueExpression> variables;
    private final Map<String, Method> functions;
    private final boolean parametersProvided;
    /** The creating factory's accessor catalog; absent after deserialization (EL-019). */
    private final transient Supplier<AccessorCatalog> accessors;

    private record Resolved(ResolverAccess.Location location, Class<?>[] types, Object[] arguments) {
    }

    PeanoMethodExpression(ELContext context, String source, Class<?> expectedReturnType, Class<?>[] expectedTypes) {
        this(context, source, expectedReturnType, expectedTypes, ExpressionAnalysis.parse(source), null);
    }

    PeanoMethodExpression(ELContext context, String source, Class<?> expectedReturnType, Class<?>[] expectedTypes,
            ExpressionAnalysis analysis, Supplier<AccessorCatalog> accessors) {
        this.accessors = accessors;
        this.source = source;
        this.template = analysis.template();
        this.identity = analysis.identity();
        this.body = template.parts().size() == 1 && template.parts().getFirst() instanceof Template.Evaluation(Ast evaluated)
                ? evaluated : null;
        boolean literal = template.kind() == Template.Kind.LITERAL;
        if (!literal && !(body instanceof Ast.Identifier || body instanceof Ast.Property
                || body instanceof Ast.Call call && call.target() instanceof Ast.Property)) {
            throw new ELException("Method expression must reference a method or a single variable");
        }
        parametersProvided = body instanceof Ast.Call;
        if (!parametersProvided) Objects.requireNonNull(expectedTypes, "expectedParamTypes");
        this.expectedReturnType = expectedReturnType;
        this.expectedParameterTypes = parametersProvided ? null : expectedTypes.clone();
        variables = Bindings.capture(analysis, context);
        functions = Bindings.captureFunctions(analysis, context);
    }

    @Override
    public Object invoke(ELContext context, Object[] parameters) {
        Objects.requireNonNull(context, "context");
        GeneratedAccessorELResolver.prepare(context, accessors);
        return invokeChecked(context, parameters, List.of());
    }

    private Object invokeChecked(ELContext context, Object[] parameters, List<Class<?>> inheritedReturns) {
        context.notifyBeforeEvaluation(source);
        Object result = invokeBody(context, parameters, inheritedReturns);
        context.notifyAfterEvaluation(source);
        return result;
    }

    private Object invokeBody(ELContext context, Object[] parameters, List<Class<?>> inheritedReturns) {
        var returns = new ArrayList<>(inheritedReturns);
        if (expectedReturnType != null) returns.add(expectedReturnType);
        if (isLiteralText()) {
            if (expectedReturnType == void.class) throw new ELException("Literal method expression cannot return void");
            Class<?> literalType = expectedReturnType == null ? String.class : expectedReturnType;
            returns.forEach(type -> MethodMetadata.checkReturn(type, literalType));
            Object value = Interpreter.evaluate(template, context, variables, functions);
            return expectedReturnType == null ? value : context.convertToType(value, expectedReturnType);
        }
        if (body instanceof Ast.Identifier) {
            MethodExpression delegate = delegate(context);
            if (delegate instanceof PeanoMethodExpression peano) return peano.invokeChecked(context, parameters, returns);
            MethodInfo info = delegate.getMethodInfo(context);
            returns.forEach(type -> MethodMetadata.checkReturn(type, info.getReturnType()));
            return delegate.invoke(context, parameters);
        }
        Resolved resolved = resolve(context, parameters);
        if (!returns.isEmpty()) {
            MethodInfo method = metadataInfo(resolved, context);
            returns.forEach(type -> MethodMetadata.checkReturn(type, method.getReturnType()));
        }
        return parametersProvided
                ? ResolverAccess.invoke(context, resolved.location(), resolved.types(), resolved.arguments())
                : ResolverAccess.invokeBare(context, resolved.location(), resolved.types(), resolved.arguments());
    }

    @Override
    public MethodInfo getMethodInfo(ELContext context) {
        Objects.requireNonNull(context, "context");
        GeneratedAccessorELResolver.prepare(context, accessors);
        if (isLiteralText()) return new MethodInfo(source, expectedReturnType == null ? String.class : expectedReturnType,
                expectedParameterTypes.clone());
        if (body instanceof Ast.Identifier) {
            MethodInfo info = delegate(context).getMethodInfo(context);
            MethodMetadata.checkReturn(expectedReturnType, info.getReturnType());
            return info;
        }
        MethodInfo method = metadataInfo(resolve(context, null), context);
        MethodMetadata.checkReturn(expectedReturnType, method.getReturnType());
        return method;
    }

    @Override
    public MethodReference getMethodReference(ELContext context) {
        Objects.requireNonNull(context, "context");
        GeneratedAccessorELResolver.prepare(context, accessors);
        context.notifyBeforeEvaluation(source);
        MethodReference reference = reference(context);
        context.notifyAfterEvaluation(source);
        return reference;
    }

    private MethodReference reference(ELContext context) {
        if (isLiteralText()) return null;
        if (body instanceof Ast.Identifier) {
            MethodReference reference = delegate(context).getMethodReference(context);
            if (reference != null) MethodMetadata.checkReturn(expectedReturnType, reference.getMethodInfo().getReturnType());
            return reference;
        }
        Resolved resolved = resolve(context, null);
        MethodReference reference = metadata(resolved, context);
        MethodMetadata.checkReturn(expectedReturnType, reference.getMethodInfo().getReturnType());
        return reference;
    }

    private static MethodReference metadata(Resolved resolved, ELContext context) {
        MethodReference generated = GeneratedAccessorELResolver.reference(context, resolved.location().base(),
                (String) resolved.location().property(), resolved.types(), resolved.arguments());
        if (generated != null) return generated;
        Method method = find(resolved, context);
        Annotation[] annotations = method.getAnnotations();
        return new MethodReference(resolved.location().base(), info(method), annotations, resolved.arguments());
    }

    private static MethodInfo metadataInfo(Resolved resolved, ELContext context) {
        MethodReference generated = GeneratedAccessorELResolver.reference(context, resolved.location().base(),
                (String) resolved.location().property(), resolved.types(), resolved.arguments());
        return generated != null ? generated.getMethodInfo() : info(find(resolved, context));
    }

    private MethodExpression delegate(ELContext context) {
        Object value = Interpreter.evaluate(body, context, variables, functions);
        if (value instanceof MethodExpression expression) return expression;
        throw new MethodNotFoundException("Variable does not resolve to a MethodExpression");
    }

    private Object writeReplace() {
        return ExpressionSerialization.create(true, false, source, null,
                expectedReturnType, expectedParameterTypes, variables, functions);
    }

    private void readObject(java.io.ObjectInputStream input) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Serialization proxy required");
    }

    private Resolved resolve(ELContext context, Object[] parameters) {
        Ast.Property target = body instanceof Ast.Call call ? (Ast.Property) call.target() : (Ast.Property) body;
        var location = ResolverAccess.property(target, context, variables, functions);
        location = new ResolverAccess.Location(location.base(), context.convertToType(location.property(), String.class));
        if (body instanceof Ast.Call call) {
            Object[] arguments = new Object[call.arguments().size()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = Interpreter.evaluate(call.arguments().get(i), context, variables, functions);
            }
            return new Resolved(location, null, arguments);
        }
        return new Resolved(location, expectedParameterTypes, parameters == null ? new Object[0] : parameters.clone());
    }

    private static Method find(Resolved resolved, ELContext context) {
        return MethodMetadata.find(resolved.location().base(), (String) resolved.location().property(),
                resolved.types(), resolved.arguments(), context);
    }

    private static MethodInfo info(Method method) {
        return new MethodInfo(method.getName(), method.getReturnType(), method.getParameterTypes());
    }

    @Override
    public boolean isParametersProvided() {
        return parametersProvided;
    }

    @Override
    public String getExpressionString() {
        return source;
    }

    @Override
    public boolean isLiteralText() {
        return template.kind() == Template.Kind.LITERAL;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PeanoMethodExpression expression && identity.equals(expression.identity)
                && expectedReturnType == expression.expectedReturnType
                && Arrays.equals(expectedParameterTypes, expression.expectedParameterTypes)
                && variables.equals(expression.variables) && functions.equals(expression.functions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, expectedReturnType, Arrays.hashCode(expectedParameterTypes), variables, functions);
    }
}

