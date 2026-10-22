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
import java.util.concurrent.atomic.AtomicReference;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.MethodExpression;
import jakarta.el.ValueExpression;
import io.vidocq.peano.core.internal.evaluation.AccessorCatalog;
import io.vidocq.peano.core.internal.evaluation.Coercions;
import io.vidocq.peano.core.internal.evaluation.StreamELResolver;
import io.vidocq.peano.core.internal.evaluation.GeneratedAccessorELResolver;
import io.vidocq.peano.core.internal.evaluation.ReflectionGuard;

/** Jakarta EL provider, discovered through the core module's service declaration. */
public final class PeanoExpressionFactory extends ExpressionFactory {
    private final ParsedExpressionCache expressions = new ParsedExpressionCache();
    /** The application this factory was created for, as ExpressionFactory.newInstance resolves it. */
    private final ClassLoader owner = Thread.currentThread().getContextClassLoader();
    /** The owner's accessor tables and lookup cache, loaded on first use and shared by every context (EL-019). */
    private final AtomicReference<AccessorCatalog> accessors = new AtomicReference<>();

    public PeanoExpressionFactory() {
        ReflectionGuard.denied();
    }

    /** A factory whose owner's catalog is already loaded (tests). */
    PeanoExpressionFactory(AccessorCatalog accessors) {
        this();
        this.accessors.set(accessors);
    }

    /**
     * The accessor catalog for the calling thread's application. A context class loader other than
     * the owner's may see other providers, so it gets its own catalog, never the shared one.
     */
    private AccessorCatalog accessors() {
        ClassLoader current = Thread.currentThread().getContextClassLoader();
        if (current != owner) return AccessorCatalog.load(current);
        AccessorCatalog catalog = accessors.get();
        if (catalog == null) {
            // A race loads twice; both catalogs are equivalent and the first one published wins.
            catalog = AccessorCatalog.load(owner);
            if (!accessors.compareAndSet(null, catalog)) catalog = accessors.get();
        }
        return catalog;
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

