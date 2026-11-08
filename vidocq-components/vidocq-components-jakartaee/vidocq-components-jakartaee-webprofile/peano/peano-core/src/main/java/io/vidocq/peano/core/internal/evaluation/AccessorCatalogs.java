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

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The accessor catalog of each class loader, loaded once and dropped with the loader.
 *
 * <p>The Jakarta EL API keeps the first expression factory in a static field of its own, for the life of the JVM,
 * while {@code vidocq dev} boots a fresh application layer (a new class loader, new table classes) on every hot
 * reload. A catalog holds the tables of its layer, and so its classes and its loader: a map keyed by the loader would
 * keep the loader alive through its own value (a weak key does not help when the value reaches the key), and the
 * factory would pin every layer it was ever asked about (EL-023).
 *
 * <p>A catalog that holds a class its loader defines is therefore kept by that class, in a {@link ClassValue}: it is
 * reachable from the layer alone, and goes with it. Only a weak, class-only link from the loader to that class is
 * held statically. A catalog whose tables all come from ancestors of the loader reaches nothing of the layer, and is
 * kept against the loader directly. This relies on a layer's tables being defined by the layer's own loader, which is
 * what the module layer and the class path both give.
 */
public final class AccessorCatalogs {
    /** The class that anchors the catalog of a loader; weakly held, so it is the layer that keeps it. */
    private static final Map<ClassLoader, WeakReference<Class<?>>> ANCHORS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The catalogs that hold nothing of their loader. */
    private static final Map<ClassLoader, AccessorCatalog> INDEPENDENT = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Hands the catalog just loaded to the {@link ClassValue}, whose computation only knows the anchor class: keyed
     * by that class, held only while {@link #of} computes it (AGENTS.md: no {@code ThreadLocal}).
     */
    private static final ConcurrentMap<Class<?>, AccessorCatalog> PENDING = new ConcurrentHashMap<>();
    private static final ClassValue<AccessorCatalog> ANCHORED = new ClassValue<>() {
        @Override
        protected AccessorCatalog computeValue(Class<?> anchor) {
            AccessorCatalog pending = PENDING.get(anchor);
            return pending != null ? pending : AccessorCatalog.load(anchor.getClassLoader());
        }
    };

    private AccessorCatalogs() {
    }

    /**
     * The catalog of the tables visible from {@code loader}, loaded on first use and shared by every caller after.
     *
     * @param loader a class loader, usually the context class loader; null for the system class loader
     * @return its catalog
     */
    public static AccessorCatalog of(ClassLoader loader) {
        WeakReference<Class<?>> link = ANCHORS.get(loader);
        Class<?> anchor = link == null ? null : link.get();
        if (anchor != null) return ANCHORED.get(anchor);
        AccessorCatalog independent = INDEPENDENT.get(loader);
        if (independent != null) return independent;
        AccessorCatalog loaded = AccessorCatalog.load(loader);
        Class<?> owned = loaded.definedBy(loader);
        if (owned == null) {
            // A race loads twice; both catalogs are equivalent and the first one published wins.
            AccessorCatalog raced = INDEPENDENT.putIfAbsent(loader, loaded);
            return raced != null ? raced : loaded;
        }
        PENDING.put(owned, loaded);
        try {
            AccessorCatalog winner = ANCHORED.get(owned);
            ANCHORS.put(loader, new WeakReference<>(owned));
            return winner;
        } finally {
            PENDING.remove(owned, loaded);
        }
    }
}
