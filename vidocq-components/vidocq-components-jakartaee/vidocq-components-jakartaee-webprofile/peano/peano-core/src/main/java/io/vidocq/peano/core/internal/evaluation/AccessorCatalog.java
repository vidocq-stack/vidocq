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
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.el.ELClass;
import jakarta.el.ELException;
import io.vidocq.peano.spi.AccessorProvider;
import io.vidocq.peano.spi.AccessorTable;

/**
 * The accessor tables of one application and their supertype lookup cache (EL-019).
 *
 * <p>Immutable once built, apart from the ConcurrentHashMap of matching tables per runtime class,
 * so one catalog serves every context and resolver of a factory concurrently. The lookup order is
 * the one documented on GeneratedAccessorELResolver.
 */
public final class AccessorCatalog {
    private final Map<Class<?>, AccessorTable> tables;
    /** Matching tables per runtime class, in lookup order; an empty list means none. */
    private final ConcurrentHashMap<Class<?>, List<AccessorTable>> matching = new ConcurrentHashMap<>();

    public AccessorCatalog(Collection<? extends AccessorTable> accessors) {
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

    /** The tables contributed by the AccessorProvider services visible from a class loader. */
    public static AccessorCatalog load(ClassLoader loader) {
        var accessors = new ArrayList<AccessorTable>();
        try {
            for (AccessorProvider provider : ServiceLoader.load(AccessorProvider.class, loader)) {
                Collection<? extends AccessorTable> contributed = provider.accessors();
                if (contributed == null) throw new ELException("Accessor provider returned null tables");
                accessors.addAll(contributed);
            }
        } catch (ServiceConfigurationError | RuntimeException error) {
            if (error instanceof ELException exception) throw exception;
            throw new ELException("Cannot load application accessor tables", error);
        }
        return new AccessorCatalog(accessors);
    }

    /** The table registered for exactly this type, or null. */
    AccessorTable exact(Class<?> type) {
        return tables.get(type);
    }

    /** The tables matching a base, in lookup order; an ELClass base matches its exact class only. */
    List<AccessorTable> matching(Object base) {
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
        collectSuperclassTables(runtime, found);
        collectInterfaceTables(runtime, found);
        return List.copyOf(found);
    }

    private void collectSuperclassTables(Class<?> runtime, List<AccessorTable> found) {
        for (Class<?> type = runtime; type != null && (type == runtime || type != Object.class); type = type.getSuperclass()) {
            AccessorTable table = tables.get(type);
            if (table != null) found.add(table);
        }
    }

    private void collectInterfaceTables(Class<?> runtime, List<AccessorTable> found) {
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
    }
}
