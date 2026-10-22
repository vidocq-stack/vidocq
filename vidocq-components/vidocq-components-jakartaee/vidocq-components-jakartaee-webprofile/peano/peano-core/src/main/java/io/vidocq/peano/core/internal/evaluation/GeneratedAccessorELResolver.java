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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ELClass;
import jakarta.el.MethodReference;
import io.vidocq.peano.spi.AccessorProvider;
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
 * list is cached per runtime class, an empty list included. An ELClass base (static access) uses the
 * exact class only, as static members are not inherited through EL, and convertToType keeps its
 * exact-target lookup for functional interfaces.
 *
 * <p>A built-in Map.Entry table is always registered, in both reflection modes, and so runs ahead of
 * the API BeanELResolver. An application table keyed on Map.Entry replaces it; tables found earlier in
 * the lookup order take precedence for what they resolve, and unresolved operations fall through.
 * Two application tables for one type remain a conflict.
 */
public final class GeneratedAccessorELResolver extends ELResolver {
    private final Map<Class<?>, AccessorTable> tables;
    /** Matching tables per runtime class, in lookup order; an empty list means none. */
    private final ConcurrentHashMap<Class<?>, List<AccessorTable>> matching = new ConcurrentHashMap<>();

    public GeneratedAccessorELResolver() {
        this(load());
    }

    public GeneratedAccessorELResolver(Collection<? extends AccessorTable> accessors) {
        if (accessors == null) throw new ELException("Accessor table collection must not be null");
        var registered = new HashMap<Class<?>, AccessorTable>();
        for (AccessorTable table : accessors) {
            if (table == null) throw new ELException("Accessor table must not be null");
            Class<?> type = table.targetType();
            if (type == null) throw new ELException("Accessor target type must not be null");
            if (registered.putIfAbsent(type, table) != null) {
                throw new ELException("Conflicting accessor tables for " + type.getName());
            }
        }
        registered.putIfAbsent(Map.Entry.class, new MapEntryAccessorTable());
        tables = Map.copyOf(registered);
    }

    private static Collection<AccessorTable> load() {
        var accessors = new ArrayList<AccessorTable>();
        try {
            for (AccessorProvider provider : ServiceLoader.load(AccessorProvider.class)) {
                Collection<? extends AccessorTable> contributed = provider.accessors();
                if (contributed == null) throw new ELException("Accessor provider returned null tables");
                accessors.addAll(contributed);
            }
        } catch (ServiceConfigurationError | RuntimeException error) {
            if (error instanceof ELException exception) throw exception;
            throw new ELException("Cannot load application accessor tables", error);
        }
        return accessors;
    }

    private List<AccessorTable> tables(ELContext context, Object base) {
        Objects.requireNonNull(context, "context");
        bind(context);
        if (base == null) return List.of();
        if (base instanceof ELClass type) {
            AccessorTable exact = tables.get(type.getKlass());
            return exact == null ? List.of() : List.of(exact);
        }
        Class<?> runtime = base.getClass();
        List<AccessorTable> found = matching.get(runtime);
        if (found == null) {
            found = lookup(runtime);
            List<AccessorTable> raced = matching.putIfAbsent(runtime, found);
            if (raced != null) found = raced;
        }
        return found;
    }

    /** The cached matching tables of a runtime class, or null before its first lookup (tests). */
    List<AccessorTable> cached(Class<?> runtime) {
        return matching.get(runtime);
    }

    private List<AccessorTable> lookup(Class<?> runtime) {
        var found = new ArrayList<AccessorTable>();
        for (Class<?> type = runtime; type != null && (type == runtime || type != Object.class); type = type.getSuperclass()) {
            AccessorTable table = tables.get(type);
            if (table != null) found.add(table);
        }
        // Breadth-first: a class contributes its interfaces, then its superclass; an interface its parents.
        var queue = new ArrayDeque<Class<?>>();
        var visited = new HashSet<Class<?>>();
        queue.add(runtime);
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (type.isInterface()) {
                AccessorTable table = tables.get(type);
                if (table != null) found.add(table);
            }
            for (Class<?> parent : type.getInterfaces()) if (visited.add(parent)) queue.add(parent);
            Class<?> superclass = type.getSuperclass();
            if (superclass != null && superclass != Object.class) queue.add(superclass);
        }
        return List.copyOf(found);
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
        AccessorTable table = tables.get(type);
        if (table != null) {
            T converted=table.convertToType(context,value,type);
            if (context.isPropertyResolved()) return converted;
        }
        return StandardFunctionalAdapters.adapt(context,value,type);
    }

    /** Factory coercion has no caller context; adapters retain the lambda's own context. */
    public static <T> T adapt(jakarta.el.LambdaExpression value, Class<T> type) {
        AccessorTable table = new GeneratedAccessorELResolver().tables.get(type);
        return table == null ? null : table.convertToType(null, value, type);
    }

    /** Generated metadata is context-owned; lazy service discovery also supports custom contexts. */
    public static MethodReference reference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        for (AccessorTable table : catalog(context).tables(context, base)) {
            MethodReference reference = table.getMethodReference(context, base, method, types, arguments);
            if (reference != null) return reference;
        }
        return null;
    }

    /** Direct captured-function dispatch; the ordinary resolution flag distinguishes a null result. */
    public static Object invokeRegistered(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        return catalog(context).invokeTables(context, base, method, types, arguments);
    }

    private static GeneratedAccessorELResolver catalog(ELContext context) {
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
