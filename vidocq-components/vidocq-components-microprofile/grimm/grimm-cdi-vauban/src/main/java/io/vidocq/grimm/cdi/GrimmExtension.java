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
package io.vidocq.grimm.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.ws.rs.Path;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MicroProfile OpenAPI 4.1 integration as a CDI 4.1
 * {@link BuildCompatibleExtension Build-Compatible Extension}.
 *
 * <p>During the {@code @Enhancement} phase, every class annotated with
 * {@link Path @Path} (typically JAX-RS resource beans) is recorded by its fully-qualified
 * name. The collected set is exposed to the runtime side via {@link #discoveredTypes()}
 * so that the {@link GrimmModelCache} can feed them to the annotation scanner at
 * startup.</p>
 *
 * <p>The BCE itself performs no scanning — it merely tells CDI which classes are
 * candidates. The actual model build pipeline (spec §4.4 — static file → reader →
 * annotation scan → merge → config-apply → filter) runs inside
 * {@link io.vidocq.grimm.internal.ModelBuilder} when the model cache is instantiated.</p>
 *
 * <p>Registered as a JPMS service in {@code module-info.java}:
 * {@code provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
 * with io.vidocq.grimm.cdi.GrimmExtension;}</p>
 */
public final class GrimmExtension implements BuildCompatibleExtension {

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Set<String> DISCOVERED_NAMES = new LinkedHashSet<>();

    /**
     * One container boot = one deployment: drop everything collected by a
     * previous boot in the same JVM (sequential Arquillian TCK deployments)
     * before this deployment's {@code @Enhancement} phase runs.
     */
    @jakarta.enterprise.inject.build.compatible.spi.Discovery
    public void resetForNewDeployment(jakarta.enterprise.inject.build.compatible.spi.ScannedClasses classes) {
        LOCK.lock();
        try {
            DISCOVERED_NAMES.clear();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Records every application class found by the CDI scanner — spec §4.4: the
     * annotation scan covers <em>all application classes</em>, so {@code @Schema}
     * POJOs and the {@code @OpenAPIDefinition} {@code Application} subclass
     * contribute to the model, not only {@link Path @Path} resources. Grimm's
     * own classes are skipped: the /openapi endpoint itself must not be listed
     * in the document it serves (spec §2.2).
     */
    @Enhancement(types = Object.class, withSubtypes = true)
    public void registerApplicationClass(ClassConfig classConfig) {
        String name = classConfig.info().name();
        if (name.startsWith("io.vidocq.grimm.")) {
            return;
        }
        LOCK.lock();
        try {
            DISCOVERED_NAMES.add(name);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Returns the resolved {@link Class} objects for all class names recorded during
     * the BCE {@code @Enhancement} phase. Class loading uses the thread context
     * classloader (Vauban deployment classloader at runtime).
     *
     * <p>Unresolvable names are silently skipped — they shouldn't happen in practice
     * since they were observable by the BCE.</p>
     */
    public static Set<Class<?>> discoveredTypes() {
        Set<String> snapshot;
        LOCK.lock();
        try {
            snapshot = new LinkedHashSet<>(DISCOVERED_NAMES);
        } finally {
            LOCK.unlock();
        }
        Set<Class<?>> resolved = new LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = GrimmExtension.class.getClassLoader();
        for (String name : snapshot) {
            try {
                resolved.add(Class.forName(name, false, cl));
            } catch (ClassNotFoundException ignored) {
                // Skip: the class is no longer visible (e.g. test isolation).
            }
        }
        return resolved;
    }

    /** Test hook — clears the collected names. Not for production use. */
    static void resetForTesting() {
        LOCK.lock();
        try {
            DISCOVERED_NAMES.clear();
        } finally {
            LOCK.unlock();
        }
    }

    /** Test hook — manually injects discovered class names. */
    static void recordForTesting(String name) {
        LOCK.lock();
        try {
            DISCOVERED_NAMES.add(name);
        } finally {
            LOCK.unlock();
        }
    }
}

