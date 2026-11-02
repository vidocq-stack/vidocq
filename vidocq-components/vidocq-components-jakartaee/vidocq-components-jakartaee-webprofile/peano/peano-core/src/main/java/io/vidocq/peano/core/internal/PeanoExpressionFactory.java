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
import io.vidocq.peano.core.internal.evaluation.AccessorCatalog;
import io.vidocq.peano.core.internal.evaluation.AccessorCatalogs;
import io.vidocq.peano.core.internal.evaluation.Coercions;
import io.vidocq.peano.core.internal.evaluation.StreamELResolver;
import io.vidocq.peano.core.internal.evaluation.GeneratedAccessorELResolver;
import io.vidocq.peano.core.internal.evaluation.ReflectionGuard;

/** Jakarta EL provider, discovered through the core module's service declaration. */
public final class PeanoExpressionFactory extends ExpressionFactory {
    private final ParsedExpressionCache expressions = new ParsedExpressionCache();
    /**
     * A catalog fixed at construction (tests); otherwise null, and the catalog is the calling thread's application's.
     * The factory holds nothing of any application: the Jakarta EL API keeps the first one for the life of the JVM,
     * across the application layers {@code vidocq dev} discards on a hot reload (EL-023).
     */
    private final AccessorCatalog fixed;

    public PeanoExpressionFactory() {
        this.fixed = null;
        ReflectionGuard.denied();
    }

    /** A factory whose catalog is already loaded (tests). */
    PeanoExpressionFactory(AccessorCatalog accessors) {
        this.fixed = accessors;
        ReflectionGuard.denied();
    }

    /**
     * The accessor catalog for the calling thread's application, loaded once per context class loader and shared by
     * every context (EL-019). A context class loader may see other providers than another's, so each gets its own.
     */
    private AccessorCatalog accessors() {
        return fixed != null ? fixed : AccessorCatalogs.of(Thread.currentThread().getContextClassLoader());
    }

    @Override
    public ELResolver getStreamELResolver() {
        var resolver = new CompositeELResolver();
        resolver.add(new GeneratedAccessorELResolver(accessors()));
        resolver.add(new StreamELResolver());
        resolver.add(new ReflectionGuard());
        return resolver;
    }

    @Override
    public ValueExpression createValueExpression(ELContext context, String expression, Class<?> expectedType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(expectedType, "expectedType");
        return PeanoValueExpression.text(expression, expectedType, context, expressions.parse(expression), this::accessors);
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
        return new PeanoMethodExpression(context, expression, expectedReturnType, expectedParamTypes, expressions.parse(expression),
                this::accessors);
    }

    @Override
    public <T> T coerceToType(Object value, Class<T> targetType) {
        return Coercions.convert(value, targetType, this::accessors);
    }
}

