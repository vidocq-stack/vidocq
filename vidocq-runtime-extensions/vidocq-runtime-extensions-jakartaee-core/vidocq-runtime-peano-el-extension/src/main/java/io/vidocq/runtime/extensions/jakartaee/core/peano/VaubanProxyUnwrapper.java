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
package io.vidocq.runtime.extensions.jakartaee.core.peano;

import io.vidocq.peano.spi.ProxyUnwrapper;

/**
 * Names the bean class a Vauban client proxy stands for: Vauban generates {@code <Bean>_ClientProxy}, a subclass of
 * the bean class in its package that delegates every call to the contextual instance. With it, the accessor table
 * Peano generated for a normal-scoped {@code @Named} bean serves the proxy EL receives, so a page reads the bean
 * without reflection.
 */
public final class VaubanProxyUnwrapper implements ProxyUnwrapper {
    private static final String SUFFIX = "_ClientProxy";

    /** Creates the unwrapper; ServiceLoader calls it. */
    public VaubanProxyUnwrapper() {
        // Stateless.
    }

    @Override
    public Class<?> proxiedClass(Class<?> runtime) {
        Class<?> parent = runtime.getSuperclass();
        if (parent == null || parent == Object.class || !runtime.getName().endsWith(SUFFIX)
                || !runtime.getPackageName().equals(parent.getPackageName())) {
            return null;
        }
        return parent;
    }
}
