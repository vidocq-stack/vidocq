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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.syntax.Ast;

/** Lambda body adapter retaining lexical lambda arguments, including nullable values. */
final class LambdaBody extends ValueExpression {
    private static final long serialVersionUID = 1L;
    private final Ast body;
    private final Map<String, ValueExpression> variables;
    private final Map<String, Method> functions;
    private final Map<String, Object> captured;

    LambdaBody(Ast.Lambda lambda, ELContext context, Map<String, ValueExpression> variables,
            Map<String, Method> functions) {
        body = lambda.body();
        this.variables = variables;
        this.functions = functions;
        captured = Bindings.captureLambdaArguments(lambda, context);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(ELContext context) {
        Objects.requireNonNull(context, "context");
        context.enterLambdaScope(captured);
        try {
            return (T) Interpreter.evaluate(body, context, variables, functions);
        } finally {
            context.exitLambdaScope();
        }
    }

    @Override
    public void setValue(ELContext context, Object value) {
        throw new PropertyNotWritableException("Lambda body is read-only");
    }

    @Override
    public boolean isReadOnly(ELContext context) {
        return true;
    }

    @Override
    public Class<?> getType(ELContext context) {
        Object value = getValue(context);
        return value == null ? null : value.getClass();
    }

    @Override
    public Class<?> getExpectedType() {
        return Object.class;
    }

    @Override
    public String getExpressionString() {
        return body.toString();
    }

    @Override
    public boolean isLiteralText() {
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LambdaBody expression && body.equals(expression.body)
                && variables.equals(expression.variables) && functions.equals(expression.functions)
                && captured.equals(expression.captured);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body, variables, functions, captured);
    }
}

