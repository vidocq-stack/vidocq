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
import jakarta.el.ELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.MethodExpression;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.evaluation.Coercions;
import io.vidocq.peano.core.internal.evaluation.StreamELResolver;
import io.vidocq.peano.core.internal.evaluation.GeneratedAccessorELResolver;

/** Jakarta EL provider, discovered through the core module's service declaration. */
public final class PeanoExpressionFactory extends ExpressionFactory {
    public PeanoExpressionFactory() {
    }

    @Override
    public ELResolver getStreamELResolver() {
        var resolver = new CompositeELResolver();
        resolver.add(new GeneratedAccessorELResolver());
        resolver.add(new StreamELResolver());
        return resolver;
    }

    @Override
    public ValueExpression createValueExpression(ELContext context, String expression, Class<?> expectedType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(expectedType, "expectedType");
        return PeanoValueExpression.text(expression, expectedType, context);
    }

    @Override
    public ValueExpression createValueExpression(Object instance, Class<?> expectedType) {
        return PeanoValueExpression.object(instance, Objects.requireNonNull(expectedType, "expectedType"));
    }

    @Override
    public MethodExpression createMethodExpression(ELContext context, String expression,
            Class<?> expectedReturnType, Class<?>[] expectedParamTypes) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expression, "expression");
        return new PeanoMethodExpression(context, expression, expectedReturnType, expectedParamTypes);
    }

    @Override
    public <T> T coerceToType(Object value, Class<T> targetType) {
        return Coercions.convert(value, targetType);
    }
}

