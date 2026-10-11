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

import java.util.Objects;
import java.util.Map;
import java.lang.reflect.Method;
import jakarta.el.ELContext;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;
import io.vidocq.peano.core.internal.evaluation.Bindings;
import io.vidocq.peano.core.internal.evaluation.Interpreter;
import io.vidocq.peano.core.internal.evaluation.ResolverAccess;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;

/** Value expression with immutable parse-time bindings and context-owned resolution. */
final class PeanoValueExpression extends ValueExpression {
    private static final long serialVersionUID = 1L;
    private final Class<?> expectedType;
    private final String source;
    private final Template template;
    private final Template identity;
    private final Object instance;
    private final boolean objectExpression;
    private final Map<String, ValueExpression> variables;
    private final Map<String, Method> functions;

    private PeanoValueExpression(Class<?> expectedType, String source, Template template, Template identity,
            Object instance, boolean objectExpression, Map<String, ValueExpression> variables, Map<String, Method> functions) {
        this.expectedType = expectedType;
        this.source = source;
        this.template = template;
        this.identity = identity;
        this.instance = instance;
        this.objectExpression = objectExpression;
        this.variables = variables;
        this.functions = functions;
    }

    static PeanoValueExpression text(String source, Class<?> expectedType, ELContext context) {
        return text(source, expectedType, context, ExpressionAnalysis.parse(source));
    }

    static PeanoValueExpression text(String source, Class<?> expectedType, ELContext context, ExpressionAnalysis analysis) {
        return new PeanoValueExpression(expectedType, source, analysis.template(), analysis.identity(), null, false,
                Bindings.capture(analysis, context), Bindings.captureFunctions(analysis, context));
    }

    static PeanoValueExpression object(Object instance, Class<?> expectedType) {
        return new PeanoValueExpression(expectedType, null, null, null, instance, true, Map.of(), Map.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(ELContext context) {
        Objects.requireNonNull(context, "context");
        if (!objectExpression) context.notifyBeforeEvaluation(source);
        T value = (T) context.convertToType(rawValue(context), expectedType);
        if (!objectExpression) context.notifyAfterEvaluation(source);
        return value;
    }

    @Override
    public void setValue(ELContext context, Object value) {
        Objects.requireNonNull(context, "context");
        Ast body = body();
        if (lambdaArgument(body, context)) {
            throw new PropertyNotWritableException("Lambda arguments are read-only");
        }
        ValueExpression variable = variable(body);
        if (variable != null) {
            variable.setValue(context, value);
            return;
        }
        ResolverAccess.Location location = location(body, context);
        if (location == null) throw new PropertyNotWritableException("This expression is read-only");
        ResolverAccess.write(context, location, value);
    }

    @Override
    public boolean isReadOnly(ELContext context) {
        Objects.requireNonNull(context, "context");
        Ast body = body();
        if (lambdaArgument(body, context)) return true;
        ValueExpression variable = variable(body);
        if (variable != null) return variable.isReadOnly(context);
        ResolverAccess.Location location = location(body, context);
        return location == null || ResolverAccess.readOnly(context, location);
    }

    @Override
    public Class<?> getType(ELContext context) {
        Objects.requireNonNull(context, "context");
        Ast body = body();
        if (!lambdaArgument(body, context)) {
            ValueExpression variable = variable(body);
            if (variable != null) return variable.getType(context);
            ResolverAccess.Location location = location(body, context);
            if (location != null) return ResolverAccess.type(context, location);
        }
        if (!objectExpression && body == null) return String.class;
        Object value = rawValue(context);
        return value == null ? null : value.getClass();
    }

    @Override
    public ValueReference getValueReference(ELContext context) {
        Objects.requireNonNull(context, "context");
        Ast body = body();
        if (lambdaArgument(body, context)) return null;
        ValueExpression variable = variable(body);
        if (variable != null) return variable.getValueReference(context);
        ResolverAccess.Location location = location(body, context);
        return location == null ? null : ResolverAccess.reference(context, location);
    }

    private Object rawValue(ELContext context) {
        return objectExpression ? instance : Interpreter.evaluate(template, context, variables, functions);
    }

    private Object writeReplace() {
        return ExpressionSerialization.create(false, objectExpression, source, instance,
                expectedType, null, variables, functions);
    }

    private void readObject(java.io.ObjectInputStream input) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Serialization proxy required");
    }

    private Ast body() {
        if (objectExpression || template.parts().size() != 1) return null;
        return template.parts().getFirst() instanceof Template.Evaluation evaluation ? evaluation.body() : null;
    }

    private boolean lambdaArgument(Ast body, ELContext context) {
        return body instanceof Ast.Identifier identifier && context.isLambdaArgument(identifier.name());
    }

    private ValueExpression variable(Ast body) {
        return body instanceof Ast.Identifier identifier ? variables.get(identifier.name()) : null;
    }

    private ResolverAccess.Location location(Ast body, ELContext context) {
        if (body instanceof Ast.Identifier identifier) return new ResolverAccess.Location(null, identifier.name());
        if (body instanceof Ast.Property property) return ResolverAccess.property(property, context, variables, functions);
        return null;
    }

    @Override
    public Class<?> getExpectedType() {
        return expectedType;
    }

    @Override
    public String getExpressionString() {
        return objectExpression ? String.valueOf(instance) : source;
    }

    @Override
    public boolean isLiteralText() {
        return objectExpression || template.kind() == Template.Kind.LITERAL;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PeanoValueExpression expression
                && expectedType == expression.expectedType
                && objectExpression == expression.objectExpression
                && variables.equals(expression.variables)
                && functions.equals(expression.functions)
                && (objectExpression ? Objects.equals(instance, expression.instance)
                        : identity.equals(expression.identity));
    }

    @Override
    public int hashCode() {
        return Objects.hash(expectedType, objectExpression, objectExpression ? instance : identity, variables, functions);
    }
}

