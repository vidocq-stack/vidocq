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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.MethodReference;
import io.vidocq.peano.spi.AccessorTable;

/**
 * Direct application-table dispatch, with no property/member introspection.
 *
 * <p>An instance base matches the tables registered for, in order:
 * <ol>
 * <li>its exact runtime class;</li>
 * <li>each superclass, nearest first, Object excluded;</li>
 * <li>its interfaces, breadth-first over the type hierarchy: the exact class's own interfaces
 * in declaration order, then the next level, in which the superinterfaces of those interfaces
 * come before the interfaces declared by the direct superclass, and so on. An interface reached
 * twice is visited once.</li>
 * </ol>
 * Each operation tries the matching tables in that order and stops at the first one that resolves
 * it (sets the resolution flag, or returns method metadata); a table that leaves the operation
 * unresolved, such as a generated table that accepts its exact class only, passes it to the next.
 * When none resolves, the operation stays unresolved for the rest of the resolver chain.
 * So a table keyed on a public interface serves anonymous and private implementations. The ordered
 * list is cached per runtime class, an empty list included, in the AccessorCatalog that the
 * resolvers of one factory share. An ELClass base (static access) uses the
 * exact class only, as static members are not inherited through EL, and convertToType keeps its
 * exact-target lookup for functional interfaces.
 *
 * <p>A built-in Map.Entry table is always registered, in both reflection modes, and so runs ahead of
 * the API BeanELResolver. An application table keyed on Map.Entry replaces it; tables found earlier in
 * the lookup order take precedence for what they resolve, and unresolved operations fall through.
 * Two application tables for one type remain a conflict.
 */
public final class GeneratedAccessorELResolver extends ELResolver {
    private final AccessorCatalog catalog;

    /** Loads the tables visible from the thread context class loader. */
    public GeneratedAccessorELResolver() {
        this(AccessorCatalog.load(Thread.currentThread().getContextClassLoader()));
    }

    public GeneratedAccessorELResolver(Collection<? extends AccessorTable> accessors) {
        this(new AccessorCatalog(accessors));
    }

    /** Shares a catalog, and its lookup cache, with the other resolvers of a factory (EL-019). */
    public GeneratedAccessorELResolver(AccessorCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    AccessorCatalog catalog() {
        return catalog;
    }

    private List<AccessorTable> tables(ELContext context, Object base) {
        Objects.requireNonNull(context, "context");
        bind(context);
        return catalog.matching(base);
    }

    /** The cached matching tables of a runtime class, or null before its first lookup (tests). */
    List<AccessorTable> cached(Class<?> runtime) {
        return catalog.cached(runtime);
    }

    private void bind(ELContext context) {
        if (context.getContext(GeneratedAccessorELResolver.class) == null) {
            context.putContext(GeneratedAccessorELResolver.class, this);
        }
    }

    @Override
    public <T> T convertToType(ELContext context, Object value, Class<T> type) {
        Objects.requireNonNull(context, "context");
        bind(context);
        AccessorTable table = catalog.exact(type);
        if (table != null) {
            T converted=table.convertToType(context,value,type);
            if (context.isPropertyResolved()) return converted;
        }
        return StandardFunctionalAdapters.adapt(context,value,type);
    }

    /** Factory coercion has no caller context; adapters retain the lambda's own context. */
    public static <T> T adapt(AccessorCatalog catalog, jakarta.el.LambdaExpression value, Class<T> type) {
        AccessorTable table = catalog.exact(type);
        return table == null ? null : table.convertToType(null, value, type);
    }

    /** Generated metadata is context-owned; lazy service discovery also supports custom contexts. */
    public static MethodReference reference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        for (AccessorTable table : bound(context).tables(context, base)) {
            MethodReference reference = table.getMethodReference(context, base, method, types, arguments);
            if (reference != null) return reference;
        }
        return null;
    }

    /** Direct captured-function dispatch; the ordinary resolution flag distinguishes a null result. */
    public static Object invokeRegistered(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        return bound(context).invokeTables(context, base, method, types, arguments);
    }

    private static GeneratedAccessorELResolver bound(ELContext context) {
        var resolver = (GeneratedAccessorELResolver) context.getContext(GeneratedAccessorELResolver.class);
        if (resolver == null) {
            resolver = new GeneratedAccessorELResolver();
            resolver.bind(context);
        }
        return resolver;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        for (AccessorTable table : tables(context, base)) {
            Object value = table.getValue(context, base, property);
            if (context.isPropertyResolved()) return value;
        }
        return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        for (AccessorTable table : tables(context, base)) {
            Class<?> type = table.getType(context, base, property);
            if (context.isPropertyResolved()) return type;
        }
        return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        for (AccessorTable table : tables(context, base)) {
            table.setValue(context, base, property, value);
            if (context.isPropertyResolved()) return;
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        for (AccessorTable table : tables(context, base)) {
            boolean readOnly = table.isReadOnly(context, base, property);
            if (context.isPropertyResolved()) return readOnly;
        }
        return false;
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        return invokeTables(context, base, method, types, arguments);
    }

    private Object invokeTables(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        for (AccessorTable table : tables(context, base)) {
            Object result = table.invoke(context, base, method, types, arguments);
            if (context.isPropertyResolved()) return result;
        }
        return null;
    }

    /** The first matching table that reports a common property type supplies it. */
    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        for (AccessorTable table : tables(context, base)) {
            Class<?> type = table.getCommonPropertyType(context, base);
            if (type != null) return type;
        }
        return null;
    }
}
