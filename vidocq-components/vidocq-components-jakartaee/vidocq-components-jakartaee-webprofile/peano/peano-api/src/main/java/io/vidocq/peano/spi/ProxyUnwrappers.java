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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The registered {@link ProxyUnwrapper}s and their cached answers. The engine registers the unwrappers it finds
 * when it loads the accessor tables; generated tables ask {@link #proxiedClass(Class)} for a base whose class is
 * not their target exactly.
 */
public final class ProxyUnwrappers {
    private static final List<ProxyUnwrapper> UNWRAPPERS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<ClassValue<Class<?>>> ANSWERS = new AtomicReference<>(newCache());

    private ProxyUnwrappers() {}

    /**
     * Registers an unwrapper once (by class); the cached answers are dropped.
     *
     * @param unwrapper a stateless unwrapper
     */
    public static void register(ProxyUnwrapper unwrapper) {
        if (unwrapper == null) throw new IllegalArgumentException("Proxy unwrapper must not be null");
        for (ProxyUnwrapper registered : UNWRAPPERS) {
            if (registered.getClass() == unwrapper.getClass()) return;
        }
        UNWRAPPERS.add(unwrapper);
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
                for (ProxyUnwrapper unwrapper : UNWRAPPERS) {
                    Class<?> proxied = unwrapper.proxiedClass(type);
                    if (proxied != null && proxied != type && proxied.isAssignableFrom(type)) return proxied;
                }
                return null;
            }
        };
    }
}
