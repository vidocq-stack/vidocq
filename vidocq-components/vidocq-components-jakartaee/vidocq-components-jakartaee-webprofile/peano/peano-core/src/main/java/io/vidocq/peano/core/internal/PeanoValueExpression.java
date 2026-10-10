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
import jakarta.el.ELContext;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.evaluation.Interpreter;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import io.vidocq.peano.core.internal.syntax.Template;

/** Read-only P1 value expression over literal values and operator ASTs. */
final class PeanoValueExpression extends ValueExpression {
    private static final long serialVersionUID = 1L;
    private final Class<?> expectedType;
    private final String source;
    private final Template template;
    private final Object instance;
    private final boolean objectExpression;

    private PeanoValueExpression(Class<?> expectedType, String source, Template template,
            Object instance, boolean objectExpression) {
        this.expectedType = expectedType;
        this.source = source;
        this.template = template;
        this.instance = instance;
        this.objectExpression = objectExpression;
    }

    static PeanoValueExpression text(String source, Class<?> expectedType) {
        return new PeanoValueExpression(expectedType, source, ExpressionParser.parse(source), null, false);
    }

    static PeanoValueExpression object(Object instance, Class<?> expectedType) {
        return new PeanoValueExpression(expectedType, null, null, instance, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(ELContext context) {
        Objects.requireNonNull(context, "context");
        return (T) context.convertToType(rawValue(), expectedType);
    }

    @Override
    public void setValue(ELContext context, Object value) {
        Objects.requireNonNull(context, "context");
        throw new PropertyNotWritableException("This expression is read-only");
    }

    @Override
    public boolean isReadOnly(ELContext context) {
        Objects.requireNonNull(context, "context");
        return true;
    }

    @Override
    public Class<?> getType(ELContext context) {
        Objects.requireNonNull(context, "context");
        Object value = rawValue();
        return value == null ? null : value.getClass();
    }

    private Object rawValue() {
        return objectExpression ? instance : Interpreter.evaluate(template);
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
                && (objectExpression ? Objects.equals(instance, expression.instance)
                        : source.equals(expression.source));
    }

    @Override
    public int hashCode() {
        return Objects.hash(expectedType, objectExpression, objectExpression ? instance : source);
    }
}

