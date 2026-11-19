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
package io.vidocq.peano.spi;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The registered {@link ProxyUnwrapper}s and their cached answers. The engine registers the unwrappers it finds
 * when it loads the accessor tables; generated tables ask {@link #proxiedClass(Class)} for a base whose class is
 * not their target exactly.
 *
 * <p>This class outlives every application layer of {@code vidocq dev}, and a layer may bring an unwrapper of its
 * own. So it holds no unwrapper itself: each one is held by its own class and is collected with the class loader
 * that defined it (EL-025).</p>
 */
public final class ProxyUnwrappers {
    /** The registered unwrapper of a class, held by that class alone: it lives as long as its class loader. */
    private static final ClassValue<AtomicReference<ProxyUnwrapper>> REGISTERED = new ClassValue<>() {
        @Override
        protected AtomicReference<ProxyUnwrapper> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };
    /** The registered unwrappers, in registration order; a reference is cleared once its class loader is gone. */
    private static final List<WeakReference<ProxyUnwrapper>> UNWRAPPERS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<ClassValue<Class<?>>> ANSWERS = new AtomicReference<>(newCache());

    private ProxyUnwrappers() {}

    /**
     * Registers an unwrapper once (by class); the cached answers are dropped. The unwrapper stays registered as
     * long as the class loader of its class lives, whether or not the caller keeps it.
     *
     * @param unwrapper a stateless unwrapper
     */
    public static void register(ProxyUnwrapper unwrapper) {
        if (unwrapper == null) throw new IllegalArgumentException("Proxy unwrapper must not be null");
        if (!REGISTERED.get(unwrapper.getClass()).compareAndSet(null, unwrapper)) return;
        UNWRAPPERS.removeIf(reference -> reference.get() == null);
        UNWRAPPERS.add(new WeakReference<>(unwrapper));
        ANSWERS.set(newCache());
    }

    /**
     * Returns the class a proxy class stands for.
     *
     * @param runtime a runtime class
     * @return the proxied class, or null when no registered unwrapper knows {@code runtime} as a proxy
     */
    public static Class<?> proxiedClass(Class<?> runtime) {
        return runtime == null || UNWRAPPERS.isEmpty() ? null : ANSWERS.get().get(runtime);
    }

    private static ClassValue<Class<?>> newCache() {
        return new ClassValue<>() {
            @Override
            protected Class<?> computeValue(Class<?> type) {
                for (WeakReference<ProxyUnwrapper> reference : UNWRAPPERS) {
                    ProxyUnwrapper unwrapper = reference.get();
                    Class<?> proxied = unwrapper == null ? null : unwrapper.proxiedClass(type);
                    if (proxied != null && proxied != type && proxied.isAssignableFrom(type)) return proxied;
                }
                return null;
            }
        };
    }
}
